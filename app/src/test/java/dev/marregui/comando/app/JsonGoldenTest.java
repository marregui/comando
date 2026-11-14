package dev.marregui.comando.app;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.JsonException;
import dev.marregui.comando.core.json.JsonNode;
import dev.marregui.comando.core.json.bind.Binder;
import dev.marregui.comando.core.json.bind.TypeRef;
import dev.marregui.comando.core.model.ApprovalDecision;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.view.ViewSpec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The files comando keeps, checked against goldens the mappers it used before its own binder wrote from
/// [JsonSamples]: each golden reads back equal to its value, and the value writes back byte for byte. `reads.json`
/// holds what those mappers made of inputs a file could hold (coercions, nulls, unknown and missing properties,
/// malformed values); the binder reads each as they did, or refuses it where they refused it, but for the listed
/// departures.
class JsonGoldenTest {

    private static final String GOLDEN = "json-golden/";
    /// Enums by index, which those mappers read and the binder refuses: an enum is read by its name only, so a file
    /// never depends on the order of its constants.
    private static final Set<String> ENUM_BY_INDEX = Set.of(
            "{\"name\":\"s\",\"workDir\":\"/w\",\"model\":\"m\",\"effort\":\"high\",\"claudeSessionId\":\"\","
                    + "\"startMode\":\"0\"}",
            "{\"level\":0}");

    /// The four store files write as their goldens, and read back equal.
    @Test
    void theStoreFilesWriteAsTheirGoldensAndReadBack(@TempDir final Path dir) throws IOException {
        store(dir, "connections", JsonSamples.connections(), new TypeRef<>() {
        });
        store(dir, "sessions", JsonSamples.sessions(), new TypeRef<>() {
        });
        store(dir, "folders", JsonSamples.folders(), new TypeRef<>() {
        });
        store(dir, "panels", JsonSamples.panels(), new TypeRef<>() {
        });
    }

    /// The two registries' entries, and a record of every shape a file could hold, write as their goldens through
    /// the files' mapping, and read back equal.
    @Test
    void theRegistriesAndTheMixedRecordWriteAsTheirGoldens() throws IOException {
        pretty("questdb-server.json", JsonSamples.questDb(), MachineQuestDb.Entry.class);
        pretty("edge-registry.json", JsonSamples.edge(), EmbeddedEdge.Entry.class);
        pretty("mixed-file.json", JsonSamples.mixed(), JsonSamples.Mixed.class);
    }

    /// Through the intent log's mapping, by fields and with class names for interface-typed values, the mixed record
    /// and one of every typed shape write as their goldens and read back equal.
    @Test
    void theLogMappingWritesAsItsGoldens() throws IOException {
        compact("mixed-fields.json", JsonSamples.mixed(), JsonSamples.Mixed.class);
        compact("typed.json", JsonSamples.typed(), JsonSamples.Typed.class);
    }

    /// An intent log of the sample intents, a threshold change among them, writes its golden line for line, times
    /// aside, and reads back as the state and the intents it was written from.
    @Test
    void anIntentLogWritesAsItsGoldenAndReplays(@TempDir final Path dir) throws IOException {
        final AtomicInteger threshold = new AtomicInteger(40);
        try (final IntentLog log = new IntentLog(dir.resolve("log"), JsonSamples.state(), threshold::get)) {
            for (int i = 0; i < IntentLogTest.SAMPLE.size(); i++) {
                if (i == 3) {
                    threshold.set(75);
                }
                log.observe(IntentLogTest.SAMPLE.get(i));
            }
        }
        final Path written;
        try (final Stream<Path> files = Files.list(dir.resolve("log"))) {
            written = files.findFirst().orElseThrow();
        }
        final StringBuilder lines = new StringBuilder();
        for (final String line : Files.readAllLines(written, StandardCharsets.UTF_8)) {
            lines.append(line.replaceFirst("^\\{\"at\":\"[^\"]*\"", "{\"at\":\"" + JsonSamples.AT + "\"")).append('\n');
        }
        final String golden = text("intent-log.jsonl");
        assertThat(lines.toString()).isEqualTo(golden);

        final Path copy = Files.writeString(dir.resolve("golden.jsonl"), golden, StandardCharsets.UTF_8);
        final IntentLog.Replay replay = IntentLog.read(copy);
        assertThat(replay.initial()).isEqualTo(JsonSamples.state());
        assertThat(replay.intents()).isEqualTo(IntentLogTest.SAMPLE);
        final List<Integer> thresholds = new ArrayList<>();
        for (int i = 0; i < IntentLogTest.SAMPLE.size(); i++) {
            thresholds.add(i < 3 ? 40 : 75);
        }
        assertThat(replay.thresholds()).isEqualTo(thresholds);
    }

