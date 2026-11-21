package dev.marregui.comando.cdb.store;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.Vector;

import static org.assertj.core.api.Assertions.assertThat;

/// [Version#read] and [Version#fetch] into a [Vector] give what the array reads (`longs`, `doubles`, `booleans`,
/// `texts`) give over the same version: every type, NULLs, NaN, multi-byte VARCHAR, a column with no file and one
/// past the definition. Seed in the output; replay with `-Dcdb.store.seed=<seed>`.
class VersionReadTest {

    static final int TS = 0;
    static final int L = 1;
    static final int D = 2;
    static final int B = 3;
    static final int S = 4;
    static final int V = 5;
    /// A LONG column with no file in the version, added after it was written.
    static final int LATE = 6;
    /// A column past the version's definition.
    static final int BEYOND = 7;
    static final CdbType[] TYPES = {CdbType.TIMESTAMP, CdbType.LONG, CdbType.DOUBLE, CdbType.BOOLEAN, CdbType.SYMBOL,
            CdbType.VARCHAR, CdbType.LONG};
    static final TableDef DEF = new TableDef("t", List.of(
            new ColumnDef("ts", CdbType.TIMESTAMP),
            new ColumnDef("l", CdbType.LONG),
            new ColumnDef("d", CdbType.DOUBLE),
            new ColumnDef("b", CdbType.BOOLEAN),
            new ColumnDef("s", CdbType.SYMBOL),
            new ColumnDef("v", CdbType.VARCHAR),
            new ColumnDef("late", CdbType.LONG)), TS, PartitionBy.DAY, List.of(), 0L);
    private static final String[] WORDS = {"ok", "warning", "", "ñandú", "日本語", "😀 emoji", "€", "a longer value"};
    private static final int ROWS = 5_000;

    @TempDir
    private Path dir;
    private Random random;

    /// A fresh generator per test, from the replay seed or a new one, printed.
    @BeforeEach
    void seed(final TestInfo test) {
        final long seed = Long.getLong("cdb.store.seed", System.nanoTime());
        System.out.println(test.getDisplayName() + " seed=" + seed);
        random = new Random(seed);
    }

    /// `read` of random windows into a vector at a random offset equals the array read of the same window, on every
    /// column.
    @Test
    void readEqualsTheArrayReads() throws IOException {
        final List<SymbolTable> symbols = new ArrayList<>();
        writeVersion(dir.resolve("p"), ROWS, random, symbols);
        final Version version = Version.open(dir.resolve("p"), DEF, ROWS, symbols);
        try {
            for (int round = 0; round < 200; round++) {
                final int n = 1 + random.nextInt(1_024);
                final long from = random.nextInt(ROWS - n + 1);
                final int at = random.nextInt(64);
                for (int c = 0; c <= BEYOND; c++) {
                    final Vector dst = Vector.of(c < TYPES.length ? TYPES[c] : CdbType.LONG, at + n);
                    version.read(c, from, n, dst, at);
                    final long[] rows = new long[n];
                    for (int k = 0; k < n; k++) {
                        rows[k] = from + k;
                    }
                    assertSame(version, c, rows, dst, at);
                }
            }
        } finally {
            version.unmap();
        }
    }

    /// `fetch` of random rows, in any order and with repeats, from a random offset of the row array, equals the array
    /// read of each row, on every column.
    @Test
    void fetchEqualsTheArrayReads() throws IOException {
        final List<SymbolTable> symbols = new ArrayList<>();
        writeVersion(dir.resolve("p"), ROWS, random, symbols);
        final Version version = Version.open(dir.resolve("p"), DEF, ROWS, symbols);
        try {
            for (int round = 0; round < 200; round++) {
                final int n = 1 + random.nextInt(512);
                final int from = random.nextInt(16);
                final long[] rows = new long[from + n];
                for (int k = 0; k < rows.length; k++) {
                    rows[k] = random.nextInt(ROWS);
                }
                final int at = random.nextInt(64);
                for (int c = 0; c <= BEYOND; c++) {
                    final Vector dst = Vector.of(c < TYPES.length ? TYPES[c] : CdbType.LONG, at + n);
                    version.fetch(c, rows, from, n, dst, at);
                    final long[] fetched = new long[n];
                    System.arraycopy(rows, from, fetched, 0, n);
                    assertSame(version, c, fetched, dst, at);
                }
            }
        } finally {
            version.unmap();
        }
    }

