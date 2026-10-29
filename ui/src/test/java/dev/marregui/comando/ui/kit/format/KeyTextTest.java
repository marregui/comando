package dev.marregui.comando.ui.kit.format;

import dev.marregui.comando.ui.kit.desktop.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [KeyText] names keys in text as each desktop names them: macOS's glyphs, or Ctrl, Alt and Shift where ⌘ is Ctrl.
/// - ⌘, ⌥ and ⇧ read as Ctrl, Alt and Shift in that order, whatever order they are written in.
/// - macOS's ⌃ keys read where they moved on other desktops, so they collide with no other command's key.
/// - The key ends the chord; punctuation after it stays.
/// - Text without a chord is unchanged, and macOS keys leave the text as written.
class KeyTextTest {

    /**
     * The keys the run chose ({@code -Dcomando.keys}), restored after each test, never cleared: later tests read them.
     */
    private final String keys = System.getProperty(Platform.KEYS_PROPERTY);

    @AfterEach
    void tearDown() {
        if (keys == null) {
            System.clearProperty(Platform.KEYS_PROPERTY);
        } else {
            System.setProperty(Platform.KEYS_PROPERTY, keys);
        }
    }

    /// [KeyText#other] turns ⌘, ⌥, ⇧ and ⏎ into `Ctrl+`, `Alt+`, `Shift+` and `Enter`, always in Ctrl, Alt, Shift
    /// order, inside running text.
    @Test
    void cmdOptionAndShiftReadAsCtrlAltAndShiftInThatOrder() {
        assertThat(KeyText.other("New panel (⌘T)")).isEqualTo("New panel (Ctrl+T)");
        assertThat(KeyText.other("⌘⇧T or the Sessions tab reopens it")).isEqualTo("Ctrl+Shift+T or the Sessions tab reopens it");
        assertThat(KeyText.other("⇧⌘⏎")).isEqualTo("Ctrl+Shift+Enter");
        assertThat(KeyText.other("⌥⏎ runs as SQL")).isEqualTo("Alt+Enter runs as SQL");
        assertThat(KeyText.other("⌘⌥G")).isEqualTo("Ctrl+Alt+G");
        assertThat(KeyText.other("Find (⇧⏎ previous)")).isEqualTo("Find (Shift+Enter previous)");
    }

    /// ⌃ with a digit reads as Alt with it, ⌃⏎ as `Ctrl+Alt+Enter`, and ⌃ with Tab or Space stays Ctrl, ranges
    /// included.
    @Test
    void macOsControlKeysReadWhereTheyMovedSoTheyAreNoOtherCommandsKeys() {
        assertThat(KeyText.other("waiting for your approval · ⌃2")).isEqualTo("waiting for your approval · Alt+2");
        assertThat(KeyText.other("⌃1–⌃4")).isEqualTo("Alt+1–Alt+4");
        assertThat(KeyText.other("⌃⏎ types the line into it")).isEqualTo("Ctrl+Alt+Enter types the line into it");
        assertThat(KeyText.other("⌃⇥, ⌃⇧⇥")).isEqualTo("Ctrl+Tab, Ctrl+Shift+Tab");
        assertThat(KeyText.other("⌃Space")).isEqualTo("Ctrl+Space");
    }

    /// A period, backslash or digit right after the modifiers is the key; a period, ellipsis, dash, comma or bracket
    /// after the key stays text.
    @Test
    void theKeyEndsTheChordAndPunctuationAfterItStays() {
        assertThat(KeyText.other("press ⌘⏎.")).isEqualTo("press Ctrl+Enter.");
        assertThat(KeyText.other("(⌘.)")).isEqualTo("(Ctrl+.)");
        assertThat(KeyText.other("⌘\\ the side pane")).isEqualTo("Ctrl+\\ the side pane");
        assertThat(KeyText.other("⌘1…⌘9")).isEqualTo("Ctrl+1…Ctrl+9");
        assertThat(KeyText.other("⌘⌥1–4")).isEqualTo("Ctrl+Alt+1–4");
        assertThat(KeyText.other("| ⌘C, ⌘A |")).isEqualTo("| Ctrl+C, Ctrl+A |");
    }

    /// Key glyphs with no modifier (⏎, ⎋, arrows) and the empty string pass through unchanged.
    @Test
    void textWithoutAChordIsUnchanged() {
        assertThat(KeyText.other("⏎ or A, D or ⎋, ↑ ↓")).isEqualTo("⏎ or A, D or ⎋, ↑ ↓");
        assertThat(KeyText.other("")).isEmpty();
    }

    /// [KeyText#of] keeps the glyphs when [Platform#KEYS_PROPERTY] is `mac`, converts them when it is `linux`, and
    /// returns null for null.
    @Test
    void macOsKeysLeaveTheTextAsWritten() {
        System.setProperty(Platform.KEYS_PROPERTY, "mac");
        assertThat(KeyText.of("⌘⇧T, ⌃2")).isEqualTo("⌘⇧T, ⌃2");
        System.setProperty(Platform.KEYS_PROPERTY, "linux");
        assertThat(KeyText.of("⌘⇧T, ⌃2")).isEqualTo("Ctrl+Shift+T, Alt+2");
        assertThat(KeyText.of(null)).isNull();
    }
}
