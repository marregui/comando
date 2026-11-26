package dev.marregui.comando.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Scene;
import javafx.scene.web.WebView;

import dev.marregui.comando.ui.kit.fx.FxToolkit;

/**
 * A child JVM of {@code StoredPageUiTest}: starts JavaFX on the EDT as comando's page view does, opens the page it is
 * given in a WebView whose data folder it is given, writes the page's text into the marker file once the page has
 * loaded, then lives until it is ended, at most {@link #LIFE_MILLIS}.
 */
public final class StoredPageOpener {

    /** Past any wait of the test, so a child the test failed to end still goes away by itself. */
    private static final long LIFE_MILLIS = 120_000L;

    private StoredPageOpener() {
    }

    /** {@code args}: the WebView's data folder, the page, the marker that takes the page's text once it has loaded. */
    public static void main(final String[] args) throws Exception {
        final Path data = Path.of(args[0]);
        final String page = Path.of(args[1]).toUri().toString();
        final Path loaded = Path.of(args[2]);
        SwingUtilities.invokeAndWait(() -> {
            FxToolkit.start();
            final JFXPanel panel = new JFXPanel();
            final JFrame frame = new JFrame("stored page");
            frame.setUndecorated(true);
            frame.setAutoRequestFocus(false);
            frame.add(panel);
            frame.setBounds(-5_000, 0, 400, 300);
            frame.setVisible(true);
            Platform.runLater(() -> {
                final WebView view = new WebView();
                panel.setScene(new Scene(view));
                view.getEngine().setUserDataDirectory(data.toFile());
                view.getEngine().getLoadWorker().stateProperty().addListener((_, _, now) -> {
                    if (now == Worker.State.SUCCEEDED) {
                        try {
                            // written beside it and moved, so the test never reads a marker half written
                            final Path part = loaded.resolveSibling(loaded.getFileName() + ".part");
                            Files.writeString(part, String.valueOf(
                                    view.getEngine().executeScript("document.getElementById('out').textContent")));
                            Files.move(part, loaded, StandardCopyOption.ATOMIC_MOVE);
                        } catch (final IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }
                });
                view.getEngine().load(page);
            });
        });
        Thread.sleep(LIFE_MILLIS);
        System.exit(0);
    }
}
