package dev.marregui.comando.ui.kit.async;

import java.awt.Component;
import java.awt.Frame;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.util.Arrays;
import java.util.function.Function;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * One repeating Swing timer that many {@link ShownTimer}s tick on: it runs while at least one of them runs, and each
 * beat ticks every running one in the same event, in the order they started. What they repaint in that event reaches
 * the repaint manager together and is painted in one pass, where timers of their own would each cause a pass (and on
 * macOS a present of the window) at their own phase: three spinners on three 80 ms timers caused up to three passes
 * per step (2026-11-02).
 * <p>
 * A timer ticks on every beat while its owner's window is in front, on one beat in {@link #BEHIND_EVERY_BEATS} while
 * the window is behind another application's, and not at all while it is minimised: on macOS a pass waits for the
 * window's last present to the screen, so ticks nobody looks at cost the EDT (2026-11-10: the EDT waited up to 49 % of
 * a busy minute, most of it under the spinners' passes). The window's state is read at each beat. EDT only.
 */
public final class SharedClock {

    /**
     * A timer whose owner's window is behind ticks on one beat in this many: about two ticks a second on the spinners'
     * 80 ms beat (Miguel, 2026-11-10).
     */
    static final int BEHIND_EVERY_BEATS = 6;

    private final Function<Component, Seen> seen;
    private final Timer timer;
    /** The beat's place in a round of {@link #BEHIND_EVERY_BEATS}; the timers behind tick when it is 0. */
    private int beatInRound;
    /** The running timers, in the order they started; replaced, never changed in place, so a beat walks a snapshot. */
    private ShownTimer[] running = new ShownTimer[0];

    public SharedClock(final int delayMillis) {
        this(delayMillis, SharedClock::seen);
    }

    /** A clock that asks {@code seen} how each owner's window is seen; for tests, which have no window to ask. */
    SharedClock(final int delayMillis, final Function<Component, Seen> seen) {
        this.timer = new Timer(delayMillis, e -> beat());
        this.seen = seen;
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

    /**
     * One beat: ticks the running timers whose windows are seen enough for this beat; a tick that stops a timer or
     * starts one changes the next beat, not this.
     */
    void beat() {
        final boolean behindToo = beatInRound == 0;
        beatInRound = (beatInRound + 1) % BEHIND_EVERY_BEATS;
        for (final ShownTimer t : running) {
            final Seen s = seen.apply(t.owner());
            if (s == Seen.FRONT || (s == Seen.BEHIND && behindToo)) {
                t.beat();
            }
        }
    }

    /**
     * How {@code owner}'s window is seen: minimised while its frame is iconified; in front while any of comando's
     * windows is active (the main window, a dialog, the diff window: comando is the application in front); else
     * behind. An owner in no window counts as in front.
     */
    static Seen seen(final Component owner) {
        final Window w = owner instanceof Window self ? self : SwingUtilities.getWindowAncestor(owner);
        if (w == null) {
            return Seen.FRONT;
        }
        final Frame f = frameOf(w);
        return seen(f != null && (f.getExtendedState() & Frame.ICONIFIED) != 0,
                KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow() != null);
    }

    /** How a window is seen when it is {@code minimised}, and when comando is {@code inFront} of other applications. */
    static Seen seen(final boolean minimised, final boolean inFront) {
        if (minimised) {
            return Seen.MINIMISED;
        }
        return inFront ? Seen.FRONT : Seen.BEHIND;
    }

    /** The frame {@code w} is or belongs to, through its owners; null when none is a frame. */
    private static Frame frameOf(final Window w) {
        for (Window o = w; o != null; o = o.getOwner()) {
            if (o instanceof Frame f) {
                return f;
            }
        }
        return null;
    }

    /** How an owner's window can be seen. */
    enum Seen {
        /** one of comando's windows is active: comando is the application in front */
        FRONT,
        /** the window shows, but another application is in front */
        BEHIND,
        /** the window is minimised to the Dock */
        MINIMISED
    }
}
