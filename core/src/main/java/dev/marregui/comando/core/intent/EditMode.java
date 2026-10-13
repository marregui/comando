package dev.marregui.comando.core.intent;

/** How text enters a panel's editor. */
public enum EditMode {
    /** A session replaces the whole document, as one undo step. */
    REPLACE,
    /** A session inserts a block of its own after the block that ran last, else at the end; the caret stays. */
    INSERT,
    /** A session appends a block at the end; the caret stays. */
    APPEND,
    /** The user's own insert, from the schema explorer: at the caret, replacing the selection, as typing would. */
    CARET
}
