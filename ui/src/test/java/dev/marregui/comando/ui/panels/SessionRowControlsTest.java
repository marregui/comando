package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.state.SessionState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionRowControlsTest {

    private static SessionState closed(String conversation, String checkpoint) {
        SessionSpec spec = SessionSpec.fresh("s", "/tmp").withCheckpoint(checkpoint);
        return SessionState.closed(conversation.isEmpty() ? spec : spec.resumable(conversation));
    }

    @Test
    void theFreshStartShowsOnlyForAStoppedSessionWithAConversation() {
        assertThat(SessionBar.showsFresh(closed("", "/reflect"))).as("nothing to leave behind").isFalse();
        assertThat(SessionBar.showsFresh(closed("conv", "/reflect"))).isTrue();
        assertThat(SessionBar.showsFresh(closed("conv", "/reflect").withStatus(Status.OPEN, ""))).as("running: unplug first").isFalse();
        assertThat(SessionBar.showsFresh(closed("conv", "/reflect").withStatus(Status.CLOSING, ""))).isFalse();
        assertThat(SessionBar.showsFresh(closed("conv", "/reflect").withStatus(Status.FAILED, "exit 1"))).as("a dead session can start over").isTrue();
    }

    @Test
    void theCheckpointShowsWhileTheSessionHasACommand() {
        assertThat(SessionBar.showsCheckpoint(closed("", "/reflect"))).isTrue();
        assertThat(SessionBar.showsCheckpoint(closed("", ""))).isFalse();
        assertThat(SessionBar.showsCheckpoint(closed("conv", "/reflect").withStatus(Status.OPEN, ""))).isTrue();
        assertThat(SessionSpec.fresh("s", "/tmp").checkpoint()).isEqualTo(SessionSpec.DEFAULT_CHECKPOINT);
    }
}
