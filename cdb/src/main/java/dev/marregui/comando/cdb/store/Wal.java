package dev.marregui.comando.cdb.store;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32C;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;

/**
 * The write-ahead log of one table, {@code <table>/_wal}: an 8-byte header (magic, format version), then one frame per
 * commit since the last checkpoint, each forced before its commit is answered. A frame is its body's length and
 * CRC32C, then the body: the commit's transaction number, whether it truncated the table, the table's definition when
 * the commit changed it, and its pending rows by column, in the order they were written. Symbols are logged as text,
 * so a replay interns them again. Its methods hold its monitor: the table's writer appends while the store's applier
 * cuts the log after a checkpoint.
 * <p>
 * Every number is little-endian. Header: u32 magic {@code CWAL}, u32 version. Frame: u32 body length, u32 CRC32C of
 * the body. Body: u64 txn, u8 flags (bit 0 truncate, bit 1 definition follows), [u32 length and the UTF-8 of the
 * definition as {@code _meta} holds it], u32 rows, u16 columns, then each column's values: LONG and TIMESTAMP i64,
 * DOUBLE its raw bits as i64, BOOLEAN u8, SYMBOL and VARCHAR an i32 length (-1 for NULL) and the UTF-8 bytes.
 */
final class Wal {

    /** The log's file name in the table's folder. */
    static final String FILE = "_wal";
    private static final System.Logger LOG = System.getLogger(Wal.class.getName());
    /** The header's bytes: magic and version. */
    static final int HEADER = 8;
    /** The bytes before a frame's body: its length and CRC. */
    static final int FRAME = 8;

    // "CWAL" read as a little-endian int
    private static final int MAGIC = 0x4C415743;
    private static final int VERSION = 1;
    private static final int TRUNCATE = 1;
    private static final int DEF = 2;
    // txn, flags, rows, columns
    private static final int MIN_BODY = Long.BYTES + 1 + Integer.BYTES + Short.BYTES;
    // the format's u16 column count
    private static final int MAX_COLUMNS = 0xFFFF;

    private final Path file;
    // records and bytes: written by whoever holds the monitor (a commit's append, the applier's reset, a scan), read
    // by the applier and the status listing without it, so a read never waits for an append's force
    private volatile int records;
    private volatile long bytes;
    // the last record's transaction, meaningful while records > 0
    private long lastTxn;

    private Wal(final Path file, final long bytes) {
        this.file = file;
        this.bytes = bytes;
    }

