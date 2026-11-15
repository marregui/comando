package dev.marregui.comando.cdb.store;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Each read [Version] overrides gives what the interface's default body gives over the same version, read through an
/// adapter that overrides nothing: random data with NULLs, NaN, -0.0, empty and multi-byte text, across block
/// boundaries and partial last blocks. Seed in the output; replay with `-Dcdb.store.seed=<seed>`.
class VersionKernelsTest {

    private static final int TS = 0;
    private static final int L = 1;
    private static final int D = 2;
    private static final int B = 3;
    private static final int S = 4;
    private static final int V = 5;
    private static final int LATE_L = 6;
    private static final int LATE_D = 7;
    private static final int LATE_B = 8;
    private static final int LATE_S = 9;
    private static final int LATE_V = 10;
    /// A column past the version's definition, added after the version was opened.
    private static final int BEYOND = 11;
    private static final int[] ROWS = {0, 1, 4_095, 4_096, 4_097, 9_000};
    private static final String[] WORDS = {"ok", "warning", "error", "", "ñandú", "日本語", "😀 emoji", "€"};
    private static final TableDef DEF = new TableDef("t", List.of(
            new ColumnDef("ts", CdbType.TIMESTAMP),
            new ColumnDef("l", CdbType.LONG),
            new ColumnDef("d", CdbType.DOUBLE),
            new ColumnDef("b", CdbType.BOOLEAN),
            new ColumnDef("s", CdbType.SYMBOL),
            new ColumnDef("v", CdbType.VARCHAR),
            new ColumnDef("late_l", CdbType.LONG),
            new ColumnDef("late_d", CdbType.DOUBLE),
            new ColumnDef("late_b", CdbType.BOOLEAN),
            new ColumnDef("late_s", CdbType.SYMBOL),
            new ColumnDef("late_v", CdbType.VARCHAR)), TS, PartitionBy.DAY, List.of(), 0L);

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

    /// `longs`, `doubles` and `booleans`, one bulk copy each, equal the values the default reads give row by row.
    @Test
    void fixedWidthReadsEqualTheDefault() throws IOException {
        for (final int rows : ROWS) {
            try (Fixture f = fixture(rows, 0, false)) {
                for (final long[] w : windows(rows)) {
                    final int n = (int) w[1];
                    for (final int c : new int[] {TS, L, LATE_L, BEYOND}) {
                        final long[] actual = new long[n];
                        f.version.longs(c, w[0], n, actual);
                        assertThat(actual).containsExactly(f.expectedLongs(c, w[0], n));
                    }
                    for (final int c : new int[] {D, LATE_D, BEYOND}) {
                        final double[] actual = new double[n];
                        f.version.doubles(c, w[0], n, actual);
                        assertThat(bits(actual)).containsExactly(bits(f.expectedDoubles(c, w[0], n)));
                    }
                    for (final int c : new int[] {B, LATE_B, BEYOND}) {
                        final boolean[] actual = new boolean[n];
                        f.version.booleans(c, w[0], n, actual);
                        assertThat(actual).containsExactly(f.expectedBooleans(c, w[0], n));
                    }
                }
            }
        }
    }

    /// `texts` of a SYMBOL and a VARCHAR column, NULLs, empty and multi-byte values among them, equal the expected.
    @Test
    void textReadsEqualTheExpectedValues() throws IOException {
        for (final int rows : ROWS) {
            try (Fixture f = fixture(rows, 0, false)) {
                for (final long[] w : windows(rows)) {
                    final int n = (int) w[1];
                    for (final int c : new int[] {S, V, LATE_S, LATE_V, BEYOND}) {
                        final String[] actual = new String[n];
                        f.version.texts(c, w[0], n, actual);
                        assertThat(actual).containsExactly(f.expectedTexts(c, w[0], n));
                    }
                }
            }
        }
    }

    /// The binary search over the mapped timestamps finds the row the default's search finds, for present values,
    /// values between and beyond them, and the extremes.
    @Test
    void rowAtOrAfterEqualsTheDefault() throws IOException {
        for (final int rows : ROWS) {
            try (Fixture f = fixture(rows, 0, false)) {
                for (final long micros : f.longConstants(TS)) {
                    for (final int c : new int[] {TS, LATE_L, BEYOND}) {
                        assertThat(f.version.rowAtOrAfter(c, micros))
                                .as("rows=%d column=%d micros=%d", rows, c, micros)
                                .isEqualTo(f.defaults.rowAtOrAfter(c, micros));
                    }
                }
            }
        }
    }

