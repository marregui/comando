package dev.marregui.comando.cdb.store;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import dev.marregui.comando.cdb.CdbType;

import static dev.marregui.comando.cdb.store.StoreRows.micros;
import static dev.marregui.comando.cdb.store.StoreRows.rows;
import static org.assertj.core.api.Assertions.assertThat;

/// Every commit to a store that always rewrites (`appendInPlace` off) reads as a model of the commit's rules: the
/// committed rows and the new, in written order, sorted by timestamp, stable, then with dedup keys every row a later
/// row of its timestamp and keys replaces dropped. Seeded commits over two partitions cover each column type as a key
/// and as a value, NULLs, empty and non-ASCII text, a text over a MiB, more than a chunk of committed rows at one
/// timestamp of which some are replaced, and columns added after a partition was written.
class PartitionRewriteModelTest {

    private static final long DAY1 = micros("2026-11-01T00:00:00Z");
    private static final long DAY2 = DAY1 + Retention.DAY_MICROS;
    private static final int COMMITS = 40;
    // the commit of more committed rows at one timestamp than a rewrite reads at a time, and the one that replaces some
    private static final int GROUP_AT = 5;
    private static final int GROUP_REPLACED_AT = 25;
    private static final int GROUP = 1_500;
    private static final long GROUP_TS = DAY1 + 7_000L;
    // the commit of a text longer than a rewrite's buffer, and the one that adds the late columns, to DAY1 alone
    private static final int HUGE_AT = 10;
    private static final int HUGE_CHARS = 1_500_000;
    private static final int ADD_AT = 15;
    // no other writer exists in this test: a wait this long is a leak
    private static final long WAIT_MILLIS = 1_000L;
    private static final List<ColumnDef> COLUMNS = List.of(
            new ColumnDef("ts", CdbType.TIMESTAMP),
            new ColumnDef("k", CdbType.LONG),
            new ColumnDef("t", CdbType.VARCHAR),
            new ColumnDef("sym", CdbType.SYMBOL),
            new ColumnDef("x", CdbType.DOUBLE),
            new ColumnDef("flag", CdbType.BOOLEAN),
            new ColumnDef("j", CdbType.JSON),
            new ColumnDef("note", CdbType.VARCHAR));
    private static final List<ColumnDef> LATE = List.of(
            new ColumnDef("lateL", CdbType.LONG),
            new ColumnDef("lateV", CdbType.VARCHAR),
            new ColumnDef("lateS", CdbType.SYMBOL),
            new ColumnDef("lateB", CdbType.BOOLEAN),
            new ColumnDef("lateD", CdbType.DOUBLE),
            new ColumnDef("lateJ", CdbType.JSON));
    private static final String[] TEXTS = {"a", "b", "é", "", null, "ünï-€"};
    private static final String[] SYMBOLS = {"s0", "s1", "s2", null};
    private static final Double[] DOUBLES = {0.5, -1.0, 2.25, null};
    private static final String[] JSONS = {"{\"a\":1}", "[1,2]", null};

    /// [#COMMITS] seeded commits read as the model after each one and after a reopen. Replay with
    /// `-Dcdb.rewrite.seed=<seed>` on the test JVM.
    @ParameterizedTest
    @EnumSource(Keys.class)
    void everyRewriteReadsAsTheModel(final Keys keys, @TempDir final Path root) {
        final long seed = Long.getLong("cdb.rewrite.seed", System.nanoTime());
        System.out.println("everyRewriteReadsAsTheModel keys=" + keys + " seed=" + seed);
        final Random random = new Random(seed);
        final Model model = new Model(keys);
        try (final Store store = Store.open(root)) {
            store.appendInPlace = false;
            // every commit a checkpoint on this thread, its own rewrite
            store.directRows = 0;
            store.create(new TableDef("m", COLUMNS, 0, PartitionBy.DAY, keys.columns, 0L), false);
            for (int commit = 0; commit < COMMITS; commit++) {
                try (final TableWriter w = store.writer("m", WAIT_MILLIS)) {
                    if (commit == ADD_AT) {
                        for (final ColumnDef late : LATE) {
                            w.addColumn(late);
                            model.add(late.type());
                        }
                    }
                    final List<Object[]> batch = batch(random, commit, model.types.size());
                    for (final Object[] row : batch) {
                        write(w, model.types, row);
                    }
                    w.commit();
                    model.commit(batch);
                }
                assertThat(read(store)).as("seed=%d commit=%d", seed, commit).isEqualTo(model.lines());
            }
        }
        try (final Store store = Store.open(root)) {
            assertThat(read(store)).as("seed=%d reopened", seed).isEqualTo(model.lines());
        }
    }

