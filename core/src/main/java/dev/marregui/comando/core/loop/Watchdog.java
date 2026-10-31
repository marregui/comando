package dev.marregui.comando.core.loop;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The one watchdog thread: every {@link #PERIOD_MILLIS} it runs each look registered with it, in the order they came.
 * It is a platform thread so that it runs while what it watches is held: the reducer, the EDT, or the virtual threads'
 * scheduler. A look must not block; it reads timestamps and, at most, a stack. The thread starts with the first look
 * and ends a period after the last one leaves.
 *
 * <p>A look that throws a {@link RuntimeException} is kept and runs again next period; one that throws an
 * {@link Error} is dropped with its stack logged, and the others go on being run.</p>
 */
public final class Watchdog {

    private static final System.Logger LOG = System.getLogger(Watchdog.class.getName());
    public static final long PERIOD_MILLIS = 100;
    private static final Watchdog SHARED = new Watchdog();

    private final CopyOnWriteArrayList<Runnable> looks = new CopyOnWriteArrayList<>();
    /** The running thread, null while there are no looks. Guarded by {@code this}. */
    private Thread thread;

    private Watchdog() {
    }

    /** The application's watchdog. */
    public static Watchdog shared() {
        return SHARED;
    }

    /** Runs {@code look} every period until the returned handle is closed. */
    public AutoCloseable watch(Runnable look) {
        Runnable mine = look::run; // its own identity, so one look added twice leaves once per handle
        synchronized (this) {
            looks.add(mine);
            if (thread == null) {
                thread = Thread.ofPlatform().name("watchdog").daemon(true).start(this::run);
            }
        }
        return () -> looks.remove(mine);
    }

    private void run() {
        try {
            while (true) {
                try {
                    Thread.sleep(PERIOD_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                synchronized (this) {
                    if (looks.isEmpty()) {
                        thread = null;
                        return;
                    }
                }
                for (Runnable look : looks) {
                    try {
                        look.run();
                    } catch (RuntimeException e) {
                        LOG.log(System.Logger.Level.ERROR, "a watchdog look failed", e);
                    } catch (final Error e) {
                        // the look's own state is unknown after an error: it is not run again
                        looks.remove(look);
                        LOG.log(System.Logger.Level.ERROR, "a watchdog look failed and is no longer run [looks="
                                + looks.size() + ']', e);
                    }
                }
            }
        } finally {
            // an end other than the empty list above (an error outside a look) lets the next watch start a new thread
            synchronized (this) {
                if (thread == Thread.currentThread()) {
                    thread = null;
                }
            }
        }
    }
}
