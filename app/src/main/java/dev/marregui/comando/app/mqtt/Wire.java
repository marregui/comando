package dev.marregui.comando.app.mqtt;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * MQTT 5's data representations (§1.5): two- and four-byte integers, Variable Byte Integers (§1.5.5), UTF-8 Encoded
 * Strings (§1.5.4) and Binary Data (§1.5.6). A value that breaks its representation fails with reason code
 * {@link Packet#MALFORMED}.
 */
final class Wire {

    /** The largest Variable Byte Integer, four bytes of seven bits each (§1.5.5). */
    static final int MAX_VBI = 268_435_455;
    /** The longest UTF-8 Encoded String or Binary Data, in bytes: its length is a two-byte integer (§1.5.4). */
    static final int MAX_LENGTH = 65_535;

    private Wire() {
    }

    /** The bytes {@link Out#vbi} writes for {@code value}. */
    static int vbiSize(final int value) {
        if (value < 128) {
            return 1;
        }
        if (value < 16_384) {
            return 2;
        }
        return value < 2_097_152 ? 3 : 4;
    }

    /**
     * A Variable Byte Integer read from {@code in}, at most four bytes in its minimal form (§1.5.5); a fifth byte, a
     * longer form than needed or the end of the stream fails.
     */
    static int readVbi(final InputStream in) throws IOException {
        int value = 0;
        for (int i = 0; i < 4; i++) {
            final int b = in.read();
            if (b < 0) {
                throw new EOFException("stream ended inside a variable byte integer");
            }
            value |= (b & 0x7F) << (7 * i);
            if ((b & 0x80) == 0) {
                if (b == 0 && i > 0) {
                    throw new MqttException(Packet.MALFORMED, "variable byte integer not in its minimal form");
                }
                return value;
            }
        }
        throw new MqttException(Packet.MALFORMED, "variable byte integer longer than four bytes");
    }

    /**
     * {@code s} as a UTF-8 Encoded String's bytes: well-formed (no lone surrogate), no U+0000, at most
     * {@link #MAX_LENGTH} bytes (§1.5.4).
     */
    static byte[] utf8(final String s) throws MqttException {
        if (s.indexOf('\u0000') >= 0) {
            throw new MqttException(Packet.MALFORMED, "string holds U+0000");
        }
        final CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        final ByteBuffer encoded;
        try {
            encoded = encoder.encode(CharBuffer.wrap(s));
        } catch (final CharacterCodingException e) {
            throw new MqttException(Packet.MALFORMED, "string not well-formed UTF-16", e);
        }
        if (encoded.remaining() > MAX_LENGTH) {
            throw new MqttException(Packet.MALFORMED, "string too long [bytes=" + encoded.remaining() + ", max="
                    + MAX_LENGTH + ']');
        }
        return Arrays.copyOfRange(encoded.array(), encoded.arrayOffset(), encoded.arrayOffset() + encoded.remaining());
    }

    /** {@code length} bytes of {@code b} from {@code offset} decoded as a UTF-8 Encoded String (§1.5.4). */
    static String string(final byte[] b, final int offset, final int length) throws MqttException {
        final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        final String s;
        try {
            s = decoder.decode(ByteBuffer.wrap(b, offset, length)).toString();
        } catch (final CharacterCodingException e) {
            throw new MqttException(Packet.MALFORMED, "string not well-formed UTF-8", e);
        }
        if (s.indexOf('\u0000') >= 0) {
            throw new MqttException(Packet.MALFORMED, "string holds U+0000");
        }
        return s;
    }

    /** A packet's bytes being read, from a position that only moves forward; reading past the end fails. */
    static final class In {

        private final byte[] bytes;
        private final int end;
        private int position;

        /** The {@code bytes} from {@code 0} to {@code end}. */
        In(final byte[] bytes, final int end) {
            this.bytes = bytes;
            this.end = end;
        }

        /** The bytes not read yet. */
        int remaining() {
            return end - position;
        }

        /** A one-byte integer. */
        int u8() throws MqttException {
            need(1);
            return bytes[position++] & 0xFF;
        }

        /** A Two Byte Integer, big-endian (§1.5.2). */
        int u16() throws MqttException {
            need(2);
            final int v = (bytes[position] & 0xFF) << 8 | bytes[position + 1] & 0xFF;
            position += 2;
            return v;
        }

        /** A Four Byte Integer, big-endian and unsigned (§1.5.3). */
        long u32() throws MqttException {
            need(4);
            final long v = (long) (bytes[position] & 0xFF) << 24 | (bytes[position + 1] & 0xFF) << 16
                    | (bytes[position + 2] & 0xFF) << 8 | bytes[position + 3] & 0xFF;
            position += 4;
            return v;
        }

        /** A Variable Byte Integer in its minimal form (§1.5.5). */
        int vbi() throws MqttException {
            int value = 0;
            for (int i = 0; i < 4; i++) {
                final int b = u8();
                value |= (b & 0x7F) << (7 * i);
                if ((b & 0x80) == 0) {
                    if (b == 0 && i > 0) {
                        throw new MqttException(Packet.MALFORMED, "variable byte integer not in its minimal form");
                    }
                    return value;
                }
            }
            throw new MqttException(Packet.MALFORMED, "variable byte integer longer than four bytes");
        }

        /** A UTF-8 Encoded String after its two-byte length (§1.5.4). */
        String string() throws MqttException {
            final int length = u16();
            need(length);
            final String s = Wire.string(bytes, position, length);
            position += length;
            return s;
        }

        /** Binary Data after its two-byte length (§1.5.6). */
        byte[] binary() throws MqttException {
            return bytes(u16());
        }

        /** The next {@code length} bytes, copied. */
        byte[] bytes(final int length) throws MqttException {
            need(length);
            final byte[] out = Arrays.copyOfRange(bytes, position, position + length);
            position += length;
            return out;
        }

        /** An {@code In} over the next {@code length} bytes, which this one skips. */
        In slice(final int length) throws MqttException {
            need(length);
            final In s = new In(Arrays.copyOfRange(bytes, position, position + length), length);
            position += length;
            return s;
        }

        private void need(final int n) throws MqttException {
            if (n > end - position) {
                throw new MqttException(Packet.MALFORMED, "packet shorter than its fields [needed=" + n
                        + ", remaining=" + (end - position) + ']');
            }
        }
    }

    /** A packet's bytes being written, in a buffer that grows. */
    static final class Out {

        private byte[] bytes = new byte[64];
        private int size;

        /** The bytes written. */
        int size() {
            return size;
        }

        /** {@code v}'s low byte. */
        Out u8(final int v) {
            room(1);
            bytes[size++] = (byte) v;
            return this;
        }

        /** {@code v} as a Two Byte Integer (§1.5.2). */
        Out u16(final int v) {
            room(2);
            bytes[size++] = (byte) (v >>> 8);
            bytes[size++] = (byte) v;
            return this;
        }

        /** {@code v} as a Four Byte Integer (§1.5.3). */
        Out u32(final long v) {
            room(4);
            bytes[size++] = (byte) (v >>> 24);
            bytes[size++] = (byte) (v >>> 16);
            bytes[size++] = (byte) (v >>> 8);
            bytes[size++] = (byte) v;
            return this;
        }

        /** {@code value}, at most {@link #MAX_VBI}, as a Variable Byte Integer in its minimal form (§1.5.5). */
        Out vbi(final int value) {
            int v = value;
            do {
                int b = v & 0x7F;
                v >>>= 7;
                if (v > 0) {
                    b |= 0x80;
                }
                u8(b);
            } while (v > 0);
            return this;
        }

        /** {@code utf8}, the bytes {@link #utf8} gave, with its two-byte length. */
        Out string(final byte[] utf8) {
            return u16(utf8.length).bytes(utf8);
        }

        /** {@code data}, at most {@link #MAX_LENGTH} bytes, as Binary Data with its two-byte length (§1.5.6). */
        Out binary(final byte[] data) throws MqttException {
            if (data.length > MAX_LENGTH) {
                throw new MqttException(Packet.MALFORMED, "binary data too long [bytes=" + data.length + ", max="
                        + MAX_LENGTH + ']');
            }
            return u16(data.length).bytes(data);
        }

        /** {@code data} as it is. */
        Out bytes(final byte[] data) {
            room(data.length);
            System.arraycopy(data, 0, bytes, size, data.length);
            size += data.length;
            return this;
        }

        /** What {@code other} holds, as it is. */
        Out bytes(final Out other) {
            room(other.size);
            System.arraycopy(other.bytes, 0, bytes, size, other.size);
            size += other.size;
            return this;
        }

        /** A copy of the bytes written. */
        byte[] toByteArray() {
            return Arrays.copyOf(bytes, size);
        }

        private void room(final int n) {
            if (n > bytes.length - size) {
                bytes = Arrays.copyOf(bytes, Math.max(bytes.length * 2, size + n));
            }
        }
    }
}
