package dev.marregui.comando.frontear.store;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/// The link log on disk: records read back as appended, offsets gap-free across segments and restarts, only durable
/// records read, acknowledgements that move the cursor and delete segments, the quota, recovery from a torn or corrupt
/// tail, and the lifecycle. Seed in the output; replay with `-Dfrontear.store.seed=<seed>`.
class LinkLogTest {

    private static final long SEED = Long.getLong("frontear.store.seed", System.nanoTime());
    // a record of RECORD bytes is a frame of 16, so a segment of SEGMENT bytes holds its header and three
    private static final int RECORD = 8;
    private static final int FRAMED = LinkLog.FRAME + RECORD;
    private static final long SEGMENT = LinkLog.HEADER + 3L * FRAMED;
    private static final LogOptions SMALL = new LogOptions(SEGMENT, 1L << 20, Duration.ZERO);
    // how long a test waits for a force on the test machine's disk, or for the forcer's cursor save and deletion
    private static final long WAIT_SECONDS = 10;

    @TempDir
    Path dir;

    private Random random;

    /// A generator per test, from the run's seed and the test's name, so one test replays alone.
    @BeforeEach
    void seed(final TestInfo info) {
        System.out.println(info.getDisplayName() + " seed=" + SEED);
        random = new Random(SEED ^ info.getDisplayName().hashCode());
    }

    /// Records of random sizes, empty ones and ones larger than a segment included, are read back byte for byte at
    /// the offsets their appends returned, in pages of any size.
    @Test
    void appendedRecordsReadBackInOrder() throws Exception {
        final List<byte[]> appended = new ArrayList<>();
        try (LinkLog log = LinkLog.open(dir, new LogOptions(256, 1L << 20, Duration.ZERO))) {
            final List<CompletableFuture<Long>> futures = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                final byte[] record = new byte[random.nextInt(i % 50 == 0 ? 600 : 64)];
                random.nextBytes(record);
                appended.add(record);
                futures.add(log.append(record));
            }
            for (int i = 0; i < futures.size(); i++) {
                assertThat(futures.get(i).get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(i);
            }
            final List<Record> read = new ArrayList<>();
            while (read.size() < appended.size()) {
                read.addAll(log.read(read.size(), 1 + random.nextInt(30)));
            }
            assertThat(log.read(appended.size(), 10)).isEmpty();
            for (int i = 0; i < appended.size(); i++) {
                assertThat(read.get(i).offset()).isEqualTo(i);
                assertThat(read.get(i).bytes()).isEqualTo(appended.get(i));
            }
        }
    }

