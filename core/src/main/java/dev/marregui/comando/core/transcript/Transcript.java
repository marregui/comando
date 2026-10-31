package dev.marregui.comando.core.transcript;

import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The block log of one session: one writer thread (the session's strand), any number of readers.
 *
 * <p>Publication: the writer stores an element, then writes the volatile {@code version} (and {@code size}
 * for an append). A reader that reads {@code version} or {@code size} first sees every element written
 * before it. The array reference is volatile so a grown array is seen with its copied prefix.</p>
 */
public final class Transcript {

    /**
     * The most blocks a transcript holds: the JDK's soft maximum array length
     * ({@code ArraysSupport.SOFT_MAX_ARRAY_LENGTH}).
     */
    static final int MAX_BLOCKS = Integer.MAX_VALUE - 8;

    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile Block[] blocks = new Block[64];
    private volatile int size;
    private volatile long version;

    // ---- writer side

    /** Appends a block and returns its index. Writer thread only. */
    public int append(Block block) {
        int n = size;
        Block[] a = blocks;
        if (n == a.length) {
            // doubles; past MAX_BLOCKS an append fails loudly instead of overflowing the length (G-1.7)
            if (n >= MAX_BLOCKS) {
                throw new IllegalStateException("transcript full [blocks=" + n + ']');
            }
            a = Arrays.copyOf(a, (int) Math.min(2L * n, MAX_BLOCKS));
            blocks = a;
        }
        a[n] = block;
        size = n + 1;
        version++;
        return n;
    }

    /** Replaces block {@code index}. Writer thread only. */
    public void replace(int index, Block block) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index " + index + ", size " + size);
        }
        blocks[index] = block;
        version++;
    }

    /** Drops every block. Writer thread only. */
    public void clear() {
        blocks = new Block[64];
        size = 0;
        version++;
    }

    /** Tells listeners that blocks changed; the writer calls it after a batch, listeners coalesce. */
    public void changed() {
        for (Runnable l : listeners) {
            l.run();
        }
    }

    // ---- reader side

    public int size() {
        return size;
    }

    /**
     * The block at {@code index}; null at or past the end, which a reader holding a size from before a
     * {@link #clear()} reaches, so it stops rather than fails.
     */
    public Block get(int index) {
        if (index < 0) {
            throw new IndexOutOfBoundsException("index " + index);
        }
        int n = size;
        Block[] a = blocks;
        return index < n && index < a.length ? a[index] : null;
    }

    public long version() {
        return version;
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }
}
