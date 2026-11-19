package dev.marregui.comando.cdb.serve;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.jetbrains.annotations.TestOnly;

/**
 * The backend messages of one PostgreSQL connection, framed into one buffer: {@link #begin} writes a message's type
 * and room for its length, {@link #end} fills the length in. The buffer goes to the socket at {@link #flush}, and at
 * the end of a message once it holds {@link #SEND_BYTES}, so a large result streams rather than piling up. A DataRow's
 * values are written in place, each after its byte length, with no array per value. One connection's thread only.
 */
final class PgOutput {

    /** A sizing choice, not measured: a result is sent in pieces of about this size between flushes. */
    private static final int SEND_BYTES = 64 << 10;
    private static final int INITIAL_BYTES = 8 << 10;
    /**
     * A sizing choice, not measured: a buffer grown past this by one large message goes back to {@link #INITIAL_BYTES}
     * once sent, so an idle connection does not keep its largest row's memory.
     */
    private static final int SHRINK_BYTES = 1 << 20;
    // the largest array every JVM allocates
    private static final int MAX_BYTES = Integer.MAX_VALUE - 8;

    private final OutputStream out;
    // formats one number or timestamp at a time: append(long) and append(double) write into it without a String
    private final StringBuilder scratch = new StringBuilder(32);
    private byte[] buffer = new byte[INITIAL_BYTES];
    private int size;
    private int start = -1;

    PgOutput(final OutputStream out) {
        this.out = out;
    }

    /** Starts a message of {@code type}. */
    PgOutput begin(final char type) {
        int8(type);
        start = size;
        return int32(0);
    }

    /** Ends the message begun last, sending what is buffered once it is large. */
    void end() throws IOException {
        put32(start, size - start);
        start = -1;
        if (size >= SEND_BYTES) {
            send();
        }
    }

    PgOutput int8(final int value) {
        ensure(1);
        buffer[size++] = (byte) value;
        return this;
    }

    PgOutput int16(final int value) {
        ensure(2);
        buffer[size++] = (byte) (value >>> 8);
        buffer[size++] = (byte) value;
        return this;
    }

    PgOutput int32(final int value) {
        ensure(4);
        buffer[size++] = (byte) (value >>> 24);
        buffer[size++] = (byte) (value >>> 16);
        buffer[size++] = (byte) (value >>> 8);
        buffer[size++] = (byte) value;
        return this;
    }

    PgOutput int64(final long value) {
        ensure(8);
        put32(size, (int) (value >>> 32));
        put32(size + 4, (int) value);
        size += 8;
        return this;
    }

    /** {@code value}'s IEEE 754 bits as {@link java.nio.ByteBuffer#putDouble} writes them, a NaN's payload kept. */
    PgOutput float8(final double value) {
        return int64(Double.doubleToRawLongBits(value));
    }

    /**
     * A value of {@code text} in UTF-8 after its byte length, as a DataRow carries it; an unpaired surrogate becomes
     * {@code ?}, as {@link String#getBytes(java.nio.charset.Charset)} writes it.
     */
    PgOutput text(final CharSequence text) {
        final int length = text.length();
        ensure(4L + length);
        final int at = size;
        size += 4;
        int i = 0;
        while (i < length && text.charAt(i) < 0x80) {
            buffer[size++] = (byte) text.charAt(i++);
        }
        if (i < length) {
            // three bytes per char at most: a surrogate pair is four bytes for two chars
            ensure(3L * (length - i));
            while (i < length) {
                i = utf8(text, i, length);
            }
        }
        put32(at, size - at - 4);
        return this;
    }

    /** An empty builder to format one value into before {@link #text}; this connection's, valid until the next call. */
    StringBuilder scratch() {
        scratch.setLength(0);
        return scratch;
    }

    /** {@code text} in UTF-8, then a zero byte. */
    PgOutput cstring(final String text) {
        bytes(text.getBytes(StandardCharsets.UTF_8));
        return int8(0);
    }

    PgOutput bytes(final byte[] bytes) {
        ensure(bytes.length);
        System.arraycopy(bytes, 0, buffer, size, bytes.length);
        size += bytes.length;
        return this;
    }

    /** Sends everything buffered. */
    void flush() throws IOException {
        send();
        out.flush();
    }

    /** The buffer's length now. */
    @TestOnly
    int capacity() {
        return buffer.length;
    }

    // between whole messages only (end sends after closing its message), so nothing buffered is lost to the shrink
    private void send() throws IOException {
        out.write(buffer, 0, size);
        size = 0;
        if (buffer.length > SHRINK_BYTES) {
            buffer = new byte[INITIAL_BYTES];
        }
    }

    // the char at i, with the one after it for a surrogate pair, in UTF-8; returns the index of the next char
    private int utf8(final CharSequence text, final int i, final int length) {
        final char c = text.charAt(i);
        if (c < 0x80) {
            buffer[size++] = (byte) c;
        } else if (c < 0x800) {
            buffer[size++] = (byte) (0xC0 | c >> 6);
            buffer[size++] = (byte) (0x80 | c & 0x3F);
        } else if (!Character.isSurrogate(c)) {
            buffer[size++] = (byte) (0xE0 | c >> 12);
            buffer[size++] = (byte) (0x80 | c >> 6 & 0x3F);
            buffer[size++] = (byte) (0x80 | c & 0x3F);
        } else if (Character.isHighSurrogate(c) && i + 1 < length && Character.isLowSurrogate(text.charAt(i + 1))) {
            final int code = Character.toCodePoint(c, text.charAt(i + 1));
            buffer[size++] = (byte) (0xF0 | code >> 18);
            buffer[size++] = (byte) (0x80 | code >> 12 & 0x3F);
            buffer[size++] = (byte) (0x80 | code >> 6 & 0x3F);
            buffer[size++] = (byte) (0x80 | code & 0x3F);
            return i + 2;
        } else {
            buffer[size++] = '?';
        }
        return i + 1;
    }

    private void put32(final int at, final int value) {
        buffer[at] = (byte) (value >>> 24);
        buffer[at + 1] = (byte) (value >>> 16);
        buffer[at + 2] = (byte) (value >>> 8);
        buffer[at + 3] = (byte) value;
    }

    private void ensure(final long more) {
        if (size + more > buffer.length) {
            final long needed = size + more;
            if (needed > MAX_BYTES) {
                throw new IllegalStateException("message over the buffer limit [bytes=" + needed + ']');
            }
            buffer = Arrays.copyOf(buffer, (int) Math.min(Math.max((long) buffer.length * 2, needed), MAX_BYTES));
        }
    }
}
