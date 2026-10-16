package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.schema.SchemaRegistry;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.ui.widgets.editor.CommandEditor;
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
    void aLogIsReadOnlyAndFollowsItsEnd() throws Exception {
        EditorPanel log = panel("comando-0.log", p -> {
            p.editor().load("one\ntwo\n");
            p.file(Path.of("/home/.comando/logs/comando-0.log"), true);
        });
        assertThat(log.isLog()).isTrue();
        assertThat(log.editor().area().isEditable()).isFalse();
        assertThat(log.editor().area().getCaretPosition()).as("opens at its end").isEqualTo(8);
        SwingUtilities.invokeAndWait(() -> log.reload("one\ntwo\nthree\n"));
        assertThat(log.editor().area().getCaretPosition()).as("follows what was appended").isEqualTo(14);
        SwingUtilities.invokeAndWait(() -> {
            log.editor().area().setCaretPosition(0);
            log.reload("one\ntwo\nthree\nfour\n");
        });
        assertThat(log.editor().area().getCaretPosition()).as("a reader scrolled up stays there").isZero();

        EditorPanel notes = panel("notes", p -> p.file(Path.of("/s/notes.md"), true));
        assertThat(notes.editor().area().isEditable()).isTrue();
    }

    @Test
    void aDocumentOfAReadOnlyFolderIsShownAndNeverEdited() throws Exception {
        EditorPanel memory = panel("MEMORY", p -> {
            p.editor().load("# Memory\n");
            p.file(Path.of("/m/MEMORY.md"), true, true);
        });
        assertThat(memory.editable()).isFalse();
        assertThat(memory.previewing()).as("Markdown opens rendered").isTrue();
        assertThat(memory.previewOffered()).as("no Edit switch").isFalse();
        SwingUtilities.invokeAndWait(memory::togglePreview);
        assertThat(memory.previewing()).as("⌘⇧M does not switch to the editor either").isTrue();

        EditorPanel json = panel("agents", p -> p.file(Path.of("/m/agents.json"), true, true));
        assertThat(json.editable()).isFalse();

        EditorPanel notes = panel("notes", p -> p.file(Path.of("/s/notes.md"), true));
        SwingUtilities.invokeAndWait(() -> {
            notes.togglePreview();
            notes.file(Path.of("/s/notes.md"), true, true);
        });
        assertThat(notes.editable()).as("a folder made read-only while its file is open").isFalse();
        assertThat(notes.previewing()).isTrue();
        SwingUtilities.invokeAndWait(() -> notes.file(Path.of("/s/notes.md"), true, false));
        assertThat(notes.editable()).isTrue();
        assertThat(notes.previewOffered()).isTrue();
        EditorPanel own = panel("own", p -> p.file(Path.of("/m/own.md"), false, true));
        assertThat(own.editable()).as("a panel's own text is never read-only").isTrue();
    }

    @Test
    void clearEmptiesThePanelsOwnTextButNeverADocument() throws Exception {
        EditorPanel own = panel("notes", p -> {
            p.editor().load("select 1;\n");
            p.file(Path.of("/home/.comando/panels/notes.md"), false);
        });
        assertThat(own.clearOffered()).isTrue();
        SwingUtilities.invokeAndWait(own::clickClear);
        assertThat(own.editor().text()).isEmpty();
        assertThat(own.clearOffered()).as("nothing left to clear").isFalse();
        SwingUtilities.invokeAndWait(() -> own.editor().area().undoLastAction());
        assertThat(own.editor().text()).isEqualTo("select 1;\n");
        assertThat(own.clearOffered()).isTrue();

        EditorPanel document = panel("PLAN", p -> {
            p.editor().load("# Plan\n");
            p.file(Path.of("/s/PLAN.md"), true);
        });
        assertThat(document.clearOffered()).as("a file of its own is not emptied from the panel").isFalse();
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
        assertThat(panel.editor().area().getSyntaxEditingStyle()).isEqualTo(CommandEditor.PANEL_STYLE);

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