    /// A SYMBOL read into a vector carries, when the vector keeps keys, the key of the table's own dictionary.
    @Test
    void symbolsAreKeysOfTheTablesDictionary() throws IOException {
        final List<SymbolTable> symbols = new ArrayList<>();
        writeVersion(dir.resolve("p"), ROWS, random, symbols);
        final Version version = Version.open(dir.resolve("p"), DEF, ROWS, symbols);
        try {
            final Vector dst = Vector.of(CdbType.SYMBOL, ROWS);
            version.read(S, 0L, ROWS, dst, 0);
            final SymbolTable table = symbols.get(S);
            int nulls = 0;
            for (int i = 0; i < ROWS; i++) {
                if (dst.isNull(i)) {
                    nulls++;
                    continue;
                }
                final String value = dst.textAt(i);
                assertThat(table.keyOf(value)).isNotNegative();
                if (dst.symbolKey(i) != Vector.NO_KEY) {
                    assertThat(dst.symbolDictionary()).isSameAs(table);
                    assertThat(dst.symbolKey(i)).isEqualTo(table.keyOf(value));
                }
            }
            assertThat(nulls).isPositive();
        } finally {
            version.unmap();
        }
    }

    /// Asserts that values `at..at+rows.length` of `dst` equal the array reads of `rows` of column `c`.
    static void assertSame(final Version version, final int c, final long[] rows, final Vector dst, final int at) {
        final int n = rows.length;
        final CdbType type = c < TYPES.length ? TYPES[c] : CdbType.LONG;
        for (int k = 0; k < n; k++) {
            final long row = rows[k];
            switch (type) {
                case LONG, TIMESTAMP -> {
                    final long[] one = new long[1];
                    version.longs(c, row, 1, one);
                    assertThat(dst.getLong(at + k)).as("column %d row %d", c, row).isEqualTo(one[0]);
                }
                case DOUBLE -> {
                    final double[] one = new double[1];
                    version.doubles(c, row, 1, one);
                    assertThat(Double.doubleToRawLongBits(dst.getDouble(at + k))).as("column %d row %d", c, row)
                            .isEqualTo(Double.doubleToRawLongBits(one[0]));
                }
                case BOOLEAN -> {
                    final boolean[] one = new boolean[1];
                    version.booleans(c, row, 1, one);
                    assertThat(dst.getBoolean(at + k)).as("column %d row %d", c, row).isEqualTo(one[0]);
                }
                case SYMBOL, VARCHAR, JSON -> {
                    final String[] one = new String[1];
                    version.texts(c, row, 1, one);
                    assertThat(dst.isNull(at + k)).as("column %d row %d", c, row).isEqualTo(one[0] == null);
                    assertThat(dst.textAt(at + k)).as("column %d row %d", c, row).isEqualTo(one[0]);
                }
            }
        }
    }

    /// Writes the files of a version of `rows` rows of [#DEF] in `folder`, every column but [#LATE], random values with
    /// NULLs among them; adds one dictionary per column to `symbols`.
    static void writeVersion(final Path folder, final int rows, final Random random, final List<SymbolTable> symbols)
            throws IOException {
        Files.createDirectories(folder);
        for (final ColumnDef column : DEF.columns()) {
            symbols.add(SymbolTable.open(folder.resolve(column.name() + ".sym")));
        }
        final ByteBuffer ts = le(rows * Long.BYTES);
        final ByteBuffer l = le(rows * Long.BYTES);
        final ByteBuffer d = le(rows * Double.BYTES);
        final byte[] b = new byte[rows];
        final ByteBuffer s = le(rows * Integer.BYTES);
        final ByteBuffer vi = le(rows * Long.BYTES);
        final ByteArrayOutputStream vd = new ByteArrayOutputStream();
        for (int r = 0; r < rows; r++) {
            ts.putLong(1_000_000L * r);
            l.putLong(random.nextInt(10) == 0 ? Long.MIN_VALUE : random.nextLong());
            d.putDouble(random.nextInt(10) == 0 ? Double.NaN : random.nextGaussian() * 1e6);
            b[r] = (byte) (random.nextBoolean() ? 1 : 0);
            s.putInt(random.nextInt(10) == 0 ? -1 : symbols.get(S).intern(WORDS[random.nextInt(WORDS.length)]));
            if (random.nextInt(10) == 0) {
                vi.putLong(~(long) vd.size());
            } else {
                vd.writeBytes(WORDS[random.nextInt(WORDS.length)].getBytes(StandardCharsets.UTF_8));
                vi.putLong(vd.size());
            }
        }
        Files.write(folder.resolve("ts.d"), ts.array());
        Files.write(folder.resolve("l.d"), l.array());
        Files.write(folder.resolve("d.d"), d.array());
        Files.write(folder.resolve("b.d"), b);
        Files.write(folder.resolve("s.d"), s.array());
        Files.write(folder.resolve("v.i"), vi.array());
        Files.write(folder.resolve("v.d"), vd.toByteArray());
    }

    private static ByteBuffer le(final int bytes) {
        return ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }
}