    // the rows of one commit, of width columns
    private static List<Object[]> batch(final Random random, final int commit, final int width) {
        final List<Object[]> rows = new ArrayList<>();
        if (commit == GROUP_AT || commit == GROUP_REPLACED_AT) {
            for (int i = 0; i < GROUP; i++) {
                // every key set tells the group's rows apart; the second commit repeats some of their keys
                if (commit == GROUP_AT || i % 97 == 0 || (i >= 1_023 && i <= 1_025) || i == GROUP - 1) {
                    final Object[] row = new Object[width];
                    row[0] = GROUP_TS;
                    row[1] = 1_000L + i;
                    row[2] = "g" + i;
                    row[3] = "g" + i;
                    row[4] = (double) i;
                    row[5] = i % 2 == 0;
                    row[7] = (commit == GROUP_AT ? "first-" : "replaced-") + i;
                    late(random, row);
                    rows.add(row);
                }
            }
        }
        final int n = 1 + random.nextInt(60);
        for (int i = 0; i < n; i++) {
            final Object[] row = new Object[width];
            final long day = commit == ADD_AT || random.nextInt(10) < 7 ? DAY1 : DAY2;
            row[0] = day + random.nextInt(40) * 1_000L;
            row[1] = random.nextInt(10) == 0 ? null : (long) random.nextInt(6);
            row[2] = TEXTS[random.nextInt(TEXTS.length)];
            row[3] = SYMBOLS[random.nextInt(SYMBOLS.length)];
            row[4] = DOUBLES[random.nextInt(DOUBLES.length)];
            row[5] = random.nextBoolean();
            row[6] = JSONS[random.nextInt(JSONS.length)];
            row[7] = commit == HUGE_AT && i == 0 ? "N".repeat(HUGE_CHARS) + 'é' : note(random);
            late(random, row);
            rows.add(row);
        }
        return rows;
    }

    // the late columns' values of row, when it has them
    private static void late(final Random random, final Object[] row) {
        if (row.length == COLUMNS.size()) {
            return;
        }
        final int c = COLUMNS.size();
        row[c] = random.nextInt(4) == 0 ? null : random.nextLong();
        row[c + 1] = random.nextInt(4) == 0 ? null : note(random);
        row[c + 2] = SYMBOLS[random.nextInt(SYMBOLS.length)];
        row[c + 3] = random.nextBoolean();
        row[c + 4] = DOUBLES[random.nextInt(DOUBLES.length)];
        row[c + 5] = JSONS[random.nextInt(JSONS.length)];
    }

    private static String note(final Random random) {
        if (random.nextInt(10) == 0) {
            return null;
        }
        final String chars = "abcé€";
        final StringBuilder note = new StringBuilder();
        final int length = random.nextInt(200);
        for (int i = 0; i < length; i++) {
            note.append(chars.charAt(random.nextInt(chars.length())));
        }
        return note.toString();
    }

    // a null value is left unset: NULL, as the model reads it
    private static void write(final TableWriter w, final List<CdbType> types, final Object[] row) {
        w.newRow((Long) row[0]);
        for (int c = 1; c < row.length; c++) {
            if (row[c] == null) {
                continue;
            }
            switch (types.get(c)) {
                case LONG, TIMESTAMP -> w.putLong(c, (Long) row[c]);
                case DOUBLE -> w.putDouble(c, (Double) row[c]);
                case BOOLEAN -> w.putBoolean(c, (Boolean) row[c]);
                case SYMBOL, VARCHAR, JSON -> w.putText(c, (String) row[c]);
            }
        }
        w.endRow();
    }

