package dev.marregui.comando.core.files;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

/**
 * Finds text in bytes where they lie, so a search decodes only the slice it shows (record 139). ASCII letters match in
 * either case; every other byte, a UTF-8 sequence's among them, matches only itself.
 */
public final class ByteScan {

    /**
     * How much of a file says whether it is binary: a NUL byte in it does, as in the files git calls binary. One
     * value for opening a file and for searching one, so both judge it alike.
     */
    public static final long BINARY_PROBE_BYTES = 8_192L;
    private static final byte[] NUL = {0};
    private static final int CASE_BIT = 0x20;

    private ByteScan() {
    }

    /** {@code text} as the UTF-8 needle {@link #indexOf} takes: its ASCII letters in lower case. */
    public static byte[] needle(final String text) {
        final byte[] needle = text.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < needle.length; i++) {
            needle[i] = lower(needle[i]);
        }
        return needle;
    }

    /**
     * Where {@code lowerCaseNeedle} first occurs at or after {@code from}, or -1 when it does not. The needle's ASCII
     * letters must be lower case; an empty needle occurs at {@code from}.
     */
    public static long indexOf(final MemorySegment haystack, final byte[] lowerCaseNeedle, final long from) {
        final long size = haystack.byteSize();
        final int length = lowerCaseNeedle.length;
        if (length == 0) {
            return from <= size ? from : -1L;
        }
        final byte first = lowerCaseNeedle[0];
        final byte firstOther = isLowerCaseLetter(first) ? (byte) (first ^ CASE_BIT) : first;
        final long last = size - length;
        for (long at = from; at <= last; at++) {
            final byte b = haystack.get(ValueLayout.JAVA_BYTE, at);
            if ((b == first || b == firstOther) && matches(haystack, at + 1L, lowerCaseNeedle)) {
                return at;
            }
        }
        return -1L;
    }

    /** Whether the needle's bytes after its first lie at {@code at}. */
    private static boolean matches(final MemorySegment haystack, final long at, final byte[] needle) {
        for (int i = 1; i < needle.length; i++) {
            if (lower(haystack.get(ValueLayout.JAVA_BYTE, at + i - 1L)) != needle[i]) {
                return false;
            }
        }
        return true;
    }

    private static byte lower(final byte b) {
        return b >= 'A' && b <= 'Z' ? (byte) (b | CASE_BIT) : b;
    }

    private static boolean isLowerCaseLetter(final byte b) {
        return b >= 'a' && b <= 'z';
    }

    /** Whether {@code file} is binary: a NUL byte in its first {@link #BINARY_PROBE_BYTES}. */
    public static boolean isBinary(final MemorySegment file) {
        return indexOf(file.asSlice(0L, Math.min(BINARY_PROBE_BYTES, file.byteSize())), NUL, 0L) >= 0L;
    }
}
