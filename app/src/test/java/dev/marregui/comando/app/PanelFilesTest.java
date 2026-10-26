package dev.marregui.comando.app;

import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.ui.PanelTexts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
            store.savePanels(List.of(new PanelState(spec, "nevsky spec", "", ""), new PanelState(notes, "notes", "", "")), List.of());
            store.changed(spec, "# Nevsky\n\nSELECT 1;\n");
            store.changed(notes, "todo");
            assertThat(store.saveNow().get(5, TimeUnit.SECONDS)).isNull();
            assertThat(store.document(spec)).as("a panel's own file, not a document opened in it").isFalse();
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
            store.savePanels(List.of(new PanelState(one, "Draft", "", ""), new PanelState(two, "draft", "", "")), List.of());
            store.changed(one, "first");
            store.changed(two, "second");
            store.saveNow().get(5, TimeUnit.SECONDS);
            // case-insensitive, as APFS is by default
            assertThat(names()).containsExactly("Draft.md", "draft 2.md");

            store.savePanels(List.of(new PanelState(one, "spec: a/b", "", ""), new PanelState(two, "draft", "", "")), List.of());
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
            store.savePanels(List.of(new PanelState(id, "spec", "", "")), List.of());
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
            store.savePanels(List.of(new PanelState(id, "spec", "", "")), List.of());
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
    void aClosedPanelKeepsItsFileWithItsLastTextBlankOrNotAndComesBackClosed() throws Exception {
        PanelId full = new PanelId("f");
        PanelId blank = new PanelId("b");
        try (Store store = new Store(home)) {
            store.load();
            store.savePanels(List.of(new PanelState(full, "full", "", ""), new PanelState(blank, "blank", "", "")), List.of());
            store.changed(full, "saved");
            store.changed(blank, "x");
            store.saveNow().get(5, TimeUnit.SECONDS);

            store.changed(full, "typed just before closing");
            store.changed(blank, "  \n");
            // the reducer's order on close: forget, then the lists with the panel among the closed ones
            store.forget(full);
            store.forget(blank);
            store.savePanels(List.of(), List.of(new PanelState(full, "full", "", ""), new PanelState(blank, "blank", "", "")));
            store.saveNow().get(5, TimeUnit.SECONDS);
            assertThat(store.file(full)).as("known after closing, for reopening").contains(dir().resolve("full.md"));
            assertThat(store.load(full)).as("kept in memory while closed").isEqualTo("typed just before closing");
        }
        assertThat(names()).as("nothing is deleted on close").containsExactly("blank.md", "full.md");
        assertThat(Files.readString(dir().resolve("full.md"))).isEqualTo("typed just before closing");
        try (Store store = new Store(home)) {
            Store.Loaded loaded = store.load();
            assertThat(loaded.closed()).extracting(PanelState::title).containsExactly("full", "blank");
            assertThat(store.load(full)).isEqualTo("typed just before closing");
        }
        assertThat(Files.readString(home.resolve("panels.json"))).contains("\"closed\" : true");
    }

    @Test
    void aFileOfThePanelsFolderWithoutAPanelBecomesAClosedPanel() throws Exception {
        Files.createDirectories(dir());
        Files.writeString(dir().resolve("main.md"), "mine");
        Files.writeString(dir().resolve("reviews.md"), "left by a closed panel");
        Files.writeString(dir().resolve(".main.md123.tmp"), "half written");
        Files.writeString(home.resolve("connections.json"), "[]");
        Files.writeString(home.resolve("sessions.json"), "[]");
        Files.writeString(home.resolve("panels.json"), """
                [{"id":"m","title":"main","connection":"","session":"","file":"main.md"}]""");
        List<String> notices = new CopyOnWriteArrayList<>();
        try (Store store = new Store(home)) {
            store.onNotice(notices::add);
            Store.Loaded loaded = store.load();
            assertThat(loaded.panels()).extracting(PanelState::title).containsExactly("main");
            assertThat(loaded.closed()).singleElement().satisfies(p -> {
                assertThat(p.title()).isEqualTo("reviews");
                assertThat(store.load(p.id())).isEqualTo("left by a closed panel");
            });
            store.saveNow().get(5, TimeUnit.SECONDS);
        }
        assertThat(notices).containsExactly("reviews.md has no panel; listed as closed under the sessions, without a session");
        assertThat(Files.readString(home.resolve("panels.json"))).contains("\"file\" : \"reviews.md\"").contains("\"closed\" : true");
    }

    @Test
    void everyFileOfThePanelsFolderBelongsToAPanelWhenAStoredNameDiffersInCase() throws Exception {
        Files.createDirectories(dir());
        Files.writeString(dir().resolve("SPEC.md"), "typed");
        Files.writeString(home.resolve("connections.json"), "[]");
        Files.writeString(home.resolve("sessions.json"), "[]");
        Files.writeString(home.resolve("panels.json"), """
                [{"id":"s","title":"spec","connection":"","session":"","file":"spec.md"}]""");
        try (Store store = new Store(home)) {
            Store.Loaded loaded = store.load();
            store.saveNow().get(5, TimeUnit.SECONDS);
            List<PanelState> all = new ArrayList<>(loaded.panels());
            all.addAll(loaded.closed());
            for (String name : names()) {
                Path f = dir().resolve(name);
                assertThat(all).as(name + " has a panel").anyMatch(p -> store.file(p.id()).map(x -> same(x, f)).orElse(false));
            }
            assertThat(all).as("the typed text is reachable").anyMatch(p -> store.load(p.id()).equals("typed"));
        }
    }

    private static boolean same(Path a, Path b) {
        try {
            return Files.exists(a) && Files.isSameFile(a, b);
        } catch (IOException e) {
            return false;
        }
    }

    @Test
    void aPanelsFolderWithoutTheJsonFilesIsAdoptedAsClosedPanels() throws Exception {
        Files.createDirectories(dir());
        Files.writeString(dir().resolve("notes.md"), "kept");
        try (Store store = new Store(home)) {
            Store.Loaded loaded = store.load();
            assertThat(loaded.closed()).extracting(PanelState::title).containsExactly("notes");
        }
    }

    @Test
    void aDocumentWhoseLastWriteFailsAtCloseComesBackOnItsOwnPathNeverAsAPanelFile() throws Exception {
        Path docs = Files.createDirectories(home.resolve("docs"));
        Path doc = Files.writeString(docs.resolve("PLAN.md"), "# plan");
        PanelId id = new PanelId("d");
        PanelState own = new PanelState(new PanelId("o"), "spec", "", "");
        Set<PosixFilePermission> writable = Files.getPosixFilePermissions(docs);
        try {
            try (Store store = new Store(home)) {
                store.load();
                assertThat(store.open(id, doc).get(5, TimeUnit.SECONDS)).isEqualTo(PanelTexts.Opened.BOUND);
                store.savePanels(List.of(own, new PanelState(id, "PLAN", "", "", true)), List.of());
                store.saveNow().get(5, TimeUnit.SECONDS);
                Files.setPosixFilePermissions(docs, PosixFilePermissions.fromString("r-xr-xr-x"));
                store.changed(id, "typed, then closed while docs/ refuses writes");
                store.forget(id);
                store.savePanels(List.of(own), List.of());
                store.saveNow().get(5, TimeUnit.SECONDS);
            }
        } finally {
            Files.setPosixFilePermissions(docs, writable);
        }
        try (Store store = new Store(home)) {
            Store.Loaded loaded = store.load();
            store.saveNow().get(5, TimeUnit.SECONDS);
            PanelState back = loaded.panels().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
            assertThat(back.document()).isTrue();
            assertThat(store.file(id)).contains(doc);
            assertThat(store.load(id)).isEqualTo("typed, then closed while docs/ refuses writes");
        }
        assertThat(dir().resolve("PLAN.md")).as("never a copy in panels/").doesNotExist();
    }

    @Test
    void aDocumentUnreadableAtTheStartKeepsItsPathAndNeverBecomesAPanelFile() throws Exception {
        Path docs = Files.createDirectories(home.resolve("docs"));
        Path doc = Files.writeString(docs.resolve("PLAN.md"), "# plan");
        Files.writeString(home.resolve("connections.json"), "[]");
        Files.writeString(home.resolve("sessions.json"), "[]");
        Files.writeString(home.resolve("panels.json"), """
                [{"id":"d","title":"PLAN","connection":"","session":"","file":"%s","content":"typed, never written"}]""".formatted(doc));
        Set<PosixFilePermission> readable = Files.getPosixFilePermissions(doc);
        Files.setPosixFilePermissions(doc, PosixFilePermissions.fromString("---------"));
        try (Store store = new Store(home)) {
            store.load();
            store.saveNow().get(5, TimeUnit.SECONDS);
            assertThat(store.file(new PanelId("d"))).contains(doc);
        } finally {
            Files.setPosixFilePermissions(doc, readable);
        }
        assertThat(dir().resolve("PLAN.md")).as("never a copy in panels/").doesNotExist();
        assertThat(Files.readString(home.resolve("panels.json"))).contains(doc.toString());
    }

    @Test
    void anEditAfterAnOutsideChangeReportIsNotReplacedEvenOnceItIsSaved() throws Exception {
        PanelId id = new PanelId("w");
        List<PanelTexts.Outside> outside = new CopyOnWriteArrayList<>();
        try (Store store = new Store(home)) {
            store.onOutsideChange(outside::add);
            store.load();
            store.savePanels(List.of(new PanelState(id, "spec", "", "")), List.of());
            store.changed(id, "v1");
            store.saveNow().get(5, TimeUnit.SECONDS);
            writeOutside(dir().resolve("spec.md"), "from the IDE");
            store.checkFilesNow().get(5, TimeUnit.SECONDS);
            // the user types and the autosave lands before the report reaches the EDT
            store.changed(id, "typed after the report");
            store.saveNow().get(5, TimeUnit.SECONDS);
            assertThat(outside.getFirst().take().getAsBoolean()).as("the typing wins").isFalse();
            assertThat(store.load(id)).isEqualTo("typed after the report");
        }
    }

    @Test
    void anEditAfterADeletionReportKeepsThePanelEvenOnceItIsSaved() throws Exception {
        PanelId id = new PanelId("e");
        List<PanelTexts.Deleted> deleted = new CopyOnWriteArrayList<>();
        try (Store store = new Store(home)) {
            store.onDeleted(deleted::add);
            store.load();
            store.savePanels(List.of(new PanelState(id, "spec", "", "")), List.of());
            store.changed(id, "v1");
            store.saveNow().get(5, TimeUnit.SECONDS);
            Files.delete(dir().resolve("spec.md"));
            store.checkFilesNow().get(5, TimeUnit.SECONDS);
            // the user types and the autosave lands before the report reaches the EDT
            store.changed(id, "typed after the report");
            store.saveNow().get(5, TimeUnit.SECONDS);
            assertThat(deleted).singleElement().matches(d -> !d.remove().getAsBoolean(), "the panel stays");
        }
        assertThat(Files.readString(dir().resolve("spec.md"))).isEqualTo("typed after the report");
    }

    @Test
    void aNewPanelGetsItsFileAtOnceEvenEmptyAndARemovedOneLosesIt() throws Exception {
        PanelId id = new PanelId("e");
        try (Store store = new Store(home)) {
            store.load();
            store.savePanels(List.of(new PanelState(id, "empty", "", "")), List.of());
            store.saveNow().get(5, TimeUnit.SECONDS);
            assertThat(Files.readString(dir().resolve("empty.md"))).isEmpty();

            store.delete(id);
            // a save that runs before the list without the panel arrives does not bring the file back
            store.saveNow().get(5, TimeUnit.SECONDS);
            assertThat(dir().resolve("empty.md")).doesNotExist();
            store.savePanels(List.of(), List.of());
            store.saveNow().get(5, TimeUnit.SECONDS);
        }
        assertThat(dir().resolve("empty.md")).doesNotExist();
        assertThat(Files.readString(home.resolve("panels.json"))).doesNotContain("empty");
    }

    @Test
    void aStoredNameReachingOutOfTheFolderIsADocumentAndIsNeverDeleted() throws Exception {
        Path outside = Files.writeString(home.resolve("victim.md"), "keep");
        PanelFiles files = new PanelFiles(dir(), notice -> { });
        assertThat(files.read("x", "victim", "../victim.md")).isEqualTo("keep");
        assertThat(files.document("x")).isTrue();
        files.remove("x");
        assertThat(Files.readString(outside)).isEqualTo("keep");
    }

    @Test
    void removingADocumentsPanelNeverDeletesTheDocument() throws Exception {
        Path doc = Files.writeString(home.resolve("PLAN.md"), "# plan");
        PanelId id = new PanelId("d");
        try (Store store = new Store(home)) {
            store.load();
            assertThat(store.open(id, doc).get(5, TimeUnit.SECONDS)).isEqualTo(PanelTexts.Opened.BOUND);
            store.savePanels(List.of(new PanelState(id, "PLAN", "", "", true)), List.of());
            store.saveNow().get(5, TimeUnit.SECONDS);
            store.delete(id);
            store.saveNow().get(5, TimeUnit.SECONDS);
        }
        assertThat(Files.readString(doc)).isEqualTo("# plan");
    }

    @Test
    void atTheStartAClosedPanelWhoseFileIsGoneIsDroppedAndAnOpenOneGetsAnEmptyFile() throws Exception {
        Files.writeString(home.resolve("connections.json"), "[]");
        Files.writeString(home.resolve("sessions.json"), "[]");
        Files.writeString(home.resolve("panels.json"), """
                [{"id":"o","title":"open","connection":"","session":"","file":"open.md"},
                 {"id":"c","title":"closed","connection":"","session":"","file":"closed.md","closed":true}]""");
        List<String> notices = new CopyOnWriteArrayList<>();
        try (Store store = new Store(home)) {
            store.onNotice(notices::add);
            Store.Loaded loaded = store.load();
            assertThat(loaded.panels()).extracting(PanelState::title).containsExactly("open");
            assertThat(loaded.closed()).isEmpty();
            store.saveNow().get(5, TimeUnit.SECONDS);
        }
        assertThat(notices).containsExactly("closed.md was deleted while comando was closed; its panel is gone");
        assertThat(Files.readString(dir().resolve("open.md"))).as("a blank panel of an older comando gets its file").isEmpty();
    }

    @Test
    void aSpecOpensFromTheFolderOnlyAndKeepsItsName() throws Exception {
        Path elsewhere = Files.writeString(home.resolve("other.md"), "no");
        PanelId id = new PanelId("o");
        try (Store store = new Store(home)) {
            store.load();
            // put in the folder while comando runs: loose until the next start, so ⌘O binds it
            Files.writeString(Files.createDirectories(dir()).resolve("Ravenna plan.md"), "# Ravenna");
            assertThat(store.open(new PanelId("g"), dir().resolve("gone.md")).get(5, TimeUnit.SECONDS)).isEqualTo(PanelTexts.Opened.refused("gone.md no longer exists"));
            assertThat(store.open(id, dir().resolve("Ravenna plan.md")).get(5, TimeUnit.SECONDS)).isEqualTo(PanelTexts.Opened.BOUND);
            assertThat(store.load(id)).isEqualTo("# Ravenna");
            store.savePanels(List.of(new PanelState(id, PanelFiles.title(dir().resolve("Ravenna plan.md")), "", "")), List.of());
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
            store.savePanels(List.of(new PanelState(id, "spec", "", "")), List.of());
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
            store.savePanels(List.of(new PanelState(id, "spec", "", "")), List.of());
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
    void aPanelsFileDeletedOutsideIsReportedForThePanelToBeRemovedUnlessEditedSince() throws Exception {
        PanelId open = new PanelId("d");
        PanelId shut = new PanelId("s");
        List<PanelTexts.Deleted> deleted = new CopyOnWriteArrayList<>();
        try (Store store = new Store(home)) {
            store.onDeleted(deleted::add);
            store.load();
            store.savePanels(List.of(new PanelState(open, "spec", "", "")), List.of(new PanelState(shut, "old", "", "")));
            store.changed(open, "kept");
            store.changed(shut, "old text");
            store.saveNow().get(5, TimeUnit.SECONDS);

            Files.delete(dir().resolve("spec.md"));
            Files.delete(dir().resolve("old.md"));
            store.checkFilesNow().get(5, TimeUnit.SECONDS);
            store.checkFilesNow().get(5, TimeUnit.SECONDS);
            assertThat(deleted).extracting(d -> d.panel().value() + " " + d.file()).containsExactlyInAnyOrder("d spec.md", "s old.md");
            assertThat(deleted).as("no edits since: the panels go").allMatch(d -> d.remove().getAsBoolean());
            store.saveNow().get(5, TimeUnit.SECONDS);
            assertThat(dir().resolve("old.md")).as("not written again while its panel is being removed").doesNotExist();
            store.changed(open, "typed after the report");
            assertThat(deleted.getFirst().panel().equals(open) ? deleted.getFirst() : deleted.getLast())
                    .as("edits typed since keep the panel").matches(d -> !d.remove().getAsBoolean());
            store.saveNow().get(5, TimeUnit.SECONDS);
            assertThat(Files.readString(dir().resolve("spec.md"))).as("their save writes the file again").isEqualTo("typed after the report");
        }
    }

    @Test
    void aClosedPanelTakesAnOutsideEditAtOnce() throws Exception {
        PanelId id = new PanelId("c");
        List<PanelTexts.Outside> outside = new CopyOnWriteArrayList<>();
        try (Store store = new Store(home)) {
            store.onOutsideChange(outside::add);
            store.load();
            store.savePanels(List.of(), List.of(new PanelState(id, "spec", "", "")));
            store.changed(id, "v1");
            store.saveNow().get(5, TimeUnit.SECONDS);
            writeOutside(dir().resolve("spec.md"), "v2 from the IDE");
            store.checkFilesNow().get(5, TimeUnit.SECONDS);
            assertThat(outside).as("no editor shows it to ask").isEmpty();
            assertThat(store.load(id)).isEqualTo("v2 from the IDE");
            store.saveNow().get(5, TimeUnit.SECONDS);
        }
        assertThat(names()).as("taken, not kept aside").containsExactly("spec.md");
    }

    @Test
    void aDocumentDeletedOutsideIsReportedForItsPanelToCloseAndNotWrittenAgain() throws Exception {
        Path day = Files.createDirectories(home.resolve("SCRATCHPAD").resolve("2026-10-14"));
        Path doc = Files.writeString(day.resolve("ALPHA.md"), "# day");
        PanelId id = new PanelId("x");
        PanelId own = new PanelId("o");
        List<String> notices = new CopyOnWriteArrayList<>();
        List<PanelTexts.Deleted> deleted = new CopyOnWriteArrayList<>();
        try (Store store = new Store(home)) {
            store.onNotice(notices::add);
            store.onDeleted(deleted::add);
            store.load();
            assertThat(store.open(id, doc).get(5, TimeUnit.SECONDS)).isEqualTo(PanelTexts.Opened.BOUND);
            store.savePanels(List.of(new PanelState(id, "ALPHA", "", ""), new PanelState(own, "spec", "", "")), List.of());
            store.changed(own, "mine");
            store.saveNow().get(5, TimeUnit.SECONDS);

            Files.delete(doc);
            Files.delete(day);
            store.checkFilesNow().get(5, TimeUnit.SECONDS);
            store.checkFilesNow().get(5, TimeUnit.SECONDS);
            assertThat(deleted).extracting(d -> d.panel().value() + " " + d.file()).containsExactly("x ALPHA.md");
            assertThat(notices).as("no warning: the panel closes instead").isEmpty();

            store.forget(id);
            store.savePanels(List.of(new PanelState(own, "spec", "", "")), List.of());
            store.saveNow().get(5, TimeUnit.SECONDS);
        }
        assertThat(day).as("closing the document's panel does not bring its folder back").doesNotExist();
    }

    @Test
    void aDocumentDeletedWhileComandoWasClosedLeavesNoPanelUnlessItHasUnsavedText() throws Exception {
        Path day = Files.createDirectories(home.resolve("SCRATCHPAD").resolve("2026-10-14"));
        Path gone = day.resolve("READ-01-X.md");
        Path alsoGone = day.resolve("ALPHA.md");
        Files.writeString(home.resolve("connections.json"), "[]");
        Files.writeString(home.resolve("sessions.json"), "[]");
        Files.writeString(home.resolve("panels.json"), """
                [{"id":"g","title":"READ-01-X","connection":"","session":"","file":"%s"},
                 {"id":"u","title":"ALPHA","connection":"","session":"","file":"%s","content":"typed, never saved"},
                 {"id":"p","title":"spec","connection":"","session":"","file":"spec.md"}]""".formatted(gone, alsoGone));
        List<String> notices = new CopyOnWriteArrayList<>();
        try (Store store = new Store(home)) {
            store.onNotice(notices::add);
            Store.Loaded loaded = store.load();
            assertThat(loaded.panels()).extracting(p -> p.id().value()).containsExactly("u", "p");
            assertThat(store.load(new PanelId("u"))).isEqualTo("typed, never saved");
        }
        assertThat(notices).containsExactly("READ-01-X.md was deleted while comando was closed; its panel is gone");
    }

    @Test
    void aNewPanelClosedBeforeItsFirstSaveStillReachesAFile() throws Exception {
        PanelId kept = new PanelId("k");
        PanelId quick = new PanelId("q");
        try (Store store = new Store(home)) {
            store.load();
            store.savePanels(List.of(new PanelState(kept, "kept", "", "")), List.of());
            store.changed(kept, "x");
            store.saveNow().get(5, TimeUnit.SECONDS);

            // the reducer's order on close: the panel is still listed when forget runs, then it is among the closed
            store.savePanels(List.of(new PanelState(kept, "kept", "", ""), new PanelState(quick, "quick", "", "")), List.of());
            store.changed(quick, "typed and closed within 800 ms");
            store.forget(quick);
            store.savePanels(List.of(new PanelState(kept, "kept", "", "")), List.of(new PanelState(quick, "quick", "", "")));
            store.saveNow().get(5, TimeUnit.SECONDS);
        }
        assertThat(Files.readString(dir().resolve("quick.md"))).isEqualTo("typed and closed within 800 ms");
    }

    @Test
    void aPanelFileThatCannotBeWrittenKeepsItsTextAcrossARestartAndTheUserIsTold() throws Exception {
        PanelId id = new PanelId("n");
        List<String> notices = new CopyOnWriteArrayList<>();
        Files.createDirectories(dir());
        Set<PosixFilePermission> writable = Files.getPosixFilePermissions(dir());
        try {
            try (Store store = new Store(home)) {
                store.onNotice(notices::add);
                store.load();
                store.savePanels(List.of(new PanelState(id, "spec", "", "")), List.of());
                Files.setPosixFilePermissions(dir(), PosixFilePermissions.fromString("r-xr-xr-x"));
                store.changed(id, "typed while panels/ refuses writes");
                assertThat(store.saveNow().get(5, TimeUnit.SECONDS)).as("⌘S says it failed").isNotNull();
            }
            assertThat(notices).as("the failure is not only in the log").anyMatch(n -> n.startsWith("Cannot write spec.md"));
        } finally {
            Files.setPosixFilePermissions(dir(), writable);
        }
        try (Store store = new Store(home)) {
            store.load();
            assertThat(store.load(id)).isEqualTo("typed while panels/ refuses writes");
        }
    }

    @Test
    void aReopenedPanelHasItsLastTextAndItsFileIsNeverBoundToAnotherPanel() throws Exception {
        PanelId closing = new PanelId("c");
        PanelState spec = new PanelState(closing, "spec", "", "");
        try (Store store = new Store(home)) {
            store.load();
            store.savePanels(List.of(spec), List.of());
            store.changed(closing, "v1");
            store.saveNow().get(5, TimeUnit.SECONDS);

            store.changed(closing, "v2 typed just before closing");
            store.forget(closing);
            store.savePanels(List.of(), List.of(spec));
            assertThat(store.open(new PanelId("r"), dir().resolve("spec.md")).get(5, TimeUnit.SECONDS)).isEqualTo(PanelTexts.Opened.alreadyOpen("spec.md"));
            store.savePanels(List.of(spec), List.of());
            assertThat(store.load(closing)).isEqualTo("v2 typed just before closing");
            store.saveNow().get(5, TimeUnit.SECONDS);
        }
        assertThat(Files.readString(dir().resolve("spec.md"))).isEqualTo("v2 typed just before closing");
    }

    @Test
    void aClosedPanelWhoseFileCannotBeWrittenKeepsItsTextAcrossARestart() throws Exception {
        PanelId kept = new PanelId("k");
        PanelId closing = new PanelId("c");
        Files.createDirectories(dir());
        Set<PosixFilePermission> writable = Files.getPosixFilePermissions(dir());
        try {
            try (Store store = new Store(home)) {
                store.load();
                store.savePanels(List.of(new PanelState(kept, "kept", "", ""), new PanelState(closing, "closing", "", "")), List.of());
                store.changed(closing, "v1");
                store.saveNow().get(5, TimeUnit.SECONDS);
                Files.setPosixFilePermissions(dir(), PosixFilePermissions.fromString("r-xr-xr-x"));
                store.changed(closing, "typed, then closed while panels/ refuses writes");
                store.forget(closing);
                store.savePanels(List.of(new PanelState(kept, "kept", "", "")), List.of(new PanelState(closing, "closing", "", "")));
                store.saveNow().get(5, TimeUnit.SECONDS);
            }
        } finally {
            Files.setPosixFilePermissions(dir(), writable);
        }
        try (Store store = new Store(home)) {
            Store.Loaded loaded = store.load();
            assertThat(loaded.panels()).extracting(PanelState::title).containsExactly("kept");
            assertThat(loaded.closed()).extracting(PanelState::title).containsExactly("closing");
            assertThat(store.load(closing)).isEqualTo("typed, then closed while panels/ refuses writes");
        }
    }

    @Test
    void aFileOpenInOnePanelIsNotBoundToASecond() throws Exception {
        try (Store store = new Store(home)) {
            store.load();
            // put in the folder while comando runs: loose until the next start, so ⌘O binds it
            Path spec = Files.writeString(Files.createDirectories(dir()).resolve("spec.md"), "# spec");
            assertThat(store.open(new PanelId("a"), spec).get(5, TimeUnit.SECONDS)).isEqualTo(PanelTexts.Opened.BOUND);
            assertThat(store.open(new PanelId("b"), spec).get(5, TimeUnit.SECONDS)).isEqualTo(PanelTexts.Opened.alreadyOpen("spec.md"));
        }
    }

    @Test
    void aNewPanelNeverTakesTheNameOfAClosedPanelStillWaitingToBeWritten() throws Exception {
        PanelId closed = new PanelId("c");
        PanelId fresh = new PanelId("f");
        Files.createDirectories(dir());
        Set<PosixFilePermission> writable = Files.getPosixFilePermissions(dir());
        try (Store store = new Store(home)) {
            store.load();
            store.savePanels(List.of(new PanelState(closed, "spec", "", "")), List.of());
            Files.setPosixFilePermissions(dir(), PosixFilePermissions.fromString("r-xr-xr-x"));
            try {
                store.changed(closed, "the closed panel's text");
                store.forget(closed);
                store.savePanels(List.of(), List.of(new PanelState(closed, "spec", "", "")));
                store.saveNow().get(5, TimeUnit.SECONDS);
            } finally {
                Files.setPosixFilePermissions(dir(), writable);
            }
            store.savePanels(List.of(new PanelState(fresh, "spec", "", "")), List.of(new PanelState(closed, "spec", "", "")));
            store.changed(fresh, "the new panel's text");
            store.saveNow().get(5, TimeUnit.SECONDS);
            store.saveNow().get(5, TimeUnit.SECONDS);
        }
        assertThat(names()).containsExactly("spec 2.md", "spec.md");
        assertThat(Files.readString(dir().resolve("spec.md"))).isEqualTo("the closed panel's text");
        assertThat(Files.readString(dir().resolve("spec 2.md"))).isEqualTo("the new panel's text");
    }

    @Test
    void closingDropsAPendingRetryAndWritesNothingAfterItReturns() throws Exception {
        PanelId id = new PanelId("q");
        Files.createDirectories(dir());
        Set<PosixFilePermission> writable = Files.getPosixFilePermissions(dir());
        Store store = new Store(home);
        long took;
        try {
            store.load();
            store.savePanels(List.of(new PanelState(id, "spec", "", "")), List.of());
            Files.setPosixFilePermissions(dir(), PosixFilePermissions.fromString("r-xr-xr-x"));
            try {
                store.changed(id, "v1");
                store.saveNow().get(5, TimeUnit.SECONDS);
            } finally {
                Files.setPosixFilePermissions(dir(), writable);
            }
            // a retry is now due in Store.RETRY_MILLIS; the close writes at once and must not wait for it
        } finally {
            long start = System.nanoTime();
            store.close();
            took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        }
        assertThat(Files.readString(dir().resolve("spec.md"))).as("the close's own write").isEqualTo("v1");
        assertThat(took).as("close waited for the retry instead of dropping it").isLessThan(Store.RETRY_MILLIS / 2);
        assertThat(store.writerTerminated()).as("no write left to land after close").isTrue();
    }

    @Test
    void aPanelWhoseFileCannotBeNamedAtCloseKeepsItsTextAcrossARestart() throws Exception {
        PanelId id = new PanelId("r");
        Files.createDirectories(dir());
        Set<PosixFilePermission> writable = Files.getPosixFilePermissions(dir());
        try {
            try (Store store = new Store(home)) {
                store.load();
                store.savePanels(List.of(new PanelState(id, "a", "", "")), List.of());
                store.changed(id, "v1");
                store.saveNow().get(5, TimeUnit.SECONDS);
                Files.setPosixFilePermissions(dir(), PosixFilePermissions.fromString("r-xr-xr-x"));
                // renamed and closed inside one save interval: the close must move a.md to b.md, which fails
                store.savePanels(List.of(new PanelState(id, "b", "", "")), List.of());
                store.changed(id, "v2, renamed and closed");
                store.forget(id);
                store.savePanels(List.of(), List.of(new PanelState(id, "b", "", "")));
                store.saveNow().get(5, TimeUnit.SECONDS);
            }
        } finally {
            Files.setPosixFilePermissions(dir(), writable);
        }
        try (Store store = new Store(home)) {
            Store.Loaded loaded = store.load();
            assertThat(loaded.closed()).extracting(PanelState::title).containsExactly("b");
            assertThat(store.load(id)).isEqualTo("v2, renamed and closed");
        }
    }

    @Test
    void aPanelsJsonThatCannotBeWrittenIsToldAndTriedAgain() throws Exception {
        PanelId id = new PanelId("j");
        List<String> notices = new CopyOnWriteArrayList<>();
        try (Store store = new Store(home)) {
            store.onNotice(notices::add);
            store.load();
            store.saveNow().get(5, TimeUnit.SECONDS);
            // a non-empty directory where panels.json goes: the replacing move cannot remove it
            Files.delete(home.resolve("panels.json"));
            Files.createDirectory(home.resolve("panels.json"));
            Files.writeString(home.resolve("panels.json").resolve("keep"), "x");
            store.savePanels(List.of(new PanelState(id, "spec", "", "")), List.of());
            store.changed(id, "text");
            assertThat(store.saveNow().get(5, TimeUnit.SECONDS)).isNotNull();
            assertThat(notices).anyMatch(n -> n.startsWith("Cannot write panels.json"));

            Files.delete(home.resolve("panels.json").resolve("keep"));
            Files.delete(home.resolve("panels.json"));
            long deadline = System.currentTimeMillis() + Store.RETRY_MILLIS + 3_000;
            while (!Files.isRegularFile(home.resolve("panels.json")) && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            assertThat(home.resolve("panels.json")).as("tried again without another edit").isRegularFile();
            assertThat(Files.readString(home.resolve("panels.json"))).contains("spec.md");
        }
    }

    @Test
    void aFileElsewhereOpensInAPanelAndIsNeverRenamedOrDeletedByComando() throws Exception {
        Path day = Files.createDirectories(home.resolve("SCRATCHPAD").resolve("2026-10-14"));
        Path doc = Files.writeString(day.resolve("READ-01-X.md"), "# read me");
        PanelId id = new PanelId("x");
        List<PanelTexts.Outside> outside = new CopyOnWriteArrayList<>();
        try (Store store = new Store(home)) {
            store.onOutsideChange(outside::add);
            store.load();
            assertThat(store.open(id, doc).get(5, TimeUnit.SECONDS)).isEqualTo(PanelTexts.Opened.BOUND);
            assertThat(store.load(id)).isEqualTo("# read me");
            assertThat(store.document(id)).as("shows as its own language, Markdown rendered first").isTrue();
            store.savePanels(List.of(new PanelState(id, "READ-01-X", "", "")), List.of());
            store.changed(id, "# read me\n\nedited in comando");
            store.saveNow().get(5, TimeUnit.SECONDS);
            assertThat(Files.readString(doc)).isEqualTo("# read me\n\nedited in comando");

            store.savePanels(List.of(new PanelState(id, "renamed tab", "", "")), List.of());
            store.saveNow().get(5, TimeUnit.SECONDS);
            assertThat(doc).as("a file elsewhere keeps its name").exists();

            writeOutside(doc, "edited by Claude");
            store.checkFilesNow().get(5, TimeUnit.SECONDS);
            assertThat(outside).singleElement().extracting(PanelTexts.Outside::text).isEqualTo("edited by Claude");
            assertThat(outside.getFirst().take().getAsBoolean()).isTrue();

            store.changed(id, "");
            store.forget(id);
            store.savePanels(List.of(), List.of());
            store.saveNow().get(5, TimeUnit.SECONDS);
        }
        assertThat(doc).as("blank, but not comando's to delete").exists();
        assertThat(Files.readString(home.resolve("panels.json"))).doesNotContain("READ-01-X");
        try (Stream<Path> files = Files.list(day)) {
            assertThat(files.map(p -> p.getFileName().toString())).as("no temporary files left").containsExactly("READ-01-X.md");
        }
    }

    @Test
    void aPanelOnAFileElsewhereComesBackOnThatFileAfterARestart() throws Exception {
        Path doc = Files.writeString(Files.createDirectories(home.resolve("SCRATCHPAD")).resolve("PLAN.md"), "# plan");
        PanelId id = new PanelId("p");
        try (Store store = new Store(home)) {
            store.load();
            store.open(id, doc).get(5, TimeUnit.SECONDS);
            store.savePanels(List.of(new PanelState(id, "PLAN", "", "")), List.of());
            store.changed(id, "# plan v2");
            store.saveNow().get(5, TimeUnit.SECONDS);
        }
        try (Store store = new Store(home)) {
            store.load();
            assertThat(store.load(id)).isEqualTo("# plan v2");
            assertThat(store.file(id)).contains(doc);
        }
        assertThat(Files.notExists(dir()) || !names().contains("PLAN.md")).as("nothing copied into panels/").isTrue();
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
