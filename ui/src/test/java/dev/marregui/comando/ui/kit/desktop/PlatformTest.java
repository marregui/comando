package dev.marregui.comando.ui.kit.desktop;

import java.awt.event.InputEvent;
import java.io.File;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [Platform] answers which desktop comando runs on and where its programs are.
/// - macOS is recognised in any letter case.
/// - Menu shortcuts use ⌘ on macOS and Ctrl elsewhere, with or without a display.
/// - A program on the path is looked for in the path's order, skipping blank entries.
class PlatformTest {

    /// [Platform#isMac] accepts `Mac OS X` in any case and refuses Linux and Windows.
    @Test
    void macOsIsRecognisedWhateverTheLetterCase() {
        assertThat(Platform.isMac("Mac OS X")).isTrue();
        assertThat(Platform.isMac("MAC OS X")).isTrue();
        assertThat(Platform.isMac("Linux")).isFalse();
        assertThat(Platform.isMac("Windows 11")).isFalse();
    }

    /// [Platform#menuShortcutMask] is META on macOS and CTRL elsewhere, and the no-argument form agrees with
    /// [Platform#macKeys] in a headless run.
    @Test
    void withoutADisplayMenuShortcutsUseCommandOnMacOsAndCtrlElsewhere() {
        assertThat(Platform.menuShortcutMask(true)).isEqualTo(InputEvent.META_DOWN_MASK);
        assertThat(Platform.menuShortcutMask(false)).isEqualTo(InputEvent.CTRL_DOWN_MASK);
        assertThat(Platform.menuShortcutMask()).isEqualTo(Platform.macKeys() ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK);
    }

    /// [Platform#onPath] lists one candidate per path entry in order, skipping empty and blank entries, and none for a
    /// null path.
    @Test
    void aProgramOnThePathIsLookedForInThePathsOrderSkippingEmptyEntries() {
        final String path = String.join(File.pathSeparator, "/opt/a", "", " ", "/usr/bin");
        assertThat(Platform.onPath("mmdc", path)).containsExactly(Path.of("/opt/a/mmdc"), Path.of("/usr/bin/mmdc"));
        assertThat(Platform.onPath("mmdc", null)).isEmpty();
    }
}
