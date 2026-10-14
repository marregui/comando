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
        String text = CommandPalette.keyText(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.META_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        assertThat(text).isEqualTo("⇧⌘⏎");
        assertThat(CommandPalette.keyText(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, InputEvent.CTRL_DOWN_MASK))).isEqualTo("⌃⇥");
    }
}
