package dev.marregui.comando.cdb.exec;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The text values of one {@link Vector}, SYMBOL or VARCHAR: UTF-8 bytes (an unpaired surrogate as the 3 bytes of its
 * code unit) appended to one growable array, each value a start and a length into it (NULL a length of -1), and for
 * SYMBOL a dictionary key per value. The first
 * {@link #setSymbol} binds the column's one {@link SymbolDictionary}; a value of another dictionary, or one set as
 * text, is held as bytes with {@link Vector#NO_KEY}. A rewritten value leaves its old bytes behind, and a copy within
 * the column shares its source's bytes; both are let go when the array is full and the live values are copied out.
 * Order and hash are {@link String#compareTo}'s and {@link String#hashCode}'s: UTF-8 byte order is code point order,
 * which differs from UTF-16 order only between a char of U+E000 to U+FFFF and a supplementary char.
 */
final class TextColumn {

    private static final int MIN_BYTES = 64; // sizing hint for the first allocation
    private static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8; // the largest array size every JVM allocates
    private static final byte[] NO_BYTES = {};
    private static final int NULL_LENGTH = -1;
    private static final ValueLayout.OfInt INT_LE = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    // a page is allocated for its full capacity and often holds a few rows: the per-value arrays start small and grow
    // to the capacity as values are written; an index past them reads NULL
    private static final int FIRST_VALUES = 16;

    private final int capacity;
    private int[] keys; // null for VARCHAR
    private int[] lengths;
    private int[] starts;
    private byte[] bytes = NO_BYTES;
    private SymbolDictionary dictionary;
    private int used;

    TextColumn(final int capacity, final boolean symbol) {
        this(capacity, symbol, false);
    }

    /** {@code full}: the per-value arrays sized for every value at once, for a column that is filled. */
    TextColumn(final int capacity, final boolean symbol, final boolean full) {
        this.capacity = capacity;
        final int first = full ? capacity : Math.min(capacity, FIRST_VALUES);
        lengths = new int[first];
        starts = new int[first];
        Arrays.fill(lengths, NULL_LENGTH);
        if (symbol) {
            keys = new int[first];
            Arrays.fill(keys, Vector.NO_KEY);
        } else {
            keys = null;
        }
    }

    int capacity() {
        return capacity;
    }

    /** Copies value {@code from} of {@code source}, which may be this column, to index {@code to}. */
    void copy(final TextColumn source, final int from, final int to) {
        if (source == this) {
            ensure(Math.max(from, to));
            starts[to] = starts[from];
            lengths[to] = lengths[from];
            if (keys != null) {
                keys[to] = keys[from];
            }
            return;
        }
        if (source.isNull(from)) {
            setNull(to);
            return;
        }
        final int length = source.lengths[from];
        final int key = source.keyAt(from);
        if (key != Vector.NO_KEY) {
            setSymbol(to, source.dictionary, key);
            return;
        }
        setNull(to);
        final int at = reserve(length);
        System.arraycopy(source.bytes, source.starts[from], bytes, at, length);
        starts[to] = at;
        lengths[to] = length;
    }

    /** Copies {@code n} values of {@code source} from {@code from} to {@code to}; ranges in one column may overlap. */
    void copy(final TextColumn source, final int from, final int to, final int n) {
        if (n <= 0) {
            // System.arraycopy checks the positions even for no values, and the per-value arrays grow only on write
            return;
        }
        if (source == this) {
            ensure(Math.max(from, to) + n - 1);
            System.arraycopy(starts, from, starts, to, n);
            System.arraycopy(lengths, from, lengths, to, n);
            if (keys != null) {
                System.arraycopy(keys, from, keys, to, n);
            }
            return;
        }
        for (int k = 0; k < n; k++) {
            copy(source, from + k, to + k);
        }
    }

    /** Exchanges values {@code i} and {@code j}; their bytes stay where they are. */
    void swap(final int i, final int j) {
        ensure(Math.max(i, j));
        final int start = starts[i];
        starts[i] = starts[j];
        starts[j] = start;
        final int length = lengths[i];
        lengths[i] = lengths[j];
        lengths[j] = length;
        if (keys != null) {
            final int key = keys[i];
            keys[i] = keys[j];
            keys[j] = key;
        }
    }

    boolean isNull(final int i) {
        return i >= lengths.length || lengths[i] == NULL_LENGTH;
    }

    void setNull(final int i) {
        ensure(i);
        lengths[i] = NULL_LENGTH;
        if (keys != null) {
            keys[i] = Vector.NO_KEY;
        }
    }

    /**
     * Sets value {@code i} to {@code key} of {@code symbols}; held as the key when this is a SYMBOL column whose
     * dictionary is {@code symbols} or not yet bound (then it binds), else as the value's bytes.
     */
    void setSymbol(final int i, final SymbolDictionary symbols, final int key) {
        assert key >= 0 : "a dictionary key is never negative";
        if (keys != null && (dictionary == null || dictionary == symbols)) {
            ensure(i);
            dictionary = symbols;
            keys[i] = key;
            starts[i] = 0;
            lengths[i] = 0;
        } else {
            setText(i, symbols.valueOf(key));
        }
    }

    /** Sets value {@code i}; null sets NULL. */
    void setText(final int i, final String value) {
        setNull(i);
        if (value == null) {
            return;
        }
        final int length = utf8Length(value);
        final int at = reserve(length);
        encode(value, bytes, at);
        starts[i] = at;
        lengths[i] = length;
    }

    /** Sets value {@code i} to a copy of the {@code length} UTF-8 bytes of {@code data} at {@code start}. */
    void setText(final int i, final MemorySegment data, final long start, final int length) {
        setNull(i);
        final int at = reserve(length);
        MemorySegment.copy(data, ValueLayout.JAVA_BYTE, start, bytes, at, length);
        starts[i] = at;
        lengths[i] = length;
    }

    /** {@link Vector#readSymbols}. */
    void readSymbols(
            final int at,
            final MemorySegment src,
            final long offset,
            final int n,
            final SymbolDictionary symbols
    ) {
        if (n <= 0) {
            return;
        }
        if (keys == null || dictionary != null && dictionary != symbols) {
            for (int k = 0; k < n; k++) {
                final int key = src.get(INT_LE, offset + (long) k * Integer.BYTES);
                if (key < 0) {
                    setNull(at + k);
                } else {
                    setSymbol(at + k, symbols, key);
                }
            }
            return;
        }
        ensure(at + n - 1);
        dictionary = symbols;
        MemorySegment.copy(src, INT_LE, offset, keys, at, n);
        for (int i = at, end = at + n; i < end; i++) {
            final boolean isNull = keys[i] < 0;
            if (isNull) {
                keys[i] = Vector.NO_KEY;
            }
            starts[i] = 0;
            lengths[i] = isNull ? NULL_LENGTH : 0;
        }
    }

    /** Empties the column for a refill: the bytes let go, the dictionary unbound, every value NULL, the arrays kept. */
    void clear() {
        used = 0;
        dictionary = null;
        Arrays.fill(lengths, NULL_LENGTH);
        if (keys != null) {
            Arrays.fill(keys, Vector.NO_KEY);
        }
    }

    /** Overwrites values {@code 0} to {@code n - 1} with a SYMBOL key past any dictionary, or a VARCHAR of no bytes. */
    void poison(final int n) {
        if (n > 0) {
            ensure(n - 1);
        }
        for (int i = 0; i < n; i++) {
            if (keys != null) {
                keys[i] = Integer.MAX_VALUE;
            }
            starts[i] = 0;
            lengths[i] = 0;
        }
    }

    /** The dictionary of {@link #symbolKey}'s keys, null until the first keyed {@link #setSymbol}. */
    SymbolDictionary symbolDictionary() {
        return dictionary;
    }

    int symbolKey(final int i) {
        return keyAt(i);
    }

    /** Value {@code i} as a String, null when NULL; decodes, so call it at output. */
    String textAt(final int i) {
        if (isNull(i)) {
            return null;
        }
        final int length = lengths[i];
        final int key = keyAt(i);
        if (key != Vector.NO_KEY) {
            return dictionary.valueOf(key);
        }
        return decodeString(bytes, starts[i], length);
    }

    /** The sign of {@link String#compareTo} of the non-NULL values {@code i} and {@code j} of {@code other}. */
    int textCompare(final int i, final TextColumn other, final int j) {
        final int a = keyAt(i);
        final int b = other.keyAt(j);
        if (a != Vector.NO_KEY && b != Vector.NO_KEY) {
            if (a == b && dictionary == other.dictionary) {
                return 0;
            }
            return Integer.signum(dictionary.valueOf(a).compareTo(other.dictionary.valueOf(b)));
        }
        if (a != Vector.NO_KEY) {
            return -compare(other.bytes, other.starts[j], other.lengths[j], dictionary.valueOf(a));
        }
        if (b != Vector.NO_KEY) {
            return compare(bytes, starts[i], lengths[i], other.dictionary.valueOf(b));
        }
        return compare(bytes, starts[i], lengths[i], other.bytes, other.starts[j], other.lengths[j]);
    }

    /** The sign of {@link String#compareTo} of the non-NULL value {@code i} and the non-null {@code value}. */
    int textCompare(final int i, final String value) {
        final int key = keyAt(i);
        if (key != Vector.NO_KEY) {
            return Integer.signum(dictionary.valueOf(key).compareTo(value));
        }
        return compare(bytes, starts[i], lengths[i], value);
    }

    /** True when the non-NULL values {@code i} and {@code j} of {@code other} are equal; by key in one dictionary. */
    boolean textEquals(final int i, final TextColumn other, final int j) {
        final int a = keyAt(i);
        final int b = other.keyAt(j);
        if (a != Vector.NO_KEY && b != Vector.NO_KEY) {
            return dictionary == other.dictionary ? a == b : dictionary.valueOf(a).equals(other.dictionary.valueOf(b));
        }
        if (a != Vector.NO_KEY) {
            return other.textEquals(j, dictionary.valueOf(a));
        }
        if (b != Vector.NO_KEY) {
            return textEquals(i, other.dictionary.valueOf(b));
        }
        final int length = lengths[i];
        final int start = starts[i];
        final int otherStart = other.starts[j];
        return length == other.lengths[j]
            && Arrays.equals(bytes, start, start + length, other.bytes, otherStart, otherStart + length);
    }

    /** True when the non-NULL value {@code i} equals the non-null {@code value}. */
    boolean textEquals(final int i, final String value) {
        final int key = keyAt(i);
        if (key != Vector.NO_KEY) {
            return dictionary.valueOf(key).equals(value);
        }
        // every UTF-16 char takes at least one UTF-8 byte, and at most three
        final int length = lengths[i];
        final int chars = value.length();
        return length >= chars && length <= 3L * chars && compare(bytes, starts[i], length, value) == 0;
    }

    /** {@link String#hashCode} of the non-NULL value {@code i}, computed from its bytes without allocating. */
    int textHash(final int i) {
        final int key = keyAt(i);
        if (key != Vector.NO_KEY) {
            return dictionary.valueOf(key).hashCode();
        }
        final byte[] a = bytes;
        final int end = starts[i] + lengths[i];
        int h = 0;
        int p = starts[i];
        while (p < end) {
            final int lead = a[p] & 0xFF;
            if (lead < 0x80) {
                h = 31 * h + lead;
                p++;
                continue;
            }
            final int cp = decode(a, p);
            p += width(lead);
            if (Character.isBmpCodePoint(cp)) {
                h = 31 * h + cp;
            } else {
                h = 31 * (31 * h + Character.highSurrogate(cp)) + Character.lowSurrogate(cp);
            }
        }
        return h;
    }

    /** The sign of {@link String#compareTo} of two UTF-8 byte ranges, with a byte-wise fast path. */
    private static int compare(
        final byte[] a,
        final int aStart,
        final int aLength,
        final byte[] b,
        final int bStart,
        final int bLength
    ) {
        final int m = Arrays.mismatch(a, aStart, aStart + aLength, b, bStart, bStart + bLength);
        if (m < 0) {
            return 0;
        }
        if (m == aLength || m == bLength) {
            // a byte prefix is a char prefix: the shorter is smaller
            return m == aLength ? -1 : 1;
        }
        final int x = a[aStart + m] & 0xFF;
        final int y = b[bStart + m] & 0xFF;
        if (x < 0x80 && y < 0x80) {
            return x < y ? -1 : 1;
        }
        // the bytes before m are equal, so both code points that differ start at the same offset
        int p = m;
        while (p > 0 && (a[aStart + p] & 0xC0) == 0x80) {
            p--;
        }
        final int cx = decode(a, aStart + p);
        final int cy = decode(b, bStart + p);
        final int order = utf16Compare(cx, cy);
        if (order != 0) {
            return order;
        }
        // an unpaired high surrogate, three bytes, against the pair it starts
        return Character.isBmpCodePoint(cx)
            ? -pairAgainstUnpaired(cy, firstUnit(a, aStart + p + 3, aStart + aLength))
            : pairAgainstUnpaired(cx, firstUnit(b, bStart + p + 3, bStart + bLength));
    }

    /** The sign of {@link String#compareTo} of a UTF-8 byte range and {@code s}, with an ASCII fast path. */
    private static int compare(final byte[] a, final int start, final int length, final String s) {
        final int end = start + length;
        final int chars = s.length();
        int p = start;
        int q = 0;
        while (p < end && q < chars) {
            final int lead = a[p] & 0xFF;
            final char c = s.charAt(q);
            if (lead < 0x80 && c < 0x80) {
                if (lead != c) {
                    return lead < c ? -1 : 1;
                }
                p++;
                q++;
                continue;
            }
            final int x = decode(a, p);
            final int y = s.codePointAt(q);
            if (x != y) {
                final int order = utf16Compare(x, y);
                if (order != 0) {
                    return order;
                }
                return Character.isBmpCodePoint(x)
                    ? -pairAgainstUnpaired(y, firstUnit(a, p + 3, end))
                    : pairAgainstUnpaired(x, q + 1 < chars ? s.charAt(q + 1) : -1);
            }
            p += width(lead);
            q += Character.charCount(y);
        }
        return Integer.compare(p < end ? 1 : 0, q < chars ? 1 : 0);
    }

    // the bytes of an unpaired surrogate (0xED then 0xA0 to 0xBF) are not UTF-8, which the JDK's decoder replaces
    private static String decodeString(final byte[] a, final int start, final int length) {
        final int end = start + length;
        boolean surrogate = false;
        for (int p = start; p + 1 < end; p++) {
            if (a[p] == (byte) 0xED && (a[p + 1] & 0xFF) >= 0xA0) {
                surrogate = true;
                break;
            }
        }
        if (!surrogate) {
            return new String(a, start, length, StandardCharsets.UTF_8);
        }
        final StringBuilder out = new StringBuilder(length);
        for (int p = start; p < end; p += width(a[p] & 0xFF)) {
            out.appendCodePoint(decode(a, p));
        }
        return out.toString();
    }

    private static int decode(final byte[] a, final int p) {
        final int lead = a[p] & 0xFF;
        if (lead < 0x80) {
            return lead;
        }
        if (lead < 0xE0) {
            return (lead & 0x1F) << 6 | a[p + 1] & 0x3F;
        }
        if (lead < 0xF0) {
            return (lead & 0x0F) << 12 | (a[p + 1] & 0x3F) << 6 | a[p + 2] & 0x3F;
        }
        return (lead & 0x07) << 18 | (a[p + 1] & 0x3F) << 12 | (a[p + 2] & 0x3F) << 6 | a[p + 3] & 0x3F;
    }

    /**
     * Writes {@code value} as UTF-8, an unpaired surrogate as the three bytes of its code unit (as CESU-8 and WTF-8
     * do), so a value split inside a pair (a {@code substring}) keeps its chars and joins back whole.
     */
    private static void encode(final String value, final byte[] a, final int at) {
        final int chars = value.length();
        int p = at;
        for (int q = 0; q < chars; q++) {
            final char c = value.charAt(q);
            if (c < 0x80) {
                a[p++] = (byte) c;
            } else if (c < 0x800) {
                a[p++] = (byte) (0xC0 | c >> 6);
                a[p++] = (byte) (0x80 | c & 0x3F);
            } else if (Character.isSurrogate(c)) {
                if (startsPair(value, q)) {
                    final int cp = Character.toCodePoint(c, value.charAt(++q));
                    a[p++] = (byte) (0xF0 | cp >> 18);
                    a[p++] = (byte) (0x80 | cp >> 12 & 0x3F);
                    a[p++] = (byte) (0x80 | cp >> 6 & 0x3F);
                    a[p++] = (byte) (0x80 | cp & 0x3F);
                } else {
                    a[p++] = (byte) (0xE0 | c >> 12);
                    a[p++] = (byte) (0x80 | c >> 6 & 0x3F);
                    a[p++] = (byte) (0x80 | c & 0x3F);
                }
            } else {
                a[p++] = (byte) (0xE0 | c >> 12);
                a[p++] = (byte) (0x80 | c >> 6 & 0x3F);
                a[p++] = (byte) (0x80 | c & 0x3F);
            }
        }
    }

    /**
     * Compares code points {@code x != y} in UTF-16 order by their first units: a supplementary char's is a high
     * surrogate, which sorts before U+E000 to U+FFFF. 0 when they are equal, an unpaired high surrogate against a pair
     * it starts, which the unit after the unpaired one decides ({@link #pairAgainstUnpaired}).
     */
    private static int utf16Compare(final int x, final int y) {
        final boolean xSupplementary = !Character.isBmpCodePoint(x);
        if (xSupplementary == !Character.isBmpCodePoint(y)) {
            return x < y ? -1 : 1;
        }
        final int ux = xSupplementary ? Character.highSurrogate(x) : x;
        final int uy = xSupplementary ? y : Character.highSurrogate(y);
        return Integer.compare(ux, uy);
    }

    /**
     * The sign of supplementary code point {@code pair} against an unpaired high surrogate equal to its first unit and
     * followed by unit {@code next}, -1 at the end of its value: the pair's low unit against {@code next}. Never equal,
     * as a low surrogate written right after a high one is a pair.
     */
    private static int pairAgainstUnpaired(final int pair, final int next) {
        return next < Character.lowSurrogate(pair) ? 1 : -1;
    }

    /** The first UTF-16 unit of the code point at byte {@code p}, -1 at {@code end}. */
    private static int firstUnit(final byte[] a, final int p, final int end) {
        if (p >= end) {
            return -1;
        }
        final int cp = decode(a, p);
        return Character.isBmpCodePoint(cp) ? cp : Character.highSurrogate(cp);
    }

    /** True when chars {@code q} and {@code q + 1} of {@code value} are a surrogate pair. */
    private static boolean startsPair(final String value, final int q) {
        return Character.isHighSurrogate(value.charAt(q))
            && q + 1 < value.length()
            && Character.isLowSurrogate(value.charAt(q + 1));
    }

    private static int utf8Length(final String value) {
        final int chars = value.length();
        int length = chars;
        for (int q = 0; q < chars; q++) {
            final char c = value.charAt(q);
            if (c >= 0x80) {
                if (c < 0x800) {
                    length += 1;
                } else if (Character.isSurrogate(c)) {
                    if (startsPair(value, q)) {
                        length += 2; // two chars, four bytes
                        q++;
                    } else {
                        length += 2; // an unpaired surrogate, three bytes
                    }
                } else {
                    length += 2;
                }
            }
        }
        return length;
    }

    private static int width(final int lead) {
        return lead < 0x80 ? 1 : lead < 0xE0 ? 2 : lead < 0xF0 ? 3 : 4;
    }

    private int keyAt(final int i) {
        return keys == null || i >= keys.length ? Vector.NO_KEY : keys[i];
    }

    // room in the per-value arrays for index i, which is below the capacity; new slots are NULL
    private void ensure(final int i) {
        final int had = lengths.length;
        if (i < had) {
            return;
        }
        if (i >= capacity) {
            throw new ArrayIndexOutOfBoundsException("text index past the capacity [index=" + i + ", capacity="
                    + capacity + ']');
        }
        final int size = (int) Math.min(capacity, Math.max(i + 1L, 2L * had));
        lengths = Arrays.copyOf(lengths, size);
        Arrays.fill(lengths, had, size, NULL_LENGTH);
        starts = Arrays.copyOf(starts, size);
        if (keys != null) {
            keys = Arrays.copyOf(keys, size);
            Arrays.fill(keys, had, size, Vector.NO_KEY);
        }
    }

    /** Room for {@code needed} more bytes at the returned offset; a full array gives way to one of the live values. */
    private int reserve(final int needed) {
        if (needed < 0) {
            throw new IllegalArgumentException("text length is negative [length=" + needed + "]");
        }
        if (needed > bytes.length - used) {
            long live = 0;
            for (int s = 0; s < lengths.length; s++) {
                if (lengths[s] > 0 && keyAt(s) == Vector.NO_KEY) {
                    live += lengths[s];
                }
            }
            final long target = live + needed;
            if (target > MAX_ARRAY_SIZE) {
                throw new IllegalStateException(
                    "text column is full [needed=" + needed + ", live=" + live + ", max=" + MAX_ARRAY_SIZE + "]");
            }
            // the first array holds what is asked, at least MIN_BYTES: a page of a few rows keeps a small one
            final long doubled = Math.max((long) bytes.length << 1, MIN_BYTES);
            final long size = target <= bytes.length >> 1 ? bytes.length : Math.max(doubled, target);
            compact((int) Math.min(size, MAX_ARRAY_SIZE));
        }
        final int at = used;
        used += needed;
        return at;
    }

    /** Moves the live values' bytes to the start of a new array of {@code size} bytes. */
    private void compact(final int size) {
        final byte[] next = new byte[size];
        int at = 0;
        for (int s = 0; s < lengths.length; s++) {
            final int length = lengths[s];
            if (length > 0 && keyAt(s) == Vector.NO_KEY) {
                System.arraycopy(bytes, starts[s], next, at, length);
                starts[s] = at;
                at += length;
            }
        }
        bytes = next;
        used = at;
    }
}
