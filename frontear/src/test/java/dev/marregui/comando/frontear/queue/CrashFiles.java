package dev.marregui.comando.frontear.queue;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// A file system in memory that keeps two states, the one a process sees and the one a power cut leaves (the data of
/// each file as of its last force, the folder's entries as of its last folder force), and that crashes at the Nth
/// operation that changes a file or the folder: it records what the disk could hold at that instant, then fails that
/// operation and every later one, as a process killed there would never run them. A kill keeps both states, as the
/// kernel keeps the pages a process wrote and has not forced; a power cut keeps the forced one. One folder per
/// instance.
final class CrashFiles implements LogFiles {

    private final int crashAt;
    private final Map<Path, Node> names = new HashMap<>();
    private final List<CrashFiles> images = new ArrayList<>();
    // the entries as of the last folder force, each with the node it named then
    private Map<Path, Node> durableNames = new HashMap<>();
    private int ops;
    private boolean crashed;
    private boolean failForces;

    /// A file system that crashes at its `crashAt`th changing operation, counted from 1; 0 never crashes.
    CrashFiles(final int crashAt) {
        this.crashAt = crashAt;
    }

    /// A file system that never crashes, holding `files`, every one forced and its entry durable.
    CrashFiles(final Map<Path, byte[]> files) {
        this(0);
        for (final Map.Entry<Path, byte[]> e : files.entrySet()) {
            final Node node = new Node();
            node.data = e.getValue().clone();
            node.forced = e.getValue().clone();
            names.put(e.getKey(), node);
        }
        durableNames = new HashMap<>(names);
    }

    // a copy of from's two states that never crashes, each node copied once, so an entry in both states names one
    private CrashFiles(final CrashFiles from) {
        this(0);
        final Map<Node, Node> copies = new HashMap<>();
        for (final Map.Entry<Path, Node> e : from.names.entrySet()) {
            names.put(e.getKey(), copies.computeIfAbsent(e.getValue(), Node::copy));
        }
        for (final Map.Entry<Path, Node> e : from.durableNames.entrySet()) {
            durableNames.put(e.getKey(), copies.computeIfAbsent(e.getValue(), Node::copy));
        }
    }

    @Override
    public synchronized void createDirectories(final Path dir) {
        // a folder exists once named
    }

    @Override
    public synchronized List<Path> list(final Path dir) {
        final List<Path> found = new ArrayList<>();
        for (final Path p : names.keySet()) {
            if (dir.equals(p.getParent())) {
                found.add(p);
            }
        }
        return found;
    }

    @Override
    public synchronized Writer create(final Path file) throws IOException {
        step(null);
        final Node existing = names.get(file);
        if (existing != null) {
            existing.data = new byte[0];
            return new NodeWriter(existing);
        }
        final Node node = new Node();
        names.put(file, node);
        return new NodeWriter(node);
    }

    @Override
    public synchronized Writer open(final Path file) throws IOException {
        final Node node = names.get(file);
        if (node == null) {
            throw new NoSuchFileException(file.toString());
        }
        return new NodeWriter(node);
    }

    @Override
    public <T> T read(final Path file, final Body<T> body) throws IOException {
        final byte[] copy;
        synchronized (this) {
            final Node node = names.get(file);
            if (node == null) {
                throw new NoSuchFileException(file.toString());
            }
            copy = node.data.clone();
        }
        return body.read(MemorySegment.ofArray(copy));
    }

    @Override
    public Mapping map(final Path file) throws IOException {
        final byte[] copy;
        synchronized (this) {
            final Node node = names.get(file);
            if (node == null) {
                throw new NoSuchFileException(file.toString());
            }
            copy = node.data.clone();
        }
        return new HeapMapping(MemorySegment.ofArray(copy));
    }

    @Override
    public synchronized void delete(final Path file) throws IOException {
        step(null);
        names.remove(file);
    }

