package dev.marregui.comando.ui.shell;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import dev.marregui.comando.core.files.ByteScan;
import dev.marregui.comando.core.files.Mapped;

/** Which files open in a panel: text small enough to edit; the rest shows read-only in the side pane. */
final class OpenFiles {

    /** The largest file opened as a panel. */
    static final long MAX_PANEL_BYTES = 2L * 1024 * 1024;

    private OpenFiles() {
    }

    /** At most {@link #MAX_PANEL_BYTES} and not binary ({@link ByteScan#isBinary}); called off the EDT. */
    static boolean isText(final Path file) {
        try {
            if (Files.size(file) > MAX_PANEL_BYTES) {
                return false;
            }
            return Mapped.read(file, m -> !ByteScan.isBinary(m.segment()));
        } catch (final IOException e) {
            return true; // the panel's own read says why it cannot open
        }
    }
}
