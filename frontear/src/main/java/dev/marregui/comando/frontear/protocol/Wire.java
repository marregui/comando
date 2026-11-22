package dev.marregui.comando.frontear.protocol;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
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
     * The bytes of {@code s} as a UTF-8 Encoded String, length excluded: fails unless it is well-formed (no lone
     * surrogate), holds no U+0000 and takes at most {@link #MAX_LENGTH} bytes (§1.5.4).
     */
    static int utf8Length(final String s) throws MqttException {
        final int chars = s.length();
        long bytes = 0;
        // one pass: U+0000 is refused here, not by a scan of its own before
        for (int i = 0; i < chars; i++) {
            final char c = s.charAt(i);
            if (c == 0) {
                throw new MqttException(Packet.MALFORMED, "string holds U+0000");
            } else if (c < 0x80) {
                bytes += 1;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (!Character.isSurrogate(c)) {
                bytes += 3;
            } else if (Character.isHighSurrogate(c) && i + 1 < chars && Character.isLowSurrogate(s.charAt(i + 1))) {
                bytes += 4;
                i++;
            } else {
                throw new MqttException(Packet.MALFORMED, "string not well-formed UTF-16");
            }
        }
        if (bytes > MAX_LENGTH) {
            throw new MqttException(Packet.MALFORMED, "string too long [bytes=" + bytes + ", max=" + MAX_LENGTH + ']');
        }
        return (int) bytes;
    }

    /** {@code length} bytes of {@code b} from {@code offset} decoded as a UTF-8 Encoded String (§1.5.4). */
    static String string(final byte[] b, final int offset, final int length) throws MqttException {
        if (ascii(b, offset, length)) {
            // below 0x80 ISO-8859-1 and UTF-8 agree byte for byte, and the JDK copies Latin-1 into the string as is
            return new String(b, offset, length, StandardCharsets.ISO_8859_1);
        }
        final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        final String s;
        try {
            s = decoder.decode(ByteBuffer.wrap(b, offset, length)).toString();
        } catch (final CharacterCodingException e) {
            throw new MqttException(Packet.MALFORMED, "string not well-formed UTF-8", e);
        }
        if (s.indexOf(0) >= 0) {
            throw new MqttException(Packet.MALFORMED, "string holds U+0000");
        }
        return s;
    }

    /** Whether every byte of the range is from 0x01 to 0x7F: ASCII without U+0000. */
    private static boolean ascii(final byte[] b, final int offset, final int length) {
        final int end = offset + length;
        for (int i = offset; i < end; i++) {
            if (b[i] <= 0) {
                return false;
            }
        }
        return true;
    }

    /** A packet's bytes being read, from a position that only moves forward; reading past the end fails. */
    static final class In {

        private final byte[] bytes;
        private final int end;
        private int position;

        /** The {@code bytes} from {@code position} to {@code end}, read in place: they must not change meanwhile. */
        In(final byte[] bytes, final int position, final int end) {
            this.bytes = bytes;
            this.end = end;
            this.position = position;
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

        /** An {@code In} over the next {@code length} bytes, which this one skips; it reads the same array. */
        In slice(final int length) throws MqttException {
            need(length);
            final In s = new In(bytes, position, position + length);
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

    /**
     * A packet's bytes being written, in a buffer that grows; or, from {@link #counting}, only counted, so a packet can
     * be sized before it is written into a buffer of that size.
     */
    static final class Out {

        // null when counting
        private byte[] bytes;
        private int size;

        /** An {@code Out} that starts small and grows. */
        Out() {
            this(64);
        }

        /** An {@code Out} whose buffer holds {@code capacity} bytes before it grows. */
        Out(final int capacity) {
            bytes = new byte[capacity];
        }

        private Out(final byte[] bytes) {
            this.bytes = bytes;
        }

        /**
         * An {@code Out} that keeps no byte and only counts them, up to {@link Integer#MAX_VALUE}, where it stays: a
         * count beyond {@link #MAX_VBI} is too large for any packet, whatever its exact value.
         */
        static Out counting() {
            return new Out((byte[]) null);
        }

        /** The bytes written. */
        int size() {
            return size;
        }

        /** {@code v}'s low byte. */
        Out u8(final int v) {
            if (room(1)) {
                bytes[size++] = (byte) v;
            }
            return this;
        }

        /** {@code v} as a Two Byte Integer (§1.5.2). */
        Out u16(final int v) {
            if (room(2)) {
                bytes[size++] = (byte) (v >>> 8);
                bytes[size++] = (byte) v;
            }
            return this;
        }

        /** {@code v} as a Four Byte Integer (§1.5.3). */
        Out u32(final long v) {
            if (room(4)) {
                bytes[size++] = (byte) (v >>> 24);
                bytes[size++] = (byte) (v >>> 16);
                bytes[size++] = (byte) (v >>> 8);
                bytes[size++] = (byte) v;
            }
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

        /** {@code s} as a UTF-8 Encoded String with its two-byte length; fails as {@link #utf8Length} does. */
        Out string(final String s) throws MqttException {
            final int length = utf8Length(s);
            u16(length);
            if (room(length)) {
                if (length == s.length()) {
                    copyAscii(s);
                } else {
                    utf8(s);
                }
            }
            return this;
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
            if (room(data.length)) {
                System.arraycopy(data, 0, bytes, size, data.length);
                size += data.length;
            }
            return this;
        }

        /**
         * The bytes written: the buffer itself when they fill it, as they do in a buffer sized to them, else a copy.
         * Nothing is written after; a counting {@code Out} has no bytes to give.
         */
        byte[] toByteArray() {
            return size == bytes.length ? bytes : Arrays.copyOf(bytes, size);
        }

        /**
         * Whether to write the next {@code n} bytes, the buffer then holding room for them; a counting {@code Out}
         * counts them here instead and answers false.
         */
        private boolean room(final int n) {
            if (bytes == null) {
                size = (int) Math.min(Integer.MAX_VALUE, (long) size + n);
                return false;
            }
            if (n > bytes.length - size) {
                bytes = Arrays.copyOf(bytes, Math.max(bytes.length * 2, size + n));
            }
            return true;
        }

        /**
         * {@code s}, all of it below U+0080, into room already made for it: such a string's UTF-8 is its characters'
         * low bytes, which the JDK copies as one array copy, where {@link #utf8} takes a character at a time (30% of
         * a link's strand at 80,000 messages a second went to encoding strings).
         */
        @SuppressWarnings("deprecation") // getBytes(int, int, byte[], int) drops each char's high byte, here all zero
        private void copyAscii(final String s) {
            s.getBytes(0, s.length(), bytes, size);
            size += s.length();
        }

        /** {@code s}, checked by {@link #utf8Length}, in UTF-8, into room already made for it. */
        private void utf8(final String s) {
            final int chars = s.length();
            for (int i = 0; i < chars; i++) {
                final char c = s.charAt(i);
                if (c < 0x80) {
                    bytes[size++] = (byte) c;
                } else if (c < 0x800) {
                    bytes[size++] = (byte) (0xC0 | c >>> 6);
                    bytes[size++] = (byte) (0x80 | c & 0x3F);
                } else if (Character.isHighSurrogate(c)) {
                    final int cp = Character.toCodePoint(c, s.charAt(++i));
                    bytes[size++] = (byte) (0xF0 | cp >>> 18);
                    bytes[size++] = (byte) (0x80 | cp >>> 12 & 0x3F);
                    bytes[size++] = (byte) (0x80 | cp >>> 6 & 0x3F);
                    bytes[size++] = (byte) (0x80 | cp & 0x3F);
                } else {
                    bytes[size++] = (byte) (0xE0 | c >>> 12);
                    bytes[size++] = (byte) (0x80 | c >>> 6 & 0x3F);
                    bytes[size++] = (byte) (0x80 | c & 0x3F);
                }
            }
        }
    }
}
