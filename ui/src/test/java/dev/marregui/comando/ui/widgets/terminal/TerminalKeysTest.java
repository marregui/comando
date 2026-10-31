package dev.marregui.comando.ui.widgets.terminal;

import java.awt.Component;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;

import com.jediterm.terminal.ui.JediTermWidget;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;
import org.junit.jupiter.api.Test;

import dev.marregui.comando.ui.kit.desktop.Platform;

import static org.assertj.core.api.Assertions.assertThat;

/// [TerminalWidget#keepsKey]: a terminal keeps for its shell the control characters a shell reads: Ctrl with a letter,
/// `[ \ ]` or space. On macOS ⌃ sends them and no command uses it; elsewhere Ctrl is also ⌘'s key, and in a terminal
/// the shell has it.
/// - Ctrl with a letter, bracket, backslash or space stays with the shell
/// - other chords stay commands
/// - on macOS the terminal keeps ⌘K as its clear buffer
/// - outside a terminal nothing is kept
class TerminalKeysTest {

    private static final int CTRL = InputEvent.CTRL_DOWN_MASK;

    private final JediTermWidget term = new JediTermWidget(new DefaultSettingsProvider());
    private final Component inside = term.getTerminalPanel();

    private static KeyStroke key(final int code, final int modifiers) {
        return KeyStroke.getKeyStroke(code, modifiers);
    }

    /// Ctrl+A through Ctrl+Z, Ctrl+`[`, `\`, `]` and space are kept inside the terminal panel and on the widget itself.
    @Test
    void ctrlWithALetterOrABracketBackslashOrSpaceStaysWithTheShell() {
        for (int code = KeyEvent.VK_A; code <= KeyEvent.VK_Z; code++) {
            assertThat(TerminalWidget.keepsKey(inside, key(code, CTRL))).as(KeyEvent.getKeyText(code)).isTrue();
        }
        for (final int code : new int[]{KeyEvent.VK_OPEN_BRACKET, KeyEvent.VK_BACK_SLASH, KeyEvent.VK_CLOSE_BRACKET, KeyEvent.VK_SPACE}) {
            assertThat(TerminalWidget.keepsKey(inside, key(code, CTRL))).as(KeyEvent.getKeyText(code)).isTrue();
        }
        assertThat(TerminalWidget.keepsKey(term, key(KeyEvent.VK_C, CTRL))).as("the widget itself").isTrue();
    }

    /// Chords with Shift or Alt, Ctrl with a digit, Enter or `=`, and ⌘W are not kept.
    @Test
    void otherChordsStayCommandsSoTheyWorkFromTheTerminal() {
        assertThat(TerminalWidget.keepsKey(inside, key(KeyEvent.VK_P, CTRL | InputEvent.SHIFT_DOWN_MASK))).as("the palette").isFalse();
        assertThat(TerminalWidget.keepsKey(inside, key(KeyEvent.VK_1, CTRL))).as("panel 1").isFalse();
        assertThat(TerminalWidget.keepsKey(inside, key(KeyEvent.VK_1, InputEvent.ALT_DOWN_MASK))).as("the Session tab").isFalse();
        assertThat(TerminalWidget.keepsKey(inside, key(KeyEvent.VK_ENTER, CTRL))).isFalse();
        assertThat(TerminalWidget.keepsKey(inside, key(KeyEvent.VK_EQUALS, CTRL))).as("zoom").isFalse();
        assertThat(TerminalWidget.keepsKey(inside, key(KeyEvent.VK_W, CTRL | InputEvent.ALT_DOWN_MASK))).isFalse();
        assertThat(TerminalWidget.keepsKey(inside, key(KeyEvent.VK_W, InputEvent.META_DOWN_MASK))).as("⌘W on macOS").isFalse();
    }

    /// ⌘K is kept in the terminal only on macOS, never in a text field, and ⌘⇧K is never kept.
    @Test
    void onMacOsTheTerminalKeepsCommandKAsItsClearBufferAndTheChangesViewHasItElsewhere() {
        final KeyStroke cmdK = key(KeyEvent.VK_K, InputEvent.META_DOWN_MASK);
        assertThat(TerminalWidget.keepsKey(inside, cmdK)).isEqualTo(Platform.isMac());
        assertThat(TerminalWidget.keepsKey(new JTextField(), cmdK)).isFalse();
        assertThat(TerminalWidget.keepsKey(inside, key(KeyEvent.VK_K, InputEvent.META_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK))).isFalse();
    }

    /// A text field, a plain panel and a null component keep no Ctrl chord.
    @Test
    void outsideATerminalNothingIsKept() {
        assertThat(TerminalWidget.keepsKey(new JTextField(), key(KeyEvent.VK_W, CTRL))).isFalse();
        assertThat(TerminalWidget.keepsKey(new JPanel(), key(KeyEvent.VK_E, CTRL))).isFalse();
        assertThat(TerminalWidget.keepsKey(null, key(KeyEvent.VK_E, CTRL))).isFalse();
    }
}
