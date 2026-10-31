package dev.marregui.comando.ui.kit.theme;

import java.awt.Font;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// [Theme#pickSerif] chooses the headings' serif, and [Theme#fallback] the face for characters it lacks.
/// - Without New York, the first installed serif asked for, else Java's logical serif in bold.
/// - On macOS New York is loaded from its file.
/// - The fallback is the logical sans in the same style and size.
class SerifTest {

    /// With no New York file the first installed family in preference order is used, with none installed the bold
    /// logical serif, and a file Java cannot read falls back to the installed family.
    @Test
    void withoutNewYorkTheFirstInstalledSerifElseJavasLogicalSerif(@TempDir final Path dir) throws Exception {
        final Path none = dir.resolve("NewYork.ttf");
        assertThat(Theme.pickSerif(none, Set.of("DejaVu Serif", "Noto Serif")).getName()).as("the name asked for").isEqualTo("Noto Serif");
        final Font logical = Theme.pickSerif(none, Set.of());
        assertThat(logical.getName()).isEqualTo(Font.SERIF);
        assertThat(logical.isBold()).isTrue();

        final Path notAFont = Files.writeString(dir.resolve("broken.ttf"), "not a font");
        assertThat(Theme.pickSerif(notAFont, Set.of("Georgia")).getName()).as("a file Java cannot read").isEqualTo("Georgia");
    }

    /// On macOS New York loads bold from [Theme#NEW_YORK] and cannot draw `→`, which is why the fallback exists.
    /// Skipped elsewhere.
    @Test
    void onMacOsNewYorkLoadedFromItsFile() {
        assumeTrue(Files.isRegularFile(Theme.NEW_YORK), "macOS only");
        final Font serif = Theme.pickSerif(Theme.NEW_YORK, Set.of());
        assertThat(serif.getFamily()).contains("New York");
        assertThat(serif.isBold()).isTrue();
        assertThat(serif.canDisplay('→')).as("why the fallback exists").isFalse();
    }

    /// The fallback of a bold 19.5 pt serif is bold 19.5 pt `Dialog`.
    @Test
    void theFallbackIsTheLogicalSansInTheSameStyleAndSize() {
        final Font f = Theme.fallback(new Font(Font.SERIF, Font.BOLD, 1).deriveFont(19.5f));
        assertThat(f.getName()).isEqualTo(Font.DIALOG);
        assertThat(f.isBold()).isTrue();
        assertThat(f.getSize2D()).isEqualTo(19.5f);
    }
}
