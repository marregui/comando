package dev.marregui.comando.app.uilock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A child JVM of {@code UiTestLockTest}: writes its first marker file, takes the lock on the file it is given as a
 * uiTest JVM does, writes its second marker file once it holds it, then lives until it is ended, at most
 * {@link #LIFE_MILLIS}.
 */
public final class UiTestLockHolder {

    /** Past any wait of the test, so a child the test failed to end still goes away by itself. */
    private static final long LIFE_MILLIS = 60_000L;

    private UiTestLockHolder() {
    }

    /** {@code args}: the lock file, the marker written before the lock call, the marker written once it holds it. */
    public static void main(final String[] args) throws IOException, InterruptedException {
        Files.writeString(Path.of(args[1]), "locking");
        UiTestLock.hold(Path.of(args[0]), UiTestLock.PATIENCE);
        Files.writeString(Path.of(args[2]), "held");
        Thread.sleep(LIFE_MILLIS);
    }
}
