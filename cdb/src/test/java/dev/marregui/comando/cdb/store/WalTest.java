package dev.marregui.comando.cdb.store;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [Wal] keeps each commit's rows, definition and truncation in a frame of its own: a torn or damaged tail is cut,
/// a frame whose CRC holds but which does not fit is refused, and a failed append leaves the log as it was.
class WalTest {

    // a NaN with a payload: the log keeps a double's raw bits, not only its NaN-ness
    private static final long NAN_BITS = 0x7FF8_0000_0000_0123L;
    private static final TableDef DEF = new TableDef("t", List.of(
            new ColumnDef("ts", CdbType.TIMESTAMP),
            new ColumnDef("l", CdbType.LONG),
            new ColumnDef("d", CdbType.DOUBLE),
            new ColumnDef("b", CdbType.BOOLEAN),
            new ColumnDef("s", CdbType.SYMBOL),
            new ColumnDef("v", CdbType.VARCHAR)
    ), 0, PartitionBy.DAY, List.of(), 0L);
    private static final TableDef WIDER = DEF.withColumn(new ColumnDef("extra", CdbType.LONG));

    /// The recorder a test installs is gone before the next.
    @AfterEach
    void removeRecorder() {
        StoreFiles.recorder = null;
    }

    /// A missing log is created with its header, the file forced and then its folder; an existing one opens empty.
    @Test
    void openCreatesTheHeaderForcedWithItsFolder(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve(Wal.FILE);
        final List<String> forced = new ArrayList<>();
        StoreFiles.recorder = new Recording() {
            @Override
            public void forcing(final Path path) {
                forced.add("file " + path.getFileName());
            }

            @Override
            public void folderForcing(final Path folder) {
                forced.add("folder " + folder.equals(dir));
            }
        };
        final Wal wal = Wal.open(dir);
        assertThat(forced).containsExactly("file " + Wal.FILE, "folder true");
        assertThat(Files.size(file)).isEqualTo(Wal.HEADER);
        assertThat(wal.bytes()).isEqualTo(Wal.HEADER);
        assertThat(wal.records()).isZero();
        assertThat(Wal.open(dir).scan(0L, DEF)).isEmpty();
    }

    /// Every type reads back as written: NULLs (a NaN with its payload bits), empty and non-ASCII text, a logged
    /// definition that the next entry's rows follow, a truncation, and a commit of no rows.
    @Test
    void everyTypeAndEntryKindRoundTrips(@TempDir final Path dir) throws IOException {
        final Wal wal = Wal.open(dir);
        final RowBlock first = sample(DEF, 4);
        final RowBlock widened = sample(WIDER, 2);
        final RowBlock empty = new RowBlock(WIDER.columns(), 0);
        wal.append(1L, false, null, first);
        wal.append(2L, false, WIDER, widened);
        wal.append(3L, true, null, empty);
        wal.append(4L, false, null, sample(WIDER, 1));
        assertThat(wal.records()).isEqualTo(4);
        assertThat(wal.bytes()).isEqualTo(Files.size(dir.resolve(Wal.FILE)));

        final Wal reopened = Wal.open(dir);
        final List<Wal.Entry> entries = reopened.scan(0L, DEF);
        assertThat(entries).extracting(Wal.Entry::txn).containsExactly(1L, 2L, 3L, 4L);
        assertThat(entries).extracting(Wal.Entry::truncate).containsExactly(false, false, true, false);
        assertThat(entries).extracting(Wal.Entry::def).containsExactly(null, WIDER, null, null);
        assertSameRows(entries.get(0).rows(), first);
        assertSameRows(entries.get(1).rows(), widened);
        assertSameRows(entries.get(2).rows(), empty);
        assertSameRows(entries.get(3).rows(), sample(WIDER, 1));
        assertThat(Double.doubleToRawLongBits(entries.get(0).rows().getDouble(2, 1))).isEqualTo(NAN_BITS);
        assertThat(texts(entries.get(0).rows(), 5, 3)).containsExactly("", null, "Grüße, 東京 ✓");
        assertThat(reopened.records()).isEqualTo(4);
        assertThat(reopened.bytes()).isEqualTo(wal.bytes());
    }

