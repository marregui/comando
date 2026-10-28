package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.state.SessionState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// When [SessionBar] shows its fresh-start and checkpoint controls.
/// - the fresh start shows only for a stopped or failed session with a conversation
/// - the checkpoint shows while the session has a checkpoint command, running or not
class SessionRowControlsTest {

    private static SessionState closed(String conversation, String checkpoint) {
        SessionSpec spec = SessionSpec.fresh("s", "/tmp").withCheckpoint(checkpoint);
        return SessionState.closed(conversation.isEmpty() ? spec : spec.resumable(conversation));
    }

    /// The fresh start shows for a closed or failed session that has a conversation, and not for one without a
    /// conversation, nor while it is open or closing.
    @Test
    void theFreshStartShowsOnlyForAStoppedSessionWithAConversation() {
        assertThat(SessionBar.showsFresh(closed("", "/reflect"))).as("nothing to leave behind").isFalse();
        assertThat(SessionBar.showsFresh(closed("conv", "/reflect"))).isTrue();
        assertThat(SessionBar.showsFresh(closed("conv", "/reflect").withStatus(Status.OPEN, ""))).as("running: unplug first").isFalse();
        assertThat(SessionBar.showsFresh(closed("conv", "/reflect").withStatus(Status.CLOSING, ""))).isFalse();
        assertThat(SessionBar.showsFresh(closed("conv", "/reflect").withStatus(Status.FAILED, "exit 1"))).as("a dead session can start over").isTrue();
    }

    /// The checkpoint shows whenever the checkpoint command is not empty, also while open, and a fresh spec carries
    /// [SessionSpec#DEFAULT_CHECKPOINT].
    @Test
    void theCheckpointShowsWhileTheSessionHasACommand() {
        assertThat(SessionBar.showsCheckpoint(closed("", "/reflect"))).isTrue();
        assertThat(SessionBar.showsCheckpoint(closed("", ""))).isFalse();
        assertThat(SessionBar.showsCheckpoint(closed("conv", "/reflect").withStatus(Status.OPEN, ""))).isTrue();
        assertThat(SessionSpec.fresh("s", "/tmp").checkpoint()).isEqualTo(SessionSpec.DEFAULT_CHECKPOINT);
    }
}