    /// `selectLong` keeps the rows the default keeps, for every comparison, over zone-mapped blocks, those with NULLs
    /// and those without, and over LONG and TIMESTAMP columns alike.
    @Test
    void selectLongEqualsTheDefault() throws IOException {
        for (final int rows : ROWS) {
            try (Fixture f = fixture(rows, 0, false)) {
                for (final int c : new int[] {TS, L, LATE_L, BEYOND}) {
                    for (final long constant : f.longConstants(c == TS ? TS : L)) {
                        for (final Compare op : Compare.values()) {
                            for (final long[] w : windows(rows)) {
                                final int n = (int) w[1];
                                final int[] actual = new int[n];
                                final int[] expected = new int[n];
                                final int count = f.version.selectLong(c, w[0], n, op, constant, actual);
                                final int want = f.defaults.selectLong(c, w[0], n, op, constant, expected);
                                assertThat(Arrays.copyOf(actual, count))
                                        .as("rows=%d column=%d %s %d from=%d n=%d", rows, c, op, constant, w[0], n)
                                        .containsExactly(Arrays.copyOf(expected, want));
                            }
                        }
                    }
                }
            }
        }
    }

    /// `selectDouble` keeps the rows the default keeps, `Double.compare` deciding: -0.0 below 0.0, NaN rows kept, a
    /// NaN constant above every value.
    @Test
    void selectDoubleEqualsTheDefault() throws IOException {
        for (final int rows : ROWS) {
            try (Fixture f = fixture(rows, 0, false)) {
                for (final int c : new int[] {D, LATE_D, BEYOND}) {
                    for (final double constant : f.doubleConstants()) {
                        for (final Compare op : Compare.values()) {
                            for (final long[] w : windows(rows)) {
                                final int n = (int) w[1];
                                final int[] actual = new int[n];
                                final int[] expected = new int[n];
                                final int count = f.version.selectDouble(c, w[0], n, op, constant, actual);
                                final int want = f.defaults.selectDouble(c, w[0], n, op, constant, expected);
                                assertThat(Arrays.copyOf(actual, count))
                                        .as("rows=%d column=%d %s %s from=%d n=%d", rows, c, op, constant, w[0], n)
                                        .containsExactly(Arrays.copyOf(expected, want));
                            }
                        }
                    }
                }
            }
        }
    }

    /// `selectText` over a VARCHAR keeps the rows the default keeps: byte comparison of the constant's UTF-8, NULLs
    /// kept, a null or absent constant equal to no value.
    @Test
    void selectTextOnVarcharEqualsTheDefault() throws IOException {
        assertSelectTextEqualsTheDefault(V);
    }

    /// `selectText` over a SYMBOL keeps the rows the default keeps: the constant's key compared, a constant absent from
    /// the dictionary keeping only NULLs under EQ and every row under NE.
    @Test
    void selectTextOnSymbolEqualsTheDefault() throws IOException {
        assertSelectTextEqualsTheDefault(S);
    }

    /// `longsAt`, `doublesAt` and `booleansAt` gather the selected rows as the default does, one row at a time.
    @Test
    void fixedWidthGathersEqualTheDefault() throws IOException {
        for (final int rows : ROWS) {
            try (Fixture f = fixture(rows, 0, false)) {
                for (final long[] w : windows(rows)) {
                    final int[] sel = selection((int) w[1]);
                    final int count = sel.length;
                    for (final int c : new int[] {TS, L, LATE_L, BEYOND}) {
                        final long[] actual = new long[count];
                        final long[] expected = new long[count];
                        f.version.longsAt(c, w[0], sel, count, actual);
                        f.defaults.longsAt(c, w[0], sel, count, expected);
                        assertThat(actual).containsExactly(expected);
                    }
                    for (final int c : new int[] {D, LATE_D, BEYOND}) {
                        final double[] actual = new double[count];
                        final double[] expected = new double[count];
                        f.version.doublesAt(c, w[0], sel, count, actual);
                        f.defaults.doublesAt(c, w[0], sel, count, expected);
                        assertThat(bits(actual)).containsExactly(bits(expected));
                    }
                    for (final int c : new int[] {B, LATE_B, BEYOND}) {
                        final boolean[] actual = new boolean[count];
                        final boolean[] expected = new boolean[count];
                        f.version.booleansAt(c, w[0], sel, count, actual);
                        f.defaults.booleansAt(c, w[0], sel, count, expected);
                        assertThat(actual).containsExactly(expected);
                    }
                }
            }
        }
    }

