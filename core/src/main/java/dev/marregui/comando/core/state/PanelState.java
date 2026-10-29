package dev.marregui.comando.core.state;

import dev.marregui.comando.core.model.PanelId;

import java.util.Objects;

/**
 * An editor panel's bindings. Its text lives outside the state, in the panel's file.
 *
 * @param connection bound connection name, empty when none
 * @param session    bound session name, empty when none
 * @param document   true for a file opened from outside comando's panels folder (a document from the Files tab):
 *                   closing it drops it, while a panel of comando's own is kept, closed, until it is removed
 */
public record PanelState(PanelId id, String title, String connection, String session, boolean document) {

    public PanelState {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(session, "session");
    }

    /** A panel of comando's own. */
    public PanelState(PanelId id, String title, String connection, String session) {
        this(id, title, connection, session, false);
    }

    public PanelState withConnection(String name) {
        return new PanelState(id, title, name, session, document);
    }

    public PanelState withSession(String name) {
        return new PanelState(id, title, connection, name, document);
    }

    public PanelState withTitle(String newTitle) {
        return new PanelState(id, newTitle, connection, session, document);
    }
}
