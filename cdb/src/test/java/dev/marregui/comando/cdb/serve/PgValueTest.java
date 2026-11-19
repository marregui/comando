package dev.marregui.comando.cdb.serve;

import com.sun.management.ThreadMXBean;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.time.Instant;
import java.util.SplittableRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.Cdb;
import dev.marregui.comando.cdb.CdbAppender;
import dev.marregui.comando.cdb.CdbResult;

import static org.assertj.core.api.Assertions.assertThat;

/// A DataRow's values written in place into the output buffer are the bytes `PgTypes.text` and `PgTypes.binary`
/// make, for every cdb type in both formats, NULLs and the extremes included; the numbers and timestamps among them
/// are written without allocating. Random values come from a seed printed and replayed through `cdb.pg.seed`.
class PgValueTest {

    private static final String CREATE = "CREATE TABLE probe (ts TIMESTAMP, host SYMBOL, n LONG, x DOUBLE,"
            + " ok BOOLEAN, note VARCHAR, at TIMESTAMP) TIMESTAMP(ts) PARTITION BY DAY";
    private static final String SELECT = "select ts, host, n, x, ok, note, at from probe";
    private static final long T0 = Instant.parse("2026-11-14T10:00:00Z").toEpochMilli() * 1_000L;
    /// Enough random rows to meet each branch of each encoding many times; a second or two of appends.
    private static final int RANDOM_ROWS = 5_000;
    /// Enough random timestamps to cross every month, leap day and era boundary of the hand-written calendar.
    private static final int TIMESTAMPS = 200_000;
    private static final long MICROS_YEAR_0 = Instant.parse("0000-01-01T00:00:00Z").getEpochSecond() * 1_000_000L;
    private static final long MICROS_YEAR_10000 = Instant.parse("+10000-01-01T00:00:00Z").getEpochSecond() * 1_000_000L;
    /// Passes over the rows measured; one pass before them grows the buffer and the scratch builder to their size.
    private static final int MEASURED_PASSES = 20;
    /// Measured on an M2 Max at load 6 to 10 (2026-11-19), over the 20 passes: 16.2 MB through an array per value
    /// (`PgTypes.text`, `PgTypes.binary`), 5.6 kB writing in place; the bound sits between, far from both.
    private static final long MAX_ALLOCATED_BYTES = 64 << 10;

    @TempDir
    private Path root;

    private Cdb cdb;

    /// A fresh cdb with the probe table.
    @BeforeEach
    void open() {
        cdb = Cdb.open(root);
        cdb.run(CREATE);
    }

    /// Closes the cdb.
    @AfterEach
    void close() {
        cdb.close();
    }

