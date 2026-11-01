package dev.marregui.comando.ui.shell;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.ui.panels.SessionBar;

import static org.assertj.core.api.Assertions.assertThat;

/// [Commands]: the palette's commands, here what they read from what the user types and when they can run.
/// - the checkpoint threshold takes a whole percentage from 10 to 100, a `%` after it allowed; anything else is refused
///   with the reason, and the threshold kept
/// - the session's commands are off while its checkpoint runs, as on the session bar and in the session's menu, and
///   Checkpoint is off while the session stops
class CommandsTest {

    /// A whole percentage from the minimum, 10, to 100 is taken, spaces and a `%` after it allowed; below the minimum
    /// or past 100 is refused as outside; a fraction, a grouped or a signed number, a `%` inside the digits and a word
    /// are refused as no whole percentage; each reason names what was typed.
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "40|",
        "' 10 '|",
        "100%|",
        "'55 %'|",
        "9|Checkpoint threshold kept: 9 is outside 10 to 100; every session checkpoints, at 10% of its window at the earliest",
        "0|Checkpoint threshold kept: 0 is outside 10 to 100; every session checkpoints, at 10% of its window at the earliest",
        "101|Checkpoint threshold kept: 101 is outside 10 to 100; every session checkpoints, at 10% of its window at the earliest",
        "99999999999|Checkpoint threshold kept: 99999999999 is outside 10 to 100; every session checkpoints, at 10% of its window at the earliest",
        "-1|Checkpoint threshold kept: -1 is not a whole percentage",
        "+50|Checkpoint threshold kept: +50 is not a whole percentage",
        "4%0|Checkpoint threshold kept: 4%0 is not a whole percentage",
        "%50|Checkpoint threshold kept: %50 is not a whole percentage",
        "'1,000'|Checkpoint threshold kept: 1,000 is not a whole percentage",
        "1_000|Checkpoint threshold kept: 1_000 is not a whole percentage",
        "40.5|Checkpoint threshold kept: 40.5 is not a whole percentage",
        "half|Checkpoint threshold kept: half is not a whole percentage"
    })
    void theCheckpointThresholdTakesAWholePercentageFromTheMinimumTo100(final String typed, final String refused) {
        assertThat(Commands.thresholdRefusal(typed)).isEqualTo(Optional.ofNullable(refused));
        if (refused == null) {
            assertThat(Commands.thresholdOf(typed)).isBetween(10, 100);
        }
    }

    /// While a session's checkpoint runs, the palette's Checkpoint, Compact, Start a new conversation, Rewrite and
    /// Remove are off for it (record 130), and on for the same session free; Checkpoint is off too while the session
    /// stops, which the reducer refuses.
    @Test
    void theSessionsCommandsAreOffWhileItsCheckpointRuns() {
        final SessionState open = SessionState.closed(SessionSpec.fresh("work", "/w").withCheckpoint("/reflect"))
                .withStatus(Status.OPEN, "").withTurns(1, 1L);
        final SessionState locked = open.withCheckpointTurns(1);
        final SessionState stopped = SessionState.closed(SessionSpec.imported("old", "/w", "abc"));
        final SessionState stoppedLocked = stopped.withTurns(1, 1L).withCheckpointTurns(1);

        assertThat(SessionBar.canCheckpoint(open)).isTrue();
        assertThat(Commands.canCompact(open)).isTrue();
        assertThat(Commands.canRewrite(open, "notes")).isTrue();
        assertThat(Commands.canRemove(open)).isTrue();
        assertThat(Commands.canStartFresh(stopped)).isTrue();

        assertThat(SessionBar.canCheckpoint(locked)).as("checkpoint").isFalse();
        assertThat(Commands.canCompact(locked)).as("compact").isFalse();
        assertThat(Commands.canRewrite(locked, "notes")).as("rewrite").isFalse();
        assertThat(Commands.canRemove(locked)).as("remove").isFalse();
        assertThat(Commands.canStartFresh(stoppedLocked)).as("fresh").isFalse();

        assertThat(SessionBar.canCheckpoint(open.withStatus(Status.CLOSING, ""))).as("stopping").isFalse();
        assertThat(Commands.canRewrite(open, " ")).as("no text").isFalse();
    }
}
