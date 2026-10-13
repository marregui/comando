package dev.marregui.comando.ui.kit.async;

import javax.swing.Timer;

/** Runs a task on the EDT once the requests stop for {@code delayMillis}. EDT only. */
public final class Debouncer {

    private final Timer timer;

    public Debouncer(int delayMillis, Runnable task) {
        timer = new Timer(delayMillis, e -> task.run());
        timer.setRepeats(false);
    }

    public void request() {
        timer.restart();
    }

    public void stop() {
        timer.stop();
    }
}
