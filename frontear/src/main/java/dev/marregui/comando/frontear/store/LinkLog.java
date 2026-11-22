package dev.marregui.comando.frontear.store;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.zip.CRC32C;

import dev.marregui.comando.core.files.Mapped;
import dev.marregui.comando.frontear.watch.Watch;

/**
 * A link's queue: an append-only log of records in segment files, read from a cursor that survives a restart.
 * Delivery is at-least-once: a record whose append completed survives a crash, and the records after the last saved
 * cursor are read again after one.
 *
 * <p>Files, every number little-endian. Segments are named by their base offset, 20 digits, {@code .log}. A segment
 * is a header (u32 magic {@code FTLG}, u32 version, u64 base offset), then one frame per record: u32 length, u32
 * CRC32C, the frame's bytes. In version 2, the one written, a length with its top bit ({@link #DELTA}) set marks a
 * delta: the bytes are the record as it differs from the record before it in the segment ({@link Deltas}); otherwise,
 * and always in version 1, they are the record. A record is written whole when it is its segment's first or every
 * {@link #KEYFRAME_EVERY}th after it, the first after an open, or when a delta would save little, so a read that
 * starts anywhere decodes at most that many records before its first. A version 1 segment the log ends on at open
 * takes the next records whole, as version 1 writes them; every segment started is version 2. The CRC covers the
 * record's offset (u64) and the length field (u32), then the frame's bytes, so a frame read at another offset, or a
 * stretch of zeros, fails it. Offsets are gap-free: a segment's base is the previous
 * segment's base plus its record count. The file {@code cursor} is u32 magic {@code FTCR}, u32 version 1, u64 cursor,
 * u32 CRC32C of the 16 bytes before it, replaced whole: written to {@code cursor.tmp}, forced, moved over, the folder
 * forced.</p>
 *
 * <p>Threads. One thread at a time calls {@link #append}, {@link #read} and {@link #ack} (the link's strand), and
 * each is short: an append copies its record into the round, the records in memory waiting for the forcer, assigns
 * its offset and place in a segment, and returns, writing no file; an ack records the cursor. The round is bounded by
 * {@link LogOptions#maxRoundBytes()}. The forcer, a platform daemon thread named {@code frontear-log-force-<folder>}
 * ({@link LogForcer}; a force blocks in the file system, which would pin a virtual thread's carrier), runs at most
 * {@link LogOptions#forceEvery()} after the first record or ack it has not covered: it takes the round, writes its
 * frames, creating the segments the round starts, forces every segment it wrote, and the folder when it created one,
 * outside every lock, so appends fill the next round meanwhile; completes the appends it covered in offset order;
 * keeps their records for reads until the next round, so a read of what just became durable opens no file; then saves
 * an advanced cursor and deletes the segments wholly below the saved one. A segment sealed and wholly forced is mapped
 * once, at its first read, and that mapping serves every later read of it until the segment is deleted or the log
 * closed, so a link that fell behind reads its backlog without a mapping per read; at most one mapping per retained
 * segment, which {@link #bytes()} and {@link LogOptions#maxBytes()} bound. No write runs on the strand because on APFS
 * a write to a file blocks while a force of the same file runs, so a write there would stall the link for the force,
 * as {@code FrontearBench} under JFR showed on an Apple M2 Max. The save waits while appends taken since the round
 * began wait for their force, at most {@link #CURSOR_EVERY_NANOS} after the last one, so under load the forcer spends
 * its time on appends, not on the cursor's two forces. The futures complete on the forcer, and so do their
 * dependants: they must hand off, not block. The close stops the forcer by a flag and a wake-up, never an interrupt,
 * which would close a channel mid-force; the forcer's last round writes, forces, completes, and closes the
 * files.</p>
 *
 * <p>Mirrored from cdb's store log ({@code cdb.store.Wal}, {@code LogForcer}, {@code ForceTicket}): the frame of a
 * length and a CRC32C; the scan that ends at the first frame short, past the end or failing its CRC, and cuts it off,
 * forced, before anything appends; group commit on a thread other than the writer's, with the force outside the lock
 * and the durable position published after it; a failed force poisons the log, since a force that holds after a failed
 * one does not prove the pages the failure dropped, and fails every append it left in doubt; an interrupt cleared for
 * a write or a force, which it would fail by closing the channel, and restored after; the forcer, one platform thread
 * stopped by a flag. Not mirrored: durability is the append's future, not a ticket to wait on; no salt, since every
 * cut is forced before a frame follows it and a segment's base is never reused; a failed write poisons the log too,
 * since the offsets after its frame are assigned and the log cannot skip it.</p>
 *
 * <p>Recovery on open: the segments wholly below the saved cursor are deleted (a crash came between the cursor's save
 * and their deletion); the rest are scanned in offset order and the log ends at the first segment whose header is
 * torn or whose base does not follow, or at the first bad frame, which is cut off; every segment after the end is
 * deleted. What is kept is forced, with the folder, before the first append, so a later force never covers a record
 * whose earlier neighbours a power cut could still take.</p>
 */
public final class LinkLog implements AutoCloseable {

