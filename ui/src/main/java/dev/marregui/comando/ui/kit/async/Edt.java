package dev.marregui.comando.ui.kit.async;

import javax.swing.SwingUtilities;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Hand-off to the event dispatch thread. */
public final class Edt {

    private Edt() {
    }

    /** Runs now when on the EDT, else posts. */
    public static void run(Runnable task) {
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
    public static boolean runAndWait(Runnable task, Duration bound) {
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
            return true;
        }
        FutureTask<Void> future = new FutureTask<>(task, null);
        SwingUtilities.invokeLater(future);
        try {
            future.get(bound.toNanos(), TimeUnit.NANOSECONDS);
            return true;
        } catch (InterruptedException e) {
            future.cancel(false);
            Thread.currentThread().interrupt();
            return false;
        } catch (TimeoutException e) {
            future.cancel(false);
            return false;
        } catch (ExecutionException e) {
            throw new IllegalStateException("EDT task failed", e.getCause());
        }
    }

    public static void later(Runnable task) {
        SwingUtilities.invokeLater(task);
    }
}
