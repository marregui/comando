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

import dev.marregui.comando.cdb.CdbException;

/**
 * The dictionary of one SYMBOL column of a table: each distinct value an int key, 0 upward, in first-seen order, kept
 * in one file, appended. Key -1 is NULL. One writer (the table's) appends; readers resolve keys below the size they
 * read at their snapshot, from any thread.
 * <p>
 * The file holds one entry per key, in key order: the value's UTF-8 length as a little-endian int, then its bytes. A
 * torn last entry, left by a crash during {@link #sync()}, is cut on open: no committed transaction used its key.
 */
final class SymbolTable implements AutoCloseable {

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

    private SymbolTable(final Path file, final String[] values, final int size) {
        this.file = file;
        this.values = values;
        this.size = size;
        this.synced = size;
        this.slots = new int[Integer.highestOneBit(Math.max(INITIAL_CAPACITY, size) * 2) * 2];
        for (int key = 0; key < size; key++) {
            insertSlot(values[key], key);
        }
    }

    /** The dictionary in {@code file}, created empty when missing. */
    static SymbolTable open(final Path file) {
        try {
            if (!Files.exists(file)) {
                return new SymbolTable(file, new String[INITIAL_CAPACITY], 0);
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
            if (valid < bytes) {
                StoreFiles.truncate(file, valid);
            }
            return new SymbolTable(file, values, count);
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

    /** The number of keys. */
    int size() {
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
            StoreFiles.append(file, buffer);
        } catch (final IOException e) {
            throw new CdbException("cannot sync symbol table [file=" + file + ", keys=" + upTo + ']', e);
        }
        synced = upTo;
    }

    /** Holds no file open between syncs: nothing to release. */
    @Override
    public void close() {
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
