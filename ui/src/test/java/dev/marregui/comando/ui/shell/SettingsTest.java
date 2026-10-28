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

class SettingsTest {

    private final Preferences legacy = Preferences.userRoot().node("comando-test-" + UUID.randomUUID());

    @AfterEach
    void removeNode() throws BackingStoreException {
        legacy.removeNode();
    }

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

    @Test
    void anUnreadableFileIsKeptAsideAndTheSettingsStartEmpty(@TempDir Path home) throws Exception {
        Path file = home.resolve("settings.json");
        Files.writeString(file, "{ not json");
        Settings s = Settings.open(file, legacy);
        assertThat(s.values()).isEmpty();
        assertThat(home.resolve("settings.json.unreadable")).hasContent("{ not json");
    }
}
