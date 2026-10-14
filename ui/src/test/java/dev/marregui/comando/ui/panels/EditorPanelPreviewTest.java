package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.schema.SchemaRegistry;
import dev.marregui.comando.core.state.PanelState;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EditorPanelPreviewTest {

    private static String shown(EditorPanel p) {
        try {
            return p.rendered().getDocument().getText(0, p.rendered().getDocument().getLength());
        } catch (BadLocationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aMarkdownPanelSwitchesBetweenTheTextAndTheRenderedPage() throws Exception {
        EditorPanel[] p = new EditorPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            p[0] = new EditorPanel(new PanelState(new PanelId("p"), "spec", "", ""), intent -> {
            }, new SchemaRegistry());
            p[0].editor().load("# Nevsky\n\nThe **plan**.\n");
            p[0].file(Path.of("/s/NEVSKY-PLAN.md"));
        });
        EditorPanel panel = p[0];
        assertThat(panel.previewOffered()).isTrue();
        assertThat(panel.previewing()).isFalse();

        SwingUtilities.invokeAndWait(panel::togglePreview);
        assertThat(panel.previewing()).isTrue();
        assertThat(shown(panel)).contains("Nevsky").contains("The plan.").doesNotContain("**").doesNotContain("#");

        SwingUtilities.invokeAndWait(() -> panel.reload("# Nevsky v2\n"));
        assertThat(panel.editor().text()).isEqualTo("# Nevsky v2\n");
        assertThat(shown(panel)).as("an outside change shows in the preview too").contains("Nevsky v2");

        SwingUtilities.invokeAndWait(panel::togglePreview);
        assertThat(panel.previewing()).isFalse();
    }

    @Test
    void aFileThatIsNotMarkdownStaysAsText() throws Exception {
        EditorPanel[] p = new EditorPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            p[0] = new EditorPanel(new PanelState(new PanelId("j"), "Red.java", "", ""), intent -> {
            }, new SchemaRegistry());
            p[0].file(Path.of("/s/work/Red.java"));
            p[0].togglePreview();
        });
        assertThat(p[0].previewOffered()).isFalse();
        assertThat(p[0].previewing()).isFalse();
    }
}
