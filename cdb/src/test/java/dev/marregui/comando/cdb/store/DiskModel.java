package dev.marregui.comando.cdb.store;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Predicate;

/// What a power cut keeps of the disk under one root folder, fed by [StoreFiles.Recorder]. Strict POSIX:
///
/// - A file is an inode with durable and volatile bytes. `wrote` and `truncated` change the volatile bytes (a write or
///   a truncation past the end extends the file with zeros); `forcing` copies the volatile bytes to the durable ones.
///   Forcing a file does not make its name durable.
/// - A folder is an inode with durable and volatile maps of names to inodes. `created`, `moved` and `deleted` change its
///   volatile map (a rename within one folder is one change, so a crash keeps the old name or the new, never neither);
///   `folderForcing` copies the volatile map to the durable one. Forcing a folder does not make its files' bytes
///   durable. An inode lives while a map names it, so a file deleted or renamed over keeps its durable bytes under its
///   durable name until the folder is forced.
/// - The root exists and is durable from the start, empty unless the model comes from [#afterKill]; operations on paths
///   outside it are ignored, and an operation on a path the model never saw created under it is refused (a store write
///   that bypassed the recorder).
/// - Paths resolve through the volatile maps, as the running process sees them.
///
/// The images, each a map from a path under the root, `/`-separated, to the file's bytes, or to null for a folder;
/// [#write] puts one on disk:
///
/// - strict: the durable state alone, reached from the root through durable maps.
/// - adversarial (seeded): the durable state, plus, per folder, each of its entry changes since its last force with
///   probability 1/2, in order; plus, per file, the first `m` of its writes and truncations since its last force, `m`
///   uniform in `0..n`; when `m < n` and the next one is a write, a uniform prefix of its bytes lands too, and with
///   probability 1/2 the file's size reaches the write's end, the bytes not landed reading as zeros.
/// - reordered (seeded): the folders as adversarial; per file, its size after the first `m` of its writes and
///   truncations since its last force, `m` uniform in `0..n`, and each of its 512-byte blocks holding the bytes after
///   a uniform prefix of its own, drawn apart from the others: a later write can land where an earlier one was
///   dropped. A byte past the end of its block's version reads zero.
/// - stale (seeded): as reordered, but a byte past the end of its block's version reads what the file last held
///   durably at that offset, before a cut shortened it (an earlier generation of the file), zero where it never held
///   any. Only the file's own earlier bytes: a file created anew starts with none.
/// - kill: the volatile state, which a process kill leaves.
///
/// Every operation is kept in order, so [#at] rebuilds the state after any prefix of them: the cut points; [#lastWrite]
/// and [#firstForce] find a file's operations among them. A test can also make `forcing` fail ([#failForce]).
/// Thread-safe.
final class DiskModel implements StoreFiles.Recorder {

    // the unit a device writes whole: a disk sector, small, so the few kilobytes of a test's log span several blocks
    private static final int BLOCK = 512;
    private static final byte[] EMPTY = new byte[0];

    private final Predicate<Path> entriesDurableAtOnce;
    private final List<Op> operations = new ArrayList<>();
    private final Path root;
    private final FolderNode start;
    private final FolderNode top = new FolderNode();
    private Predicate<Path> failForce = _ -> false;

    /// A strict model of `root`, an existing empty folder.
    DiskModel(final Path root) {
        this(root, _ -> false);
    }

    /// As above, but the entry changes of each folder `entriesDurableAtOnce` accepts are durable at once, as if the
    /// folder were forced after each: the check of a power-cut test against a weaker disk.
    DiskModel(final Path root, final Predicate<Path> entriesDurableAtOnce) {
        this(root, entriesDurableAtOnce, new FolderNode());
    }

    private DiskModel(final Path root, final Predicate<Path> entriesDurableAtOnce, final FolderNode start) {
        this.root = root;
        this.entriesDurableAtOnce = entriesDurableAtOnce;
        this.start = start;
        settle(start, top);
    }

    @Override
    public synchronized void created(final Path path) {
        final Path relative = relative(path);
        if (relative != null) {
            record(new Created(relative, Files.isDirectory(path)));
        }
    }

