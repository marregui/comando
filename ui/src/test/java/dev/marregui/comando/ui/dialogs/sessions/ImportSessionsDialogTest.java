package dev.marregui.comando.ui.dialogs.sessions;

import java.awt.event.KeyEvent;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.testkit.RecordingSink;

import static org.assertj.core.api.Assertions.assertThat;

/// [ImportSessionsDialog] imports stored conversations as sessions.
/// - Enter imports the selection and Escape closes.
/// - An untitled conversation is named after its folder, or `session`, with the first eight characters of its id.
/// - A titled conversation is named by its title, stripped and cut at 48 characters.
class ImportSessionsDialogTest {

    /// Installed on a root pane, the default button is `Import selected` and Escape is bound window-wide.
    @Test
    void enterImportsAndEscapeCloses() {
        final ImportSessionsDialog d = new ImportSessionsDialog(null, new RecordingSink(), List::of);
        final JRootPane root = new JRootPane();
        d.install(root);
        assertThat(root.getDefaultButton().getText()).isEqualTo("Import selected");
        assertThat(root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0))).isNotNull();
    }

    /// [ImportSessionsDialog#nameFor] uses the folder's last name; the root, an empty folder and a path with a NUL,
    /// which is no valid path, fall back to `session`.
    @Test
    void anUntitledConversationIsNamedAfterItsFolderOrSession() {
        assertThat(ImportSessionsDialog.nameFor(new StoredConversation("1a2b3c4d5e", "/w/edge", "", "", "", 0L, 0L))).isEqualTo("edge 1a2b3c4d");
        assertThat(ImportSessionsDialog.nameFor(new StoredConversation("1a2b3c4d5e", "/", "", "", "", 0L, 0L))).isEqualTo("session 1a2b3c4d");
        assertThat(ImportSessionsDialog.nameFor(new StoredConversation("1a2b3c4d5e", "", "", "", "", 0L, 0L))).isEqualTo("session 1a2b3c4d");
        assertThat(ImportSessionsDialog.nameFor(new StoredConversation("1a2b3c4d5e", "/w/a\0b", "", "", "", 0L, 0L))).isEqualTo("session 1a2b3c4d");
    }

    /// A titled conversation is named by its title, stripped, and a title over 48 characters is cut to 48.
    @Test
    void aTitledConversationIsNamedByItsTitleCutAt48() {
        assertThat(ImportSessionsDialog.nameFor(new StoredConversation("1a2b3c4d5e", "/w/edge", "", "  Fix the lane  ", "", 0L, 0L)))
                .isEqualTo("Fix the lane");
        assertThat(ImportSessionsDialog.nameFor(new StoredConversation("1a2b3c4d5e", "/w/edge", "", "x".repeat(60), "", 0L, 0L)))
                .isEqualTo("x".repeat(48));
    }
}
