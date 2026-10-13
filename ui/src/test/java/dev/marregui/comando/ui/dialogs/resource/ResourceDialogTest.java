package dev.marregui.comando.ui.dialogs.resource;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceDialogTest {

    @Test
    void aStartedSessionsNewIdReachesTheFormAndTheUsersEditStays() {
        Map<String, String> filled = Map.of("name", "edge", "effort", "", "sessionId", "A", "startMode", "FORK");
        Map<String, String> form = Map.of("name", "edge", "effort", "high", "sessionId", "A", "startMode", "FORK");
        Map<String, String> stored = Map.of("name", "edge", "effort", "", "sessionId", "B", "startMode", "RESUME");

        assertThat(ResourceDialog.merge(form, filled, stored))
                .containsEntry("effort", "high")
                .containsEntry("sessionId", "B")
                .containsEntry("startMode", "RESUME");
    }

    @Test
    void aFieldTheUserChangedWinsOverTheStoredChange() {
        Map<String, String> filled = Map.of("sessionId", "A");
        assertThat(ResourceDialog.merge(Map.of("sessionId", "C"), filled, Map.of("sessionId", "B"))).containsEntry("sessionId", "C");
    }
}
