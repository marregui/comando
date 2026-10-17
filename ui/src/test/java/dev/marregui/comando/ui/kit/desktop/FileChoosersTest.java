package dev.marregui.comando.ui.kit.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FileChoosersTest {

    @Test
    void theChooserOpensInTheFolderOfTheStartOrItsNearestExistingParent(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("a.txt"), "x");
        assertThat(FileChoosers.startDirectory(dir)).isEqualTo(dir);
        assertThat(FileChoosers.startDirectory(file)).isEqualTo(dir);
        assertThat(FileChoosers.startDirectory(dir.resolve("missing/deeper"))).isEqualTo(dir);
        assertThat(FileChoosers.startDirectory(null)).isEqualTo(Path.of(System.getProperty("user.home")));
    }
}
