package dev.marregui.comando.cdb.serve;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/// The parser reads the body's UTF-8 bytes in place: names and strings, non-ASCII and malformed sequences included,
/// read as the decoded body reads, an error's offset counts characters, and no byte past the given length is read.
/// Random tokens come from a seed printed and replayed through `cdb.serve.seed`.
class IlpBytesTest {

    private static final long NOW_MICROS = 1_763_117_385_000_000L;
    /// ASCII, two-, three- and four-byte characters, lone continuation bytes, truncated sequences, an overlong form,
    /// and the escapes a tag value and a string take.
    private static final byte[][] PIECES = {
            bytes(0x61), bytes(0x5a), bytes(0x37), bytes(0xc3, 0xa9), bytes(0xe2, 0x82, 0xac),
            bytes(0xf0, 0x9f, 0x98, 0x80), bytes(0x80), bytes(0xbf), bytes(0xc3), bytes(0xe2, 0x82),
            bytes(0xf0, 0x9f, 0x98), bytes(0xc0, 0xaf), bytes(0xed, 0xa0, 0x80), bytes(0xff), bytes(0x5c, 0x2c),
            bytes(0x5c, 0x20), bytes(0x5c, 0x3d), bytes(0x5c, 0x5c), bytes(0x5c, 0xc3, 0xa9), bytes(0x5c, 0x80)};

    /// A tag value and a string field of random pieces: each reads as the escape-free text of the token decoded on
    /// its own, which is how the decoded body read it.
    @Test
    void randomNamesAndStringsReadAsTheDecodedBody() {
        final long seed = Long.getLong("cdb.serve.seed", System.nanoTime());
        System.out.println("IlpBytesTest.randomNamesAndStringsReadAsTheDecodedBody seed=" + seed);
        final SplittableRandom random = new SplittableRandom(seed);
        for (int i = 0; i < 50_000; i++) {
            final byte[] tag = token(random);
            final byte[] string = token(random);
            final ByteArrayOutputStream line = new ByteArrayOutputStream();
            line.writeBytes(ascii("m,k="));
            line.writeBytes(tag);
            line.writeBytes(ascii(" s=\""));
            line.writeBytes(string);
            line.writeBytes(ascii("\" 1"));
            final byte[] body = line.toByteArray();
            final RecordingAppender sink = new RecordingAppender();

            IlpParser.parse(body, body.length, sink, Precision.MICROS, NOW_MICROS);

            assertThat(sink.pending).as("seed=%d, line %d: %s", seed, i, Arrays.toString(body)).containsExactly(
                    "table(m)", "symbol(k=" + unescaped(tag) + ')', "string(s=" + unescaped(string) + ')', "at(1)");
        }
    }

    /// The offset of a refused value after two- and four-byte characters counts them as one and two characters.
    @Test
    void errorOffsetCountsCharactersNotBytes() {
        final byte[] body = "mé,t=😀 f=zz 1".getBytes(StandardCharsets.UTF_8);

        final IlpException e = catchThrowableOfType(IlpException.class,
                () -> IlpParser.parse(body, body.length, new RecordingAppender(), Precision.MICROS, NOW_MICROS));

        assertThat(e.offset()).isEqualTo("mé,t=😀 f=".length());
    }

    /// Bytes past the given length, a pooled buffer's earlier body, are never read.
    @Test
    void readsNoBytePastTheLength() {
        final byte[] body = Arrays.copyOf(ascii("m f=1.5 7"), 64);
        final byte[] stale = ascii("0\nm f=\"unclosed");
        System.arraycopy(stale, 0, body, 9, stale.length);
        final RecordingAppender sink = new RecordingAppender();

        final int rows = IlpParser.parse(body, 9, sink, Precision.MICROS, NOW_MICROS);

        assertThat(rows).isEqualTo(1);
        assertThat(sink.pending).containsExactly("table(m)", "double(f=1.5)", "at(7)");
    }

    // one to six pieces, never a quote or a line break, so the token stays one value; a token ending in an escape
    // backslash with nothing after it cannot occur, as every escape piece carries its escaped byte
    private static byte[] token(final SplittableRandom random) {
        final ByteArrayOutputStream token = new ByteArrayOutputStream();
        final int pieces = 1 + random.nextInt(6);
        for (int i = 0; i < pieces; i++) {
            token.writeBytes(PIECES[random.nextInt(PIECES.length)]);
        }
        return token.toByteArray();
    }

    // the token decoded on its own, as the whole body decodes it between ASCII bytes, then each backslash dropped and
    // the character after it kept as it is
    private static String unescaped(final byte[] token) {
        final String text = new String(token, StandardCharsets.UTF_8);
        final StringBuilder value = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                i++;
                value.append(text.charAt(i));
            } else {
                value.append(c);
            }
        }
        return value.toString();
    }

    private static byte[] ascii(final String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] bytes(final int... values) {
        final byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            bytes[i] = (byte) values[i];
        }
        return bytes;
    }
}