    @Override
    public synchronized void wrote(final Path file, final long position, final ByteBuffer bytes) {
        final Path relative = relative(file);
        if (relative != null) {
            final byte[] copy = new byte[bytes.remaining()];
            bytes.duplicate().get(copy);
            record(new Wrote(relative, position, copy));
        }
    }

    @Override
    public synchronized void truncated(final Path file, final long size) {
        final Path relative = relative(file);
        if (relative != null) {
            record(new Truncated(relative, size));
        }
    }

    @Override
    public synchronized void forcing(final Path file) throws IOException {
        if (failForce.test(file)) {
            throw new IOException("refuse force in a power-cut test [file=" + file + ']');
        }
        final Path relative = relative(file);
        if (relative != null) {
            record(new Forced(relative));
        }
    }

    @Override
    public synchronized void folderForcing(final Path folder) {
        final Path relative = relative(folder);
        if (relative != null) {
            record(new FolderForced(relative));
        }
    }

    @Override
    public synchronized void moved(final Path from, final Path to) {
        final Path relativeFrom = relative(from);
        final Path relativeTo = relative(to);
        if (relativeFrom != null && relativeTo != null) {
            record(new Moved(relativeFrom, relativeTo));
        }
    }

    @Override
    public synchronized void deleted(final Path path) {
        final Path relative = relative(path);
        if (relative != null) {
            record(new Deleted(relative));
        }
    }

    /// From now on `forcing` throws an `IOException` for each file `files` accepts (an absolute path), before the
    /// force is recorded: the file keeps its durable bytes.
    synchronized void failForce(final Predicate<Path> files) {
        failForce = files;
    }

    /// A strict model of `root` whose durable state is `image`, once written there: what a reboot after a power cut
    /// finds. Each file's bytes in `image` are also what it held durably before, for a later stale image.
    static DiskModel of(final Path root, final SortedMap<String, byte[]> image) {
        final FolderNode start = new FolderNode();
        for (final Map.Entry<String, byte[]> entry : image.entrySet()) {
            final String[] names = entry.getKey().split("/");
            FolderNode folder = start;
            for (int i = 0; i < names.length - 1; i++) {
                folder = folder.child(names[i]);
            }
            if (entry.getValue() == null) {
                folder.child(names[names.length - 1]);
            } else {
                folder.live.put(names[names.length - 1], FileNode.of(entry.getValue()));
            }
        }
        return new DiskModel(root, _ -> false, start);
    }

    /// How many operations were recorded.
    synchronized int operations() {
        return operations.size();
    }

    /// The index of the last write to `file` (an absolute path) among the operations from `from` to `to`, `to`
    /// excluded; -1 when there is none.
    synchronized int lastWrite(final Path file, final int from, final int to) {
        final Path relative = relative(file);
        for (int i = Math.min(to, operations.size()) - 1; i >= from; i--) {
            if (operations.get(i) instanceof Wrote(Path written, _, _) && written.equals(relative)) {
                return i;
            }
        }
        return -1;
    }

    /// The index of the first force of `file` (an absolute path) from operation `from` on; -1 when there is none.
    synchronized int firstForce(final Path file, final int from) {
        final Path relative = relative(file);
        for (int i = from; i < operations.size(); i++) {
            if (operations.get(i) instanceof Forced(Path forced) && forced.equals(relative)) {
                return i;
            }
        }
        return -1;
    }

    /// Operation `k` (from 0), for a failure message.
    synchronized String describe(final int k) {
        if (k < 0) {
            return "none";
        }
        return switch (operations.get(k)) {
            case Created(Path path, boolean folder) -> (folder ? "created folder " : "created ") + path;
            case Wrote(Path file, long position, byte[] bytes) -> "wrote " + file + " at "
                    + position + ", " + bytes.length + " bytes";
            case Truncated(Path file, long size) -> "truncated " + file + " to " + size;
            case Forced(Path file) -> "forced " + file;
            case FolderForced(Path folder) -> "forced folder " + (folder.toString().isEmpty() ? "." : folder);
            case Moved(Path from, Path to) -> "moved " + from + " to " + to;
            case Deleted(Path path) -> "deleted " + path;
        };
    }

