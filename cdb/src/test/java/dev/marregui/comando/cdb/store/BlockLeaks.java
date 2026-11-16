package dev.marregui.comando.cdb.store;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.fail;

/// The leak check of row-block memory, for tests that build blocks: every block closed (`RowBlock.liveBytes` back to
/// where the test started) and, once the epochs aged and a sweep ran, every epoch arena of the process closed with no
/// byte held. The arena check is process-wide on purpose: a block left open by any code in the test JVM keeps its
/// epoch, and the check fails naming what is held. Test classes run one after another in the cdb test JVM.
final class BlockLeaks {

    // the epochs age out after ROTATE_NANOS; five seconds leaves room for a loaded CI runner
    private static final long WAIT_NANOS = TimeUnit.SECONDS.toNanos(5L);

    private BlockLeaks() {
    }

    /// Fails unless the live block bytes are back at `liveBefore` and every epoch arena closes within the wait.
    static void assertNoBlockMemoryHeld(final long liveBefore) {
        final BlockArenas arenas = BlockArenas.shared();
        final long start = System.nanoTime();
        long backoffNanos = TimeUnit.MILLISECONDS.toNanos(1L);
        while (true) {
            arenas.sweep();
            // a store's close joins its applier, but a block closed on another thread may land a moment later
            if (RowBlock.liveBytes() == liveBefore && arenas.openArenas() == 0L && arenas.heldBytes() == 0L) {
                return;
            }
            if (System.nanoTime() - start - WAIT_NANOS > 0L) {
                fail("row block memory still held [liveBytes=" + (RowBlock.liveBytes() - liveBefore) + ", openArenas="
                        + arenas.openArenas() + ", heldBytes=" + arenas.heldBytes() + ", waitMillis="
                        + TimeUnit.NANOSECONDS.toMillis(WAIT_NANOS) + ']');
            }
            LockSupport.parkNanos(backoffNanos);
            backoffNanos = Math.min(backoffNanos * 2L, TimeUnit.MILLISECONDS.toNanos(20L));
        }
    }
}
