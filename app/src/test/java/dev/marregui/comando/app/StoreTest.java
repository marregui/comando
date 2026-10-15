package dev.marregui.comando.app;

import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.state.PanelState;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class StoreTest {

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
}
