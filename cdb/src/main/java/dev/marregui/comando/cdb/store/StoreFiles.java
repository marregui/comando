package dev.marregui.comando.cdb.store;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

import org.jetbrains.annotations.TestOnly;

/**
 * Every file operation of the store. A partition's column files grow only past their committed end, and are cut only
 * when the store opens, before anything maps them: a version folder is deleted only when no reader maps it. So a
 * mapping never meets a file cut under it.
 */
final class StoreFiles {

    private static final System.Logger LOG = System.getLogger(StoreFiles.class.getName());

    /**
     * Sees every operation that changes the disk, in the order the store makes them; a power-cut test replays them
     * into a model of what a crash keeps. Null outside such a test. Written by the test's thread before and after the
     * store runs; read by whichever thread makes a store write, the volatile read ordering it after the test's write.
     */
    @TestOnly
    static volatile Recorder recorder;

    /**
     * The disk operations of the store, as a power-cut test sees them. {@code forcing} and {@code folderForcing} run
     * before the force and may throw, as a failed force would.
     */
    @TestOnly
    interface Recorder {

        /** {@code path}, a file or a folder, was created empty. */
        void created(Path path);

        /** {@code bytes} were written into {@code file} at {@code position}. */
        void wrote(Path file, long position, ByteBuffer bytes);

        /** {@code file} was cut to {@code size} bytes. */
        void truncated(Path file, long size);

        /** {@code file}'s data is about to be forced to the device. */
        void forcing(Path file) throws IOException;

        /** {@code folder}'s entries are about to be forced to the device. */
        void folderForcing(Path folder) throws IOException;

        /** {@code from} was renamed to {@code to}, replacing it. */
        void moved(Path from, Path to);

        /** {@code path}, a file or an empty folder, was deleted. */
        void deleted(Path path);

        /** A delete of {@code path} is about to run, whether or not it exists: the cost of a missing one is an error. */
        default void deleting(final Path path) {
        }

        /** A channel on {@code path}, a file or a folder, is about to be opened; it changes nothing on the disk. */
        default void opening(final Path path) {
        }
    }

    private StoreFiles() {
    }

    /**
     * The channel of {@code file} holding the exclusive lock on it, the file made when missing; null when another
     * process, or another channel in this JVM, holds it. The lock goes when the channel closes, or the process ends.
     */
    static FileChannel lockExclusively(final Path file) throws IOException {
        final FileChannel channel = open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            if (channel.tryLock() != null) {
                return channel;
            }
        } catch (final OverlappingFileLockException _) {
            // this JVM already holds it, through another store on the same root
        } catch (final IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
        channel.close();
        return null;
    }

    /** Maps {@code file} read-only into {@code arena}; an empty file is an empty segment. */
    static MemorySegment map(final Path file, final Arena arena) throws IOException {
        try (final FileChannel channel = open(file, StandardOpenOption.READ)) {
            final long size = channel.size();
            return size == 0L ? MemorySegment.NULL : channel.map(FileChannel.MapMode.READ_ONLY, 0L, size, arena);
        }
    }

