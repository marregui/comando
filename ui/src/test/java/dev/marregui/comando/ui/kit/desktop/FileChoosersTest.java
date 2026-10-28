package dev.marregui.comando.ui.kit.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/// [FileChoosers#startDirectory] picks the folder a file chooser opens in.
/// - A folder opens as itself, a file in its folder, a missing path in its nearest existing parent.
/// - No start opens in the user's home.
class FileChoosersTest {

    /// A folder, a file in it and a path two levels below it that does not exist all open in that folder; null opens
    /// in `user.home`.
    @Test
    void theChooserOpensInTheFolderOfTheStartOrItsNearestExistingParent(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("a.txt"), "x");
        assertThat(FileChoosers.startDirectory(dir)).isEqualTo(dir);
        assertThat(FileChoosers.startDirectory(file)).isEqualTo(dir);
        assertThat(FileChoosers.startDirectory(dir.resolve("missing/deeper"))).isEqualTo(dir);
        assertThat(FileChoosers.startDirectory(null)).isEqualTo(Path.of(System.getProperty("user.home")));
    }
}
