package dev.marregui.comando.ui.shell;

import java.awt.AWTException;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.util.Locale;

/**
 * Desktop notifications for work that finished while the user looked elsewhere: shown only when the
 * window is not active: Notification Center on macOS, a tray balloon where the desktop has a tray.
 */
final class Notifier {

    private static final System.Logger LOG = System.getLogger(Notifier.class.getName());
    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

    private final Window window;

    Notifier(Window window) {
        this.window = window;
    }

    void notify(String title, String text) {
        if (window.isActive()) {
            return;
        }
        if (!MAC) {
            tray(title, text);
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

    private TrayIcon trayIcon;

    /** Elsewhere: a tray balloon, where the desktop has a tray; nothing otherwise. */
    private void tray(String title, String text) {
        if (!SystemTray.isSupported()) {
            return;
        }
        try {
            if (trayIcon == null) {
                BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
                trayIcon = new TrayIcon(image, "comando");
                trayIcon.setImageAutoSize(true);
                SystemTray.getSystemTray().add(trayIcon);
            }
            String clipped = text.length() > 200 ? text.substring(0, 200) + "…" : text;
            trayIcon.displayMessage(title, clipped, TrayIcon.MessageType.INFO);
        } catch (AWTException | RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "notification failed: " + e.getMessage());
        }
    }

    static String escape(String s) {
        String clipped = s.length() > 200 ? s.substring(0, 200) + "…" : s;
        return clipped.replace("\\", "\\\\").replace("\"", "\\\"").replace('\n', ' ');
    }
}
