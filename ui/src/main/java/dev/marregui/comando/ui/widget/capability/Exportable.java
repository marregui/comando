package dev.marregui.comando.ui.widget.capability;

import java.io.IOException;
import java.nio.file.Path;

/**
 * A widget that saves its content to a file. Saving has two steps so the window never waits for the disk:
 * {@link #prepare} runs on the EDT and takes what the file needs; the returned {@link Export} writes it on
 * another thread, so it must not touch Swing or the widget's mutable fields.
 */
public interface Exportable {

    /** File name suggested in the save dialog, with extension. */
    String exportName();

    /** On the EDT: a snapshot of the content and the code that writes it. */
    Export prepare();

    /** Writes a prepared snapshot; runs off the EDT. */
    @FunctionalInterface
    interface Export {
        void writeTo(Path file) throws IOException;
    }
}
