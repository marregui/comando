package dev.marregui.comando.ui.kit.desktop;

import org.junit.jupiter.api.Test;

import java.awt.event.InputEvent;
import java.io.File;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformTest {

    @Test
    void macOsIsRecognisedWhateverTheLetterCase() {
        assertThat(Platform.isMac("Mac OS X")).isTrue();
        assertThat(Platform.isMac("MAC OS X")).isTrue();
        assertThat(Platform.isMac("Linux")).isFalse();
        assertThat(Platform.isMac("Windows 11")).isFalse();
    }

    @Test
    void withoutADisplayMenuShortcutsUseCommandOnMacOsAndCtrlElsewhere() {
        assertThat(Platform.menuShortcutMask(true)).isEqualTo(InputEvent.META_DOWN_MASK);
        assertThat(Platform.menuShortcutMask(false)).isEqualTo(InputEvent.CTRL_DOWN_MASK);
        assertThat(Platform.menuShortcutMask()).isEqualTo(Platform.macKeys() ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK);
    }

    @Test
    void aProgramOnThePathIsLookedForInThePathsOrderSkippingEmptyEntries() {
        String path = String.join(File.pathSeparator, "/opt/a", "", " ", "/usr/bin");
        assertThat(Platform.onPath("mmdc", path)).containsExactly(Path.of("/opt/a/mmdc"), Path.of("/usr/bin/mmdc"));
        assertThat(Platform.onPath("mmdc", null)).isEmpty();
    }
}
