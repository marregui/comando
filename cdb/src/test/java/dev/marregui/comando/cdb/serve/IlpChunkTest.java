package dev.marregui.comando.cdb.serve;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.IntSupplier;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// A body held in several buffers parses as the same body in one array: the same calls to the sink in the same
/// order, the same row count, or the same calls for the rows before the first error and the same error with its
/// line and offset, wherever the buffers' ends cut a line, a quoted string holding a line break, an escape, a `\r\n`
/// or a UTF-8 character. Random bodies come from a seed printed and replayed through `cdb.serve.seed`.
class IlpChunkTest {

    private static final long NOW_MICROS = 1_763_117_385_000_000L;
    private static final String[] TABLES = {"m", "cpu", "t\\ x", "日本", "a\\,b"};
    private static final String[] TAGS = {",k=h1", ",k=x\\ y", ",host=é", ",a\\=b=c"};
    private static final String[] VALUES = {"1i", "-7i", "2.5", "1.7976931348623157e308", "0.30000000000000004",
            "12345678901234567", "t", "FALSE", "\"s\"", "\"a\nb\"", "\"q\\\"x\"", "\"back\\\\\"", "\"é\r\n\"", "5t",
            "\"日\\\n本\""};
    private static final byte[] NOISE = ascii("\"\\ ,=\r\nx");

    /// Every buffer size from 1 byte to the body's length, over a body of every case a cut can split: each parses
    /// as the body in one array.
    @Test
    void everyCutOfAFixedBodyParsesAsOneArray() {
        final byte[] body = ("m,host=a\\ b f=1.5,s=\"x\ny\",n=3i 1700000000000000\r\n"
                + "\n"
                + "日本,k=é b=t,q=\"\\\"\\\\\" 1700000000000001\n"
                + "cpu v=12345678901234567,w=0.30000000000000004 1700000000000002\r\n"
                + "m f=\"tail\"").getBytes(StandardCharsets.UTF_8);
        for (int size = 1; size <= body.length; size++) {
            assertThat(chunked(body, size, 0, new SplittableRandom(size))).as("size %d", size)
                    .isEqualTo(whole(body, 0));
        }
    }

    /// Twenty thousand random bodies, some with a byte that breaks a line, some whose sink refuses a row, cut into
    /// buffers of 1 to 64 bytes: each parses as the body in one array, and a share of them fail.
    @Test
    void randomBodiesParseAsOneArray() {
        final long seed = Long.getLong("cdb.serve.seed", System.nanoTime());
        System.out.println("IlpChunkTest.randomBodiesParseAsOneArray seed=" + seed);
        final SplittableRandom random = new SplittableRandom(seed);
        int failed = 0;
        for (int i = 0; i < 20_000; i++) {
            final byte[] body = body(random);
            final int refuseAt = random.nextInt(4) == 0 ? 1 + random.nextInt(4) : 0;
            final int size = 1 + random.nextInt(64);
            final List<String> expected = whole(body, refuseAt);
            if (expected.getLast().startsWith("error")) {
                failed++;
            }
            final List<String> parsed = chunked(body, size, refuseAt, random);
            if (!parsed.equals(expected)) {
                assertThat(parsed).as("seed=%d, body %d, size %d: %s", seed, i, size,
                        new String(body, StandardCharsets.UTF_8)).isEqualTo(expected);
            }
        }
        assertThat(failed).as("bodies that fail").isPositive().isLessThan(20_000);
    }

    private static List<String> whole(final byte[] body, final int refuseAt) {
        final RecordingAppender sink = new RecordingAppender(refuseAt);
        return outcome(sink, () -> IlpParser.parse(body, body.length, sink, Precision.MICROS, NOW_MICROS));
    }

    // the body cut into buffers of size bytes, the last one's bytes past the body random noise the parser must not
    // read
    private static List<String> chunked(final byte[] body, final int size, final int refuseAt,
            final SplittableRandom random) {
        final int count = Math.max(1, (body.length + size - 1) / size);
        final byte[][] chunks = new byte[count][size];
        for (int c = 0; c < count; c++) {
            for (int b = 0; b < size; b++) {
                final int at = c * size + b;
                chunks[c][b] = at < body.length ? body[at] : NOISE[random.nextInt(NOISE.length)];
            }
        }
        final RecordingAppender sink = new RecordingAppender(refuseAt);
        return outcome(sink, () -> IlpParser.parse(chunks, body.length, sink, Precision.MICROS, NOW_MICROS));
    }

    // the sink's calls and the row count, or on an error the calls of the rows before it, whose at() returned, and
    // the error: the server drops a body that fails to parse, so the calls of the row an error cuts short reach no
    // table, and outside the last buffer the parser holds them back until the row's end
    private static List<String> outcome(final RecordingAppender sink, final IntSupplier parse) {
        final List<String> outcome = new ArrayList<>();
        try {
            final int rows = parse.getAsInt();
            outcome.addAll(sink.pending);
            outcome.add("rows=" + rows);
        } catch (final IlpException e) {
            int finished = sink.pending.size();
            while (finished > 0 && !sink.pending.get(finished - 1).startsWith("at(")) {
                finished--;
            }
            outcome.addAll(sink.pending.subList(0, finished));
            outcome.add("error " + e.getMessage() + " line=" + e.line() + " offset=" + e.offset());
        }
        return outcome;
    }

    // up to eight lines of a table, tags, fields and an optional timestamp, blank lines among them, \n or \r\n
    // between; now and then a noise byte anywhere, or no line break at the end
    private static byte[] body(final SplittableRandom random) {
        final StringBuilder text = new StringBuilder();
        final int lines = 1 + random.nextInt(8);
        for (int l = 0; l < lines; l++) {
            if (random.nextInt(8) == 0) {
                text.append('\n');
                continue;
            }
            text.append(TABLES[random.nextInt(TABLES.length)]);
            for (int t = random.nextInt(3); t > 0; t--) {
                text.append(TAGS[random.nextInt(TAGS.length)]);
            }
            text.append(' ');
            for (int f = 1 + random.nextInt(3); f > 0; f--) {
                text.append('f').append(f).append('=').append(VALUES[random.nextInt(VALUES.length)]);
                if (f > 1) {
                    text.append(',');
                }
            }
            if (random.nextBoolean()) {
                text.append(' ').append(1_700_000_000_000_000L + l);
            }
            text.append(random.nextBoolean() ? "\n" : "\r\n");
        }
        if (random.nextInt(4) == 0) {
            text.setLength(text.length() - 1);
        }
        final byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        if (random.nextInt(6) != 0) {
            return bytes;
        }
        final int at = random.nextInt(bytes.length + 1);
        final ByteArrayOutputStream noisy = new ByteArrayOutputStream();
        noisy.write(bytes, 0, at);
        noisy.write(NOISE[random.nextInt(NOISE.length)]);
        noisy.write(bytes, at, bytes.length - at);
        return noisy.toByteArray();
    }

    private static byte[] ascii(final String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