    /// Offsets run 0, 1, 2… across segment files and across a close and reopen, which reads every record again.
    @Test
    void offsetsAreGapFreeAcrossSegmentsAndReopen() throws Exception {
        try (LinkLog log = LinkLog.open(dir, SMALL)) {
            for (int i = 0; i < 20; i++) {
                assertThat(log.append(record(i)).get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(i);
            }
        }
        assertThat(segments()).hasSize(7);
        try (LinkLog log = LinkLog.open(dir, SMALL)) {
            assertThat(log.tail()).isEqualTo(20);
            assertThat(log.append(record(20)).get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(20);
            assertRecords(log.read(0, 100), 0, 21);
        }
    }

    /// An append not yet forced is not read: the reader sees only what survives a crash. The close forces it.
    @Test
    void readReturnsOnlyDurableRecords() throws Exception {
        final CompletableFuture<Long> future;
        try (LinkLog log = LinkLog.open(dir, new LogOptions(SEGMENT, 1L << 20, Duration.ofHours(1)))) {
            future = log.append(record(0));
            assertThat(log.tail()).isEqualTo(1);
            assertThat(log.read(0, 10)).isEmpty();
            assertThat(future).isNotDone();
        }
        assertThat(future).isCompletedWithValue(0L);
        try (LinkLog log = LinkLog.open(dir, SMALL)) {
            assertRecords(log.read(0, 10), 0, 1);
        }
    }

    /// An acknowledgement moves the cursor at once; the forcer then saves it and deletes the segments wholly below it,
    /// and a reopen starts there. An older acknowledgement changes nothing; one past the durable records is refused.
    @Test
    void ackMovesTheCursorAndDeletesSegmentsWhollyBelowIt() throws Exception {
        try (LinkLog log = LinkLog.open(dir, SMALL)) {
            appendAll(log, 30);
            assertThat(segments()).hasSize(10);
            assertThat(log.bytes()).isEqualTo(10 * SEGMENT);
            log.ack(14);
            assertThat(log.cursor()).isEqualTo(15);
            assertRecords(log.read(0, 3), 15, 18);
            // segments 0 to 12 hold offsets below 15; 15 to 27 stay
            await().atMost(Duration.ofSeconds(WAIT_SECONDS)).until(() -> log.bytes() == 5 * SEGMENT);
            assertThat(segments()).hasSize(5);
            log.ack(3);
            assertThat(log.cursor()).isEqualTo(15);
            assertThatThrownBy(() -> log.ack(30)).isInstanceOf(IllegalArgumentException.class);
        }
        try (LinkLog log = LinkLog.open(dir, SMALL)) {
            assertThat(log.cursor()).isEqualTo(15);
            assertThat(log.tail()).isEqualTo(30);
            assertRecords(log.read(0, 100), 15, 30);
        }
    }

    /// An append that would pass the quota fails at once and assigns no offset, every refusal with the log's one
    /// exception, which has no stack trace and names the quota; once an acknowledgement frees a segment the next append
    /// succeeds.
    @Test
    void quotaRefusesAppendsAtOnceAndFreesAfterAck() throws Exception {
        try (LinkLog log = LinkLog.open(dir, new LogOptions(SEGMENT, 2 * SEGMENT, Duration.ZERO))) {
            appendAll(log, 6);
            final CompletableFuture<Long> refused = log.append(record(6));
            assertThat(refused).isCompletedExceptionally();
            assertThatThrownBy(refused::join).hasCauseInstanceOf(QuotaExceededException.class);
            final Throwable quota = refused.exceptionNow();
            assertThat(quota.getStackTrace()).isEmpty();
            assertThat(log.append(record(6)).exceptionNow()).isSameAs(quota);
            assertThat(log.tail()).isEqualTo(6);
            log.ack(2);
            await().atMost(Duration.ofSeconds(WAIT_SECONDS)).until(() -> log.bytes() == SEGMENT);
            assertThat(log.append(record(6)).get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(6);
        }
    }

    /// A tail cut mid-frame, as a crash mid-write leaves it, is cut back to the last whole record, and appends go on
    /// from there.
    @Test
    void aTornTailIsCutAndAppendsContinue() throws Exception {
        final LogOptions big = new LogOptions(1 << 16, 1L << 20, Duration.ZERO);
        try (LinkLog log = LinkLog.open(dir, big)) {
            appendAll(log, 10);
        }
        final Path segment = segments().getFirst();
        final long size = Files.size(segment);
        try (FileChannel channel = FileChannel.open(segment, StandardOpenOption.WRITE)) {
            channel.truncate(size - 3);
        }
        try (LinkLog log = LinkLog.open(dir, big)) {
            assertThat(log.tail()).isEqualTo(9);
            assertThat(Files.size(segment)).isEqualTo(size - FRAMED);
            assertThat(log.append(record(9)).get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(9);
        }
        try (LinkLog log = LinkLog.open(dir, big)) {
            assertRecords(log.read(0, 100), 0, 10);
        }
    }

    /// A frame that fails its CRC ends the log there: it and every record after it, in its segment and in later
    /// ones, are gone, and the records before it stay.
    @Test
    void aCorruptFrameEndsTheLogThere() throws Exception {
        try (LinkLog log = LinkLog.open(dir, SMALL)) {
            appendAll(log, 12);
        }
        // record 4 is the second of the segment whose base is 3
        final Path segment = segments().get(1);
        try (FileChannel channel = FileChannel.open(segment, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(new byte[] {(byte) 0xFF}), LinkLog.HEADER + FRAMED + LinkLog.FRAME + 2L);
        }
        try (LinkLog log = LinkLog.open(dir, SMALL)) {
            assertThat(log.tail()).isEqualTo(4);
            assertRecords(log.read(0, 100), 0, 4);
            assertThat(segments()).hasSize(2);
            assertThat(Files.size(segment)).isEqualTo(LinkLog.HEADER + FRAMED);
        }
    }

    /// A cursor file that holds no cursor (a foreign or damaged file) is ignored: delivery starts at the first record.
    @Test
    void aCursorFileThatHoldsNoCursorIsIgnored() throws Exception {
        try (LinkLog log = LinkLog.open(dir, SMALL)) {
            appendAll(log, 5);
            log.ack(1);
            await().atMost(Duration.ofSeconds(WAIT_SECONDS)).until(() -> Files.exists(dir.resolve("cursor")));
        }
        Files.write(dir.resolve("cursor"), new byte[] {1, 2, 3});
        try (LinkLog log = LinkLog.open(dir, SMALL)) {
            assertThat(log.cursor()).isZero();
            assertRecords(log.read(0, 10), 0, 5);
        }
    }

    /// A second close does nothing, the forcer thread is gone after the first, and the closed log refuses appends,
    /// reads and acknowledgements.
    @Test
    void closeIsIdempotentAndEndsTheForcer() throws Exception {
        final LinkLog log = LinkLog.open(dir, SMALL);
        appendAll(log, 2);
        log.close();
        log.close();
        assertThat(Thread.getAllStackTraces().keySet()).extracting(Thread::getName)
                .doesNotContain("frontear-log-force-" + dir.getFileName());
        assertThat(log.append(record(2))).isCompletedExceptionally();
        assertThatThrownBy(() -> log.read(0, 1)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> log.ack(0)).isInstanceOf(IOException.class);
    }

    /// Options that cannot hold a record, or that are inconsistent, are refused when built.
    @Test
    void optionsRefuseImpossibleSizes() {
        assertThatThrownBy(() -> new LogOptions(LinkLog.HEADER, 1 << 20, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LogOptions(SEGMENT, SEGMENT - 1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LogOptions(SEGMENT, SEGMENT, Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void appendAll(final LinkLog log, final int count)
            throws ExecutionException, InterruptedException, TimeoutException {
        final long first = log.tail();
        for (int i = 0; i < count; i++) {
            assertThat(log.append(record(first + i)).get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(first + i);
        }
    }

    private static void assertRecords(final List<Record> records, final long from, final long to) {
        assertThat(records).extracting(Record::offset).hasSize((int) (to - from));
        for (int i = 0; i < records.size(); i++) {
            assertThat(records.get(i).offset()).isEqualTo(from + i);
            assertThat(records.get(i).bytes()).isEqualTo(record(from + i));
        }
    }

    // RECORD bytes that name their offset
    private static byte[] record(final long offset) {
        return ByteBuffer.allocate(RECORD).putLong(offset).array();
    }

    private List<Path> segments() throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(p -> p.getFileName().toString().endsWith(".log")).sorted().toList();
        }
    }
}
