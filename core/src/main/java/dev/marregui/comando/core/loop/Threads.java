package dev.marregui.comando.core.loop;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;

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

    /** The live threads made here named {@code name}, virtual ones included; for tests. */
    public static List<Thread> alive(String name) {
        synchronized (MADE) {
            return MADE.keySet().stream().filter(t -> t.isAlive() && t.getName().equals(name)).toList();
        }
    }

    /** A virtual thread named {@code name}, not started, kept in the list {@link #alive} reads. */
    static Thread virtual(String name, Runnable task) {
        Thread t = Thread.ofVirtual().name(name).unstarted(task);
        made(t);
        return t;
    }

    private static ThreadFactory virtualFactory(String name) {
        ThreadFactory f = Thread.ofVirtual().name(name).factory();
        return r -> {
            Thread t = f.newThread(r);
            made(t);
            return t;
        };
    }

    private static void made(Thread t) {
        synchronized (MADE) {
            MADE.put(t, Boolean.TRUE);
        }
    }

    /**
     * A fork: a virtual thread its caller collects in the same call, with a bound (a process's output read while the
     * caller waits for the process). It needs no owner, since it cannot outlive the call that started it.
     */
    public static Thread fork(String name, Runnable task) {
        Thread t = virtual(name, task);
        t.start();
        return t;
    }

    /**
     * A strand: one virtual thread running its tasks in order, for one resource (a connection, the store, a renderer).
     */
    public static ExecutorService strand(String name) {
        return Executors.newSingleThreadExecutor(virtualFactory(name));
    }

    /**
     * A strand that also runs tasks after a delay or at a period. Its thread is virtual: waiting for the next task it
     * holds no operating-system thread, and its timers are kept by the JDK's one delay thread. A task cancelled before
     * it runs leaves the queue at once.
     */
    public static ScheduledExecutorService timedStrand(String name) {
        ScheduledThreadPoolExecutor e = new ScheduledThreadPoolExecutor(1, virtualFactory(name));
        e.setRemoveOnCancelPolicy(true);
        return e;
    }
}
