package dev.marregui.comando.ui.shell;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import dev.marregui.comando.core.model.AtomicFiles;
import dev.marregui.comando.ui.kit.format.KeyText;
import dev.marregui.comando.ui.kit.text.LogLines;

/**
 * The files behind the Help menu, so each opens in a panel as any document does, and can be opened as a file too: the
 * tutorial, written into comando's folder from the copy this build carries, and the log comando writes.
 */
final class HelpFiles {

    static final String TUTORIAL_RESOURCE = "/dev/marregui/comando/ui/help/tutorial.md";

    private HelpFiles() {
    }

    /** Where the tutorial is kept: {@code help/tutorial.md} in comando's folder. */
    static Path tutorial(final Path home) {
        return home.resolve("help").resolve("tutorial.md");
    }

    /** The log comando writes now; older ones are numbered after it. */
    static Path log(final Path logDir) {
        return logDir.resolve("comando-0.log");
    }

    /** How many logs comando keeps, the one it writes and the older ones: the count its log handler rotates. */
    static final int LOGS = 10;

    /**
     * The log that holds the entry written at {@code atMillis} (see {@link LogLines#offsetOf}), the newest first; empty
     * when none holds it any more. Reads the files: called off the EDT.
     */
    static Optional<Path> logHolding(final Path logDir, final long atMillis) {
        for (int g = 0; g < LOGS; g++) {
            final Path file = logDir.resolve("comando-" + g + ".log");
            if (!Files.isRegularFile(file)) {
                break;
            }
            try {
                if (LogLines.offsetOf(Files.readString(file), atMillis) >= 0) {
                    return Optional.of(file);
                }
            } catch (final IOException e) {
                // rotated away while read, or not text: the older ones may still hold it
            }
        }
        return Optional.empty();
    }

    /**
     * Writes the build's tutorial to {@link #tutorial}, unless the file already holds it, and returns the file: it is
     * comando's manual, replaced when this build's differs, so it never falls behind the app.
     *
     * @throws IOException when the build carries no tutorial, or the file cannot be written
     */
    static Path writeTutorial(final Path home) throws IOException {
        return writeTutorial(home, Files::move);
    }

    /** How a file is moved into place: {@link Files#move}, or a stand-in in tests. */
    interface Move {
        void move(Path from, Path to, CopyOption... options) throws IOException;
    }

    static Path writeTutorial(final Path home, final Move move) throws IOException {
        String text;
        try (InputStream in = HelpFiles.class.getResourceAsStream(TUTORIAL_RESOURCE)) {
            if (in == null) {
                throw new IOException("the tutorial is not in this build");
            }
            text = KeyText.of(tutorialText(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        }
        final Path file = tutorial(home);
        if (Files.isRegularFile(file) && Files.readString(file, StandardCharsets.UTF_8).equals(text)) {
            return file;
        }
        Files.createDirectories(file.getParent());
        AtomicFiles.write(file, text.getBytes(StandardCharsets.UTF_8), move::move);
        return file;
    }

    /**
     * The tutorial as the app shows it: links to other docs are relative to the repository, which the app does not
     * ship, so those links keep their text only; so do in-page anchors, which the shown tutorial does not need.
     */
    static String tutorialText(final String markdown) {
        return markdown.replaceAll("\\[([^\\]]+)\\]\\((\\.\\./[^)]+|[a-z-]+\\.md[^)]*|#[^)]+)\\)", "$1");
    }
}