    /** The bytes of a segment's header: magic, version, base offset. */
    static final int HEADER = 16;
    /** The bytes before a record in its frame: length and CRC. */
    static final int FRAME = 8;
    /**
     * The longest a cursor save waits for the forcer to go idle while appends keep it busy. A save is two forces and a
     * move, about 8 ms measured on an Apple M2 Max, so a save a second holds the forcer under 1% of the time; a crash
     * delivers again at most about a second of acknowledged records, which at-least-once allows.
     */
    static final long CURSOR_EVERY_NANOS = 1_000_000_000L;
    /** The segment format written: frames whole or deltas. */
    static final int VERSION = 2;
    /** The first segment format, frames whole only: still read, and continued by the log that opens on one. */
    static final int VERSION_1 = 1;
    /** The bit of a frame's length field that marks a delta, in a version 2 segment. */
    static final int DELTA = 0x8000_0000;
    /**
     * A record whose offset from its segment's base is a multiple of this is written whole, so a read that does not
     * go on from the last one decodes at most this many records before its first. Not tuned: for the PUBLISH a link
     * logs with a 128-byte payload (a frame of 234 bytes whole, 144 on average measured with deltas), one whole record
     * in 64 adds under 2 bytes a record, and a read away from the last decodes at most 14 KiB; a profile of a link
     * catching up would settle it.
     */
    static final int KEYFRAME_EVERY = 64;

    private static final System.Logger LOG = System.getLogger(LinkLog.class.getName());
    // "FTLG" and "FTCR" read as little-endian ints
    private static final int MAGIC = 0x474C5446;
    private static final int CURSOR_MAGIC = 0x52435446;
    private static final int CURSOR_VERSION = 1;
    // a frame's length field without its delta bit
    private static final int LENGTH = ~DELTA;
    // magic, version, cursor, CRC
    private static final int CURSOR_BYTES = 20;
    private static final String CURSOR = "cursor";
    private static final String SUFFIX = ".log";
    // a segment's name is its base offset, zero-padded so the names sort as the offsets do
    private static final int NAME_DIGITS = 20;
    // the answer of readCursor when the file holds no cursor
    private static final long NO_CURSOR = -1L;
    // a sizing hint: the forcer joins a segment's frames into writes of about this many bytes, a frame larger than it
    // alone, so the copy that joins them stays small and a round of 8 MiB is a handful of writes
    private static final int RUN_BYTES = 1 << 20;
    private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private final LogFiles files;
    private final Path dir;
    private final LogOptions options;
    private final long forceEveryNanos;
    private final LogForcer forcer;
    // keeps each round the forcer writes while it is on
    private final Watch watch;
    // the log's one refusal, built again for each: a link offers one message per refusal while the forcer is behind
    private final QuotaExceededException quotaExceeded;
    // guards the fields below up to the volatile ones: taken by the writer's thread and the forcer, never across a
    // file operation or a future's completion
    private final Object lock = new Object();
    // every retained segment, oldest first: the writer's thread adds at the end, the forcer deletes from the front
    private final ArrayDeque<Segment> segments;
    // the appends not yet durable, in offset order: the round, which the forcer takes whole, as it is at the start of
    // every flush, since the flush before completed or failed every append it took
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    // the cursor the last ack recorded, and the one the cursor file holds
    private long acked;
    private long saved;
    // a flush is armed and has not yet taken its snapshot
    private boolean flushScheduled;
    // the first failed write or force, which poisons the log; null while none failed
    private IOException failure;
    private boolean closed;
    // the segment appended to: the writer's thread rolls it
    private Segment active;
    // the appends of the last round the forcer completed, at gap-free offsets, kept for reads until the next round
    // replaces them or an ack passes them; empty when there are none
    private List<Pending> lastRound = List.of();
    // the next offset an append assigns: written by the writer's thread under the lock, read by anyone
    private volatile long tail;
    // every record below it is durable: written by the forcer under the lock after its force, read by the writer's
    // thread, so a read never returns a record whose force has not held
    private volatile long durable;
    // the next offset to deliver: written by the writer's thread under the lock, read by anyone
    private volatile long cursor;
    // the retained bytes: written under the lock by the writer's thread (an append) and the forcer (a deletion)
    private volatile long bytes;
    // the bytes the appends not yet durable hold, each its record whole and a frame's FRAME bytes: written under the
    // lock by the writer's thread (an append), the forcer (a completion, a failure) and the closing thread (a close
    // that gave up waiting), read by anyone
    private volatile long roundBytes;
    // the writer's thread's alone: where the last read of a file ended, and a copy of the record before that, so a
    // read that goes on from there walks no frame twice and decodes the delta there
    private Segment hintSegment;
    private long hintOffset;
    private long hintPosition;
    private byte[] hintPrevious;
    // the forcer's alone: when it last tried to save the cursor, from System.nanoTime
    private long savedAtNanos;

    private LinkLog(
            final LogFiles files,
            final Path dir,
            final LogOptions options,
            final ArrayDeque<Segment> segments,
            final long cursor,
            final long tail,
            final Watch watch
    ) {
        this.files = files;
        this.watch = watch;
        this.dir = dir;
        this.options = options;
        this.forceEveryNanos = nanos(options.forceEvery());
        this.segments = segments;
        this.active = segments.peekLast();
        this.acked = cursor;
        this.saved = cursor;
        this.cursor = cursor;
        this.tail = tail;
        this.durable = tail;
        long retained = 0L;
        for (final Segment s : segments) {
            retained += s.size;
        }
        this.bytes = retained;
        // written before the forcer starts, which publishes it to that thread
        this.savedAtNanos = System.nanoTime();
        this.forcer = new LogForcer(dir.getFileName().toString(), forceEveryNanos, this::flush, this::finish);
        this.quotaExceeded = new QuotaExceededException(dir);
    }

