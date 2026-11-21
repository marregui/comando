package dev.marregui.comando.cdb.json;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import dev.marregui.comando.cdb.CdbException;

/**
 * A path to a value inside a JSON text, compiled once per query: a list of steps, each an object member's name or an
 * array element's index. Its text form is QuestDB's {@code json_extract} path, with an optional leading {@code $}:
 * {@code .name} for a member whose name holds no {@code .} or {@code [}, {@code ["name"]} for any member (the name a
 * JSON string), {@code [n]} for element {@code n}, counted from 0. The empty path and {@code $} name the whole text.
 */
public final class JsonPath {

    private static final int NO_INDEX = -1;

    private final byte[][] keys; // UTF-8; null at an index step
    private final int[] indexes; // NO_INDEX at a member step

    private JsonPath(final byte[][] keys, final int[] indexes) {
        this.keys = keys;
        this.indexes = indexes;
    }

    /** The path of {@code text}; an error at the first character that breaks the grammar. */
    public static JsonPath parse(final String text) {
        final List<byte[]> keys = new ArrayList<>();
        final List<Integer> indexes = new ArrayList<>();
        int p = !text.isEmpty() && text.charAt(0) == '$' ? 1 : 0;
        while (p < text.length()) {
            final char c = text.charAt(p);
            if (c == '.') {
                int end = p + 1;
                while (end < text.length() && text.charAt(end) != '.' && text.charAt(end) != '[') {
                    end++;
                }
                if (end == p + 1) {
                    throw invalid(text, p);
                }
                keys.add(text.substring(p + 1, end).getBytes(StandardCharsets.UTF_8));
                indexes.add(NO_INDEX);
                p = end;
            } else if (c == '[' && p + 1 < text.length() && text.charAt(p + 1) == '"') {
                p = quoted(text, p + 1, keys, indexes);
            } else if (c == '[') {
                p = index(text, p + 1, keys, indexes);
            } else {
                throw invalid(text, p);
            }
        }
        return of(keys, indexes);
    }

    /** The one-step path to member {@code name}, the right side of {@code ->} as text. */
    public static JsonPath member(final String name) {
        return new JsonPath(new byte[][]{name.getBytes(StandardCharsets.UTF_8)}, new int[]{NO_INDEX});
    }

    /** The one-step path to element {@code index}, the right side of {@code ->} as a number; a negative names none. */
    public static JsonPath element(final long index) {
        final int at = index < 0 || index > Integer.MAX_VALUE ? Integer.MIN_VALUE : (int) index;
        return new JsonPath(new byte[][]{null}, new int[]{at});
    }

    /** The number of steps. */
    int steps() {
        return indexes.length;
    }

    /** True when step {@code s} is an element's index. */
    boolean isIndex(final int s) {
        return keys[s] == null;
    }

    /** The index of step {@code s}, an element step; negative names none. */
    int index(final int s) {
        return indexes[s];
    }

    /** The UTF-8 name of step {@code s}, a member step. */
    byte[] key(final int s) {
        return keys[s];
    }

    private static JsonPath of(final List<byte[]> keys, final List<Integer> indexes) {
        final int[] at = new int[indexes.size()];
        for (int i = 0; i < at.length; i++) {
            at[i] = indexes.get(i);
        }
        return new JsonPath(keys.toArray(new byte[0][]), at);
    }

    // ["name"] from the quote at p: the name a JSON string, decoded; returns the offset past the bracket
    private static int quoted(final String text, final int p, final List<byte[]> keys, final List<Integer> indexes) {
        int end = p + 1;
        while (end < text.length() && text.charAt(end) != '"') {
            end += text.charAt(end) == '\\' ? 2 : 1;
        }
        if (end >= text.length() || end + 1 >= text.length() || text.charAt(end + 1) != ']') {
            throw invalid(text, Math.min(end, text.length()));
        }
        final byte[] string = text.substring(p, end + 1).getBytes(StandardCharsets.UTF_8);
        final Json json = new Json();
        if (json.invalidAt(string, 0, string.length) != Json.VALID) {
            throw invalid(text, p);
        }
        final int n = json.unescape(string, 1, string.length - 1);
        keys.add(n == Json.RAW ? Arrays.copyOfRange(string, 1, string.length - 1)
                : Arrays.copyOf(json.buffer(), n));
        indexes.add(NO_INDEX);
        return end + 2;
    }

    // [n] from the first digit at p; returns the offset past the bracket
    private static int index(final String text, final int p, final List<byte[]> keys, final List<Integer> indexes) {
        int end = p;
        long value = 0;
        while (end < text.length() && text.charAt(end) >= '0' && text.charAt(end) <= '9') {
            value = Math.min(Integer.MAX_VALUE + 1L, value * 10 + text.charAt(end) - '0');
            end++;
        }
        if (end == p || end >= text.length() || text.charAt(end) != ']') {
            throw invalid(text, end);
        }
        keys.add(null);
        indexes.add(value > Integer.MAX_VALUE ? Integer.MIN_VALUE : (int) value);
        return end + 1;
    }

    private static CdbException invalid(final String text, final int at) {
        return new CdbException("refuse invalid JSON path [path=" + text + ", at=" + at + ']', -1);
    }
}
