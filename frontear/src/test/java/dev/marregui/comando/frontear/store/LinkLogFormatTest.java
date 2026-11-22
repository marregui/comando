package dev.marregui.comando.frontear.store;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.frontear.protocol.MqttException;
import dev.marregui.comando.frontear.protocol.Packet;
import dev.marregui.comando.frontear.protocol.PublishRecord;

import static org.assertj.core.api.Assertions.assertThat;

/// The link log's segment format: a record a link queues is stored in at most 40 bytes over its payload and topic, a
/// log the previous format wrote reads whole and goes on, a torn delta frame at the tail is cut, and a read from any
/// offset of a reopened log decodes the deltas before it. Seed in the output; replay with
/// `-Dfrontear.store.seed=<seed>`.
class LinkLogFormatTest {

    private static final long SEED = Long.getLong("frontear.store.seed", System.nanoTime());
    // how long a test waits for a force on the test machine's disk
    private static final long WAIT_SECONDS = 30;
    // the shape the bound is set for: a 128-byte payload on a 35-character topic
    private static final int PAYLOAD = 128;
    private static final String TOPIC = "plant/area-07/line-03/cell/pressure";
    // the most bytes a record of that shape may take in the log over its payload and topic, frame included
    private static final int OVERHEAD = 40;
    // segments far smaller than a link's 64 MiB, so the records a roll writes whole count against the bound
    private static final LogOptions LINK = new LogOptions(1L << 20, 1L << 30, Duration.ZERO);
    // the options the version 1 fixture was written with
    private static final LogOptions FIXTURE = new LogOptions(1024, 1L << 20, Duration.ZERO);
    // the fixture's segments, written by the log before the version 2 format, and its record count
    private static final String[] FIXTURE_SEGMENTS = {
        "00000000000000000000.log",
        "00000000000000000030.log",
        "00000000000000000047.log",
        "00000000000000000076.log",
        "00000000000000000095.log",
    };
    private static final int FIXTURE_RECORDS = 100;

    @TempDir
    Path dir;

    private Random random;

    /// A generator per test, from the run's seed and the test's name, so one test replays alone.
    @BeforeEach
    void seed(final TestInfo info) {
        System.out.println(info.getDisplayName() + " seed=" + SEED);
        random = new Random(SEED ^ info.getDisplayName().hashCode());
    }

    /// 10,000 PUBLISH records as a link queues them (QoS 1, packet id 1, its hop count and queue time stamped as user
    /// properties) with a random 128-byte payload on a 35-character topic take at most 128 + 35 + 40 bytes each in the
    /// log's files, headers and frames included, and read back byte for byte after a reopen.
    @Test
    void aQueuedPublishTakesAtMostFortyBytesOverItsPayloadAndTopic() throws Exception {
        assertThat(TOPIC).hasSize(35);
        final int count = 10_000;
        final List<byte[]> appended = new ArrayList<>();
        final long queuedMillis = 1_795_000_000_000L;
        try (LinkLog log = LinkLog.open(dir, LINK)) {
            final List<CompletableFuture<Long>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                // 20 a millisecond, the rate of a link at 20,000 messages a second
                final byte[] record = queued(queuedMillis + i / 20);
                appended.add(record);
                futures.add(log.append(record));
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(WAIT_SECONDS, TimeUnit.SECONDS);
            assertThat(log.bytes()).isEqualTo(fileBytes());
        }
        final double perRecord = (double) fileBytes() / count;
        System.out.println("log bytes per record [whole=" + (LinkLog.FRAME + appended.getFirst().length)
                + ", stored=" + perRecord + ", bound=" + (PAYLOAD + TOPIC.length() + OVERHEAD) + ']');
        assertThat(perRecord).isLessThanOrEqualTo(PAYLOAD + TOPIC.length() + OVERHEAD);
        try (LinkLog log = LinkLog.open(dir, LINK)) {
            assertReadsBack(log, appended);
        }
    }

