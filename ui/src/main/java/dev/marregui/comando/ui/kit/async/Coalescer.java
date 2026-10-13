package dev.marregui.comando.ui.kit.async;

import javax.swing.SwingUtilities;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Turns many requests from any thread into at most one pending run on the EDT. A request made while a run
 * is pending joins it; the run reads the latest data, so nothing is lost and the EDT is not flooded.
 */
public final class Coalescer {

    private final Runnable task;
    private final AtomicBoolean pending = new AtomicBoolean();

    public Coalescer(Runnable task) {
        this.task = task;
    }

    public void request() {
        if (pending.compareAndSet(false, true)) {
            SwingUtilities.invokeLater(() -> {
                pending.set(false);
                task.run();
            });
        }
    }
}
