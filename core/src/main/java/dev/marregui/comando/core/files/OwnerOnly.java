package dev.marregui.comando.core.files;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Files and folders of the data root that only their owner may read (rule 15): a folder {@code rwx------}, a file
 * {@code rw-------}, set at creation and narrowed again at each start, whatever the umask comando started with or the
 * program that wrote the file last. A file system without POSIX permissions is left as it is. The one way comando makes
 * a file or folder owner-only, for every module.
 */
public final class OwnerOnly {

    private static final System.Logger LOG = System.getLogger(OwnerOnly.class.getName());
    private static final Set<PosixFilePermission> FOLDER = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE = PosixFilePermissions.fromString("rw-------");
    private static final FileAttribute<Set<PosixFilePermission>> BORN_FILE = PosixFilePermissions.asFileAttribute(FILE);

    private OwnerOnly() {
    }

    /** Creates {@code dir} and the folders above it that are missing, and narrows {@code dir} to its owner. */
    public static Path folder(final Path dir) throws IOException {
        Files.createDirectories(dir);
        narrow(dir);
        return dir;
    }

    /** Narrows {@code p}, a folder or a file, to its owner; a symbolic link is not followed. */
    public static void narrow(final Path p) throws IOException {
        if (Files.isSymbolicLink(p) || !Files.getFileStore(p).supportsFileAttributeView(PosixFileAttributeView.class)) {
            return;
        }
        final Set<PosixFilePermission> wanted = Files.isDirectory(p) ? FOLDER : FILE;
        if (!Files.getPosixFilePermissions(p).equals(wanted)) {
            Files.setPosixFilePermissions(p, wanted);
        }
    }

    /**
     * Creates {@code file}, which must not exist, owner-only from its creation, so no other user can read it in between
     * (a file holding a token); its folder must exist.
     */
    public static Path file(final Path file) throws IOException {
        try {
            return Files.createFile(file, BORN_FILE);
        } catch (final UnsupportedOperationException e) {
            LOG.log(System.Logger.Level.DEBUG, "no POSIX permissions on this file system [file=" + file + ']');
            return Files.createFile(file);
        }
    }

    /** A new empty file in the platform's temporary folder, owner-only from its creation, named {@code prefix…suffix}. */
    public static Path tempFile(final String prefix, final String suffix) throws IOException {
        try {
            return Files.createTempFile(prefix, suffix, BORN_FILE);
        } catch (final UnsupportedOperationException e) {
            LOG.log(System.Logger.Level.DEBUG, "no POSIX permissions on this file system");
            return Files.createTempFile(prefix, suffix);
        }
    }
}
