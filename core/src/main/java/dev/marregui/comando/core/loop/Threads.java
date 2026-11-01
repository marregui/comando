package dev.marregui.comando.core.loop;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * comando's threads by kind ({@code docs/architecture.md} §6). Two event loops (the EDT, the reducer), one
 * {@link Watchdog} and one stdout reader per running Claude session are platform threads; everything else runs on
 * virtual threads, which hold no operating-system thread while they wait on a queue, a lock, a socket or a process's
 * exit. A virtual thread blocked reading a file or a process pipe does hold one (measured on JDK 25), so such reads
 * are either short or on the session reader.
 */
public final class Threads {

    /** Every thread made here, held weakly: the JDK lists no virtual thread, and tests look for strands by name. */
    private static final Map<Thread, Boolean> MADE = new WeakHashMap<>();

    private Threads() {
    }

    /** Every live thread made here, virtual ones included; for tests that check a close left none of an owner's. */
    public static List<Thread> alive() {
        synchronized (MADE) {
            return MADE.keySet().stream().filter(Thread::isAlive).toList();
        }
    }

    /** The live threads made here named {@code name}, virtual ones included; for tests. */
    public static List<Thread> alive(final String name) {
        synchronized (MADE) {
            return MADE.keySet().stream().filter(t -> t.isAlive() && t.getName().equals(name)).toList();
        }
    }

    /** A virtual thread named {@code name}, not started, kept in the list {@link #alive} reads. */
    static Thread virtual(final String name, final Runnable task) {
        final Thread t = Thread.ofVirtual().name(name).unstarted(task);
        made(t);
        return t;
    }

    private static ThreadFactory virtualFactory(final String name) {
        final ThreadFactory f = Thread.ofVirtual().name(name).factory();
        return r -> {
            final Thread t = f.newThread(r);
            made(t);
            return t;
        };
    }

    private static void made(final Thread t) {
        synchronized (MADE) {
            MADE.put(t, Boolean.TRUE);
        }
    }

    /**
     * A fork: a virtual thread its caller collects in the same call, with a bound (a process's output read while the
     * caller waits for the process). It needs no owner, since it cannot outlive the call that started it.
     */
    public static Thread fork(final String name, final Runnable task) {
        final Thread t = virtual(name, task);
        t.start();
        return t;
    }

    /**
     * A strand: one virtual thread running its tasks in order, for one resource (a connection, the store, a renderer).
     * Its queue is unbounded and measured: {@link Queues} holds its depth and peak under the strand's name until the
     * strand terminates.
     */
    public static ExecutorService strand(final String name) {
        return new Strand(name, virtualFactory(name));
    }

    /** A strand's executor: one thread, an unbounded queue counted as tasks join and leave it. */
    private static final class Strand extends ThreadPoolExecutor {

        private final String name;
        private final Queues.Gauge queued;

        Strand(final String name, final ThreadFactory threads) {
            super(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), threads);
            this.name = name;
            this.queued = Queues.gauge(name);
        }

        @Override
        public void execute(final Runnable task) {
            queued.added();
            try {
                super.execute(task);
            } catch (final RejectedExecutionException e) {
                queued.removed();
                throw e;
            }
        }

        @Override
        protected void beforeExecute(final Thread t, final Runnable r) {
            queued.removed();
            super.beforeExecute(t, r);
        }

        @Override
        protected void terminated() {
            Queues.drop(name, queued);
            super.terminated();
        }
    }

    /**
     * A strand that also runs tasks after a delay or at a period. Its thread is virtual: waiting for the next task it
     * holds no operating-system thread, and its timers are kept by the JDK's one delay thread. A task cancelled before
     * it runs leaves the queue at once. Its queue is unbounded and measured as a strand's is: {@link Queues} holds its
     * depth (the tasks waiting, delayed and periodic ones included) and peak under the strand's name until it
     * terminates.
     */
    public static ScheduledExecutorService timedStrand(final String name) {
        return new TimedStrand(name, virtualFactory(name));
    }

    /**
     * A timed strand's executor: one thread; its queue, kept by the JDK, is sampled when a task joins it and when it is
     * read, since a cancel or a shutdown empties it without telling.
     */
    private static final class TimedStrand extends ScheduledThreadPoolExecutor {

        private final String name;
        private final Queues.Gauge queued;

        TimedStrand(final String name, final ThreadFactory threads) {
            super(1, threads);
            this.name = name;
            this.queued = Queues.sampled(name, () -> getQueue().size());
            setRemoveOnCancelPolicy(true);
        }

        // execute and submit schedule with no delay, so these four are every way a task joins the queue

        @Override
        public ScheduledFuture<?> schedule(final Runnable command, final long delay, final TimeUnit unit) {
            return sampled(super.schedule(command, delay, unit));
        }

        @Override
        public <V> ScheduledFuture<V> schedule(final Callable<V> callable, final long delay, final TimeUnit unit) {
            return sampled(super.schedule(callable, delay, unit));
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(final Runnable command, final long initialDelay, final long period, final TimeUnit unit) {
            return sampled(super.scheduleAtFixedRate(command, initialDelay, period, unit));
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(final Runnable command, final long initialDelay, final long delay, final TimeUnit unit) {
            return sampled(super.scheduleWithFixedDelay(command, initialDelay, delay, unit));
        }

        private <F> F sampled(final F future) {
            queued.sample();
            return future;
        }

        @Override
        protected void terminated() {
            Queues.drop(name, queued);
            super.terminated();
        }
    }
}
