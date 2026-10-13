package dev.marregui.comando.ui.shell;

import dev.marregui.comando.ui.kit.action.Command;
import org.junit.jupiter.api.Test;

import java.awt.event.KeyEvent;
import java.util.List;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.text.DefaultEditorKit;

import static org.assertj.core.api.Assertions.assertThat;

class CommandKeysTest {

    private static final int CMD = java.awt.event.InputEvent.META_DOWN_MASK;

    @Test
    void anEditKeyRunsTheFocusedComponentsOwnBindingAndPassesWhenThereIsNone() {
        JTextField field = new JTextField("hello");
        KeyStroke selectAll = KeyStroke.getKeyStroke(KeyEvent.VK_A, CMD);
        field.getInputMap().put(selectAll, DefaultEditorKit.selectAllAction);
        assertThat(Keys.forward(field, selectAll, false)).isTrue();
        assertThat(Keys.forward(field, selectAll, true)).isTrue();
        assertThat(field.getSelectedText()).isEqualTo("hello");
        // a component that reads keys itself, as the terminal does, keeps the key
        assertThat(Keys.forward(new JPanel(), selectAll, false)).isFalse();
        assertThat(Keys.forward(null, selectAll, false)).isFalse();
    }

    @Test
    void theKeyTableListsEveryCommandWithAKeyByMenu() {
        String md = Keys.markdown(List.of(
                Command.of("a", "Run | block", null, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, CMD), "Run", () -> { }),
                Command.of("b", "No key", null, null, "Run", () -> { })));
        assertThat(md).contains("| Run | Run \\| block |").doesNotContain("No key");
    }
}