    /**
     * Opens the log in {@code dir}, creating the folder when it is missing, and recovers it as the class says. Holds
     * a channel on its last segment and a forcer thread until {@link #close}.
     */
    public static LinkLog open(final Path dir, final LogOptions options) throws IOException {
        return open(DiskFiles.INSTANCE, dir, options);
    }

    /** As above, each round the forcer writes kept in {@code watch} while that is on, named by the folder. */
    public static LinkLog open(final Path dir, final LogOptions options, final Watch watch) throws IOException {
        return open(DiskFiles.INSTANCE, dir, options, Objects.requireNonNull(watch, "watch"));
    }

    /**
     * Takes a copy of {@code record} into the round and returns its offset's future, which completes once the forcer
     * has written it and a force covers it: then the record survives a crash. No file is touched on the caller's
     * thread. Offsets are strictly increasing and gap-free. Fails at once with {@link QuotaExceededException} when the
     * retained bytes would pass {@link LogOptions#maxBytes()} or the round's {@link LogOptions#maxRoundBytes()}, the
     * log's one instance, its message valid until the next refusal, with an {@link IOException} when the log is closed
     * or poisoned; nothing is appended then. A future that fails (a
     * failed write or force, a close before the force) leaves the record in doubt: it may be read after a restart.
     */
    public CompletableFuture<Long> append(final byte[] record) {
        Objects.requireNonNull(record, "record");
        // the forcer writes it after the caller moves on, which may reuse the array
        final byte[] copy = record.clone();
        final long held = FRAME + (long) copy.length;
        final CompletableFuture<Long> future = new CompletableFuture<>();
        synchronized (lock) {
            final IOException refused = refusal("append");
            if (refused != null) {
                return CompletableFuture.failedFuture(refused);
            }
            final long offset = tail;
            byte[] delta = active == null || active.version != VERSION || active.previous == null
                    || (offset - active.base) % KEYFRAME_EVERY == 0L ? null : Deltas.encode(active.previous, copy);
            final boolean roll = active == null || active.count > 0L && active.size + FRAME
                    + (delta == null ? copy.length : delta.length) > options.segmentBytes();
            if (roll) {
                // a segment's first record is written whole
                delta = null;
            }
            final byte[] stored = delta == null ? copy : delta;
            final long frame = FRAME + (long) stored.length;
            final long need = frame + (roll ? HEADER : 0L);
            if (bytes + need > options.maxBytes()) {
                return CompletableFuture.failedFuture(quotaExceeded.pastQuota(bytes, need, options.maxBytes()));
            }
            if (roundBytes > 0L && roundBytes + held > options.maxRoundBytes()) {
                return CompletableFuture.failedFuture(quotaExceeded.pastRound(roundBytes, held,
                        options.maxRoundBytes()));
            }
            if (roll) {
                roll(offset);
            }
            pending.addLast(new Pending(offset, active, active.size, copy, stored,
                    delta == null ? stored.length : DELTA | stored.length, future));
            active.size += frame;
            active.count++;
            active.previous = copy;
            tail = offset + 1L;
            bytes += need;
            roundBytes += held;
            scheduleFlush();
        }
        return future;
    }

    /**
     * Up to {@code max} durable records from {@code fromOffset} on, in offset order; empty when there are none. A
     * record below the cursor is done and never returned: a read from below it starts at the cursor. The records of
     * the last round forced are read from memory, older ones from their segment.
     */
    public List<Record> read(final long fromOffset, final int max) throws IOException {
        if (max < 0) {
            throw new IllegalArgumentException("refuse read of a negative count [max=" + max + ']');
        }
        final long upTo;
        final List<Pending> round;
        synchronized (lock) {
            if (closed) {
                throw new IOException("refuse read of closed log [dir=" + dir + ']');
            }
            upTo = durable;
            round = lastRound;
        }
        final long from = Math.max(fromOffset, cursor);
        if (from >= upTo || max == 0) {
            return List.of();
        }
        final long end = from + Math.min(max, upTo - from);
        final long roundFrom = round.isEmpty() ? end : round.getFirst().offset;
        final long roundTo = round.isEmpty() ? end : roundFrom + round.size();
        final List<Record> out = new ArrayList<>((int) (end - from));
        long next = from;
        while (next < end) {
            if (next >= roundFrom && next < roundTo) {
                out.add(new Record(next, round.get((int) (next - roundFrom)).record.clone()));
                next++;
            } else {
                final Segment s = segmentOf(next);
                final long stop = Math.min(end, s.base + countOf(s));
                next = readSegment(s, next, next < roundFrom ? Math.min(stop, roundFrom) : stop, out);
            }
        }
        return out;
    }

    /**
     * Marks every record at or below {@code offset} done: {@link #cursor()} moves past it at once, the forcer saves
     * it within {@link LogOptions#forceEvery()} once no append waits for a force, and at most
     * {@link #CURSOR_EVERY_NANOS} after its last save while appends keep it busy, so it survives a restart, and then
     * deletes the segments wholly below it. An offset already below the cursor changes nothing; one not durable yet is
     * refused.
     */
    public void ack(final long offset) throws IOException {
        synchronized (lock) {
            final IOException refused = refusal("acknowledge");
            if (refused != null) {
                throw refused;
            }
            if (offset >= durable) {
                throw new IllegalArgumentException("refuse acknowledgement of a record not durable [dir=" + dir
                        + ", offset=" + offset + ", durable=" + durable + ']');
            }
            if (offset < cursor) {
                return;
            }
            acked = offset + 1L;
            cursor = offset + 1L;
            if (!lastRound.isEmpty() && lastRound.getLast().offset < cursor) {
                lastRound = List.of();
            }
            scheduleFlush();
        }
    }

