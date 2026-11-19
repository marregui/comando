package dev.marregui.comando.cdb.exec;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// [TextColumn] answers what [String] answers for equality, order and hash, whichever way a value arrived: as a
/// String, as UTF-8 bytes, as a key of the column's dictionary or of another one, or as a copy.
class TextColumnTest {

    private static final int NO_SCRATCH = -1;
    private static final int PAIRS = 20_000;
    private static final int SLOTS = 8;
    private static final int STEPS = 3_000;
    // the last slot carries copies within a column; the others hold the values under test
    private static final int SCRATCH = SLOTS - 1;
    private static final int VALUE_SLOTS = SLOTS - 1;

    /// Random pairs over ASCII, Latin-1, CJK, U+E000 to U+FFFF and supplementary chars, empty strings and NULLs, put
    /// in columns bound to one dictionary, to another, and in a VARCHAR column, compare as their Strings do.
    @Test
    void randomPairsMatchString() {
        final Random random = ReferenceCursors.random("randomPairsMatchString");
        final Dictionary first = new Dictionary();
        final Dictionary second = new Dictionary();
        final TextColumn[] columns = {
            bound(first), bound(first), bound(second), new TextColumn(SLOTS, false),
        };
        for (int n = 0; n < PAIRS; n++) {
            final String a = string(random);
            final String b = partner(random, a);
            final int c1 = random.nextInt(columns.length);
            final int c2 = random.nextInt(columns.length);
            final int s1 = random.nextInt(VALUE_SLOTS);
            final int s2 = c1 == c2
                ? (s1 + 1 + random.nextInt(VALUE_SLOTS - 1)) % VALUE_SLOTS
                : random.nextInt(VALUE_SLOTS);
            put(random, columns[c1], s1, a, first, second, SCRATCH);
            put(random, columns[c2], s2, b, first, second, SCRATCH);
            check(columns[c1], s1, a, columns[c2], s2, b);
        }
    }

    /// Two values keyed in one dictionary compare and test equal by key, without resolving a value.
    @Test
    void keysOfOneDictionaryCompareByKey() {
        final Dictionary dictionary = new Dictionary();
        final TextColumn left = new TextColumn(2, true);
        final TextColumn right = new TextColumn(2, true);
        left.setSymbol(0, dictionary, dictionary.key("alpha"));
        right.setSymbol(0, dictionary, dictionary.key("alpha"));
        right.setSymbol(1, dictionary, dictionary.key("beta"));
        dictionary.resolved = 0;

        assertThat(left.textEquals(0, right, 0)).isTrue();
        assertThat(left.textEquals(0, right, 1)).isFalse();
        assertThat(left.textCompare(0, right, 0)).isZero();
        assertThat(dictionary.resolved).isZero();
        assertThat(left.symbolKey(0)).isEqualTo(right.symbolKey(0));
        assertThat(left.symbolDictionary()).isSameAs(dictionary);
    }

    /// A key of a second dictionary, or a SYMBOL set as text, is held as bytes with no key; the bound dictionary stays.
    @Test
    void valuesOfAnotherDictionaryAreHeldAsBytes() {
        final Dictionary first = new Dictionary();
        final Dictionary second = new Dictionary();
        final TextColumn column = new TextColumn(3, true);
        column.setSymbol(0, first, first.key("x"));
        column.setSymbol(1, second, second.key("y"));
        column.setText(2, "x");

        assertThat(column.symbolKey(0)).isEqualTo(first.key("x"));
        assertThat(column.symbolKey(1)).isEqualTo(Vector.NO_KEY);
        assertThat(column.symbolKey(2)).isEqualTo(Vector.NO_KEY);
        assertThat(column.symbolDictionary()).isSameAs(first);
        assertThat(column.textAt(1)).isEqualTo("y");
        assertThat(column.textEquals(0, column, 2)).isTrue();
    }

