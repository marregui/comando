package dev.marregui.comando.core.state;

import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;

import java.util.Objects;

/**
 * A view placed by Claude or the user.
 *
 * @param session the session that placed it, empty when the user did
 * @param pinned  a pinned view is never replaced by Claude
 * @param version bumped when the view's spec is replaced, so the widget re-renders
 */
public record ViewState(long id, String title, ViewSpec spec, Placement placement, boolean pinned, String session, long version) {

    public ViewState {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(session, "session");
    }

    public ViewState replaced(ViewSpec newSpec) {
        return new ViewState(id, title, newSpec, placement, pinned, session, version + 1);
    }

    public ViewState withPinned(boolean newPinned) {
        return new ViewState(id, title, spec, placement, newPinned, session, version);
    }
}
