package dev.marregui.comando.core.view;

import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.state.LowerTab;

/**
 * A change to the window's arrangement that is not application state: which panes show and how the
 * window is split. The UI keeps these values itself and stores them with the window.
 *
 * @param sidebar    show or hide the sidebar
 * @param sidePane   show or hide the side pane
 * @param lowerShare the lower panel's share of the window height, from 0.05 to 0.95; {@code NaN} keeps it
 * @param focus      a panel to focus once the user pauses typing, or null; set for a session's request only
 * @param tab        a lower tab to select with that focus, or null
 * @param session    the session that asked for the focus, checked again when the focus is applied; empty otherwise
 * @param transcript the transcript above the prompt (show) or below it (hide), kept for the next start (record 144)
 */
public record Layout(
        Toggle sidebar,
        Toggle sidePane,
        double lowerShare,
        PanelId focus,
        LowerTab tab,
        String session,
        Toggle transcript
) {

    public enum Toggle { KEEP, SHOW, HIDE }

    public static final Layout KEEP = new Layout(Toggle.KEEP, Toggle.KEEP, Double.NaN);

    public Layout(final Toggle sidebar, final Toggle sidePane, final double lowerShare) {
        this(sidebar, sidePane, lowerShare, null, null, "");
    }

    public Layout(final Toggle sidebar, final Toggle sidePane, final double lowerShare, final PanelId focus) {
        this(sidebar, sidePane, lowerShare, focus, null, "");
    }

    public Layout(final Toggle sidebar, final Toggle sidePane, final double lowerShare, final PanelId focus,
                  final LowerTab tab) {
        this(sidebar, sidePane, lowerShare, focus, tab, "");
    }

    public Layout(
            final Toggle sidebar,
            final Toggle sidePane,
            final double lowerShare,
            final PanelId focus,
            final LowerTab tab,
            final String session
    ) {
        this(sidebar, sidePane, lowerShare, focus, tab, session, Toggle.KEEP);
    }

    public Layout {
        if (!Double.isNaN(lowerShare) && (lowerShare < 0.05 || lowerShare > 0.95)) {
            throw new IllegalArgumentException("lower_share must be between 0.05 and 0.95, was " + lowerShare);
        }
        session = session == null ? "" : session;
        transcript = transcript == null ? Toggle.KEEP : transcript;
    }

    public boolean changesNothing() {
        return sidebar == Toggle.KEEP
                && sidePane == Toggle.KEEP
                && Double.isNaN(lowerShare)
                && focus == null
                && tab == null
                && transcript == Toggle.KEEP;
    }
}
