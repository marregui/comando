package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.schema.SchemaRegistry;
import dev.marregui.comando.core.state.PanelState;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import java.nio.file.Path;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class EditorPanelPreviewTest {

    private static String shown(EditorPanel p) {
        try {
            return p.rendered().getDocument().getText(0, p.rendered().getDocument().getLength());
        } catch (BadLocationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static EditorPanel panel(String title, Consumer<EditorPanel> setUp) throws Exception {
        EditorPanel[] p = new EditorPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            p[0] = new EditorPanel(new PanelState(new PanelId(title), title, "", ""), intent -> {
            }, new SchemaRegistry());
            setUp.accept(p[0]);
        });
        return p[0];
    }

    @Test
    void aMarkdownDocumentOpensRenderedAndEditSwitchesToTheText() throws Exception {
        EditorPanel panel = panel("NEVSKY-PLAN", p -> {
            p.editor().load("# Nevsky\n\nThe **plan**.\n");
            p.file(Path.of("/s/NEVSKY-PLAN.md"), true);
        });
        assertThat(panel.previewOffered()).isTrue();
        assertThat(panel.previewing()).as("a scratchpad document opens rendered").isTrue();
        assertThat(panel.modeLabel()).as("the button names the other view").isEqualTo("Edit");
        assertThat(shown(panel)).contains("Nevsky").contains("The plan.").doesNotContain("**").doesNotContain("#");
        assertThat(panel.editor().area().getSyntaxEditingStyle()).isEqualTo(SyntaxConstants.SYNTAX_STYLE_MARKDOWN);

        SwingUtilities.invokeAndWait(() -> panel.reload("# Nevsky v2\n"));
        assertThat(panel.editor().text()).isEqualTo("# Nevsky v2\n");
        assertThat(shown(panel)).as("an outside change shows in the preview too").contains("Nevsky v2");

        SwingUtilities.invokeAndWait(panel::togglePreview);
        assertThat(panel.previewing()).as("Edit shows the text").isFalse();
        assertThat(panel.modeLabel()).isEqualTo("Preview");

        SwingUtilities.invokeAndWait(() -> panel.file(Path.of("/s/NEVSKY-PLAN.md"), true));
        assertThat(panel.previewing()).as("a render does not take the editor away").isFalse();
    }

    @Test
    void aPanelsOwnTextStaysInTheEditorAsSqlAndPrompts() throws Exception {
        EditorPanel panel = panel("spec", p -> {
            p.editor().load("# spec\n\nselect 1;\n");
            p.file(Path.of("/home/.comando/panels/spec.md"), false);
        });
        assertThat(panel.previewOffered()).isTrue();
        assertThat(panel.previewing()).isFalse();
        assertThat(panel.modeLabel()).isEqualTo("Preview");
        assertThat(panel.editor().area().getSyntaxEditingStyle()).isEqualTo(SyntaxConstants.SYNTAX_STYLE_SQL);

        SwingUtilities.invokeAndWait(() -> panel.file(Path.of("/home/.comando/panels/spec 2.md"), false));
        assertThat(panel.previewing()).as("a renamed panel's file is still its own").isFalse();

        SwingUtilities.invokeAndWait(panel::togglePreview);
        assertThat(panel.previewing()).isTrue();
    }

    @Test
    void aSourceFileIsColouredAsItsLanguageAndStaysAsText() throws Exception {
        EditorPanel java = panel("Red.java", p -> {
            p.file(Path.of("/s/work/Red.java"), true);
            p.togglePreview();
        });
        assertThat(java.previewOffered()).isFalse();
        assertThat(java.previewing()).isFalse();
        assertThat(java.editor().area().getSyntaxEditingStyle()).isEqualTo(SyntaxConstants.SYNTAX_STYLE_JAVA);

        EditorPanel python = panel("rig.py", p -> p.file(Path.of("/s/work/rig.py"), true));
        assertThat(python.editor().area().getSyntaxEditingStyle()).isEqualTo(SyntaxConstants.SYNTAX_STYLE_PYTHON);
    }
}
