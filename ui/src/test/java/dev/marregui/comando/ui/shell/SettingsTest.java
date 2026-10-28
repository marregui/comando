package dev.marregui.comando.ui.shell;

import dev.marregui.comando.ui.kit.theme.NamedTheme;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/// [Settings] keeps comando's settings in a JSON file, migrating the earlier Java preferences once.
/// - The first open copies the earlier preferences typed, in key order, and leaves them as they were; the file wins after.
/// - A change is written at flush, atomically, and read by the next window.
/// - An unreadable file is kept aside and the settings start empty.
class SettingsTest {

    private final Preferences legacy = Preferences.userRoot().node("comando-test-" + UUID.randomUUID());

    @AfterEach
    void removeNode() throws BackingStoreException {
        legacy.removeNode();
    }

    /// Strings, negative integers and booleans from a private preferences node arrive typed, are written as JSON numbers
    /// and booleans in key order, the node is left unchanged, and a later change to it is not read again.
    @Test
    void theFirstOpenCopiesTheEarlierPreferencesTypedAndLeavesThemAsTheyWere(@TempDir Path home) throws Exception {
        legacy.put("theme.ground", "MIDNIGHT");
        legacy.put("theme.tint", "ELECTRIC_BLUE");
        legacy.putInt("window.width", 1728);
        legacy.putInt("window.y", -1044);
        legacy.putBoolean("sidebar.visible", false);
        Path file = home.resolve("settings.json");

        Settings s = Settings.open(file, legacy);
        assertThat(new WindowLayout(s).theme()).isEqualTo(NamedTheme.MIDNIGHT_BLUE);
        assertThat(s.getInt("window.y", 0)).isEqualTo(-1044);
        assertThat(s.getBoolean("sidebar.visible", true)).isFalse();
        assertThat(Files.readString(file)).as("numbers and booleans as JSON's, in key order")
                .contains("\"sidebar.visible\" : false").contains("\"window.width\" : 1728")
                .containsSubsequence("sidebar.visible", "theme.ground", "theme.tint", "window.width", "window.y");
        assertThat(legacy.get("theme.ground", null)).as("the preferences stay").isEqualTo("MIDNIGHT");

        legacy.put("theme.ground", "SLATE");
        assertThat(new WindowLayout(Settings.open(file, legacy)).theme()).as("read once: the file wins from then on")
                .isEqualTo(NamedTheme.MIDNIGHT_BLUE);
    }

    /// With nothing to migrate no file is written; a theme and Gradle tasks set through [WindowLayout] are read by a fresh
    /// open, and no temporary file is left.
    @Test
    void aChangeIsWrittenAtFlushAndReadByTheNextWindow(@TempDir Path home) throws Exception {
        Path file = home.resolve("settings.json");
        Settings s = Settings.open(file, legacy);
        assertThat(file).as("nothing to copy, nothing written").doesNotExist();
        WindowLayout layout = new WindowLayout(s);
        layout.gradleTasks(":app:run");
        layout.theme(NamedTheme.EVERGREEN_FOREST);
        assertThat(new WindowLayout(Settings.open(file, legacy)).theme()).isEqualTo(NamedTheme.EVERGREEN_FOREST);
        assertThat(new WindowLayout(Settings.open(file, legacy)).gradleTasks()).isEqualTo(":app:run");
        assertThat(home.resolve("settings.json.tmp")).as("written beside, then moved").doesNotExist();
    }

    /// Malformed JSON gives empty settings, and the bytes are kept in `settings.json.unreadable`.
    @Test
    void anUnreadableFileIsKeptAsideAndTheSettingsStartEmpty(@TempDir Path home) throws Exception {
        Path file = home.resolve("settings.json");
        Files.writeString(file, "{ not json");
        Settings s = Settings.open(file, legacy);
        assertThat(s.values()).isEmpty();
        assertThat(home.resolve("settings.json.unreadable")).hasContent("{ not json");
    }
}
