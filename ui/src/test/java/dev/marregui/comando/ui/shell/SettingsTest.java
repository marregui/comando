package dev.marregui.comando.ui.shell;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.prefs.Preferences;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.testkit.MemoryPreferences;
import dev.marregui.comando.ui.kit.theme.NamedTheme;

import static org.assertj.core.api.Assertions.assertThat;

/// [Settings] keeps comando's settings in a JSON file, migrating the earlier Java preferences once.
/// - The first open copies the earlier preferences typed, in key order, and leaves them as they were; the file wins
///   after.
/// - A change is written at flush, atomically, and read by the next window.
/// - An unreadable file is kept aside and the settings start empty.
/// - The file names its version; a later one is kept as it is, with what the user is told once.
/// - The checkpoint threshold is a percentage.
class SettingsTest {

    /// The earlier preferences, in memory: the test never touches the user's preferences store.
    private final Preferences legacy = new MemoryPreferences();

    /// Strings, negative integers and booleans from the earlier preferences arrive typed, are written as JSON numbers
    /// and booleans in key order, the node is left unchanged, and a later change to it is not read again.
    @Test
    void theFirstOpenCopiesTheEarlierPreferencesTypedAndLeavesThemAsTheyWere(@TempDir final Path home) throws Exception {
        legacy.put("theme.ground", "MIDNIGHT");
        legacy.put("theme.tint", "ELECTRIC_BLUE");
        legacy.putInt("window.width", 1728);
        legacy.putInt("window.y", -1044);
        legacy.putBoolean("sidebar.visible", false);
        final Path file = home.resolve("settings.json");

        final Settings s = Settings.open(file, legacy);
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

    /// With nothing to migrate no file is written; a theme and Gradle tasks set through [WindowLayout] are read by a
    /// fresh open, and no temporary file is left.
    @Test
    void aChangeIsWrittenAtFlushAndReadByTheNextWindow(@TempDir final Path home) throws Exception {
        final Path file = home.resolve("settings.json");
        final Settings s = Settings.open(file, legacy);
        assertThat(file).as("nothing to copy, nothing written").doesNotExist();
        final WindowLayout layout = new WindowLayout(s);
        layout.gradleTasks(":app:run");
        layout.theme(NamedTheme.EVERGREEN_FOREST);
        assertThat(new WindowLayout(Settings.open(file, legacy)).theme()).isEqualTo(NamedTheme.EVERGREEN_FOREST);
        assertThat(new WindowLayout(Settings.open(file, legacy)).gradleTasks()).isEqualTo(":app:run");
        assertThat(home.resolve("settings.json.tmp")).as("written beside, then moved").doesNotExist();
    }

    /// Malformed JSON gives empty settings, and the bytes are kept in `settings.json.unreadable`.
    @Test
    void anUnreadableFileIsKeptAsideAndTheSettingsStartEmpty(@TempDir final Path home) throws Exception {
        final Path file = home.resolve("settings.json");
        Files.writeString(file, "{ not json");
        final Settings s = Settings.open(file, legacy);
        assertThat(s.values()).isEmpty();
        assertThat(home.resolve("settings.json.unreadable")).hasContent("{ not json");
    }

    /// The file names its schema's version first (record 133); a file of a later version is kept as it is, its values
    /// not read, and nothing written over it.
    @Test
    void theFileNamesItsVersionAndALaterOneIsKeptAsItIs(@TempDir final Path home) throws Exception {
        final Path file = home.resolve("settings.json");
        final Settings s = Settings.open(file, legacy);
        s.put("tools.gradle", "build");
        s.flush();
        assertThat(Files.readString(file)).containsSubsequence("\"version\" : 1", "\"tools.gradle\" : \"build\"");
        assertThat(Settings.open(file, legacy).values()).as("the version is no setting").containsOnlyKeys("tools.gradle");
        assertThat(Settings.open(file, legacy).refusal()).as("its own version reads").isEmpty();

        final String later = "{\"version\": 2, \"tools.gradle\": \"check\"}";
        Files.writeString(file, later);
        final Settings newer = Settings.open(file, legacy);
        assertThat(newer.values()).isEmpty();
        assertThat(newer.refusal()).as("what the user is told once").isEqualTo("settings.json is of version 2, written by a later comando;"
                + " this one reads version 1. It is kept as it is and nothing is saved to it until a comando that reads it runs;"
                + " the window runs on its defaults");
        newer.put("tools.gradle", "test");
        newer.flush();
        assertThat(Files.readString(file)).isEqualTo(later);
    }

    /// The checkpoint threshold is a share of the context window in percent, 40 until chosen (record 129), 10 to 100;
    /// the token count an earlier version kept is not read, and a choice removes it.
    @Test
    void theCheckpointThresholdIsAPercentageAndTheOldTokenCountIsNotRead(@TempDir final Path home) {
        final Settings s = Settings.open(home.resolve("settings.json"), legacy);
        s.putInt(WindowLayout.CHECKPOINT_THRESHOLD_TOKENS, 400_000);
        final WindowLayout layout = new WindowLayout(s);
        assertThat(layout.checkpointThreshold()).isEqualTo(40);
        layout.checkpointThreshold(55);
        assertThat(new WindowLayout(Settings.open(home.resolve("settings.json"), legacy)).checkpointThreshold()).isEqualTo(55);
        assertThat(s.values()).doesNotContainKey(WindowLayout.CHECKPOINT_THRESHOLD_TOKENS);
        layout.checkpointThreshold(250);
        assertThat(layout.checkpointThreshold()).as("at most the whole window").isEqualTo(100);
        s.putInt(WindowLayout.CHECKPOINT_THRESHOLD, 0);
        assertThat(layout.checkpointThreshold()).as("an earlier version's 0, no checkpoint, reads as the minimum").isEqualTo(10);
        layout.checkpointThreshold(5);
        assertThat(layout.checkpointThreshold()).as("at least the minimum").isEqualTo(10);
    }
}
