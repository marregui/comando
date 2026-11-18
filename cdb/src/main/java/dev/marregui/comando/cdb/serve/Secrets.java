package dev.marregui.comando.cdb.serve;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Set;

/**
 * The two secrets a client presents to the servers, one file each in the auth folder: {@code secret}, the owner's, and
 * {@code secret.readonly}. Each file is one line of 64 lowercase hex characters (32 random bytes), then a newline,
 * mode {@code rw-------}. {@link #load(Path)} makes a file that is absent and refuses one whose content is anything
 * else; rotating a secret is deleting its file and restarting the server.
 */
public final class Secrets {

    /** The owner's secret: every statement and line-protocol writes. */
    static final String OWNER_FILE = "secret";
    /** The read-only secret: SELECT, SHOW and EXPLAIN only. */
    static final String READ_ONLY_FILE = "secret.readonly";
    /** 32 bytes: the strength of a 256-bit key, written as hex. */
    private static final int SECRET_BYTES = 32;
    private static final int FILE_BYTES = 2 * SECRET_BYTES + 1;
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> OWNER_ONLY_FOLDER = PosixFilePermissions.fromString("rwx------");
    private static final byte[] HEX = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    private final byte[] owner;
    private final byte[] readOnly;

    private Secrets(final byte[] owner, final byte[] readOnly) {
        this.owner = owner;
        this.readOnly = readOnly;
    }

    /**
     * The secrets of {@code folder}, each made when its file is absent: written to a temporary file of mode
     * {@code rw-------} in the folder, then linked to its name, so a reader never sees half a file and two servers
     * starting at once end with one secret. The folder is made {@code rwx------} when absent. A file whose content is
     * not one secret, or two files holding the same secret, are refused, naming the file; a file readable by others is
     * narrowed to {@code rw-------}.
     */
    public static Secrets load(final Path folder) throws IOException {
        Files.createDirectories(folder, PosixFilePermissions.asFileAttribute(OWNER_ONLY_FOLDER));
        final byte[] owner = loadOrMake(folder.resolve(OWNER_FILE));
        final byte[] readOnly = loadOrMake(folder.resolve(READ_ONLY_FILE));
        if (MessageDigest.isEqual(owner, readOnly)) {
            throw new IOException("refuse a read-only secret equal to the owner's [file="
                    + folder.resolve(READ_ONLY_FILE) + ']');
        }
        return new Secrets(owner, readOnly);
    }

    /**
     * What {@code presented} lets its client do: {@link Role#NONE} when it is null or neither secret. Both secrets are
     * compared, each in a time independent of where the presented text differs from it.
     */
    Role roleOf(final String presented) {
        if (presented == null) {
            return Role.NONE;
        }
        final byte[] bytes = presented.getBytes(StandardCharsets.UTF_8);
        final boolean isOwner = MessageDigest.isEqual(owner, bytes);
        final boolean isReadOnly = MessageDigest.isEqual(readOnly, bytes);
        if (isOwner) {
            return Role.OWNER;
        }
        return isReadOnly ? Role.READ_ONLY : Role.NONE;
    }

    private static byte[] loadOrMake(final Path file) throws IOException {
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
            make(file);
        }
        return read(file);
    }

    // a server starting at the same time may link its file first: the loser keeps the winner's
    private static void make(final Path file) throws IOException {
        final byte[] random = new byte[SECRET_BYTES];
        new SecureRandom().nextBytes(random);
        final byte[] line = new byte[FILE_BYTES];
        for (int i = 0; i < SECRET_BYTES; i++) {
            line[2 * i] = HEX[(random[i] >> 4) & 0xF];
            line[2 * i + 1] = HEX[random[i] & 0xF];
        }
        line[FILE_BYTES - 1] = '\n';
        final Path temporary = Files.createTempFile(file.getParent(), '.' + file.getFileName().toString() + '-',
                ".tmp", PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        try {
            Files.write(temporary, line, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.createLink(file, temporary);
        } catch (final FileAlreadyExistsException _) {
            // another server made it between the check and the link; its secret is the one
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    // cdb depends on the JDK alone, so this 65-byte file is read through a channel, not core's mapped reader; nothing
    // is mapped, so no file cut under the read can meet a bulk copy
    private static byte[] read(final Path file) throws IOException {
        final ByteBuffer content = ByteBuffer.allocate(FILE_BYTES + 1);
        try (SeekableByteChannel channel = Files.newByteChannel(file,
                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            int read = 0;
            while (read >= 0 && content.hasRemaining()) {
                read = channel.read(content);
            }
        }
        if (content.position() != FILE_BYTES || content.get(FILE_BYTES - 1) != '\n') {
            throw refusal(file);
        }
        final byte[] secret = new byte[FILE_BYTES - 1];
        content.get(0, secret);
        for (final byte b : secret) {
            if ((b < '0' || b > '9') && (b < 'a' || b > 'f')) {
                throw refusal(file);
            }
        }
        // a copy made readable by others is narrowed back to the mode the file was made with
        if (!Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS).equals(OWNER_ONLY)) {
            Files.setPosixFilePermissions(file, OWNER_ONLY);
        }
        return secret;
    }

    private static IOException refusal(final Path file) {
        return new IOException("refuse a secret file that is not 64 lowercase hex characters and a newline [file="
                + file + ']');
    }
}
