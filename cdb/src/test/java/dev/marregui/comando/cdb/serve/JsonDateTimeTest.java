package dev.marregui.comando.cdb.serve;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// The package's date-time text against `java.time`: micros since the epoch as `yyyy-MM-dd`, the separator, then
/// `HH:mm:ss.ffffff`, four year digits for the years 0 to 9999 and the year as a long prints otherwise. Random micros
/// come from a seed printed and replayed through `cdb.serve.seed`.
class JsonDateTimeTest {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSSSSS");
    private static final long MICROS_PER_SECOND = 1_000_000L;
    // 0000-01-01T00:00:00Z and 10000-01-01T00:00:00Z
    private static final long YEAR_0 =
            LocalDateTime.of(0, 1, 1, 0, 0).toEpochSecond(ZoneOffset.UTC) * MICROS_PER_SECOND;
    private static final long YEAR_10000 =
            LocalDateTime.of(10_000, 1, 1, 0, 0).toEpochSecond(ZoneOffset.UTC) * MICROS_PER_SECOND;

    /// A micro either side of the years 0 and 10000, the epoch, leap days and the long's extremes.
    @Test
    void edgesMatchJavaTime() {
        final long[] edges = {YEAR_0 - 1L, YEAR_0, YEAR_0 + 1L, YEAR_10000 - 1L, YEAR_10000, YEAR_10000 + 1L, -1L, 0L,
            1L, micros(2024, 2, 29), micros(2000, 2, 29), micros(1900, 3, 1), micros(-1, 12, 31) + 86_399_999_999L,
            Long.MIN_VALUE, Long.MAX_VALUE, Long.MIN_VALUE + 1L, Long.MAX_VALUE - 1L};
        for (final long micros : edges) {
            assertThat(JsonWriter.dateTime(new StringBuilder(), micros, ' ').toString())
                    .as("micros %d", micros).isEqualTo(expected(micros, ' '));
            assertThat(JsonWriter.fourDigitYear(micros)).as("micros %d", micros).isEqualTo(fourDigits(micros));
        }
    }

    /// A million micros, half over the long's whole range and half within the four-digit years.
    @Test
    void randomMicrosMatchJavaTime() {
        final long seed = Long.getLong("cdb.serve.seed", System.nanoTime());
        System.out.println("JsonDateTimeTest.randomMicrosMatchJavaTime seed=" + seed);
        final SplittableRandom random = new SplittableRandom(seed);
        for (int i = 0; i < 1_000_000; i++) {
            final long micros = i % 2 == 0 ? random.nextLong() : random.nextLong(YEAR_0, YEAR_10000);
            final char separator = i % 3 == 0 ? 'T' : ' ';
            assertThat(JsonWriter.dateTime(new StringBuilder(), micros, separator).toString())
                    .as("micros %d", micros).isEqualTo(expected(micros, separator));
            assertThat(JsonWriter.fourDigitYear(micros)).as("micros %d", micros).isEqualTo(fourDigits(micros));
        }
    }

    private static String expected(final long micros, final char separator) {
        final LocalDateTime time = dateTime(micros);
        final int year = time.getYear();
        final String yearText = fourDigits(micros) ? String.format("%04d", year) : Integer.toString(year);
        return yearText + String.format("-%02d-%02d", time.getMonthValue(), time.getDayOfMonth()) + separator
                + TIME.format(time);
    }

    private static boolean fourDigits(final long micros) {
        final int year = dateTime(micros).getYear();
        return year >= 0 && year <= 9_999;
    }

    private static LocalDateTime dateTime(final long micros) {
        return LocalDateTime.ofEpochSecond(Math.floorDiv(micros, MICROS_PER_SECOND),
                (int) Math.floorMod(micros, MICROS_PER_SECOND) * 1_000, ZoneOffset.UTC);
    }

    private static long micros(final int year, final int month, final int day) {
        return LocalDateTime.of(year, month, day, 0, 0).toEpochSecond(ZoneOffset.UTC) * MICROS_PER_SECOND;
    }
}
