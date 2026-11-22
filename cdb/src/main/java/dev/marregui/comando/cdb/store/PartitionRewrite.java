package dev.marregui.comando.cdb.store;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;

/**
 * Writes a partition's new version folder: its committed rows, when it has a version, merged with a commit's new rows
 * in designated-timestamp order. The order is planned first as runs of committed and of new rows, found by a search
 * of the committed timestamps: a new row goes after every committed row of its timestamp, and with dedup keys
 * replaces the committed row of its timestamp and keys, as a stable merge (the committed row first on a tie)
 * followed by {@link Dedup}'s drop would order them. A commit's rows are deduplicated among themselves first, and the
 * committed rows never repeat their keys. Without a designated timestamp the new rows follow the committed ones. Each
 * column is then streamed run by run from the committed version's mapped files and the new rows' block into the new
 * files, through one buffer per file being written that goes to the file each time it fills, so a rewrite holds a
 * bounded number of bytes whatever the partition's size: the plan grows with the new rows only. Files are written
 * unforced, as the commit's other files are.
 */
final class PartitionRewrite {

    // the most bytes gathered for one file before they are written to it: a sizing hint; a multiple of 8, so an index
    // entry never straddles two writes
    private static final int BUFFER_BYTES = 1 << 20;
    // committed rows read at a time: of one timestamp, their keys, to find those the new rows replace; of a SYMBOL
    // column, their values, interned again: a sizing hint
    private static final int CHUNK_ROWS = 1024;
    private static final VarHandle LONGS = MethodHandles.byteArrayViewVarHandle(long[].class,
            ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle INTS = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    private final List<SymbolTable> symbols;
    // the partition's committed version; null for a new partition
    private final Version old;
    private final long oldRows;
    private final RowBlock rows;
    private final int[] order;
    private final int[] keys;
    private final int ts;
    private final long[] one = new long[1];
    private final String[] texts = new String[CHUNK_ROWS];
    private final Sink data = new Sink();
    private final Sink index = new Sink();
    // the plan, as pairs: a >= 0 the committed rows [a, b), a < 0 the new rows order[~a, b); runs[0, runCount)
    private long[] runs = new long[16];
    private int runCount;
    private long mergedRows;
    // per dedup key, the committed rows' values of one chunk, of the array its type reads into
    private long[][] keyLongs;
    private double[][] keyDoubles;
    private boolean[][] keyBooleans;
    private String[][] keyTexts;

    private PartitionRewrite(final TableDef def, final List<SymbolTable> symbols, final Version old,
            final RowBlock rows, final int[] order, final int[] keys) {
        this.symbols = symbols;
        this.old = old;
        this.oldRows = old == null ? 0L : old.rowCount();
        this.rows = rows;
        this.order = order;
        this.keys = keys;
        this.ts = def.timestampIndex();
    }

    /**
     * Writes the version folder {@code dir} of the committed rows of {@code old} (null for a new partition) and the
     * rows {@code order} of {@code rows}, ordered by {@link Dedup#order} on the dedup key columns {@code keys};
     * returns its row count. A folder of that name is deleted first. On a throw the folder is left as far as it got:
     * the caller deletes it.
     */
    static int write(final Path dir, final TableDef def, final List<SymbolTable> symbols, final Version old,
            final RowBlock rows, final int[] order, final int[] keys) throws IOException {
        final long oldRows = old == null ? 0L : old.rowCount();
        if (oldRows > Integer.MAX_VALUE - order.length) {
            throw new CdbException("refuse partition over capacity [table=" + def.name() + ", rows="
                    + (oldRows + order.length) + ']', -1);
        }
        final PartitionRewrite rewrite = new PartitionRewrite(def, symbols, old, rows, order, keys);
        rewrite.plan();
        // a folder of this name is an orphan of a commit that crashed before naming it in _txn
        StoreFiles.deleteFolder(dir);
        StoreFiles.createFolder(dir);
        final List<ColumnDef> columns = def.columns();
        for (int c = 0; c < columns.size(); c++) {
            final Path dataFile = dir.resolve(columns.get(c).name() + ".d");
            if (rows.types[c].isVarchar()) {
                rewrite.text(c, dataFile, dir.resolve(columns.get(c).name() + ".i"));
            } else {
                rewrite.fixed(c, dataFile);
            }
        }
        return (int) rewrite.mergedRows;
    }

    private void plan() {
        final int k = order.length;
        if (ts < 0 || oldRows == 0L) {
            addOld(0L, oldRows);
            addNew(0, k);
            return;
        }
        long i = 0L;
        int j = 0;
        while (j < k) {
            final long t = rows.getLong(ts, order[j]);
            int to = j + 1;
            while (to < k && rows.getLong(ts, order[to]) == t) {
                to++;
            }
            if (keys.length == 0) {
                final long after = seek(i, t, false);
                addOld(i, after);
                i = after;
            } else {
                final long tied = seek(i, t, true);
                addOld(i, tied);
                final long after = seek(tied, t, false);
                keepUnreplaced(tied, after, j, to);
                i = after;
            }
            addNew(j, to);
            j = to;
        }
        addOld(i, oldRows);
    }

    /**
     * The first committed row from {@code from} whose timestamp is above {@code t}, or at it when {@code atOrAbove};
     * the row count when none is. A gallop from {@code from}, then a binary search: the new rows come in timestamp
     * order, so each search starts where the last ended.
     */
    private long seek(final long from, final long t, final boolean atOrAbove) {
        long lo = from;
        long hi = from;
        long step = 1L;
        while (hi < oldRows && below(hi, t, atOrAbove)) {
            lo = hi + 1L;
            hi += step;
            step <<= 1;
        }
        hi = Math.min(hi, oldRows);
        while (lo < hi) {
            final long mid = (lo + hi) >>> 1;
            if (below(mid, t, atOrAbove)) {
                lo = mid + 1L;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private boolean below(final long row, final long t, final boolean atOrAbove) {
        old.longs(ts, row, 1, one);
        return atOrAbove ? one[0] < t : one[0] <= t;
    }

    /**
     * Plans the committed rows {@code [from, to)}, all of the timestamp of the new rows {@code order[j, jTo)}, less
     * those whose keys one of the new rows has: the committed rows' keys are read a chunk at a time and looked up among
     * the new rows' by hash.
     */
    private void keepUnreplaced(final long from, final long to, final int j, final int jTo) {
        if (from == to) {
            return;
        }
        final int g = jTo - j;
        final long[] hashes = new long[g];
        for (int x = 0; x < g; x++) {
            hashes[x] = newHash(order[j + x]);
        }
        final int[] byHash = Dedup.stableOrder(hashes, g);
        final long[] sorted = new long[g];
        for (int x = 0; x < g; x++) {
            sorted[x] = hashes[byHash[x]];
        }
        long kept = from;
        for (long at = from; at < to; at += CHUNK_ROWS) {
            final int n = (int) Math.min(CHUNK_ROWS, to - at);
            readKeys(at, n);
            for (int x = 0; x < n; x++) {
                if (replaced(x, sorted, byHash, j)) {
                    addOld(kept, at + x);
                    kept = at + x + 1L;
                }
            }
        }
        addOld(kept, to);
    }

    // true when committed row x of the chunk read has the keys of one of the new rows order[j + byHash[...]]
    private boolean replaced(final int x, final long[] sorted, final int[] byHash, final int j) {
        final long h = oldHash(x);
        int at = Arrays.binarySearch(sorted, h);
        if (at < 0) {
            return false;
        }
        while (at > 0 && sorted[at - 1] == h) {
            at--;
        }
        for (; at < sorted.length && sorted[at] == h; at++) {
            if (sameKeys(x, order[j + byHash[at]])) {
                return true;
            }
        }
        return false;
    }

    // the keys of the committed rows [at, at + n) into the chunk arrays, made on the first read
    private void readKeys(final long at, final int n) {
        if (keyLongs == null) {
            keyLongs = new long[keys.length][];
            keyDoubles = new double[keys.length][];
            keyBooleans = new boolean[keys.length][];
            keyTexts = new String[keys.length][];
            for (int kk = 0; kk < keys.length; kk++) {
                switch (rows.types[keys[kk]]) {
                    case LONG, TIMESTAMP -> keyLongs[kk] = new long[CHUNK_ROWS];
                    case DOUBLE -> keyDoubles[kk] = new double[CHUNK_ROWS];
                    case BOOLEAN -> keyBooleans[kk] = new boolean[CHUNK_ROWS];
                    case SYMBOL, VARCHAR, JSON -> keyTexts[kk] = new String[CHUNK_ROWS];
                }
            }
        }
        for (int kk = 0; kk < keys.length; kk++) {
            final int c = keys[kk];
            switch (rows.types[c]) {
                case LONG, TIMESTAMP -> old.longs(c, at, n, keyLongs[kk]);
                case DOUBLE -> old.doubles(c, at, n, keyDoubles[kk]);
                case BOOLEAN -> old.booleans(c, at, n, keyBooleans[kk]);
                case SYMBOL, VARCHAR, JSON -> old.texts(c, at, n, keyTexts[kk]);
            }
        }
    }

    private long newHash(final int r) {
        long h = 1L;
        for (final int c : keys) {
            final long v = switch (rows.types[c]) {
                case LONG, TIMESTAMP -> rows.getLong(c, r);
                case DOUBLE -> Double.doubleToLongBits(rows.getDouble(c, r));
                case BOOLEAN -> rows.getBoolean(c, r) ? 1L : 0L;
                case SYMBOL, VARCHAR, JSON -> Objects.hashCode(rows.textAt(c, r));
            };
            h = h * 0x9E3779B97F4A7C15L + v;
        }
        return h ^ (h >>> 31);
    }

    private long oldHash(final int x) {
        long h = 1L;
        for (int kk = 0; kk < keys.length; kk++) {
            final long v = switch (rows.types[keys[kk]]) {
                case LONG, TIMESTAMP -> keyLongs[kk][x];
                case DOUBLE -> Double.doubleToLongBits(keyDoubles[kk][x]);
                case BOOLEAN -> keyBooleans[kk][x] ? 1L : 0L;
                case SYMBOL, VARCHAR, JSON -> Objects.hashCode(keyTexts[kk][x]);
            };
            h = h * 0x9E3779B97F4A7C15L + v;
        }
        return h ^ (h >>> 31);
    }

    private boolean sameKeys(final int x, final int r) {
        for (int kk = 0; kk < keys.length; kk++) {
            final int c = keys[kk];
            final boolean same = switch (rows.types[c]) {
                case LONG, TIMESTAMP -> keyLongs[kk][x] == rows.getLong(c, r);
                case DOUBLE -> Double.doubleToLongBits(keyDoubles[kk][x])
                        == Double.doubleToLongBits(rows.getDouble(c, r));
                case BOOLEAN -> keyBooleans[kk][x] == rows.getBoolean(c, r);
                case SYMBOL, VARCHAR, JSON -> Objects.equals(keyTexts[kk][x], rows.textAt(c, r));
            };
            if (!same) {
                return false;
            }
        }
        return true;
    }

    private void addOld(final long from, final long to) {
        if (from == to) {
            return;
        }
        mergedRows += to - from;
        if (runCount > 0 && runs[runCount - 2] >= 0L && runs[runCount - 1] == from) {
            runs[runCount - 1] = to;
        } else {
            addRun(from, to);
        }
    }

    private void addNew(final int from, final int to) {
        if (from == to) {
            return;
        }
        mergedRows += to - from;
        if (runCount > 0 && runs[runCount - 2] < 0L && runs[runCount - 1] == from) {
            runs[runCount - 1] = to;
        } else {
            addRun(~(long) from, to);
        }
    }

    private void addRun(final long a, final long b) {
        if (runCount == runs.length) {
            runs = Arrays.copyOf(runs, runs.length * 2);
        }
        runs[runCount++] = a;
        runs[runCount++] = b;
    }

    // whether the committed version has files of column c; a column added after it was written reads NULL there
    private boolean filed(final int c) {
        return old != null && c < old.fileColumns;
    }

    /** Writes fixed-width column {@code c} as {@code file}, run by run. */
    private void fixed(final int c, final Path file) throws IOException {
        final CdbType type = rows.types[c];
        final int width = TableFiles.width(type);
        data.open(file, mergedRows * width);
        for (int s = 0; s < runCount; s += 2) {
            final long a = runs[s];
            final long b = runs[s + 1];
            if (a < 0L) {
                newFixed(c, type, (int) ~a, (int) b);
            } else if (!filed(c)) {
                nullFixed(type, b - a);
            } else if (type == CdbType.SYMBOL) {
                oldSymbols(c, a, b);
            } else {
                oldBytes(c, type, a * width, b * width);
            }
        }
        data.finish();
    }

    private void newFixed(final int c, final CdbType type, final int from, final int to) throws IOException {
        for (int pos = from; pos < to; pos++) {
            final int r = order[pos];
            switch (type) {
                case LONG, TIMESTAMP -> data.putLong(rows.getLong(c, r));
                case DOUBLE -> data.putLong(Double.doubleToRawLongBits(rows.getDouble(c, r)));
                case BOOLEAN -> data.putByte(rows.getBoolean(c, r) ? (byte) 1 : (byte) 0);
                case SYMBOL -> data.putInt(symbols.get(c).intern(rows.textAt(c, r)));
                case VARCHAR, JSON -> throw new IllegalArgumentException("refuse text as fixed width [column=" + c
                        + ']');
            }
        }
    }

    // count NULLs of a column the committed version has no file of, as a commit's block holds them
    private void nullFixed(final CdbType type, final long count) throws IOException {
        for (long i = 0L; i < count; i++) {
            switch (type) {
                case LONG, TIMESTAMP -> data.putLong(Long.MIN_VALUE);
                case DOUBLE -> data.putLong(Double.doubleToRawLongBits(Double.NaN));
                case BOOLEAN -> data.putByte((byte) 0);
                case SYMBOL -> data.putInt(-1);
                case VARCHAR, JSON -> throw new IllegalArgumentException("refuse text as fixed width [type=" + type
                        + ']');
            }
        }
    }

    // the committed SYMBOL rows [from, to) of column c, each value interned again in the table's dictionary
    private void oldSymbols(final int c, final long from, final long to) throws IOException {
        final SymbolTable table = symbols.get(c);
        for (long at = from; at < to; at += CHUNK_ROWS) {
            final int n = (int) Math.min(CHUNK_ROWS, to - at);
            old.texts(c, at, n, texts);
            for (int x = 0; x < n; x++) {
                data.putInt(table.intern(texts[x]));
            }
        }
        Arrays.fill(texts, null);
    }

    // the committed bytes [from, to) of column c's data file, as they lie there
    private void oldBytes(final int c, final CdbType type, final long from, final long to) throws IOException {
        long at = from;
        while (at < to) {
            final int n = (int) Math.min(to - at, data.room());
            old.copyData(c, type, at, data.bytes(), data.at(), n);
            data.advance(n);
            at += n;
        }
    }

    /**
     * Writes text column {@code c} as {@code dataFile} and {@code indexFile}, run by run; refused before a byte is
     * written when its text passes 2 GB, the most an offset of its readers can reach.
     */
    private void text(final int c, final Path dataFile, final Path indexFile) throws IOException {
        long total = 0L;
        for (int s = 0; s < runCount; s += 2) {
            final long a = runs[s];
            final long b = runs[s + 1];
            if (a < 0L) {
                for (int pos = (int) ~a; pos < b; pos++) {
                    total += Math.max(0, rows.textLength(c, order[pos]));
                }
            } else if (filed(c)) {
                total += old.textEnd(c, b - 1L) - old.textEnd(c, a - 1L);
            }
        }
        if (total > Integer.MAX_VALUE) {
            throw new CdbException("refuse text column over 2 GB in one partition [file=" + dataFile + ", bytes="
                    + total + ']', -1);
        }
        index.open(indexFile, mergedRows * Long.BYTES);
        data.open(dataFile, total);
        long out = 0L;
        for (int s = 0; s < runCount; s += 2) {
            final long a = runs[s];
            final long b = runs[s + 1];
            if (a < 0L) {
                out = newText(c, (int) ~a, (int) b, out);
            } else if (!filed(c)) {
                for (long i = a; i < b; i++) {
                    index.putLong(~out);
                }
            } else {
                out = oldText(c, a, b, out);
            }
        }
        index.finish();
        data.finish();
    }

    // the new rows order[from, to) of text column c, the first starting at out; returns where the next starts
    private long newText(final int c, final int from, final int to, final long out) throws IOException {
        long end = out;
        for (int pos = from; pos < to; pos++) {
            final int r = order[pos];
            final int length = rows.textLength(c, r);
            if (length < 0) {
                index.putLong(~end);
                continue;
            }
            end += length;
            index.putLong(end);
            int done = 0;
            while (done < length) {
                final int n = Math.min(length - done, data.room());
                rows.copyText(c, r, done, data.bytes(), data.at(), n);
                data.advance(n);
                done += n;
            }
        }
        return end;
    }

    // the committed rows [from, to) of text column c, the first starting at out; returns where the next starts
    private long oldText(final int c, final long from, final long to, final long out) throws IOException {
        final long start = old.textEnd(c, from - 1L);
        long end = start;
        long base = out;
        long at = from;
        while (at < to) {
            final int n = (int) Math.min(to - at, index.room() / Long.BYTES);
            final long pieceEnd = old.copyEnds(c, at, n, index.bytes(), index.at(), base);
            index.advance(n * Long.BYTES);
            base += pieceEnd - end;
            end = pieceEnd;
            at += n;
        }
        final CdbType type = rows.types[c];
        long done = start;
        while (done < end) {
            final int n = (int) Math.min(end - done, data.room());
            old.copyData(c, type, done, data.bytes(), data.at(), n);
            data.advance(n);
            done += n;
        }
        return out + (end - start);
    }

    /**
     * One file being written: bytes gather in a buffer that is written to the file, unforced, each time it fills,
     * the first time as a new file. The buffer is kept for the next file, grown to what a file needs up to
     * {@link #BUFFER_BYTES}; its length stays a multiple of 8.
     */
    private static final class Sink {

        private byte[] buffer = new byte[0];
        private int used;
        private Path file;
        private boolean created;

        // starts the file, of about bytes bytes
        void open(final Path file, final long bytes) {
            final int want = (int) Math.min(BUFFER_BYTES, Math.max(Long.BYTES, (bytes + 7L) & ~7L));
            if (buffer.length < want) {
                buffer = new byte[want];
            }
            this.file = file;
            this.used = 0;
            this.created = false;
        }

        // the room left in the buffer, written to the file first when it is full: never 0
        int room() throws IOException {
            if (used == buffer.length) {
                flush();
            }
            return buffer.length - used;
        }

        byte[] bytes() {
            return buffer;
        }

        int at() {
            return used;
        }

        // n bytes were copied into the buffer at at()
        void advance(final int n) {
            used += n;
        }

        void putLong(final long value) throws IOException {
            if (buffer.length - used < Long.BYTES) {
                flush();
            }
            LONGS.set(buffer, used, value);
            used += Long.BYTES;
        }

        void putInt(final int value) throws IOException {
            if (buffer.length - used < Integer.BYTES) {
                flush();
            }
            INTS.set(buffer, used, value);
            used += Integer.BYTES;
        }

        void putByte(final byte value) throws IOException {
            if (used == buffer.length) {
                flush();
            }
            buffer[used++] = value;
        }

        // writes what the buffer holds; the file is created even when it holds nothing
        void finish() throws IOException {
            flush();
            file = null;
        }

        private void flush() throws IOException {
            final ByteBuffer pending = ByteBuffer.wrap(buffer, 0, used);
            if (created) {
                StoreFiles.append(file, pending, false);
            } else {
                StoreFiles.writeNew(file, pending, false);
                created = true;
            }
            used = 0;
        }
    }
}
