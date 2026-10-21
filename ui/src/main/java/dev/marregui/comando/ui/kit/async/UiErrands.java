package dev.marregui.comando.ui.kit.async;

import dev.marregui.comando.core.loop.Errands;

/**
 * The window's errands: the blocking calls the user's gestures start off the EDT (open a file or a link, write the
 * tutorial, an export, a diff, a notification), each on a virtual thread named for what it does. The window owns them:
 * closing it interrupts those still running and waits for them, bounded; a window opened later starts afresh.
 */
public final class UiErrands {

    private static volatile Errands current = new Errands("window");

    private UiErrands() {
    }

    /** Runs {@code task} on a virtual thread named {@code name}, owned by the window. */
    public static void start(String name, Runnable task) {
        current.start(name, task);
    }

    /** Closes the errands running now; later ones belong to the next window. */
    public static void closeAll() {
        Errands closing = current;
        current = new Errands("window");
        closing.close();
    }
}
