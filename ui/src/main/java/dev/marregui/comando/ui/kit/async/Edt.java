package dev.marregui.comando.ui.kit.async;

import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;

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

    public static void later(Runnable task) {
        SwingUtilities.invokeLater(task);
    }

    public static void assertEdt() {
        assert SwingUtilities.isEventDispatchThread() : "not on the EDT: " + Thread.currentThread().getName();
    }
}
