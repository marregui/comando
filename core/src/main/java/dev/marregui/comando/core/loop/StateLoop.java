package dev.marregui.comando.core.loop;

import dev.marregui.comando.core.effect.Effect;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.reduce.Reducer;
import dev.marregui.comando.core.reduce.Transition;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.Notice;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The single thread that owns the state. Intents from every producer enter one queue; the loop applies
 * each with the reducer, publishes the new state, hands the effects out in order, then tells listeners.
 *
 * <p>The state is published through a volatile field, so any thread reads a complete, consistent
 * snapshot. Effect handlers and listeners run on this thread and must only hand work off.</p>
 *
 * <p>An intent that takes {@link #STALL_MILLIS} or more is a stall, told once it ends with the part that took
 * longest (the observers, the reducer, an effect, the listeners). The shared {@link Watchdog} looks at the loop every
 * {@link #PERIOD_MILLIS}; an intent still applying at {@link #HANG_MILLIS} is a hang, told then with the loop's stack,
 * so a part that blocks is seen while it blocks, not only if it ever ends.</p>
 */
public final class StateLoop implements IntentSink, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(StateLoop.class.getName());
    private static final Intent STOP = new Intent.Notify(Notice.Level.INFO, "stop", false);

    public static final long PERIOD_MILLIS = Watchdog.PERIOD_MILLIS;
    public static final long STALL_MILLIS = 200;
    /** An intent still applying this long is reported with the loop's stack. */
    public static final long HANG_MILLIS = 1_000;

    /** Receives every state change: {@code previous} and {@code next} differ by one intent. */
    @FunctionalInterface
    public interface Listener {
        void onState(AppState previous, AppState next);
    }

    /** Receives the effects of each transition. */
    @FunctionalInterface
    public interface EffectHandler {
        void handle(Effect effect);
    }

    /** Sees every intent before it is applied: the replay log. */
    @FunctionalInterface
    public interface IntentObserver {
        void observe(Intent intent);
    }

    /**
     * A stall as the loop tells it.
     *
     * @param part   what the loop was applying: {@code observe}, {@code reduce <Intent>}, {@code effect <Effect>} or
     *               {@code listeners}; when ended, the part that took longest
     * @param millis how long the intent had been applying, or took when ended
     * @param ended  false for a hang told while the intent still applies, true once the intent is done
     * @param stack  the loop's stack at the hang; empty when ended
     * @param since  the {@link System#nanoTime} the intent started at, which ties a hang to its end
     */
    record Stall(String part, long millis, boolean ended, String stack, long since) {
    }

    /** The hang whose stack the log holds: the intent's start and the time just before the entry was written. */
    private record Logged(long since, long atMillis) {
    }

    /** What the loop applies now: the intent's start, the part, the thread; null when idle. */
    private record Busy(long sinceNanos, String part, Thread thread) {
    }

    private final LinkedBlockingQueue<Intent> queue = new LinkedBlockingQueue<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<IntentObserver> observers = new CopyOnWriteArrayList<>();
    private final Reducer reducer;
    private final EffectHandler effects;
    private final Thread thread;
    /** The look at the loop on the shared {@link Watchdog}, from the start to the close. */
    private volatile AutoCloseable watchdog;
    private final long stallMillis;
    private final long hangMillis;
    private final Consumer<Stall> stalls;
    private volatile AppState state;
    private volatile boolean halted;
    private volatile Busy busy;
    // confined to the thread applying an intent
    private long partStart;
    private String slowestPart;
    private long slowestNanos = -1;
    // the watchdog's own: the start of the intent last told as a hang
    private long hangTold = Long.MIN_VALUE;
    // written by the watchdog's log of a hang, read by the log of its end on the loop's thread
    private volatile Logged hangLogged;

    public StateLoop(AppState initial, Reducer reducer, EffectHandler effects) {
        this(initial, reducer, effects, STALL_MILLIS, HANG_MILLIS, null);
    }

    /** With other limits and a receiver of every stall in place of the log; for tests. */
    StateLoop(AppState initial, Reducer reducer, EffectHandler effects, long stallMillis, long hangMillis, Consumer<Stall> stalls) {
        this.state = initial;
        this.reducer = reducer;
        this.effects = effects;
        this.stallMillis = stallMillis;
        this.hangMillis = hangMillis;
        this.stalls = stalls == null ? this::log : stalls;
        this.thread = Thread.ofPlatform().name("comando-reducer").daemon(true).unstarted(this::run);
    }

    public StateLoop start() {
        thread.start();
        watchdog = Watchdog.shared().watch(this::look);
        return this;
    }

    @Override
    public void dispatch(Intent intent) {
        if (!halted) {
            queue.add(intent);
        }
    }

    public AppState state() {
        return state;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void addObserver(IntentObserver observer) {
        observers.add(observer);
    }

    /** Applies one intent on the caller's thread; for tests that drive the loop without its thread. */
    public void applyNow(Intent intent) {
        apply(intent);
    }

    private void run() {
        // ends at the STOP marker, not at the halt flag, so the intents queued before close still apply
        while (true) {
            Intent intent;
            try {
                intent = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (intent == STOP) {
                return;
            }
            apply(intent);
        }
    }

    private void apply(Intent intent) {
        long start = System.nanoTime();
        try {
            enter(start, "observe");
            for (IntentObserver o : observers) {
                o.observe(intent);
            }
            AppState previous = state;
            Transition t;
            enter(start, "reduce " + intent.getClass().getSimpleName());
            try {
                t = reducer.apply(previous, intent);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "reducer failed [intent=" + intent.getClass().getSimpleName() + ']', e);
                return;
            }
            state = t.state();
            for (Effect effect : t.effects()) {
                enter(start, "effect " + effect.getClass().getSimpleName());
                try {
                    effects.handle(effect);
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.ERROR, "effect failed [effect=" + effect.getClass().getSimpleName() + ']', e);
                }
            }
            if (previous != t.state()) {
                enter(start, "listeners");
                for (Listener l : listeners) {
                    try {
                        l.onState(previous, t.state());
                    } catch (RuntimeException e) {
                        LOG.log(System.Logger.Level.ERROR, "listener failed", e);
                    }
                }
            }
        } finally {
            finish(start);
        }
    }

    /** Ends the part under way, keeping it if it is the slowest so far, and starts {@code part}. */
    private void enter(long start, String part) {
        long now = System.nanoTime();
        Busy b = busy;
        if (b != null && b.sinceNanos() == start && now - partStart > slowestNanos) {
            slowestNanos = now - partStart;
            slowestPart = b.part();
        }
        partStart = now;
        busy = part == null ? null : new Busy(start, part, Thread.currentThread());
    }

    private void finish(long start) {
        enter(start, null);
        long millis = (System.nanoTime() - start) / 1_000_000;
        if (millis >= stallMillis) {
            stalls.accept(new Stall(slowestPart, millis, true, "", start));
        }
        slowestPart = null;
        slowestNanos = -1;
    }

    /**
     * One look at the loop, on the watchdog's thread every period until the close, which comes after the loop has applied
     * what was queued, so the shutdown is watched too.
     */
    private void look() {
        Busy b = busy;
        if (b != null && b.sinceNanos() != hangTold) {
            long millis = (System.nanoTime() - b.sinceNanos()) / 1_000_000;
            if (millis >= hangMillis) {
                hangTold = b.sinceNanos();
                stalls.accept(new Stall(b.part(), millis, false, format(b.thread().getStackTrace()), b.sinceNanos()));
            }
        }
    }

    /**
     * A stall below the hang limit is a line, a hang a warning with the stack while it holds and a warning when it
     * ends; the user hears of one that lasted the hang limit or more, since nothing in the window changed meanwhile. A
     * click on the notice opens the log at the hang's stack, or at the end's line when the hang ended before the
     * watchdog looked.
     */
    private void log(Stall stall) {
        if (!stall.ended()) {
            // known before the entry is written: the intent can end while the log writes it
            hangLogged = new Logged(stall.since(), System.currentTimeMillis());
            LOG.log(System.Logger.Level.WARNING, "the reducer has not finished an intent for " + stall.millis() + " ms [part="
                    + stall.part() + "]:\n" + stall.stack());
        } else if (stall.millis() >= hangMillis) {
            long at = System.currentTimeMillis();
            LOG.log(System.Logger.Level.WARNING, "the reducer finished an intent after " + stall.millis() + " ms [part=" + stall.part() + ']');
            Logged hang = hangLogged;
            long where = hang != null && hang.since() == stall.since() ? hang.atMillis() : at;
            dispatch(new Intent.Notify(Notice.Level.WARN, "comando stopped updating for " + stall.millis()
                    + " ms; click to see where in the log", false, Notice.log(where)));
        } else {
            LOG.log(System.Logger.Level.INFO, "the reducer was busy for " + stall.millis() + " ms [part=" + stall.part() + ']');
        }
    }

    private static String format(StackTraceElement[] frames) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(frames.length, 40); i++) {
            sb.append("\tat ").append(frames[i]).append('\n');
        }
        return sb.toString();
    }

    /** Stops after the intents already queued. Idempotent. */
    @Override
    public void close() {
        if (halted) {
            return;
        }
        halted = true; // later dispatches are dropped; everything queued before the marker still applies
        queue.add(STOP);
        try {
            thread.join(TimeUnit.SECONDS.toMillis(2));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        AutoCloseable w = watchdog;
        if (w != null) {
            try {
                w.close();
            } catch (Exception e) {
                LOG.log(System.Logger.Level.WARNING, "cannot stop watching the loop: " + e.getMessage());
            }
        }
    }
}