    @Override
    public synchronized void move(final Path from, final Path to) throws IOException {
        step(null);
        final Node node = names.remove(from);
        if (node == null) {
            throw new NoSuchFileException(from.toString());
        }
        names.put(to, node);
    }

    @Override
    public synchronized void forceFolder(final Path dir) throws IOException {
        step(null);
        if (failForces) {
            throw new IOException("cannot force folder, failing on purpose [dir=" + dir + ']');
        }
        durableNames = new HashMap<>(names);
    }

    // counts a changing operation; at the crash, records the images, the torn one from tornWrite when it is a write
    /// Whether the crash came.
    synchronized boolean crashed() {
        return crashed;
    }

    /// What the disk could hold at the crash, each a file system to reopen: a kill, a kill mid-write when the crashing
    /// operation was a write (half its bytes written), a power cut. Empty while no crash came.
    synchronized List<CrashFiles> images() {
        return List.copyOf(images);
    }

    /// The forced state now: what a power cut at this instant would leave.
    synchronized Map<Path, byte[]> powerCut() {
        final Map<Path, byte[]> image = new HashMap<>();
        for (final Map.Entry<Path, Node> e : durableNames.entrySet()) {
            image.put(e.getKey(), e.getValue().forced.clone());
        }
        return image;
    }

    /// Makes every force and folder force fail from now on, and every other operation succeed, or the opposite.
    synchronized void failForces(final boolean fail) {
        failForces = fail;
    }

    private void step(final Runnable tornWrite) throws IOException {
        if (crashed) {
            throw new IOException("cannot run operation after the crash [op=" + ops + ']');
        }
        ops++;
        if (ops == crashAt) {
            crashed = true;
            images.add(new CrashFiles(this));
            if (tornWrite != null) {
                tornWrite.run();
                images.add(new CrashFiles(this));
            }
            images.add(new CrashFiles(powerCut()));
            throw new IOException("cannot run operation, crash on purpose [op=" + ops + ']');
        }
    }

    // a file's two states: the written bytes, and the bytes its last force made durable
    /// A file's bytes as they were when mapped, copied, so a later write is not seen; nothing to let go.
    private record HeapMapping(MemorySegment bytes) implements Mapping {

        @Override
        public void close() {
        }
    }

    private static final class Node {
        byte[] data = new byte[0];
        byte[] forced = new byte[0];

        Node copy() {
            final Node c = new Node();
            c.data = data.clone();
            c.forced = forced.clone();
            return c;
        }
    }

    // writes into a node, as a channel writes into an inode: a deleted or moved file keeps its writer
    private final class NodeWriter implements Writer {

        private final Node node;

        NodeWriter(final Node node) {
            this.node = node;
        }

        @Override
        public void write(final ByteBuffer src, final long position) throws IOException {
            final byte[] bytes = new byte[src.remaining()];
            src.duplicate().get(bytes);
            synchronized (CrashFiles.this) {
                step(() -> put(Arrays.copyOf(bytes, bytes.length / 2), position));
                put(bytes, position);
            }
            src.position(src.limit());
        }

        @Override
        public void force() throws IOException {
            synchronized (CrashFiles.this) {
                step(null);
                if (failForces) {
                    throw new IOException("cannot force file, failing on purpose");
                }
                node.forced = node.data.clone();
            }
        }

        @Override
        public void truncate(final long size) throws IOException {
            synchronized (CrashFiles.this) {
                step(null);
                if (size < node.data.length) {
                    node.data = Arrays.copyOf(node.data, (int) size);
                }
            }
        }

        @Override
        public void close() {
            // nothing to release
        }

        private void put(final byte[] bytes, final long position) {
            final int end = (int) position + bytes.length;
            if (end > node.data.length) {
                node.data = Arrays.copyOf(node.data, end);
            }
            System.arraycopy(bytes, 0, node.data, (int) position, bytes.length);
        }
    }
}
