package dev.marregui.comando.frontear.queue;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import dev.marregui.comando.core.files.Mapped;

/**
 * The file system as a {@link LinkLog} uses it: reads through {@code core}'s {@link Mapped}, as every comando read
 * goes; writes, forces and folder forces through channels, the one class of the log that opens one. Forces are
 * {@code force(true)}, data and metadata, as cdb's store forces its log. A {@link #map} is a sealed segment's mapping
 * in a shared arena of its own, for the log's reads on whichever thread runs them, until the log closes it.
 */
final class DiskFiles implements LogFiles {

    static final DiskFiles INSTANCE = new DiskFiles();

    private DiskFiles() {
    }

    @Override
    public void createDirectories(final Path dir) throws IOException {
        Files.createDirectories(dir);
    }

    @Override
    public List<Path> list(final Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(Files::isRegularFile).toList();
        }
    }

    @Override
    public Writer create(final Path file) throws IOException {
        return new ChannelWriter(FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE));
    }

    @Override
    public Writer open(final Path file) throws IOException {
        return new ChannelWriter(FileChannel.open(file, StandardOpenOption.WRITE));
    }

    @Override
    public <T> T read(final Path file, final Body<T> body) throws IOException {
        return Mapped.read(file, m -> body.read(m.segment()));
    }

    @Override
    public Mapping map(final Path file) throws IOException {
        if (!Files.readAttributes(file, BasicFileAttributes.class).isRegularFile()) {
            // as Mapped refuses one: no file given, so the JDK does not prefix the path and the message keeps its shape
            throw new FileSystemException(null, null, "cannot map what is not a regular file [file=" + file + ']');
        }
        final Arena arena = Arena.ofShared();
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            final long size = channel.size();
            final MemorySegment bytes = size == 0L ? MemorySegment.ofArray(new byte[0])
                    : channel.map(FileChannel.MapMode.READ_ONLY, 0L, size, arena);
            return new SharedMapping(file, arena, bytes);
        } catch (final IOException | RuntimeException e) {
            arena.close();
            throw e;
        }
    }

    @Override
    public void delete(final Path file) throws IOException {
        Files.deleteIfExists(file);
    }

    @Override
    public void move(final Path from, final Path to) throws IOException {
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public void forceFolder(final Path dir) throws IOException {
        try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    /**
     * A file mapped in a shared arena: its owner is the log segment that keeps it, its tag the file, its size the
     * bytes'. A shared arena's close makes a read racing it on another thread fail rather than fault.
     */
    private static final class SharedMapping implements Mapping {

        private final Arena arena;
        private final MemorySegment bytes;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Path file;

        private SharedMapping(final Path file, final Arena arena, final MemorySegment bytes) {
            this.file = file;
            this.arena = arena;
            this.bytes = bytes;
        }

        @Override
        public MemorySegment bytes() {
            return bytes;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                arena.close();
            }
        }

        @Override
        public String toString() {
            return "mapping [file=" + file + ", bytes=" + bytes.byteSize() + ']';
        }
    }

    /** A channel written at explicit positions, so a write and a force on two threads never race on its position. */
    private record ChannelWriter(FileChannel channel) implements Writer {

        @Override
        public void write(final ByteBuffer src, final long position) throws IOException {
            long at = position;
            while (src.hasRemaining()) {
                at += channel.write(src, at);
            }
        }

        @Override
        public void force() throws IOException {
            channel.force(true);
        }

        @Override
        public void truncate(final long size) throws IOException {
            channel.truncate(size);
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }
}
