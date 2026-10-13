package dev.marregui.comando.ui.shell;

import java.awt.Window;
import java.util.Locale;

/**
 * Desktop notifications for work that finished while the user looked elsewhere: shown only when the
 * window is not active, through the platform's notification centre.
 */
final class Notifier {

    private static final System.Logger LOG = System.getLogger(Notifier.class.getName());
    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

    private final Window window;

    Notifier(Window window) {
        this.window = window;
    }

    void notify(String title, String text) {
        if (window.isActive() || !MAC) {
            return;
        }
        String script = "display notification \"" + escape(text) + "\" with title \"comando\" subtitle \"" + escape(title) + "\"";
        Thread.ofVirtual().name("notify").start(() -> {
            try {
                new ProcessBuilder("osascript", "-e", script).redirectErrorStream(true).start().waitFor();
            } catch (Exception e) {
                LOG.log(System.Logger.Level.DEBUG, "notification failed: " + e.getMessage());
            }
        });
    }

    static String escape(String s) {
        String clipped = s.length() > 200 ? s.substring(0, 200) + "…" : s;
        return clipped.replace("\\", "\\\\").replace("\"", "\\\"").replace('\n', ' ');
    }
}
