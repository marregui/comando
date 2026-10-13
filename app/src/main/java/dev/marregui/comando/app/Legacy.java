package dev.marregui.comando.app;

import com.fasterxml.jackson.databind.JsonNode;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.state.PanelState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads the store files of the earlier comando ({@code ~/QUESTS/connections.json} and
 * {@code default-notebook.json}) once, to seed a fresh data root. The old files are only read.
 */
final class Legacy {

    private static final System.Logger LOG = System.getLogger(Legacy.class.getName());

    private final Path root;
    private final Map<String, String> texts = new HashMap<>();

    Legacy(Path root) {
        this.root = root;
    }

    static Legacy none() {
        return new Legacy(null);
    }

    Optional<Store.Loaded> read() {
        if (root == null || !Files.isRegularFile(root.resolve("connections.json"))) {
            return Optional.empty();
        }
        try {
            List<ConnectionSpec> connections = new ArrayList<>();
            Set<String> names = new HashSet<>();
            for (JsonNode c : JsonFile.JSON.readTree(root.resolve("connections.json").toFile())) {
                JsonNode a = c.path("attrs");
                String name = unique(c.path("name").asText("connection"), names);
                int port;
                try {
                    port = Integer.parseInt(a.path("port").asText("8812"));
                } catch (NumberFormatException e) {
                    port = 8812;
                }
                connections.add(new ConnectionSpec(name, a.path("host").asText("localhost"), port,
                        a.path("database").asText("qdb"), a.path("username").asText("admin"), a.path("password").asText("quest"), false));
            }
            String bound = connections.isEmpty() ? "" : connections.getFirst().name();
            List<PanelState> panels = new ArrayList<>();
            Path notebook = root.resolve("default-notebook.json");
            if (Files.isRegularFile(notebook)) {
                Set<String> titles = new HashSet<>();
                for (JsonNode n : JsonFile.JSON.readTree(notebook.toFile())) {
                    PanelState p = new PanelState(PanelId.random(), unique(n.path("name").asText("panel"), titles), bound, "");
                    texts.put(p.id().value(), n.path("attrs").path("content").asText(""));
                    panels.add(p);
                }
            }
            if (panels.isEmpty()) {
                PanelState p = new PanelState(PanelId.random(), "scratch", bound, "");
                texts.put(p.id().value(), Welcome.TEXT);
                panels.add(p);
            }
            LOG.log(System.Logger.Level.INFO, "seeded from the earlier store [root=" + root + ", connections=" + connections.size()
                    + ", panels=" + panels.size() + ']');
            return Optional.of(new Store.Loaded(panels, connections, List.of()));
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot read the earlier store [root=" + root + "]: " + e.getMessage());
            return Optional.empty();
        }
    }

    Optional<String> textOf(PanelId id) {
        return Optional.ofNullable(texts.get(id.value())).or(() -> Optional.of(Welcome.TEXT));
    }

    private static String unique(String base, Set<String> taken) {
        String name = base.isBlank() ? "unnamed" : base.strip();
        String candidate = name;
        for (int i = 2; taken.contains(candidate); i++) {
            candidate = name + " " + i;
        }
        taken.add(candidate);
        return candidate;
    }
}
