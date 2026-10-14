package dev.marregui.comando.app;

import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.state.PanelState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** ⌘S: the panels are on disk when the save completes, not 800 ms later; a failed write says why. */
class StoreSaveNowTest {

    @TempDir
    Path home;

    @Test
    void saveNowWritesThePanelsBeforeItCompletes() throws Exception {
        PanelId id = new PanelId("p-1");
        try (Store store = new Store(home)) {
            store.load();
            store.savePanels(List.of(new PanelState(id, "work", "", "")));
            store.changed(id, "SELECT 42;");
            assertThat(store.saveNow().get(5, TimeUnit.SECONDS)).isNull();
            assertThat(Files.readString(home.resolve("panels").resolve("work.md"))).isEqualTo("SELECT 42;");
            assertThat(Files.readString(home.resolve("panels.json"))).contains("work.md");
        }
    }

    @Test
    void aFailedWriteCompletesWithTheReason() throws Exception {
        try (Store store = new Store(home)) {
            store.load();
            Files.deleteIfExists(home.resolve("panels.json"));
            // a non-empty directory where the file goes: the replacing move cannot remove it
            Files.createDirectory(home.resolve("panels.json"));
            Files.writeString(home.resolve("panels.json").resolve("keep"), "x");
            assertThat(store.saveNow().get(5, TimeUnit.SECONDS)).isNotNull();
        }
    }
}
