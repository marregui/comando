package dev.marregui.comando.cdb.store;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbType;

import static org.assertj.core.api.Assertions.assertThat;

/// [Wal]'s replay reads the log through a window, never whole: a log many windows long, with frames longer than the
/// window, replays the same entries through any window; a torn tail is cut at every frame boundary and one byte into
/// the next frame; and the read buffer grows past the window only to a frame it decodes.
class WalWindowTest {

    private static final TableDef DEF = new TableDef("t", List.of(
            new ColumnDef("ts", CdbType.TIMESTAMP),
            new ColumnDef("l", CdbType.LONG),
            new ColumnDef("d", CdbType.DOUBLE),
            new ColumnDef("b", CdbType.BOOLEAN),
            new ColumnDef("s", CdbType.SYMBOL),
            new ColumnDef("v", CdbType.VARCHAR)
    ), 0, PartitionBy.DAY, List.of(), 0L);
    private static final TableDef WIDER = DEF.withColumn(new ColumnDef("extra", CdbType.LONG));
    // commits of the log: every seventh one large, about 13 kB, longer than every window below but the default
    private static final int COMMITS = 30;
    private static final int LARGE_ROWS = 200;
    // windows below the smallest frame, around a small frame, and around the large ones; the smallest is clamped up
    // to a frame's header and transaction number
    private static final int[] WINDOWS = {1, 64, 257, 4_096, Wal.WINDOW};
    private static final int SMALL_WINDOW = 64;
    // the long log: frames of about 260 kB, the one in the middle of its first LONG_LOG_FRAMES about 3 MB, over twice
    // the default window, at some 65 bytes a row
    private static final int LONG_LOG_ROWS = 4_000;
    private static final long LONG_LOG_FRAMES = 128L;
    private static final int WINDOW_PLUS_ROWS = 48_000;

    /// A log many windows long replays through every window the entries it replays through the default one: the same
    /// transactions, truncations, definitions and rows, and the same record and byte counts after.
    @Test
    void aLogLongerThanTheWindowReplaysTheSameEntries(@TempDir final Path dir) throws IOException {
        writeLog(dir);
        final List<List<Object>> expected = replay(dir, Wal.WINDOW, 0L, DEF);
        assertThat(expected).hasSize(COMMITS);
        final long size = Files.size(dir.resolve(Wal.FILE));
        assertThat(size).as("many windows long").isGreaterThan(10L * 4_096);
        for (final int window : WINDOWS) {
            assertThat(replay(dir, window, 0L, DEF)).as("window %d", window).isEqualTo(expected);
            assertThat(Files.size(dir.resolve(Wal.FILE))).as("nothing cut, window %d", window).isEqualTo(size);
        }
        for (final int window : WINDOWS) {
            // the definition logged at 5 is skipped with its frame: the checkpoint's is the wider one
            assertThat(replay(dir, window, 20L, WIDER)).as("after 20, window %d", window)
                    .isEqualTo(expected.subList(20, COMMITS));
        }
    }

    /// The log cut at every frame boundary, and one byte past each into the next frame, as a crash leaves it: the
    /// whole frames before the cut replay through a small window, and the file is cut back to the boundary.
    @Test
    void aTornTailIsCutAtEveryFrameBoundaryAndOneByteIn(@TempDir final Path dir) throws IOException {
        writeLog(dir);
        final Path file = dir.resolve(Wal.FILE);
        final byte[] image = Files.readAllBytes(file);
        final List<List<Object>> whole = replay(dir, Wal.WINDOW, 0L, DEF);
        final long[] boundaries = boundaries(image);
        assertThat(boundaries).hasSize(COMMITS + 1);
        for (int i = 0; i < boundaries.length - 1; i++) {
            for (final long cut : new long[] {boundaries[i], boundaries[i] + 1L, boundaries[i + 1] - 1L}) {
                Files.write(file, Arrays.copyOf(image, (int) cut));
                final Wal torn = Wal.open(dir);
                try {
                    assertThat(entries(torn.scan(0L, DEF, SMALL_WINDOW))).as("cut at %d", cut)
                            .isEqualTo(whole.subList(0, i));
                    assertThat(torn.records()).as("cut at %d", cut).isEqualTo(i);
                    assertThat(torn.bytes()).as("cut at %d", cut).isEqualTo(boundaries[i]);
                } finally {
                    torn.close();
                }
                assertThat(Files.size(file)).as("cut at %d", cut).isEqualTo(boundaries[i]);
            }
        }
    }

