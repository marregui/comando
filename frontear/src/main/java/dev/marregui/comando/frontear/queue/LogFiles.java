package dev.marregui.comando.frontear.queue;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;

/**
 * Every file operation of a {@link LinkLog}, so a test can stand in for the file system and cut it at any write or
 * force: the log touches its folder only through here. {@link DiskFiles} is the one production implementation.
 */
interface LogFiles {

    /** Creates {@code dir} and its missing parents. */
    void createDirectories(Path dir) throws IOException;

    /** The regular files in {@code dir}, in no order. */
    List<Path> list(Path dir) throws IOException;

    /** Creates {@code file} for writing, emptied when it exists; its entry is durable once the folder is forced. */
    Writer create(Path file) throws IOException;

    /** Opens the existing {@code file} for writing at any position. */
    Writer open(Path file) throws IOException;

    /** Runs {@code body} over {@code file}'s bytes as they are now; the segment is valid only during the call. */
    <T> T read(Path file, Body<T> body) throws IOException;

    /**
     * Maps {@code file}'s bytes as they are now, for any number of reads from any thread until the mapping is closed;
     * bytes written later are not seen. For a file whose bytes no longer change.
     */
    Mapping map(Path file) throws IOException;

    /** Deletes {@code file} when it exists. */
    void delete(Path file) throws IOException;

    /** Moves {@code from} over {@code to} atomically; durable once the folder is forced. */
    void move(Path from, Path to) throws IOException;

    /** Forces {@code dir}'s entries to the device: the files created, moved and deleted in it. */
    void forceFolder(Path dir) throws IOException;

    /** What a read does with a file's bytes. */
    @FunctionalInterface
    interface Body<T> {
        T read(MemorySegment bytes) throws IOException;
    }

    /**
     * A file's bytes kept for many reads. A read after the close, or racing it on another thread, fails with an
     * {@link IllegalStateException}; one of a file cut while mapped with an {@link InternalError}.
     */
    interface Mapping extends AutoCloseable {

        /** The file's bytes as they were when mapped, valid until {@link #close()}. */
        MemorySegment bytes();

        /** Lets the bytes go; idempotent. */
        @Override
        void close();
    }

    /** One open file, written at explicit positions; safe for a writer and a forcer on two threads. */
    interface Writer extends AutoCloseable {

        /** Writes all of {@code src} at {@code position}. */
        void write(ByteBuffer src, long position) throws IOException;

        /** Forces the file's data and size to the device. */
        void force() throws IOException;

        /** Cuts the file to {@code size} bytes. */
        void truncate(long size) throws IOException;

        @Override
        void close() throws IOException;
    }
}
