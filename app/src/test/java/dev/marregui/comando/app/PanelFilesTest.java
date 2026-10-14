package dev.marregui.comando.app;

import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.ui.PanelTexts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class PanelFilesTest {

    @TempDir
    Path home;

    private Path dir() {
        return home.resolve("panels");
    }

    private List<String> names() throws Exception {
        try (Stream<Path> files = Files.list(dir())) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void eachPanelsTextIsAMarkdownFileNamedAfterItsTitleAndPanelsJsonHoldsNoText() throws Exception {
        PanelId spec = new PanelId("a");
        PanelId notes = new PanelId("b");
        try (Store store = new Store(home)) {
            store.load();
            store.savePanels(List.of(new PanelState(spec, "nevsky spec", "", ""), new PanelState(notes, "notes", "", "")));
            store.changed(spec, "# Nevsky\n\nSELECT 1;\n");
            store.changed(notes, "todo");
            assertThat(store.saveNow().get(5, TimeUnit.SECONDS)).isNull();
        }
        assertThat(Files.readString(dir().resolve("nevsky spec.md"))).isEqualTo("# Nevsky\n\nSELECT 1;\n");
        assertThat(Files.readString(dir().resolve("notes.md"))).isEqualTo("todo");
        String json = Files.readString(home.resolve("panels.json"));
        assertThat(json).contains("\"file\" : \"nevsky spec.md\"").doesNotContain("content").doesNotContain("SELECT 1");
        try (Store store = new Store(home)) {
            Store.Loaded loaded = store.load();
            assertThat(loaded.panels()).extracting(PanelState::title).containsExactly("nevsky spec", "notes");
            assertThat(store.load(spec)).isEqualTo("# Nevsky\n\nSELECT 1;\n");
            assertThat(store.file(spec)).contains(dir().resolve("nevsky spec.md"));
        }
    }

    @Test
    void textStoredInPanelsJsonBeforePanelsHadFilesMovesIntoFiles() throws Exception {
        Files.writeString(home.resolve("connections.json"), "[]");
        Files.writeString(home.resolve("sessions.json"), "[]");
        Files.writeString(home.resolve("panels.json"), """
                [{"id":"p","title":"main","connection":"","session":"","content":"SELECT 42;"}]""");
        try (Store store = new Store(home)) {
            store.load();
            assertThat(store.load(new PanelId("p"))).isEqualTo("SELECT 42;");
        }
        assertThat(Files.readString(dir().resolve("main.md"))).isEqualTo("SELECT 42;");
        assertThat(Files.readString(home.resolve("panels.json"))).contains("\"file\" : \"main.md\"").doesNotContain("content");
    }

    @Test
    void aRenamedPanelMovesItsFileAndEqualTitlesGetNumberedNames() throws Exception {
        PanelId one = new PanelId("1");
        PanelId two = new PanelId("2");
        try (Store store = new Store(home)) {
            store.load();
            store.savePanels(List.of(new PanelState(one, "Draft", "", ""), new PanelState(two, "draft", "", "")));
            store.changed(one, "first");
            store.changed(two, "second");
            store.saveNow().get(5, TimeUnit.SECONDS);
            // case-insensitive, as APFS is by default
            assertThat(names()).containsExactly("Draft.md", "draft 2.md");

            store.savePanels(List.of(new PanelState(one, "spec: a/b", "", ""), new PanelState(two, "draft", "", "")));
            store.saveNow().get(5, TimeUnit.SECONDS);
            assertThat(names()).containsExactly("draft 2.md", "spec- a-b.md");
            assertThat(Files.readString(dir().resolve("spec- a-b.md"))).isEqualTo("first");
        }
    }

    @Test
    void aNewPanelNeverTakesTheFileOfAClosedOne() throws Exception {
        Files.createDirectories(dir());
        Files.writeString(dir().resolve("spec.md"), "kept from an earlier panel");
        PanelId id = new PanelId("n");
        try (Store store = new Store(home)) {
            store.load();
            store.savePanels(List.of(new PanelState(id, "spec", "", "")));
            store.changed(id, "new");
            store.saveNow().get(5, TimeUnit.SECONDS);
        }
        assertThat(Files.readString(dir().resolve("spec.md"))).isEqualTo("kept from an earlier panel");
        assertThat(Files.readString(dir().resolve("spec 2.md"))).isEqualTo("new");
    }

    @Test
    void anEditMadeOutsideComandoIsKeptAsideAndTheUserIsTold() throws Exception {
        PanelId id = new PanelId("x");
        List<String> notices = new CopyOnWriteArrayList<>();
        try (Store store = new Store(home)) {
            store.onNotice(notices::add);
            store.load();
            store.savePanels(List.of(new PanelState(id, "spec", "", "")));
            store.changed(id, "comando's text");
            store.saveNow().get(5, TimeUnit.SECONDS);
            Path f = dir().resolve("spec.md");
            Files.writeString(f, "edited in the IDE");
            Files.setLastModifiedTime(f, FileTime.fromMillis(Files.getLastModifiedTime(f).toMillis() + 2_000));

            store.changed(id, "comando's next text");
            store.saveNow().get(5, TimeUnit.SECONDS);
        }
        assertThat(Files.readString(dir().resolve("spec.md"))).isEqualTo("comando's next text");
        assertThat(names()).hasSize(2).anyMatch(n -> n.startsWith("spec (outside comando ") && n.endsWith(".md"));
        Path aside = dir().resolve(names().stream().filter(n -> n.startsWith("spec (outside")).findFirst().orElseThrow());
        assertThat(Files.readString(aside)).isEqualTo("edited in the IDE");
        assertThat(notices).singleElement().asString().startsWith("spec.md changed outside comando");
    }

    @Test
    void aClosedPanelKeepsItsFileWithItsLastTextAndABlankOneIsDeleted() throws Exception {
        PanelId full = new PanelId("f");
        PanelId blank = new PanelId("b");
        try (Store store = new Store(home)) {
            store.load();
            store.savePanels(List.of(new PanelState(full, "full", "", ""), new PanelState(blank, "blank", "", "")));
            store.changed(full, "saved");
            store.changed(blank, "x");
            store.saveNow().get(5, TimeUnit.SECONDS);

            store.changed(full, "typed just before closing");
            store.changed(blank, "  \n");
            store.forget(full);
            store.forget(blank);
            store.savePanels(List.of());
            store.saveNow().get(5, TimeUnit.SECONDS);
            assertThat(store.file(full)).as("known after closing, for ⌘⇧T").contains(dir().resolve("full.md"));
        }
        assertThat(names()).containsExactly("full.md");
        assertThat(Files.readString(dir().resolve("full.md"))).isEqualTo("typed just before closing");
    }

    @Test
    void aSpecOpensFromTheFolderOnlyAndKeepsItsName() throws Exception {
        Files.createDirectories(dir());
        Files.writeString(dir().resolve("Ravenna plan.md"), "# Ravenna");
        Path elsewhere = Files.writeString(home.resolve("other.md"), "no");
        PanelId id = new PanelId("o");
        try (Store store = new Store(home)) {
            store.load();
            assertThat(store.open(new PanelId("e"), elsewhere)).startsWith("Specs live in");
            assertThat(store.open(id, dir().resolve("Ravenna plan.md"))).isEmpty();
            assertThat(store.load(id)).isEqualTo("# Ravenna");
            store.savePanels(List.of(new PanelState(id, PanelFiles.title(dir().resolve("Ravenna plan.md")), "", "")));
            store.changed(id, "# Ravenna\n\nmore");
            store.saveNow().get(5, TimeUnit.SECONDS);
        }
        assertThat(names()).containsExactly("Ravenna plan.md");
        assertThat(Files.readString(dir().resolve("Ravenna plan.md"))).isEqualTo("# Ravenna\n\nmore");
    }

    /** Writes a file as another editor would, with a modification time comando has not seen. */
    private static void writeOutside(Path f, String text) throws Exception {
        Files.writeString(f, text);
        Files.setLastModifiedTime(f, FileTime.fromMillis(Files.getLastModifiedTime(f).toMillis() + 2_000));
    }

    @Test
    void anOutsideEditIsReportedOnceAndTakingItMakesItThePanelsText() throws Exception {
        PanelId id = new PanelId("w");
        List<PanelTexts.Outside> outside = new CopyOnWriteArrayList<>();
        try (Store store = new Store(home)) {
            store.onOutsideChange(outside::add);
            store.load();
            store.savePanels(List.of(new PanelState(id, "spec", "", "")));
            store.changed(id, "v1");
            store.saveNow().get(5, TimeUnit.SECONDS);
            store.checkFilesNow().get(5, TimeUnit.SECONDS);
            assertThat(outside).as("comando's own write is no outside change").isEmpty();

            writeOutside(dir().resolve("spec.md"), "v2 from Claude's Edit tool");
            store.checkFilesNow().get(5, TimeUnit.SECONDS);
            store.checkFilesNow().get(5, TimeUnit.SECONDS);
            assertThat(outside).singleElement().satisfies(o -> {
                assertThat(o.panel()).isEqualTo(id);
                assertThat(o.file()).isEqualTo("spec.md");
                assertThat(o.text()).isEqualTo("v2 from Claude's Edit tool");
            });
            assertThat(outside.getFirst().take().getAsBoolean()).isTrue();
            assertThat(store.load(id)).isEqualTo("v2 from Claude's Edit tool");

            store.changed(id, "v3");
            store.saveNow().get(5, TimeUnit.SECONDS);
        }
        assertThat(names()).as("a taken outside version is not kept aside").containsExactly("spec.md");
        assertThat(Files.readString(dir().resolve("spec.md"))).isEqualTo("v3");
    }

    @Test
    void anOutsideEditMeetingUnsavedEditsIsNotTakenAndIsKeptAside() throws Exception {
        PanelId id = new PanelId("u");
        List<PanelTexts.Outside> outside = new CopyOnWriteArrayList<>();
        try (Store store = new Store(home)) {
            store.onOutsideChange(outside::add);
            store.load();
            store.savePanels(List.of(new PanelState(id, "spec", "", "")));
            store.changed(id, "v1");
            store.saveNow().get(5, TimeUnit.SECONDS);

            writeOutside(dir().resolve("spec.md"), "outside");
            store.checkFilesNow().get(5, TimeUnit.SECONDS);
            store.changed(id, "typed after the report");
            assertThat(outside.getFirst().take().getAsBoolean()).as("the typing wins").isFalse();
            store.saveNow().get(5, TimeUnit.SECONDS);
        }
        assertThat(Files.readString(dir().resolve("spec.md"))).isEqualTo("typed after the report");
        assertThat(names()).hasSize(2).anyMatch(n -> n.startsWith("spec (outside comando "));
    }

    @Test
    void aFileDeletedOutsideIsReportedOnceAndWrittenAgainOnTheNextSave() throws Exception {
        PanelId id = new PanelId("d");
        List<String> notices = new CopyOnWriteArrayList<>();
        try (Store store = new Store(home)) {
            store.onNotice(notices::add);
            store.load();
            store.savePanels(List.of(new PanelState(id, "spec", "", "")));
            store.changed(id, "kept");
            store.saveNow().get(5, TimeUnit.SECONDS);

            Files.delete(dir().resolve("spec.md"));
            store.checkFilesNow().get(5, TimeUnit.SECONDS);
            store.checkFilesNow().get(5, TimeUnit.SECONDS);
            assertThat(notices).singleElement().asString().startsWith("spec.md was deleted outside comando");
            assertThat(store.load(id)).isEqualTo("kept");

            store.changed(id, "kept");
            store.saveNow().get(5, TimeUnit.SECONDS);
        }
        assertThat(Files.readString(dir().resolve("spec.md"))).isEqualTo("kept");
    }

    @Test
    void titlesBecomeSafeFileNames() {
        assertThat(PanelFiles.safe("a/b\\c:d")).isEqualTo("a-b-c-d");
        assertThat(PanelFiles.safe("  ..hidden  ")).isEqualTo("hidden");
        assertThat(PanelFiles.safe("tab\there")).isEqualTo("tab-here");
        assertThat(PanelFiles.safe("   ")).isEqualTo("panel");
        assertThat(PanelFiles.safe("x".repeat(200))).hasSize(PanelFiles.NAME_CHARS);
    }
}
