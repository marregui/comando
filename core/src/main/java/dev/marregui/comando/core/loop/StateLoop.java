package dev.marregui.comando.core.loop;

import dev.marregui.comando.core.diag.IntentEvent;
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

/**
 * The single thread that owns the state. Intents from every producer enter one queue; the loop applies
 * each with the reducer, publishes the new state, hands the effects out in order, then tells listeners.
 *
 * <p>The state is published through a volatile field, so any thread reads a complete, consistent
 * snapshot. Effect handlers and listeners run on this thread and must only hand work off.</p>
 */
public final class StateLoop implements IntentSink, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(StateLoop.class.getName());
    private static final Intent STOP = new Intent.Notify(Notice.Level.INFO, "stop", false);

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

    private final LinkedBlockingQueue<Intent> queue = new LinkedBlockingQueue<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<IntentObserver> observers = new CopyOnWriteArrayList<>();
    private final Reducer reducer;
    private final EffectHandler effects;
    private final Thread thread;
    private volatile AppState state;
    private volatile boolean halted;

    public StateLoop(AppState initial, Reducer reducer, EffectHandler effects) {
        this.state = initial;
        this.reducer = reducer;
        this.effects = effects;
        this.thread = Thread.ofPlatform().name("comando-reducer").daemon(true).unstarted(this::run);
    }

    public StateLoop start() {
        thread.start();
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
        IntentEvent event = new IntentEvent();
        event.begin();
        for (IntentObserver o : observers) {
            o.observe(intent);
        }
        AppState previous = state;
        Transition t;
        try {
            t = reducer.apply(previous, intent);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "reducer failed [intent=" + intent.getClass().getSimpleName() + ']', e);
            return;
        }
        state = t.state();
        for (Effect effect : t.effects()) {
            try {
                effects.handle(effect);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "effect failed [effect=" + effect.getClass().getSimpleName() + ']', e);
            }
        }
        if (previous != t.state()) {
            for (Listener l : listeners) {
                try {
                    l.onState(previous, t.state());
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.ERROR, "listener failed", e);
                }
            }
        }
        event.end();
        if (event.shouldCommit()) {
            event.type = intent.getClass().getSimpleName();
            event.effects = t.effects().size();
            event.changed = previous != t.state();
            event.commit();
        }
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
    }
}