    /// The last frame cut at every byte, as a crash during its write leaves it: the earlier entries come back and the
    /// file is cut to them, so the next append follows the last whole frame.
    @Test
    void aTornLastFrameIsCutAtEveryByte(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve(Wal.FILE);
        final Wal wal = Wal.open(dir);
        wal.append(1L, false, null, sample(DEF, 3));
        final long whole = wal.bytes();
        wal.append(2L, false, WIDER, sample(WIDER, 3));
        final byte[] image = Files.readAllBytes(file);
        for (int cut = (int) whole; cut < image.length; cut++) {
            Files.write(file, Arrays.copyOf(image, cut));
            final Wal torn = Wal.open(dir);
            final List<Wal.Entry> entries = torn.scan(0L, DEF);
            assertThat(entries).as("cut at %d", cut).extracting(Wal.Entry::txn).containsExactly(1L);
            assertSameRows(entries.getFirst().rows(), sample(DEF, 3));
            assertThat(Files.size(file)).as("cut at %d", cut).isEqualTo(whole);
            assertThat(torn.records()).isEqualTo(1);
            assertThat(torn.bytes()).isEqualTo(whole);
        }
        final Wal afterCut = Wal.open(dir);
        afterCut.scan(0L, DEF);
        afterCut.append(2L, false, null, sample(DEF, 1));
        assertThat(Wal.open(dir).scan(0L, DEF)).extracting(Wal.Entry::txn).containsExactly(1L, 2L);
    }

    /// One bit flipped anywhere in the last frame, length and CRC included, never yields a wrong entry: CRC32C
    /// catches every single-bit error, so the frame is dropped as a torn tail and the earlier entry kept. The last
    /// frame logs no definition, to keep the frame, and the cuts this forces, small; a definition is bytes under the
    /// same CRC.
    @Test
    void aFlippedBitInTheLastFrameNeverYieldsAWrongEntry(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve(Wal.FILE);
        final Wal wal = Wal.open(dir);
        wal.append(1L, false, null, sample(DEF, 2));
        final long whole = wal.bytes();
        wal.append(2L, true, null, sample(DEF, 3));
        final byte[] image = Files.readAllBytes(file);
        for (int at = (int) whole; at < image.length; at++) {
            for (int bit = 0; bit < Byte.SIZE; bit++) {
                final byte[] damaged = image.clone();
                damaged[at] ^= (byte) (1 << bit);
                Files.write(file, damaged);
                final List<Wal.Entry> entries = Wal.open(dir).scan(0L, DEF);
                assertThat(entries).as("byte %d, bit %d", at, bit).extracting(Wal.Entry::txn).containsExactly(1L);
                assertSameRows(entries.getFirst().rows(), sample(DEF, 2));
                assertThat(Files.size(file)).as("byte %d, bit %d", at, bit).isEqualTo(whole);
            }
        }
    }

    /// A transaction that does not follow the previous frame's is corruption, not a torn tail: refused, nothing cut.
    @Test
    void aTransactionGapIsRefused(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve(Wal.FILE);
        final Wal wal = Wal.open(dir);
        wal.append(7L, false, null, sample(DEF, 1));
        final long whole = wal.bytes();
        // a log opened without its scan knows no last record, so it appends 9 after 7 as given
        Wal.open(dir).append(9L, false, null, sample(DEF, 1));
        final long size = Files.size(file);
        assertThatThrownBy(() -> Wal.open(dir).scan(6L, DEF))
                .isInstanceOf(CdbException.class)
                .hasMessage("refuse log record out of order [file=" + file + ", offset=" + whole
                        + ", txn=9, previous=7]");
        assertThat(Files.size(file)).isEqualTo(size);
    }

    /// A frame whose CRC holds but whose columns are not the definition's is corruption: refused, nothing cut.
    @Test
    void aWholeFrameOfAnotherDefinitionIsRefused(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve(Wal.FILE);
        Wal.open(dir).append(1L, false, null, sample(WIDER, 1));
        final long size = Files.size(file);
        assertThatThrownBy(() -> Wal.open(dir).scan(0L, DEF))
                .isInstanceOf(CdbException.class)
                .hasMessage("refuse log record of another definition [file=" + file + ", offset=" + Wal.HEADER
                        + ", txn=1, columns=7, expected=6]");
        assertThat(Files.size(file)).isEqualTo(size);
    }

    /// Frames at or below the checkpointed transaction, left by a checkpoint that crashed before its reset, are
    /// skipped, though their columns are fewer than the definition's, and still counted as records.
    @Test
    void framesUpToTheCheckpointAreSkipped(@TempDir final Path dir) throws IOException {
        final Wal wal = Wal.open(dir);
        wal.append(5L, false, null, sample(DEF, 2));
        wal.append(6L, false, WIDER, sample(WIDER, 2));
        wal.append(7L, false, null, sample(WIDER, 3));
        final Wal reopened = Wal.open(dir);
        final List<Wal.Entry> entries = reopened.scan(6L, WIDER);
        assertThat(entries).extracting(Wal.Entry::txn).containsExactly(7L);
        assertSameRows(entries.getFirst().rows(), sample(WIDER, 3));
        assertThat(reopened.records()).isEqualTo(3);
        assertThat(Wal.open(dir).scan(7L, WIDER)).isEmpty();
    }

