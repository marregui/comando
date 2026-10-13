package dev.marregui.comando.app;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.model.ApprovalDecision;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.view.ViewSpec;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes every intent, in order, as one JSON line with its time and type: the record of a session for
 * diagnosing a UI defect, and the input of replay tests ({@link #read}). Enabled with
 * {@code -Dcomando.recordIntents=true}; runs on the reducer thread.
 */
final class IntentLog implements StateLoop.IntentObserver, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(IntentLog.class.getName());

    /** Intent fields typed by an interface carry their record's class name. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@type")
    private interface Typed {
    }

    static final ObjectMapper JSON = new ObjectMapper()
            .setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE)
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .addMixIn(ViewSpec.class, Typed.class)
            .addMixIn(ApprovalDecision.class, Typed.class)
            .setPolymorphicTypeValidator(BasicPolymorphicTypeValidator.builder().allowIfSubType("dev.marregui.comando.core.").build());

    private final BufferedWriter out;

    /** A log: the state the intents start from, and the intents. */
    record Replay(AppState initial, List<Intent> intents) {
    }

    /** Opens a log whose first line is {@code initial}, the state the logged intents apply to. */
    IntentLog(Path dir, AppState initial) {
        try {
            Files.createDirectories(dir);
            out = Files.newBufferedWriter(dir.resolve("intents-" + Instant.now().toEpochMilli() + ".jsonl"), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
            ObjectNode n = JSON.createObjectNode();
            n.put("at", Instant.now().toString());
            n.put("type", STATE);
            n.set("state", redact(JSON.valueToTree(initial)));
            out.write(JSON.writeValueAsString(n));
            out.write('\n');
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open the intent log in " + dir, e);
        }
    }

    static final String STATE = "@state";

    static String line(Intent intent) throws IOException {
        ObjectNode n = JSON.createObjectNode();
        n.put("at", Instant.now().toString());
        n.put("type", intent.getClass().getSimpleName());
        n.set("intent", redact(JSON.valueToTree(intent)));
        return JSON.writeValueAsString(n);
    }

    /** Blanks every {@code password} field: a log is for sharing a defect, not the user's credentials. */
    static JsonNode redact(JsonNode node) {
        if (node instanceof ObjectNode o) {
            if (o.has("password")) {
                o.put("password", "");
            }
            o.properties().forEach(e -> redact(e.getValue()));
        } else if (node.isArray()) {
            node.forEach(IntentLog::redact);
        }
        return node;
    }

    @Override
    public void observe(Intent intent) {
        try {
            out.write(line(intent));
            out.write('\n');
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "intent log write failed [type=" + intent.getClass().getSimpleName() + "]: " + e.getMessage());
        }
    }

    /** The starting state and the intents of a log, in order. */
    static Replay read(Path file) throws IOException {
        AppState initial = null;
        List<Intent> out = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode n = JSON.readTree(line);
            if (n.path("type").asText().equals(STATE)) {
                initial = JSON.treeToValue(n.path("state"), AppState.class);
            } else {
                out.add(parse(line));
            }
        }
        if (initial == null) {
            throw new IOException("the log has no starting state: " + file);
        }
        return new Replay(initial, out);
    }

    static Intent parse(String line) throws IOException {
        JsonNode n = JSON.readTree(line);
        String type = n.path("type").asText();
        Class<?> c;
        try {
            c = Class.forName(Intent.class.getName() + "$" + type);
        } catch (ClassNotFoundException e) {
            throw new IOException("unknown intent type " + type, e);
        }
        return (Intent) JSON.treeToValue(n.path("intent"), c);
    }

    @Override
    public void close() throws IOException {
        out.close();
    }
}
