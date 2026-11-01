package dev.marregui.comando.ui.shell;

import java.util.function.Consumer;

/** Reaches the window's hook on its steps of closing from the application's ui tests, in another package. */
public final class ClosingSteps {

    private ClosingSteps() {
    }

    /**
     * Runs {@code hook} with the name of each step of closing of {@code ui}, just before the step, and with
     * {@code "teardown"} before the teardown's steps and {@code "close"} after the close's, outside their guard.
     */
    public static void beforeEach(final ComandoUi ui, final Consumer<String> hook) {
        ui.beforeEachStep(hook);
    }
}
