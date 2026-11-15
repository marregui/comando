package dev.marregui.comando.cdb.serve;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbAppender;

import static org.assertj.core.api.Assertions.assertThat;

/// A `/write` body the store refuses a row of writes none of its rows, as QuestDB answers it, and the other bodies of
/// the same group commit are written whole.
class WriteAtomicityTest {

    /// Ten seconds only bounds a hung test; every wait here ends in milliseconds when the queue works.
    private static final long TIMEOUT_SECONDS = 10L;

    /// The rows of a body before the refused one are dropped with it: the appender is asked to discard them.
    @Test
    void aRefusedBodyWritesNoneOfItsRows() {
        final List<RecordingAppender> opened = new CopyOnWriteArrayList<>();
        final CommitQueue queue = new CommitQueue(() -> open(opened, 2, () -> { }));
        final CommitQueue.Body body = rows(1, 2, 3);

        final CommitQueue.Refusal refusal = queue.write(body);

        assertThat(refusal).isNotNull();
        assertThat(refusal.row()).isEqualTo(1);
        assertThat(opened).hasSize(1);
        assertThat(opened.getFirst().discards()).isEqualTo(1);
        assertThat(opened.getFirst().flushed).filteredOn(c -> c.startsWith("at(")).isEmpty();
    }

    /// Of three bodies in one commit, the middle one refused: the first and the last are written, the middle one is not.
    @Test
    void aRefusedBodyDoesNotDiscardTheOthersOfItsGroup() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        final List<RecordingAppender> opened = new CopyOnWriteArrayList<>();
        // the first commit holds the table until released; the second one refuses its third row: the middle body's
        final Supplier<CdbAppender> appenders = () -> opened.isEmpty()
                ? open(opened, 0, () -> await(release))
                : open(opened, 3, () -> { });
        final CommitQueue queue = new CommitQueue(appenders);
        final AtomicReference<CommitQueue.Refusal> refused = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();

        final Thread first = start(() -> queue.write(rows(100)), refused, failure);
        awaitTrue(() -> opened.size() == 1);
        final Thread before = start(() -> queue.write(rows(1)), refused, failure);
        awaitTrue(() -> queue.queued("t") == 1);
        final Thread middle = start(() -> queue.write(rows(10, 11)), refused, failure);
        awaitTrue(() -> queue.queued("t") == 2);
        final Thread after = start(() -> queue.write(rows(3)), refused, failure);
        awaitTrue(() -> queue.queued("t") == 3);
        release.countDown();
        for (final Thread thread : List.of(first, before, middle, after)) {
            assertThat(thread.join(Duration.ofSeconds(TIMEOUT_SECONDS))).isTrue();
        }

        assertThat(failure.get()).isNull();
        assertThat(refused.get()).isNotNull();
        assertThat(refused.get().row()).isEqualTo(1);
        assertThat(opened).hasSize(2);
        assertThat(opened.get(1).discards()).isEqualTo(1);
        assertThat(opened.get(1).flushed).filteredOn(c -> c.startsWith("at(")).containsExactly("at(1)", "at(3)");
    }

    private static RecordingAppender open(final List<RecordingAppender> opened, final int refuseAt,
            final Runnable onClose) {
        final RecordingAppender appender = new RecordingAppender(refuseAt, onClose);
        opened.add(appender);
        return appender;
    }

    private static CommitQueue.Body rows(final int... values) {
        final CommitQueue.Body body = new CommitQueue.Body();
        for (final int value : values) {
            body.table("t").longColumn("v", value).at(value);
        }
        return body;
    }

    private static Thread start(final Supplier<CommitQueue.Refusal> write,
            final AtomicReference<CommitQueue.Refusal> refused, final AtomicReference<Throwable> failure) {
        return Thread.ofVirtual().name("write-atomicity-test").start(() -> {
            try {
                final CommitQueue.Refusal refusal = write.get();
                if (refusal != null) {
                    refused.set(refusal);
                }
            } catch (final RuntimeException | Error e) {
                failure.set(e);
            }
        });
    }

    private static void await(final CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch not released");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    // the same bounded poll as CommitQueueTest's
    private static void awaitTrue(final BooleanSupplier condition) throws InterruptedException {
        CommitQueueTest.awaitTrue(condition);
    }
}