    /// A log the previous format wrote (version 1 segments, kept as a test resource) reads every record after the
    /// change; appends go on in its last segment as version 1 frames, then in version 2 segments, which store
    /// deltas; after a reopen every record reads back, and the old segments' bytes are unchanged.
    @Test
    void aVersionOneLogReadsWholeAndGoesOn() throws Exception {
        for (final String name : FIXTURE_SEGMENTS) {
            try (InputStream in = LinkLogFormatTest.class.getResourceAsStream("v1-log/" + name)) {
                assertThat(in).as("fixture segment " + name).isNotNull();
                Files.copy(in, dir.resolve(name));
            }
        }
        final Path last = dir.resolve(FIXTURE_SEGMENTS[FIXTURE_SEGMENTS.length - 1]);
        final long lastSize = Files.size(last);
        final List<byte[]> expected = new ArrayList<>();
        for (int i = 0; i < FIXTURE_RECORDS; i++) {
            expected.add(fixtureRecord(i));
        }
        try (LinkLog log = LinkLog.open(dir, FIXTURE)) {
            assertThat(log.tail()).isEqualTo(FIXTURE_RECORDS);
            assertReadsBack(log, expected);
            final List<CompletableFuture<Long>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                final byte[] record = queued(1_795_000_000_000L + i);
                expected.add(record);
                futures.add(log.append(record));
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(WAIT_SECONDS, TimeUnit.SECONDS);
        }
        try (LinkLog log = LinkLog.open(dir, FIXTURE)) {
            assertReadsBack(log, expected);
        }
        for (final String name : FIXTURE_SEGMENTS) {
            assertThat(versionOf(dir.resolve(name))).as("version of " + name).isEqualTo(LinkLog.VERSION_1);
            assertThat(fields(dir.resolve(name))).as("deltas in " + name).noneMatch(f -> (f & LinkLog.DELTA) != 0);
        }
        assertThat(Files.size(last)).as("the last fixture segment took the first appends").isGreaterThan(lastSize);
        try (InputStream in = LinkLogFormatTest.class.getResourceAsStream("v1-log/" + last.getFileName())) {
            final byte[] original = in.readAllBytes();
            final byte[] now = new byte[original.length];
            content(last).get(0, now);
            assertThat(now).as("the fixture's bytes in its last segment").isEqualTo(original);
        }
        final List<Path> added = segments().stream().filter(p -> !isFixture(p)).toList();
        assertThat(added).isNotEmpty();
        for (final Path p : added) {
            assertThat(versionOf(p)).as("version of " + p.getFileName()).isEqualTo(LinkLog.VERSION);
        }
        assertThat(added).as("a new segment that stores deltas")
                .anyMatch(p -> fields(p).stream().anyMatch(f -> (f & LinkLog.DELTA) != 0));
    }

