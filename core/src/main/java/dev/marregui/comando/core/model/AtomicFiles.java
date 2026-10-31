package dev.marregui.comando.core.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;

/**
 * Files written whole or not at all: the bytes go to a temporary file of a unique name in the target's folder, which
 * is then moved over the target, atomically where the file system can, else by a plain replacing move. A reader sees
 * the old content or the new, never half of it, and two writers of one file never share a temporary file. On any
 * failure the temporary file is deleted, the target is left as it was, and the exception reaches the caller. A
 * replaced target keeps its permissions.
 */
public final class AtomicFiles {

    /** How a file is moved into place: {@link Files#move}, or a stand-in in tests. */
    @FunctionalInterface
    public interface Mover {
        void move(Path from, Path to, CopyOption... options) throws IOException;
    }

    private AtomicFiles() {
    }

    /** Writes {@code bytes} to {@code target}, replacing it; the target's folder must exist. */
    public static void write(final Path target, final byte[] bytes) throws IOException {
        write(target, bytes, Files::move);
    }

    /** Writes {@code text} to {@code target} in UTF-8, replacing it; the target's folder must exist. */
    public static void writeString(final Path target, final String text) throws IOException {
        write(target, text.getBytes(StandardCharsets.UTF_8));
    }

    /** As {@link #write(Path, byte[])}, the temporary file moved into place by {@code mover}. */
    public static void write(final Path target, final byte[] bytes, final Mover mover) throws IOException {
        final Path dir = target.toAbsolutePath().getParent();
        final Path tmp = Files.createTempFile(dir, "." + target.getFileName() + ".", ".tmp");
        try {
            keepMode(target, tmp);
            Files.write(tmp, bytes);
            move(tmp, target, mover);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Moves {@code source} over {@code target}, atomically where the file system can, else by a plain replacing move;
     * for a file another program wrote in the target's folder. {@code source} stays where it was on failure.
     */
    public static void move(final Path source, final Path target) throws IOException {
        move(source, target, Files::move);
    }

    /**
     * Gives {@code tmp} the permissions of the {@code target} it replaces: a temporary file is made readable by its
     * owner alone, and the move would carry that onto a file another program or person reads (a listing in the user's
     * folder). A new target keeps the temporary file's.
     */
    private static void keepMode(final Path target, final Path tmp) throws IOException {
        if (Files.exists(target) && Files.getFileStore(tmp).supportsFileAttributeView(PosixFileAttributeView.class)) {
            Files.setPosixFilePermissions(tmp, Files.getPosixFilePermissions(target));
        }
    }

    private static void move(final Path source, final Path target, final Mover mover) throws IOException {
        try {
            mover.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException e) {
            mover.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