    /// Every input of `reads.json` reads as the earlier mappers read it, `toString` for `toString`, or is refused
    /// where they refused it; an enum by index, which they read, is refused.
    @Test
    void everyReadOfTheGoldenReadsAsBefore() throws IOException {
        final JsonNode cases = Json.readTree(text("reads.json"));
        final List<String> differ = new ArrayList<>();
        int departures = 0;
        for (final JsonNode c : cases) {
            final String input = c.path("input").asString();
            final boolean fields = c.path("mapper").asString().equals("fields");
            final Binder binder = fields ? IntentLog.BINDER : JsonFile.BINDER;
            final Type type = TYPES.get(c.path("type").asString());
            final String expected = c.path("outcome").asString();
            String outcome;
            try {
                outcome = "ok " + binder.fromTree(Json.readTree(input), type);
            } catch (final JsonException e) {
                outcome = "error";
            }
            if (ENUM_BY_INDEX.contains(input)) {
                departures++;
                assertThat(expected).as(input).startsWith("ok ");
                assertThat(outcome).as(input).isEqualTo("error");
            } else if (!(expected.startsWith("error ") ? outcome.equals("error") : outcome.equals(expected))) {
                differ.add(c.path("type").asString() + ' ' + input + "\n  before: " + expected + "\n  now:    "
                        + outcome);
            }
        }
        assertThat(differ).as(String.join("\n", differ)).isEmpty();
        assertThat(departures).isEqualTo(ENUM_BY_INDEX.size());
        assertThat(cases.size()).isGreaterThan(90);
    }

    /// A class name outside comando's core types is refused before any class is looked up by it.
    @Test
    void theLogReadsNoClassOutsideTheCore() {
        final JsonNode outside = Json.readTree("{\"@type\":\"java.lang.ProcessBuilder\",\"command\":[\"rm\"]}");
        assertThatThrownBy(() -> IntentLog.BINDER.fromTree(outside, ViewSpec.class))
                .isInstanceOf(JsonException.class);
    }

    /// The real files under `COMANDO_REAL_JSON`, copies of a data root's store files, read as the earlier mappers read
    /// them: each `<key>.json` gives the `toString` in `<key>.tostring.txt` beside it. Off unless the variable names
    /// the copies' folder; it never reads a data root itself.
    @Test
    @EnabledIfEnvironmentVariable(named = "COMANDO_REAL_JSON", matches = ".+")
    void realFilesReadAsBefore() throws IOException {
        final Path real = Path.of(System.getenv("COMANDO_REAL_JSON"));
        int read = 0;
        read += real(real, "connections", new TypeRef<List<ConnectionSpec>>() {
        });
        read += real(real, "sessions", new TypeRef<List<SessionSpec>>() {
        });
        read += real(real, "folders", new TypeRef<List<FolderRoot>>() {
        });
        read += real(real, "panels", new TypeRef<List<Store.StoredPanel>>() {
        });
        assertThat(read).isPositive();
    }

    private static final Map<String, Type> TYPES = types();

    private static Map<String, Type> types() {
        final Map<String, Type> t = new LinkedHashMap<>();
        t.put("ConnectionSpec", ConnectionSpec.class);
        t.put("SessionSpec", SessionSpec.class);
        t.put("FolderRoot", FolderRoot.class);
        t.put("StoredPanel", Store.StoredPanel.class);
        t.put("QuestDbEntry", MachineQuestDb.Entry.class);
        t.put("EdgeEntry", EmbeddedEdge.Entry.class);
        t.put("ListFolderRoot", new TypeRef<List<FolderRoot>>() {
        }.type());
        t.put("Mixed", JsonSamples.Mixed.class);
        t.put("Typed", JsonSamples.Typed.class);
        t.put("ViewSpec", ViewSpec.class);
        t.put("ApprovalDecision", ApprovalDecision.class);
        t.put("AppState", AppState.class);
        assertThat(t.get("ListFolderRoot")).isInstanceOf(ParameterizedType.class);
        return t;
    }

    private static <T> void store(final Path dir, final String key, final T value, final TypeRef<T> type)
            throws IOException {
        final Path file = dir.resolve(key + ".json");
        new JsonFile<>(file, key, 1, type).write(value);
        assertThat(Files.readAllBytes(file)).as(key).isEqualTo(bytes(key + ".json"));
        final Path golden = Files.write(dir.resolve("golden-" + key + ".json"), bytes(key + ".json"));
        assertThat(new JsonFile<>(golden, key, 1, type).readAsIs()).as(key).isEqualTo(value);
    }

    private static void pretty(final String name, final Object value, final Class<?> type) throws IOException {
        assertThat(JsonFile.pretty(JsonFile.BINDER.toTree(value))).as(name).isEqualTo(bytes(name));
        assertThat(JsonFile.BINDER.fromTree(Json.readTree(bytes(name)), type)).as(name).isEqualTo(value);
    }

    private static void compact(final String name, final Object value, final Class<?> type) throws IOException {
        assertThat(Json.write(IntentLog.BINDER.toTree(value))).as(name).isEqualTo(text(name));
        assertThat(IntentLog.BINDER.fromTree(Json.readTree(text(name)), type)).as(name).isEqualTo(value);
    }

    private static <T> int real(final Path dir, final String key, final TypeRef<T> type) throws IOException {
        final Path file = dir.resolve(key + ".json");
        if (!Files.exists(file)) {
            return 0;
        }
        final String before = Files.readString(dir.resolve(key + ".tostring.txt"), StandardCharsets.UTF_8);
        assertThat(String.valueOf(new JsonFile<>(file, key, 1, type).readAsIs())).as(key).isEqualTo(before);
        return 1;
    }

    private static byte[] bytes(final String name) throws IOException {
        try (final InputStream in = JsonGoldenTest.class.getResourceAsStream(GOLDEN + name)) {
            assertThat(in).as(name).isNotNull();
            return in.readAllBytes();
        }
    }

    private static String text(final String name) throws IOException {
        return new String(bytes(name), StandardCharsets.UTF_8);
    }
}
