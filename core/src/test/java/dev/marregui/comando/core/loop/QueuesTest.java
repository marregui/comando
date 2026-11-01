package dev.marregui.comando.core.loop;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [Queues] measures comando's unbounded queues for the metrics log.
///
/// - a gauge counts what its queue holds and the most it held since the last read
/// - a strand's tasks are counted as they wait, and its gauge goes when it terminates
/// - a strand made again under the name of one still ending is read from its own gauge, which the old one's end keeps
/// - a timed strand's waiting and delayed tasks are measured too, and its gauge goes when it terminates
class QueuesTest {

    /// A gauge read after three additions and one removal says depth 2 and peak 3; the next read starts the peak again
    /// at the depth it found.
    @Test
    void aGaugeHoldsItsDepthAndThePeakSinceTheLastRead() {
        final Queues.Gauge g = Queues.gauge("test-gauge");
        try {
            g.added();
            g.added();
            g.added();
            g.removed();
            assertThat(Queues.read()).contains(new Queues.Reading("test-gauge", 2, 3));
            assertThat(Queues.read()).as("the peak starts again at the depth").contains(new Queues.Reading("test-gauge", 2, 2));
        } finally {
            Queues.drop("test-gauge", g);
        }
        assertThat(Queues.read()).extracting(Queues.Reading::name).doesNotContain("test-gauge");
    }

    /// Two tasks queued behind one that blocks read as depth 2 and peak 2; once all have run the depth is 0; a
    /// terminated strand's gauge is gone.
    @Test
    void aStrandsWaitingTasksAreCountedAndItsGaugeGoesWithIt() throws Exception {
        final ExecutorService strand = Threads.strand("queues-test-strand");
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch running = new CountDownLatch(1);
        strand.execute(() -> {
            running.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(running.await(10, TimeUnit.SECONDS)).isTrue();
        strand.execute(() -> { });
        strand.execute(() -> { });
        assertThat(Queues.read()).contains(new Queues.Reading("queues-test-strand", 2, 2));
        release.countDown();
        strand.submit(() -> { }).get(10, TimeUnit.SECONDS);
        assertThat(Queues.read()).as("all run").anyMatch(r -> r.name().equals("queues-test-strand") && r.depth() == 0);
        strand.shutdown();
        assertThat(strand.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        assertThat(Queues.read()).extracting(Queues.Reading::name).doesNotContain("queues-test-strand");
    }

    /// A strand made again under its name while the one before drains its last task (a connection's lane, a session's
    /// strand after a restart) is the one read; once the old one terminates, the new one, running a task with one
    /// queued behind it, is still read under the name.
    @Test
    void aStrandMadeAgainUnderItsNameIsStillReadAfterTheOldOneEnds() throws Exception {
        final String name = "queues-test-again";
        final ExecutorService old = Threads.strand(name);
        final CountDownLatch release = new CountDownLatch(1);
        old.execute(() -> await(release));
        final ExecutorService again = Threads.strand(name);
        old.shutdown();
        release.countDown();
        assertThat(old.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        final CountDownLatch hold = new CountDownLatch(1);
        final CountDownLatch running = new CountDownLatch(1);
        try {
            again.execute(() -> {
                running.countDown();
                await(hold);
            });
            assertThat(running.await(10, TimeUnit.SECONDS)).isTrue();
            again.execute(() -> { });
            assertThat(Queues.read()).as("the new strand of " + name + " holds a task queued").contains(new Queues.Reading(name, 1, 1));
        } finally {
            hold.countDown();
            again.shutdown();
            assertThat(again.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(Queues.read()).extracting(Queues.Reading::name).doesNotContain(name);
    }

    /// A timed strand's queue is measured as a strand's is: three tasks delayed an hour read as depth 3 and peak 3, a
    /// periodic one among them; one cancelled leaves at once (depth 2, peak 3 since the last read); a terminated timed
    /// strand's gauge is gone.
    @Test
    void aTimedStrandsDelayedTasksAreCountedAndItsGaugeGoesWithIt() throws Exception {
        final String name = "queues-test-timed";
        final ScheduledExecutorService timed = Threads.timedStrand(name);
        try {
            final ScheduledFuture<?> first = timed.schedule(() -> { }, 1, TimeUnit.HOURS);
            timed.schedule(() -> "done", 1, TimeUnit.HOURS);
            timed.scheduleWithFixedDelay(() -> { }, 1, 1, TimeUnit.HOURS);
            first.cancel(false);
            assertThat(Queues.read()).contains(new Queues.Reading(name, 2, 3));
            assertThat(Queues.read()).as("the peak starts again at the depth").contains(new Queues.Reading(name, 2, 2));
        } finally {
            timed.shutdownNow();
            assertThat(timed.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(Queues.read()).extracting(Queues.Reading::name).doesNotContain(name);
    }

    private static void await(final CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
