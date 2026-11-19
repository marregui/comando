package dev.marregui.comando.cdb.store;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

import org.jetbrains.annotations.TestOnly;

/**
 * The native memory of row blocks, in shared arenas each of which serves the blocks opened during one epoch: an epoch
 * takes new blocks until it is {@link #ROTATE_NANOS} old or holds {@link #ROTATE_BYTES}, and its arena is closed once
 * it takes no more blocks and its last block left it. Closing a shared arena stops every thread of the process for a
 * handshake: an arena per block did that once per commit (2.46 M handshakes in a 17 min Cosa soak, 221 s of the VM
 * thread's time, the longest 5.3 s); an epoch does it once per {@link #ROTATE_NANOS} or per {@link #ROTATE_BYTES} held,
 * whichever ends it first. An epoch holds memory in {@link #CHUNK_BYTES} chunks, so one small block holds a whole
 * chunk. A segment a block gives back ({@link Epoch#free}, on its close or when its text outgrew it) is reused by a
 * later block of the same epoch, up to {@link #FREE_DEPTH} per size class, so a steady load holds fewer chunks.
 * <p>
 * A block's bytes stay mapped until its epoch's arena closes, so a closed block is made unreadable by the block itself
 * ({@link RowBlock#close}), not by its arena. An epoch holds its chunks and lone segments until its last block leaves:
 * {@link #ROTATE_BYTES} ends it for new blocks at the next join, but blocks already in it keep allocating as their text
 * grows, and one block never closed keeps the whole epoch; an epoch past {@link #ROTATE_NANOS} with no open block is
 * closed by the last block that leaves it, or by {@link #sweep} on the applier's pass, so nothing stays held while no
 * commit comes. Thread-safe: blocks join on request threads and leave on the applier, the replay and a table's close.
 */
final class BlockArenas {

    // the slab segments are cut from: one native allocation (zeroed once) instead of one per segment, which was 6.6 %
    // of cdb's Java CPU samples on the Cosa soak of record 170; a segment over a quarter of it is allocated alone
    static final long CHUNK_BYTES = 1L << 20;
    // the segments of one size class an epoch keeps for reuse: it bounds a free list's length, so its memory and the
    // scan allocate makes of it under chunkLock; a segment freed past it stays unused until its epoch closes. Not
    // measured on a soak: on a single-thread model of 1,700 group commits a second, native allocations reach their
    // floor of one chunk per epoch from 16 on, and 64 against 256 cuts 346k against 280k fresh segments per 100k
    // blocks; the soak's Epoch.allocate samples at 16, 64 and 256 would settle it
    static final int FREE_DEPTH = 64;
    // the bytes that end an epoch, so a burst of large commits does not keep one arena's memory past their close
    static final long ROTATE_BYTES = 16L << 20;
    // the age that ends an epoch, so 10 shared-arena closes a second plus one per ROTATE_BYTES held: set from the
    // soak above (2,377 a second with an arena per commit); an epoch then takes no more than 100 ms of commits
    static final long ROTATE_NANOS = TimeUnit.MILLISECONDS.toNanos(100L);
    // the largest alignment a block asks for (a long), and so the alignment of every chunk
    private static final long MAX_ALIGNMENT = Long.BYTES;
    private static final BlockArenas SHARED =
            new BlockArenas(ROTATE_NANOS, ROTATE_BYTES, CHUNK_BYTES, System::nanoTime);

    private final LongSupplier clock;
    // guards current, open and every epoch's blocks and retired
    private final ReentrantLock lock = new ReentrantLock();
    // the epochs whose arena is not yet closed, under lock: what the leak check reads, with no counter written per
    // allocation
    private final Set<Epoch> open = new HashSet<>();
    private final long chunkBytes;
    private final long rotateBytes;
    private final long rotateNanos;
    // the epoch new blocks join, null when none; under lock
    private Epoch current;

    BlockArenas(final long rotateNanos, final long rotateBytes, final long chunkBytes, final LongSupplier clock) {
        if (chunkBytes < MAX_ALIGNMENT || chunkBytes % MAX_ALIGNMENT != 0L) {
            throw new IllegalArgumentException("refuse a chunk that is not a multiple of a long [chunkBytes="
                    + chunkBytes + ']');
        }
        this.chunkBytes = chunkBytes;
        this.rotateNanos = rotateNanos;
        this.rotateBytes = rotateBytes;
        this.clock = clock;
    }

