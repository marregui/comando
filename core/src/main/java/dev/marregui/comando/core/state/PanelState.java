package dev.marregui.comando.core.state;

import dev.marregui.comando.core.model.PanelId;

import java.util.Objects;

/**
 * An editor panel's bindings. Its text lives outside the state, in the panel's document.
 *
 * @param connection bound connection name, empty when none
 * @param session    bound session name, empty when none
 */
public record PanelState(PanelId id, String title, String connection, String session) {

    public PanelState {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(session, "session");
    }

    public PanelState withConnection(String name) {
        return new PanelState(id, title, name, session);
    }

    public PanelState withSession(String name) {
        return new PanelState(id, title, connection, name);
    }

    public PanelState withTitle(String newTitle) {
        return new PanelState(id, newTitle, connection, session);
    }
}
