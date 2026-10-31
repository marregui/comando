package dev.marregui.comando.core.loop;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * How an owner stops the executors it holds when it closes: their threads end before its {@code close} returns, so a
 * task cut by the stop does not go on running against the parts closed after it. Every wait is bounded; an executor
 * still running at the bound is named in the log and left.
 */
public final class ExecutorStop {

    private static final System.Logger LOG = System.getLogger(ExecutorStop.class.getName());
    /** How long an owner waits for its executors in all: a strand's task answers an interrupt well within it. */
    public static final Duration WAIT = Duration.ofSeconds(2);

    private ExecutorStop() {
    }

    /**
     * Interrupts the tasks of every one of {@code executors}, drops their queued ones and waits for all of them to end,
     * at most {@link #WAIT} in all. Returns whether every one ended.
     */
    public static boolean now(final String owner, final ExecutorService... executors) {
        return now(owner, WAIT, executors);
    }

    /** As {@link #now(String, ExecutorService...)}, waiting at most {@code wait} in all. */
    public static boolean now(final String owner, final Duration wait, final ExecutorService... executors) {
        for (final ExecutorService e : executors) {
            e.shutdownNow();
        }
        return await(owner, System.nanoTime() + wait.toNanos(), List.of(executors));
    }

    /**
     * Lets {@code executor} finish its running and queued tasks, a write being written, for at most {@code wait}; then
     * interrupts what is left and names in the log how many queued tasks were cut. Returns whether it ended in time.
     */
    public static boolean drain(final String owner, final Duration wait, final ExecutorService executor) {
        executor.shutdown();
        try {
            if (executor.awaitTermination(wait.toNanos(), TimeUnit.NANOSECONDS)) {
                return true;
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        final int cut = executor.shutdownNow().size();
        LOG.log(System.Logger.Level.WARNING, "executor not drained in time, interrupted [owner=" + owner + ", waitMillis=" + wait.toMillis()
                + ", queuedCut=" + cut + ']');
        return await(owner, System.nanoTime() + WAIT.toNanos(), List.of(executor));
    }

    private static boolean await(final String owner, final long deadline, final List<ExecutorService> executors) {
        for (final ExecutorService e : executors) {
            try {
                e.awaitTermination(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (final InterruptedException ex) {
                // the closing thread was told to stop: what has not ended is reported, not waited for
                Thread.currentThread().interrupt();
                break;
            }
        }
        final long left = executors.stream().filter(x -> !x.isTerminated()).count();
        if (left > 0) {
            LOG.log(System.Logger.Level.WARNING, "executors still running after close [owner=" + owner + ", running=" + left + ']');
            return false;
        }
        return true;
    }
}
