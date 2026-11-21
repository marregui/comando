package dev.marregui.comando.frontear.store;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The log across crashes: a scripted run of appends, acknowledgements and reads is cut at every operation that
/// changes a file or the folder, and every state the disk could hold there (a kill, a kill mid-write, a power cut) is
/// reopened. After each: every append whose future completed is read back at its offset, in order, unless an
/// acknowledgement covered it; nothing is read that was not appended; the cursor is never past an acknowledgement; the
/// recovered state is itself forced; and the log appends on, gap-free.
class LinkLogCrashTest {

    private static final Path DIR = Path.of("/crash/link");
    // small segments, so the run rolls several times and deletes acknowledged ones
    private static final LogOptions OPTIONS = new LogOptions(160, 1L << 20, Duration.ZERO);
    // the appends of each step of the run, awaited together, so some forces cover more than one
    private static final int[] GROUPS = {1, 3, 1, 4, 2, 1, 5, 1, 2, 3, 1, 1, 4, 2};
    // a bound on the runs: the scripted run changes the files fewer times than this
    private static final int MAX_RUNS = 5_000;
    // how long the run waits for one append's force, far above an in-memory force
    private static final long WAIT_SECONDS = 10;

    /// Cut at every changing operation, each state the cut leaves holds every completed append and only appends, and
    /// reopens as a forced, appendable log.
    @Test
    void everyCompletedAppendSurvivesACrashAtEveryWriteAndForce() throws Exception {
        int crashAt = 1;
        int images = 0;
        while (true) {
            assertThat(crashAt).as("runs").isLessThan(MAX_RUNS);
            final CrashFiles files = new CrashFiles(crashAt);
            final Run run = run(files);
            if (!files.crashed()) {
                assertThat(run.completed).hasSize(run.attempted);
                break;
            }
            for (final CrashFiles image : files.images()) {
                check(image, run, crashAt);
                images++;
            }
            crashAt++;
        }
        System.out.println("crash points=" + (crashAt - 1) + " images=" + images);
        // each of the run's appends writes once, and each awaited group needs a force
        assertThat(crashAt - 1).as("crash points").isGreaterThan(Arrays.stream(GROUPS).sum() + GROUPS.length);
        assertThat(images).as("images").isGreaterThan(2 * (crashAt - 1));
    }

    /// A force that fails fails the append waiting on it, and the log refuses every later append, even once forces
    /// work again: a force after a failed one does not prove the pages the failure dropped.
    @Test
    void aFailedForceFailsPendingAppendsAndRefusesLaterOnes() throws Exception {
        final CrashFiles files = new CrashFiles(0);
        try (LinkLog log = LinkLog.open(files, DIR, OPTIONS)) {
            assertThat(log.append(payload(0)).get(WAIT_SECONDS, TimeUnit.SECONDS)).isZero();
            files.failForces(true);
            final CompletableFuture<Long> failed = log.append(payload(1));
            assertThatThrownBy(() -> failed.get(WAIT_SECONDS, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IOException.class);
            files.failForces(false);
            assertThat(log.append(payload(2))).isCompletedExceptionally();
            assertThatThrownBy(() -> log.ack(0)).isInstanceOf(IOException.class);
            assertThat(log.read(0, 10)).extracting(Record::offset).containsExactly(0L);
        }
    }

    // the scripted run on files: what it appended, what completed, the highest acknowledgement; it stops at the first
    // failure, as a crashed process would
    private static Run run(final CrashFiles files) throws InterruptedException {
        final Run run = new Run();
        final LinkLog log;
        try {
            log = LinkLog.open(files, DIR, OPTIONS);
        } catch (final IOException _) {
            return run;
        }
        try (log) {
            for (int step = 0; step < GROUPS.length; step++) {
                final List<CompletableFuture<Long>> futures = new ArrayList<>();
                for (int i = 0; i < GROUPS[step]; i++) {
                    futures.add(log.append(payload(run.attempted)));
                    run.attempted++;
                }
                for (final CompletableFuture<Long> f : futures) {
                    run.completed.add(f.get(WAIT_SECONDS, TimeUnit.SECONDS));
                }
                if (step % 3 == 2) {
                    final long ack = run.completed.getLast() - 1L;
                    log.ack(ack);
                    run.acked = Math.max(run.acked, ack);
                }
                if (step % 4 == 3) {
                    for (final Record r : log.read(log.cursor(), 4)) {
                        assertThat(r.bytes()).isEqualTo(payload(r.offset()));
                    }
                }
            }
        } catch (final ExecutionException | IOException | TimeoutException _) {
            // the crash: the run ends here
        }
        return run;
    }

    // reopens one state the crash left and checks it against the run
    private static void check(final CrashFiles files, final Run run, final int crashAt) throws Exception {
        final String at = "crashAt=" + crashAt;
        try (LinkLog log = LinkLog.open(files, DIR, OPTIONS)) {
            final long cursor = log.cursor();
            final long tail = log.tail();
            final List<Record> records = readAll(log);
            assertThat(records).as(at).extracting(Record::offset)
                    .containsExactlyElementsOf(offsets(cursor, tail));
            for (final Record r : records) {
                assertThat(r.bytes()).as(at + " offset=" + r.offset()).isEqualTo(payload(r.offset()));
            }
            assertThat(tail).as(at + " tail").isLessThanOrEqualTo(run.attempted);
            assertThat(cursor).as(at + " cursor").isLessThanOrEqualTo(run.acked + 1L);
            for (final long done : run.completed) {
                if (done >= cursor) {
                    assertThat(done).as(at + " completed append read back").isLessThan(tail);
                }
            }
            try (LinkLog cut = LinkLog.open(new CrashFiles(files.powerCut()), DIR, OPTIONS)) {
                assertThat(cut.tail()).as(at + " recovered state forced").isEqualTo(tail);
                assertThat(readAll(cut)).as(at + " recovered records forced").extracting(Record::offset)
                        .containsExactlyElementsOf(offsets(cut.cursor(), tail));
            }
            assertThat(log.append(payload(tail)).get(WAIT_SECONDS, TimeUnit.SECONDS)).as(at + " next append")
                    .isEqualTo(tail);
        }
    }

    private static List<Record> readAll(final LinkLog log) throws IOException {
        final List<Record> all = new ArrayList<>();
        List<Record> page = log.read(log.cursor(), 7);
        while (!page.isEmpty()) {
            all.addAll(page);
            page = log.read(page.getLast().offset() + 1L, 7);
        }
        return all;
    }

    private static List<Long> offsets(final long from, final long to) {
        final List<Long> out = new ArrayList<>();
        for (long o = from; o < to; o++) {
            out.add(o);
        }
        return out;
    }

    // the record appended at offset i: its length and bytes vary with i, so a record read at another offset differs
    private static byte[] payload(final long i) {
        final byte[] bytes = new byte[1 + (int) (i * 7 % 40)];
        for (int j = 0; j < bytes.length; j++) {
            bytes[j] = (byte) (i * 31 + j);
        }
        return bytes;
    }

    // what a scripted run did before it stopped
    private static final class Run {
        int attempted;
        final List<Long> completed = new ArrayList<>();
        long acked = -1L;
    }
}
