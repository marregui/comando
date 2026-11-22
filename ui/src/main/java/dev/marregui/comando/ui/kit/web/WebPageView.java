package dev.marregui.comando.ui.kit.web;

import java.awt.Color;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.JComponent;
import javax.swing.UIManager;

import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.embed.swing.JFXPanel;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.paint.Paint;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;

import dev.marregui.comando.ui.kit.async.Edt;
import dev.marregui.comando.ui.kit.theme.Theme;

/**
 * A page rendered by JavaFX's {@link WebView} inside a {@link JFXPanel}. The view, its engine and the page's address
 * live on the JavaFX application thread, which every call hands its work to; the panel and the link consumer live on
 * the EDT. No Java object is exposed to the page's scripts: the page is read through scripts whose answers are
 * strings.
 */
final class WebPageView implements PageView {

    private static final System.Logger LOG = System.getLogger(WebPageView.class.getName());

    /**
     * Keeps the last selection that was not empty in a page variable, so a click into another component, which clears
     * the page's live selection, does not lose what the user selected.
     */
    private static final String KEEP_SELECTION = """
            if (!window.comandoKeptSelection) {
                window.comandoKeptSelection = { text: '' };
                document.addEventListener('selectionchange', function () {
                    var s = String(window.getSelection());
                    if (s.length > 0) {
                        window.comandoKeptSelection.text = s;
                    }
                });
            }
            """;

    /** The live selection, else the one kept by {@link #KEEP_SELECTION}. */
    private static final String SELECTION = """
            (function () {
                var s = String(window.getSelection());
                if (s.length > 0) {
                    return s;
                }
                var kept = window.comandoKeptSelection;
                return kept ? kept.text : '';
            })()""";

    private static final String BODY_TEXT = "document.body ? document.body.innerText : ''";

    private static final String SCROLL = "Math.round(window.scrollX) + ',' + Math.round(window.scrollY)";

    /**
     * Gives a page with no style sheet of its own the theme's, as a style element of comando's; takes it away from a
     * page that has one, which keeps its own look whole. Called with the rules as its one argument; runs even where
     * the page's own scripts are off, as every call of the view's does.
     */
    private static final String THEME = """
            (function (css) {
                var own = document.getElementById('comando-theme');
                var sheets = 0;
                for (var i = 0; i < document.styleSheets.length; i++) {
                    var node = document.styleSheets[i].ownerNode;
                    if (!node || node.id !== 'comando-theme') {
                        sheets++;
                    }
                }
                if (sheets > 0) {
                    if (own) {
                        own.parentNode.removeChild(own);
                    }
                    return;
                }
                if (!own) {
                    own = document.createElement('style');
                    own.id = 'comando-theme';
                    (document.head || document.documentElement).appendChild(own);
                }
                own.textContent = css;
            })""";

    /** Whether this process has started the JavaFX toolkit. EDT only, as {@link #create()} is. */
    private static boolean toolkitStarted;

    private final Surface panel = new Surface();

    /** Called with the links the page does not follow. EDT only. */
    private Consumer<URI> link = _ -> {
    };

    /** Written once by {@link #close()} on the EDT; read on the JavaFX thread and by the callers of the futures. */
    private volatile boolean closed;

    /** The view and its engine; made and used on the JavaFX thread only, null until made. */
    private WebView view;

    private WebEngine engine;

    /** The page shown, without leaving it for its own anchors; JavaFX thread only. */
    private URI page;

    /**
     * Whether a page has loaded and still shows: a link stopped before it left the page keeps it shown, though the
     * engine then reads the load as cancelled and may drop its document. JavaFX thread only.
     */
    private boolean shown;

    /** The scroll position to restore once the page now loading has loaded, or null; JavaFX thread only. */
    private int[] scrollAfterLoad;

    private WebPageView() {
    }