    /// A delta frame torn by a crash at the tail fails its CRC: the reopen cuts it off, every record before it reads
    /// back, and the next append takes its offset and reads back after another reopen.
    @Test
    void aTornDeltaFrameAtTheTailIsCutAndAppendsGoOn() throws Exception {
        final int count = 200;
        final List<byte[]> appended = new ArrayList<>();
        try (LinkLog log = LinkLog.open(dir, LINK)) {
            final List<CompletableFuture<Long>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                final byte[] record = queued(1_795_000_000_000L + i);
                appended.add(record);
                futures.add(log.append(record));
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(WAIT_SECONDS, TimeUnit.SECONDS);
        }
        final Path segment = segments().getLast();
        final List<Integer> fields = fields(segment);
        assertThat(fields).hasSize(count);
        assertThat(fields.getLast() & LinkLog.DELTA).as("the last frame is a delta").isNotZero();
        final int lastFrame = LinkLog.FRAME + (fields.getLast() & ~LinkLog.DELTA);
        try (FileChannel ch = FileChannel.open(segment, StandardOpenOption.WRITE)) {
            ch.truncate(ch.size() - 1 - random.nextInt(lastFrame - 1));
        }
        final List<byte[]> kept = new ArrayList<>(appended.subList(0, count - 1));
        try (LinkLog log = LinkLog.open(dir, LINK)) {
            assertThat(log.tail()).isEqualTo(count - 1);
            assertReadsBack(log, kept);
            assertThat(log.append(appended.getLast()).get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(count - 1);
        }
        try (LinkLog log = LinkLog.open(dir, LINK)) {
            assertReadsBack(log, appended);
        }
    }

    /// After a reopen, with no round in memory and no read to go on from, a read from any offset returns the records
    /// appended there, decoding the deltas from the whole record before it; reads that go on from the last one
    /// return the same.
    @Test
    void aReadFromAnyOffsetOfAReopenedLogDecodesTheDeltasBeforeIt() throws Exception {
        final int count = 1_000;
        final List<byte[]> appended = new ArrayList<>();
        try (LinkLog log = LinkLog.open(dir, LINK)) {
            final List<CompletableFuture<Long>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                final byte[] record = queued(1_795_000_000_000L + i);
                appended.add(record);
                futures.add(log.append(record));
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(WAIT_SECONDS, TimeUnit.SECONDS);
        }
        try (LinkLog log = LinkLog.open(dir, LINK)) {
            for (int i = 0; i < 100; i++) {
                final int from = random.nextInt(count);
                final List<Record> read = log.read(from, 1 + random.nextInt(100));
                assertThat(read).isNotEmpty();
                for (final Record r : read) {
                    assertThat(r.bytes()).as("record " + r.offset()).isEqualTo(appended.get((int) r.offset()));
                }
            }
        }
        try (LinkLog log = LinkLog.open(dir, LINK)) {
            assertReadsBack(log, appended);
        }
    }

    // a PUBLISH as a link's log keeps it: QoS 1, packet id 1, the hop count and the queue time as user properties,
    // a random payload on TOPIC
    private byte[] queued(final long queuedMillis) throws MqttException {
        final byte[] payload = new byte[PAYLOAD];
        random.nextBytes(payload);
        final Packet.Meta meta = new Packet.Meta(-1, -1, null, List.of(new Packet.UserProperty("frontear-hops", "1"),
                new Packet.UserProperty("frontear-queued-ms", Long.toString(queuedMillis))));
        return PublishRecord.encode(new Packet.Publish(TOPIC, payload, 1, false, false, 1, null, null, meta));
    }

    // record i of the fixture, as the code that wrote it made it
    private static byte[] fixtureRecord(final int i) {
        final byte[] record = new byte[10 + i % 50];
        for (int j = 0; j < record.length; j++) {
            record[j] = (byte) (i * 13 + j);
        }
        return record;
    }

    private static boolean isFixture(final Path p) {
        for (final String name : FIXTURE_SEGMENTS) {
            if (p.getFileName().toString().equals(name)) {
                return true;
            }
        }
        return false;
    }

    // reads the whole log in pages of random sizes and checks it holds exactly expected, from offset 0
    private void assertReadsBack(final LinkLog log, final List<byte[]> expected) throws IOException {
        final List<Record> read = new ArrayList<>();
        while (read.size() < expected.size()) {
            final List<Record> page = log.read(read.size(), 1 + random.nextInt(100));
            assertThat(page).as("a page at " + read.size()).isNotEmpty();
            read.addAll(page);
        }
        assertThat(log.read(expected.size(), 10)).isEmpty();
        for (int i = 0; i < expected.size(); i++) {
            assertThat(read.get(i).offset()).isEqualTo(i);
            assertThat(read.get(i).bytes()).as("record " + i).isEqualTo(expected.get(i));
        }
    }

    private List<Path> segments() throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".log")).sorted().toList();
        }
    }

    private long fileBytes() throws IOException {
        long n = 0L;
        for (final Path p : segments()) {
            n += Files.size(p);
        }
        return n;
    }

    // the version in a segment's header
    private static int versionOf(final Path segment) throws IOException {
        return content(segment).getInt(Integer.BYTES);
    }

    // the length field of every frame of a segment, walked as its header and fields say
    private static List<Integer> fields(final Path segment) {
        final ByteBuffer content;
        try {
            content = content(segment);
        } catch (final IOException e) {
            throw new IllegalStateException("cannot read segment [file=" + segment + ']', e);
        }
        final List<Integer> found = new ArrayList<>();
        int at = LinkLog.HEADER;
        while (at + LinkLog.FRAME <= content.limit()) {
            final int field = content.getInt(at);
            found.add(field);
            at += LinkLog.FRAME + (field & ~LinkLog.DELTA);
        }
        return found;
    }

    private static ByteBuffer content(final Path segment) throws IOException {
        try (FileChannel ch = FileChannel.open(segment, StandardOpenOption.READ)) {
            final ByteBuffer content = ByteBuffer.allocate((int) ch.size()).order(ByteOrder.LITTLE_ENDIAN);
            while (content.hasRemaining()) {
                if (ch.read(content) < 0) {
                    break;
                }
            }
            return content.flip();
        }
    }
}