    /// `textsAt` gathers the selected SYMBOL and VARCHAR rows as the default does.
    @Test
    void textGathersEqualTheDefault() throws IOException {
        for (final int rows : ROWS) {
            try (Fixture f = fixture(rows, 0, false)) {
                for (final long[] w : windows(rows)) {
                    final int[] sel = selection((int) w[1]);
                    for (final int c : new int[] {S, V, LATE_S, LATE_V, BEYOND}) {
                        final String[] actual = new String[sel.length];
                        final String[] expected = new String[sel.length];
                        f.version.textsAt(c, w[0], sel, sel.length, actual);
                        f.defaults.textsAt(c, w[0], sel, sel.length, expected);
                        assertThat(actual).containsExactly(expected);
                    }
                }
            }
        }
    }

    /// Files longer than the committed rows need, bytes past the end as an append that failed leaves them, open and
    /// read exactly as the same version without those bytes.
    @Test
    void aFileLongerThanItsRowsReadsByTheRowCount() throws IOException {
        for (final int rows : ROWS) {
            try (Fixture f = fixture(rows, 1 + random.nextInt(4_096), false)) {
                for (final long[] w : windows(rows)) {
                    final int n = (int) w[1];
                    final long[] longs = new long[n];
                    f.version.longs(L, w[0], n, longs);
                    assertThat(longs).containsExactly(f.expectedLongs(L, w[0], n));
                    final String[] texts = new String[n];
                    f.version.texts(V, w[0], n, texts);
                    assertThat(texts).containsExactly(f.expectedTexts(V, w[0], n));
                    final int[] sel = new int[n];
                    final int[] want = new int[n];
                    assertThat(f.version.selectLong(L, w[0], n, Compare.GE, Long.MIN_VALUE + 1L, sel))
                            .isEqualTo(f.defaults.selectLong(L, w[0], n, Compare.GE, Long.MIN_VALUE + 1L, want));
                }
                assertThat(f.version.rowAtOrAfter(TS, Long.MAX_VALUE)).isEqualTo(rows);
            }
        }
    }

    /// A column file shorter than its committed rows need is refused when the version opens.
    @Test
    void aFileShorterThanItsRowsIsRefused() throws IOException {
        final Path folder = dir.resolve("short");
        writeVersion(folder, 100, 0, false, new ArrayList<>(), new Data(100));
        final Path file = folder.resolve("l.d");
        final byte[] bytes = Files.readAllBytes(file);
        Files.write(file, Arrays.copyOf(bytes, bytes.length - 1));

        assertThatThrownBy(() -> Version.open(folder, DEF, 100, symbolTables(folder)))
                .isInstanceOf(CdbException.class);
    }

    /// A low-cardinality VARCHAR column reads each distinct value as one shared String, across calls and gathers;
    /// SYMBOL values are the dictionary's own Strings.
    @Test
    void lowCardinalityTextIsShared() throws IOException {
        try (Fixture f = fixture(9_000, 0, false)) {
            final String[] first = new String[9_000];
            final String[] second = new String[9_000];
            f.version.texts(V, 0L, 9_000, first);
            f.version.texts(V, 0L, 9_000, second);
            for (int i = 0; i < 9_000; i++) {
                if (first[i] != null && first[i].getBytes(StandardCharsets.UTF_8).length <= TextCache.MAX_BYTES) {
                    assertThat(second[i]).isSameAs(first[i]);
                }
            }
            final String[] symbols = new String[9_000];
            f.version.texts(S, 0L, 9_000, symbols);
            final String[] dictionary = f.symbols.get(S).values();
            for (final String value : symbols) {
                if (value != null) {
                    assertThat(value).isSameAs(dictionary[f.symbols.get(S).keyOf(value)]);
                }
            }
        }
    }

