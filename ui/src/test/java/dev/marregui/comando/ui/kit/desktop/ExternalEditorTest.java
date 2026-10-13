package dev.marregui.comando.ui.kit.desktop;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalEditorTest {

    @Test
    void ideaLauncherGetsTheLine() {
        assertThat(ExternalEditor.command(Optional.of(Path.of("/bin/idea")), Path.of("/w/A.java"), 42))
                .containsExactly("/bin/idea", "--line", "42", "/w/A.java");
        assertThat(ExternalEditor.command(Optional.of(Path.of("/bin/idea")), Path.of("/w/A.java"), 0))
                .containsExactly("/bin/idea", "/w/A.java");
    }

    @Test
    void withoutLauncherTheSystemOpensIt() {
        assertThat(ExternalEditor.command(Optional.empty(), Path.of("/w/A.java"), 42)).containsExactly("open", "/w/A.java");
    }
}
