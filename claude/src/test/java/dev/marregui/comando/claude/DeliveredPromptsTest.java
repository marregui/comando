package dev.marregui.comando.claude;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.transcript.Block;

import static org.assertj.core.api.Assertions.assertThat;

/// [DeliveredPrompts] is a session's record of the prompts comando delivered, read back against its stored
/// conversation.
///
/// - The file is versioned, says when it began, survives a new read, and keeps at most its cap, the oldest going
///   first.
/// - A stored prompt matches a delivery by its text and a time from just before the send to a day after it, and each
///   delivery matches one stored prompt.
/// - With no file, or for a stored prompt from before the file began, the prompt's header decides, as before
///   records; a file of a later version is left as it is, and says why.
class DeliveredPromptsTest {

    private static final String HEADED = "[from session x]\nwrite it";
    private static final long SENT_MILLIS = 1_800_000_000_000L;

    @TempDir
    Path tmp;

    /// Two deliveries written and read back by a new reader: the file starts with its version line and when it began,
    /// then a line each, and both prompts match, the text stored stripped as Claude Code stores it.
    @Test
    void deliveriesSurviveANewRead() throws Exception {
        final Path file = DeliveredPrompts.fileOf(tmp.resolve("delivered"), "work");
        final DeliveredPrompts written = DeliveredPrompts.read(file);
        written.add(SENT_MILLIS, HEADED + "\n");
        written.add(SENT_MILLIS + 1_000, "[from instance alice]\nlook");
        final List<String> lines = Files.readAllLines(file);
        assertThat(lines).hasSize(4);
        assertThat(lines.getFirst()).isEqualTo("# comando delivered, version 1");
        assertThat(lines.get(1)).isEqualTo("began\t" + SENT_MILLIS);
        final DeliveredPrompts.Matcher m = DeliveredPrompts.read(file).matcher();
        assertThat(m.delivered(HEADED, SENT_MILLIS + 20)).isTrue();
        assertThat(m.delivered("[from instance alice]\nlook", SENT_MILLIS + 1_020)).isTrue();
    }

    /// With no file, a stored prompt headed `[from ` reads as delivered and one without as typed; once a delivery is
    /// recorded, the record decides, and the same header on a prompt it does not hold is the user's.
    @Test
    void withoutAFileTheHeaderDecides() {
        final DeliveredPrompts none = DeliveredPrompts.read(DeliveredPrompts.fileOf(tmp, "work"));
        assertThat(none.matcher().delivered(HEADED, SENT_MILLIS)).isTrue();
        assertThat(none.matcher().delivered("write it", SENT_MILLIS)).isFalse();
        assertThat(DeliveredPrompts.NONE.matcher().delivered(HEADED, SENT_MILLIS)).isTrue();
        none.add(SENT_MILLIS, "[from session y]\nother");
        assertThat(none.matcher().delivered(HEADED, SENT_MILLIS)).isFalse();
    }

    /// A stored line matches from five seconds before the send to a day after it, and at any time when it has none; a
    /// line outside that window with the same text is the user's. An earlier delivery began the file, so every line
    /// read here is from after it.
    @Test
    void aStoredLineMatchesWithinItsWindow() {
        final DeliveredPrompts d = DeliveredPrompts.read(DeliveredPrompts.fileOf(tmp, "work"));
        d.add(SENT_MILLIS - 60_000, "[from session y]\nfirst");
        d.add(SENT_MILLIS, HEADED);
        assertThat(d.matcher().delivered(HEADED, SENT_MILLIS - DeliveredPrompts.EARLY_MILLIS)).isTrue();
        assertThat(d.matcher().delivered(HEADED, SENT_MILLIS - DeliveredPrompts.EARLY_MILLIS - 1)).isFalse();
        assertThat(d.matcher().delivered(HEADED, SENT_MILLIS + DeliveredPrompts.LATE_MILLIS)).isTrue();
        assertThat(d.matcher().delivered(HEADED, SENT_MILLIS + DeliveredPrompts.LATE_MILLIS + 1)).isFalse();
        assertThat(d.matcher().delivered(HEADED, Block.UNKNOWN_TIME)).isTrue();
        assertThat(d.matcher().delivered("[from session x]\nwrite that", SENT_MILLIS)).isFalse();
    }

