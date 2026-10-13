package dev.marregui.comando.ui.dialogs.sessions;

import dev.marregui.comando.testkit.RecordingSink;
import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;
import java.awt.event.KeyEvent;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ImportSessionsDialogTest {

    @Test
    void enterImportsAndEscapeCloses() {
        ImportSessionsDialog d = new ImportSessionsDialog(null, new RecordingSink(), List::of);
        JRootPane root = new JRootPane();
        d.install(root);
        assertThat(root.getDefaultButton().getText()).isEqualTo("Import selected");
        assertThat(root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0))).isNotNull();
    }
}
