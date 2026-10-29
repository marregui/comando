package dev.marregui.comando.ui;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/// [PanelTexts] gives the title of a panel opened on a file.
/// - A file of the panels' folder is titled without its extension, if Markdown.
/// - A file elsewhere keeps its extension and takes its folder only when its name is taken.
class PanelTextsTitleTest {

    /// [PanelTexts#titleOf] drops `.md` from a panel file's name and keeps `.txt`.
    @Test
    void aFileOfThePanelsFolderIsTitledWithoutItsExtension() {
        assertThat(PanelTexts.titleOf(Path.of("/home/u/.comando/panels/Release plan.md"))).isEqualTo("Release plan");
        assertThat(PanelTexts.titleOf(Path.of("/home/u/.comando/panels/notes.txt"))).isEqualTo("notes.txt");
    }

    /// [PanelTexts#documentTitle] keeps a file's name with its extension when only the bare name is a taken title, adds
    /// ` · docs` to a README whose name is taken in any case, and names `/` as the folder of a file at the root.
    @Test
    void aFileElsewhereKeepsItsExtensionAndTakesItsFolderOnlyWhenItsNameIsTaken() {
        Set<String> taken = Set.of("reviewer", "readme.md");
        assertThat(PanelTexts.documentTitle(Path.of("/c/plugin/agents/reviewer.md"), t -> taken.stream().anyMatch(t::equalsIgnoreCase)))
                .as("a session's panel called reviewer is no clash").isEqualTo("reviewer.md");
        assertThat(PanelTexts.documentTitle(Path.of("/w/docs/README.md"), t -> taken.stream().anyMatch(t::equalsIgnoreCase)))
                .isEqualTo("README.md · docs");
        assertThat(PanelTexts.documentTitle(Path.of("/README.md"), t -> true)).as("a file at the root has a folder, /").isEqualTo("README.md · /");
    }
}
