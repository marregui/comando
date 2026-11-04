package dev.marregui.comando.ui.kit.async;

import java.awt.Component;
import java.util.Arrays;
import javax.swing.Timer;

/**
 * One repeating Swing timer that many {@link ShownTimer}s tick on: it runs while at least one of them runs, and each
 * beat ticks every running one in the same event, in the order they started. What they repaint in that event reaches
 * the repaint manager together and is painted in one pass, where timers of their own would each cause a pass (and on
 * macOS a present of the window) at their own phase: three spinners on three 80 ms timers caused up to three passes
 * per step (2026-11-02). EDT only.
 */
public final class SharedClock {

    private final Timer timer;
    /** The running timers, in the order they started; replaced, never changed in place, so a beat walks a snapshot. */
    private ShownTimer[] running = new ShownTimer[0];

    public SharedClock(final int delayMillis) {
        this.timer = new Timer(delayMillis, e -> beat());
    }

    /** A timer owned by {@code owner} that runs {@code tick} on this clock's beat while it is wanted and shows. */
    public ShownTimer timer(final Component owner, final Runnable tick) {
        return new ShownTimer(owner, this, tick);
    }

    /** Whether the clock's own timer runs: while any of its timers runs; for tests. */
    boolean ticking() {
        return timer.isRunning();
    }

    void join(final ShownTimer t) {
        running = Arrays.copyOf(running, running.length + 1);
        running[running.length - 1] = t;
        if (!timer.isRunning()) {
            timer.start();
        }
    }

    void leave(final ShownTimer t) {
        for (int i = 0; i < running.length; i++) {
            if (running[i] == t) {
                final ShownTimer[] rest = new ShownTimer[running.length - 1];
                System.arraycopy(running, 0, rest, 0, i);
                System.arraycopy(running, i + 1, rest, i, rest.length - i);
                running = rest;
                break;
            }
        }
        if (running.length == 0) {
            timer.stop();
        }
    }

    /** One beat: ticks the running timers; a tick that stops a timer or starts one changes the next beat, not this. */
    void beat() {
        for (final ShownTimer t : running) {
            t.beat();
        }
    }
}
