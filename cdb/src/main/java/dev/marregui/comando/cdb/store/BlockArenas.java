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
 * takes new blocks until it is {@link #ROTATE_NANOS} old or has handed out {@link #ROTATE_BYTES}, and its arena is
 * closed once it takes no more blocks and its last block left it. Closing a shared arena stops every thread of the
 * process for a handshake: an arena per block did that once per commit (2.46 M handshakes in a 17 min Cosa soak, 221 s
 * of the VM thread's time, the longest 5.3 s); an epoch does it once per {@link #ROTATE_NANOS} or per {@link
 * #ROTATE_BYTES} handed out, whichever ends it first.
 * <p>
 * A block's bytes stay mapped until its epoch's arena closes, so a closed block is made unreadable by the block itself
 * ({@link RowBlock#close}), not by its arena. An epoch holds every byte its blocks were given until its last block
 * leaves: {@link #ROTATE_BYTES} ends it for new blocks at the next join, but blocks already in it keep allocating as
 * their text grows, and one block never closed keeps the whole epoch; an epoch past {@link #ROTATE_NANOS} with no open
 * block is closed by the last block that leaves it, or by {@link #sweep} on the applier's pass, so nothing stays held
 * while no commit comes. Thread-safe: blocks join on request threads and leave on the applier, the replay and a table's
 * close.
 */
final class BlockArenas {

    // the age that ends an epoch, so 10 shared-arena closes a second plus one per ROTATE_BYTES handed out: set from the
    // soak above (2,377 a second with an arena per commit); an epoch then takes no more than 100 ms of commits
    static final long ROTATE_NANOS = TimeUnit.MILLISECONDS.toNanos(100L);
    // the bytes that end an epoch, so a burst of large commits does not keep one arena's memory past their close
    static final long ROTATE_BYTES = 16L << 20;
    private static final BlockArenas SHARED = new BlockArenas(ROTATE_NANOS, ROTATE_BYTES, System::nanoTime);

    private final LongSupplier clock;
    // guards current, open and every epoch's blocks and retired
    private final ReentrantLock lock = new ReentrantLock();
    // the epochs whose arena is not yet closed, under lock: what the leak check reads, no counter written per allocation
    private final Set<Epoch> open = new HashSet<>();
    private final long rotateBytes;
    private final long rotateNanos;
    // the epoch new blocks join, null when none; under lock
    private Epoch current;

    BlockArenas(final long rotateNanos, final long rotateBytes, final LongSupplier clock) {
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
                current = new Epoch(nowNanos);
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
            epoch.arena.close();
            lock.lock();
            try {
                open.remove(epoch);
            } finally {
                lock.unlock();
            }
        }
    }

    /** One epoch's shared arena, tagged as row blocks, with the bytes handed out of it. */
    static final class Epoch {

        private final Arena arena = Arena.ofShared();
        // added to by allocate on any thread holding a block of this epoch; read by join under the lock
        private final AtomicLong bytes = new AtomicLong();
        private final long openedNanos;
        // under BlockArenas.lock: the open blocks of this epoch, and whether it takes no more
        private int blocks;
        private boolean retired;

        private Epoch(final long openedNanos) {
            this.openedNanos = openedNanos;
        }

        /** {@code byteSize} zeroed bytes aligned to {@code alignment}; the caller holds a block of this epoch. */
        MemorySegment allocate(final long byteSize, final long alignment) {
            final MemorySegment segment = arena.allocate(byteSize, alignment);
            bytes.addAndGet(byteSize);
            return segment;
        }

        /** The bytes handed out of this epoch. */
        @TestOnly
        long bytes() {
            return bytes.get();
        }

        @Override
        public String toString() {
            return "row blocks [bytes=" + bytes.get() + ']';
        }
    }
}
