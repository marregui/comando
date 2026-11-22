package dev.marregui.comando.cdb.store;

import java.nio.file.Path;
import java.util.List;

import dev.marregui.comando.cdb.CdbType;

/// The process [BoundedRewriteTest] runs in a small heap: it fills one DAY partition with [#ROWS] rows of about a
/// kilobyte of VARCHAR each, in order, then commits rows that land among them, which rewrites the partition. It prints
/// its heap's limit as `max-heap=<bytes>`, then [#DONE] once the rewrite is committed, and exits 0; any throw exits
/// 1 after its stack trace.
final class BoundedRewriteChild {

    /// The line the child prints once the out-of-order commit returned and the store closed.
    static final String DONE = "rewrite-committed";
    static final String MAX_HEAP = "max-heap=";
    /// The start of the partition's day.
    static final long DAY1 = Retention.floor(StoreCrashChild.DAY1, PartitionBy.DAY);
    /// The rows of the in-order commits, ids `0..ROWS-1`, row `i` at `DAY1 + i * STEP_MICROS`.
    static final int ROWS = 320_000;
    // 320,000 rows a quarter second apart span 22.2 hours: one DAY partition
    static final long STEP_MICROS = 250_000L;
    /// New rows between two committed ones, ids from `ROWS`.
    static final int BETWEEN = 2_000;
    /// New rows at a committed row's timestamp, with an id of their own, ids from `ROWS + BETWEEN`.
    static final int TIES = 1_000;
    /// The committed row the out-of-order commit replaces: its timestamp and id, a new text.
    static final int REPLACED = 123_456;
    static final int COLUMNS = 7;
    static final int ID = 1;
    static final int V = 2;
    static final int J = 3;
    static final int S = 4;
    static final int D = 5;
    static final int B = 6;
    // rows per in-order commit: under directRows 0 each is a checkpoint that appends in place
    private static final int BATCH = 5_000;
    // the text of a row is about this long, so ROWS rows hold over 300 MB
    private static final int TEXT_CHARS = 1_000;
    // no other writer exists: a wait this long is a leak
    private static final long WAIT_MILLIS = 1_000L;

    private BoundedRewriteChild() {
    }

    /// Fills and rewrites the partition of the store at `args[0]`.
    static void main(final String[] args) {
        try {
            run(Path.of(args[0]));
        } catch (final Throwable t) {
            t.printStackTrace(System.out);
            System.out.flush();
            System.exit(1);
        }
        System.out.println(DONE);
        System.out.flush();
        System.exit(0);
    }

    /// The table's definition: dedup on the timestamp and the id.
    static TableDef def() {
        return new TableDef("m", List.of(
                new ColumnDef("ts", CdbType.TIMESTAMP),
                new ColumnDef("id", CdbType.LONG),
                new ColumnDef("v", CdbType.VARCHAR),
                new ColumnDef("j", CdbType.JSON),
                new ColumnDef("s", CdbType.SYMBOL),
                new ColumnDef("d", CdbType.DOUBLE),
                new ColumnDef("b", CdbType.BOOLEAN)), 0, PartitionBy.DAY, List.of("ts", "id"), 0L);
    }

    /// The timestamp of row `id`.
    static long timestamp(final long id) {
        if (id < ROWS) {
            return DAY1 + id * STEP_MICROS;
        }
        if (id < ROWS + BETWEEN) {
            // 157 is prime to ROWS: every new row lands in its own gap
            return DAY1 + (((id - ROWS) * 157L) % ROWS) * STEP_MICROS + STEP_MICROS / 2L;
        }
        // 311 is prime to ROWS: every tie lands on its own committed row
        return DAY1 + (((id - ROWS - BETWEEN) * 311L) % ROWS) * STEP_MICROS;
    }

    /// The VARCHAR value of row `id`: NULL for every 97th, the replacement's own text for [#REPLACED].
    static String text(final long id, final boolean replacement) {
        if (replacement) {
            return "replaced-" + id;
        }
        if (id % 97L == 0L) {
            return null;
        }
        final String head = "v" + id + ':';
        return head + String.valueOf((char) ('a' + id % 26L)).repeat(TEXT_CHARS - head.length());
    }

    static String json(final long id) {
        return "{\"id\":" + id + '}';
    }

    static String symbol(final long id) {
        return "s" + id % 7L;
    }

    static double number(final long id) {
        return id * 0.5;
    }

    static boolean flag(final long id) {
        return id % 3L == 0L;
    }

    private static void run(final Path root) {
        System.out.println(MAX_HEAP + Runtime.getRuntime().maxMemory());
        System.out.flush();
        try (final Store store = Store.open(root)) {
            // every commit a checkpoint on this thread: the rewrite runs here, in this heap, before the commit returns
            store.directRows = 0;
            store.create(def(), false);
            try (final TableWriter w = store.writer("m", WAIT_MILLIS)) {
                for (int id = 0; id < ROWS; id++) {
                    row(w, id, false);
                    if ((id + 1) % BATCH == 0) {
                        w.commit();
                    }
                }
                w.commit();
                // written in falling timestamp order, landing among the committed rows
                for (int id = ROWS + BETWEEN + TIES - 1; id >= ROWS; id--) {
                    row(w, id, false);
                }
                row(w, REPLACED, true);
                w.commit();
            }
        }
    }

    private static void row(final TableWriter w, final long id, final boolean replacement) {
        w.newRow(timestamp(id));
        w.putLong(ID, id);
        w.putText(V, text(id, replacement));
        w.putText(J, json(id));
        w.putText(S, symbol(id));
        w.putDouble(D, number(id));
        w.putBoolean(B, flag(id));
        w.endRow();
    }
}
