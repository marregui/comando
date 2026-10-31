package dev.marregui.comando.ui.kit.async;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.swing.SwingUtilities;

/** Hand-off to the event dispatch thread. */
public final class Edt {

    private Edt() {
    }

    /** Runs now when on the EDT, else posts. */
    public static void run(final Runnable task) {
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    /**
     * Runs now when on the EDT, else on the EDT, waiting at most {@code bound}; true when the task ran to its end
     * within the bound. For shutdown, where an EDT that does not answer must not hold the closing thread forever. A
     * task that has not started by then never runs.
     */
    public static boolean runAndWait(final Runnable task, final Duration bound) {
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
            return true;
        }
        final FutureTask<Void> future = new FutureTask<>(task, null);
        SwingUtilities.invokeLater(future);
        try {
            future.get(bound.toNanos(), TimeUnit.NANOSECONDS);
            return true;
        } catch (final InterruptedException e) {
            future.cancel(false);
            Thread.currentThread().interrupt();
            return false;
        } catch (final TimeoutException e) {
            future.cancel(false);
            return false;
        } catch (final ExecutionException e) {
            throw new IllegalStateException("EDT task failed", e.getCause());
        }
    }

    public static void later(final Runnable task) {
        SwingUtilities.invokeLater(task);
    }
}
