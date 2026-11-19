package dev.marregui.comando.frontear.queue;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// A segment sealed and wholly forced is mapped once, at its first read, and that mapping serves every later read of
/// it, from any thread, until the segment is deleted, when it is freed before the file goes, or the log closes.
class LinkLogMappingTest {

    // reads of every record per test, so each sealed segment is read PASSES * PER_SEGMENT times
    private static final int PASSES = 5;
    // a record of RECORD bytes is a frame of 16, so a segment of SEGMENT bytes holds its header and three
    private static final int PER_SEGMENT = 3;
    private static final int RECORD = 8;
    private static final int RECORDS = 30;
    private static final long SEGMENT = LinkLog.HEADER + PER_SEGMENT * (long) (LinkLog.FRAME + RECORD);
    private static final LogOptions SMALL = new LogOptions(SEGMENT, 1L << 20, Duration.ZERO);
    // how long a test waits for a force on the test machine's disk, or for the forcer's cursor save and deletion
    private static final long WAIT_SECONDS = 10;

    @TempDir
    Path dir;

    /// Reads of one record at a time, over every record again and again, on the test's thread and on another, map
    /// each sealed segment once and open none per read; the active segment, whose bytes still change, is read per
    /// call.
    @Test
    void aSealedSegmentIsMappedOnceAcrossManyReads() throws Exception {
        final CountingFiles files = new CountingFiles();
        try (LinkLog log = LinkLog.open(files, dir, SMALL);
                ExecutorService other = Executors.newVirtualThreadPerTaskExecutor()) {
            appendAll(log);
            for (int pass = 0; pass < PASSES - 1; pass++) {
                readEach(log);
            }
            other.submit(() -> {
                readEach(log);
                return null;
            }).get(WAIT_SECONDS, TimeUnit.SECONDS);
            for (int base = 0; base < RECORDS - PER_SEGMENT; base += PER_SEGMENT) {
                assertThat(files.count("map", base)).as("maps of sealed segment %d", base).isOne();
                assertThat(files.count("read", base)).as("reads of sealed segment %d", base).isZero();
            }
            final int active = RECORDS - PER_SEGMENT;
            assertThat(files.count("map", active)).as("maps of the active segment").isZero();
            assertThat(files.count("read", active)).as("reads of the active segment").isPositive();
        }
    }

    /// The mapping of a segment the cursor's save deletes is freed before its file is deleted; the log's close frees
    /// the rest, once, however often it is called.
    @Test
    void mappingsAreFreedBeforeDeletionAndOnClose() throws Exception {
        final CountingFiles files = new CountingFiles();
        final LinkLog log = LinkLog.open(files, dir, SMALL);
        try {
            appendAll(log);
            readEach(log);
            // segments 0 to 12 hold offsets up to 14, all below the cursor the ack moves to 15
            log.ack(14);
            await().atMost(Duration.ofSeconds(WAIT_SECONDS)).until(() -> files.count("delete", 12) == 1);
            for (int base = 0; base <= 12; base += PER_SEGMENT) {
                assertThat(files.events).as("segment %d", base).containsSubsequence(event("map", base),
                        event("unmap", base), event("delete", base));
            }
            for (int base = 15; base < RECORDS - PER_SEGMENT; base += PER_SEGMENT) {
                assertThat(files.count("unmap", base)).as("unmaps of kept segment %d", base).isZero();
            }
        } finally {
            log.close();
        }
        for (int base = 15; base < RECORDS - PER_SEGMENT; base += PER_SEGMENT) {
            assertThat(files.count("unmap", base)).as("unmaps of segment %d after the close", base).isOne();
        }
        log.close();
        for (int base = 0; base < RECORDS - PER_SEGMENT; base += PER_SEGMENT) {
            assertThat(files.count("unmap", base)).as("unmaps of segment %d after a second close", base).isOne();
        }
    }

    private static void appendAll(final LinkLog log) throws Exception {
        for (int i = 0; i < RECORDS; i++) {
            assertThat(log.append(record(i)).get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(i);
        }
    }

    private static void readEach(final LinkLog log) throws IOException {
        for (int i = 0; i < RECORDS; i++) {
            final List<Record> read = log.read(i, 1);
            assertThat(read).hasSize(1);
            assertThat(read.getFirst().offset()).isEqualTo(i);
            assertThat(read.getFirst().bytes()).isEqualTo(record(i));
        }
    }

    private static byte[] record(final int i) {
        return ByteBuffer.allocate(RECORD).putLong(i).array();
    }

    private static String event(final String kind, final int base) {
        return kind + ' ' + segmentName(base);
    }

    private static String segmentName(final int base) {
        return String.format(Locale.ROOT, "%020d.log", base);
    }

    /// The disk, recording each read, map, unmap and delete of a segment, in order.
    private static final class CountingFiles implements LogFiles {

        private final List<String> events = new CopyOnWriteArrayList<>();

        @Override
        public void createDirectories(final Path d) throws IOException {
            DiskFiles.INSTANCE.createDirectories(d);
        }

        @Override
        public List<Path> list(final Path d) throws IOException {
            return DiskFiles.INSTANCE.list(d);
        }

        @Override
        public Writer create(final Path file) throws IOException {
            return DiskFiles.INSTANCE.create(file);
        }

        @Override
        public Writer open(final Path file) throws IOException {
            return DiskFiles.INSTANCE.open(file);
        }

        @Override
        public <T> T read(final Path file, final Body<T> body) throws IOException {
            events.add("read " + file.getFileName());
            return DiskFiles.INSTANCE.read(file, body);
        }

        @Override
        public Mapping map(final Path file) throws IOException {
            events.add("map " + file.getFileName());
            final Mapping mapping = DiskFiles.INSTANCE.map(file);
            return new Mapping() {

                @Override
                public MemorySegment bytes() {
                    return mapping.bytes();
                }

                @Override
                public void close() {
                    events.add("unmap " + file.getFileName());
                    mapping.close();
                }
            };
        }

        @Override
        public void delete(final Path file) throws IOException {
            events.add("delete " + file.getFileName());
            DiskFiles.INSTANCE.delete(file);
        }

        @Override
        public void move(final Path from, final Path to) throws IOException {
            DiskFiles.INSTANCE.move(from, to);
        }

        @Override
        public void forceFolder(final Path d) throws IOException {
            DiskFiles.INSTANCE.forceFolder(d);
        }

        // how many events of kind the segment whose base is base has had
        long count(final String kind, final int base) {
            final String wanted = event(kind, base);
            return events.stream().filter(wanted::equals).count();
        }
    }
}
