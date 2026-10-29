package dev.marregui.comando.app;

import dev.marregui.comando.core.model.InstanceName;
import tools.jackson.core.JacksonException;

import javax.swing.JOptionPane;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Entry point: configuration, logging, one instance per data root, the application, and an exit that closes
 * everything once.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        // AppKit reads it once, when the toolkit starts: set before anything touches AWT, or title bars, menus and
        // native file dialogs ignore it
        System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua");
        AppConfig config = AppConfig.fromEnvironment();
        // several instances on one machine tell themselves apart: the Dock's and menu bar's name, the window's title and
        // badge, and a preferences node of its own so their layouts never overwrite each other (record 122)
        String given = System.getProperty("comando.name", System.getenv("COMANDO_NAME"));
        String name = InstanceName.of(config.home(), given == null || given.isBlank() ? nameFile(config.home()) : given);
        System.setProperty(InstanceName.PROPERTY, name);
        System.setProperty("apple.awt.application.name", InstanceName.display(name));
        if (System.getProperty("comando.prefsNode") == null) {
            // keyed by the data root's folder, so renaming an instance keeps its window layout
            String folder = InstanceName.of(config.home(), null);
            System.setProperty("comando.prefsNode", InstanceName.prefsNode(folder.isEmpty() ? name : folder));
        }
        Logging.configure(config.logDir());
        // two instances on one data root would overwrite each other's panels and sessions
        if (!lock(config.home())) {
            String message = lockedMessage(config.home());
            System.err.println(message);
            if (!GraphicsEnvironment.isHeadless()) {
                JOptionPane.showMessageDialog(null, message, InstanceName.display(name), JOptionPane.INFORMATION_MESSAGE);
            }
            System.exit(1);
        }
        String version = Main.class.getPackage().getImplementationVersion();
        App[] app = new App[1];
        app[0] = new App(config, version == null ? "dev" : version, () -> Thread.ofPlatform().name("exit").start(() -> {
            app[0].close();
            System.exit(0);
        }));
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("shutdown").unstarted(app[0]::close));
        app[0].start();
    }

    /** The name in the data root's {@code instance.json} ({@code {"name": "Comando Expert"}}, written by the kit's
     * {@code kit.py instance new}), or null when the file is missing, unreadable or names none. */
    static String nameFile(Path home) {
        Path file = home.resolve(InstanceName.FILE);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            String name = JsonFile.JSON.readTree(file.toFile()).path("name").asString("");
            return name.isBlank() ? null : name;
        } catch (JacksonException e) {
            return null;
        }
    }

    /** What a second instance says: the first may be closing, its window already gone, while it holds the lock. */
    static String lockedMessage(Path home) {
        return "comando is already running on " + home + ", or still closing; switch to its window, or try again in a few seconds.";
    }

    /** Held until the process ends; the operating system releases it, also after a crash. */
    @SuppressWarnings("unused")
    private static FileLock held;

    /** Takes the data root's lock file; false when another process holds it. */
    static boolean lock(Path home) {
        try {
            Files.createDirectories(home);
            FileChannel channel = FileChannel.open(home.resolve("comando.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            held = channel.tryLock();
            if (held == null) {
                channel.close();
                return false;
            }
            return true;
        } catch (IOException | OverlappingFileLockException e) {
            // a data root that cannot hold a lock file still runs; the lock is a guard, not a requirement
            return true;
        }
    }
}
