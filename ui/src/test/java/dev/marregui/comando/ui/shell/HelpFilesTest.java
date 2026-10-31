package dev.marregui.comando.ui.shell;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// [HelpFiles] writes the tutorial into comando's folder and finds the log a moment was written to.
/// - The tutorial is written into `help/`, kept in step with the build, and not rewritten when unchanged.
/// - Without atomic rename the tutorial is still written, leaving no temporary file.
/// - Links the app cannot follow keep their text.
/// - The log is the one being written; the log holding an entry is the newest that does, or none once rotated away.
class HelpFilesTest {

    /// [HelpFiles#writeTutorial] writes `help/tutorial.md` with no `../` links, leaves an identical file's time
    /// untouched, overwrites a user's edit with the build's text, and leaves only that file in the folder.
    @Test
    void theTutorialIsWrittenIntoComandosFolderAndKeptInStepWithTheBuild(@TempDir final Path home) throws Exception {
        final Path file = HelpFiles.writeTutorial(home);
        assertThat(file).isEqualTo(home.resolve("help/tutorial.md"));
        final String text = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(text).startsWith("#").doesNotContain("](../");

        final FileTime written = FileTime.fromMillis(1_000_000L);
        Files.setLastModifiedTime(file, written);
        HelpFiles.writeTutorial(home);
        assertThat(Files.getLastModifiedTime(file)).as("the same text is not written again").isEqualTo(written);

        Files.writeString(file, "edited", StandardCharsets.UTF_8);
        HelpFiles.writeTutorial(home);
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).as("the build's tutorial wins").isEqualTo(text);
        try (var left = Files.list(file.getParent())) {
            assertThat(left).containsExactly(file);
        }
    }

    /// When the atomic move throws [java.nio.file.AtomicMoveNotSupportedException], a second, plain move writes the
    /// file and no temporary file remains.
    @Test
    void onAFileSystemWithoutAtomicRenameTheTutorialIsStillWritten(@TempDir final Path home) throws Exception {
        final List<List<CopyOption>> moves = new ArrayList<>();
        final Path file = HelpFiles.writeTutorial(home, (from, to, options) -> {
            moves.add(List.of(options));
            if (List.of(options).contains(StandardCopyOption.ATOMIC_MOVE)) {
                throw new AtomicMoveNotSupportedException(from.toString(), to.toString(), "no atomic rename here");
            }
            Files.move(from, to, options);
        });
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).startsWith("#");
        assertThat(moves).hasSize(2);
        try (var left = Files.list(file.getParent())) {
            assertThat(left).containsExactly(file);
        }
    }

    /// [HelpFiles#tutorialText] turns relative and fragment links into their text and keeps web links.
    @Test
    void linksTheAppCannotFollowKeepTheirText() {
        assertThat(HelpFiles.tutorialText("see [Build](../guides/build.md), [keys](#keys) and [web](https://x.dev)"))
                .isEqualTo("see Build, keys and [web](https://x.dev)");
    }

    /// [HelpFiles#log] is `comando-0.log` in the logs folder.
    @Test
    void theLogIsTheOneBeingWritten() {
        assertThat(HelpFiles.log(Path.of("/h/logs"))).isEqualTo(Path.of("/h/logs/comando-0.log"));
    }

    /// [HelpFiles#logHolding] finds the current log for a recent entry, the rotated log for an older one, and nothing
    /// for an entry older than every log or a missing folder.
    @Test
    void theLogHoldingAnEntryIsTheNewestThatDoesAndNoneOnceRotatedAway(@TempDir final Path logs) throws Exception {
        final long t = Instant.parse("2026-10-20T10:00:00Z").toEpochMilli();
        Files.writeString(logs.resolve("comando-0.log"), Instant.ofEpochMilli(t + 60_000) + " INFO A newest\n");
        Files.writeString(logs.resolve("comando-1.log"), Instant.ofEpochMilli(t + 5) + " WARNING A the hang\n");
        assertThat(HelpFiles.logHolding(logs, t + 60_000)).contains(logs.resolve("comando-0.log"));
        assertThat(HelpFiles.logHolding(logs, t)).as("rotated into the older one").contains(logs.resolve("comando-1.log"));
        assertThat(HelpFiles.logHolding(logs, t - 3_600_000)).isEmpty();
        assertThat(HelpFiles.logHolding(logs.resolve("none"), t)).isEmpty();
    }
}
