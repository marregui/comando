package dev.marregui.comando.cdb.serve;

import java.util.List;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbAppender;

import static org.assertj.core.api.Assertions.assertThat;

/// A double field's value, read by the parser's exact path or by the JDK's, against `Double.parseDouble` bit for bit,
/// and refused exactly where a token outside `[0-9.+-eE]`, one with no digit, or one the JDK does not take is refused.
/// Random tokens come from a seed printed and replayed through `cdb.serve.seed`.
class IlpNumberTest {

    private static final long NOW_MICROS = 1_763_117_385_000_000L;
    private static final String REFUSED = "refused";

    /// Fifteen digits and sixteen, powers of ten 22 and 23 on both sides, either sign, zeros, the double's extremes,
    /// and the malformed shapes the grammar refuses.
    @Test
    void edgeCasesMatchTheJdk() {
        final List<String> tokens = List.of(
                "123456789012345", "1234567890123456", "999999999999999", "9999999999999999", "9007199254740993",
                "-123456789012345", "+1234567890123456", "1.23456789012345", "1.234567890123456",
                "0.000000000000001", "000000000000000000001.5", "1e22", "1e23", "1E22", "1E23", "1e-22", "1e-23",
                "123456789012345e22", "123456789012345e-22", "123456789012345e23", "1234567890123456e22",
                "0.1e-21", "0.1e23", "10e21", "10e22", "-1e22", "-1e-23", "0", "-0", "+0", "-0.0", "0e400", "0.1",
                "0.3", "5.", ".5", "-.5", ".5e+1", "1.7976931348623157e308", "1.8e308", "4.9e-324", "2e-324",
                "2.2250738585072014E-308", "1e0000000000000000005", "1e-0022", "1e1000", "1e-1000", "1e99999999999",
                "1e", "1e+", "e1", "E", ".", "+", "-", "+.", ".e5", "1.2.3", "--1", "+-1", "1e5e5", "1e1.5", "1-",
                "NaN", "nan", "Infinity", "-Infinity", "0x1p3", "1d", "1f");
        for (final String token : tokens) {
            assertThat(parsed(token)).as(token).isEqualTo(expected(token));
        }
    }

    /// A million decimals of 0 to 20 integer digits and 0 to 20 fraction digits, either sign or none, with or without
    /// an exponent of 0 to 3 digits: every value the same bits as the JDK's, and a third or more of them inside
    /// Clinger's bounds of 15 digits and a power of 22.
    @Test
    void randomDecimalsMatchTheJdkBitForBit() {
        final SplittableRandom random = random("randomDecimalsMatchTheJdkBitForBit");
        int exact = 0;
        final int count = 1_000_000;
        for (int i = 0; i < count; i++) {
            final String token = decimal(random);
            if (withinExactBounds(token)) {
                exact++;
            }
            final String expected = expected(token);
            final String parsed = parsed(token);
            if (!parsed.equals(expected)) {
                assertThat(parsed).as("token %d: %s", i, token).isEqualTo(expected);
            }
        }
        assertThat(exact).as("decimals inside Clinger's bounds").isGreaterThan(count / 3);
    }

    /// A million doubles of random bits, NaN and the infinities left out, printed by `Double.toString` and either
    /// negated or not: each reads back as the JDK reads it, the shortest decimal of up to 17 digits that rounds to it,
    /// so all but the subnormals go through the exact path of up to 19 digits.
    @Test
    void doublesPrintedByTheJdkReadBackBitForBit() {
        final SplittableRandom random = random("doublesPrintedByTheJdkReadBackBitForBit");
        int count = 0;
        while (count < 1_000_000) {
            final double value = Double.longBitsToDouble(random.nextLong());
            if (!Double.isFinite(value)) {
                continue;
            }
            count++;
            final String token = Double.toString(value);
            final String expected = expected(token);
            final String parsed = parsed(token);
            if (!parsed.equals(expected)) {
                assertThat(parsed).as("double %d: %s", count, token).isEqualTo(expected);
            }
        }
    }

    /// Subnormals and the smallest normals, 2^53 and its neighbours, the halfway cases between two doubles that round
    /// to even, the shortest decimals whose rounding carries into a new leading bit, 19 digits up to 2^64 and past
    /// `Long.MAX_VALUE`, and 20 digits, which the JDK reads: each as the JDK reads it, of either sign.
    @Test
    void nineteenDigitEdgeCasesMatchTheJdk() {
        final List<String> tokens = List.of(
                "4.9E-324", "2.4703282292062327E-324", "2.4703282292062328E-324", "1.0E-323",
                "2.225073858507201E-308", "2.2250738585072009E-308", "2.2250738585072014E-308",
                "2.2250738585072011E-308", "4.9406564584124654E-324", "1.7976931348623157E308",
                "1.7976931348623158E308", "1.7976931348623159E308", "9007199254740991", "9007199254740992",
                "9007199254740993", "9007199254740994", "9007199254740995", "18014398509481983", "18014398509481985",
                "9007199254740993.0", "900719925474099.3e1", "1e23", "8.41e21", "7.3177701707893310e15",
                "7.2057594037927933e16", "1.00000000000000011102230246251565404",
                "1.0000000000000001110223024625156540", "1.000000000000000111", "1.000000000000000222",
                "9999999999999999999", "1844674407370955161", "18446744073709551615", "9223372036854775807",
                "9223372036854775808", "9223372036854775807e-19", "1234567890123456789e-300",
                "1234567890123456789e290", "1234567890123456789e-343", "9999999999999999999e-344",
                "12345678901234567890", "0.1234567890123456789", "0.00000000000000000000001234567890123456789",
                "123456789012345678e-325", "1e-325", "1e-326", "1e308", "1e309", "0.0000000000000000000",
                "0000000000000000000000000000001");
        for (final String token : tokens) {
            assertThat(parsed(token)).as(token).isEqualTo(expected(token));
            assertThat(parsed("-" + token)).as("-" + token).isEqualTo(expected("-" + token));
        }
    }