    /**
     * A new view, its toolkit started on the first call. EDT.
     *
     * @throws UnsupportedOperationException when this process has no display, or JavaFX cannot start
     */
    static WebPageView create() {
        startToolkit();
        final WebPageView v = new WebPageView();
        v.panel.ground();
        Platform.runLater(v::build);
        return v;
    }

    @Override
    public JComponent component() {
        return panel;
    }

    @Override
    public void load(final Path file, final boolean scripts) {
        final URI uri = file.toAbsolutePath().toUri();
        onFx(() -> {
            page = uri;
            shown = false;
            scrollAfterLoad = null;
            engine.setJavaScriptEnabled(scripts);
            engine.load(uri.toString());
        });
    }

    @Override
    public void reload() {
        onFx(() -> {
            if (page == null) {
                return;
            }
            scrollAfterLoad = scrollNow();
            engine.load(page.toString());
        });
    }

    @Override
    public void onLink(final Consumer<URI> consumer) {
        link = Objects.requireNonNull(consumer, "consumer");
    }

    @Override
    public CompletableFuture<BufferedImage> snapshot() {
        return onPage(() -> SwingFXUtils.fromFXImage(view.snapshot(null, null), null));
    }

    @Override
    public CompletableFuture<Text> text() {
        return onPage(() -> new Text(script("document.title"), script(SELECTION), script(BODY_TEXT)));
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        link = _ -> {
        };
        try {
            Platform.runLater(() -> {
                if (engine != null) {
                    engine.load(null);
                }
                panel.setScene(null);
            });
        } catch (final IllegalStateException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot release the page, the JavaFX toolkit has ended", e);
        }
    }

    private static void startToolkit() {
        if (toolkitStarted) {
            return;
        }
        if (GraphicsEnvironment.isHeadless()) {
            throw new UnsupportedOperationException("cannot render a page without a display [java.awt.headless=true]");
        }
        try {
            Platform.startup(() -> {
            });
        } catch (final IllegalStateException _) {
            // a JFXPanel made elsewhere in this process started it already
        } catch (final RuntimeException | LinkageError e) {
            throw new UnsupportedOperationException("cannot start the page renderer [cause=" + e + ']', e);
        }
        // closing the last page must not end JavaFX: a later page could not start it again
        Platform.setImplicitExit(false);
        toolkitStarted = true;
    }

    /** Makes the view, on the JavaFX thread. */
    private void build() {
        if (closed) {
            return;
        }
        view = new WebView();
        engine = view.getEngine();
        engine.setJavaScriptEnabled(false);
        // every load, the same link followed twice included, is scheduled with its address set; the address itself
        // does not change on the second time, so a listener on it would let that load through
        engine.getLoadWorker().stateProperty().addListener((_, _, state) -> {
            if (state == Worker.State.SCHEDULED) {
                navigated(engine.getLocation());
            } else if (state == Worker.State.SUCCEEDED) {
                loaded();
            }
        });
        panel.setScene(new Scene(view, Paint.valueOf(panel.groundHex)));
    }

