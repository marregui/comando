package dev.marregui.comando.core.intent;

/** How text from Claude enters a panel's editor. */
public enum EditMode {
    /** Replace the whole document. */
    REPLACE,
    /** Insert at the caret. */
    INSERT,
    /** Append at the end, on a new paragraph. */
    APPEND
}
