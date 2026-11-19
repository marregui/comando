package dev.marregui.comando.cdb.store;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32C;

import org.jetbrains.annotations.TestOnly;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;

/**
 * The write-ahead log of one table, {@code <table>/_wal}: a header (magic, format version, salt), then one frame per
 * commit since the last checkpoint. A frame is its body's length and CRC32C, then the body: the commit's transaction
 * number, whether it truncated the table, the table's definition when the commit changed it, and its pending rows by
 * column, in the order they were written. Symbols are logged as text, so a replay interns them again. A commit is
 * durable once its frame is forced: by {@link #append} itself, or by a later {@link #force} that covers the sequence
 * number {@link #write} gave it. The writes, the cuts and the scan hold its monitor: the table's writer appends while
 * the store's applier cuts the log after a checkpoint; {@link #force} holds it only to read the last sequence number,
 * and a durable waiter never takes it.
 * <p>
 * Every number is little-endian. Header: u32 magic {@code CWAL}, u32 version, u64 salt. Frame: u32 body length, u32
 * CRC32C of the salt's 8 bytes then the body. Body: u64 txn, u8 flags (bit 0 truncate, bit 1 definition follows), [u32
 * length and the UTF-8 of the definition as {@code _meta} holds it], u32 rows, u16 columns, then each column's
 * values: LONG and TIMESTAMP i64, DOUBLE its raw bits as i64, BOOLEAN u8, SYMBOL and VARCHAR an i32 length (-1 for
 * NULL) and the UTF-8 bytes.
 * <p>
 * The salt is drawn anew when the log is created and at every cut, and forced with the header before a frame follows
 * it: after a power cut, bytes of an earlier generation that a new frame did not overwrite fail their CRC, so the scan
 * cuts there rather than reading them as a commit. Format 1, the previous one, has an 8-byte header (magic, version)
 * and CRCs of the body alone; such a log is read and appended to as it is, and written as format 2 at its next cut.
 */
final class Wal {

    /** The log's file name in the table's folder. */
    static final String FILE = "_wal";
    /** The header's bytes in format 2: magic, version and salt. */
    static final int HEADER = 16;
    /** The bytes before a frame's body: its length and CRC. */
    static final int FRAME = 8;
    // the bytes a replay reads at a time, so a log larger than the heap replays: a frame up to it is checked in place,
    // a larger one has its CRC computed through it, and the buffer grows past it only to decode a frame whose CRC
    // held, a commit its writer once held whole in the heap. A sizing hint: it bounds memory, not what replays
    static final int WINDOW = 1 << 20;

    private static final System.Logger LOG = System.getLogger(Wal.class.getName());
    // "CWAL" read as a little-endian int
    private static final int MAGIC = 0x4C415743;
    private static final int VERSION = 2;
    // the format before the salt: an 8-byte header, magic and version
    private static final int UNSALTED = 1;
    private static final int UNSALTED_HEADER = 8;
    // a salt only tells one generation of the log from the next, but two stores' generations must not repeat each
    // other's, so it comes from the system's entropy rather than a seeded generator
    private static final SecureRandom SALTS = new SecureRandom();
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
    // the last record's transaction and its offset, meaningful while records > 0
    private long lastTxn;
    // the sequence number of the last record written, never reused (a retract reuses a txn): written under this
    // after its frame is in the file, read by a force without the monitor, so a force never waits for a drain that
    // holds it, and covers every record its read counts
    private volatile long writtenSeq;
    private long lastStart;
    // guarded by this: the header's bytes in the file's format, and the salt its frames' CRCs start from, its 8 bytes
    // as the header holds them (null in format 1)
    private int header;
    private byte[] salt;
    // guarded by this: the largest the last scan's read buffer grew
    private int scanBufferBytes;
    // the durable waiters' monitor, taken inside this one (a write, a cut) or alone (a force, a waiter), never the
    // other way round, so a waiter never queues behind a drain or a force-per-commit append holding the log's monitor
    private final Object forced = new Object();
    // written under forced by a force that held, a cut and an append; read by durable waiters and the forcer, the
    // fast path without the lock
    private volatile long forcedSeq;
    // guarded by forced: the first failed force (a cut's included), and the sequence numbers it left in doubt; null
    // while none failed
    private Throwable failure;
    private long failedFromSeq;
    private long failedToSeq;
    // guards the force channel's swaps: a reopen after an interrupt closed it, and the close; taken alone
    private final Object channelLock = new Object();
    // the channel every force of the log goes through, opened with the log: written under channelLock by a reopen and
    // by close, read without it by every force (the timed forcer's, a cut's), so a force never waits for a swap
    private volatile FileChannel channel;
    // written under channelLock by close, before the channel is detached; read by a force and a cut without it
    private volatile boolean closed;