    /** The whole of {@code file} as UTF-8 text, read from a mapping byte by byte. */
    static String readText(final Path file) throws IOException {
        try (final Arena arena = Arena.ofConfined()) {
            final MemorySegment segment = map(file, arena);
            final long size = segment.byteSize();
            if (size > Integer.MAX_VALUE) {
                throw new IOException("refuse text file over 2 GB [file=" + file + ", bytes=" + size + ']');
            }
            final byte[] bytes = new byte[(int) size];
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = segment.get(ValueLayout.JAVA_BYTE, i);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /** Writes {@code bytes} to the new file {@code file} and forces it to the device. */
    static void writeNew(final Path file, final ByteBuffer bytes) throws IOException {
        writeNew(file, bytes, true);
    }

    /** Writes {@code bytes} to the new file {@code file}, forced to the device when {@code force}. */
    static void writeNew(final Path file, final ByteBuffer bytes, final boolean force) throws IOException {
        try (final FileChannel channel = open(file, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            final Recorder r = recorder;
            if (r != null) {
                r.created(file);
            }
            final ByteBuffer written = bytes.duplicate();
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
            if (r != null) {
                r.wrote(file, 0L, written);
            }
            if (force) {
                force(channel, file);
            }
        }
    }

    /** Appends {@code bytes} to {@code file}, created when missing, and forces it to the device. */
    static void append(final Path file, final ByteBuffer bytes) throws IOException {
        append(file, bytes, true);
    }

    /** Appends {@code bytes} to {@code file}, created when missing, forced to the device when {@code force}. */
    static void append(final Path file, final ByteBuffer bytes, final boolean force) throws IOException {
        final boolean existed = Files.exists(file);
        try (final FileChannel channel = open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            final Recorder r = recorder;
            if (r != null && !existed) {
                r.created(file);
            }
            final long at = channel.size();
            final ByteBuffer written = bytes.duplicate();
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
            if (r != null) {
                r.wrote(file, at, written);
            }
            if (force) {
                force(channel, file);
            }
        }
    }

    /**
     * Writes {@code bytes} into the existing {@code file} from {@code position}, over whatever lies there, and forces
     * it to the device; the file grows when the bytes reach past its end.
     */
    @TestOnly
    static void writeAt(final Path file, final long position, final ByteBuffer bytes) throws IOException {
        writeAt(file, position, bytes, true);
    }

    /**
     * Writes {@code bytes} into the existing {@code file} from {@code position}, over whatever lies there, forced to
     * the device when {@code force}; the file grows when the bytes reach past its end.
     */
    static void writeAt(final Path file, final long position, final ByteBuffer bytes, final boolean force)
            throws IOException {
        try (final FileChannel channel = open(file, StandardOpenOption.WRITE)) {
            final ByteBuffer written = bytes.duplicate();
            long at = position;
            while (bytes.hasRemaining()) {
                at += channel.write(bytes, at);
            }
            final Recorder r = recorder;
            if (r != null) {
                r.wrote(file, position, written);
            }
            if (force) {
                force(channel, file);
            }
        }
    }

    /** Forces the data of the existing {@code file} to the device. */
    static void force(final Path file) throws IOException {
        try (final FileChannel channel = open(file, StandardOpenOption.WRITE)) {
            force(channel, file);
        }
    }

    /**
     * A channel on the existing {@code file} for {@link #force(FileChannel, Path)}, kept open by an owner that forces
     * the file often, so no force opens it; the owner closes it. An interrupt during a force through it closes it.
     */
    static FileChannel openForForce(final Path file) throws IOException {
        return open(file, StandardOpenOption.WRITE);
    }

    /**
     * Creates the folder {@code folder} when it is missing, its parent existing; its entry is durable once the parent
     * is forced.
     */
    static void createFolder(final Path folder) throws IOException {
        if (Files.isDirectory(folder)) {
            return;
        }
        Files.createDirectory(folder);
        final Recorder r = recorder;
        if (r != null) {
            r.created(folder);
        }
    }

    /** Deletes {@code path}, a file or an empty folder, when it exists. */
    static void delete(final Path path) throws IOException {
        final Recorder r = recorder;
        if (r != null) {
            r.deleting(path);
        }
        if (Files.deleteIfExists(path)) {
            if (r != null) {
                r.deleted(path);
            }
        }
    }

    /** The whole of {@code file}, read into the heap; refuses a file over 2 GB. */
    static ByteBuffer readAll(final Path file) throws IOException {
        try (final FileChannel channel = open(file, StandardOpenOption.READ)) {
            final long size = channel.size();
            if (size > Integer.MAX_VALUE) {
                throw new IOException("refuse file over 2 GB [file=" + file + ", bytes=" + size + ']');
            }
            final ByteBuffer buffer = ByteBuffer.allocate((int) size);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) {
                    break;
                }
            }
            return buffer.flip();
        }
    }

    /** The little-endian long at {@code position} of {@code file}. */
    static long readLong(final Path file, final long position) throws IOException {
        final ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        try (final FileChannel channel = open(file, StandardOpenOption.READ)) {
            long at = position;
            while (buffer.hasRemaining()) {
                final int read = channel.read(buffer, at);
                if (read < 0) {
                    throw new IOException("cannot read past end of file [file=" + file + ", position=" + position
                            + ']');
                }
                at += read;
            }
        }
        return buffer.flip().getLong();
    }

    /** Cuts {@code file} to {@code size} bytes; used on open, before anything maps it. */
    static void truncate(final Path file, final long size) throws IOException {
        try (final FileChannel channel = open(file, StandardOpenOption.WRITE)) {
            channel.truncate(size);
            final Recorder r = recorder;
            if (r != null) {
                r.truncated(file, size);
            }
            force(channel, file);
        }
    }

    /**
     * Replaces {@code file} with {@code text} whole: written to {@code <file>.tmp}, forced, moved over it atomically,
     * then its folder forced, so a crash leaves the old content or the new, never a mix. Throws only before the move:
     * once moved, the new content is the file's (for {@code _txn}, the commit is done and visible), so a failure to
     * force the folder after it, which leaves only the rename's survival of a power loss in doubt, is logged, not
     * thrown; a throw there would have the caller undo a commit that stands. Returns whether the folder was forced:
     * false leaves the new content visible but its survival of a power loss in doubt.
     */
    static boolean replaceText(final Path file, final String text) throws IOException {
        final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        final ByteBuffer bytes = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
        try {
            writeNew(tmp, bytes);
        } catch (final FileAlreadyExistsException _) {
            // a crash between the write and the move left it, rare: deleted then, not before every replace, where
            // the delete of a missing file costs an exception inside the JDK
            delete(tmp);
            writeNew(tmp, bytes);
        }
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        final Recorder r = recorder;
        if (r != null) {
            r.moved(tmp, file);
        }
        try {
            forceFolder(file.getParent());
            return true;
        } catch (final IOException e) {
            // an interrupt closing the channel keeps the thread's interrupt status set for the caller to see
            LOG.log(System.Logger.Level.WARNING, "cannot force folder after replace [file=" + file + ']', e);
            return false;
        }
    }

    /** Forces {@code folder}'s entries to the device, so a rename in it survives a crash. */
    static void forceFolder(final Path folder) throws IOException {
        final Recorder r = recorder;
        if (r != null) {
            r.folderForcing(folder);
        }
        try (final FileChannel channel = open(folder, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    /** Forces the data of {@code file} to the device through {@code channel}, a channel on it. */
    static void force(final FileChannel channel, final Path file) throws IOException {
        final Recorder r = recorder;
        if (r != null) {
            r.forcing(file);
        }
        channel.force(true);
    }

    private static FileChannel open(final Path path, final StandardOpenOption... options) throws IOException {
        final Recorder r = recorder;
        if (r != null) {
            r.opening(path);
        }
        return FileChannel.open(path, options);
    }

    /** Deletes {@code folder} and the files in it (a partition version holds no folders). */
    static void deleteFolder(final Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return;
        }
        try (final DirectoryStream<Path> files = Files.newDirectoryStream(folder)) {
            for (final Path file : files) {
                delete(file);
            }
        }
        delete(folder);
    }
}