    /** The next offset to deliver: the last acknowledged plus one; after a restart, the last saved plus one. */
    public long cursor() {
        return cursor;
    }

    /** The next offset an append assigns. */
    public long tail() {
        return tail;
    }

    /** The bytes the log retains on disk, segment headers included, the round's frames counted from their append. */
    public long bytes() {
        return bytes;
    }

    /**
     * The bytes the appends not yet durable hold, each counted as its record's frame written whole, the usage of
     * {@link LogOptions#maxRoundBytes()}.
     */
    public long roundBytes() {
        return roundBytes;
    }

    /** The segment files the log retains: read under the lock, which no file operation holds. */
    public int segments() {
        synchronized (lock) {
            return segments.size();
        }
    }

    /**
     * Writes and forces what was appended and saves the cursor, waiting a bounded time for it, then ends the forcer
     * and closes the files; idempotent. An append whose force did not hold by then fails, in doubt. When the wait runs
     * out, a force still in flight, the files are closed on the forcer once that force returns, never under it.
     */
    @Override
    public void close() {
        final List<LogFiles.Mapping> unmapped = new ArrayList<>();
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            // no flush is armed after the last round
            flushScheduled = true;
            for (final Segment s : segments) {
                if (s.mapping != null) {
                    unmapped.add(s.mapping);
                    s.mapping = null;
                }
            }
        }
        unmap(unmapped);
        if (!forcer.stop()) {
            failPending();
        }
    }

    /** As {@link #open(Path, LogOptions)}, every file operation through {@code files}. */
    static LinkLog open(final LogFiles files, final Path dir, final LogOptions options) throws IOException {
        return open(files, dir, options, new Watch(1));
    }

    /** As {@link #open(Path, LogOptions, Watch)}, every file operation through {@code files}. */
    static LinkLog open(final LogFiles files, final Path dir, final LogOptions options, final Watch watch)
            throws IOException {
        Objects.requireNonNull(options, "options");
        files.createDirectories(dir);
        files.delete(WholeFile.tmp(dir.resolve(CURSOR)));
        final List<Path> entries = files.list(dir);
        long savedCursor = NO_CURSOR;
        for (final Path entry : entries) {
            if (entry.getFileName().toString().equals(CURSOR)) {
                savedCursor = readCursor(files, entry);
            }
        }
        final ArrayDeque<Segment> kept = new ArrayDeque<>();
        try {
            recover(files, segmentFiles(entries), savedCursor, kept);
            final Segment last = kept.peekLast();
            final long tail = last == null ? Math.max(0L, savedCursor) : last.base + last.count;
            final long first = last == null ? tail : kept.getFirst().base;
            long cursor = savedCursor == NO_CURSOR ? first : savedCursor;
            if (cursor < first || cursor > tail) {
                LOG.log(System.Logger.Level.WARNING, "move cursor into the log's records [dir=" + dir + ", cursor="
                        + cursor + ", first=" + first + ", tail=" + tail + ']');
                cursor = Math.clamp(cursor, first, tail);
            }
            for (final Segment s : kept) {
                if (s == last) {
                    s.writer = files.open(s.file);
                    s.writer.force();
                } else {
                    try (LogFiles.Writer writer = files.open(s.file)) {
                        writer.force();
                    }
                    s.sealed = true;
                }
                s.forcedSize = s.size;
            }
            files.forceFolder(dir);
            LOG.log(System.Logger.Level.INFO, "opened link log [dir=" + dir + ", segments=" + kept.size() + ", cursor="
                    + cursor + ", tail=" + tail + ']');
            final LinkLog log = new LinkLog(files, dir, options, kept, cursor, tail, watch);
            log.forcer.start();
            return log;
        } catch (final IOException | RuntimeException e) {
            for (final Segment s : kept) {
                closeAfter(e, s.writer);
            }
            throw e;
        }
    }

    // the cursor file's value, NO_CURSOR when it holds none
    private static long readCursor(final LogFiles files, final Path file) throws IOException {
        final long value = files.read(file, content -> {
            if (content.byteSize() != CURSOR_BYTES || content.get(INT, 0L) != CURSOR_MAGIC
                    || content.get(INT, Integer.BYTES) != CURSOR_VERSION) {
                return NO_CURSOR;
            }
            final CRC32C crc = new CRC32C();
            crc.update(Mapped.bytes(content.asSlice(0L, CURSOR_BYTES - Integer.BYTES)));
            final long read = content.get(LONG, Long.BYTES);
            return (int) crc.getValue() == content.get(INT, CURSOR_BYTES - Integer.BYTES) && read >= 0L ? read
                    : NO_CURSOR;
        });
        if (value == NO_CURSOR) {
            LOG.log(System.Logger.Level.WARNING, "ignore cursor file that holds no cursor, deliver from the first"
                    + " record [file=" + file + ']');
        }
        return value;
    }

    // the segments among entries, by base offset
    private static List<Path> segmentFiles(final List<Path> entries) {
        final List<Path> found = new ArrayList<>();
        for (final Path entry : entries) {
            if (baseOf(entry) >= 0L) {
                found.add(entry);
            }
        }
        found.sort(Comparator.comparingLong(LinkLog::baseOf));
        return found;
    }

    // scans found into kept, as the class says; the segments deleted are those below the cursor and past the end
    private static void recover(
            final LogFiles files,
            final List<Path> found,
            final long savedCursor,
            final ArrayDeque<Segment> kept
    ) throws IOException {
        boolean ended = false;
        for (int i = 0; i < found.size(); i++) {
            final Path file = found.get(i);
            final long base = baseOf(file);
            if (ended) {
                LOG.log(System.Logger.Level.WARNING, "delete log segment past the end of the log [file=" + file + ']');
                files.delete(file);
                continue;
            }
            if (savedCursor != NO_CURSOR && i + 1 < found.size() && baseOf(found.get(i + 1)) <= savedCursor) {
                files.delete(file);
                continue;
            }
            final Segment last = kept.peekLast();
            final long expected = last == null ? base : last.base + last.count;
            final Scan scan = files.read(file, content -> scan(content, base));
            if (!scan.header || base != expected) {
                LOG.log(System.Logger.Level.WARNING, "end log at segment that does not follow [file=" + file
                        + ", base=" + base + ", expected=" + expected + ", header=" + scan.header + ", version="
                        + scan.version + ']');
                files.delete(file);
                ended = true;
                continue;
            }
            final Segment s = new Segment(file, base, scan.version);
            s.size = scan.end;
            s.count = scan.count;
            s.created = true;
            if (scan.end < scan.size) {
                LOG.log(System.Logger.Level.WARNING, "cut torn tail off log segment [file=" + file + ", offset="
                        + (base + scan.count) + ", position=" + scan.end + ", bytes=" + (scan.size - scan.end) + ']');
                try (LogFiles.Writer writer = files.open(file)) {
                    writer.truncate(scan.end);
                    writer.force();
                }
                ended = true;
            }
            kept.addLast(s);
        }
    }

    // a segment's frames from its header on, up to the first frame short, past the end or failing its CRC
    private static Scan scan(final MemorySegment content, final long base) {
        final long size = content.byteSize();
        final int version = size < HEADER ? 0 : content.get(INT, Integer.BYTES);
        if (size < HEADER || content.get(INT, 0L) != MAGIC || version != VERSION && version != VERSION_1
                || content.get(LONG, Long.BYTES) != base) {
            return new Scan(false, version, size, 0L, 0L);
        }
        long at = HEADER;
        long count = 0L;
        byte[] bytes = frameAt(content, at, base, version);
        while (bytes != null) {
            at += FRAME + bytes.length;
            count++;
            bytes = frameAt(content, at, base + count, version);
        }
        return new Scan(true, version, size, at, count);
    }

    // the bytes of the frame at position at, a record or a delta as its length field says, null when the frame is
    // short, runs past the end, fails its CRC or is a delta in a version 1 segment
    private static byte[] frameAt(final MemorySegment content, final long at, final long offset, final int version) {
        final long left = content.byteSize() - at;
        if (left < FRAME) {
            return null;
        }
        final int field = content.get(INT, at);
        final long length = version == VERSION_1 ? Integer.toUnsignedLong(field) : field & LENGTH;
        if (length > left - FRAME || length > Integer.MAX_VALUE - FRAME) {
            return null;
        }
        final byte[] bytes = Mapped.bytes(content.asSlice(at + FRAME, length));
        return checksum(offset, field, bytes) == content.get(INT, at + Integer.BYTES) ? bytes : null;
    }

    // the records of s from offset from up to stop added to out, walking its frames in content from startOffset at
    // startPosition, previous the record before startOffset; where the walk ended. A delta needs the record before
    // it, so the walk decodes from the last record written whole at or below from, or from startOffset when that is
    // later, and only steps over the frames before
    private static long walk(
            final Segment s,
            final MemorySegment content,
            final long startOffset,
            final long startPosition,
            final byte[] previous,
            final long from,
            final long stop,
            final List<Record> out
    ) throws IOException {
        final long whole = s.version == VERSION_1 ? from : from - (from - s.base) % KEYFRAME_EVERY;
        final long decodeFrom = Math.max(startOffset, whole);
        long at = startPosition;
        byte[] before = previous;
        for (long o = startOffset; o < stop; o++) {
            if (o < decodeFrom) {
                at += FRAME + (long) (content.get(INT, at) & LENGTH);
                continue;
            }
            final int field = content.get(INT, at);
            final byte[] bytes = frameAt(content, at, o, s.version);
            final byte[] record = bytes == null || (field & DELTA) == 0 || s.version == VERSION_1 ? bytes
                    : before == null ? null : Deltas.decode(before, bytes);
            if (record == null) {
                throw new IOException("cannot read durable record, its frame is corrupt [file=" + s.file
                        + ", offset=" + o + ", position=" + at + ']');
            }
            if (o >= from) {
                out.add(new Record(o, record));
            }
            before = record;
            at += FRAME + bytes.length;
        }
        return at;
    }

    // a frame's CRC32C: the record's offset and the frame's length field, then the frame's bytes
    private static int checksum(final long offset, final int field, final byte[] bytes) {
        final CRC32C crc = new CRC32C();
        crc.update(ByteBuffer.allocate(Long.BYTES + Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(offset)
                .putInt(field).flip());
        crc.update(bytes);
        return (int) crc.getValue();
    }

    // the end of the run of round that starts at from: the appends after it in the same segment while the run stays
    // within RUN_BYTES
    private static int runEnd(final List<Pending> round, final int from) {
        final Segment s = round.get(from).segment;
        long run = FRAME + (long) round.get(from).stored.length;
        int end = from + 1;
        while (end < round.size() && round.get(end).segment == s
                && run + FRAME + round.get(end).stored.length <= RUN_BYTES) {
            run += FRAME + round.get(end).stored.length;
            end++;
        }
        return end;
    }

    // the frames of round from from up to to, joined, ready to write
    private static ByteBuffer frames(final List<Pending> round, final int from, final int to) {
        int size = 0;
        for (int i = from; i < to; i++) {
            size += FRAME + round.get(i).stored.length;
        }
        final ByteBuffer joined = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = from; i < to; i++) {
            final Pending p = round.get(i);
            joined.putInt(p.field).putInt(checksum(p.offset, p.field, p.stored)).put(p.stored);
        }
        return joined.flip();
    }

    private static String nameOf(final long base) {
        return String.format(Locale.ROOT, "%0" + NAME_DIGITS + "d", base) + SUFFIX;
    }

    // the base offset a segment's name holds, -1 when the name is not a segment's
    private static long baseOf(final Path file) {
        final String name = file.getFileName().toString();
        if (name.length() != NAME_DIGITS + SUFFIX.length() || !name.endsWith(SUFFIX)) {
            return -1L;
        }
        for (int i = 0; i < NAME_DIGITS; i++) {
            if (name.charAt(i) < '0' || name.charAt(i) > '9') {
                return -1L;
            }
        }
        try {
            return Long.parseLong(name, 0, NAME_DIGITS, 10);
        } catch (final NumberFormatException _) {
            return -1L;
        }
    }

    // a duration in nanoseconds, the longest one a long holds for any longer
    private static long nanos(final Duration d) {
        try {
            return d.toNanos();
        } catch (final ArithmeticException _) {
            return Long.MAX_VALUE;
        }
    }

    private static void closeAfter(final Exception failure, final LogFiles.Writer writer) {
        if (writer == null) {
            return;
        }
        try {
            writer.close();
        } catch (final IOException e) {
            failure.addSuppressed(e);
        }
    }

    // called holding the lock: the reason an append or an ack is refused, null when it is not
    private IOException refusal(final String operation) {
        if (closed) {
            return new IOException("refuse " + operation + " on closed log [dir=" + dir + ']');
        }
        if (failure != null) {
            return new IOException("refuse " + operation + " on log after failed write or force [dir=" + dir
                    + ']', failure);
        }
        return null;
    }

    // called holding the lock by the writer's thread: starts the segment whose base is offset, its bytes the header's;
    // the forcer creates its file when it writes the segment's first frame
    private void roll(final long offset) {
        final Segment s = new Segment(dir.resolve(nameOf(offset)), offset, VERSION);
        s.size = HEADER;
        if (active != null) {
            active.sealed = true;
            active.previous = null;
        }
        segments.addLast(s);
        active = s;
    }

    // called holding the lock; the forcer's monitor, taken by its arm, is a leaf
    private void scheduleFlush() {
        if (!flushScheduled) {
            flushScheduled = true;
            forcer.arm();
        }
    }

    // the forcer's: takes the round, writes and forces it, completes the appends it covered, saves the cursor
    private void flush() {
        final long target;
        final List<Pending> round;
        final long cursorToSave;
        synchronized (lock) {
            flushScheduled = closed;
            if (failure != null) {
                return;
            }
            target = tail;
            round = List.copyOf(pending);
            cursorToSave = acked > saved ? acked : NO_CURSOR;
        }
        if (!round.isEmpty()) {
            final long startedNanos = System.nanoTime();
            if (!write(round)) {
                return;
            }
            if (watch.on()) {
                watch.round(dir.getFileName().toString(), round.size(), frameBytes(round), System.nanoTime()
                        - startedNanos);
            }
        }
        complete(target, round);
        if (cursorToSave != NO_CURSOR && cursorDue()) {
            savedAtNanos = System.nanoTime();
            saveCursor(cursorToSave);
        }
    }

    // the frame bytes round's appends write to the files
    private static long frameBytes(final List<Pending> round) {
        long n = 0L;
        for (final Pending p : round) {
            n += FRAME + p.stored.length;
        }
        return n;
    }

    // the forcer's: whether this round saves the cursor. The save's forces would hold back the appends taken since
    // the snapshot, so it waits for them while it is younger than CURSOR_EVERY_NANOS; they armed the next round, which
    // decides again. The close's last round always saves
    private boolean cursorDue() {
        synchronized (lock) {
            return closed || pending.isEmpty() || System.nanoTime() - savedAtNanos >= CURSOR_EVERY_NANOS;
        }
    }

    // the forcer's last round, once the close stopped it: a last flush, then the appends it left fail in doubt and the
    // files close, after every force has returned
    private void finish() {
        flush();
        final int left = failPending();
        final List<LogFiles.Writer> open = new ArrayList<>();
        synchronized (lock) {
            for (final Segment s : segments) {
                if (s.writer != null) {
                    open.add(s.writer);
                    s.writer = null;
                }
            }
        }
        for (final LogFiles.Writer writer : open) {
            try {
                writer.close();
            } catch (final IOException e) {
                LOG.log(System.Logger.Level.WARNING, "cannot close log segment [dir=" + dir + ']', e);
            }
        }
        LOG.log(System.Logger.Level.INFO, "closed link log [dir=" + dir + ", cursor=" + cursor + ", tail=" + tail
                + ", durable=" + durable + ", inDoubt=" + left + ']');
    }

    // fails the appends not yet durable, in doubt, once the log is closed; how many there were
    private int failPending() {
        final List<Pending> left;
        synchronized (lock) {
            left = new ArrayList<>(pending);
            pending.clear();
            roundBytes = 0L;
        }
        for (final Pending p : left) {
            p.future.completeExceptionally(new IOException("refuse append, log closed before its force [dir=" + dir
                    + ", offset=" + p.offset + ']'));
        }
        return left.size();
    }

    // the forcer's: writes the frames of round, creating the segments it starts, then forces every segment written,
    // and the folder when a segment was created; false, and the log poisoned, when one failed
    private boolean write(final List<Pending> round) {
        final boolean interrupted = Thread.interrupted();
        try {
            final List<Segment> written = new ArrayList<>();
            boolean created = false;
            int from = 0;
            while (from < round.size()) {
                final Segment s = round.get(from).segment;
                if (!s.created) {
                    create(s);
                    created = true;
                }
                final int to = runEnd(round, from);
                s.writer.write(frames(round, from, to), round.get(from).position);
                if (written.isEmpty() || written.getLast() != s) {
                    written.add(s);
                }
                from = to;
            }
            for (final Segment s : written) {
                s.writer.force();
            }
            if (created) {
                files.forceFolder(dir);
            }
            return true;
        } catch (final Throwable t) {
            // an Error is a failed write or force too: what reached the file, and the disk, is unknown after either
            fail(t);
            return false;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // the forcer's: creates the file of s and writes its header, not forced; the writer is kept before the write, so
    // the last round closes it whatever the write does
    private void create(final Segment s) throws IOException {
        s.writer = files.create(s.file);
        s.created = true;
        s.writer.write(ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN).putInt(MAGIC).putInt(s.version)
                .putLong(s.base).flip(), 0L);
    }

    // the forcer's: publishes the forced sizes and the durable position, closes the sealed segments now wholly forced,
    // keeps round for reads, and completes the appends below target in offset order, outside the lock
    private void complete(final long target, final List<Pending> round) {
        final List<Pending> done = new ArrayList<>();
        final List<LogFiles.Writer> spent = new ArrayList<>();
        synchronized (lock) {
            for (final Pending p : round) {
                p.segment.forcedSize = Math.max(p.segment.forcedSize, p.position + FRAME + p.stored.length);
            }
            for (final Segment s : segments) {
                if (s.sealed && s.writer != null && s.forcedSize == s.size) {
                    spent.add(s.writer);
                    s.writer = null;
                }
            }
            durable = Math.max(durable, target);
            while (!pending.isEmpty() && pending.peekFirst().offset < target) {
                final Pending p = pending.pollFirst();
                roundBytes -= FRAME + p.record.length;
                done.add(p);
            }
            if (!round.isEmpty()) {
                lastRound = round;
            }
        }
        for (final LogFiles.Writer writer : spent) {
            try {
                writer.close();
            } catch (final IOException e) {
                LOG.log(System.Logger.Level.WARNING, "cannot close forced log segment [dir=" + dir + ']', e);
            }
        }
        for (final Pending p : done) {
            p.future.complete(p.offset);
        }
    }

    // poisons the log with its first failure and fails every append in doubt
    private void fail(final Throwable cause) {
        final List<Pending> doubt;
        final IOException error;
        final boolean first;
        synchronized (lock) {
            first = failure == null;
            if (first) {
                failure = new IOException("refuse log after failed write or force, appends in doubt [dir=" + dir
                        + ", durable=" + durable + ", tail=" + tail + ']', cause);
            }
            error = failure;
            doubt = new ArrayList<>(pending);
            pending.clear();
            roundBytes = 0L;
        }
        if (first) {
            LOG.log(System.Logger.Level.ERROR, "refuse further appends, a write or force of the link log failed [dir="
                    + dir + ", fromOffset=" + durable + ", toOffset=" + tail + ']', cause);
        }
        for (final Pending p : doubt) {
            p.future.completeExceptionally(error);
        }
    }

    // the forcer's: replaces the cursor file, then deletes the segments wholly below the saved cursor. A failure keeps
    // the previous cursor (its records are delivered again after a restart) and the next flush tries again
    private void saveCursor(final long value) {
        final ByteBuffer content = ByteBuffer.allocate(CURSOR_BYTES).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(CURSOR_MAGIC).putInt(CURSOR_VERSION).putLong(value);
        final CRC32C crc = new CRC32C();
        crc.update(content.duplicate().flip());
        content.putInt((int) crc.getValue()).flip();
        final boolean interrupted = Thread.interrupted();
        try {
            WholeFile.replace(files, dir.resolve(CURSOR), content);
        } catch (final IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot save link log cursor, records after the saved one are"
                    + " delivered again after a restart [dir=" + dir + ", cursor=" + value + ", saved=" + saved + ']',
                    e);
            return;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        final List<Segment> gone = new ArrayList<>();
        final List<LogFiles.Writer> spent = new ArrayList<>();
        final List<LogFiles.Mapping> unmapped = new ArrayList<>();
        synchronized (lock) {
            saved = Math.max(saved, value);
            final Iterator<Segment> it = segments.iterator();
            Segment s = it.next();
            while (it.hasNext()) {
                final Segment next = it.next();
                if (next.base > saved) {
                    break;
                }
                gone.add(s);
                s = next;
            }
            for (final Segment g : gone) {
                segments.removeFirst();
                bytes -= g.size;
                // sealed by a roll after this flush's completion step, so that step left its writer open
                if (g.writer != null) {
                    spent.add(g.writer);
                    g.writer = null;
                }
                // below the saved cursor, so below every read the strand makes from now on
                g.deleted = true;
                if (g.mapping != null) {
                    unmapped.add(g.mapping);
                    g.mapping = null;
                }
            }
        }
        unmap(unmapped);
        for (final LogFiles.Writer writer : spent) {
            try {
                writer.close();
            } catch (final IOException e) {
                LOG.log(System.Logger.Level.WARNING, "cannot close acknowledged log segment [dir=" + dir + ']', e);
            }
        }
        for (final Segment g : gone) {
            try {
                files.delete(g.file);
            } catch (final IOException e) {
                // the next open deletes it, as it is below the saved cursor
                LOG.log(System.Logger.Level.WARNING, "cannot delete acknowledged log segment [file=" + g.file + ']', e);
            }
        }
    }

    // the retained segment holding offset, which is at or above the cursor and below the tail
    private Segment segmentOf(final long offset) {
        synchronized (lock) {
            final Iterator<Segment> it = segments.descendingIterator();
            while (it.hasNext()) {
                final Segment s = it.next();
                if (s.base <= offset) {
                    return s;
                }
            }
        }
        throw new IllegalStateException("no segment holds offset [dir=" + dir + ", offset=" + offset + ']');
    }

    private long countOf(final Segment s) {
        synchronized (lock) {
            return s.count;
        }
    }

    // the writer's thread's: adds the records of s from offset from up to stop to out; returns stop
    private long readSegment(final Segment s, final long from, final long stop, final List<Record> out)
            throws IOException {
        final boolean hinted = hintSegment == s && hintOffset <= from;
        final long startOffset = hinted ? hintOffset : s.base;
        final long startPosition = hinted ? hintPosition : HEADER;
        final byte[] previous = hinted ? hintPrevious : null;
        final LogFiles.Mapping mapping = mappingOf(s);
        final long endPosition;
        if (mapping == null) {
            endPosition = files.read(s.file, content -> walk(s, content, startOffset, startPosition, previous, from,
                    stop, out));
        } else {
            try {
                endPosition = walk(s, mapping.bytes(), startOffset, startPosition, previous, from, stop, out);
            } catch (final IllegalStateException | InternalError e) {
                // a close of the log or a deletion of s that raced this read from another thread, or the file cut
                throw new IOException("cannot read mapped log segment [file=" + s.file + ", offset=" + from + ']', e);
            }
        }
        hintSegment = s;
        hintOffset = stop;
        hintPosition = endPosition;
        // the record before stop, which the next delta needs; a copy, as the caller owns what it was handed
        hintPrevious = out.getLast().bytes().clone();
        return stop;
    }

    // the writer's thread's: the mapping every read of s shares once it is sealed and wholly forced, mapped at the
    // first; null while its bytes can still change, or once the log closed or s was deleted. Mapped outside the lock
    private LogFiles.Mapping mappingOf(final Segment s) throws IOException {
        synchronized (lock) {
            if (s.mapping != null || !s.sealed || s.forcedSize != s.size || closed || s.deleted) {
                return s.mapping;
            }
        }
        final LogFiles.Mapping mapped = files.map(s.file);
        final LogFiles.Mapping kept;
        synchronized (lock) {
            if (s.mapping == null && !closed && !s.deleted) {
                s.mapping = mapped;
            }
            kept = s.mapping;
        }
        if (kept != mapped) {
            mapped.close();
        }
        return kept;
    }

    // frees mappings detached from their segments; a failure is logged and the rest are freed
    private void unmap(final List<LogFiles.Mapping> mappings) {
        for (final LogFiles.Mapping m : mappings) {
            try {
                m.close();
            } catch (final RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "cannot unmap log segment [dir=" + dir + ", mapping=" + m + ']',
                        e);
            }
        }
    }

    /** One segment file; its sizes and count are guarded by the log's lock. */
    private static final class Segment {

        final Path file;
        final long base;
        // the format its frames are written in: a segment found at open keeps its own, a new one is VERSION
        final int version;
        // the forcer's, once the log is open: open while the segment has bytes to write or force; closed once it is
        // sealed and wholly forced
        LogFiles.Writer writer;
        // written by the writer's thread: the bytes the segment's appends take in the file, and their count
        long size;
        long count;
        // written by the writer's thread when it starts the next segment
        boolean sealed;
        // the forcer's: the file exists, its header written
        boolean created;
        // written by the forcer: the bytes its last force covered
        long forcedSize;
        // the writer's thread's to set once the segment is sealed and wholly forced; detached and freed by the forcer
        // when it deletes the segment, by the close otherwise
        LogFiles.Mapping mapping;
        // set by the forcer when it deletes the segment: no mapping is kept for it after
        boolean deleted;
        // the writer's thread's: the last record appended to the segment since the log opened, which the next
        // append's delta is made against; null for a segment sealed or not appended to since the open
        byte[] previous;

        Segment(final Path file, final long base, final int version) {
            this.file = file;
            this.base = base;
            this.version = version;
        }
    }

    /**
     * An append waiting in the round: its offset, its segment, where its frame goes there, its record, the bytes its
     * frame stores (the record, or its delta) with the frame's length field, and its future.
     */
    private record Pending(
            long offset,
            Segment segment,
            long position,
            byte[] record,
            byte[] stored,
            int field,
            CompletableFuture<Long> future
    ) {
    }

    /**
     * What a segment's scan found: whether its header holds, its format version, its size, where its last whole frame
     * ends, its count.
     */
    private record Scan(boolean header, int version, long size, long end, long count) {
    }
}
