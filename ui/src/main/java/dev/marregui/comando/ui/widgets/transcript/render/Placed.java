package dev.marregui.comando.ui.widgets.transcript.render;

/** A text box at its position in the canvas, in document order; selection and find walk these. */
record Placed(TextBox box, int x, int y, Item item) {

    boolean contains(final int px, final int py) {
        return py >= y && py < y + box.height();
    }
}