    /// Short strings of `[0-9.+-eE]`, most of them malformed: the parser refuses exactly those today's rule refuses,
    /// and reads the rest as the JDK does.
    @Test
    void randomTokensAreRefusedAsBefore() {
        final SplittableRandom random = random("randomTokensAreRefusedAsBefore");
        final String alphabet = "0123456789.+-eE";
        int refused = 0;
        final int count = 200_000;
        for (int i = 0; i < count; i++) {
            final StringBuilder token = new StringBuilder();
            final int length = 1 + random.nextInt(8);
            for (int c = 0; c < length; c++) {
                token.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            final String expected = expected(token.toString());
            if (expected.equals(REFUSED)) {
                refused++;
            }
            final String parsed = parsed(token.toString());
            if (!parsed.equals(expected)) {
                assertThat(parsed).as("token %d: %s", i, token).isEqualTo(expected);
            }
        }
        assertThat(refused).as("refused tokens").isPositive().isLessThan(count);
    }

    private static SplittableRandom random(final String test) {
        final long seed = Long.getLong("cdb.serve.seed", System.nanoTime());
        System.out.println("IlpNumberTest." + test + " seed=" + seed);
        return new SplittableRandom(seed);
    }

    // digit counts weighted to Clinger's bounds of 15 digits and a power of 22, and past the 19 digits of the exact
    // path
    private static String decimal(final SplittableRandom random) {
        final StringBuilder token = new StringBuilder();
        switch (random.nextInt(3)) {
            case 0 -> token.append('-');
            case 1 -> token.append('+');
            default -> { }
        }
        final int integerDigits = random.nextInt(21);
        digits(random, token, integerDigits);
        final boolean point = integerDigits == 0 || random.nextBoolean();
        if (point) {
            token.append('.');
            digits(random, token, integerDigits == 0 ? 1 + random.nextInt(20) : random.nextInt(21));
        }
        if (random.nextBoolean()) {
            token.append(random.nextBoolean() ? 'e' : 'E');
            switch (random.nextInt(3)) {
                case 0 -> token.append('-');
                case 1 -> token.append('+');
                default -> { }
            }
            digits(random, token, 1 + random.nextInt(random.nextInt(4) == 0 ? 3 : 2));
        }
        return token.toString();
    }

    // a leading zero now and then, so zeros before the first significant digit are counted as the JDK counts them
    private static void digits(final SplittableRandom random, final StringBuilder token, final int count) {
        for (int i = 0; i < count; i++) {
            token.append((char) ('0' + (random.nextInt(4) == 0 ? 0 : random.nextInt(10))));
        }
    }

    // at most 15 significant digits and a power of ten of magnitude at most 22 (Clinger's bounds), the token well
    // formed
    private static boolean withinExactBounds(final String token) {
        final int e = Math.max(token.indexOf('e'), token.indexOf('E'));
        final String mantissa = e < 0 ? token : token.substring(0, e);
        final int exponent = e < 0 ? 0 : Integer.parseInt(token.substring(e + 1));
        final int point = mantissa.indexOf('.');
        final int scale = point < 0 ? 0 : mantissa.length() - point - 1;
        final String digits = mantissa.replace(".", "").replace("-", "").replace("+", "").replaceFirst("^0+", "");
        final int power = exponent - scale;
        return digits.length() <= 15 && power >= -22 && power <= 22;
    }

    // the rule before the exact path: only [0-9.+-eE] with a digit, or NaN, and what the JDK then takes
    private static String expected(final String token) {
        boolean digit = false;
        for (int i = 0; i < token.length(); i++) {
            final char c = token.charAt(i);
            if (c >= '0' && c <= '9') {
                digit = true;
            } else if (c != '.' && c != '-' && c != '+' && c != 'e' && c != 'E') {
                digit = false;
                break;
            }
        }
        if (digit || token.equals("NaN")) {
            try {
                return bits(Double.parseDouble(token));
            } catch (final NumberFormatException _) {
                return REFUSED;
            }
        }
        return REFUSED;
    }

    private static String parsed(final String token) {
        final LastDouble sink = new LastDouble();
        try {
            IlpParser.parse("m f=" + token + " 1", sink, Precision.MICROS, NOW_MICROS);
        } catch (final IlpException _) {
            return REFUSED;
        }
        return bits(sink.value);
    }

    private static String bits(final double value) {
        return "bits=" + Long.toHexString(Double.doubleToRawLongBits(value));
    }

    /// Keeps the last double field; ignores everything else.
    private static final class LastDouble implements CdbAppender {

        double value;

        @Override
        public CdbAppender table(final String table) {
            return this;
        }

        @Override
        public CdbAppender symbol(final String column, final String value) {
            return this;
        }

        @Override
        public CdbAppender stringColumn(final String column, final String value) {
            return this;
        }

        @Override
        public CdbAppender longColumn(final String column, final long value) {
            return this;
        }

        @Override
        public CdbAppender doubleColumn(final String column, final double value) {
            this.value = value;
            return this;
        }

        @Override
        public CdbAppender booleanColumn(final String column, final boolean value) {
            return this;
        }

        @Override
        public CdbAppender timestampColumn(final String column, final long micros) {
            return this;
        }

        @Override
        public void at(final long micros) {
        }

        @Override
        public void flush() {
        }

        @Override
        public void discard() {
        }

        @Override
        public void close() {
        }
    }
}
