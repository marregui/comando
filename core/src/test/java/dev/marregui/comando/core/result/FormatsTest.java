package dev.marregui.comando.core.result;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class FormatsTest {

    @Test
    void microsMatchJavaTime() {
        long[] samples = {0L, 1L, 999_999L, 1_633_046_400_123_456L, -1L, -86_400_000_001L, 253_402_300_799_999_999L, 951_782_400_000_000L};
        StringBuilder sb = new StringBuilder();
        for (long micros : samples) {
            sb.setLength(0);
            Formats.appendMicrosUtc(sb, micros);
            Instant expected = Instant.EPOCH.plus(micros, ChronoUnit.MICROS);
            String iso = expected.toString(); // trims zeros, so compare the parsed value
            assertThat(Instant.parse(sb.toString())).as("micros %d -> %s", micros, iso).isEqualTo(expected);
            assertThat(sb.toString()).hasSize(27);
        }
    }

    @Test
    void durations() {
        StringBuilder sb = new StringBuilder();
        Formats.appendMillis(sb, 850);
        sb.append('|');
        Formats.appendMillis(sb, 12_400);
        sb.append('|');
        Formats.appendMillis(sb, 185_000);
        assertThat(sb).hasToString("850 ms|12.4 s|3 m 05 s");
    }
}
