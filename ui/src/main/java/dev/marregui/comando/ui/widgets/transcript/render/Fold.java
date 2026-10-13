package dev.marregui.comando.ui.widgets.transcript.render;

/**
 * Whether a collapsible item is open, and who opened it. A click is the user's choice and wins over every
 * automatic rule from then on; find opens an item only until the find is cleared.
 */
final class Fold {

    private boolean open;
    private boolean userToggled;
    private boolean revealed;

    Fold(boolean open) {
        this.open = open;
    }

    boolean isOpen() {
        return open;
    }

    boolean userToggled() {
        return userToggled;
    }

    boolean revealed() {
        return revealed;
    }

    void toggle() {
        open = !open;
        userToggled = true;
        revealed = false;
    }

    /** Opens when {@code want} and the user has not chosen; never closes. */
    void openIf(boolean want) {
        if (want && !userToggled) {
            open = true;
            revealed = false;
        }
    }

    /** Opens or closes to {@code want} unless the user has chosen. */
    void follow(boolean want) {
        if (!userToggled) {
            open = want;
            revealed = false;
        }
    }

    /** Opens for a find match; false when already open. */
    boolean reveal() {
        if (open) {
            return false;
        }
        open = true;
        revealed = true;
        return true;
    }

    /**
     * Ends a find's hold on the item: closes it unless {@code keep}, when it stays open as if never found.
     * False when nothing changed: the find did not open it, or the user toggled it since.
     */
    boolean conceal(boolean keep) {
        if (!revealed) {
            return false;
        }
        revealed = false;
        if (keep) {
            return false;
        }
        open = false;
        return true;
    }
}
