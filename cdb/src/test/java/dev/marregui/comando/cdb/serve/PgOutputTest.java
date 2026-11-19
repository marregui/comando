package dev.marregui.comando.cdb.serve;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// The PostgreSQL output buffer: a value's text written in place is the UTF-8 `String.getBytes` makes, after its byte
/// length, and a buffer a large row grew goes back to its first size once sent, so an idle connection keeps no
/// memory of its largest row.
class PgOutputTest {

    private static final int MIB = 1 << 20;
    /// Enough random strings to meet every encoding branch many times over; well under a second.
    private static final int STRINGS = 20_000;

    /// Text with every UTF-8 width, surrogate pairs and unpaired surrogates (a high one at the end, before a non-low
    /// char, a low one alone) is written as its length, then the bytes of `String.getBytes(UTF_8)`.
    @Test
    void writesTextAsTheUtf8OfGetBytes() throws IOException {
        final String high = String.valueOf((char) 0xD83D);
        final String low = String.valueOf((char) 0xDE00);
        // the last string holds each width's first and last char: 0x7F, 0x80, 0x7FF, 0x800, 0xFFFF
        final String edges = "" + (char) 0x7F + (char) 0x80 + (char) 0x7FF + (char) 0x800 + (char) 0xFFFF;
        for (final String text : new String[] {"", "plain", "café", "日本", high + low, high, low,
                high + "x", "x" + low + high, low + high, "a" + high + low + "b" + edges}) {
            assertThat(written(text)).as("text %s", text.codePoints().boxed().toList()).isEqualTo(expected(text));
        }
    }

    /// Random strings over the whole char range, surrogates included, are written as `String.getBytes(UTF_8)`
    /// writes them. The seed is printed and replayed through `cdb.pg.seed`.
    @Test
    void writesRandomTextAsTheUtf8OfGetBytes() throws IOException {
        final long seed = Long.getLong("cdb.pg.seed", System.nanoTime());
        System.out.println("PgOutputTest.writesRandomTextAsTheUtf8OfGetBytes seed=" + seed);
        final SplittableRandom random = new SplittableRandom(seed);
        for (int i = 0; i < STRINGS; i++) {
            final char[] chars = new char[random.nextInt(24)];
            for (int c = 0; c < chars.length; c++) {
                chars[c] = switch (random.nextInt(4)) {
                    case 0 -> (char) random.nextInt(0x80);
                    case 1 -> (char) random.nextInt(0x80, 0x800);
                    case 2 -> (char) random.nextInt(0xD800, 0xE000);
                    default -> (char) random.nextInt(0x10000);
                };
            }
            final String text = new String(chars);
            assertThat(written(text)).as("seed=%d, string %d", seed, i).isEqualTo(expected(text));
        }
    }

    /// A 4 MiB DataRow grows the buffer past it; once the row is sent the buffer is back at its first size, a small
    /// row after it leaves it there, and both rows reach the socket whole.
    @Test
    void shrinksTheBufferAfterSendingALargeRow() throws IOException {
        final ByteArrayOutputStream socket = new ByteArrayOutputStream();
        final PgOutput out = new PgOutput(socket);
        final int initial = out.capacity();
        final String large = "x".repeat(4 * MIB);
        out.begin('D').int16(1).text(large);
        assertThat(out.capacity()).isGreaterThan(4 * MIB);
        out.end();
        assertThat(out.capacity()).isEqualTo(initial);
        out.begin('D').int16(1).text("small").end();
        out.flush();
        assertThat(out.capacity()).isEqualTo(initial);
        final ByteArrayOutputStream rows = new ByteArrayOutputStream();
        rows.write(dataRow(large));
        rows.write(dataRow("small"));
        assertThat(socket.toByteArray()).isEqualTo(rows.toByteArray());
    }

    /// A row that grows the buffer to no more than 1 MiB leaves it grown after the send: the next rows of its size
    /// reuse it.
    @Test
    void keepsABufferOfAtMostOneMibAfterASend() throws IOException {
        final PgOutput out = new PgOutput(OutputStream.nullOutputStream());
        final int initial = out.capacity();
        out.begin('D').int16(1).text("x".repeat(MIB / 2)).end();
        assertThat(out.capacity()).isGreaterThan(initial).isLessThanOrEqualTo(MIB);
    }

    private static byte[] written(final String text) throws IOException {
        final ByteArrayOutputStream socket = new ByteArrayOutputStream();
        final PgOutput out = new PgOutput(socket);
        out.text(text).flush();
        return socket.toByteArray();
    }

    private static byte[] expected(final String text) throws IOException {
        final ByteArrayOutputStream socket = new ByteArrayOutputStream();
        final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        new PgOutput(socket).int32(bytes.length).bytes(bytes).flush();
        return socket.toByteArray();
    }

    // a DataRow of one text column as the protocol frames it: 'D', the length, the column count, the value
    private static byte[] dataRow(final String text) throws IOException {
        final ByteArrayOutputStream row = new ByteArrayOutputStream();
        final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        final int length = 4 + 2 + 4 + bytes.length;
        row.write('D');
        row.write(new byte[] {(byte) (length >>> 24), (byte) (length >>> 16), (byte) (length >>> 8), (byte) length});
        row.write(new byte[] {0, 1});
        row.write(new byte[] {(byte) (bytes.length >>> 24), (byte) (bytes.length >>> 16), (byte) (bytes.length >>> 8),
                (byte) bytes.length});
        row.write(bytes);
        return row.toByteArray();
    }
}
