package dev.marregui.comando.core.loop;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * How an owner stops the executors it holds when it closes: their threads end before its {@code close} returns, so a
 * task cut by the stop does not go on running against the parts closed after it. Every wait is bounded; an executor
 * still running at the bound is named in the log and left.
 */
public final class ExecutorStop {

    /**
     * How long an owner waits for its executors in all. Unmeasured: a strand's task waits on a queue, a socket or a
     * process, each of which answers an interrupt at once, so the bound covers the task's cleanup; settled by timing
     * the closes in {@code ThreadBudgetTest}.
     */
    public static final Duration WAIT = Duration.ofSeconds(2);
    private static final System.Logger LOG = System.getLogger(ExecutorStop.class.getName());

    private ExecutorStop() {
    }

    /**
     * Interrupts the tasks of every one of {@code executors}, drops their queued ones and waits for all of them to end,
     * at most {@link #WAIT} in all. Returns whether every one ended. The log names an executor still running by its
     * place in {@code executors}; {@link #now(String, Map)} names it by a label.
     */
    public static boolean now(final String owner, final ExecutorService... executors) {
        return now(owner, WAIT, executors);
    }

    /** As {@link #now(String, ExecutorService...)}, waiting at most {@code wait} in all. */
    public static boolean now(final String owner, final Duration wait, final ExecutorService... executors) {
        final SequencedMap<String, ExecutorService> labelled = new LinkedHashMap<>();
        for (int i = 0; i < executors.length; i++) {
            labelled.put("#" + i, executors[i]);
        }
        return now(owner, wait, labelled);
    }

    /**
     * As {@link #now(String, ExecutorService...)}, for executors by label: the log names each one still running at
     * the bound by its label.
     */
    public static boolean now(final String owner, final Map<String, ExecutorService> executors) {
        return now(owner, WAIT, executors);
    }

    /** As {@link #now(String, Map)}, waiting at most {@code wait} in all. */
    public static boolean now(final String owner, final Duration wait, final Map<String, ExecutorService> executors) {
        for (final ExecutorService e : executors.values()) {
            e.shutdownNow();
        }
        return stopped(owner, System.nanoTime() + wait.toNanos(), executors);
    }

    /**
     * Lets {@code executor} finish its running and queued tasks, a write being written, for at most {@code wait}; then
     * interrupts what is left and names in the log how many queued tasks were cut. Returns whether it ended in time.
     */
    public static boolean drain(final String owner, final Duration wait, final ExecutorService executor) {
        return drain(owner, wait, Map.of(owner, executor));
    }

    /**
     * As {@link #drain(String, Duration, ExecutorService)} for every one of {@code executors} together: one bound for
     * all, not one each. The log names by label each executor interrupted and each still running after it.
     */
    public static boolean drain(final String owner, final Duration wait, final Map<String, ExecutorService> executors) {
        for (final ExecutorService e : executors.values()) {
            e.shutdown();
        }
        if (await(System.nanoTime() + wait.toNanos(), executors)) {
            return true;
        }
        final SequencedMap<String, Integer> cut = new LinkedHashMap<>();
        executors.forEach((label, e) -> {
            if (!e.isTerminated()) {
                cut.put(label, e.shutdownNow().size());
            }
        });
        LOG.log(System.Logger.Level.WARNING, "executors not drained in time, interrupted [owner=" + owner + ", waitMillis="
                + wait.toMillis() + ", queuedCut=" + cut + ']');
        return stopped(owner, System.nanoTime() + WAIT.toNanos(), executors);
    }

    /** Waits for every one of {@code executors} until {@code deadlineNanos}; returns whether all ended. */
    private static boolean await(final long deadlineNanos, final Map<String, ExecutorService> executors) {
        boolean ended = true;
        for (final ExecutorService e : executors.values()) {
            try {
                ended &= e.awaitTermination(Math.max(0L, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (final InterruptedException ex) {
                // the closing thread was told to stop: what has not ended is reported, not waited for
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return ended;
    }

    /** Waits for every one of {@code executors} until {@code deadlineNanos}, then logs the labels of those running. */
    private static boolean stopped(final String owner, final long deadlineNanos, final Map<String, ExecutorService> executors) {
        await(deadlineNanos, executors);
        final List<String> running = new ArrayList<>();
        executors.forEach((label, e) -> {
            if (!e.isTerminated()) {
                running.add(label);
            }
        });
        if (running.isEmpty()) {
            return true;
        }
        LOG.log(System.Logger.Level.WARNING, "executors still running after close [owner=" + owner + ", running=" + running + ']');
        return false;
    }
}
