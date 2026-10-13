package dev.marregui.comando.ui.widget.capability;

/** A widget whose selection, or whole content, copies as text. */
public interface Copyable {

    String copyText();

    /** Whether there is anything to copy now; ⌘⇧C is disabled otherwise. Cheap: it never builds the text. */
    default boolean canCopy() {
        return true;
    }
}
