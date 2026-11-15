package dev.marregui.comando.cdb.serve;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The backend messages of one PostgreSQL connection, framed into one buffer: {@link #begin} writes a message's type
 * and room for its length, {@link #end} fills the length in. The buffer goes to the socket at {@link #flush}, and at
 * the end of a message once it holds {@link #SEND_BYTES}, so a large result streams rather than piling up. One
 * connection's thread only.
 */
final class PgOutput {

    /** A sizing choice, not measured: a result is sent in pieces of about this size between flushes. */
    private static final int SEND_BYTES = 64 << 10;

    private final OutputStream out;
    private byte[] buffer = new byte[8 << 10];
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
        final int length = size - start;
        buffer[start] = (byte) (length >>> 24);
        buffer[start + 1] = (byte) (length >>> 16);
        buffer[start + 2] = (byte) (length >>> 8);
        buffer[start + 3] = (byte) length;
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

    private void send() throws IOException {
        out.write(buffer, 0, size);
        size = 0;
    }

    private void ensure(final int more) {
        if (size + more > buffer.length) {
            final long wanted = Math.max((long) buffer.length * 2, (long) size + more);
            if (wanted > Integer.MAX_VALUE - 8) {
                throw new IllegalStateException("message over the buffer limit [bytes=" + wanted + ']');
            }
            buffer = Arrays.copyOf(buffer, (int) wanted);
        }
    }
}