    /// The disk after the first `k` operations, with this model's durability.
    DiskModel at(final int k) {
        return at(k, entriesDurableAtOnce);
    }

    /// The disk after the first `k` operations, the entry changes of the folders `entriesDurableAtOnce` accepts durable
    /// at once.
    synchronized DiskModel at(final int k, final Predicate<Path> entriesDurableAtOnce) {
        final DiskModel cut = new DiskModel(root, entriesDurableAtOnce, start);
        for (int i = 0; i < k; i++) {
            cut.record(operations.get(i));
        }
        return cut;
    }

    /// A strict model of `newRoot` whose durable state is this model's volatile state: what a reboot after a process
    /// kill finds, once the kill image is written to `newRoot`.
    synchronized DiskModel afterKill(final Path newRoot) {
        final FolderNode settled = new FolderNode();
        settle(top, settled);
        return new DiskModel(newRoot, _ -> false, settled);
    }

    /// Writes `image` into `target`, created empty.
    static void write(final Path target, final SortedMap<String, byte[]> image) throws IOException {
        Files.createDirectories(target);
        for (final Map.Entry<String, byte[]> entry : image.entrySet()) {
            final Path path = target.resolve(entry.getKey());
            if (entry.getValue() == null) {
                Files.createDirectories(path);
            } else {
                Files.createDirectories(path.getParent());
                Files.write(path, entry.getValue());
            }
        }
    }

    /// The durable state.
    synchronized SortedMap<String, byte[]> strictImage() {
        return image(Image.STRICT, null);
    }

    /// The durable state and a random part of what was not forced, chosen from `seed`.
    synchronized SortedMap<String, byte[]> adversarialImage(final long seed) {
        return image(Image.ADVERSARIAL, new Random(seed));
    }

    /// The durable state and, chosen from `seed`, a part of what was not forced, each file's blocks landed out of
    /// order.
    synchronized SortedMap<String, byte[]> reorderedImage(final long seed) {
        return image(Image.REORDERED, new Random(seed));
    }

    /// As [#reorderedImage], the bytes no landed write covers reading the file's earlier generation.
    synchronized SortedMap<String, byte[]> staleImage(final long seed) {
        return image(Image.STALE, new Random(seed));
    }

    /// The volatile state.
    synchronized SortedMap<String, byte[]> killImage() {
        return image(Image.KILL, null);
    }

    private static void settle(final FolderNode from, final FolderNode to) {
        for (final Map.Entry<String, Node> entry : from.live.entrySet()) {
            final Node copy = switch (entry.getValue()) {
                case FileNode file -> FileNode.of(file.liveBytes());
                case FolderNode folder -> {
                    final FolderNode made = new FolderNode();
                    settle(folder, made);
                    yield made;
                }
            };
            to.live.put(entry.getKey(), copy);
            to.durable.put(entry.getKey(), copy);
        }
    }

    // each entry of folder, its path starting with prefix, into into; a file named twice gets one content
    private static void collect(final String prefix, final FolderNode folder, final Map<FileNode, byte[]> contents,
            final Image image, final Random random, final SortedMap<String, byte[]> into) {
        for (final Map.Entry<String, Node> entry : folder.entries(image, random).entrySet()) {
            final String path = prefix + entry.getKey();
            switch (entry.getValue()) {
                case FolderNode child -> {
                    into.put(path, null);
                    collect(path + '/', child, contents, image, random, into);
                }
                case FileNode file -> into.put(path, contents.computeIfAbsent(file, f -> f.content(image, random)));
            }
        }
    }

    private static String name(final Path path) {
        return path.getFileName().toString();
    }

    private static boolean same(final Path a, final Path b) {
        return a == null ? b == null : a.equals(b);
    }

    private SortedMap<String, byte[]> image(final Image image, final Random random) {
        final SortedMap<String, byte[]> into = new TreeMap<>();
        collect("", top, new IdentityHashMap<>(), image, random, into);
        return into;
    }

    // null when path is not under the root
    private Path relative(final Path path) {
        return path.startsWith(root) ? root.relativize(path) : null;
    }