    /// A reset cuts the log back to its header; what is appended after it is all a scan returns.
    @Test
    void resetThenAppendThenScan(@TempDir final Path dir) throws IOException {
        final Wal wal = Wal.open(dir);
        wal.append(1L, false, null, sample(DEF, 2));
        wal.append(2L, false, null, sample(DEF, 2));
        wal.reset();
        assertThat(wal.records()).isZero();
        assertThat(wal.bytes()).isEqualTo(Wal.HEADER);
        assertThat(Files.size(dir.resolve(Wal.FILE))).isEqualTo(Wal.HEADER);
        wal.append(3L, false, null, sample(DEF, 1));
        assertThat(wal.records()).isEqualTo(1);
        final List<Wal.Entry> entries = Wal.open(dir).scan(2L, DEF);
        assertThat(entries).extracting(Wal.Entry::txn).containsExactly(3L);
        assertSameRows(entries.getFirst().rows(), sample(DEF, 1));
    }

    /// An append whose force fails throws, and the log is cut back to its length before it: the next append and a
    /// scan see only the frames before the failure.
    @Test
    void aFailedForceLeavesTheLogAtItsOldLength(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve(Wal.FILE);
        final Wal wal = Wal.open(dir);
        wal.append(1L, false, null, sample(DEF, 2));
        final long before = Files.size(file);
        final int[] failures = {1};
        StoreFiles.recorder = new Recording() {
            @Override
            public void forcing(final Path path) throws IOException {
                if (path.equals(file) && failures[0]-- > 0) {
                    throw new IOException("injected force failure");
                }
            }
        };
        assertThatThrownBy(() -> wal.append(2L, false, null, sample(DEF, 2)))
                .isInstanceOf(IOException.class)
                .satisfies(e -> assertThat(e.getSuppressed()).isEmpty());
        assertThat(Files.size(file)).isEqualTo(before);
        assertThat(wal.bytes()).isEqualTo(before);
        assertThat(wal.records()).isEqualTo(1);
        wal.append(2L, false, null, sample(DEF, 1));
        assertThat(Wal.open(dir).scan(0L, DEF)).extracting(Wal.Entry::txn).containsExactly(1L, 2L);
    }

    /// When the cut after a failed force fails too, at its own force, the cut's failure travels suppressed on the
    /// append's, and the file is still back at its old length.
    @Test
    void aFailedCutIsSuppressedOnTheAppendFailure(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve(Wal.FILE);
        final Wal wal = Wal.open(dir);
        wal.append(1L, false, null, sample(DEF, 2));
        final long before = Files.size(file);
        StoreFiles.recorder = new Recording() {
            @Override
            public void forcing(final Path path) throws IOException {
                throw new IOException("injected force failure");
            }
        };
        assertThatThrownBy(() -> wal.append(2L, false, null, sample(DEF, 2)))
                .isInstanceOf(IOException.class)
                .satisfies(e -> assertThat(e.getSuppressed()).hasSize(1).hasOnlyElementsOfType(IOException.class));
        assertThat(Files.size(file)).isEqualTo(before);
    }

    /// A log shorter than its header, left by a crash during its creation, is created again; an unknown magic and an
    /// unknown version are refused on open.
    @Test
    void anUnknownHeaderIsRefused(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve(Wal.FILE);
        Files.write(file, new byte[] {0x43, 0x57, 0x41});
        final Wal wal = Wal.open(dir);
        assertThat(Files.size(file)).isEqualTo(Wal.HEADER);
        assertThat(wal.scan(0L, DEF)).isEmpty();
        Files.write(file, header(0x4C415744, 1));
        assertThatThrownBy(() -> Wal.open(dir))
                .isInstanceOf(CdbException.class)
                .hasMessage("refuse log of unknown format [file=" + file + ", magic=0x4c415744, version=1]");
        Files.write(file, header(0x4C415743, 3));
        assertThatThrownBy(() -> Wal.open(dir))
                .isInstanceOf(CdbException.class)
                .hasMessage("refuse log of unknown format [file=" + file + ", magic=0x4c415743, version=3]");
    }

    private static byte[] header(final int magic, final int version) {
        return ByteBuffer.allocate(Wal.HEADER).order(ByteOrder.LITTLE_ENDIAN).putInt(magic).putInt(version).array();
    }

