package dev.marregui.comando.app;

import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.KitRoot;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.state.PanelState;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class StoreTest {

    private static final Path CLAUDE_DIR = Path.of("/home/u/.claude");

    @TempDir
    Path home;

    @Test
    void freshRootGetsDefaultsAndWritesThem() {
        Store.Loaded loaded;
        try (Store store = new Store(home)) {
            loaded = store.load();
            assertThat(store.load(loaded.panels().getFirst().id())).contains("Welcome to comando");
        }
        assertThat(loaded.connections()).extracting(ConnectionSpec::name).containsExactly("local QuestDB");
        assertThat(loaded.panels()).singleElement().extracting(PanelState::connection).isEqualTo("local QuestDB");
        assertThat(home.resolve("connections.json")).exists();
        assertThat(home.resolve("panels.json")).exists();
    }

    @Test
    void aSettingsFileThatCannotBeWrittenIsTold() throws Exception {
        List<String> notices = new CopyOnWriteArrayList<>();
        try (Store store = new Store(home)) {
            store.onNotice(notices::add);
            store.load();
            store.saveNow().get(5, TimeUnit.SECONDS); // what loading wrote is on disk before the file is replaced
            // a non-empty directory where sessions.json goes: the replacing move cannot remove it
            Files.deleteIfExists(home.resolve("sessions.json"));
            Files.createDirectories(home.resolve("sessions.json"));
            Files.writeString(home.resolve("sessions.json").resolve("keep"), "x");
            store.saveSessions(List.of(SessionSpec.imported("edge", "/w", "abc")));
            store.saveNow().get(5, TimeUnit.SECONDS);
        }
        assertThat(notices).anyMatch(n -> n.startsWith("Cannot write sessions.json"));
    }

    @Test
    void roundTripOfSpecsBindingsAndText() {
        PanelId id = new PanelId("p-1");
        try (Store store = new Store(home)) {
            store.load();
            store.saveConnections(List.of(new ConnectionSpec("lab", "10.0.0.2", 8812, "qdb", "admin", "pw", true)));
            store.saveSessions(List.of(SessionSpec.imported("edge", "/w", "abc")));
            store.savePanels(List.of(new PanelState(id, "work", "lab", "edge")), List.of());
            store.changed(id, "SELECT 42;");
        }
        try (Store store = new Store(home)) {
            Store.Loaded loaded = store.load();
            assertThat(loaded.connections()).singleElement().extracting(ConnectionSpec::prod).isEqualTo(true);
            assertThat(loaded.sessions()).singleElement().extracting(SessionSpec::claudeSessionId).isEqualTo("abc");
            assertThat(loaded.panels()).containsExactly(new PanelState(id, "work", "lab", "edge"));
            assertThat(store.load(id)).isEqualTo("SELECT 42;");
        }
    }

    @Test
    void aFileIsWrittenWholeOnTheWritersThreadEvenWhereItsFolderIsGone() throws Exception {
        Path file = home.resolve("repo/docs/gone/a.md");
        try (Store store = new Store(home)) {
            assertThat(store.write(file, "# a\n").get(10, TimeUnit.SECONDS)).as("written").isNull();
            assertThat(Files.readString(file)).isEqualTo("# a\n");
            assertThat(store.write(file, "# b\n").get(10, TimeUnit.SECONDS)).isNull();
            assertThat(Files.readString(file)).isEqualTo("# b\n");
            assertThat(Files.list(file.getParent())).as("no temporary file left").extracting(p -> p.getFileName().toString()).containsExactly("a.md");
        }
    }

    @Test
    void theFilesTabsFoldersHaveADefaultAndAreKeptAsSavedEvenEmpty() throws Exception {
        try (Store store = new Store(home)) {
            assertThat(store.loadFolders(CLAUDE_DIR)).as("no folders.json: the default").isEqualTo(FolderRoot.defaults(CLAUDE_DIR));
            store.saveFolders(List.of(new FolderRoot("KB", "/kb", "ALPHA.md", List.of("tools")),
                    new FolderRoot("app", "/app", "", List.of()).asRepository()));
        }
        assertThat(Files.readString(home.resolve("folders.json"))).contains("\"index\" : \"ALPHA.md\"", "\"tools\"", "\"repository\" : true");
        try (Store store = new Store(home)) {
            assertThat(store.loadFolders(CLAUDE_DIR)).as("a repository stays one")
                    .containsExactly(new FolderRoot("KB", "/kb", "ALPHA.md", List.of("tools")), new FolderRoot("app", "/app", "", List.of()).asRepository());
            store.saveFolders(List.of());
        }
        try (Store store = new Store(home)) {
            assertThat(store.loadFolders(CLAUDE_DIR)).as("no folder is a choice").isEmpty();
        }
        Files.writeString(home.resolve("folders.json"), "[{\"name\":\"KB\",\"path\":\"/kb\"}]");
        try (Store store = new Store(home)) {
            assertThat(store.loadFolders(CLAUDE_DIR)).as("a hand-written entry without index or excludes")
                    .containsExactly(new FolderRoot("KB", "/kb", "", List.of()));
        }
        Files.writeString(home.resolve("folders.json"), "[{\"name\":\"Memory\",\"path\":\"/m\",\"icon\":\"memory\",\"readOnly\":true}]");
        try (Store store = new Store(home)) {
            assertThat(store.loadFolders(CLAUDE_DIR)).as("the icon kept through the folders' rules; an old read-only flag ignored")
                    .containsExactly(new FolderRoot("Memory", "/m", "", List.of(), "memory"));
        }
        Files.writeString(home.resolve("folders.json"), "[{\"name\":\"Scratchpad\",\"path\":\"/s\",\"deletable\":true,\"listedIn\":\"ALPHA.md\"}]");
        try (Store store = new Store(home)) {
            assertThat(store.loadFolders(CLAUDE_DIR)).as("entries that go to the Trash, with their listing file")
                    .containsExactly(new FolderRoot("Scratchpad", "/s", "", List.of()).withTrash("ALPHA.md"));
        }
        Files.writeString(home.resolve("folders.json"), "{not json");
        try (Store store = new Store(home)) {
            assertThat(store.loadFolders(CLAUDE_DIR)).as("unreadable: the default").isEqualTo(FolderRoot.defaults(CLAUDE_DIR));
        }
        try (var aside = Files.list(home)) {
            assertThat(aside.map(p -> p.getFileName().toString())).anyMatch(n -> n.startsWith("folders.json.corrupt-"));
        }
    }

    @Test
    void theKitsFoldersComeFirstAtTheFirstStartOnItsRootAndOnlyThen() throws Exception {
        Path root = home.resolve("claude/kit");
        Path memory = Files.createDirectories(root.resolve("BRAIN/MEMORY"));
        KitRoot kitRoot = new KitRoot(root, Optional.of(new KitRoot.Manifest("k", "BRAIN",
                List.of(new FolderRoot("Memory", "BRAIN/MEMORY", "MEMORY.md", List.of(), "memory")), List.of(), List.of(), List.of())),
                home.resolve("claude"));
        FolderRoot kitMemory = new FolderRoot("Memory", memory.toString(), "MEMORY.md", List.of(), "memory");
        FolderRoot repo = new FolderRoot("app", "/app", "", List.of()).asRepository();
        Files.writeString(home.resolve("folders.json"), "[{\"name\":\"app\",\"path\":\"/app\",\"repository\":true}]");
        try (Store store = new Store(home)) {
            assertThat(store.withKitFolders(store.loadFolders(CLAUDE_DIR), kitRoot)).containsExactly(kitMemory, repo);
        }
        try (Store store = new Store(home)) {
            assertThat(store.loadFolders(CLAUDE_DIR)).as("saved").containsExactly(kitMemory, repo);
            store.saveFolders(List.of(repo));
        }
        try (Store store = new Store(home)) {
            assertThat(store.withKitFolders(store.loadFolders(CLAUDE_DIR), kitRoot)).as("taken out by the user: stays out").containsExactly(repo);
            assertThat(store.withKitFolders(List.of(repo), KitRoot.plain(root))).as("no kit").containsExactly(repo);
        }
        assertThat(Files.readAllLines(home.resolve("kit-folders"))).containsExactly(root.toString());
    }

    @Test
    void aHandEditedFoldersFileMeetsTheDialogsRulesAndSaysWhatItLeftOut() throws Exception {
        Files.writeString(home.resolve("folders.json"), """
                [ {"name":"KB","path":"/kb"}, {"name":"KB","path":"/other"}, {"name":"Rel","path":"../other"},
                  {"name":"","path":"/x"}, {"name":"Out","path":"/o","index":"../up.md"}, {"name":"Home","path":"~/notes"}, {"name":"NoPath"}, null ]
                """);
        List<String> told = new CopyOnWriteArrayList<>();
        try (Store store = new Store(home)) {
            List<FolderRoot> loaded = store.loadFolders(CLAUDE_DIR);
            store.onNotice(told::add);
            assertThat(loaded).extracting(FolderRoot::name).containsExactly("KB", "Home");
            assertThat(loaded.getFirst().path()).isEqualTo("/kb");
            assertThat(loaded.get(1).path()).isEqualTo(System.getProperty("user.home") + "/notes");
        }
        assertThat(told).singleElement().asString().startsWith("folders.json: left out KB (the name is used by another folder); Rel (")
                .contains("a folder without a name (", "Out (the index file must be inside the folder", "NoPath (choose the folder to show)");
    }

    @Test
    void foldersJsonChangedByHandIsReadAgainAndOneThatDoesNotReadStaysWhereItIs() throws Exception {
        Path json = home.resolve("folders.json");
        Files.writeString(json, "[{\"name\":\"KB\",\"path\":\"/kb\"}]");
        List<String> told = new CopyOnWriteArrayList<>();
        List<List<FolderRoot>> read = new CopyOnWriteArrayList<>();
        try (Store store = new Store(home)) {
            store.loadFolders(CLAUDE_DIR);
            store.onNotice(told::add);
            store.onFoldersChanged(read::add);
            store.checkFilesNow().get(5, TimeUnit.SECONDS);
            assertThat(read).as("unchanged since it was read").isEmpty();

            Files.writeString(json, "[{\"name\":\"KB\",\"path\":\"/kb\",\"index\":\"ALPHA.md\"},{\"name\":\"Notes\",\"path\":\"/n\"}]");
            Files.setLastModifiedTime(json, FileTime.fromMillis(System.currentTimeMillis() + 2_000));
            store.checkFilesNow().get(5, TimeUnit.SECONDS);
            assertThat(read).singleElement().isEqualTo(List.of(new FolderRoot("KB", "/kb", "ALPHA.md", List.of()), new FolderRoot("Notes", "/n", "", List.of())));

            Files.writeString(json, "[{\"name\":");
            Files.setLastModifiedTime(json, FileTime.fromMillis(System.currentTimeMillis() + 4_000));
            store.checkFilesNow().get(5, TimeUnit.SECONDS);
            assertThat(read).as("a file half edited keeps the tab's folders").hasSize(1);
            assertThat(told).singleElement().asString().startsWith("folders.json does not read (").endsWith("); the Files tab keeps its folders");
            assertThat(json).as("not moved aside: the user is editing it").hasContent("[{\"name\":");

            store.saveFolders(List.of(new FolderRoot("Own", "/o", "", List.of())));
            store.checkFilesNow().get(5, TimeUnit.SECONDS);
            assertThat(read).as("the store's own write is not a change of the user's").hasSize(1);
        }
    }

    @Test
    void whatTheStartFoundIsToldWhenTheListenerComesLater() throws Exception {
        Files.writeString(home.resolve("connections.json"), "[]");
        Files.writeString(home.resolve("sessions.json"), "[]");
        Files.writeString(home.resolve("panels.json"), "[ {\"id\":\"a\",\"title\":\"notes\",\"file\":\"notes.md\",\"closed\":true} ]");
        List<String> told = new CopyOnWriteArrayList<>();
        try (Store store = new Store(home)) {
            store.load();
            store.onNotice(told::add);
            assertThat(told).singleElement().asString().contains("deleted while comando was closed");
            store.saveFolders(List.of());
        }
    }

    @Test
    void aSessionsFileFromBeforeTheCheckpointFieldLoadsWithTheDefaultCommand() throws Exception {
        Files.writeString(home.resolve("connections.json"), "[]");
        Files.writeString(home.resolve("sessions.json"), "[{\"name\":\"old\",\"workDir\":\"/w\",\"model\":\"\",\"effort\":\"\","
                + "\"permissionMode\":\"\",\"addDirs\":[],\"budgetUsd\":0.0,\"claudeSessionId\":\"\",\"startMode\":\"NEW\"}]");
        Files.writeString(home.resolve("panels.json"), "[]");
        try (Store store = new Store(home)) {
            assertThat(store.load().sessions()).extracting(SessionSpec::checkpoint).containsExactly("/reflect");
        }
    }

    @Test
    void bindingsToMissingNamesAreDropped() throws Exception {
        Files.writeString(home.resolve("connections.json"), "[]");
        Files.writeString(home.resolve("sessions.json"), "[]");
        Files.writeString(home.resolve("panels.json"), "[{\"id\":\"p\",\"title\":\"t\",\"connection\":\"gone\",\"session\":\"gone\",\"content\":\"x\"}]");
        try (Store store = new Store(home)) {
            assertThat(store.load().panels()).containsExactly(new PanelState(new PanelId("p"), "t", "", ""));
        }
    }

    @Test
    void aSessionBoundToSeveralPanelsKeepsItsFirstAtTheStart() {
        List<PanelState> panels = List.of(new PanelState(new PanelId("a"), "one", "", "work"),
                new PanelState(new PanelId("b"), "two", "", "work"), new PanelState(new PanelId("c"), "three", "", "edge"),
                new PanelState(new PanelId("d"), "four", "", "work"));
        List<String> unbound = new ArrayList<>();
        assertThat(Store.oneBindingPerSession(panels, unbound)).extracting(PanelState::session)
                .containsExactly("work", "", "edge", "");
        assertThat(unbound).containsExactly("work stays in one; two, four are detached");
        assertThat(Store.oneBindingPerSession(panels.subList(0, 3), new ArrayList<>())).extracting(PanelState::session)
                .containsExactly("work", "", "edge");
    }

    @Test
    void anUnreadableFileIsKeptAsideAndNotOverwritten() throws Exception {
        Files.writeString(home.resolve("connections.json"), "{ this is not json");
        Files.writeString(home.resolve("sessions.json"), "[]");
        try (Store store = new Store(home)) {
            Store.Loaded loaded = store.load();
            assertThat(loaded.connections()).isEmpty();
        }
        try (Stream<Path> files = Files.list(home)) {
            assertThat(files.map(p -> p.getFileName().toString())).anyMatch(n -> n.startsWith("connections.json.corrupt-"));
        }
    }

    @Test
    void writesLeaveNoTemporaryFiles() throws Exception {
        try (Store store = new Store(home)) {
            store.load();
            for (int i = 0; i < 20; i++) {
                store.saveSessions(List.of(SessionSpec.fresh("s" + i, "/w")));
            }
        }
        try (Stream<Path> files = Files.list(home)) {
            assertThat(files.map(p -> p.getFileName().toString())).noneMatch(n -> n.endsWith(".tmp"));
        }
    }

    /** A secret store in memory. */
    static class MapSecrets implements Secrets {
        final Map<String, String> map = new ConcurrentHashMap<>();

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public Optional<String> read(String name) {
            return Optional.ofNullable(map.get(name));
        }

        @Override
        public boolean write(String name, String password) {
            map.put(name, password);
            return true;
        }

        @Override
        public void delete(String name) {
            map.remove(name);
        }
    }

    @Test
    void passwordsLiveInTheSecretStoreNotInTheFile() throws Exception {
        MapSecrets secrets = new MapSecrets();
        try (Store store = new Store(home, secrets)) {
            store.load();
            store.saveConnections(List.of(new ConnectionSpec("lab", "h", 8812, "qdb", "admin", "s3cret", false),
                    new ConnectionSpec("open", "h", 8812, "qdb", "admin", "", false)));
        }
        assertThat(Files.readString(home.resolve("connections.json"))).doesNotContain("s3cret");
        assertThat(secrets.map).containsExactly(Map.entry("lab", "s3cret"));
        try (Store store = new Store(home, secrets)) {
            assertThat(store.load().connections()).extracting(ConnectionSpec::password).containsExactly("s3cret", "");
            store.saveConnections(List.of(new ConnectionSpec("open", "h", 8812, "qdb", "admin", "", false)));
        }
        assertThat(secrets.map).isEmpty();
    }

    @Test
    void aPasswordTheStoreCouldNotReadIsNotDeletedBySaving() throws Exception {
        MapSecrets secrets = new MapSecrets();
        try (Store store = new Store(home, secrets)) {
            store.load();
            store.saveConnections(List.of(new ConnectionSpec("lab", "h", 8812, "qdb", "admin", "s3cret", false)));
        }
        MapSecrets locked = new MapSecrets() {
            @Override
            public Optional<String> read(String name) {
                throw new Secrets.Unreadable("locked");
            }
        };
        locked.map.putAll(secrets.map);
        try (Store store = new Store(home, locked)) {
            assertThat(store.load().connections()).extracting(ConnectionSpec::password).containsExactly("");
            // saving another change, and renaming the connection away, both keep the item it could not read
            store.saveConnections(List.of(new ConnectionSpec("lab", "h2", 8812, "qdb", "admin", "", false)));
            store.saveConnections(List.of(new ConnectionSpec("lab2", "h2", 8812, "qdb", "admin", "", false)));
        }
        assertThat(locked.map).containsEntry("lab", "s3cret");
    }

    @Test
    void aKeychainPromptNobodyAnswersDoesNotHoldBackPanelText() throws Exception {
        CountDownLatch prompt = new CountDownLatch(1);
        MapSecrets waiting = new MapSecrets() {
            @Override
            public boolean write(String name, String password) {
                try {
                    prompt.await(); // the unlock prompt stays open
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.write(name, password);
            }
        };
        try (Store store = new Store(home, waiting)) {
            store.load();
            PanelId id = new PanelId("p-wait");
            store.saveConnections(List.of(new ConnectionSpec("lab", "h", 8812, "qdb", "admin", "s3cret", false)));
            store.savePanels(List.of(new PanelState(id, "work", "", "")), List.of());
            store.changed(id, "SELECT 7;");
            long deadline = System.currentTimeMillis() + 5_000;
            Path file = home.resolve("panels").resolve("work.md");
            while (!(Files.exists(file) && Files.readString(file).contains("SELECT 7;")) && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertThat(Files.readString(file)).isEqualTo("SELECT 7;");
            prompt.countDown();
        }
        assertThat(waiting.map).containsEntry("lab", "s3cret");
    }

    @Test
    void keychainAnswersAreDecodedInBothForms() {
        assertThat(Secrets.Keychain.password("keychain: \"/x\"\npassword: \"pa ss\"word\"\n")).isEqualTo("pa ss\"word");
        assertThat(Secrets.Keychain.password("password: 0x636F6E7472617365C3B16120E29C93  \"contrase\\303\\261a \\342\\234\\223\"\n"))
                .isEqualTo("contraseña ✓");
        assertThat(Secrets.Keychain.password("password: \"0x41\"\n")).isEqualTo("0x41");
        assertThat(Secrets.Keychain.password("password: \n")).isEmpty();
    }

    @Test
    void theKeychainStoresReadsAndDeletes() {
        Assumptions.assumeTrue(Boolean.getBoolean("comando.keychainTest"), "set -Dcomando.keychainTest=true to touch the login keychain");
        Secrets.Keychain k = new Secrets.Keychain(Path.of("/usr/bin/security"), "comando-test");
        String name = "test-" + System.nanoTime();
        try {
            assertThat(k.write(name, "pa ss\"word")).isTrue();
            assertThat(k.read(name)).contains("pa ss\"word");
            assertThat(k.write(name, "second")).isTrue();
            assertThat(k.read(name)).contains("second");
            assertThat(k.write(name, "contraseña ✓")).isTrue();
            assertThat(k.read(name)).contains("contraseña ✓");
        } finally {
            k.delete(name);
        }
        assertThat(k.read(name)).isEmpty();
    }

    @Test
    void anIndexNamedButMissingInsideTheConfigFolderIsCreatedOnceAndNothingElseIs() throws Exception {
        Path claude = Files.createDirectories(home.resolve(".claude"));
        Path skills = Files.createDirectories(claude.resolve("skills"));
        Path memory = Files.createDirectories(claude.resolve("MEMORY"));
        Files.writeString(memory.resolve("MEMORY.md"), "kept\n");
        Path repo = Files.createDirectories(home.resolve("repo"));
        List<FolderRoot> folders = List.of(
                new FolderRoot("Skills", skills.toString(), "INDEX.md", List.of()),
                new FolderRoot("Memory", memory.toString(), "MEMORY.md", List.of()),
                new FolderRoot("Deep", skills.toString(), "gone/INDEX.md", List.of()),
                new FolderRoot("Repo", repo.toString(), "README.md", List.of()).asRepository(),
                new FolderRoot("Outside", repo.toString(), "INDEX.md", List.of()));

        assertThat(Store.createIndexes(folders, claude)).containsExactly(skills.resolve("INDEX.md"));
        assertThat(Files.readString(skills.resolve("INDEX.md"))).startsWith("# Skills\n").contains("`/reflect` keeps it current");
        assertThat(Files.readString(memory.resolve("MEMORY.md"))).as("an index that exists is left").isEqualTo("kept\n");
        assertThat(skills.resolve("gone")).as("no folder is made for it").doesNotExist();
        assertThat(repo.resolve("README.md")).doesNotExist();
        assertThat(repo.resolve("INDEX.md")).as("outside Claude Code's folder").doesNotExist();
        assertThat(Store.createIndexes(folders, claude)).as("once").isEmpty();
    }
}
