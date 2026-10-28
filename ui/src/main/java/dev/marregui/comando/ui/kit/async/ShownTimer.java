package dev.marregui.comando.ui.kit.async;

import javax.swing.Timer;
import java.awt.Component;
import java.awt.event.HierarchyEvent;

/**
 * A repeating timer owned by a component that runs exactly while its owner wants ticks and is showing: a closed window
 * or a hidden tab stops it, and showing again resumes it only if it is still wanted. A running Swing timer keeps AWT,
 * and so the JVM, alive; this one cannot outlive what it paints. EDT only.
 */
public final class ShownTimer {

    private final Component owner;
    private final Timer timer;
    private boolean wanted;

    public ShownTimer(Component owner, int delayMillis, Runnable tick) {
        this.owner = owner;
        this.timer = new Timer(delayMillis, e -> tick.run());
        owner.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                update();
            }
        });
    }

    /** Records whether the owner wants ticks; the timer runs while this is true and the owner shows. */
    public void want(boolean want) {
        wanted = want;
        update();
    }

    /** Wants no more ticks, and stops. */
    public void stop() {
        want(false);
    }

    /** Whether the owner wants ticks, showing or not. */
    public boolean wanted() {
        return wanted;
    }

    /** Whether the timer runs now. */
    public boolean running() {
        return timer.isRunning();
    }

    private void update() {
        boolean run = wanted && owner.isShowing();
        if (run && !timer.isRunning()) {
            timer.start();
        } else if (!run && timer.isRunning()) {
            timer.stop();
        }
    }
}
