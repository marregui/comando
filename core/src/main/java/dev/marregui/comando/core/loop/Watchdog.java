package dev.marregui.comando.core.loop;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * The one watchdog thread: every {@link #PERIOD_MILLIS} it runs each look registered with it, in the order they came.
 * It is a platform thread so that it runs while what it watches is held: the reducer, the EDT, or the virtual threads'
 * scheduler. A look must not block; it reads timestamps and, at most, a stack. The thread starts with the first look
 * and ends a period after the last one leaves.
 *
 * <p>A look that throws a {@link RuntimeException} is kept and runs again next period: its first failure is logged with
 * its stack, the later ones are counted and the count logged at most once a minute. One that throws an {@link Error} is
 * dropped with its stack logged, and the others go on being run.</p>
 */
public final class Watchdog {

    private static final System.Logger LOG = System.getLogger(Watchdog.class.getName());
    public static final long PERIOD_MILLIS = 100;
    private static final Watchdog SHARED = new Watchdog();

    private final CopyOnWriteArrayList<Runnable> looks = new CopyOnWriteArrayList<>();
    private final Repeats failures = new Repeats(LOG, Repeats.TELL_EVERY_NANOS);
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
        return () -> {
            looks.remove(mine);
            failures.forget(mine);
        };
    }

    private void run() {
        try {
            while (true) {
                try {
                    Thread.sleep(PERIOD_MILLIS);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                synchronized (this) {
                    if (looks.isEmpty()) {
                        thread = null;
                        return;
                    }
                }
                for (final Runnable look : looks) {
                    try {
                        look.run();
                    } catch (final RuntimeException e) {
                        // a look closed while it ran is not recorded: its close has forgotten it, and a new entry would
                        // stay for good. Checked under the lock forget takes, after the close removed the look
                        failures.failed(look, "a watchdog look failed", "look=" + look, e, System.nanoTime(),
                                () -> looks.contains(look));
                    } catch (final Error e) {
                        // the look's own state is unknown after an error: it is not run again
                        looks.remove(look);
                        failures.forget(look);
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

    /**
     * A failure that recurs, logged without filling the log: the first one of a kind with its stack, then how many more
     * came, at most once per bound, with the last one's message. A kind is a key with an exception class and the frame
     * that threw, so a second, different failure of the same key is told with its own stack. A count not yet told is
     * told when its key is forgotten and on {@link #flush}. Thread-safe.
     */
    static final class Repeats {

        /** At most one count line a minute per failing kind: a failure each watchdog period would be 600 lines. */
        static final long TELL_EVERY_NANOS = TimeUnit.MINUTES.toNanos(1);

        private final System.Logger log;
        private final long tellEveryNanos;
        /** Per failing kind: what to tell of it. Guarded by this. */
        private final Map<Kind, Seen> seen = new HashMap<>();

        Repeats(final System.Logger log, final long tellEveryNanos) {
            this.log = log;
            this.tellEveryNanos = tellEveryNanos;
        }

        /** Records that {@code key} failed with {@code e} at {@code nowNanos}; logs as the class says. */
        void failed(final Object key, final String what, final String context, final Throwable e, final long nowNanos) {
            failed(key, what, context, e, nowNanos, () -> true);
        }

        /**
         * As {@link #failed(Object, String, String, Throwable, long)}, recording nothing unless {@code live}, which is
         * read under the lock {@link #forget} takes: a key its owner removed before forgetting it is not recorded again.
         */
        void failed(final Object key, final String what, final String context, final Throwable e, final long nowNanos,
                final BooleanSupplier live) {
            final StackTraceElement[] stack = e.getStackTrace();
            final Kind kind = new Kind(key, e.getClass(), stack.length == 0 ? null : stack[0]);
            final long count;
            synchronized (this) {
                if (!live.getAsBoolean()) {
                    return;
                }
                final Seen mine = seen.get(kind);
                if (mine == null) {
                    seen.put(kind, new Seen(what, context, nowNanos));
                    count = 0L;
                } else {
                    mine.count++;
                    mine.last = e.toString();
                    if (nowNanos - mine.toldNanos < tellEveryNanos) {
                        return;
                    }
                    count = mine.count;
                    mine.count = 0L;
                    mine.toldNanos = nowNanos;
                }
            }
            if (count == 0L) {
                log.log(System.Logger.Level.ERROR, what + "; repeats are counted [" + context + ']', e);
            } else {
                tell(what, context, count, e.toString());
            }
        }

        /** Drops what is kept for {@code key}, which no longer runs, telling a count not yet told. */
        void forget(final Object key) {
            final List<Seen> pending = new ArrayList<>();
            synchronized (this) {
                final Iterator<Map.Entry<Kind, Seen>> it = seen.entrySet().iterator();
                while (it.hasNext()) {
                    final Map.Entry<Kind, Seen> entry = it.next();
                    if (Objects.equals(entry.getKey().key(), key)) {
                        it.remove();
                        if (entry.getValue().count > 0) {
                            pending.add(entry.getValue());
                        }
                    }
                }
            }
            pending.forEach(p -> tell(p.what, p.context, p.count, p.last));
        }

        /** Tells every count not yet told, as its owner stops. */
        void flush() {
            final List<Seen> pending = new ArrayList<>();
            synchronized (this) {
                for (final Seen mine : seen.values()) {
                    if (mine.count > 0) {
                        pending.add(new Seen(mine));
                        mine.count = 0L;
                    }
                }
            }
            pending.forEach(p -> tell(p.what, p.context, p.count, p.last));
        }

        private void tell(final String what, final String context, final long times, final String last) {
            log.log(System.Logger.Level.ERROR, what + " again [" + context + ", times=" + times + ", last=" + last + ']');
        }

        /** A key failing in one way: the exception's class and the frame that threw it ({@code null} for none). */
        private record Kind(Object key, Class<?> type, StackTraceElement top) {
        }

        /** What is told of one kind: the failures since the last line, when that line was written, the last message. */
        private static final class Seen {
            private final String what;
            private final String context;
            private long count;
            private long toldNanos;
            private String last;

            Seen(final String what, final String context, final long toldNanos) {
                this.what = what;
                this.context = context;
                this.toldNanos = toldNanos;
            }

            Seen(final Seen other) {
                this(other.what, other.context, other.toldNanos);
                this.count = other.count;
                this.last = other.last;
            }
        }
    }
}
