package dev.marregui.comando.ui.kit.desktop;

import java.awt.Desktop;
import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Consumer;

import dev.marregui.comando.ui.kit.async.UiErrands;

/**
 * Opens web links, and files such as HTML pages, in the user's browser. The browser call can block, so it runs on the
 * window's {@code open-link} errand, never on the caller's thread (the EDT, for a clicked link).
 */
public final class Browser {

    private static final System.Logger LOG = System.getLogger(Browser.class.getName());

    private Browser() {
    }

    /** True for an {@code http} or {@code https} link, in any letter case; false for null. */
    public static boolean isWebLink(final String href) {
        if (href == null) {
            return false;
        }
        final String h = href.toLowerCase(Locale.ROOT);
        return h.startsWith("http://") || h.startsWith("https://");
    }

    /** Opens {@code href} in the browser when it is a web link and the desktop can browse; returns at once. */
    public static void open(final String href) {
        if (Desktop.isDesktopSupported()) {
            open(href, uri -> {
                try {
                    Desktop.getDesktop().browse(uri);
                } catch (final Exception e) {
                    LOG.log(System.Logger.Level.WARNING, "cannot open [uri=" + uri + ']', e);
                }
            });
        }
    }

    /** Opens a local file, such as an HTML page, in the browser when the desktop can browse; returns at once. */
    public static void openFile(final Path file) {
        if (Desktop.isDesktopSupported()) {
            final URI uri = file.toAbsolutePath().toUri();
            UiErrands.start("open-link", () -> {
                try {
                    Desktop.getDesktop().browse(uri);
                } catch (final Exception e) {
                    LOG.log(System.Logger.Level.WARNING, "cannot open [uri=" + uri + ']', e);
                }
            });
        }
    }

    static void open(final String href, final Consumer<URI> browse) {
        if (!isWebLink(href)) {
            return;
        }
        URI uri;
        try {
            uri = URI.create(href);
        } catch (final IllegalArgumentException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot open [href=" + href + ", cause=" + e.getMessage() + ']');
            return;
        }
        UiErrands.start("open-link", () -> browse.accept(uri));
    }
}
