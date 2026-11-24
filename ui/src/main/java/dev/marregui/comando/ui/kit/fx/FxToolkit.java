package dev.marregui.comando.ui.kit.fx;

import java.awt.GraphicsEnvironment;

import javafx.application.Platform;

/** Starts JavaFX once for this process, for the views that draw with it inside Swing: pages and videos. */
public final class FxToolkit {

    /** Whether JavaFX was started; written and read on the EDT only. */
    private static boolean started;

    private FxToolkit() {
    }

    /**
     * Starts JavaFX on the first call; later calls do nothing. EDT.
     *
     * @throws UnsupportedOperationException when this process has no display, or JavaFX cannot start
     */
    public static void start() {
        if (started) {
            return;
        }
        if (GraphicsEnvironment.isHeadless()) {
            throw new UnsupportedOperationException("cannot start JavaFX without a display [java.awt.headless=true]");
        }
        try {
            Platform.startup(() -> {
            });
        } catch (final IllegalStateException _) {
            // a JFXPanel made elsewhere in this process started it already
        } catch (final RuntimeException | LinkageError e) {
            throw new UnsupportedOperationException("cannot start JavaFX [cause=" + e + ']', e);
        }
        // closing the last view must not end JavaFX: a later view could not start it again
        Platform.setImplicitExit(false);
        started = true;
    }
}
