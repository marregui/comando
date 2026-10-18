package dev.marregui.comando.ui.shell;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The files behind Tools ▸ Help, so each opens in a panel as any document does, and can be opened as a file too: the
 * tutorial, written into comando's folder from the copy this build carries, and the log comando writes.
 */
final class HelpFiles {

    static final String TUTORIAL_RESOURCE = "/dev/marregui/comando/ui/help/tutorial.md";

    private HelpFiles() {
    }

    /** Where the tutorial is kept: {@code help/tutorial.md} in comando's folder. */
    static Path tutorial(Path home) {
        return home.resolve("help").resolve("tutorial.md");
    }

    /** The log comando writes now; older ones are numbered after it. */
    static Path log(Path logDir) {
        return logDir.resolve("comando-0.log");
    }

    /**
     * Writes the build's tutorial to {@link #tutorial}, unless the file already holds it: the file is comando's manual,
     * replaced when this build's differs, so it never falls behind the app.
     *
     * @return the file
     * @throws IOException when the build carries no tutorial, or the file cannot be written
     */
    static Path writeTutorial(Path home) throws IOException {
        return writeTutorial(home, Files::move);
    }

    /** How a file is moved into place: {@link Files#move}, or a stand-in in tests. */
    interface Move {
        void move(Path from, Path to, CopyOption... options) throws IOException;
    }

    static Path writeTutorial(Path home, Move move) throws IOException {
        String text;
        try (InputStream in = HelpFiles.class.getResourceAsStream(TUTORIAL_RESOURCE)) {
            if (in == null) {
                throw new IOException("the tutorial is not in this build");
            }
            text = tutorialText(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        Path file = tutorial(home);
        if (Files.isRegularFile(file) && Files.readString(file, StandardCharsets.UTF_8).equals(text)) {
            return file;
        }
        Files.createDirectories(file.getParent());
        Path tmp = Files.createTempFile(file.getParent(), "tutorial", ".tmp");
        try {
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            try {
                move.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                move.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        return file;
    }

    /**
     * The tutorial as the app shows it: links to other docs are relative to the repository, which the app does not
     * ship, and the preview does not follow in-page anchors, so those links keep their text only.
     */
    static String tutorialText(String markdown) {
        return markdown.replaceAll("\\[([^\\]]+)\\]\\((\\.\\./[^)]+|[a-z-]+\\.md[^)]*|#[^)]+)\\)", "$1");
    }
}