    private Wal(final Path file, final long bytes, final byte[] salt, final FileChannel channel) {
        this.file = file;
        this.bytes = bytes;
        this.header = salt == null ? UNSALTED_HEADER : HEADER;
        this.salt = salt;
        this.channel = channel;
    }

    /**
     * Opens the log of the table folder {@code dir}, creating it with its header and a new salt, forced with its
     * folder, when it is missing or shorter than its header (its creation cut by a crash). Reads format 2 and format
     * 1; refuses a header of an unknown magic or version. The log holds a channel on its file for its forces, opened
     * last: its owner {@link #close closes} it.
     */
    static Wal open(final Path dir) throws IOException {
        final Path file = dir.resolve(FILE);
        if (Files.exists(file) && isCutHeader(file)) {
            StoreFiles.delete(file);
        }
        if (!Files.exists(file)) {
            final byte[] salt = newSalt(null);
            StoreFiles.writeNew(file, headerOf(salt), true);
            StoreFiles.forceFolder(dir);
            return new Wal(file, HEADER, salt, StoreFiles.openForForce(file));
        }
        final long size = Files.size(file);
        final long head = StoreFiles.readLong(file, 0L);
        final int magic = (int) head;
        final int version = (int) (head >>> Integer.SIZE);
        if (magic != MAGIC || (version != VERSION && version != UNSALTED)) {
            throw new CdbException("refuse log of unknown format [file=" + file + ", magic=0x"
                    + Integer.toHexString(magic) + ", version=" + version + ']', -1);
        }
        final byte[] salt = version == UNSALTED ? null : ByteBuffer.allocate(Long.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN).putLong(StoreFiles.readLong(file, UNSALTED_HEADER)).array();
        return new Wal(file, size, salt, StoreFiles.openForForce(file));
    }

    /**
     * Whether {@code file} is shorter than its header: only when a crash cut its creation, or the rewrite of a format
     * 1 header at a cut that had already emptied the log, so no commit is left in it to replay.
     */
    private static boolean isCutHeader(final Path file) throws IOException {
        final long size = Files.size(file);
        if (size < UNSALTED_HEADER) {
            return true;
        }
        return size < HEADER && StoreFiles.readLong(file, 0L) == ((long) VERSION << Integer.SIZE | MAGIC);
    }