    /** The process's row blocks' arenas. */
    static BlockArenas shared() {
        return SHARED;
    }

    /** Joins the current epoch, first ending it when too old or too full; the block leaves with {@link #leave}. */
    Epoch join() {
        Epoch ended = null;
        final Epoch joined;
        lock.lock();
        try {
            final long nowNanos = clock.getAsLong();
            if (current != null
                    && (nowNanos - current.openedNanos >= rotateNanos || current.bytes.get() >= rotateBytes)) {
                final Epoch old = current;
                retire(old);
                if (old.blocks == 0) {
                    ended = old;
                }
            }
            if (current == null) {
                current = new Epoch(nowNanos, chunkBytes);
                open.add(current);
            }
            current.blocks++;
            joined = current;
        } finally {
            lock.unlock();
        }
        close(ended);
        return joined;
    }

    /** A block leaves {@code epoch}; the epoch's arena closes when it was the last block and the epoch ended. */
    void leave(final Epoch epoch) {
        Epoch ended = null;
        lock.lock();
        try {
            assert epoch.blocks > 0 : "an epoch left more often than joined";
            epoch.blocks--;
            if (epoch.blocks == 0 && (epoch.retired || clock.getAsLong() - epoch.openedNanos >= rotateNanos)) {
                retire(epoch);
                ended = epoch;
            }
        } finally {
            lock.unlock();
        }
        close(ended);
    }

    /** Closes the current epoch when no block holds it and it is past its age; called on the applier's pass. */
    void sweep() {
        Epoch ended = null;
        lock.lock();
        try {
            if (current != null && current.blocks == 0 && clock.getAsLong() - current.openedNanos >= rotateNanos) {
                ended = current;
                retire(ended);
            }
        } finally {
            lock.unlock();
        }
        close(ended);
    }

    /** Arenas not yet closed. */
    @TestOnly
    long openArenas() {
        lock.lock();
        try {
            return open.size();
        } finally {
            lock.unlock();
        }
    }

    /** Bytes the arenas not yet closed hold: what their blocks were given, closed or not. */
    @TestOnly
    long heldBytes() {
        lock.lock();
        try {
            long held = 0L;
            for (final Epoch e : open) {
                held += e.bytes.get();
            }
            return held;
        } finally {
            lock.unlock();
        }
    }

    // called with lock held: the epoch takes no block from here on
    private void retire(final Epoch epoch) {
        epoch.retired = true;
        if (epoch == current) {
            current = null;
        }
    }

    // outside the lock: a close waits for the handshake of every thread, and a join must not wait for it
    private void close(final Epoch epoch) {
        if (epoch != null) {
            epoch.closeArena();
            lock.lock();
            try {
                open.remove(epoch);
            } finally {
                lock.unlock();
            }
        }
    }

    /** One epoch's shared arena, tagged as row blocks, with the bytes it holds. */
    static final class Epoch {

        private final Arena arena = Arena.ofShared();
        // added to by allocate on any thread holding a block of this epoch; read by join under the lock
        private final AtomicLong bytes = new AtomicLong();
        private final long chunkBytes;
        // guards chunk and chunkUsed: blocks of one epoch allocate on several request threads at once
        private final ReentrantLock chunkLock = new ReentrantLock();
        private final long openedNanos;
        // under BlockArenas.lock: the open blocks of this epoch, and whether it takes no more
        private int blocks;
        private boolean retired;
        // under chunkLock: the slab small segments are cut from, null before the first, and the bytes of it cut
        private MemorySegment chunk;
        private long chunkUsed;
        // under chunkLock: per size class (the floor of the log2 of a byte size), the segments freed and not yet
        // reused, a stack of freeCount[class] of them; null before the first free
        private MemorySegment[][] free;
        private int[] freeCount;

        private Epoch(final long openedNanos, final long chunkBytes) {
            this.openedNanos = openedNanos;
            this.chunkBytes = chunkBytes;
        }

        @Override
        public String toString() {
            return "row blocks [bytes=" + bytes.get() + ']';
        }