    /// A VARCHAR column holds a key it is given as the value's bytes.
    @Test
    void varcharColumnCarriesNoKeys() {
        final Dictionary dictionary = new Dictionary();
        final TextColumn column = new TextColumn(1, false);
        column.setSymbol(0, dictionary, dictionary.key("v"));

        assertThat(column.symbolKey(0)).isEqualTo(Vector.NO_KEY);
        assertThat(column.symbolDictionary()).isNull();
        assertThat(column.textAt(0)).isEqualTo("v");
    }

    /// A char of U+E000 to U+FFFF sorts after a supplementary char, as in UTF-16, though its UTF-8 bytes sort before.
    @Test
    void privateUseSortsAfterSupplementaryAsInUtf16() {
        final String high = "a" + Character.toString(0xE000);
        final String supplementary = "a" + Character.toString(0x10000);
        final TextColumn column = new TextColumn(2, false);
        column.setText(0, high);
        column.setText(1, supplementary);

        assertThat(high.compareTo(supplementary)).isPositive();
        assertThat(column.textCompare(0, column, 1)).isPositive();
        assertThat(column.textCompare(1, column, 0)).isNegative();
        assertThat(column.textCompare(0, supplementary)).isPositive();
        assertThat(column.textCompare(1, high)).isNegative();
    }

    /// The two halves of a supplementary char, each an unpaired surrogate (as `substring` makes them), read back as
    /// themselves, not `?`, equal and hash as their Strings, and compare to `?` and to the whole char as Strings do.
    @Test
    void unpairedSurrogatesKeepTheirChars() {
        final String whole = Character.toString(0x1F600);
        final String high = whole.substring(0, 1);
        final String low = whole.substring(1);
        final TextColumn column = new TextColumn(3, false);
        column.setText(0, high);
        column.setText(1, low);
        column.setText(2, "?");

        assertThat(column.textAt(0)).isEqualTo(high);
        assertThat(column.textAt(0) + column.textAt(1)).isEqualTo(whole);
        assertThat(column.textEquals(0, high)).isTrue();
        assertThat(column.textEquals(0, column, 2)).isFalse();
        assertThat(column.textHash(1)).isEqualTo(low.hashCode());
        assertThat(Integer.signum(column.textCompare(0, column, 2))).isEqualTo(Integer.signum(high.compareTo("?")));
        assertThat(Integer.signum(column.textCompare(1, whole))).isEqualTo(Integer.signum(low.compareTo(whole)));
    }

    /// An unpaired high surrogate against the pair it starts compares as Strings do, by the unit after it against the
    /// pair's low unit: U+FF71 sorts after the low surrogate U+DC00, `a` before it, and the end of the value before
    /// both. Column against column and column against String, either way round.
    @Test
    void anUnpairedHighSurrogateAgainstItsPairComparesByTheNextUnit() {
        final String pair = Character.toString(0x10000);
        final String high = String.valueOf((char) 0xD800);
        final List<String> unpaired = List.of(high + (char) 0xFF71, high + "a", high);
        final TextColumn column = new TextColumn(2, false);
        column.setText(0, pair);
        for (final String value : unpaired) {
            column.setText(1, value);
            final int sign = Integer.signum(value.compareTo(pair));
            assertThat(Integer.signum(column.textCompare(1, column, 0))).as(value).isEqualTo(sign);
            assertThat(Integer.signum(column.textCompare(0, column, 1))).as(value).isEqualTo(-sign);
            assertThat(Integer.signum(column.textCompare(1, pair))).as(value).isEqualTo(sign);
            assertThat(Integer.signum(column.textCompare(0, value))).as(value).isEqualTo(-sign);
        }
        assertThat(unpaired.getFirst().compareTo(pair)).isPositive();
    }

