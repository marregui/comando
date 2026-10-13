package dev.marregui.comando.ui.widget.capability;

/** A widget whose content the shared find bar can search. */
public interface Searchable {

    /** Moves to the next (or previous) match of {@code text}; returns the number of matches. */
    int find(String text, boolean forward);

    /** Removes match highlights. */
    void clearFind();
}
