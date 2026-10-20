package dev.marregui.comando.ui.kit.text;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class LogLinesTest {

    private static final long T = Instant.parse("2026-10-20T10:28:52.069Z").toEpochMilli();

    private static String line(long millis, String rest) {
        return Instant.ofEpochMilli(millis) + " " + rest + "\n";
    }

    @Test
    void theEntryIsTheFirstWrittenAtTheTimeOrWithinTheSlackAfterIt() {
        String before = line(T - 5_000, "INFO A earlier");
        String hang = line(T + 2, "WARNING ComandoUi the EDT has not answered for 1000 ms:") + "\tat a.B.c(B.java:1)\n";
        String text = before + hang + line(T + 300, "INFO A next") + line(T + 4_000, "INFO A later");
        assertThat(LogLines.offsetOf(text, T)).isEqualTo(before.length());
        assertThat(LogLines.offsetOf(text, T + 2)).as("written at the very time").isEqualTo(before.length());
        assertThat(LogLines.offsetOf(text, T + 100)).as("the next entry within the slack").isEqualTo(before.length() + hang.length());
        assertThat(LogLines.offsetOf(text, T + 1_500)).as("nothing written within a second after").isEqualTo(-1);
        assertThat(LogLines.offsetOf(text, T - 60_000)).as("older than every entry within reach").isEqualTo(-1);
    }

    @Test
    void aNeighbourWrittenSlightlyOutOfOrderDoesNotHideTheEntry() {
        String first = line(T - 9_000, "INFO A first");
        String text = first + line(T + 1, "WARNING A the entry") + line(T - 4, "INFO B took its time before the entry");
        assertThat(LogLines.offsetOf(text, T)).isEqualTo(first.length());
    }

    @Test
    void linesThatAreNotEntriesAndAWholeSecondAreRead() {
        long whole = Instant.parse("2026-10-20T10:28:52Z").toEpochMilli();
        String text = "\n\tat x.Y.z(Y.java:3)\n2026-10-20 not an instant\n" + line(whole, "INFO A on the second");
        assertThat(LogLines.offsetOf(text, whole)).isEqualTo(text.indexOf("2026-10-20T"));
        assertThat(LogLines.offsetOf("", whole)).isEqualTo(-1);
        assertThat(LogLines.offsetOf(line(whole, "INFO no newline").strip(), whole)).isZero();
    }
}
