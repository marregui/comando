package dev.marregui.comando.cdb.store;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Every file operation of the store. Files of a partition version are written once, forced, and never changed or cut
 * while mapped: a version folder is deleted only when no reader maps it. So a mapping never meets a file cut under it.
 */
final class StoreFiles {

    private StoreFiles() {
    }

    /**
     * The channel of {@code file} holding the exclusive lock on it, the file made when missing; null when another
     * process, or another channel in this JVM, holds it. The lock goes when the channel closes, or the process ends.
     */
    static FileChannel lockExclusively(final Path file) throws IOException {
        final FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
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
        try (final FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
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
        try (final FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
            channel.force(true);
        }
    }

    /** Appends {@code bytes} to {@code file}, created when missing, and forces it to the device. */
    static void append(final Path file, final ByteBuffer bytes) throws IOException {
        try (final FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
            channel.force(true);
        }
    }

    /** Cuts {@code file} to {@code size} bytes; used on open, before anything maps it. */
    static void truncate(final Path file, final long size) throws IOException {
        try (final FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.truncate(size);
            channel.force(true);
        }
    }

    /**
     * Replaces {@code file} with {@code text} whole: written to {@code <file>.tmp}, forced, then moved over it
     * atomically, so a crash leaves the old content or the new, never a mix.
     */
    static void replaceText(final Path file, final String text) throws IOException {
        final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        writeNew(tmp, ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8)));
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Deletes {@code folder} and the files in it (a partition version holds no folders). */
    static void deleteFolder(final Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return;
        }
        try (final DirectoryStream<Path> files = Files.newDirectoryStream(folder)) {
            for (final Path file : files) {
                Files.delete(file);
            }
        }
        Files.delete(folder);
    }
}
