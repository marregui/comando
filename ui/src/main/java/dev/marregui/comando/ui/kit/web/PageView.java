package dev.marregui.comando.ui.kit.web;

import java.awt.image.BufferedImage;
import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javax.swing.JComponent;

/**
 * A rendered HTML page inside a Swing panel. Methods without a thread named run on the EDT. The futures complete on
 * the page's own thread, never the EDT, so a caller off the EDT may wait on them with a timeout.
 */
public interface PageView extends AutoCloseable {

    /** The component the panel lays out. */
    JComponent component();

    /**
     * Shows {@code file}, a local HTML page; its relative links and images resolve against its folder. Its scripts run
     * only when {@code scripts}: a script on a {@code file:} page can read any file on this machine, so only pages
     * comando's own work made or keeps run them.
     */
    void load(Path file, boolean scripts);

    /** Shows the page again from disk, keeping the scroll position when the page still has it. */
    void reload();

    /**
     * Called on the EDT with every link the user follows that does not lead inside the page itself: another file or a
     * web address. The page does not navigate to it.
     */
    void onLink(Consumer<URI> link);

    /** Draws the page as it shows now. Any thread. */
    CompletableFuture<BufferedImage> snapshot();

    /** The page's title, selection and visible text. Any thread. */
    CompletableFuture<Text> text();

    /** Releases the page; later calls do nothing, futures fail. Idempotent. */
    @Override
    void close();

    /**
     * A page's text as the user sees it.
     *
     * @param title the document's {@code <title>}, empty when it has none
     * @param selection the selected text, empty when none
     * @param body the visible text of the whole document
     */
    record Text(String title, String selection, String body) {
    }
}