    /// A SYMBOL value copied into a VARCHAR column keeps its value as bytes, and a VARCHAR value copied into a SYMBOL
    /// column keeps its value with no key, one at a time and in bulk.
    @Test
    void copiesBetweenSymbolAndVarcharKeepTheValue() {
        final Dictionary dictionary = new Dictionary();
        final TextColumn symbol = new TextColumn(3, true);
        final TextColumn varchar = new TextColumn(3, false);
        symbol.setSymbol(0, dictionary, dictionary.key("keyed"));
        symbol.setText(1, "bytes");
        varchar.setText(2, "varchar");

        varchar.copy(symbol, 0, 0);
        varchar.copy(symbol, 1, 1);
        symbol.copy(varchar, 2, 2);
        assertThat(varchar.textAt(0)).isEqualTo("keyed");
        assertThat(varchar.symbolKey(0)).isEqualTo(Vector.NO_KEY);
        assertThat(varchar.textEquals(0, symbol, 0)).isTrue();
        assertThat(varchar.textAt(1)).isEqualTo("bytes");
        assertThat(symbol.textAt(2)).isEqualTo("varchar");
        assertThat(symbol.symbolKey(2)).isEqualTo(Vector.NO_KEY);

        final TextColumn bulk = new TextColumn(3, false);
        bulk.copy(symbol, 0, 0, 3);
        final TextColumn back = new TextColumn(3, true);
        back.copy(bulk, 0, 0, 3);
        assertThat(new String[] {bulk.textAt(0), bulk.textAt(1), bulk.textAt(2)})
            .containsExactly("keyed", "bytes", "varchar");
        assertThat(new String[] {back.textAt(0), back.textAt(1), back.textAt(2)})
            .containsExactly("keyed", "bytes", "varchar");
    }

    /// Values set from bytes are copies: a later write to the source does not change them.
    @Test
    void bytesAreCopiedOnSet() {
        final byte[] source = "héllo".getBytes(StandardCharsets.UTF_8);
        final TextColumn column = new TextColumn(1, false);
        column.setText(0, MemorySegment.ofArray(source), 0, source.length);
        source[0] = 'j';

        assertThat(column.textAt(0)).isEqualTo("héllo");
    }

    /// A new column is all NULL; NULL set, copied and rewritten reads back as NULL, then as the new value.
    @Test
    void nullsSetCopyAndClear() {
        final Dictionary dictionary = new Dictionary();
        final TextColumn column = new TextColumn(3, true);
        final TextColumn other = new TextColumn(3, true);
        assertThat(column.isNull(0)).isTrue();
        assertThat(column.textAt(0)).isNull();

        column.setSymbol(0, dictionary, dictionary.key("k"));
        column.setNull(0);
        assertThat(column.isNull(0)).isTrue();
        assertThat(column.symbolKey(0)).isEqualTo(Vector.NO_KEY);

        column.setText(1, "v");
        column.copy(other, 2, 1);
        assertThat(column.isNull(1)).isTrue();

        column.setText(2, null);
        assertThat(column.isNull(2)).isTrue();
        column.setText(2, "");
        assertThat(column.isNull(2)).isFalse();
        assertThat(column.textAt(2)).isEmpty();
        assertThat(column.textHash(2)).isZero();
    }

    /// A copy of no values at a position past the per-value arrays' first size, which grow only on write, changes
    /// nothing and does not fail, within one column and from another.
    @Test
    void copyingNoValuesPastTheGrownArraysDoesNothing() {
        final int capacity = 32;
        final TextColumn column = new TextColumn(capacity, true);
        final TextColumn other = new TextColumn(capacity, true);
        column.copy(column, capacity - 1, 0, 0);
        column.copy(other, capacity - 1, capacity - 1, 0);
        assertThat(column.isNull(0)).isTrue();
        assertThat(column.isNull(capacity - 1)).isTrue();
    }