    /// One delivery matches one stored prompt in a read: the same text stored twice is delivered once, then typed; a
    /// new read of the conversation matches it again.
    @Test
    void aDeliveryMatchesOneStoredPromptARead() {
        final DeliveredPrompts d = DeliveredPrompts.read(DeliveredPrompts.fileOf(tmp, "work"));
        d.add(SENT_MILLIS, HEADED);
        final DeliveredPrompts.Matcher m = d.matcher();
        assertThat(m.delivered(HEADED, SENT_MILLIS + 20)).isTrue();
        assertThat(m.delivered(HEADED, SENT_MILLIS + 60_000)).isFalse();
        assertThat(d.matcher().delivered(HEADED, SENT_MILLIS + 20)).isTrue();
    }

    /// Past its cap the file keeps the newest deliveries: the first one written no longer matches, the last does.
    @Test
    void theFileKeepsAtMostItsCap() throws Exception {
        final Path file = DeliveredPrompts.fileOf(tmp, "work");
        final DeliveredPrompts d = DeliveredPrompts.read(file);
        for (int i = 0; i <= DeliveredPrompts.CAP; i++) {
            d.add(SENT_MILLIS + i, "[from session x]\nprompt " + i);
        }
        assertThat(Files.readAllLines(file)).hasSize(DeliveredPrompts.CAP + 2);
        final DeliveredPrompts.Matcher m = DeliveredPrompts.read(file).matcher();
        assertThat(m.delivered("[from session x]\nprompt 0", SENT_MILLIS)).isFalse();
        assertThat(m.delivered("[from session x]\nprompt " + DeliveredPrompts.CAP, SENT_MILLIS)).isTrue();
    }

    /// A file a later comando wrote is read as none, headers deciding, says why for the user, and a delivery adds
    /// nothing to it.
    @Test
    void aFileOfALaterVersionIsLeftAsItIs() throws Exception {
        final Path file = DeliveredPrompts.fileOf(tmp, "work");
        final String later = "# comando delivered, version 2\nsomething new\n";
        Files.writeString(file, later);
        final DeliveredPrompts d = DeliveredPrompts.read(file);
        assertThat(d.matcher().delivered(HEADED, SENT_MILLIS)).isTrue();
        assertThat(d.refused()).as("the reason told to the user").isNotEmpty();
        d.add(SENT_MILLIS, "write it");
        assertThat(Files.readString(file)).isEqualTo(later);
    }

    /// A session from before records holds a delivered prompt; its first delivery since begins the file. Read back, the
    /// earlier prompt keeps its sender by its header, the new delivery matches, and the same header typed after the
    /// file began is the user's.
    @Test
    void aPromptFromBeforeTheFileKeepsItsHeader() {
        final Path file = DeliveredPrompts.fileOf(tmp, "work");
        DeliveredPrompts.read(file).add(SENT_MILLIS, "[from session y]\nlater");
        final DeliveredPrompts.Matcher m = DeliveredPrompts.read(file).matcher();
        assertThat(m.delivered(HEADED, SENT_MILLIS - 60_000)).as("delivered before the file").isTrue();
        assertThat(m.delivered("[from session y]\nlater", SENT_MILLIS + 20)).as("the recorded one").isTrue();
        assertThat(m.delivered(HEADED, SENT_MILLIS + 60_000)).as("typed after it began").isFalse();
        assertThat(m.delivered("write it", SENT_MILLIS - 60_000)).as("no header before it").isFalse();
    }

    /// A session's name becomes one file name in the folder, whatever it holds: `..` and a slash stay inside it.
    @Test
    void anyNameIsOneFileInTheFolder() {
        for (final String name : List.of("..", ".", "a/b", "comando · b", "*")) {
            final Path file = DeliveredPrompts.fileOf(tmp, name);
            assertThat(file.getParent()).as(name).isEqualTo(tmp);
            assertThat(file.getFileName().toString()).as(name).doesNotContain("/").isNotIn(".", "..");
        }
    }
}