    private static List<String> read(final Store store) {
        try (final TableReader r = store.reader("m")) {
            return rows(r);
        }
    }

    /// The dedup keys of the table: none, a LONG and a VARCHAR, or a SYMBOL, a DOUBLE and a BOOLEAN, each with the
    /// timestamp.
    enum Keys {

        NONE(List.of()),
        LONG_AND_TEXT(List.of("ts", "k", "t")),
        SYMBOL_DOUBLE_AND_BOOLEAN(List.of("ts", "sym", "x", "flag"));

        final List<String> columns;

        Keys(final List<String> columns) {
            this.columns = columns;
        }
    }

    /// The table's rows by the commit's rules alone, kept as values; [#lines] formats them as [StoreRows] does.
    private static final class Model {

        final List<CdbType> types = new ArrayList<>();
        private final int[] keys;
        private List<Object[]> rows = new ArrayList<>();

        Model(final Keys keys) {
            for (final ColumnDef column : COLUMNS) {
                types.add(column.type());
            }
            this.keys = keys.columns.stream()
                    .mapToInt(name -> COLUMNS.stream().map(ColumnDef::name).toList().indexOf(name))
                    .toArray();
        }

        // a column added: the committed rows read NULL there, false for a BOOLEAN
        void add(final CdbType type) {
            types.add(type);
            final List<Object[]> widened = new ArrayList<>(rows.size());
            for (final Object[] row : rows) {
                final Object[] wide = Arrays.copyOf(row, types.size());
                wide[types.size() - 1] = type == CdbType.BOOLEAN ? Boolean.FALSE : null;
                widened.add(wide);
            }
            rows = widened;
        }

        void commit(final List<Object[]> batch) {
            final List<Object[]> all = new ArrayList<>(rows);
            for (final Object[] row : batch) {
                final Object[] copy = row.clone();
                for (int c = 0; c < copy.length; c++) {
                    if (copy[c] == null && types.get(c) == CdbType.BOOLEAN) {
                        copy[c] = Boolean.FALSE;
                    }
                }
                all.add(copy);
            }
            // List.sort is stable: the committed rows, then the new in written order, on each timestamp
            all.sort(Comparator.comparingLong(row -> (Long) row[0]));
            rows = keys.length == 0 ? all : dropReplaced(all);
        }

        // each row a later row of its timestamp and keys replaces, dropped
        private List<Object[]> dropReplaced(final List<Object[]> sorted) {
            final boolean[] dropped = new boolean[sorted.size()];
            int groupEnd = sorted.size();
            while (groupEnd > 0) {
                final long ts = (Long) sorted.get(groupEnd - 1)[0];
                final Set<List<Object>> seen = new HashSet<>();
                int i = groupEnd - 1;
                for (; i >= 0 && (Long) sorted.get(i)[0] == ts; i--) {
                    final Object[] row = sorted.get(i);
                    final Object[] key = new Object[keys.length];
                    for (int k = 0; k < keys.length; k++) {
                        key[k] = row[keys[k]];
                    }
                    dropped[i] = !seen.add(Arrays.asList(key));
                }
                groupEnd = i + 1;
            }
            final List<Object[]> kept = new ArrayList<>();
            for (int i = 0; i < sorted.size(); i++) {
                if (!dropped[i]) {
                    kept.add(sorted.get(i));
                }
            }
            return kept;
        }

        List<String> lines() {
            final List<String> lines = new ArrayList<>(rows.size());
            for (final Object[] row : rows) {
                final StringBuilder line = new StringBuilder();
                for (int c = 0; c < row.length; c++) {
                    if (c > 0) {
                        line.append('|');
                    }
                    final Object value = row[c];
                    if (value == null) {
                        line.append("null");
                    } else if (value instanceof final String text) {
                        line.append('"').append(text).append('"');
                    } else {
                        line.append(value);
                    }
                }
                lines.add(line.toString());
            }
            return lines;
        }
    }
}
