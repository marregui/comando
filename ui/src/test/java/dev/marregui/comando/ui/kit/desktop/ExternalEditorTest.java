package dev.marregui.comando.ui.kit.desktop;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/// [ExternalEditor#command] builds the command line that opens a file in the user's editor.
/// - An IDEA launcher gets the line when there is one.
/// - Without a launcher, the system opener opens the file.
class ExternalEditorTest {

    /// With a launcher, a positive line becomes `--line N` before the path; line 0 means no line and passes only the path.
    @Test
    void ideaLauncherGetsTheLine() {
        assertThat(ExternalEditor.command(Optional.of(Path.of("/bin/idea")), Path.of("/w/A.java"), 42))
                .containsExactly("/bin/idea", "--line", "42", "/w/A.java");
        assertThat(ExternalEditor.command(Optional.of(Path.of("/bin/idea")), Path.of("/w/A.java"), 0))
                .containsExactly("/bin/idea", "/w/A.java");
    }

    /// A negative line, as an unknown position may arrive, is no line: the launcher gets only the path.
    @Test
    void aNegativeLineIsNoLine() {
        assertThat(ExternalEditor.command(Optional.of(Path.of("/bin/idea")), Path.of("/w/A.java"), -1))
                .containsExactly("/bin/idea", "/w/A.java");
    }

    /// Without a launcher the command is `open` with the path, and the line is dropped.
    @Test
    void withoutLauncherTheSystemOpensIt() {
        assertThat(ExternalEditor.command(Optional.empty(), Path.of("/w/A.java"), 42)).containsExactly("open", "/w/A.java");
    }
}
