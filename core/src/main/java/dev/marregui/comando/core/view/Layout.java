package dev.marregui.comando.core.view;

/**
 * A change to the window's arrangement that is not application state: which panes show and how the
 * window is split. The UI keeps these values itself and stores them with the window.
 *
 * @param sidebar    show or hide the sessions sidebar
 * @param sidePane   show or hide the side pane
 * @param lowerShare the lower panel's share of the window height, in (0, 1); {@code NaN} keeps it
 */
public record Layout(Toggle sidebar, Toggle sidePane, double lowerShare) {

    public enum Toggle { KEEP, SHOW, HIDE }

    public static final Layout KEEP = new Layout(Toggle.KEEP, Toggle.KEEP, Double.NaN);

    public Layout {
        if (!Double.isNaN(lowerShare) && (lowerShare < 0.05 || lowerShare > 0.95)) {
            throw new IllegalArgumentException("lower_share must be between 0.05 and 0.95, was " + lowerShare);
        }
    }

    public boolean changesNothing() {
        return sidebar == Toggle.KEEP && sidePane == Toggle.KEEP && Double.isNaN(lowerShare);
    }
}