    /// A VARCHAR column of more distinct values than the cache keeps, some longer than it keeps, still reads and
    /// selects as the default does past the bound.
    @Test
    void highCardinalityTextEqualsTheDefaultPastTheBound() throws IOException {
        try (Fixture f = fixture(9_000, 0, true)) {
            final String[] actual = new String[9_000];
            f.version.texts(V, 0L, 9_000, actual);
            assertThat(actual).containsExactly(f.expectedTexts(V, 0L, 9_000));
            final String[] again = new String[9_000];
            f.version.texts(V, 0L, 9_000, again);
            assertThat(again).containsExactly(actual);
            for (final String constant : f.textConstants(V)) {
                final int[] sel = new int[9_000];
                final int[] want = new int[9_000];
                final int count = f.version.selectText(V, 0L, 9_000, Compare.EQ, constant, sel);
                final int expected = f.defaults.selectText(V, 0L, 9_000, Compare.EQ, constant, want);
                assertThat(Arrays.copyOf(sel, count)).containsExactly(Arrays.copyOf(want, expected));
            }
        }
    }

    /// A read of a column as another type is refused by an override as by the default.
    @Test
    void aReadAsAnotherTypeIsRefusedAsByTheDefault() throws IOException {
        try (Fixture f = fixture(10, 0, false)) {
            final int[] sel = new int[10];
            assertThatThrownBy(() -> f.version.selectLong(D, 0L, 10, Compare.EQ, 1L, sel))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> f.defaults.selectLong(D, 0L, 10, Compare.EQ, 1L, sel))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> f.version.selectText(L, 0L, 10, Compare.EQ, "x", sel))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> f.defaults.selectText(L, 0L, 10, Compare.EQ, "x", sel))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> f.version.doublesAt(L, 0L, sel, 1, new double[1]))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> f.defaults.doublesAt(L, 0L, sel, 1, new double[1]))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private void assertSelectTextEqualsTheDefault(final int column) throws IOException {
        for (final int rows : ROWS) {
            try (Fixture f = fixture(rows, 0, false)) {
                for (final int c : new int[] {column, column == S ? LATE_S : LATE_V, BEYOND}) {
                    for (final String constant : f.textConstants(column)) {
                        for (final Compare op : Compare.values()) {
                            for (final long[] w : windows(rows)) {
                                final int n = (int) w[1];
                                final int[] actual = new int[n];
                                final int[] expected = new int[n];
                                final int count = f.version.selectText(c, w[0], n, op, constant, actual);
                                final int want = f.defaults.selectText(c, w[0], n, op, constant, expected);
                                assertThat(Arrays.copyOf(actual, count))
                                        .as("rows=%d column=%d %s '%s' from=%d n=%d", rows, c, op, constant, w[0], n)
                                        .containsExactly(Arrays.copyOf(expected, want));
                            }
                        }
                    }
                }
            }
        }
    }

    /// Windows `{from, n}` over `rows`: the whole, empty ones at both ends, block edges, the last partial block and
    /// random ones.
    private List<long[]> windows(final int rows) {
        final List<long[]> windows = new ArrayList<>();
        windows.add(new long[] {0L, rows});
        windows.add(new long[] {0L, 0L});
        windows.add(new long[] {rows, 0L});
        final int block = ZoneMap.BLOCK_ROWS;
        if (rows > block) {
            windows.add(new long[] {block - 3L, Math.min(7L, rows - (block - 3L))});
            windows.add(new long[] {block - 1L, 1L});
            windows.add(new long[] {block, rows - block});
        }
        if (rows % block != 0) {
            windows.add(new long[] {rows - rows % block, rows % block});
        }
        for (int i = 0; i < 6 && rows > 0; i++) {
            final int from = random.nextInt(rows);
            windows.add(new long[] {from, random.nextInt(Math.min(rows - from, 2_048) + 1)});
        }
        return windows;
    }

    /// Ascending random offsets below `n`, some of them.
    private int[] selection(final int n) {
        final int[] sel = new int[n];
        int count = 0;
        for (int i = 0; i < n; i++) {
            if (random.nextInt(3) == 0) {
                sel[count++] = i;
            }
        }
        return Arrays.copyOf(sel, count);
    }

    private Fixture fixture(final int rows, final int junk, final boolean highCardinality) throws IOException {
        final Path folder = dir.resolve("v" + rows + '_' + junk + '_' + highCardinality);
        final Data data = new Data(rows);
        final List<SymbolTable> symbols = new ArrayList<>();
        writeVersion(folder, rows, junk, highCardinality, symbols, data);
        final Version version = Version.open(folder, DEF, rows, symbols);
        return new Fixture(version, new Defaults(version), data, symbols, random);
    }

    private void writeVersion(final Path folder, final int rows, final int junk, final boolean highCardinality,
            final List<SymbolTable> symbols, final Data data) throws IOException {
        Files.createDirectories(folder);
        symbols.clear();
        for (int c = 0; c < DEF.columns().size(); c++) {
            symbols.add(SymbolTable.open(folder.resolve(DEF.columns().get(c).name() + ".sym")));
        }
        long ts = 1_700_000_000_000_000L;
        for (int r = 0; r < rows; r++) {
            ts += random.nextInt(4);
            data.ts[r] = ts;
            final boolean nullable = (r >> ZoneMap.BLOCK_SHIFT) % 2 == 1;
            data.l[r] = nullable && random.nextInt(30) == 0 ? Long.MIN_VALUE : longValue(r);
            data.d[r] = nullable && random.nextInt(30) == 0 ? Double.NaN : doubleValue(r);
            data.b[r] = random.nextBoolean();
            data.s[r] = random.nextInt(20) == 0 ? null : WORDS[random.nextInt(WORDS.length)];
            data.v[r] = random.nextInt(20) == 0 ? null : text(highCardinality);
        }
        final ByteBuffer ts8 = buffer(rows * 8);
        final ByteBuffer l8 = buffer(rows * 8);
        final ByteBuffer d8 = buffer(rows * 8);
        final ByteBuffer b1 = buffer(rows);
        final ByteBuffer s4 = buffer(rows * 4);
        final ByteBuffer vi = buffer(rows * 8);
        final ByteArrayOutputStream vd = new ByteArrayOutputStream();
        for (int r = 0; r < rows; r++) {
            ts8.putLong(data.ts[r]);
            l8.putLong(data.l[r]);
            d8.putLong(Double.doubleToRawLongBits(data.d[r]));
            b1.put((byte) (data.b[r] ? 1 : 0));
            s4.putInt(symbols.get(S).intern(data.s[r]));
            if (data.v[r] != null) {
                vd.writeBytes(data.v[r].getBytes(StandardCharsets.UTF_8));
                vi.putLong(vd.size());
            } else {
                vi.putLong(~(long) vd.size());
            }
        }
        write(folder.resolve("ts.d"), ts8.array(), junk);
        write(folder.resolve("l.d"), l8.array(), junk);
        write(folder.resolve("d.d"), d8.array(), junk);
        write(folder.resolve("b.d"), b1.array(), junk);
        write(folder.resolve("s.d"), s4.array(), junk);
        write(folder.resolve("v.i"), vi.array(), junk);
        write(folder.resolve("v.d"), vd.toByteArray(), junk);
    }

    private long longValue(final int row) {
        return switch (random.nextInt(500)) {
            case 0 -> Long.MAX_VALUE;
            case 1 -> Long.MIN_VALUE + 1L;
            default -> row * 3L + random.nextInt(7) - 3L;
        };
    }

    private double doubleValue(final int row) {
        return switch (random.nextInt(200)) {
            case 0 -> -0.0;
            case 1 -> 0.0;
            case 2 -> Double.POSITIVE_INFINITY;
            case 3 -> Double.NEGATIVE_INFINITY;
            default -> (row / 7) * 0.5 - 100.0;
        };
    }

    private String text(final boolean highCardinality) {
        if (highCardinality) {
            final int kind = random.nextInt(10);
            if (kind == 0) {
                return "€".repeat(TextCache.MAX_BYTES / 3 + random.nextInt(20)) + random.nextInt(1_000);
            }
            return "v" + random.nextInt(5_000) + (kind == 1 ? "日本" : "");
        }
        return WORDS[random.nextInt(WORDS.length)];
    }

    private void write(final Path file, final byte[] bytes, final int junk) throws IOException {
        Files.write(file, bytes);
        if (junk > 0) {
            final byte[] tail = new byte[junk];
            random.nextBytes(tail);
            Files.write(file, tail, StandardOpenOption.APPEND);
        }
    }

    private static List<SymbolTable> symbolTables(final Path folder) {
        final List<SymbolTable> symbols = new ArrayList<>();
        for (final ColumnDef column : DEF.columns()) {
            symbols.add(SymbolTable.open(folder.resolve(column.name() + ".sym")));
        }
        return symbols;
    }

    private static ByteBuffer buffer(final int bytes) {
        return ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static long[] bits(final double[] values) {
        final long[] bits = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            bits[i] = Double.doubleToRawLongBits(values[i]);
        }
        return bits;
    }

    /// The values written, by column.
    private static final class Data {

        final long[] ts;
        final long[] l;
        final double[] d;
        final boolean[] b;
        final String[] s;
        final String[] v;

        Data(final int rows) {
            ts = new long[rows];
            l = new long[rows];
            d = new double[rows];
            b = new boolean[rows];
            s = new String[rows];
            v = new String[rows];
        }
    }

    /// A version, the default-only adapter over it and the data both must read back.
    private record Fixture(Version version, Defaults defaults, Data data, List<SymbolTable> symbols, Random random)
            implements AutoCloseable {

        long[] expectedLongs(final int column, final long from, final int n) {
            final long[] values = new long[n];
            for (int i = 0; i < n; i++) {
                values[i] = switch (column) {
                    case TS -> data.ts[(int) from + i];
                    case L -> data.l[(int) from + i];
                    default -> Long.MIN_VALUE;
                };
            }
            return values;
        }

        double[] expectedDoubles(final int column, final long from, final int n) {
            final double[] values = new double[n];
            for (int i = 0; i < n; i++) {
                values[i] = column == D ? data.d[(int) from + i] : Double.NaN;
            }
            return values;
        }

        boolean[] expectedBooleans(final int column, final long from, final int n) {
            final boolean[] values = new boolean[n];
            for (int i = 0; i < n; i++) {
                values[i] = column == B && data.b[(int) from + i];
            }
            return values;
        }

        String[] expectedTexts(final int column, final long from, final int n) {
            final String[] values = new String[n];
            for (int i = 0; i < n; i++) {
                values[i] = switch (column) {
                    case S -> data.s[(int) from + i];
                    case V -> data.v[(int) from + i];
                    default -> null;
                };
            }
            return values;
        }

        /// Constants for a comparison with the column `column` holds: present values, their neighbours, the extremes.
        List<Long> longConstants(final int column) {
            final long[] values = column == TS ? data.ts : data.l;
            final List<Long> constants = new ArrayList<>(List.of(Long.MIN_VALUE, Long.MIN_VALUE + 1L, Long.MAX_VALUE,
                    0L, -1L));
            for (int i = 0; i < 4 && values.length > 0; i++) {
                final long value = values[random.nextInt(values.length)];
                constants.add(value);
                constants.add(value + 1L);
                constants.add(value - 1L);
            }
            if (values.length > 0) {
                constants.add(values[0]);
                constants.add(values[values.length - 1]);
            }
            return constants;
        }

        List<Double> doubleConstants() {
            final List<Double> constants = new ArrayList<>(List.of(Double.NaN, -0.0, 0.0, Double.POSITIVE_INFINITY,
                    Double.NEGATIVE_INFINITY, -100.0, 1e300));
            for (int i = 0; i < 4 && data.d.length > 0; i++) {
                final double value = data.d[random.nextInt(data.d.length)];
                constants.add(value);
                constants.add(Math.nextUp(value));
            }
            return constants;
        }

        List<String> textConstants(final int column) {
            final String[] values = column == S ? data.s : data.v;
            final List<String> constants = new ArrayList<>(Arrays.asList(WORDS));
            constants.add(null);
            constants.add("absent");
            constants.add("o");
            for (int i = 0; i < 4 && values.length > 0; i++) {
                constants.add(values[random.nextInt(values.length)]);
            }
            return constants;
        }

        @Override
        public void close() {
            version.unmap();
        }
    }

    /// Reads through the version's four column reads only, so every other method runs the interface's default body.
    private record Defaults(PartitionReader reader) implements PartitionReader {

        @Override
        public long lowerBound() {
            return reader.lowerBound();
        }

        @Override
        public long minTimestamp() {
            return reader.minTimestamp();
        }

        @Override
        public long maxTimestamp() {
            return reader.maxTimestamp();
        }

        @Override
        public long rowCount() {
            return reader.rowCount();
        }

        @Override
        public void longs(final int column, final long from, final int n, final long[] dst) {
            reader.longs(column, from, n, dst);
        }

        @Override
        public void doubles(final int column, final long from, final int n, final double[] dst) {
            reader.doubles(column, from, n, dst);
        }

        @Override
        public void booleans(final int column, final long from, final int n, final boolean[] dst) {
            reader.booleans(column, from, n, dst);
        }

        @Override
        public void texts(final int column, final long from, final int n, final String[] dst) {
            reader.texts(column, from, n, dst);
        }
    }
}