    private void record(final Op op) {
        operations.add(op);
        switch (op) {
            case Created(Path path, boolean folder) -> change(path.getParent(),
                    new Change(name(path), folder ? new FolderNode() : new FileNode(), null));
            case DataOp data -> file(data.file()).apply(data);
            case Forced(Path file) -> file(file).force();
            case FolderForced(Path folder) -> folder(folder).force();
            case Moved(Path from, Path to) -> {
                final Node node = node(from);
                if (same(from.getParent(), to.getParent())) {
                    change(to.getParent(), new Change(name(to), node, name(from)));
                } else {
                    change(from.getParent(), new Change(name(from), null, null));
                    change(to.getParent(), new Change(name(to), node, null));
                }
            }
            case Deleted(Path path) -> change(path.getParent(), new Change(name(path), null, null));
        }
    }

    private void change(final Path parent, final Change change) {
        final FolderNode folder = folder(parent);
        folder.apply(change, folder.live);
        if (entriesDurableAtOnce.test(parent == null ? root : root.resolve(parent))) {
            folder.apply(change, folder.durable);
        } else {
            folder.unforced.add(change);
        }
    }

    private FileNode file(final Path path) {
        if (node(path) instanceof FileNode file) {
            return file;
        }
        throw new IllegalStateException("refuse file operation on a folder [path=" + path + ']');
    }

    private FolderNode folder(final Path path) {
        if (path == null || path.toString().isEmpty()) {
            return top;
        }
        if (node(path) instanceof FolderNode folder) {
            return folder;
        }
        throw new IllegalStateException("refuse folder operation on a file [path=" + path + ']');
    }

    private Node node(final Path path) {
        if (path.toString().isEmpty()) {
            return top;
        }
        final Node node = folder(path.getParent()).live.get(name(path));
        if (node == null) {
            throw new IllegalStateException("refuse operation on a path the model never saw created [root=" + root
                    + ", path=" + path + ']');
        }
        return node;
    }

    private enum Image {
        STRICT,
        ADVERSARIAL,
        REORDERED,
        STALE,
        KILL
    }

    private sealed interface Node permits FileNode, FolderNode {
    }

    private sealed interface Op permits Created, DataOp, Forced, FolderForced, Moved, Deleted {
    }

    /// An operation on a file's bytes.
    private sealed interface DataOp extends Op permits Wrote, Truncated {

        Path file();
    }

    private record Created(Path path, boolean folder) implements Op {
    }

    private record Wrote(Path file, long position, byte[] bytes) implements DataOp {
    }

    private record Truncated(Path file, long size) implements DataOp {
    }

    private record Forced(Path file) implements Op {
    }

    private record FolderForced(Path folder) implements Op {
    }

    private record Moved(Path from, Path to) implements Op {
    }

    private record Deleted(Path path) implements Op {
    }

    /// Sets `name` to `node` (null: removes it), and removes `alsoRemoved` when not null: a rename within the folder.
    private record Change(String name, Node node, String alsoRemoved) {
    }

    private static final class FolderNode implements Node {

        private final Map<String, Node> durable = new TreeMap<>();
        private final Map<String, Node> live = new TreeMap<>();
        private final List<Change> unforced = new ArrayList<>();

        void apply(final Change change, final Map<String, Node> entries) {
            if (change.alsoRemoved() != null) {
                entries.remove(change.alsoRemoved());
            }
            if (change.node() == null) {
                entries.remove(change.name());
            } else {
                entries.put(change.name(), change.node());
            }
        }

        void force() {
            durable.clear();
            durable.putAll(live);
            unforced.clear();
        }

        // the folder name names in this one's live map, made when missing; for building a model from an image
        FolderNode child(final String name) {
            if (live.computeIfAbsent(name, _ -> new FolderNode()) instanceof FolderNode folder) {
                return folder;
            }
            throw new IllegalStateException("refuse folder over a file in an image [name=" + name + ']');
        }

        Map<String, Node> entries(final Image image, final Random random) {
            return switch (image) {
                case STRICT -> durable;
                case KILL -> live;
                case ADVERSARIAL, REORDERED, STALE -> {
                    final Map<String, Node> entries = new TreeMap<>(durable);
                    for (final Change change : unforced) {
                        if (random.nextBoolean()) {
                            apply(change, entries);
                        }
                    }
                    yield entries;
                }
            };
        }
    }

