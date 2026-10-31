package dev.marregui.comando.ui.kit.action;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import javax.swing.KeyStroke;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [CommandPalette] ranks commands against the typed query and renders their keys as text.
/// - A prefix match ranks 0, a word match 1, a match inside a word 2, and a subsequence 3.
/// - On macOS a key reads as its glyphs in Apple's modifier order.
/// - Elsewhere a key reads as `Ctrl+Alt+Shift+` and the key's short name.
class CommandPaletteTest {

    /// A query that starts the title ranks 0, one that starts a later word ranks 1, and one found only as a
    /// subsequence ranks 3. The palette lowercases both sides before calling [CommandPalette#rank].
    @Test
    void prefixAndWordMatchesRankBeforeSubsequences() {
        assertThat(CommandPalette.rank("terminal restart", "term")).isZero();
        assertThat(CommandPalette.rank("show the panel's terminal", "term")).isEqualTo(1);
        assertThat(CommandPalette.rank("focus the prompt bar", "term")).isEqualTo(3);
    }

    /// A query found inside a word, neither at the start of the title nor of a word, ranks 2: between a word match and
    /// a subsequence.
    @Test
    void aQueryInsideAWordRanksBetweenAWordMatchAndASubsequence() {
        assertThat(CommandPalette.rank("show the panel's terminal", "minal")).isEqualTo(2);
    }

    /// [CommandPalette#macText] puts ⇧ before ⌘ and renders Enter and Tab as ⏎ and ⇥.
    @Test
    void keysReadAsTheMacGlyphsInAppleOrder() {
        final String text = CommandPalette.macText(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.META_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        assertThat(text).isEqualTo("⇧⌘⏎");
        assertThat(CommandPalette.macText(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, InputEvent.CTRL_DOWN_MASK))).isEqualTo("⌃⇥");
    }

    /// [CommandPalette#otherText] joins Ctrl, Alt and Shift with `+` before the key, and names Enter, Tab, Esc,
    /// Space, digits and punctuation the way a desktop menu does.
    @Test
    void elsewhereKeysReadAsCtrlAltShiftAndTheKeysName() {
        assertThat(CommandPalette.otherText(KeyStroke.getKeyStroke(KeyEvent.VK_P, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK)))
                .isEqualTo("Ctrl+Shift+P");
        assertThat(CommandPalette.otherText(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK)))
                .isEqualTo("Ctrl+Alt+Enter");
        assertThat(CommandPalette.otherText(KeyStroke.getKeyStroke(KeyEvent.VK_1, InputEvent.ALT_DOWN_MASK))).isEqualTo("Alt+1");
        assertThat(CommandPalette.otherText(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, InputEvent.CTRL_DOWN_MASK))).isEqualTo("Ctrl+Tab");
        assertThat(CommandPalette.otherText(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, InputEvent.ALT_DOWN_MASK))).isEqualTo("Alt+Esc");
        assertThat(CommandPalette.otherText(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK))).isEqualTo("Ctrl+Space");
        assertThat(CommandPalette.otherText(KeyStroke.getKeyStroke(KeyEvent.VK_PERIOD, InputEvent.CTRL_DOWN_MASK))).isEqualTo("Ctrl+.");
    }
}
