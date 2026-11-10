package dev.marregui.comando.app.uilock;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * Keeps the JVMs of {@code :app:uiTest} one at a time on the machine, across checkouts, users and direct
 * {@code ./gradlew} runs: their windows share one desktop. Active only when {@code comando.uiTests=true}, which the
 * {@code uiTest} task sets; plain {@code test} runs take no lock. The lock is taken when the launcher session opens and
 * never released: the channel stays open until the JVM ends, however it ends, and the kernel frees the lock then. The
 * lock file and its folder are never reached through a symbolic link, so a link planted in the shared folder cannot
 * make another file writable by everyone.
 */
public final class UiTestLock implements LauncherSessionListener {

    private static final System.Logger LOG = System.getLogger(UiTestLock.class.getName());
    private static final Set<PosixFilePermission> ANYONE_FOLDER = PosixFilePermissions.fromString("rwxrwxrwx");
    private static final Set<PosixFilePermission> ANYONE_FILE = PosixFilePermissions.fromString("rw-rw-rw-");
    /**
     * How long a uiTest JVM waits for another to end before it fails: the whole suite took 6 min on 2026-11-10 (Apple
     * silicon), so a holder past five times that is hung, and the wait ends as a failure that names the lock.
     */
    static final Duration PATIENCE = Duration.ofMinutes(30);
    /** Between two tries of a lock held by another JVM; a wait is logged again after {@link #LOG_EVERY}. */
    private static final long RETRY_MILLIS = 250L;
    private static final Duration LOG_EVERY = Duration.ofSeconds(30);

    /** Open for the JVM's life, so the lock is never released before it ends; guarded by the class. */
    private static FileChannel held;

    @Override
    public void launcherSessionOpened(final LauncherSession session) {
        if (!Boolean.getBoolean("comando.uiTests")) {
            return;
        }
        final Path file = machineFile();
        try {
            hold(file, PATIENCE);
        } catch (final IOException e) {
            throw new IllegalStateException("cannot take the uiTest lock [file=" + file + ']', e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for the uiTest lock [file=" + file + ']', e);
        }
    }

    /**
     * Takes the lock on {@code file} for the rest of this JVM's life, waiting while another process holds it, at most
     * {@code patience}: past it the wait fails with an {@link IllegalStateException} naming the file. A second call in
     * the same JVM returns at once. A folder or file that is a symbolic link fails with an {@link IOException}.
     */
    static synchronized void hold(final Path file, final Duration patience) throws IOException, InterruptedException {
        if (held != null) {
            return;
        }
        final Path folder = file.getParent();
        Files.createDirectories(folder);
        if (Files.isSymbolicLink(folder)) {
            throw new IOException("the uiTest lock's folder is a link [folder=" + folder + ']');
        }
        openToAnyone(folder, ANYONE_FOLDER);
        createOpenToAnyone(file);
        final long start = System.nanoTime();
        long logged = start - LOG_EVERY.toNanos();
        while (true) {
            final FileChannel channel = openOrNull(file);
            if (channel != null) {
                final FileLock lock = channel.tryLock();
                if (lock != null) {
                    held = channel;
                    return;
                }
                channel.close();
            }
            final long now = System.nanoTime();
            if (now - start - patience.toNanos() > 0) {
                throw new IllegalStateException("the uiTest lock is still held by another JVM [lock=" + file
                        + ", waitedSeconds=" + TimeUnit.NANOSECONDS.toSeconds(now - start) + ']');
            }
            if (now - logged - LOG_EVERY.toNanos() >= 0) {
                LOG.log(System.Logger.Level.INFO, "waiting for another uiTest JVM to end [lock=" + file + ']');
                logged = now;
            }
            Thread.sleep(RETRY_MILLIS);
        }
    }

    /** {@code /tmp/comando/uiTest.lock}, one path for every user; on Windows under {@code java.io.tmpdir}. */
    static Path machineFile() {
        final boolean windows = System.getProperty("os.name").startsWith("Windows");
        return Path.of(windows ? System.getProperty("java.io.tmpdir") : "/tmp", "comando", "uiTest.lock");
    }

    /**
     * Creates {@code file} when it is missing and opens it to anyone; a file another run created first is left as it
     * is. Only a file this call created is changed, so a link put in its place is never followed.
     */
    private static void createOpenToAnyone(final Path file) throws IOException {
        try {
            Files.createFile(file);
        } catch (final FileAlreadyExistsException _) {
            return;
        }
        openToAnyone(file, ANYONE_FILE);
    }

    /**
     * The lock file opened for writing, never through a link; null while it cannot be opened yet: another user created
     * it and has not yet opened it to anyone.
     */
    private static FileChannel openOrNull(final Path file) throws IOException {
        try {
            return FileChannel.open(file, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        } catch (final AccessDeniedException _) {
            return null;
        }
    }

    /** Another user's run must open the same folder and file; the umask strips these bits at creation. */
    private static void openToAnyone(final Path path, final Set<PosixFilePermission> wanted) throws IOException {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
                && !Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(wanted)) {
            Files.setPosixFilePermissions(path, wanted);
        }
    }
}
