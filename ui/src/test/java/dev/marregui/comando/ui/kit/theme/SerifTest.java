package dev.marregui.comando.ui.kit.theme;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Font;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SerifTest {

    @Test
    void withoutNewYorkTheFirstInstalledSerifElseJavasLogicalSerif(@TempDir Path dir) throws Exception {
        Path none = dir.resolve("NewYork.ttf");
        assertThat(Theme.pickSerif(none, Set.of("DejaVu Serif", "Noto Serif")).getName()).as("the name asked for").isEqualTo("Noto Serif");
        Font logical = Theme.pickSerif(none, Set.of());
        assertThat(logical.getName()).isEqualTo(Font.SERIF);
        assertThat(logical.isBold()).isTrue();

        Path notAFont = Files.writeString(dir.resolve("broken.ttf"), "not a font");
        assertThat(Theme.pickSerif(notAFont, Set.of("Georgia")).getName()).as("a file Java cannot read").isEqualTo("Georgia");
    }

    @Test
    void onMacOsNewYorkLoadedFromItsFile() {
        assumeTrue(Files.isRegularFile(Theme.NEW_YORK), "macOS only");
        Font serif = Theme.pickSerif(Theme.NEW_YORK, Set.of());
        assertThat(serif.getFamily()).contains("New York");
        assertThat(serif.isBold()).isTrue();
        assertThat(serif.canDisplay('→')).as("why the fallback exists").isFalse();
    }

    @Test
    void theFallbackIsTheLogicalSansInTheSameStyleAndSize() {
        Font f = Theme.fallback(new Font(Font.SERIF, Font.BOLD, 1).deriveFont(19.5f));
        assertThat(f.getName()).isEqualTo(Font.DIALOG);
        assertThat(f.isBold()).isTrue();
        assertThat(f.getSize2D()).isEqualTo(19.5f);
    }
}
