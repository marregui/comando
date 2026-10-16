package dev.marregui.comando.ui.kit.desktop;

import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.Window;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Open, save and folder choosers: the system's own dialog on macOS (Finder's sidebar, recent places, ⌘⇧G
 * and drag and drop of a folder), Swing's chooser elsewhere. Every method runs on the EDT and returns
 * empty when the user cancels.
 */
public final class FileChoosers {

    private static final String FOLDERS = "apple.awt.fileDialogForDirectories";

    private FileChoosers() {
    }

    /** A folder, starting at {@code start} (a folder, or a file whose folder is used). */
    public static Optional<Path> directory(Component owner, String title, Path start) {
        Path dir = startDirectory(start);
        if (Platform.isMac()) {
            FileDialog d = dialog(owner, title, FileDialog.LOAD);
            d.setDirectory(dir.toString());
            String previous = System.getProperty(FOLDERS);
            System.setProperty(FOLDERS, "true");
            try {
                d.setVisible(true);
            } finally {
                if (previous == null) {
                    System.clearProperty(FOLDERS);
                } else {
                    System.setProperty(FOLDERS, previous);
                }
            }
            return chosen(d);
        }
        JFileChooser c = new JFileChooser(dir.toFile());
        c.setDialogTitle(title);
        c.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        return c.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION ? Optional.of(c.getSelectedFile().toPath()) : Optional.empty();
    }

    /**
     * A file to open, starting at {@code start}; with {@code extensions} (without the dot), only files with
     * one of them can be chosen.
     */
    public static Optional<Path> open(Component owner, String title, Path start, String... extensions) {
        Path dir = startDirectory(start);
        if (Platform.isMac()) {
            FileDialog d = dialog(owner, title, FileDialog.LOAD);
            d.setDirectory(dir.toString());
            if (start != null && Files.isRegularFile(start)) {
                d.setFile(start.getFileName().toString());
            }
            if (extensions.length > 0) {
                d.setFilenameFilter((folder, name) -> accepts(name, extensions));
            }
            d.setVisible(true);
            return chosen(d);
        }
        JFileChooser c = new JFileChooser(dir.toFile());
        c.setDialogTitle(title);
        if (start != null && Files.isRegularFile(start)) {
            c.setSelectedFile(start.toFile());
        }
        if (extensions.length > 0) {
            c.setFileFilter(new FileNameExtensionFilter(String.join(", ", extensions), extensions));
        }
        return c.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION ? Optional.of(c.getSelectedFile().toPath()) : Optional.empty();
    }

    /** A file to write, starting at {@code start} with {@code name} proposed. */
    public static Optional<Path> save(Component owner, String title, Path start, String name) {
        Path dir = startDirectory(start);
        if (Platform.isMac()) {
            FileDialog d = dialog(owner, title, FileDialog.SAVE);
            d.setDirectory(dir.toString());
            d.setFile(name);
            d.setVisible(true);
            return chosen(d);
        }
        JFileChooser c = new JFileChooser(dir.toFile());
        c.setDialogTitle(title);
        c.setSelectedFile(new File(dir.toFile(), name));
        return c.showSaveDialog(owner) == JFileChooser.APPROVE_OPTION ? Optional.of(c.getSelectedFile().toPath()) : Optional.empty();
    }

    /** The folder a chooser opens in: {@code start} when a folder, its folder when a file, else the nearest existing parent, else home. */
    static Path startDirectory(Path start) {
        Path home = Path.of(System.getProperty("user.home"));
        if (start == null) {
            return home;
        }
        for (Path p = start.toAbsolutePath(); p != null; p = p.getParent()) {
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        return home;
    }

    static boolean accepts(String name, String... extensions) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String e : extensions) {
            if (lower.endsWith("." + e.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static FileDialog dialog(Component owner, String title, int mode) {
        Window w = owner == null ? null : owner instanceof Window ow ? ow : SwingUtilities.getWindowAncestor(owner);
        return switch (w) {
            case Dialog d -> new FileDialog(d, title, mode);
            case Frame f -> new FileDialog(f, title, mode);
            case null, default -> new FileDialog((Frame) null, title, mode);
        };
    }

    private static Optional<Path> chosen(FileDialog d) {
        return d.getFile() == null ? Optional.empty() : Optional.of(Path.of(d.getDirectory(), d.getFile()));
    }
}
