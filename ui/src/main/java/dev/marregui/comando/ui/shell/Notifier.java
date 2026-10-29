package dev.marregui.comando.ui.shell;

import dev.marregui.comando.core.view.Clip;
import dev.marregui.comando.ui.kit.async.UiErrands;
import dev.marregui.comando.ui.kit.desktop.Platform;

import java.awt.AWTException;
import java.awt.SystemTray;
import java.awt.Taskbar;
import java.awt.TrayIcon;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Desktop notifications for work that finished while the user looked elsewhere, shown only while the window is not
 * active: Notification Center on macOS (through {@code osascript}, on the window's {@code notify} errand), a tray
 * balloon where the desktop has a tray. And the desktop's attention when a session needs the user: the Dock icon
 * bounces until the window is activated.
 */
final class Notifier {

    private static final System.Logger LOG = System.getLogger(Notifier.class.getName());
    /** How long {@code osascript} may take to post a notification before it is stopped. */
    static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_CHARS = 200;

    private final Window window;

    Notifier(Window window) {
        this.window = window;
    }

    void notify(String title, String text) {
        if (window.isActive()) {
            return;
        }
        if (!Platform.isMac()) {
            tray(title, text);
            return;
        }
        String script = "display notification \"" + escape(text) + "\" with title \"comando\" subtitle \"" + escape(title) + "\"";
        UiErrands.start("notify", () -> run(List.of("osascript", "-e", script), TIMEOUT));
    }

    /**
     * Runs {@code command} with no input and its output discarded, for at most {@code bound}, then stops it; true when
     * it ended within the bound.
     */
    static boolean run(List<String> command, Duration bound) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            p.getOutputStream().close();
            if (!p.waitFor(bound.toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                LOG.log(System.Logger.Level.DEBUG, "notification not posted in time [seconds=" + bound.toSeconds() + ']');
                return false;
            }
            return true;
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "notification failed [cause=" + e.getMessage() + ']');
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Asks for the desktop's attention while the window is not active: the Dock icon bounces until comando is in front.
     */
    void attention() {
        if (window.isActive() || !Taskbar.isTaskbarSupported()) {
            return;
        }
        Taskbar taskbar = Taskbar.getTaskbar();
        if (taskbar.isSupported(Taskbar.Feature.USER_ATTENTION)) {
            try {
                taskbar.requestUserAttention(true, true);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.DEBUG, "attention request failed [cause=" + e.getMessage() + ']');
            }
        }
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
            trayIcon.displayMessage(title, Clip.keepStart(text, MAX_CHARS), TrayIcon.MessageType.INFO);
        } catch (AWTException | RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "notification failed [cause=" + e.getMessage() + ']');
        }
    }

    /** {@code s} cut to its first 200 characters and escaped for an AppleScript string, on one line. */
    static String escape(String s) {
        return Clip.keepStart(s, MAX_CHARS).replace("\\", "\\\\").replace("\"", "\\\"").replace('\n', ' ');
    }
}
