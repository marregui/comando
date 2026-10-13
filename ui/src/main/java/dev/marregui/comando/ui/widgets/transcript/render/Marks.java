package dev.marregui.comando.ui.widgets.transcript.render;

import java.awt.Component;
import java.util.List;

/** What painting needs besides the graphics: the selection and find ranges of each box, and a component for icons. */
interface Marks {

    /** {@code [start, end)} of the selection inside {@code box}; start equals end when none. */
    int[] selection(TextBox box);

    /** The find matches inside {@code box} as {@code [start, end]} pairs, null when none. */
    List<int[]> finds(TextBox box);

    /** The index in {@link #finds} of the current match, -1 when it is in another box. */
    int currentFind(TextBox box);

    Component component();
}