    /// `n` rows in the columns of `def`, the same for the same arguments: row 1 holds every NULL, row 0 the
    /// smallest non-NULL values and an empty text, row 2 non-ASCII text.
    private static RowBlock sample(final TableDef def, final int n) {
        final RowBlock rows = new RowBlock(def.columns(), n);
        rows.size = n;
        for (int c = 0; c < rows.types.length; c++) {
            for (int r = 0; r < n; r++) {
                final boolean isNull = r == 1;
                switch (rows.types[c]) {
                    case LONG, TIMESTAMP -> rows.setLong(c, r, isNull ? Long.MIN_VALUE : Long.MIN_VALUE + 1L + c * r);
                    case DOUBLE -> rows.setDouble(c, r, isNull ? Double.longBitsToDouble(NAN_BITS) : -0.5 * r);
                    case BOOLEAN -> rows.setBoolean(c, r, !isNull && r % 2 == 0);
                    case SYMBOL, VARCHAR -> rows.setText(c, r, switch (r) {
                        case 0 -> "";
                        case 1 -> null;
                        case 2 -> "Grüße, 東京 ✓";
                        default -> "v" + c + '.' + r;
                    });
                }
            }
        }
        return rows;
    }

    private static void assertSameRows(final RowBlock actual, final RowBlock expected) {
        assertThat(actual.types).containsExactly(expected.types);
        assertThat(actual.size).isEqualTo(expected.size);
        final int n = expected.size;
        for (int c = 0; c < expected.types.length; c++) {
            switch (expected.types[c]) {
                case LONG, TIMESTAMP -> assertThat(longs(actual, c, n)).containsExactly(longs(expected, c, n));
                case DOUBLE -> assertThat(rawBits(actual, c, n)).containsExactly(rawBits(expected, c, n));
                case BOOLEAN -> assertThat(booleans(actual, c, n)).containsExactly(booleans(expected, c, n));
                case SYMBOL, VARCHAR -> assertThat(texts(actual, c, n)).containsExactly(texts(expected, c, n));
            }
        }
    }

    private static long[] longs(final RowBlock rows, final int column, final int n) {
        final long[] values = new long[n];
        for (int r = 0; r < n; r++) {
            values[r] = rows.getLong(column, r);
        }
        return values;
    }

    private static boolean[] booleans(final RowBlock rows, final int column, final int n) {
        final boolean[] values = new boolean[n];
        for (int r = 0; r < n; r++) {
            values[r] = rows.getBoolean(column, r);
        }
        return values;
    }

    private static String[] texts(final RowBlock rows, final int column, final int n) {
        final String[] values = new String[n];
        for (int r = 0; r < n; r++) {
            values[r] = rows.textAt(column, r);
        }
        return values;
    }

    private static long[] rawBits(final RowBlock rows, final int column, final int n) {
        final long[] bits = new long[n];
        for (int r = 0; r < n; r++) {
            bits[r] = Double.doubleToRawLongBits(rows.getDouble(column, r));
        }
        return bits;
    }

    /// A log whose first commit after the checkpoint is not the checkpoint's txn + 1 lost commits: refused.
    @Test
    void aLogThatSkipsCommitsAfterTheCheckpointIsRefused(@TempDir final Path dir) throws IOException {
        final Wal wal = Wal.open(dir);
        wal.append(8L, false, null, sample(DEF, 2));
        assertThatThrownBy(() -> Wal.open(dir).scan(5L, DEF))
                .isInstanceOf(CdbException.class)
                .hasMessageStartingWith("refuse log that skips commits after the checkpoint");
        assertThat(Wal.open(dir).scan(7L, DEF)).hasSize(1);
    }

    /// An append whose transaction does not follow the log's last record comes after a direct commit's checkpoint
    /// that could not cut the log: the records before it are cut first, so the log never holds a gap.
    @Test
    void anAppendAfterADirectCommitCutsTheRecordsBeforeIt(@TempDir final Path dir) throws IOException {
        final Wal wal = Wal.open(dir);
        wal.append(1L, false, null, sample(DEF, 1));
        wal.append(2L, false, null, sample(DEF, 1));
        wal.append(4L, false, null, sample(DEF, 1));
        assertThat(wal.records()).isEqualTo(1);
        assertThat(Wal.open(dir).scan(3L, DEF)).extracting(Wal.Entry::txn).containsExactly(4L);
    }

    /// A recorder that records nothing; a test overrides the operations it fails or watches.
    private static class Recording implements StoreFiles.Recorder {

        @Override
        public void created(final Path path) {
        }

        @Override
        public void wrote(final Path file, final long position, final ByteBuffer bytes) {
        }

        @Override
        public void truncated(final Path file, final long size) {
        }

        @Override
        public void forcing(final Path file) throws IOException {
        }

        @Override
        public void folderForcing(final Path folder) throws IOException {
        }

        @Override
        public void moved(final Path from, final Path to) {
        }

        @Override
        public void deleted(final Path path) {
        }
    }
}
