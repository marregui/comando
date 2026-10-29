package dev.marregui.comando.ui.kit.desktop;

import dev.marregui.comando.ui.kit.async.UiErrands;

import java.awt.Desktop;
import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Opens web links, and files such as HTML pages, in the user's browser. The browser call can block, so it runs on an {@code open-link} virtual
 * thread, never on the caller's (the EDT, for a clicked link).
 */
public final class Browser {

    private static final System.Logger LOG = System.getLogger(Browser.class.getName());

    private Browser() {
    }

    /** True for an {@code http} or {@code https} link, in any letter case; false for null. */
    public static boolean isWebLink(String href) {
        if (href == null) {
            return false;
        }
        String h = href.toLowerCase(Locale.ROOT);
        return h.startsWith("http://") || h.startsWith("https://");
    }

    /** Opens {@code href} in the browser when it is a web link and the desktop can browse; returns at once. */
    public static void open(String href) {
        if (Desktop.isDesktopSupported()) {
            open(href, uri -> {
                try {
                    Desktop.getDesktop().browse(uri);
                } catch (Exception e) {
                    LOG.log(System.Logger.Level.WARNING, "cannot open [uri=" + uri + ']', e);
                }
            });
        }
    }

    /** Opens a file of this machine, such as an HTML page, in the browser when the desktop can browse; returns at once. */
    public static void openFile(Path file) {
        if (Desktop.isDesktopSupported()) {
            URI uri = file.toAbsolutePath().toUri();
            UiErrands.start("open-link", () -> {
                try {
                    Desktop.getDesktop().browse(uri);
                } catch (Exception e) {
                    LOG.log(System.Logger.Level.WARNING, "cannot open [uri=" + uri + ']', e);
                }
            });
        }
    }

    static void open(String href, Consumer<URI> browse) {
        if (!isWebLink(href)) {
            return;
        }
        URI uri;
        try {
            uri = URI.create(href);
        } catch (IllegalArgumentException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot open [href=" + href + ", cause=" + e.getMessage() + ']');
            return;
        }
        UiErrands.start("open-link", () -> browse.accept(uri));
    }
}
