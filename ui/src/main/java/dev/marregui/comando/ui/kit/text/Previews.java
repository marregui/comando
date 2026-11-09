package dev.marregui.comando.ui.kit.text;

import java.nio.file.Path;

/**
 * The preview tab of the workspace, where a search hit and a file a session wrote are read (records 141, 143); the
 * shell gives it.
 */
public interface Previews {

    /**
     * Shows {@code file} in the preview tab, replacing the file it showed, {@code marks} marked and the current one
     * in view; {@code takeKeyboard} gives the tab the keyboard, else the keyboard stays where it is.
     */
    void preview(Path file, HitMarks marks, boolean takeKeyboard);

    /** Keeps the preview tab of {@code file} as a tab that stays, with the keyboard in it. */
    void pin(Path file);
}