    /** A format 2 header holding {@code salt}, ready to write. */
    private static ByteBuffer headerOf(final byte[] salt) {
        return ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN).putInt(MAGIC).putInt(VERSION).put(salt)
                .flip();
    }

    /** A random salt other than {@code previous}, the salt of the generation it follows (null for none). */
    private static byte[] newSalt(final byte[] previous) {
        final byte[] salt = new byte[Long.BYTES];
        do {
            SALTS.nextBytes(salt);
        } while (Arrays.equals(salt, previous));
        return salt;
    }

    /** The CRC32C a frame of this log carries for {@code body}: seeded with the salt in format 2. */
    private int checksum(final ByteBuffer body) {
        final CRC32C crc = new CRC32C();
        if (salt != null) {
            crc.update(salt);
        }
        crc.update(body.duplicate());
        return (int) crc.getValue();
    }

    /**
     * The commits logged after transaction {@code afterTxn}, in order, decoded against {@code def}, the definition at
     * that transaction (a logged definition applies from its entry on). A torn tail (a short frame, a length past the
     * end, a CRC mismatch) ends the scan and is cut off the file, forced; a frame whose CRC holds but which does not
     * decode, or a transaction that does not follow the previous one, is refused as corruption, nothing cut. Called
     * once, on open, before any append. Frames at or below {@code afterTxn}, left by a checkpoint that crashed before
     * its reset, are skipped undecoded: their rows are in the checkpointed files, in columns that may be fewer than
     * {@code def}'s. The entries' row blocks are the caller's, to close once their rows are applied; on a throw the
     * scan closes those it decoded. The log is read through a window of {@link #WINDOW} bytes, never whole.
     */
    synchronized List<Entry> scan(final long afterTxn, final TableDef def) throws IOException {
        return scanThrough(afterTxn, def, WINDOW);
    }

    /** {@link #scan} through a window of {@code windowBytes}, so a test reaches frames larger than its window. */
    @TestOnly
    synchronized List<Entry> scan(final long afterTxn, final TableDef def, final int windowBytes) throws IOException {
        return scanThrough(afterTxn, def, windowBytes);
    }

    /** The largest the last scan's read buffer grew: its window, or the largest frame it decoded when larger. */
    @TestOnly
    synchronized int scanBufferBytes() {
        return scanBufferBytes;
    }

    // called with the monitor held: scan's body around the entries it closes on a throw
    private List<Entry> scanThrough(final long afterTxn, final TableDef def, final int windowBytes)
            throws IOException {
        final List<Entry> entries = new ArrayList<>();
        try {
            return scanInto(afterTxn, def, windowBytes, entries);
        } catch (final Throwable t) {
            for (final Entry entry : entries) {
                entry.rows().close();
            }
            throw t;
        }
    }

    // called with the monitor held: each decoded entry added to entries as it is read
    private List<Entry> scanInto(final long afterTxn, final TableDef def, final int windowBytes,
                                 final List<Entry> entries) throws IOException {
        TableDef current = def;
        long previous = 0L;
        int frames = 0;
        long end = header;
        final long size;
        // read into the heap, not mapped: the scan cuts a torn tail, and a cut under a mapping ends the JVM
        try (final Window log = new Window(file, windowBytes)) {
            size = log.size;
            try {
                while (size - end >= FRAME) {
                    final long length = Integer.toUnsignedLong(log.intAt(end));
                    final int crc = log.intAt(end + Integer.BYTES);
                    if (length < MIN_BODY || length > size - end - FRAME) {
                        break;
                    }
                    if (checksum(log, end + FRAME, length) != crc) {
                        break;
                    }
                    final long txn = log.longAt(end + FRAME);
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
                        if (length > Integer.MAX_VALUE - FRAME) {
                            throw undecodable("record over 2 GB", end, txn, length);
                        }
                        final ByteBuffer body = log.slice(end + FRAME, (int) length).position(Long.BYTES);
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
            } finally {
                scanBufferBytes = log.capacity();
            }
        }
        if (end < size) {
            StoreFiles.truncate(file, end);
        }
        records = frames;
        bytes = end;
        return entries;
    }

    /**
     * The CRC32C of the {@code length} bytes of {@code log} from {@code at}, as {@link #checksum(ByteBuffer)} gives
     * it: checked in place when they fit the window, else streamed through it, so a length a torn tail made up never
     * grows the buffer.
     */
    private int checksum(final Window log, final long at, final long length) throws IOException {
        if (length <= log.capacity()) {
            return checksum(log.slice(at, (int) length));
        }
        final CRC32C crc = new CRC32C();
        if (salt != null) {
            crc.update(salt);
        }
        log.update(crc, at, length);
        return (int) crc.getValue();
    }

    /**
     * Writes the commit {@code txn} as {@link #append} does, without the force, and returns its record's sequence
     * number, one past the last record's and never reused (a retract gives its txn back, not its sequence number): the
     * commit is durable once {@link #forcedSeq()} reaches that number. On a failure the log is cut back as
     * {@link #append} does.
     */
    synchronized long write(final long txn, final boolean truncate, final TableDef def, final RowBlock rows)
            throws IOException {
        frame(txn, truncate, def, rows, false);
        writtenSeq++;
        return writtenSeq;
    }

    /**
     * Forces every record written so far; the force itself runs outside the log's monitor, so writes go on during
     * it. A failure is thrown and {@link #forcedSeq()} does not move: the records since the last force are in doubt,
     * every later {@link #awaitForced} past them throws, and every later force throws without forcing, since a force
     * that holds after a failed one does not prove the pages the failure dropped. An interrupt is cleared for the
     * force, which it would fail by closing its channel, and restored after. After {@link #close} a force with records
     * to cover throws without recording a failure: the records stay unforced, not in doubt.
     */
    void force() throws IOException {
        final long target = writtenSeq;
        synchronized (forced) {
            if (target <= forcedSeq) {
                return;
            }
            if (failure != null) {
                throw new IOException("refuse force of log after failed force [file=" + file + ", forcedSeq="
                        + forcedSeq + ", writtenSeq=" + target + ']', failure);
            }
        }
        if (closed) {
            throw closedLog("force");
        }
        final boolean interrupted = Thread.interrupted();
        try {
            StoreFiles.force(channel(), file);
        } catch (final Throwable t) {
            // an Error is a failed force too: whether the pages reached the disk is unknown after either
            failed(t, target);
            throw t;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        forcedThrough(target, false);
    }

    /**
     * The channel the forces go through, opened again when an interrupt closed it during a force, a force that failed
     * and was recorded: a cut still forces the log after it, as it did on a channel of its own. Throws once closed.
     */
    private FileChannel channel() throws IOException {
        final FileChannel open = channel;
        if (open != null && open.isOpen()) {
            return open;
        }
        synchronized (channelLock) {
            if (closed) {
                throw closedLog("force");
            }
            final FileChannel current = channel;
            if (current != null && current.isOpen()) {
                return current;
            }
            final FileChannel reopened = StoreFiles.openForForce(file);
            channel = reopened;
            return reopened;
        }
    }

    /**
     * Closes the log's force channel; idempotent. Its owner calls it once no force runs or follows: the timed forcer
     * stopped and the table's writer closed. A force after it throws, a force in flight fails as a failed force.
     */
    void close() {
        final FileChannel open;
        synchronized (channelLock) {
            if (closed) {
                return;
            }
            closed = true;
            open = channel;
            channel = null;
        }
        if (open != null) {
            try {
                open.close();
            } catch (final IOException e) {
                LOG.log(System.Logger.Level.WARNING, "cannot close log channel [file=" + file + ']', e);
            }
        }
    }

    private IOException closedLog(final String operation) {
        return new IOException("refuse " + operation + " of closed log [file=" + file + ", forcedSeq=" + forcedSeq
                + ", writtenSeq=" + writtenSeq + ']');
    }

    // records the first failed force, the records after forcedSeq up to target in doubt, and wakes the durable waiters
    // past forcedSeq, which fail now rather than at their timeout
    private void failed(final Throwable t, final long target) {
        synchronized (forced) {
            if (failure == null) {
                failure = t;
                failedFromSeq = forcedSeq + 1L;
                failedToSeq = target;
            }
            forced.notifyAll();
        }
    }

    /**
     * Waits at most {@code timeoutNanos} until a force covers record {@code seq}; true once one has, false when the
     * time ran out first. Throws the "commit in doubt" failure once a force of this log failed with {@code seq} not
     * covered, and {@link InterruptedException} when the thread is interrupted while it waits.
     */
    boolean awaitForced(final long seq, final long timeoutNanos) throws InterruptedException {
        if (forcedSeq >= seq) {
            return true;
        }
        final long startNanos = System.nanoTime();
        synchronized (forced) {
            while (forcedSeq < seq) {
                if (failure != null) {
                    throw new CdbException("refuse durable commit, commit in doubt after failed log force [table="
                            + file.getParent().getFileName() + ", seq=" + seq + ", fromSeq=" + failedFromSeq
                            + ", toSeq=" + failedToSeq + ']', failure);
                }
                final long leftNanos = timeoutNanos - (System.nanoTime() - startNanos);
                if (leftNanos <= 0L) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(forced, leftNanos);
            }
            return true;
        }
    }

    /** The sequence number of the last record a force covered. */
    long forcedSeq() {
        return forcedSeq;
    }

    /**
     * Appends the commit {@code txn} and forces the log: once this returns the commit is durable. {@code def} is the
     * definition after the commit when it changed, else null; {@code rows} holds {@code rows.size} rows in the columns
     * of the definition after the commit. On a failure the log is cut back to its length before the append, when it
     * can be, and the failure thrown; the caller then refuses the table's commits until it reopens. Refused once a
     * force of the log failed, a cut's included: a force that holds after it does not prove the header.
     */
    synchronized void append(final long txn, final boolean truncate, final TableDef def, final RowBlock rows)
            throws IOException {
        synchronized (forced) {
            if (failure != null) {
                throw new IOException("refuse append to log after failed force [file=" + file + ", txn=" + txn + ']',
                        failure);
            }
        }
        frame(txn, truncate, def, rows, true);
        writtenSeq++;
        forcedThrough(writtenSeq, false);
    }

    // called with the monitor held: appends the commit's frame, forced when force, cut back on a failure
    private void frame(final long txn, final boolean truncate, final TableDef def, final RowBlock rows,
                       final boolean force) throws IOException {
        if (records > 0 && txn != lastTxn + 1L) {
            // a direct commit came between: its checkpoint's _txn, durable, covers the records left when its reset
            // failed; cut, so a scan never meets a gap
            cut();
        }
        final ByteBuffer frame = encode(txn, truncate, def, rows);
        final int length = frame.remaining();
        final long before = bytes;
        try {
            StoreFiles.append(file, frame, force);
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
        lastStart = before;
        bytes = before + length;
    }

    /**
     * Cuts the last record, commit {@code txn}, back out of the log and forces the cut: a commit refused after it was
     * logged, so no replay makes visible a commit its caller saw fail. False, and the log kept, when the last record is
     * not {@code txn}. The record's sequence number is not given back.
     */
    synchronized boolean retract(final long txn) throws IOException {
        if (records == 0 || lastTxn != txn) {
            return false;
        }
        StoreFiles.truncate(file, lastStart);
        records--;
        lastTxn = txn - 1L;
        bytes = lastStart;
        return true;
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

    /**
     * Called with the monitor held: cuts the log back to its header, forced, then writes a format 2 header with a new
     * salt over it, forced before any frame follows. In that order every failure leaves the file and this object
     * agreeing: a failed cut changes nothing, a failed header write leaves the cut log in its old generation, and a
     * failed force after the write leaves the new header in the page cache, where the next frames follow it: the disk
     * may hold the old salt, so that failure is the log's failed force, and every later force and append is refused.
     * Once the truncate holds every record written is covered: the records left were in the checkpointed files.
     * Refused, nothing cut, once the log is closed.
     */
    private void cut() throws IOException {
        if (closed) {
            throw closedLog("cut");
        }
        StoreFiles.truncate(file, header);
        records = 0;
        bytes = header;
        final byte[] next = newSalt(salt);
        StoreFiles.writeAt(file, 0L, headerOf(next), false);
        header = HEADER;
        salt = next;
        bytes = HEADER;
        // the truncate was forced: the records left are the checkpoint's, durable whatever the header's force does
        forcedThrough(writtenSeq, true);
        try {
            StoreFiles.force(channel(), file);
        } catch (final Throwable t) {
            failed(t, writtenSeq);
            throw t;
        }
    }

    /**
     * Moves {@link #forcedSeq} up to {@code seq} and wakes the durable waiters; after a failed force only for a cut
     * ({@code byCut}), whose records the checkpoint made durable, not the log's force.
     */
    private void forcedThrough(final long seq, final boolean byCut) {
        synchronized (forced) {
            if ((byCut || failure == null) && seq > forcedSeq) {
                forcedSeq = seq;
                forced.notifyAll();
            }
        }
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
                    final String value = rows.textAt(c, r);
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
                    for (int r = 0; r < n; r++) {
                        frame.putLong(rows.getLong(c, r));
                    }
                }
                case DOUBLE -> {
                    for (int r = 0; r < n; r++) {
                        frame.putLong(Double.doubleToRawLongBits(rows.getDouble(c, r)));
                    }
                }
                case BOOLEAN -> {
                    for (int r = 0; r < n; r++) {
                        frame.put((byte) (rows.getBoolean(c, r) ? 1 : 0));
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
        frame.putInt(Integer.BYTES, checksum(frame.slice(FRAME, (int) length)));
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
        try {
            rows.size = size;
            decodeRows(body, rows, offset, txn);
        } catch (final Throwable t) {
            rows.close();
            throw t;
        }
        return new Entry(txn, (flags & TRUNCATE) != 0, logged, rows);
    }

    /** Fills {@code rows}, {@code rows.size} rows, from {@code body}, then checks no byte is left past them. */
    private void decodeRows(final ByteBuffer body, final RowBlock rows, final long offset, final long txn) {
        final int columns = rows.types.length;
        final int size = rows.size;
        for (int c = 0; c < columns; c++) {
            switch (rows.types[c]) {
                case LONG, TIMESTAMP -> {
                    for (int r = 0; r < size; r++) {
                        rows.setLong(c, r, body.getLong());
                    }
                }
                case DOUBLE -> {
                    for (int r = 0; r < size; r++) {
                        rows.setDouble(c, r, Double.longBitsToDouble(body.getLong()));
                    }
                }
                case BOOLEAN -> {
                    for (int r = 0; r < size; r++) {
                        final byte b = body.get();
                        if (b != 0 && b != 1) {
                            throw undecodable("boolean other than 0 or 1", offset, txn, b);
                        }
                        rows.setBoolean(c, r, b == 1);
                    }
                }
                case SYMBOL, VARCHAR -> {
                    for (int r = 0; r < size; r++) {
                        final int length = textLength(body, offset, txn);
                        if (length < -1) {
                            throw undecodable("text of negative length", offset, txn, length);
                        }
                        if (length >= 0) {
                            final byte[] utf8 = new byte[length];
                            body.get(utf8);
                            rows.setText(c, r, new String(utf8, StandardCharsets.UTF_8));
                        }
                    }
                }
            }
        }
        if (body.hasRemaining()) {
            throw undecodable("bytes past the rows", offset, txn, body.remaining());
        }
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
     * {@code rows} holds its pending rows, {@code rows.size} of them, in the columns of that definition; whoever holds
     * the entry closes {@code rows} once they are applied.
     */
    record Entry(long txn, boolean truncate, TableDef def, RowBlock rows) {
    }

    /**
     * One scan's view of the log: positional reads into a heap buffer of the window's bytes, which {@link #slice} alone
     * grows, to the length of a frame it must hand over whole. A slice is valid until the next read through the window.
     */
    private static final class Window implements AutoCloseable {
        private final FileChannel channel;
        private final Path file;
        private final long size;
        // the file offset of the buffer's first byte
        private long base;
        private ByteBuffer buffer;
        // how many bytes from base the buffer holds
        private int filled;

        Window(final Path file, final int windowBytes) throws IOException {
            this.file = file;
            channel = StoreFiles.openForRead(file);
            try {
                size = channel.size();
                // a frame's header and txn fit any window, so only a body ever needs more
                final long capacity = Math.max(FRAME + MIN_BODY, Math.min(windowBytes, size));
                buffer = ByteBuffer.allocate((int) capacity).order(ByteOrder.LITTLE_ENDIAN);
            } catch (final Throwable t) {
                try {
                    channel.close();
                } catch (final IOException e) {
                    t.addSuppressed(e);
                }
                throw t;
            }
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }

        int capacity() {
            return buffer.capacity();
        }

        int intAt(final long at) throws IOException {
            cover(at, Integer.BYTES);
            return buffer.getInt((int) (at - base));
        }

        long longAt(final long at) throws IOException {
            cover(at, Long.BYTES);
            return buffer.getLong((int) (at - base));
        }

        /** The {@code length} bytes from {@code at}, little-endian; grows the buffer to {@code length} if smaller. */
        ByteBuffer slice(final long at, final int length) throws IOException {
            if (length > buffer.capacity()) {
                buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
                filled = 0;
            }
            cover(at, length);
            return buffer.slice((int) (at - base), length).order(ByteOrder.LITTLE_ENDIAN);
        }

        /** Feeds {@code crc} the {@code length} bytes from {@code at}, a buffer's worth at a time. */
        void update(final CRC32C crc, final long at, final long length) throws IOException {
            long done = 0L;
            while (done < length) {
                final int chunk = (int) Math.min(buffer.capacity(), length - done);
                cover(at + done, chunk);
                crc.update(buffer.array(), (int) (at + done - base), chunk);
                done += chunk;
            }
        }

        // makes the file's bytes [at, at + need) readable at buffer index at - base; the caller has checked that need
        // fits the buffer
        private void cover(final long at, final int need) throws IOException {
            if (at >= base && at + need <= base + filled) {
                return;
            }
            if (at + need > size) {
                throw new IOException("cannot read log past its end [file=" + file + ", offset=" + at + ", bytes="
                        + need + ", size=" + size + ']');
            }
            // a scan moves forward: keep what is already read past at, so each byte is read once
            final int keep = at >= base && at < base + filled ? (int) (base + filled - at) : 0;
            if (keep > 0) {
                System.arraycopy(buffer.array(), (int) (at - base), buffer.array(), 0, keep);
            }
            base = at;
            filled = keep;
            buffer.clear().limit((int) Math.min(buffer.capacity(), size - at));
            while (filled < need) {
                final int read = channel.read(buffer.position(filled), base + filled);
                if (read < 0) {
                    throw new IOException("cannot read log past its end [file=" + file + ", offset=" + (base + filled)
                            + ", size=" + size + ']');
                }
                filled += read;
            }
        }
    }
}
