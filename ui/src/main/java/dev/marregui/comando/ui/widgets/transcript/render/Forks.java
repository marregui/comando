package dev.marregui.comando.ui.widgets.transcript.render;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;

import dev.marregui.comando.core.loop.Threads;

/**
 * Work over many items spread over the cores, the caller taking part, for an exact layout, a find or a copy of a long
 * conversation. Each task touches only its own item, which no one else changes while the caller waits: the caller is
 * the EDT, which owns the items. The caller and up to one fork per other core take indices in turn from one counter;
 * once the caller finds none left, every index is taken, so its wait for the forks is bounded by the one task each
 * may still run. The latch orders the forks' work before the caller reads it; the first failure is thrown on the
 * caller once all have stopped.
 */
final class Forks {

    /** Fewer tasks than this run on the caller alone. */
    static final int PARALLEL_FROM = 64;
    /** Tasks enough to be worth a fork of their own. */
    private static final int TASKS_PER_FORK = 16;

    private Forks() {
    }

    /** Runs {@code task} for every index in {@code [0, n)}, on the caller and the forks; returns once all have run. */
    static void each(final String name, final int n, final IntConsumer task) {
        final int forks = n < PARALLEL_FROM ? 0 : Math.min(Runtime.getRuntime().availableProcessors() - 1, n / TASKS_PER_FORK);
        final AtomicInteger next = new AtomicInteger();
        final AtomicReference<RuntimeException> failure = new AtomicReference<>();
        final Runnable work = () -> {
            for (int i; failure.get() == null && (i = next.getAndIncrement()) < n; ) {
                try {
                    task.accept(i);
                } catch (final RuntimeException e) {
                    failure.compareAndSet(null, e);
                }
            }
        };
        final CountDownLatch done = new CountDownLatch(forks);
        for (int f = 0; f < forks; f++) {
            Threads.fork(name + "-" + f, () -> {
                try {
                    work.run();
                } finally {
                    done.countDown();
                }
            });
        }
        work.run();
        boolean interrupted = false;
        while (true) {
            try {
                done.await();
                break;
            } catch (final InterruptedException e) {
                interrupted = true; // the forks touch the items still: the caller may not read them before they stop
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (failure.get() != null) {
            throw failure.get();
        }
    }
}
