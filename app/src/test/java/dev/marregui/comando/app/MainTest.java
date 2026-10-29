package dev.marregui.comando.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/// [Main] describes the message shown when a data-root instance lock is already held.
///
/// - includes the contended root path
/// - explains that the earlier process may still be closing
class MainTest {

    /// A contended root names its exact path and directs the user to the existing window or a later retry.
    /// The fixed Unix path makes interpolation visible in the complete message.
    @Test
    void aSecondInstanceSaysTheFirstMayStillBeClosing() {
        assertThat(Main.lockedMessage(Path.of("/w/.comando")))
                .isEqualTo("comando is already running on /w/.comando, or still closing; switch to its window, or try again in a few seconds.");
    }

    /// The data root's `instance.json` names the instance; a missing, unreadable or nameless file names none.
    @Test
    void theDataRootsInstanceFileNamesTheInstance(@TempDir Path home) throws IOException {
        assertThat(Main.nameFile(home)).as("no file").isNull();
        Files.writeString(home.resolve("instance.json"), "{\"name\": \"Comando Expert\", \"slug\": \"b\"}");
        assertThat(Main.nameFile(home)).isEqualTo("Comando Expert");
        Files.writeString(home.resolve("instance.json"), "{\"slug\": \"b\"}");
        assertThat(Main.nameFile(home)).as("no name").isNull();
        Files.writeString(home.resolve("instance.json"), "{not json");
        assertThat(Main.nameFile(home)).as("unreadable").isNull();
    }
}