    /// A byte changed inside the last frame, longer than the window, fails the CRC computed through the window: the
    /// frame is cut as a torn tail and the frames before it replay.
    @Test
    void aDamagedFrameLongerThanTheWindowIsCut(@TempDir final Path dir) throws IOException {
        final Wal wal = Wal.open(dir);
        try {
            append(wal, 1L, 3);
            append(wal, 2L, LARGE_ROWS);
        } finally {
            wal.close();
        }
        final Path file = dir.resolve(Wal.FILE);
        final byte[] image = Files.readAllBytes(file);
        final long[] boundaries = boundaries(image);
        final int middle = (int) ((boundaries[1] + boundaries[2]) / 2L);
        image[middle] ^= 0x10;
        Files.write(file, image);
        assertThat(boundaries[2] - boundaries[1]).as("longer than the window").isGreaterThan(SMALL_WINDOW);
        final List<List<Object>> entries = replay(dir, SMALL_WINDOW, 0L, DEF);
        assertThat(entries).extracting(e -> e.getFirst()).containsExactly(1L);
        assertThat(Files.size(file)).isEqualTo(boundaries[1]);
    }

    /// The read buffer stays at most the largest frame plus the window: the window itself while every frame it decodes
    /// fits it or is skipped as at or before the checkpoint, and the largest decoded frame's body when one does not.
    @Test
    void theReadBufferStaysWithinTheLargestFramePlusTheWindow(@TempDir final Path dir) throws IOException {
        writeLog(dir);
        final byte[] image = Files.readAllBytes(dir.resolve(Wal.FILE));
        final long[] boundaries = boundaries(image);
        long largest = 0L;
        for (int i = 1; i < boundaries.length; i++) {
            largest = Math.max(largest, boundaries[i] - boundaries[i - 1]);
        }
        final Wal wal = Wal.open(dir);
        try {
            close(wal.scan(0L, DEF, SMALL_WINDOW));
            assertThat((long) wal.scanBufferBytes()).isLessThanOrEqualTo(largest + SMALL_WINDOW);
            assertThat((long) wal.scanBufferBytes()).as("grew to the largest body")
                    .isEqualTo(largest - Wal.FRAME);
            // every frame at or before the checkpoint: skipped, the large ones' CRC streamed through the window
            close(wal.scan(COMMITS, WIDER, SMALL_WINDOW));
            assertThat(wal.scanBufferBytes()).as("never grown past the window").isEqualTo(SMALL_WINDOW);
            close(wal.scan(0L, DEF, Wal.WINDOW));
            assertThat((long) wal.scanBufferBytes()).as("the whole log fits the default window")
                    .isEqualTo(image.length);
        } finally {
            wal.close();
        }
    }

    /// A log 64 windows long, of frames under the window and one over twice its size, replays through the
    /// default window: every frame comes back, and the read buffer ends at the largest frame's body, at most the
    /// largest frame plus the window and a small part of the file, where the whole log was read into the heap before.
    @Test
    void aLogSixtyFourWindowsLongReplaysThroughABufferOfItsLargestFrame(@TempDir final Path dir) throws IOException {
        final long target = 64L * Wal.WINDOW;
        final long large = LONG_LOG_FRAMES / 2L;
        final Wal wal = Wal.open(dir);
        long txn = 0L;
        try {
            while (wal.bytes() < target) {
                txn++;
                try (RowBlock block = rows(DEF, txn, txn == large ? WINDOW_PLUS_ROWS : LONG_LOG_ROWS)) {
                    wal.write(txn, false, null, block);
                }
            }
        } finally {
            wal.close();
        }
        final byte[] image = Files.readAllBytes(dir.resolve(Wal.FILE));
        assertThat((long) image.length).as("the log's bytes").isGreaterThanOrEqualTo(target);
        final long[] boundaries = boundaries(image);
        long largest = 0L;
        for (int i = 1; i < boundaries.length; i++) {
            largest = Math.max(largest, boundaries[i] - boundaries[i - 1]);
        }
        assertThat(largest).as("one frame longer than the window").isGreaterThan(2L * Wal.WINDOW);
        final Wal replay = Wal.open(dir);
        try {
            final List<Wal.Entry> entries = replay.scan(0L, DEF);
            try {
                assertThat(entries).hasSize((int) txn);
                assertThat(entries.getLast().txn()).isEqualTo(txn);
            } finally {
                close(entries);
            }
            assertThat((long) replay.scanBufferBytes()).as("within the largest frame plus the window")
                    .isLessThanOrEqualTo(largest + Wal.WINDOW)
                    .isEqualTo(largest - Wal.FRAME)
                    .isLessThan(image.length / 16L);
        } finally {
            replay.close();
        }
    }

