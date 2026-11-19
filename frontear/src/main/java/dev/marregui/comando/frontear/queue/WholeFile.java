package dev.marregui.comando.frontear.queue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;

/**
 * A small file replaced whole and durably: written beside it as {@code <name>.tmp}, forced, moved over it, and its
 * folder forced, so a crash leaves the old content or the new, never a mix. A link log's cursor is one; a link's spec
 * is another. The writes and forces run on the caller's thread.
 */
public final class WholeFile {

    private static final String TMP = ".tmp";

    private WholeFile() {
    }

    /** Replaces {@code file}, whose folder exists, with {@code bytes}. */
    public static void replace(final Path file, final byte[] bytes) throws IOException {
        replace(DiskFiles.INSTANCE, file, ByteBuffer.wrap(bytes));
    }

    /** Creates the folder {@code dir} and forces its parent, so the new entry survives a crash. */
    public static void createFolder(final Path dir) throws IOException {
        DiskFiles.INSTANCE.createDirectories(dir);
        DiskFiles.INSTANCE.forceFolder(dir.getParent());
    }

    /** Deletes {@code file} when it exists, and forces its folder so the deletion survives a crash. */
    public static void delete(final Path file) throws IOException {
        DiskFiles.INSTANCE.delete(file);
        DiskFiles.INSTANCE.forceFolder(file.getParent());
    }

    /** The file {@link #replace} writes before it moves it over {@code file}. */
    static Path tmp(final Path file) {
        return file.resolveSibling(file.getFileName() + TMP);
    }

    static void replace(final LogFiles files, final Path file, final ByteBuffer content) throws IOException {
        final Path tmp = tmp(file);
        try (LogFiles.Writer writer = files.create(tmp)) {
            writer.write(content, 0L);
            writer.force();
        }
        files.move(tmp, file);
        files.forceFolder(file.getParent());
    }
}