    /// Overlapping copies within one column, copies from another, and rewrites that fill the byte array and make it
    /// reclaim, leave every slot what an array of Strings moved the same way holds.
    @Test
    void overlappingCopiesAndRewritesKeepEveryValue() {
        final Random random = ReferenceCursors.random("overlappingCopiesAndRewritesKeepEveryValue");
        final Dictionary dictionary = new Dictionary();
        final int capacity = 32;
        final TextColumn column = new TextColumn(capacity, true);
        final TextColumn other = new TextColumn(capacity, true);
        final String[] expected = new String[capacity];
        final String[] otherExpected = new String[capacity];
        for (int step = 0; step < STEPS; step++) {
            switch (random.nextInt(5)) {
                case 0, 1 -> {
                    final int slot = random.nextInt(capacity);
                    final String value = random.nextInt(4) == 0 ? longString(random) : string(random);
                    put(random, column, slot, value, dictionary, new Dictionary(), NO_SCRATCH);
                    expected[slot] = value;
                }
                case 2 -> {
                    final int from = random.nextInt(capacity);
                    final int to = random.nextInt(capacity);
                    final int n = random.nextInt(capacity - Math.max(from, to) + 1);
                    column.copy(column, from, to, n);
                    System.arraycopy(expected, from, expected, to, n);
                }
                case 3 -> {
                    final int slot = random.nextInt(capacity);
                    final String value = string(random);
                    put(random, other, slot, value, dictionary, new Dictionary(), NO_SCRATCH);
                    otherExpected[slot] = value;
                }
                default -> {
                    final int from = random.nextInt(capacity);
                    final int to = random.nextInt(capacity);
                    final int n = random.nextInt(capacity - Math.max(from, to) + 1);
                    column.copy(other, from, to, n);
                    System.arraycopy(otherExpected, from, expected, to, n);
                }
            }
            for (int s = 0; s < capacity; s++) {
                if (!Objects.equals(column.textAt(s), expected[s])) {
                    fail("slot " + s + " at step " + step + ": " + escape(column.textAt(s)) + " != "
                        + escape(expected[s]));
                }
            }
        }
    }

    private static TextColumn bound(final Dictionary dictionary) {
        final TextColumn column = new TextColumn(SLOTS, true);
        column.setSymbol(0, dictionary, dictionary.key("bound"));
        return column;
    }

    private static void check(
        final TextColumn left,
        final int i,
        final String a,
        final TextColumn right,
        final int j,
        final String b
    ) {
        same("textAt", left.textAt(i), a, a, b);
        same("textAt", right.textAt(j), b, a, b);
        same("isNull", left.isNull(i), a == null, a, b);
        final int key = left.symbolKey(i);
        if (key != Vector.NO_KEY) {
            same("symbolKey", left.symbolDictionary().valueOf(key), a, a, b);
        }
        if (a == null || b == null) {
            return;
        }
        final int sign = Integer.signum(a.compareTo(b));
        same("textEquals", left.textEquals(i, right, j), a.equals(b), a, b);
        same("textEquals reversed", right.textEquals(j, left, i), a.equals(b), a, b);
        same("textEquals String", left.textEquals(i, b), a.equals(b), a, b);
        same("textCompare", Integer.signum(left.textCompare(i, right, j)), sign, a, b);
        same("textCompare reversed", Integer.signum(right.textCompare(j, left, i)), -sign, a, b);
        same("textCompare String", Integer.signum(left.textCompare(i, b)), sign, a, b);
        same("textHash", left.textHash(i), a.hashCode(), a, b);
        same("textHash", right.textHash(j), b.hashCode(), a, b);
    }

    private static String escape(final String value) {
        if (value == null) {
            return "NULL";
        }
        final StringBuilder out = new StringBuilder("\"");
        value.codePoints().forEach(cp -> {
            if (cp >= 0x20 && cp < 0x7F) {
                out.appendCodePoint(cp);
            } else {
                out.append("U+").append(Integer.toHexString(cp).toUpperCase(Locale.ROOT)).append(' ');
            }
        });
        return out.append('"').toString();
    }

    private static String longString(final Random random) {
        final StringBuilder out = new StringBuilder();
        final int length = 200 + random.nextInt(800);
        while (out.length() < length) {
            out.appendCodePoint(codePoint(random));
        }
        return out.toString();
    }