    // COMMITS commits: a definition change at 5, a truncation at 9, every seventh large
    private static void writeLog(final Path dir) throws IOException {
        final Wal wal = Wal.open(dir);
        try {
            TableDef def = DEF;
            for (long txn = 1L; txn <= COMMITS; txn++) {
                final TableDef logged = txn == 5L ? WIDER : null;
                if (logged != null) {
                    def = logged;
                }
                final int rows = txn % 7L == 0L ? LARGE_ROWS : (int) (txn % 4L);
                try (RowBlock block = rows(def, txn, rows)) {
                    wal.append(txn, txn == 9L, logged, block);
                }
            }
        } finally {
            wal.close();
        }
    }

    private static void append(final Wal wal, final long txn, final int n) throws IOException {
        try (RowBlock block = rows(DEF, txn, n)) {
            wal.append(txn, false, null, block);
        }
    }

    /// `n` rows of `def`, set from `txn` and the row, a NULL text every third row and a longer text every fifth.
    private static RowBlock rows(final TableDef def, final long txn, final int n) {
        final RowBlock rows = new RowBlock(def.columns(), n);
        rows.size = n;
        for (int c = 0; c < rows.types.length; c++) {
            for (int r = 0; r < n; r++) {
                final long v = txn * 1_000L + c * 100L + r;
                switch (rows.types[c]) {
                    case LONG, TIMESTAMP -> rows.setLong(c, r, v);
                    case DOUBLE -> rows.setDouble(c, r, v / 4.0);
                    case BOOLEAN -> rows.setBoolean(c, r, v % 2L == 0L);
                    case SYMBOL, VARCHAR -> rows.setText(c, r, r % 3 == 1 ? null
                            : "t" + v + (r % 5 == 0 ? "-".repeat(40) : ""));
                }
            }
        }
        return rows;
    }

    // the entries scan returns through window after afterTxn, decoded against def, as values, their rows closed
    private static List<List<Object>> replay(final Path dir, final int window, final long afterTxn,
                                             final TableDef def) throws IOException {
        final Wal wal = Wal.open(dir);
        try {
            return entries(wal.scan(afterTxn, def, window));
        } finally {
            wal.close();
        }
    }

    // each entry as its txn, truncation, definition and rows by column, the rows closed
    private static List<List<Object>> entries(final List<Wal.Entry> entries) {
        final List<List<Object>> values = new ArrayList<>();
        for (final Wal.Entry entry : entries) {
            final List<Object> value = new ArrayList<>();
            value.add(entry.txn());
            value.add(entry.truncate());
            value.add(entry.def());
            final RowBlock rows = entry.rows();
            for (int c = 0; c < rows.types.length; c++) {
                for (int r = 0; r < rows.size; r++) {
                    value.add(switch (rows.types[c]) {
                        case LONG, TIMESTAMP -> rows.getLong(c, r);
                        case DOUBLE -> Double.doubleToRawLongBits(rows.getDouble(c, r));
                        case BOOLEAN -> rows.getBoolean(c, r);
                        case SYMBOL, VARCHAR -> rows.textAt(c, r);
                    });
                }
            }
            values.add(value);
            rows.close();
        }
        return values;
    }

    private static void close(final List<Wal.Entry> entries) {
        for (final Wal.Entry entry : entries) {
            entry.rows().close();
        }
    }

    // the offsets where the log's frames start, and its end: read from the frames' own length fields
    private static long[] boundaries(final byte[] image) {
        final ByteBuffer log = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN);
        final List<Long> at = new ArrayList<>();
        long end = Wal.HEADER;
        at.add(end);
        while (end < image.length) {
            end += Wal.FRAME + Integer.toUnsignedLong(log.getInt((int) end));
            at.add(end);
        }
        assertThat(end).as("the log ends on a frame boundary").isEqualTo(image.length);
        final long[] offsets = new long[at.size()];
        for (int i = 0; i < offsets.length; i++) {
            offsets[i] = at.get(i);
        }
        return offsets;
    }
}
