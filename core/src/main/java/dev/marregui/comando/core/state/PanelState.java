package dev.marregui.comando.core.state;

import java.util.Objects;

import dev.marregui.comando.core.model.PanelId;

/**
 * An editor panel's bindings. Its text lives outside the state, in the panel's file.
 *
 * @param connection bound connection name, empty when none
 * @param session    bound session name, empty when none
 * @param document   true for a file opened from outside comando's panels folder (a document from the folder tabs (Kit,
 *                   Brain, Scratchpad)): closing it drops it, while a panel of comando's own is kept, closed, until
 *                   it is removed
 * @param side       true when the panel's tab is in the right-hand partition of the workspace, not the main tabs
 *                   (record 141)
 * @param preview    true for the one preview tab: a file shown for a search's hit, replaced by the next one shown,
 *                   kept once pinned (record 141); never saved
 */
public record PanelState(
        PanelId id,
        String title,
        String connection,
        String session,
        boolean document,
        boolean side,
        boolean preview
) {

    public PanelState {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(session, "session");
    }

    /** A panel of the main tabs, no preview. */
    public PanelState(
            final PanelId id,
            final String title,
            final String connection,
            final String session,
            final boolean document
    ) {
        this(id, title, connection, session, document, false, false);
    }

    /** A panel of comando's own. */
    public PanelState(final PanelId id, final String title, final String connection, final String session) {
        this(id, title, connection, session, false);
    }

    public PanelState withConnection(final String name) {
        return new PanelState(id, title, name, session, document, side, preview);
    }

    public PanelState withSession(final String name) {
        return new PanelState(id, title, connection, name, document, side, preview);
    }

    public PanelState withTitle(final String newTitle) {
        return new PanelState(id, newTitle, connection, session, document, side, preview);
    }

    /** This panel in the partition when {@code inSide}, else in the main tabs. */
    public PanelState withSide(final boolean inSide) {
        return new PanelState(id, title, connection, session, document, inSide, preview);
    }

    /** This panel as the preview tab when {@code shown}, else as a tab that stays. */
    public PanelState withPreview(final boolean shown) {
        return new PanelState(id, title, connection, session, document, side, shown);
    }
}