    private static int codePoint(final Random random) {
        return switch (random.nextInt(6)) {
            case 0, 1 -> 0x20 + random.nextInt(0x5F);
            case 2 -> 0x80 + random.nextInt(0x80);
            case 3 -> 0x4E00 + random.nextInt(0x5200);
            case 4 -> 0xE000 + random.nextInt(0x2000);
            default -> 0x10000 + random.nextInt(0x100000);
        };
    }

    /// The second of a pair: often equal to the first or sharing a prefix of it, so comparisons reach the tail.
    private static String partner(final Random random, final String a) {
        if (a == null || random.nextInt(10) == 0) {
            return string(random);
        }
        final int shape = random.nextInt(3);
        if (shape == 0) {
            return new String(a);
        }
        final int[] points = a.codePoints().toArray();
        final StringBuilder out = new StringBuilder();
        final int keep = shape == 1 ? points.length : random.nextInt(points.length + 1);
        for (int k = 0; k < keep; k++) {
            out.appendCodePoint(points[k]);
        }
        final int tail = random.nextInt(3);
        for (int k = 0; k < tail; k++) {
            out.appendCodePoint(codePoint(random));
        }
        return out.toString();
    }

    /// Puts `value` at `slot` by a random route: as a String, as bytes, as a key of either dictionary, or as a copy
    /// from a staging column or, unless `scratch` is [#NO_SCRATCH], from slot `scratch` of the same column.
    private static void put(
        final Random random,
        final TextColumn column,
        final int slot,
        final String value,
        final Dictionary first,
        final Dictionary second,
        final int scratch
    ) {
        final int route = value == null ? random.nextInt(2) : random.nextInt(scratch == NO_SCRATCH ? 5 : 6);
        switch (route) {
            case 0 -> column.setText(slot, value);
            case 1 -> {
                if (value == null) {
                    column.setNull(slot);
                } else {
                    final byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
                    final int pad = random.nextInt(4);
                    final byte[] padded = new byte[pad + utf8.length + pad];
                    System.arraycopy(utf8, 0, padded, pad, utf8.length);
                    column.setText(slot, MemorySegment.ofArray(padded), pad, utf8.length);
                }
            }
            case 2 -> column.setSymbol(slot, first, first.key(value));
            case 3 -> column.setSymbol(slot, second, second.key(value));
            case 4 -> {
                final TextColumn staging = new TextColumn(1, true);
                if (random.nextBoolean()) {
                    staging.setSymbol(0, first, first.key(value));
                } else {
                    staging.setText(0, value);
                }
                column.copy(staging, 0, slot);
            }
            default -> {
                column.setText(scratch, value);
                column.copy(column, scratch, slot);
            }
        }
    }

    private static void same(
        final String what,
        final Object actual,
        final Object expected,
        final String a,
        final String b
    ) {
        if (!Objects.equals(actual, expected)) {
            fail(what + " differs from String [actual=" + actual + ", expected=" + expected + ", a=" + escape(a)
                + ", b=" + escape(b) + "]");
        }
    }

    private static String string(final Random random) {
        final int shape = random.nextInt(20);
        if (shape == 0) {
            return null;
        }
        if (shape == 1) {
            return "";
        }
        final StringBuilder out = new StringBuilder();
        final int length = 1 + random.nextInt(8);
        for (int k = 0; k < length; k++) {
            out.appendCodePoint(codePoint(random));
        }
        return out.toString();
    }

    /// A dictionary that gives dense keys from 0 and counts how often a value is resolved.
    private static final class Dictionary implements SymbolDictionary {

        private final Map<String, Integer> keys = new HashMap<>();
        private final List<String> values = new ArrayList<>();
        private int resolved;

        @Override
        public String valueOf(final int key) {
            resolved++;
            return values.get(key);
        }

        @Override
        public int size() {
            return values.size();
        }

        @Override
        public int keyOf(final String value) {
            final Integer key = keys.get(value);
            return key == null ? -1 : key;
        }

        int key(final String value) {
            return keys.computeIfAbsent(value, v -> {
                values.add(v);
                return values.size() - 1;
            });
        }
    }
}
