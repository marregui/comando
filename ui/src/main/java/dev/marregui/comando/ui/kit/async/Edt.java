package dev.marregui.comando.ui.kit.async;

import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;
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

    /** Runs now when on the EDT, else on the EDT and waits for it; for shutdown, never from a lock the EDT needs. */
    public static void runAndWait(Runnable task) {
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(task);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("EDT task failed", e.getCause());
        }
    }

    /**
     * Runs now when on the EDT, else on the EDT and waits at most {@code bound} for it: for shutdown, where an EDT
     * that does not answer must not hold the closing thread forever. A task that has not started by then never runs.
     *
     * @return true when the task ran to its end within the bound
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
