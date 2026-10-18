package dev.marregui.comando.ui.kit.action;

import org.junit.jupiter.api.Test;

import javax.swing.KeyStroke;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

import static org.assertj.core.api.Assertions.assertThat;

class CommandPaletteTest {

    @Test
    void prefixAndWordMatchesRankBeforeSubsequences() {
        // the palette compares lower case
        assertThat(CommandPalette.rank("terminal restart", "term")).isZero();
        assertThat(CommandPalette.rank("show the panel's terminal", "term")).isEqualTo(1);
        assertThat(CommandPalette.rank("focus the prompt bar", "term")).isEqualTo(3);
    }

    @Test
    void keysReadAsTheMacGlyphsInAppleOrder() {
        String text = CommandPalette.macText(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.META_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        assertThat(text).isEqualTo("⇧⌘⏎");
        assertThat(CommandPalette.macText(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, InputEvent.CTRL_DOWN_MASK))).isEqualTo("⌃⇥");
    }

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