    /**
     * Opens the log of the table folder {@code dir}, creating it with its header, forced with its folder, when it is
     * missing or shorter than its header (its creation cut by a crash). Refuses a header of an unknown magic or
     * version.
     */
    static Wal open(final Path dir) throws IOException {
        final Path file = dir.resolve(FILE);
        // shorter than its header only when a crash cut its creation: no commit was logged in it yet
        if (Files.exists(file) && Files.size(file) < HEADER) {
            StoreFiles.delete(file);
        }
        if (!Files.exists(file)) {
            final ByteBuffer header = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN);
            header.putInt(MAGIC).putInt(VERSION).flip();
            StoreFiles.writeNew(file, header, true);
            StoreFiles.forceFolder(dir);
            return new Wal(file, HEADER);
        }
        final long size = Files.size(file);
        if (size < HEADER) {
            throw new CdbException("refuse log of unknown format [file=" + file + ", bytes=" + size + ']', -1);
        }
        final long header = StoreFiles.readLong(file, 0L);
        final int magic = (int) header;
        final int version = (int) (header >>> Integer.SIZE);
        if (magic != MAGIC || version != VERSION) {
            throw new CdbException("refuse log of unknown format [file=" + file + ", magic=0x"
                    + Integer.toHexString(magic) + ", version=" + version + ']', -1);
        }
        return new Wal(file, size);
    }

    /**
     * The commits logged after transaction {@code afterTxn}, in order, decoded against {@code def}, the definition at
     * that transaction (a logged definition applies from its entry on). A torn tail (a short frame, a length past the
     * end, a CRC mismatch) ends the scan and is cut off the file, forced; a frame whose CRC holds but which does not
     * decode, or a transaction that does not follow the previous one, is refused as corruption, nothing cut. Called
     * once, on open, before any append. Frames at or below {@code afterTxn}, left by a checkpoint that crashed before
     * its reset, are skipped undecoded: their rows are in the checkpointed files, in columns that may be fewer than
     * {@code def}'s.
     */
    synchronized List<Entry> scan(final long afterTxn, final TableDef def) throws IOException {
        final List<Entry> entries = new ArrayList<>();
        TableDef current = def;
        long previous = 0L;
        int frames = 0;
        long end = HEADER;
        // read into the heap, not mapped: the scan cuts a torn tail, and a cut under a mapping ends the JVM
        final ByteBuffer log = StoreFiles.readAll(file).order(ByteOrder.LITTLE_ENDIAN);
        final long size = log.limit();
        while (size - end >= FRAME) {
            final long length = Integer.toUnsignedLong(log.getInt((int) end));
            final int crc = log.getInt((int) end + Integer.BYTES);
            if (length < MIN_BODY || length > size - end - FRAME) {
                break;
            }
            final ByteBuffer body = log.slice((int) end + FRAME, (int) length).order(ByteOrder.LITTLE_ENDIAN);
            final CRC32C check = new CRC32C();
            check.update(body.duplicate());
            if ((int) check.getValue() != crc) {
                break;
            }
            final long txn = body.getLong();
            if (frames > 0 && txn != previous + 1L) {
                throw new CdbException("refuse log record out of order [file=" + file + ", offset=" + end
                        + ", txn=" + txn + ", previous=" + previous + ']', -1);
            }
            if (txn > afterTxn) {
                // the first commit after the checkpoint is its txn + 1; a later one means commits were lost
                if (entries.isEmpty() && txn != afterTxn + 1L) {
                    throw new CdbException("refuse log that skips commits after the checkpoint [file=" + file
                            + ", offset=" + end + ", txn=" + txn + ", checkpointTxn=" + afterTxn + ']', -1);
                }
                final Entry entry = decode(body, txn, current, end);
                if (entry.def() != null) {
                    current = entry.def();
                }
                entries.add(entry);
            }
            previous = txn;
            lastTxn = txn;
            frames++;
            end += FRAME + length;
        }
        if (end < size) {
            StoreFiles.truncate(file, end);
        }
        records = frames;
        bytes = end;
        return entries;
    }

    /**
     * Appends the commit {@code txn} and forces the log: once this returns the commit is durable. {@code def} is the
     * definition after the commit when it changed, else null; {@code rows} holds {@code rows.size} rows in the columns
     * of the definition after the commit. On a failure the log is cut back to its length before the append, when it
     * can be, and the failure thrown; the caller then refuses the table's commits until it reopens.
     */
    synchronized void append(final long txn, final boolean truncate, final TableDef def, final RowBlock rows)
            throws IOException {
        if (records > 0 && txn != lastTxn + 1L) {
            // a direct commit came between: its checkpoint's _txn, durable, covers the records left when its reset
            // failed; cut, so a scan never meets a gap
            cut();
        }
        final ByteBuffer frame = encode(txn, truncate, def, rows);
        final int length = frame.remaining();
        final long before = bytes;
        try {
            StoreFiles.append(file, frame, true);
        } catch (final IOException e) {
            // an interrupt that closed the force's channel would close the cut's too: cleared for the cut, restored
            final boolean interrupted = Thread.interrupted();
            try {
                StoreFiles.truncate(file, before);
            } catch (final IOException cut) {
                e.addSuppressed(cut);
                LOG.log(System.Logger.Level.ERROR, "cannot cut log after failed append, commit in doubt [file=" + file
                        + ", txn=" + txn + ", bytes=" + before + ']', cut);
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            throw e;
        }
        records++;
        lastTxn = txn;
        bytes = before + length;
    }

    /**
     * Cuts the log back to its header when no record follows transaction {@code txn}, the one a checkpoint just named:
     * false, and the log kept, when a commit logged after it is still to be applied. A scan skips the records at or
     * before the checkpoint, so the next checkpoint with nothing after it cuts them.
     */
    synchronized boolean resetThrough(final long txn) throws IOException {
        if (records > 0 && lastTxn > txn) {
            return false;
        }
        cut();
        return true;
    }

    /**
     * Runs {@code drain} holding the log's monitor, so no commit appends meanwhile: the applier's last resort to empty
     * a log that a stream of commits never let it cut. {@code drain} may call {@link #resetThrough}: a Java monitor
     * is reentrant by the language (JLS 17.1), not by this class's convention.
     */
    synchronized void holdingAppends(final Runnable drain) {
        drain.run();
    }

    /** Cuts the log back to its header and forces it: every logged commit is in the checkpointed files. */
    synchronized void reset() throws IOException {
        cut();
    }

    // called with the monitor held
    private void cut() throws IOException {
        StoreFiles.truncate(file, HEADER);
        records = 0;
        bytes = HEADER;
    }

    /** The commits logged since the last reset. */
    int records() {
        return records;
    }

    /** The log's size in bytes, its header included. */
    long bytes() {
        return bytes;
    }

    private ByteBuffer encode(final long txn, final boolean truncate, final TableDef def, final RowBlock rows) {
        final int columns = rows.types.length;
        if (columns > MAX_COLUMNS) {
            throw new CdbException("refuse log record over column limit [file=" + file + ", txn=" + txn + ", columns="
                    + columns + ", limit=" + MAX_COLUMNS + ']', -1);
        }
        final int n = rows.size;
        final byte[] meta = def == null ? null : TableFiles.encodeMeta(def).getBytes(StandardCharsets.UTF_8);
        final byte[][][] texts = new byte[columns][][];
        long length = MIN_BODY + (meta == null ? 0L : Integer.BYTES + (long) meta.length);
        for (int c = 0; c < columns; c++) {
            final CdbType type = rows.types[c];
            if (type.isText()) {
                final byte[][] utf8 = new byte[n][];
                for (int r = 0; r < n; r++) {
                    final String value = rows.texts[c][r];
                    if (value != null) {
                        utf8[r] = value.getBytes(StandardCharsets.UTF_8);
                        length += utf8[r].length;
                    }
                }
                texts[c] = utf8;
                length += (long) Integer.BYTES * n;
            } else {
                length += (long) TableFiles.width(type) * n;
            }
        }
        if (length > Integer.MAX_VALUE - FRAME) {
            throw new CdbException("refuse log record over 2 GB [file=" + file + ", txn=" + txn + ", bytes=" + length
                    + ']', -1);
        }
        final ByteBuffer frame = ByteBuffer.allocate(FRAME + (int) length).order(ByteOrder.LITTLE_ENDIAN);
        frame.putInt((int) length).putInt(0);
        frame.putLong(txn);
        frame.put((byte) ((truncate ? TRUNCATE : 0) | (meta == null ? 0 : DEF)));
        if (meta != null) {
            frame.putInt(meta.length).put(meta);
        }
        frame.putInt(n).putShort((short) columns);
        for (int c = 0; c < columns; c++) {
            switch (rows.types[c]) {
                case LONG, TIMESTAMP -> {
                    final long[] values = rows.longs[c];
                    for (int r = 0; r < n; r++) {
                        frame.putLong(values[r]);
                    }
                }
                case DOUBLE -> {
                    final double[] values = rows.doubles[c];
                    for (int r = 0; r < n; r++) {
                        frame.putLong(Double.doubleToRawLongBits(values[r]));
                    }
                }
                case BOOLEAN -> {
                    final boolean[] values = rows.booleans[c];
                    for (int r = 0; r < n; r++) {
                        frame.put((byte) (values[r] ? 1 : 0));
                    }
                }
                case SYMBOL, VARCHAR -> {
                    for (final byte[] utf8 : texts[c]) {
                        if (utf8 == null) {
                            frame.putInt(-1);
                        } else {
                            frame.putInt(utf8.length).put(utf8);
                        }
                    }
                }
            }
        }
        final CRC32C crc = new CRC32C();
        crc.update(frame.array(), FRAME, (int) length);
        frame.putInt(Integer.BYTES, (int) crc.getValue());
        return frame.flip();
    }

    /** The body after its txn, read against {@code def}; every length is checked against the bytes left first. */
    private Entry decode(final ByteBuffer body, final long txn, final TableDef def, final long offset) {
        final int flags = body.get() & 0xFF;
        if ((flags & ~(TRUNCATE | DEF)) != 0) {
            throw undecodable("unknown flags", offset, txn, flags);
        }
        TableDef logged = null;
        if ((flags & DEF) != 0) {
            final int length = textLength(body, offset, txn);
            if (length < 0) {
                throw undecodable("definition of negative length", offset, txn, length);
            }
            final byte[] meta = new byte[length];
            body.get(meta);
            try {
                logged = TableFiles.decodeMeta(new String(meta, StandardCharsets.UTF_8), file.toString());
            } catch (final CdbException e) {
                throw new CdbException("refuse log record with unreadable definition [file=" + file + ", offset="
                        + offset + ", txn=" + txn + ']', e);
            }
        }
        final TableDef inForce = logged == null ? def : logged;
        if (body.remaining() < Integer.BYTES + Short.BYTES) {
            throw undecodable("row count past the record", offset, txn, body.remaining());
        }
        final long n = Integer.toUnsignedLong(body.getInt());
        final int columns = body.getShort() & 0xFFFF;
        if (columns != inForce.columns().size()) {
            throw new CdbException("refuse log record of another definition [file=" + file + ", offset=" + offset
                    + ", txn=" + txn + ", columns=" + columns + ", expected=" + inForce.columns().size() + ']', -1);
        }
        long least = 0L;
        for (final ColumnDef column : inForce.columns()) {
            final CdbType type = column.type();
            least += (type.isText() ? Integer.BYTES : TableFiles.width(type)) * n;
        }
        if (n > Integer.MAX_VALUE || least > body.remaining()) {
            throw undecodable("rows past the record", offset, txn, n);
        }
        final int size = (int) n;
        final RowBlock rows = new RowBlock(inForce.columns(), size);
        rows.size = size;
        for (int c = 0; c < columns; c++) {
            switch (rows.types[c]) {
                case LONG, TIMESTAMP -> {
                    final long[] values = rows.longs[c];
                    for (int r = 0; r < size; r++) {
                        values[r] = body.getLong();
                    }
                }
                case DOUBLE -> {
                    final double[] values = rows.doubles[c];
                    for (int r = 0; r < size; r++) {
                        values[r] = Double.longBitsToDouble(body.getLong());
                    }
                }
                case BOOLEAN -> {
                    final boolean[] values = rows.booleans[c];
                    for (int r = 0; r < size; r++) {
                        final byte b = body.get();
                        if (b != 0 && b != 1) {
                            throw undecodable("boolean other than 0 or 1", offset, txn, b);
                        }
                        values[r] = b == 1;
                    }
                }
                case SYMBOL, VARCHAR -> {
                    final String[] values = rows.texts[c];
                    for (int r = 0; r < size; r++) {
                        final int length = textLength(body, offset, txn);
                        if (length < -1) {
                            throw undecodable("text of negative length", offset, txn, length);
                        }
                        if (length >= 0) {
                            final byte[] utf8 = new byte[length];
                            body.get(utf8);
                            values[r] = new String(utf8, StandardCharsets.UTF_8);
                        }
                    }
                }
            }
        }
        if (body.hasRemaining()) {
            throw undecodable("bytes past the rows", offset, txn, body.remaining());
        }
        return new Entry(txn, (flags & TRUNCATE) != 0, logged, rows);
    }

    /** A text's i32 length, checked against the bytes left in {@code body}; -1 for NULL. */
    private int textLength(final ByteBuffer body, final long offset, final long txn) {
        if (body.remaining() < Integer.BYTES) {
            throw undecodable("text length past the record", offset, txn, body.remaining());
        }
        final int length = body.getInt();
        if (length > body.remaining()) {
            throw undecodable("text past the record", offset, txn, length);
        }
        return length;
    }

    private CdbException undecodable(final String problem, final long offset, final long txn, final long value) {
        return new CdbException("refuse log record that does not decode [file=" + file + ", offset=" + offset
                + ", txn=" + txn + ", problem=" + problem + ", value=" + value + ']', -1);
    }

    /**
     * One logged commit: {@code def} is the table's definition after it when the commit changed it, else null;
     * {@code rows} holds its pending rows, {@code rows.size} of them, in the columns of that definition.
     */
    record Entry(long txn, boolean truncate, TableDef def, RowBlock rows) {
    }
}
