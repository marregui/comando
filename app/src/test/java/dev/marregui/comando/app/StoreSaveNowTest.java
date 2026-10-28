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

/// [Store#saveNow], the ⌘S save: the panels are on disk when the save completes, not 800 ms later; a failed write says
/// why.
/// - the panel's file and `panels.json` are written before the future completes
/// - a write that cannot replace its file completes with the reason
class StoreSaveNowTest {

    @TempDir
    Path home;

    /// The future completes with no error, and by then `panels/work.md` holds the panel's text `SELECT 42;` and
    /// `panels.json` names it.
    @Test
    void saveNowWritesThePanelsBeforeItCompletes() throws Exception {
        PanelId id = new PanelId("p-1");
        try (Store store = new Store(home)) {
            store.load();
            store.savePanels(List.of(new PanelState(id, "work", "", "")), List.of());
            store.changed(id, "SELECT 42;");
            assertThat(store.saveNow().get(5, TimeUnit.SECONDS)).isNull();
            assertThat(Files.readString(home.resolve("panels").resolve("work.md"))).isEqualTo("SELECT 42;");
            assertThat(Files.readString(home.resolve("panels.json"))).contains("work.md");
        }
    }

    /// With a non-empty directory where the panel's file goes, so the replacing move cannot remove it, the future completes
    /// with a non-null reason.
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