    /**
     * Lets the page move within itself (an anchor, the page again); anything else is stopped before it loads and
     * handed to the link consumer. JavaFX thread.
     */
    private void navigated(final String location) {
        if (closed || page == null || location == null || location.isEmpty()) {
            return;
        }
        URI to;
        try {
            to = new URI(location);
        } catch (final URISyntaxException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot follow a link [location=" + location + ']', e);
            to = null;
        }
        if (to != null && samePage(to)) {
            return;
        }
        // the engine cannot cancel a load from inside the listener that announces it
        Platform.runLater(() -> engine.getLoadWorker().cancel());
        if (to != null) {
            final URI followed = to;
            Edt.later(() -> {
                if (!closed) {
                    link.accept(followed);
                }
            });
        }
    }

    private boolean samePage(final URI to) {
        return Objects.equals(to.getScheme(), page.getScheme())
                && Objects.equals(to.getRawSchemeSpecificPart(), page.getRawSchemeSpecificPart());
    }

    /**
     * A page finished loading: keeps its selection, takes the theme when it has no style of its own, and restores the
     * scroll position a reload asked for.
     */
    private void loaded() {
        if (closed) {
            return;
        }
        shown = true;
        try {
            theme();
        } catch (final RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot give the page the theme [page=" + page + ']', e);
        }
        try {
            engine.executeScript(KEEP_SELECTION);
            if (scrollAfterLoad != null) {
                engine.executeScript("window.scrollTo(" + scrollAfterLoad[0] + ", " + scrollAfterLoad[1] + ")");
            }
        } catch (final RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot set up the page [page=" + page + ']', e);
        } finally {
            scrollAfterLoad = null;
        }
    }

    /** Gives the page the theme's rules as they are now, unless it has style of its own. JavaFX thread. */
    private void theme() {
        final String css = panel.themeCss.replace("\\", "\\\\").replace("'", "\\'");
        engine.executeScript(THEME + "('" + css + "')");
    }

    /** The page's scroll position as {x, y}, or null when the page cannot say. JavaFX thread. */
    private int[] scrollNow() {
        try {
            final String[] xy = script(SCROLL).split(",", -1);
            return new int[] {Integer.parseInt(xy[0]), Integer.parseInt(xy[1])};
        } catch (final RuntimeException e) {
            return null;
        }
    }

    /** The string the page's script yields; empty for anything that is not a string. JavaFX thread. */
    private String script(final String expression) {
        return engine.executeScript("String(" + expression + ")") instanceof final String s ? s : "";
    }

    /** Runs {@code task} on the JavaFX thread once the view is made, unless this view is closed by then. */
    private void onFx(final Runnable task) {
        if (closed) {
            return;
        }
        Platform.runLater(() -> {
            if (!closed) {
                task.run();
            }
        });
    }

    /** A future of {@code work}'s answer, done on the JavaFX thread; failed when this view is closed. */
    private <T> CompletableFuture<T> onPage(final Supplier<T> work) {
        final CompletableFuture<T> out = new CompletableFuture<>();
        if (closed) {
            out.completeExceptionally(new IllegalStateException("page view is closed"));
            return out;
        }
        try {
            Platform.runLater(() -> {
                if (closed) {
                    out.completeExceptionally(new IllegalStateException("page view is closed"));
                    return;
                }
                try {
                    out.complete(work.get());
                } catch (final RuntimeException e) {
                    out.completeExceptionally(e);
                }
            });
        } catch (final IllegalStateException e) {
            out.completeExceptionally(e);
        }
        return out;
    }

    /** The panel; behind the page it shows the look's panel colour, which a theme change sets again. */
    private final class Surface extends JFXPanel {

        /** The panel colour as {@code #rrggbb}, for the scene behind the page; EDT writes, the JavaFX thread reads. */
        private volatile String groundHex = "#000000";

        /**
         * The rules a page without style of its own takes ({@link PageTheme}); the EDT writes them at each theme
         * change, the JavaFX thread reads them when a page loads or the theme changes.
         */
        private volatile String themeCss = "";

        @Override
        public void updateUI() {
            super.updateUI();
            ground();
        }

        /** Takes the look's panel colour. EDT. */
        void ground() {
            final Color c = UIManager.getColor("Panel.background");
            if (c == null) {
                return;
            }
            setBackground(c);
            groundHex = Theme.hex(c);
            themeCss = PageTheme.css();
            Platform.runLater(() -> {
                if (view != null && view.getScene() != null) {
                    view.getScene().setFill(Paint.valueOf(groundHex));
                }
                // the page shown takes the new theme now, also when the last load was a link stopped before it left
                // the page (its state then reads cancelled); one still loading takes it again when loaded
                if (!closed && engine != null && shown) {
                    try {
                        theme();
                    } catch (final RuntimeException e) {
                        LOG.log(System.Logger.Level.WARNING, "cannot restyle the page [page=" + page + ']', e);
                    }
                }
            });
        }
    }
}
