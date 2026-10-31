package dev.marregui.comando.core.loop;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [Watchdog] keeps running its other looks when one of them fails, and a look that keeps failing does not fill the
/// log: its stack once, then a count at most once per bound.
class WatchdogTest {

    /// A look that throws every period for at least ten periods (one second): the log holds its stack once, not once
    /// per failure, and the count of the rest once the look is closed.
    @Test
    void aLookThatKeepsThrowingLogsItsStackOnce() throws Exception {
        final List<LogRecord> records = new CopyOnWriteArrayList<>();
        final Handler capture = capture(records);
        final Logger log = Logger.getLogger(Watchdog.class.getName());
        log.addHandler(capture);
        final CountDownLatch tenFailures = new CountDownLatch(10);
        final AutoCloseable failing = Watchdog.shared().watch(() -> {
            tenFailures.countDown();
            throw new IllegalStateException("simulated");
        });
        try {
            assertThat(tenFailures.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            failing.close();
            log.removeHandler(capture);
        }
        assertThat(records).filteredOn(r -> r.getThrown() instanceof IllegalStateException).hasSize(1);
        assertThat(records).as("the stack once, then the count told as the look goes").hasSize(2);
        assertThat(records.get(1).getThrown()).isNull();
        assertThat(records.get(1).getMessage()).startsWith("a watchdog look failed again [");
    }

    /// The count of a recurring failure on a clock the test moves: the first failure is told with its stack, the ones
    /// within the bound are counted, and the first one past it is told with the count and no stack.
    @Test
    void aRecurringFailureIsToldOnceThenCountedOncePerBound() {
        final List<LogRecord> records = new CopyOnWriteArrayList<>();
        final Handler capture = capture(records);
        final Logger log = Logger.getLogger(WatchdogTest.class.getName());
        log.addHandler(capture);
        try {
            final Watchdog.Repeats repeats = new Watchdog.Repeats(System.getLogger(WatchdogTest.class.getName()), 1_000L);
            final Object key = new Object();
            final IllegalStateException e = new IllegalStateException("simulated");
            for (long nowNanos = 0; nowNanos < 1_000L; nowNanos += 100L) {
                repeats.failed(key, "a look failed", "look=k", e, nowNanos);
            }
            assertThat(records).hasSize(1);
            repeats.failed(key, "a look failed", "look=k", e, 1_000L);

            assertThat(records).hasSize(2);
            assertThat(records.getFirst().getThrown()).isSameAs(e);
            assertThat(records.get(1).getThrown()).isNull();
            assertThat(records.get(1).getMessage()).isEqualTo("a look failed again [look=k, times=10, last=" + e + ']');
        } finally {
            log.removeHandler(capture);
        }
    }

    /// A second, different failure of the same key within the bound is told with its own stack, not counted under
    /// the first; counts not yet told come out when the key is forgotten and on a flush.
    @Test
    void aDifferentFailureIsToldWithItsStackAndPendingCountsAreToldOnForgetAndFlush() {
        final List<LogRecord> records = new CopyOnWriteArrayList<>();
        final Handler capture = capture(records);
        final Logger log = Logger.getLogger(WatchdogTest.class.getName());
        log.addHandler(capture);
        try {
            final Watchdog.Repeats repeats = new Watchdog.Repeats(System.getLogger(WatchdogTest.class.getName()), 1_000L);
            final Object key = new Object();
            final NullPointerException npe = new NullPointerException("first");
            final IllegalStateException ise = new IllegalStateException("second");
            repeats.failed(key, "a look failed", "look=k", npe, 0L);
            repeats.failed(key, "a look failed", "look=k", ise, 100L);
            assertThat(records).extracting(LogRecord::getThrown).containsExactly(npe, ise);

            repeats.failed(key, "a look failed", "look=k", npe, 200L);
            repeats.failed(key, "a look failed", "look=k", npe, 300L);
            repeats.forget(key);
            assertThat(records).hasSize(3);
            assertThat(records.get(2).getMessage()).isEqualTo("a look failed again [look=k, times=2, last=" + npe + ']');

            final Object other = new Object();
            repeats.failed(other, "reducer failed", "intent=X", ise, 0L);
            repeats.failed(other, "reducer failed", "intent=X", ise, 10L);
            repeats.flush();
            assertThat(records).hasSize(5);
            assertThat(records.get(4).getMessage()).isEqualTo("reducer failed again [intent=X, times=1, last=" + ise + ']');
            repeats.flush();
            assertThat(records).as("a told count is not told again").hasSize(5);
        } finally {
            log.removeHandler(capture);
        }
    }

    /// A look that throws `StackOverflowError` is run once and dropped, while a second look goes on being run three
    /// more periods.
    @Test
    void aLookThatThrowsAnErrorIsDroppedAndTheOtherLooksGoOn() throws Exception {
        final AtomicInteger failingRuns = new AtomicInteger();
        final CountDownLatch failed = new CountDownLatch(1);
        final CountDownLatch looked = new CountDownLatch(3);
        final AutoCloseable failing = Watchdog.shared().watch(() -> {
            failingRuns.incrementAndGet();
            failed.countDown();
            throw new StackOverflowError("simulated");
        });
        final AutoCloseable healthy = Watchdog.shared().watch(() -> {
            if (failed.getCount() == 0) {
                looked.countDown();
            }
        });
        try {
            assertThat(looked.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(failingRuns).hasValue(1);
        } finally {
            failing.close();
            healthy.close();
        }
    }

    private static Handler capture(final List<LogRecord> records) {
        return new Handler() {
            @Override
            public void publish(final LogRecord entry) {
                records.add(entry);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
    }
}