    private static final class FileNode implements Node {

        private final Bytes live = new Bytes(EMPTY);
        private final List<DataOp> unforced = new ArrayList<>();
        private byte[] durable = EMPTY;
        // at each offset, the last byte forced there, kept when a cut shortens the file: its earlier generations
        private byte[] earlier = EMPTY;

        static FileNode of(final byte[] bytes) {
            final FileNode file = new FileNode();
            file.durable = bytes;
            file.earlier = bytes;
            file.live.write(0L, bytes, bytes.length);
            return file;
        }

        void apply(final DataOp op) {
            Bytes.apply(live, op);
            unforced.add(op);
        }

        void force() {
            durable = live.toArray();
            unforced.clear();
            if (earlier.length < durable.length) {
                earlier = Arrays.copyOf(earlier, durable.length);
            }
            System.arraycopy(durable, 0, earlier, 0, durable.length);
        }

        byte[] liveBytes() {
            return live.toArray();
        }

        byte[] content(final Image image, final Random random) {
            return switch (image) {
                case STRICT -> durable;
                case KILL -> live.toArray();
                case ADVERSARIAL -> {
                    final Bytes landed = new Bytes(durable);
                    final int n = unforced.size();
                    final int m = random.nextInt(n + 1);
                    for (int i = 0; i < m; i++) {
                        Bytes.apply(landed, unforced.get(i));
                    }
                    if (m < n && unforced.get(m) instanceof Wrote(_, long position, byte[] bytes)) {
                        landed.write(position, bytes, random.nextInt(bytes.length + 1));
                        if (random.nextBoolean()) {
                            landed.extend(position + bytes.length);
                        }
                    }
                    yield landed.toArray();
                }
                case REORDERED -> reordered(random, false);
                case STALE -> reordered(random, true);
            };
        }

        // the size after a uniform prefix of the unforced operations, each block after a prefix of its own
        private byte[] reordered(final Random random, final boolean stale) {
            final int n = unforced.size();
            final byte[][] versions = new byte[n + 1][];
            versions[0] = durable;
            final Bytes bytes = new Bytes(durable);
            for (int m = 0; m < n; m++) {
                Bytes.apply(bytes, unforced.get(m));
                versions[m + 1] = bytes.toArray();
            }
            final byte[] landed = new byte[versions[random.nextInt(n + 1)].length];
            for (int start = 0; start < landed.length; start += BLOCK) {
                final byte[] version = versions[random.nextInt(n + 1)];
                final int end = Math.min(landed.length, start + BLOCK);
                for (int i = start; i < end; i++) {
                    if (i < version.length) {
                        landed[i] = version[i];
                    } else if (stale && i < earlier.length) {
                        landed[i] = earlier[i];
                    }
                }
            }
            return landed;
        }
    }

    /// A file's bytes, grown by writes, zero-filled past the end.
    private static final class Bytes {

        private byte[] data;
        private int size;

        Bytes(final byte[] initial) {
            data = Arrays.copyOf(initial, Math.max(16, initial.length));
            size = initial.length;
        }

        static void apply(final Bytes bytes, final DataOp op) {
            switch (op) {
                case Wrote(_, long position, byte[] written) -> bytes.write(position, written, written.length);
                case Truncated(_, long newSize) -> bytes.truncate(newSize);
            }
        }

        void write(final long position, final byte[] bytes, final int length) {
            extend(position + length);
            System.arraycopy(bytes, 0, data, Math.toIntExact(position), length);
        }

        void extend(final long newSize) {
            final int end = Math.toIntExact(newSize);
            if (end > data.length) {
                data = Arrays.copyOf(data, Math.max(end, data.length * 2));
            }
            if (end > size) {
                Arrays.fill(data, size, end, (byte) 0);
                size = end;
            }
        }

        void truncate(final long newSize) {
            final int end = Math.toIntExact(newSize);
            if (end < size) {
                size = end;
            } else {
                extend(end);
            }
        }

        byte[] toArray() {
            return Arrays.copyOf(data, size);
        }
    }
}
