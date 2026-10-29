package dev.marregui.comando.ui.shell;

import dev.marregui.comando.ui.kit.action.Command;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.text.DefaultEditorKit;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// [Keys] forwards edit keys to the focused component and lists the commands' keys as a Markdown table.
/// - An edit key runs the focused component's own binding, and passes when it has none.
/// - The key table lists every command with a key, escaped, under its place.
/// - The table groups commands by place in the order each place first appears.
class CommandKeysTest {

    private static final int CMD = InputEvent.META_DOWN_MASK;

    /// [Keys#forward] runs a text field's select-all binding for ⌘A, typed or pressed; a component that reads keys
    /// itself, as the terminal does, and no component both return false.
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

    /// [Keys#markdown] writes a `Where | Command | Keys` table, escapes a `|` in a title, and leaves out a command with
    /// no key.
    @Test
    void theKeyTableListsEveryCommandWithAKeyByMenu() {
        String md = Keys.markdown(List.of(
                Command.of("a", "Run | block", null, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, CMD), "Run", () -> { }),
                Command.of("b", "No key", null, null, "Run", () -> { })));
        assertThat(md).contains("| Where | Command | Keys |", "| Run | Run \\| block |").doesNotContain("No key");
    }

    /// Commands of interleaved places come out grouped, the first place first, keeping their order within each place.
    @Test
    void theKeyTableGroupsCommandsWhereTheyBelongInTheOrderEachPlaceFirstAppears() {
        KeyStroke k = KeyStroke.getKeyStroke(KeyEvent.VK_1, CMD);
        String md = Keys.markdown(List.of(
                Command.of("a", "New panel", null, k, "Panels", () -> { }),
                Command.of("b", "Open file…", null, k, "Files", () -> { }),
                Command.of("c", "Close", null, k, "Panels", () -> { }),
                Command.of("d", "Export view…", null, k, "Files", () -> { })));
        assertThat(md.indexOf("New panel")).isLessThan(md.indexOf("Close"));
        assertThat(md.indexOf("Close")).as("the panels together").isLessThan(md.indexOf("Open file…"));
        assertThat(md.indexOf("Open file…")).isLessThan(md.indexOf("Export view…"));
    }
}
