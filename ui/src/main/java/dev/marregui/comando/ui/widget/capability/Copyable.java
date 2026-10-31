package dev.marregui.comando.ui.widget.capability;

/** A widget whose selection, or whole content, copies as text. */
public interface Copyable {

    String copyText();

    /**
     * Whether there is anything to copy now; ⌘⇧C is disabled otherwise. Cheap: it never builds the text. No default,
     * so each widget answers from its own state rather than enabling the command on an empty view.
     */
    boolean canCopy();
}