        /**
         * {@code byteSize} zeroed bytes aligned to {@code alignment} (a power of two, at most a long's); the caller
         * holds a block of this epoch. A segment freed into the epoch is reused first, zeroed again, so no byte of the
         * block that freed it reaches the next; else a segment of up to a quarter of a chunk is cut from the epoch's
         * current chunk, a fresh one taken when it does not fit (the rest of the old one unused until the epoch
         * closes), and a larger one is allocated alone. The epoch's bytes count whole chunks, the memory it holds.
         */
        MemorySegment allocate(final long byteSize, final long alignment) {
            assert alignment > 0L && alignment <= MAX_ALIGNMENT && (alignment & (alignment - 1L)) == 0L
                    : "alignment not a power of two up to a long's";
            final boolean alone = byteSize > chunkBytes / 4L;
            final MemorySegment reused;
            // the lock is held while a fresh chunk is allocated and zeroed, once per CHUNK_BYTES; a lock-free bump was
            // not measured against it (the 127 ns per block of record 170 is one thread)
            chunkLock.lock();
            try {
                reused = takeFree(byteSize, alignment);
                if (reused == null && !alone) {
                    long at = (chunkUsed + alignment - 1L) & -alignment;
                    if (chunk == null || at + byteSize > chunkBytes) {
                        chunk = arena.allocate(chunkBytes, MAX_ALIGNMENT);
                        bytes.addAndGet(chunkBytes);
                        at = 0L;
                    }
                    chunkUsed = at + byteSize;
                    return chunk.asSlice(at, byteSize);
                }
            } finally {
                chunkLock.unlock();
            }
            if (reused != null) {
                // outside the lock: the segment is the caller's alone once taken off the free list
                return reused.fill((byte) 0);
            }
            final MemorySegment lone = arena.allocate(byteSize, alignment);
            bytes.addAndGet(byteSize);
            return lone;
        }

        /** The bytes this epoch holds: its chunks and the segments allocated alone. */
        @TestOnly
        long bytes() {
            return bytes.get();
        }

        /**
         * Gives back {@code segment}, which {@link #allocate} cut for a block of this epoch that no longer reaches it,
         * for a later allocate of this epoch to reuse; dropped, unused until the epoch closes, when its size class
         * already keeps {@link #FREE_DEPTH}. The epoch's bytes do not change: it still holds the memory.
         */
        void free(final MemorySegment segment) {
            final long byteSize = segment.byteSize();
            if (byteSize == 0L) {
                return;
            }
            assert segment.scope().equals(arena.scope()) : "a segment freed into an epoch it was not cut from";
            final int sizeClass = sizeClass(byteSize);
            chunkLock.lock();
            try {
                if (free == null) {
                    free = new MemorySegment[Long.SIZE][];
                    freeCount = new int[Long.SIZE];
                }
                if (free[sizeClass] == null) {
                    free[sizeClass] = new MemorySegment[FREE_DEPTH];
                }
                final int n = freeCount[sizeClass];
                if (n < FREE_DEPTH) {
                    free[sizeClass][n] = segment;
                    freeCount[sizeClass] = n + 1;
                }
            } finally {
                chunkLock.unlock();
            }
        }

        /** The segments of {@code byteSize}'s size class kept for reuse. */
        @TestOnly
        int freeSegments(final long byteSize) {
            chunkLock.lock();
            try {
                return free == null ? 0 : freeCount[sizeClass(byteSize)];
            } finally {
                chunkLock.unlock();
            }
        }

        private static int sizeClass(final long byteSize) {
            return Long.SIZE - 1 - Long.numberOfLeadingZeros(byteSize);
        }

        // called with no block left in the epoch: drop every reference into the arena, then close it
        private void closeArena() {
            chunkLock.lock();
            try {
                chunk = null;
                free = null;
                freeCount = null;
            } finally {
                chunkLock.unlock();
            }
            arena.close();
        }

        // called with chunkLock held: the most recently freed segment of byteSize's class that holds byteSize bytes at
        // alignment, taken off the free list and cut to byteSize; null when none
        private MemorySegment takeFree(final long byteSize, final long alignment) {
            if (free == null || byteSize == 0L) {
                return null;
            }
            final int sizeClass = sizeClass(byteSize);
            final MemorySegment[] stack = free[sizeClass];
            final int n = freeCount[sizeClass];
            for (int i = n - 1; i >= 0; i--) {
                final MemorySegment candidate = stack[i];
                if (candidate.byteSize() >= byteSize && (candidate.address() & (alignment - 1L)) == 0L) {
                    stack[i] = stack[n - 1];
                    stack[n - 1] = null;
                    freeCount[sizeClass] = n - 1;
                    return candidate.asSlice(0L, byteSize);
                }
            }
            return null;
        }
    }
}