    /// Every type, NULLs, non-ASCII and four-byte text, `Long.MIN_VALUE + 1` and `Long.MAX_VALUE`, NaN, both
    /// infinities, -0.0, the smallest and largest doubles, timestamps before the epoch and beyond year 9999, then
    /// random rows: each DataRow is byte for byte the one built from `PgTypes.text` and `PgTypes.binary`.
    @Test
    void writesEveryValueAsTheArrayEncodingWrites() throws IOException {
        final long seed = Long.getLong("cdb.pg.seed", System.nanoTime());
        System.out.println("PgValueTest.writesEveryValueAsTheArrayEncodingWrites seed=" + seed);
        final SplittableRandom random = new SplittableRandom(seed);
        final long[] longs = {Long.MIN_VALUE, Long.MIN_VALUE + 1, Long.MAX_VALUE, 0L, -1L, 1L, 1_000_000_007L};
        final double[] doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.0, 0.0,
                Double.MIN_VALUE, Double.MAX_VALUE, -Double.MIN_NORMAL, 1e-3, 1e-4, 1e7, 9_999_999.0, 0.1 + 0.2};
        final long[] timestamps = {Long.MIN_VALUE, Long.MIN_VALUE + 1, Long.MAX_VALUE, 0L, -1L, 1L,
                MICROS_YEAR_0, MICROS_YEAR_0 - 1, MICROS_YEAR_10000, MICROS_YEAR_10000 - 1, T0 + 123_456};
        final String[] texts = {null, "", "plain", "héllo 'quoted'", "日本語", "😀 emoji", "a" + (char) 0 + "b"};
        try (CdbAppender appender = cdb.appender()) {
            for (int i = 0; i < longs.length * doubles.length; i++) {
                append(appender, i, texts[i % texts.length], longs[i % longs.length], doubles[i % doubles.length],
                        i % 2 == 0, texts[(i + 3) % texts.length], timestamps[i % timestamps.length]);
            }
            for (int i = 0; i < RANDOM_ROWS; i++) {
                append(appender, longs.length * doubles.length + i, randomText(random), random.nextLong(),
                        Double.longBitsToDouble(random.nextLong()), random.nextBoolean(), randomText(random),
                        randomTimestamp(random));
            }
        }
        final CdbResult result = cdb.run(SELECT);
        assertThat(result.rowCount()).isEqualTo(longs.length * doubles.length + RANDOM_ROWS);
        for (int format = 0; format <= 1; format++) {
            for (int row = 0; row < result.rowCount(); row++) {
                assertThat(written(result, row, format)).as("seed=%d, row %d, format %d", seed, row, format)
                        .isEqualTo(expected(result, row, format));
            }
        }
    }

    /// Timestamps over the whole range of a long, and densely over the years 0 to 9999, are appended as
    /// `PgTypes.timestampText(long)` formats them.
    @Test
    void appendsTimestampsAsTheFormatterWrites() {
        final long seed = Long.getLong("cdb.pg.seed", System.nanoTime());
        System.out.println("PgValueTest.appendsTimestampsAsTheFormatterWrites seed=" + seed);
        final SplittableRandom random = new SplittableRandom(seed);
        final StringBuilder sink = new StringBuilder();
        for (int i = 0; i < TIMESTAMPS; i++) {
            final long micros = randomTimestamp(random);
            sink.setLength(0);
            assertThat(PgTypes.timestampText(sink, micros).toString()).as("seed=%d, micros=%d", seed, micros)
                    .isEqualTo(PgTypes.timestampText(micros));
        }
    }

    /// Numbers, booleans and timestamps written as DataRows in text and in binary allocate nothing once the buffer
    /// has grown to a send's size: no array per value, no String per number.
    @Test
    void writesNumbersAndTimestampsWithoutAllocating() throws IOException {
        try (CdbAppender appender = cdb.appender()) {
            for (int i = 0; i < 1_000; i++) {
                append(appender, i, "h", i * 7_919L - 3_000_000L, i / 7.0, i % 3 == 0, null, T0 + i * 1_001_001L);
            }
        }
        final CdbResult result = cdb.run("select n, x, ok, at from probe");
        final PgOutput out = new PgOutput(OutputStream.nullOutputStream());
        final ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        encode(out, result);
        final long before = threads.getCurrentThreadAllocatedBytes();
        for (int pass = 0; pass < MEASURED_PASSES; pass++) {
            encode(out, result);
        }
        final long allocated = threads.getCurrentThreadAllocatedBytes() - before;
        assertThat(allocated).as("bytes allocated over %d passes", MEASURED_PASSES)
                .isLessThan(MAX_ALLOCATED_BYTES);
    }

    private static void append(
            final CdbAppender appender,
            final int i,
            final String host,
            final long n,
            final double x,
            final boolean ok,
            final String note,
            final long at
    ) {
        appender.table("probe").symbol("host", host).longColumn("n", n).doubleColumn("x", x).booleanColumn("ok", ok)
                .stringColumn("note", note).timestampColumn("at", at).at(T0 + i * 1_000_000L);
    }

    private static void encode(final PgOutput out, final CdbResult result) throws IOException {
        for (int format = 0; format <= 1; format++) {
            for (int row = 0; row < result.rowCount(); row++) {
                out.begin('D').int16(result.columnCount());
                for (int column = 0; column < result.columnCount(); column++) {
                    PgTypes.value(out, result, row, column, format);
                }
                out.end();
            }
        }
        out.flush();
    }

    private static byte[] written(final CdbResult result, final int row, final int format) throws IOException {
        final ByteArrayOutputStream socket = new ByteArrayOutputStream();
        final PgOutput out = new PgOutput(socket);
        out.begin('D').int16(result.columnCount());
        for (int column = 0; column < result.columnCount(); column++) {
            PgTypes.value(out, result, row, column, format);
        }
        out.end();
        out.flush();
        return socket.toByteArray();
    }

    // the DataRow as the row path built it before it wrote in place: an array per value, then its length and bytes
    private static byte[] expected(final CdbResult result, final int row, final int format) throws IOException {
        final ByteArrayOutputStream socket = new ByteArrayOutputStream();
        final PgOutput out = new PgOutput(socket);
        out.begin('D').int16(result.columnCount());
        for (int column = 0; column < result.columnCount(); column++) {
            final byte[] value = format == 0 ? PgTypes.text(result, row, column) : PgTypes.binary(result, row, column);
            if (value == null) {
                out.int32(-1);
            } else {
                out.int32(value.length).bytes(value);
            }
        }
        out.end();
        out.flush();
        return socket.toByteArray();
    }

    private static String randomText(final SplittableRandom random) {
        if (random.nextInt(8) == 0) {
            return null;
        }
        final StringBuilder text = new StringBuilder();
        final int length = random.nextInt(16);
        for (int i = 0; i < length; i++) {
            switch (random.nextInt(4)) {
                case 0 -> text.append((char) random.nextInt(0x20, 0x7F));
                case 1 -> text.append((char) random.nextInt(0xA0, 0x800));
                case 2 -> text.append((char) random.nextInt(0x800, 0xD800));
                default -> text.appendCodePoint(random.nextInt(0x10000, 0x110000));
            }
        }
        return text.toString();
    }

    // a third over a long's whole range, a third within the years 0 to 9999, a third a few days around a boundary
    private static long randomTimestamp(final SplittableRandom random) {
        return switch (random.nextInt(3)) {
            case 0 -> random.nextLong();
            case 1 -> random.nextLong(MICROS_YEAR_0, MICROS_YEAR_10000);
            default -> {
                final long[] edges = {MICROS_YEAR_0, MICROS_YEAR_10000, 0L, T0};
                yield edges[random.nextInt(edges.length)] + random.nextLong(-5 * 86_400_000_000L, 5 * 86_400_000_000L);
            }
        };
    }
}
