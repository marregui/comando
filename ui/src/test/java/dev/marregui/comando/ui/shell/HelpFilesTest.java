package dev.marregui.comando.ui.shell;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.assertj.core.api.Assertions.assertThat;

class HelpFilesTest {

    @Test
    void theTutorialIsWrittenIntoComandosFolderAndKeptInStepWithTheBuild(@TempDir Path home) throws Exception {
        Path file = HelpFiles.writeTutorial(home);
        assertThat(file).isEqualTo(home.resolve("help/tutorial.md"));
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(text).startsWith("#").doesNotContain("](../");

        FileTime written = FileTime.fromMillis(1_000_000L);
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

    @Test
    void linksTheAppCannotFollowKeepTheirText() {
        assertThat(HelpFiles.tutorialText("see [Build](../guides/build.md), [keys](#keys) and [web](https://x.dev)"))
                .isEqualTo("see Build, keys and [web](https://x.dev)");
    }

    @Test
    void theLogIsTheOneBeingWritten() {
        assertThat(HelpFiles.log(Path.of("/h/logs"))).isEqualTo(Path.of("/h/logs/comando-0.log"));
    }
}
