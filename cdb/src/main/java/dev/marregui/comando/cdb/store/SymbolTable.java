package dev.marregui.comando.cdb.store;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.jetbrains.annotations.TestOnly;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.exec.SymbolDictionary;

/**
 * The dictionary of one SYMBOL column of a table: each distinct value an int key, 0 upward, in first-seen order, kept
 * in one file, appended. Key -1 is NULL. One writer (the table's) appends; readers resolve keys below the size they
 * read at their snapshot, from any thread.
 * <p>
 * The file holds one entry per key, in key order: the value's UTF-8 length as a little-endian int, then its bytes. A
 * torn last entry, left by a crash during {@link #sync()}, is cut on open: no committed transaction used its key. A
 * {@link #sync()} that failed may leave bytes past the committed ones: the next sync cuts them before it appends, so
 * the file never holds a torn entry before a later one.
 */
final class SymbolTable implements SymbolDictionary, AutoCloseable {

    private static final ValueLayout.OfInt INT_LE = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final int INITIAL_CAPACITY = 16;

    private final Path file;
    // written by the table's writer thread, read by reader threads: a value is stored in the array before the array is
    // published here and before size is raised, so a reader that read size and then this array sees every key below
    private volatile String[] values;
    // written by the table's writer thread, read by reader threads; the release that publishes values[size - 1]
    private volatile int size;
    // the writer's alone: open addressing, key + 1 per slot, 0 empty, power-of-two length
    private int[] slots;
    private int synced;
    // the writer's alone: the file's length after the last sync, what a checkpoint records in _txn
    private long syncedBytes;

    private SymbolTable(final Path file, final String[] values, final int size, final long syncedBytes) {
        this.file = file;
        this.values = values;
        this.size = size;
        this.synced = size;
        this.syncedBytes = syncedBytes;
        this.slots = new int[Integer.highestOneBit(Math.max(INITIAL_CAPACITY, size) * 2) * 2];
        for (int key = 0; key < size; key++) {
            insertSlot(values[key], key);
        }
    }

    /** The dictionary in {@code file}, created empty when missing. */
    @TestOnly
    static SymbolTable open(final Path file) {
        return open(file, -1L);
    }

    /**
     * The dictionary in {@code file}, first cut to {@code committedBytes}, its length at the last checkpoint (-1: not
     * recorded, the file taken as it is): bytes past it were appended unforced and may hold anything, zeros included.
     * A file that holds fewer committed bytes than {@code committedBytes}, or none, lost keys a committed transaction
     * uses: it is refused, as those keys would read as NULL.
     */
    static SymbolTable open(final Path file, final long committedBytes) {
        try {
            if (!Files.exists(file)) {
                if (committedBytes > 0L) {
                    throw lost(file, committedBytes, 0L);
                }
                return new SymbolTable(file, new String[INITIAL_CAPACITY], 0, 0L);
            }
            if (committedBytes >= 0L && Files.size(file) > committedBytes) {
                StoreFiles.truncate(file, committedBytes);
            }
            String[] values = new String[INITIAL_CAPACITY];
            int count = 0;
            long valid = 0L;
            final long bytes;
            try (final Arena arena = Arena.ofConfined()) {
                final MemorySegment segment = StoreFiles.map(file, arena);
                bytes = segment.byteSize();
                while (valid + Integer.BYTES <= bytes) {
                    final int length = segment.get(INT_LE, valid);
                    if (length < 0 || valid + Integer.BYTES + length > bytes) {
                        break;
                    }
                    final byte[] utf8 = new byte[length];
                    for (int i = 0; i < length; i++) {
                        utf8[i] = segment.get(ValueLayout.JAVA_BYTE, valid + Integer.BYTES + i);
                    }
                    if (count == values.length) {
                        values = Arrays.copyOf(values, grow(count));
                    }
                    values[count++] = new String(utf8, StandardCharsets.UTF_8);
                    valid += Integer.BYTES + length;
                }
            }
            if (valid < committedBytes) {
                throw lost(file, committedBytes, valid);
            }
            if (valid < bytes) {
                StoreFiles.truncate(file, valid);
            }
            return new SymbolTable(file, values, count, valid);
        } catch (final IOException e) {
            throw new CdbException("cannot open symbol table [file=" + file + ']', e);
        }
    }

