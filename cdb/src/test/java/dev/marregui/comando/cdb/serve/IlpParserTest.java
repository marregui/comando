package dev.marregui.comando.cdb.serve;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/// Line protocol into a recording appender: every field type, every escape, each precision, and malformed lines
/// refused with their line and offset.
class IlpParserTest {

    private static final long NOW_MICROS = 1_763_117_385_000_000L;

    /// One row with a tag and a field of every type, its timestamp in nanoseconds by default.
    @Test
    void parsesEveryFieldType() {
        final RecordingAppender sink = new RecordingAppender();

        final int rows = IlpParser.parse("m,host=a i=-42i,d=1.5,e=-1e3,n=NaN,b=t,s=\"x y\",ts=5t 1000000000", sink,
                Precision.of(null), NOW_MICROS);

        assertThat(rows).isEqualTo(1);
        assertThat(sink.pending).containsExactly("table(m)", "symbol(host=a)", "long(i=-42)", "double(d=1.5)",
                "double(e=-1000.0)", "double(n=NaN)", "boolean(b=true)", "string(s=x y)", "timestamp(ts=5)",
                "at(1000000)");
    }

    /// Each spelling of a boolean QuestDB takes.
    @Test
    void parsesEveryBooleanSpelling() {
        for (final String word : List.of("t", "T", "true", "True", "TRUE")) {
            final RecordingAppender sink = new RecordingAppender();
            IlpParser.parse("m b=" + word + " 1", sink, Precision.MICROS, NOW_MICROS);
            assertThat(sink.pending).as(word).containsExactly("table(m)", "boolean(b=true)", "at(1)");
        }
        for (final String word : List.of("f", "F", "false", "False", "FALSE")) {
            final RecordingAppender sink = new RecordingAppender();
            IlpParser.parse("m b=" + word + " 1", sink, Precision.MICROS, NOW_MICROS);
            assertThat(sink.pending).as(word).containsExactly("table(m)", "boolean(b=false)", "at(1)");
        }
    }

    /// A backslash takes a space, a comma or an equals sign literally in names and tags, and a quote or a backslash
    /// in a quoted string.
    @Test
    void unescapesNamesTagsAndStrings() {
        final RecordingAppender sink = new RecordingAppender();

        IlpParser.parse("my\\ table,ta\\,g\\=1=v\\ a\\,b f\\=x=\"q\\\"u\\\\o\" 1", sink, Precision.MICROS,
                NOW_MICROS);

        assertThat(sink.pending).containsExactly("table(my table)", "symbol(ta,g=1=v a,b)", "string(f=x=q\"u\\o)",
                "at(1)");
    }

    /// A quoted string holds a line break, raw or escaped, without ending the row.
    @Test
    void quotedStringHoldsLineBreaks() {
        final RecordingAppender sink = new RecordingAppender();

        final int rows = IlpParser.parse("m s=\"a\\\nb\",r=\"c\nd\" 1\nm f=1i 2", sink, Precision.MICROS,
                NOW_MICROS);

        assertThat(rows).isEqualTo(2);
        assertThat(sink.pending).containsExactly("table(m)", "string(s=a\nb)", "string(r=c\nd)", "at(1)",
                "table(m)", "long(f=1)", "at(2)");
    }

    /// The row timestamp in each precision, nanoseconds rounding down, also before the epoch.
    @Test
    void convertsEachPrecisionToMicros() {
        assertThat(atOf("1500", Precision.of("n"))).isEqualTo(1L);
        assertThat(atOf("-1", Precision.of("n"))).isEqualTo(-1L);
        assertThat(atOf("1500", Precision.of("u"))).isEqualTo(1_500L);
        assertThat(atOf("2", Precision.of("ms"))).isEqualTo(2_000L);
        assertThat(atOf("3", Precision.of("s"))).isEqualTo(3_000_000L);
        assertThat(atOf("7000", Precision.of(null))).isEqualTo(7L);
    }

    /// A precision QuestDB does not name is refused.
    @Test
    void refusesAnUnknownPrecision() {
        assertThatThrownBy(() -> Precision.of("us")).isInstanceOf(IllegalArgumentException.class);
    }

    /// A row without a timestamp takes the request's time.
    @Test
    void rowWithoutTimestampTakesNow() {
        final RecordingAppender sink = new RecordingAppender();

        IlpParser.parse("m f=1i", sink, Precision.NANOS, NOW_MICROS);

        assertThat(sink.pending).containsExactly("table(m)", "long(f=1)", "at(" + NOW_MICROS + ')');
    }

