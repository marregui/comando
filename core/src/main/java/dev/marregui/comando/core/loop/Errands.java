package dev.marregui.comando.core.loop;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The errands of one owner: each is one blocking call (open a file, run git, wait for a process) on its own virtual
 * thread, named for what it does. The owner closes them when it closes: those still running are interrupted and waited
 * for, a bounded time. An errand started after the close does not run.
 */
public final class Errands implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(Errands.class.getName());
    static final Duration CLOSE_WAIT = Duration.ofSeconds(2);

    private final String owner;
    private final Set<Thread> running = ConcurrentHashMap.newKeySet();
    /**
     * Held while an errand is added and started, and while the close takes the ones to stop: it sees only started ones.
     */
    private final Object lock = new Object();
    private boolean closed;

    public Errands(String owner) {
        this.owner = owner;
    }

    /** Runs {@code task} on a virtual thread named {@code name}; returns it, or null when the owner has closed. */
    public Thread start(String name, Runnable task) {
        Thread t = Threads.virtual(name, () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "an errand failed [owner=" + owner + ", errand=" + Thread.currentThread().getName() + ']', e);
            } finally {
                running.remove(Thread.currentThread());
            }
        });
        synchronized (lock) {
            if (closed) {
                return null;
            }
            running.add(t);
            t.start();
        }
        return t;
    }

    /** The errands running now; for tests. */
    int running() {
        return running.size();
    }

    /** Interrupts the errands still running and waits for them, at most {@link #CLOSE_WAIT} in all. Idempotent. */
    @Override
    public void close() {
        List<Thread> stopping;
        synchronized (lock) {
            closed = true;
            stopping = List.copyOf(running);
        }
        long deadline = System.nanoTime() + CLOSE_WAIT.toNanos();
        for (Thread t : stopping) {
            t.interrupt();
        }
        for (Thread t : stopping) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                break;
            }
            try {
                t.join(Duration.ofNanos(left));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (!running.isEmpty()) {
            LOG.log(System.Logger.Level.WARNING, "errands still running at close [owner=" + owner + ", errands=" + running.size() + ']');
        }
    }
}
