package dev.marregui.comando.core.result;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [Formats] writes fixed-width UTC instants and compact elapsed-time labels into a caller buffer.
///
/// - Epoch microseconds retain their instant across the supported range.
/// - Millisecond ranges select milliseconds, seconds, or minutes and seconds.
class FormatsTest {

    /// [Formats#appendMicrosUtc] emits 27-character UTC timestamps for zero, negative, fractional, and far-future
    /// epoch-microsecond values, matching `Instant` even where its textual form omits trailing zeroes.
    @Test
    void microsMatchJavaTime() {
        final long[] samples = {0L, 1L, 999_999L, 1_633_046_400_123_456L, -1L, -86_400_000_001L, 253_402_300_799_999_999L, 951_782_400_000_000L};
        final StringBuilder sb = new StringBuilder();
        for (final long micros : samples) {
            sb.setLength(0);
            Formats.appendMicrosUtc(sb, micros);
            final Instant expected = Instant.EPOCH.plus(micros, ChronoUnit.MICROS);
            final String iso = expected.toString(); // trims zeros, so compare the parsed value
            assertThat(Instant.parse(sb.toString())).as("micros %d -> %s", micros, iso).isEqualTo(expected);
            assertThat(sb.toString()).hasSize(27);
        }
    }

    /// [Formats#appendMillis] changes units at one second and one minute, retaining a tenth of a second below a minute.
    @Test
    void durations() {
        final StringBuilder sb = new StringBuilder();
        Formats.appendMillis(sb, 850);
        sb.append('|');
        Formats.appendMillis(sb, 12_400);
        sb.append('|');
        Formats.appendMillis(sb, 185_000);
        assertThat(sb).hasToString("850 ms|12.4 s|3 m 05 s");
    }
}
