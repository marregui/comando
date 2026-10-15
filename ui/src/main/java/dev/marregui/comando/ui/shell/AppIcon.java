package dev.marregui.comando.ui.shell;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import java.awt.Image;
import java.awt.Taskbar;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * comando's own icon on the window and the Dock, instead of the Java runtime's: {@code /icons/comando.png}, drawn
 * from {@code app/src/main/icon/comando.svg}, the same picture the disk image bundles as {@code comando.icns}.
 */
final class AppIcon {

    private static final System.Logger LOG = System.getLogger(AppIcon.class.getName());

    private AppIcon() {
    }

    static void install(JFrame frame) {
        BufferedImage image = load();
        if (image == null) {
            return;
        }
        frame.setIconImages(List.<Image>of(image));
        if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)) {
            try {
                Taskbar.getTaskbar().setIconImage(image);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.DEBUG, "Dock icon not set: " + e.getMessage());
            }
        }
    }

    static BufferedImage load() {
        try (InputStream in = AppIcon.class.getResourceAsStream("/icons/comando.png")) {
            return in == null ? null : ImageIO.read(in);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "app icon not read: " + e.getMessage());
            return null;
        }
    }
}
