package dev.marregui.comando.ui;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The title a file gives the panel opened on it. */
class PanelTextsTitleTest {

    @Test
    void aFileOfThePanelsFolderIsTitledWithoutItsExtension() {
        assertThat(PanelTexts.titleOf(Path.of("/home/u/.comando/panels/Ravenna plan.md"))).isEqualTo("Ravenna plan");
        assertThat(PanelTexts.titleOf(Path.of("/home/u/.comando/panels/notes.txt"))).isEqualTo("notes.txt");
    }

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
