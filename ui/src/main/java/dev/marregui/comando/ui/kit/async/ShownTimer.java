package dev.marregui.comando.ui.kit.async;

import java.awt.Component;
import java.awt.event.HierarchyEvent;
import javax.swing.Timer;

/**
 * A repeating timer owned by a component that runs exactly while its owner wants ticks and is showing: a closed window
 * or a hidden tab stops it, and showing again resumes it only if it is still wanted. A running Swing timer keeps AWT,
 * and so the JVM, alive; this one cannot outlive what it paints. It ticks on a Swing timer of its own, or on a
 * {@link SharedClock} with the other timers of that clock, in the same event. EDT only.
 */
public final class ShownTimer {

    private final Component owner;
    private final Runnable tick;
    /** Its own Swing timer; null when it ticks on {@link #clock}. */
    private final Timer own;
    /** The clock it shares; null when it has a timer of its own. */
    private final SharedClock clock;
    private boolean wanted;
    private boolean running;

    public ShownTimer(final Component owner, final int delayMillis, final Runnable tick) {
        this(owner, new Timer(delayMillis, null), null, tick);
    }

    /** A timer that ticks on {@code clock}; made by {@link SharedClock#timer}. */
    ShownTimer(final Component owner, final SharedClock clock, final Runnable tick) {
        this(owner, null, clock, tick);
    }

    private ShownTimer(final Component owner, final Timer own, final SharedClock clock, final Runnable tick) {
        this.owner = owner;
        this.tick = tick;
        this.own = own;
        this.clock = clock;
        if (own != null) {
            own.addActionListener(e -> tick.run());
        }
        owner.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                update();
            }
        });
    }

    /** Records whether the owner wants ticks; the timer runs while this is true and the owner shows. */
    public void want(final boolean want) {
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
        return running;
    }

    /** The component whose window tells the shared clock how often to tick this timer. */
    Component owner() {
        return owner;
    }

    /** One tick from the shared clock: run unless a timer that ticked before it in the same beat stopped this one. */
    void beat() {
        if (running) {
            tick.run();
        }
    }

    private void update() {
        final boolean run = wanted && owner.isShowing();
        if (run == running) {
            return;
        }
        running = run;
        if (own != null) {
            if (run) {
                own.start();
            } else {
                own.stop();
            }
        } else if (run) {
            clock.join(this);
        } else {
            clock.leave(this);
        }
    }
}
