package dev.marregui.comando.core.state;

import java.util.Objects;

import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;

/**
 * A view placed by Claude or the user.
 *
 * @param session the session that placed it, empty when the user did
 * @param pinned  a pinned view is never replaced: a view shown again under its title opens beside it
 * @param version bumped when the view's spec is replaced, so the widget re-renders
 * @param home    where the view was first placed: a view shown again under its title is found there, wherever the
 *                user moved it since (record 141)
 */
public record ViewState(
        long id,
        String title,
        ViewSpec spec,
        Placement placement,
        boolean pinned,
        String session,
        long version,
        Placement home
) {

    public ViewState {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(home, "home");
    }

    /** A view at home where it is placed. */
    public ViewState(
            final long id,
            final String title,
            final ViewSpec spec,
            final Placement placement,
            final boolean pinned,
            final String session,
            final long version
    ) {
        this(id, title, spec, placement, pinned, session, version, placement);
    }

    public ViewState replaced(final ViewSpec newSpec) {
        return new ViewState(id, title, newSpec, placement, pinned, session, version + 1, home);
    }

    public ViewState withPinned(final boolean newPinned) {
        return new ViewState(id, title, spec, placement, newPinned, session, version, home);
    }

    /** This view moved to {@code where}; its home stays. */
    public ViewState placed(final Placement where) {
        return new ViewState(id, title, spec, where, pinned, session, version, home);
    }
}