    /// CRLF ends a line as LF does; blank lines are skipped and counted.
    @Test
    void skipsBlankLinesAndTakesCrLf() {
        final RecordingAppender sink = new RecordingAppender();

        final int rows = IlpParser.parse("a f=1i 1\r\n\r\nb f=2i 2\n\n", sink, Precision.MICROS, NOW_MICROS);

        assertThat(rows).isEqualTo(2);
        assertThat(sink.pending).containsExactly("table(a)", "long(f=1)", "at(1)", "table(b)", "long(f=2)", "at(2)");
        assertThat(refusal("a f=1i 1\r\n\r\nb f=x 2").line()).isEqualTo(3);
    }

    /// A field value that is no number, on the second line.
    @Test
    void refusesABadFieldValueWithItsLineAndOffset() {
        final IlpException e = refusal("t f=1i 1\nt f=abc 2");

        assertThat(e.line()).isEqualTo(2);
        assertThat(e.offset()).isEqualTo(4);
    }

    /// A tag without its equals sign.
    @Test
    void refusesATagWithoutEquals() {
        final IlpException e = refusal("t,tag f=1i");

        assertThat(e.line()).isEqualTo(1);
        assertThat(e.offset()).isEqualTo(5);
    }

    /// A string that never closes, at its opening quote.
    @Test
    void refusesAnUnclosedString() {
        final IlpException e = refusal("t f=1i 1\nt f=\"open");

        assertThat(e.line()).isEqualTo(2);
        assertThat(e.offset()).isEqualTo(4);
    }

    /// Every other malformed shape, each at the character that breaks it.
    @Test
    void refusesEachMalformedShapeAtItsOffset() {
        assertThat(offsetOf("t f=1i 12x")).as("timestamp").isEqualTo(7);
        assertThat(offsetOf("t")).as("no fields").isEqualTo(1);
        assertThat(offsetOf(",a=b f=1i")).as("no table").isEqualTo(0);
        assertThat(offsetOf("t,a= f=1i")).as("empty tag value").isEqualTo(4);
        assertThat(offsetOf("t f=1.5i")).as("integer").isEqualTo(4);
        assertThat(offsetOf("t f=1.5t")).as("timestamp field").isEqualTo(4);
        assertThat(offsetOf("t f=1i 1 x")).as("after timestamp").isEqualTo(8);
        assertThat(offsetOf("t s=\"a\"x 1")).as("after field").isEqualTo(7);
        assertThat(offsetOf("t f=1i,")).as("no field name").isEqualTo(7);
        assertThat(offsetOf("t f=")).as("no field value").isEqualTo(4);
        assertThat(offsetOf("t f")).as("no equals").isEqualTo(3);
        assertThat(offsetOf("t,a=b\\")).as("unfinished escape").isEqualTo(5);
        assertThat(offsetOf("t f=1d")).as("java-only double").isEqualTo(4);
        assertThat(offsetOf("t f=0x1p3")).as("hex double").isEqualTo(4);
        assertThat(offsetOf("t f=tRuE")).as("mixed-case boolean").isEqualTo(4);
    }

    /// A timestamp whose conversion to micros overflows.
    @Test
    void refusesATimestampOutOfRange() {
        final IlpException e = catchThrowableOfType(IlpException.class,
                () -> IlpParser.parse("t f=1i 9223372036854775807", new RecordingAppender(), Precision.SECONDS,
                        NOW_MICROS));

        assertThat(e.offset()).isEqualTo(7);
    }

    /// A row the sink refuses stops the parse at its line, with the sink's exception as the cause and the rows
    /// before it already in the sink.
    @Test
    void sinkRefusalCarriesItsLine() {
        final RecordingAppender sink = new RecordingAppender(2);

        final IlpException e = catchThrowableOfType(IlpException.class,
                () -> IlpParser.parse("a f=1i 1\nb f=2i 2\nc f=3i 3", sink, Precision.MICROS, NOW_MICROS));

        assertThat(e.line()).isEqualTo(2);
        assertThat(e.offset()).isEqualTo(-1);
        assertThat(e.getCause()).isInstanceOf(CdbException.class);
        assertThat(sink.pending).startsWith("table(a)", "long(f=1)", "at(1)");
    }

    private static long atOf(final String timestamp, final Precision precision) {
        final RecordingAppender sink = new RecordingAppender();
        IlpParser.parse("m f=1i " + timestamp, sink, precision, NOW_MICROS);
        return Long.parseLong(sink.pending.getLast().substring(3, sink.pending.getLast().length() - 1));
    }

    private static IlpException refusal(final String body) {
        return catchThrowableOfType(IlpException.class,
                () -> IlpParser.parse(body, new RecordingAppender(), Precision.MICROS, NOW_MICROS));
    }

    private static int offsetOf(final String line) {
        final IlpException e = refusal(line);
        assertThat(e).as(line).isNotNull();
        assertThat(e.line()).as(line).isEqualTo(1);
        return e.offset();
    }
}
