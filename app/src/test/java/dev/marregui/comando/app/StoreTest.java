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

/// The data root's files: settings, panels and their text, the Files tab's folders and the secret store behind the
/// connections.
class StoreTest {

    private static final Path CLAUDE_DIR = Path.of("/home/u/.claude");

    @TempDir
    Path home;

    /// A data root with no files starts with the default connection and one welcome panel bound to it, and writes
    /// `connections.json` and `panels.json`.
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

    /// When a settings file cannot be replaced, here `sessions.json` blocked by a non-empty directory, the save posts a
    /// notice naming the file.
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

    /// Connections, sessions, the panel bound to them and the panel's text survive a close and a fresh load unchanged.
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

    /// A file written through the store lands whole, in a folder created if it is gone, and a second write replaces it,
    /// leaving no temporary file beside it.
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

    /// The Files tab's folders start as the default, come back as saved with their index, excludes, icon, group and
    /// trash listing, a hand-written entry with only a name and path loads with defaults, an emptied list stays empty,
    /// and an unreadable `folders.json` gives the default and is kept aside.
    @Test
    void theFilesTabsFoldersHaveADefaultAndAreKeptAsSavedEvenEmpty() throws Exception {
        try (Store store = new Store(home)) {
            assertThat(store.loadFolders(CLAUDE_DIR)).as("no folders.json: the default").isEqualTo(FolderRoot.defaults(CLAUDE_DIR));
            store.saveFolders(List.of(new FolderRoot("KB", "/kb", "ALPHA.md", List.of("tools")),
                    new FolderRoot("app", "/app", "", List.of())));
        }
        assertThat(Files.readString(home.resolve("folders.json"))).contains("\"index\" : \"ALPHA.md\"", "\"tools\"").doesNotContain("repository");
        try (Store store = new Store(home)) {
            assertThat(store.loadFolders(CLAUDE_DIR)).as("as saved")
                    .containsExactly(new FolderRoot("KB", "/kb", "ALPHA.md", List.of("tools")), new FolderRoot("app", "/app", "", List.of()));
            store.saveFolders(List.of(new FolderRoot("KB", "/kb", "", List.of(), "knowledge", false, "", "Brain")));
        }
        try (Store store = new Store(home)) {
            assertThat(store.loadFolders(CLAUDE_DIR)).extracting(FolderRoot::group).as("a group kept (record 98)").containsExactly("Brain");
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

    /// On the first start on a kit's root its folders are put first and recorded, and afterwards they are neither added
    /// again nor kept once the user took them out; a root with no kit adds nothing.
    @Test
    void theKitsFoldersComeFirstAtTheFirstStartOnItsRootAndOnlyThen() throws Exception {
        Path root = home.resolve("claude/kit");
        Path memory = Files.createDirectories(root.resolve("BRAIN/MEMORY"));
        KitRoot kitRoot = new KitRoot(root, Optional.of(new KitRoot.Manifest("k", "BRAIN",
                List.of(new FolderRoot("Memory", "BRAIN/MEMORY", "MEMORY.md", List.of(), "memory")), List.of(), List.of(), List.of())),
                home.resolve("claude"));
        FolderRoot kitMemory = new FolderRoot("Memory", memory.toString(), "MEMORY.md", List.of(), "memory");
        FolderRoot repo = new FolderRoot("app", "/app", "", List.of());
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

    /// A fresh data home on a kit root shows the kit's folders, the default folder being the kit's own root and so not
    /// counting as the kit already shown.
    @Test
    void aFreshHomeOnAKitRootGetsTheKitsFoldersInsteadOfTheDefault() throws Exception {
        // the default folder is the config folder, which is the kit's own root ("Kit", path "."): it must not count as
        // the kit already shown
        Path root = Files.createDirectories(home.resolve("claude/kit"));
        Path memory = Files.createDirectories(root.resolve("BRAIN/MEMORY"));
        KitRoot kitRoot = new KitRoot(root, Optional.of(new KitRoot.Manifest("k", "BRAIN",
                List.of(new FolderRoot("Kit", ".", "", List.of(), "folder"),
                        new FolderRoot("Memory", "BRAIN/MEMORY", "MEMORY.md", List.of(), "memory")),
                List.of(), List.of(), List.of())), root);
        FolderRoot kitDir = new FolderRoot("Kit", root.toString(), "", List.of(), "folder");
        FolderRoot kitMemory = new FolderRoot("Memory", memory.toString(), "MEMORY.md", List.of(), "memory");
        try (Store store = new Store(home)) {
            assertThat(store.loadFolders(root)).as("no folders file: the default").isEqualTo(FolderRoot.defaults(root));
            assertThat(store.withKitFolders(store.loadFolders(root), kitRoot)).containsExactly(kitDir, kitMemory);
        }
        try (Store store = new Store(home)) {
            assertThat(store.loadFolders(root)).as("saved").containsExactly(kitDir, kitMemory);
        }
    }

    /// A home that recorded the kit's root while saving no folders file, so the tab showed only the default, gets the
    /// kit's folders at the next start, and a list the user then empties stays empty.
    @Test
    void aHomeTheDefaultHidTheKitFromIsRepairedAtTheNextStart() throws Exception {
        // before the fix a fresh home recorded the root in kit-folders and saved no folders file: the Files tab showed only
        // "Claude", and no later start tried again
        Path root = Files.createDirectories(home.resolve("claude/kit"));
        KitRoot kitRoot = new KitRoot(root, Optional.of(new KitRoot.Manifest("k", "BRAIN",
                List.of(new FolderRoot("Kit", ".", "", List.of(), "folder")), List.of(), List.of(), List.of())), root);
        Files.writeString(home.resolve("kit-folders"), root + "\n");
        try (Store store = new Store(home)) {
            assertThat(store.withKitFolders(store.loadFolders(root), kitRoot))
                    .containsExactly(new FolderRoot("Kit", root.toString(), "", List.of(), "folder"));
        }
        try (Store store = new Store(home)) {
            store.saveFolders(List.of());
        }
        try (Store store = new Store(home)) {
            assertThat(store.withKitFolders(store.loadFolders(root), kitRoot)).as("a list the user emptied stays empty").isEmpty();
        }
    }

    /// A hand-edited `folders.json` is held to the rules of the folders dialog: duplicate or empty names, relative
    /// paths, an index outside the folder and a missing path are left out, `~` is expanded, and one notice says what
    /// was left out and why.
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

    /// A `folders.json` changed by hand is read again and handed to the tab once, while an unchanged file or the
    /// store's own write is not; one that stops reading leaves the tab's folders, posts a notice and is not moved
    /// aside.
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

    /// A notice raised while loading is kept and delivered to the listener that registers afterwards.
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

    /// A `sessions.json` written before sessions had a checkpoint command loads with the default `/reflect`.
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

    /// A panel bound to a connection or session that no longer exists loads unbound.
    @Test
    void bindingsToMissingNamesAreDropped() throws Exception {
        Files.writeString(home.resolve("connections.json"), "[]");
        Files.writeString(home.resolve("sessions.json"), "[]");
        Files.writeString(home.resolve("panels.json"), "[{\"id\":\"p\",\"title\":\"t\",\"connection\":\"gone\",\"session\":\"gone\",\"content\":\"x\"}]");
        try (Store store = new Store(home)) {
            assertThat(store.load().panels()).containsExactly(new PanelState(new PanelId("p"), "t", "", ""));
        }
    }

    /// A session bound to several panels stays with the first and is detached from the others, and one notice names the
    /// session and the detached panels; a list with one binding each is left as it is.
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

    /// A settings file that does not parse loads as empty and is kept aside under a `.corrupt-` name instead of being
    /// overwritten.
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

    /// Repeated saves leave no `.tmp` file in the data root.
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

    /// A connection's password goes to the secret store and not into `connections.json`, a blank one stores nothing, it
    /// reads back at the next load, and a connection saved with a blank password has its item removed.
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

    /// When the secret store cannot be read, as with a locked keychain, the connection loads without its password and
    /// saving it, or renaming it, keeps the stored item.
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

    /// A secret store write stuck on an unanswered unlock prompt does not hold back the panels' text, which reaches its
    /// file meanwhile; the password is stored once the prompt is answered.
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

    /// The keychain's answer is decoded in both forms it prints, the quoted password and the hex form with escapes, a
    /// quoted `0x` text stays text, and an empty password is empty.
    @Test
    void keychainAnswersAreDecodedInBothForms() {
        assertThat(Secrets.Keychain.password("keychain: \"/x\"\npassword: \"pa ss\"word\"\n")).isEqualTo("pa ss\"word");
        assertThat(Secrets.Keychain.password("password: 0x636F6E7472617365C3B16120E29C93  \"contrase\\303\\261a \\342\\234\\223\"\n"))
                .isEqualTo("contraseña ✓");
        assertThat(Secrets.Keychain.password("password: \"0x41\"\n")).isEqualTo("0x41");
        assertThat(Secrets.Keychain.password("password: \n")).isEmpty();
    }

    /// The login keychain stores, replaces, reads and deletes a password, with quotes and non-ASCII characters intact;
    /// it touches the real keychain, so it runs only when asked by a property.
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

    /// A folder naming an index file that is missing inside the config folder gets it created once, with a heading and
    /// a note on how it is kept; an existing index, a path into a missing subfolder and a folder outside the config
    /// folder are left alone.
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
                new FolderRoot("Outside", repo.toString(), "INDEX.md", List.of()));

        assertThat(Store.createIndexes(folders, claude)).containsExactly(skills.resolve("INDEX.md"));
        assertThat(Files.readString(skills.resolve("INDEX.md"))).startsWith("# Skills\n").contains("`/reflect` keeps it current");
        assertThat(Files.readString(memory.resolve("MEMORY.md"))).as("an index that exists is left").isEqualTo("kept\n");
        assertThat(skills.resolve("gone")).as("no folder is made for it").doesNotExist();
        assertThat(repo.resolve("INDEX.md")).as("outside Claude Code's folder").doesNotExist();
        assertThat(Store.createIndexes(folders, claude)).as("once").isEmpty();
    }
}