    /** The key of {@code value}, added when new; -1 for null. */
    int intern(final String value) {
        if (value == null) {
            return -1;
        }
        final int mask = slots.length - 1;
        int slot = mix(value.hashCode()) & mask;
        final String[] current = values;
        while (slots[slot] != 0) {
            final int key = slots[slot] - 1;
            if (current[key].equals(value)) {
                return key;
            }
            slot = (slot + 1) & mask;
        }
        final int key = size;
        String[] target = current;
        if (key == target.length) {
            target = Arrays.copyOf(target, grow(key));
        }
        target[key] = value;
        values = target;
        size = key + 1;
        slots[slot] = key + 1;
        if ((key + 1) * 2 > slots.length) {
            rehash();
        }
        return key;
    }

    /** The value of {@code key}; null for -1. */
    String value(final int key) {
        if (key < 0) {
            return null;
        }
        return values[key];
    }

    /** The value of {@code key}, a key a committed version holds; from any thread, as {@link #values()}. */
    @Override
    public String valueOf(final int key) {
        return values[key];
    }

    /**
     * The values by key, read only: every key a committed version holds resolves in it, so a reader resolves a run of
     * keys without a volatile read per key. Safe from any thread: the array is published after its values.
     */
    String[] values() {
        return values;
    }

    /**
     * The key of {@code value} for a reader, from any thread, without adding it: -1 when the dictionary does not hold
     * it, or for null. A linear scan over the keys; a {@link Version} keeps the answer per column, so it runs once per
     * constant, not per row.
     */
    @Override
    public int keyOf(final String value) {
        if (value == null) {
            return -1;
        }
        // size before values: the array read after it holds every key below it
        final int upTo = size;
        final String[] current = values;
        for (int key = 0; key < upTo; key++) {
            if (current[key].equals(value)) {
                return key;
            }
        }
        return -1;
    }

    @Override
    public int size() {
        return size;
    }

    /** Makes every key added so far durable; called before the transaction that uses them is committed. */
    void sync() {
        final int upTo = size;
        if (upTo == synced) {
            return;
        }
        final String[] current = values;
        final byte[][] encoded = new byte[upTo - synced][];
        int bytes = 0;
        for (int key = synced; key < upTo; key++) {
            final byte[] utf8 = current[key].getBytes(StandardCharsets.UTF_8);
            encoded[key - synced] = utf8;
            bytes += Integer.BYTES + utf8.length;
        }
        final ByteBuffer buffer = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (final byte[] utf8 : encoded) {
            buffer.putInt(utf8.length).put(utf8);
        }
        buffer.flip();
        try {
            // bytes of an earlier sync that failed after writing part of its keys: cut, or this one lands behind them
            if (Files.exists(file) && Files.size(file) > syncedBytes) {
                StoreFiles.truncate(file, syncedBytes);
            }
            StoreFiles.append(file, buffer);
        } catch (final IOException e) {
            throw new CdbException("cannot sync symbol table [file=" + file + ", keys=" + upTo + ']', e);
        }
        synced = upTo;
        syncedBytes += bytes;
    }

    /** The dictionary's file; missing until the first {@link #sync()} with a key to write. */
    Path file() {
        return file;
    }

    /** The file's length after the last {@link #sync()}: every key below the synced ones, durable. */
    long syncedBytes() {
        return syncedBytes;
    }

    /** Holds no file open between syncs: nothing to release. */
    @Override
    public void close() {
    }

    private static CdbException lost(final Path file, final long committedBytes, final long validBytes) {
        return new CdbException("refuse symbol file shorter than its committed keys [file=" + file + ", bytes="
                + validBytes + ", committedBytes=" + committedBytes + ']', -1);
    }

    private static int grow(final int length) {
        if (length >= Integer.MAX_VALUE / 2) {
            throw new CdbException("refuse symbol table over capacity [keys=" + length + ']', -1);
        }
        return length * 2;
    }

    private static int mix(final int hash) {
        final int h = hash * 0x9E3779B9;
        return h ^ (h >>> 16);
    }

    private void rehash() {
        slots = new int[slots.length * 2];
        final String[] current = values;
        for (int key = 0; key < size; key++) {
            insertSlot(current[key], key);
        }
    }

    private void insertSlot(final String value, final int key) {
        final int mask = slots.length - 1;
        int slot = mix(value.hashCode()) & mask;
        while (slots[slot] != 0) {
            slot = (slot + 1) & mask;
        }
        slots[slot] = key + 1;
    }
}
