package dev.marregui.comando.core.json;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.HexFormat;

/** The characters a {@link JsonParser} reads, a buffer at a time, from text, a reader or UTF-8 bytes. */
abstract sealed class JsonInput implements Closeable {

    JsonInput() {
    }

    /**
     * Fills {@code into} from its start with the next characters, and says how many; at least 1, or -1 at the end of
     * the input. Never splits a surrogate pair that UTF-8 decoding produced.
     *
     * @throws JsonException when the input is not valid UTF-8 where this read reaches it
     */
    abstract int read(char[] into) throws IOException;

    /** The input over {@code text}. */
    static JsonInput of(final String text) {
        return new Text(text);
    }

    /** The input over {@code reader}, closed by {@link #close()}. */
    static JsonInput of(final Reader reader) {
        return new Chars(reader);
    }

    /** The input over UTF-8 {@code bytes}, a leading byte order mark skipped. */
    static JsonInput of(final byte[] bytes) {
        return new Utf8(null, bytes, bytes.length);
    }

    /** The input over the UTF-8 bytes of {@code stream}, closed by {@link #close()}; a byte order mark skipped. */
    static JsonInput of(final InputStream stream) {
        return new Utf8(stream, new byte[Utf8.BUFFER_BYTES], 0);
    }

    /** The characters of a string, copied out a buffer at a time. */
    private static final class Text extends JsonInput {

        private final String text;
        private int at;

        Text(final String text) {
            this.text = text;
        }

        @Override
        int read(final char[] into) {
            final int count = Math.min(into.length, text.length() - at);
            if (count == 0) {
                return -1;
            }
            text.getChars(at, at + count, into, 0);
            at += count;
            return count;
        }

        @Override
        public void close() {
        }
    }

    /** The characters of a reader. */
    private static final class Chars extends JsonInput {

        private final Reader reader;

        Chars(final Reader reader) {
            this.reader = reader;
        }

        @Override
        int read(final char[] into) throws IOException {
            return reader.read(into, 0, into.length);
        }

        @Override
        public void close() throws IOException {
            reader.close();
        }
    }

    /**
     * UTF-8 decoded as jackson-core 3.2.4 decodes it: two-, three- and four-byte sequences, a four-byte one as a
     * surrogate pair; a bad start or continuation byte fails, but only when a read reaches it, so a document that ends
     * before it still parses. An encoded surrogate fails, as there. An overlong form fails too, where jackson-core
     * decodes one in a string value as its bits say: decoded ahead of the parser, an overlong quote or brace would act
     * as JSON syntax.
     */
    private static final class Utf8 extends JsonInput {

        static final int BUFFER_BYTES = 8_192;
        private static final int LAST_CODE_POINT = 0x10FFFF;
        /** The least code point each sequence length may encode, by length; less is an overlong form (RFC 3629 §3). */
        private static final int[] MIN_CODE_POINT = {0, 0, 0x80, 0x800, 0x10000};
        private static final int BOM_LENGTH = 3;
        private final InputStream stream;
        private final byte[] bytes;
        private int position;
        private int limit;
        private long consumed;
        private boolean started;
        private JsonException failure;

        Utf8(final InputStream stream, final byte[] bytes, final int limit) {
            this.stream = stream;
            this.bytes = bytes;
            this.limit = limit;
        }

        @Override
        int read(final char[] into) throws IOException {
            if (failure != null) {
                throw failure;
            }
            if (!started) {
                started = true;
                skipByteOrderMark();
            }
            int count = 0;
            while (count < into.length - 1) {
                if (position == limit && (count > 0 || !refill(1))) {
                    break;
                }
                final int lead = bytes[position] & 0xFF;
                if (lead < 0x80) {
                    into[count++] = (char) lead;
                    position++;
                    continue;
                }
                final int length = sequenceLength(lead);
                if (length == 0) {
                    return failAfter(count, new JsonException("invalid UTF-8 start byte", byteAt(position)));
                }
                if (limit - position < length && (count > 0 || !refill(length))) {
                    if (count > 0) {
                        break;
                    }
                    throw new JsonException("ran out of input in a UTF-8 sequence", byteAt(position));
                }
                int codePoint = lead & (0xFF >> (length + 1));
                for (int i = 1; i < length; i++) {
                    final int next = bytes[position + i] & 0xFF;
                    if ((next & 0xC0) != 0x80) {
                        return failAfter(count, new JsonException("invalid UTF-8 continuation byte",
                                byteAt(position + i)));
                    }
                    codePoint = codePoint << 6 | next & 0x3F;
                }
                if (codePoint < MIN_CODE_POINT[length]) {
                    // an overlong form would decode to a character the JSON syntax reads, a quote or a brace (RFC 3629)
                    return failAfter(count, new JsonException("invalid UTF-8: an overlong form", byteAt(position)));
                }
                if (codePoint > LAST_CODE_POINT) {
                    return failAfter(count, new JsonException("invalid UTF-8 code point", byteAt(position)));
                }
                if (length == 3 && Character.isSurrogate((char) codePoint)) {
                    return failAfter(count, new JsonException("invalid UTF-8: an encoded surrogate",
                            byteAt(position)));
                }
                if (length == 4) {
                    into[count++] = Character.highSurrogate(codePoint);
                    into[count++] = Character.lowSurrogate(codePoint);
                } else {
                    into[count++] = (char) codePoint;
                }
                position += length;
            }
            return count == 0 ? -1 : count;
        }

        @Override
        public void close() throws IOException {
            if (stream != null) {
                stream.close();
            }
        }

        /**
         * Skips a byte order mark that a fourth byte follows: jackson-core reads four bytes to detect the encoding and
         * looks for the mark only when it has them, so a mark alone is read as the character U+FEFF and refused.
         */
        private void skipByteOrderMark() throws IOException {
            refill(BOM_LENGTH + 1);
            if (limit - position > BOM_LENGTH && (bytes[position] & 0xFF) == 0xEF
                    && (bytes[position + 1] & 0xFF) == 0xBB && (bytes[position + 2] & 0xFF) == 0xBF) {
                position += BOM_LENGTH;
            }
        }

        /** Whether {@code needed} bytes are buffered after reading more from the stream, when there is one. */
        private boolean refill(final int needed) throws IOException {
            if (stream == null || limit - position >= needed) {
                return limit - position >= needed;
            }
            System.arraycopy(bytes, position, bytes, 0, limit - position);
            consumed += position;
            limit -= position;
            position = 0;
            while (limit < needed) {
                final int read = stream.read(bytes, limit, bytes.length - limit);
                if (read < 0) {
                    return false;
                }
                limit += read;
            }
            return true;
        }

        /** The characters decoded before a bad byte, the failure kept for the next read; the failure when none. */
        private int failAfter(final int count, final JsonException bad) {
            if (count == 0) {
                throw bad;
            }
            failure = bad;
            return count;
        }

        private String byteAt(final int index) {
            return "byte=0x" + HexFormat.of().withUpperCase().toHexDigits(bytes[index])
                    + ", offset=" + (consumed + index);
        }

        /** The length of the sequence a start byte opens, or 0 for a byte that cannot start one. */
        private static int sequenceLength(final int lead) {
            if ((lead & 0xE0) == 0xC0) {
                return 2;
            }
            if ((lead & 0xF0) == 0xE0) {
                return 3;
            }
            return (lead & 0xF8) == 0xF0 ? 4 : 0;
        }
    }
}
