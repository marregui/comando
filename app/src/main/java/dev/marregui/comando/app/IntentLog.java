package dev.marregui.comando.app;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.model.ApprovalDecision;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.view.ViewSpec;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.node.ObjectNode;

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

    static final ObjectMapper JSON = JsonFile.filed()
            .changeDefaultVisibility(v -> v.withVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE)
                    .withVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY))
            .addMixIn(ViewSpec.class, Typed.class)
            .addMixIn(ApprovalDecision.class, Typed.class)
            .polymorphicTypeValidator(BasicPolymorphicTypeValidator.builder().allowIfSubType("dev.marregui.comando.core.").build())
            .build();

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

    /**
     * Blanks every {@code password} field: a log is for sharing a defect, not the user's credentials. A text field that
     * holds JSON (an approval card's tool input) is read, blanked the same way and written back, so a password inside
     * it does not reach the log either.
     */
    static JsonNode redact(final JsonNode node) {
        if (node instanceof final ObjectNode o) {
            if (o.has("password")) {
                o.put("password", "");
            }
            for (final String name : List.copyOf(o.propertyNames())) {
                final JsonNode value = o.get(name);
                if (value.isString() && value.asString().contains("password")) {
                    o.put(name, redactText(value.asString()));
                } else {
                    redact(value);
                }
            }
        } else if (node.isArray()) {
            node.forEach(IntentLog::redact);
        }
        return node;
    }

    /** {@code text} blanked as {@link #redact} does when it is a JSON object or array; else unchanged. */
    private static String redactText(final String text) {
        try {
            final JsonNode inner = JSON.readTree(text);
            return inner.isContainer() ? JSON.writeValueAsString(redact(inner)) : text;
        } catch (final RuntimeException e) {
            return text; // not JSON: free text keeps its words
        }
    }

    @Override
    public void observe(Intent intent) {
        try {
            out.write(line(intent));
            out.write('\n');
            // to the operating system at once: the log is for diagnosing a crash or a kill, which loses a buffer
            out.flush();
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot write the intent log [type=" + intent.getClass().getSimpleName() + ']', e);
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
            JsonNode n = tree(line);
            if (n.path("type").asString("").equals(STATE)) {
                try {
                    initial = JSON.treeToValue(n.path("state"), AppState.class);
                } catch (JacksonException e) {
                    throw new IOException(e.getMessage(), e);
                }
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
        JsonNode n = tree(line);
        String type = n.path("type").asString("");
        Class<?> c;
        try {
            c = Class.forName(Intent.class.getName() + "$" + type);
        } catch (ClassNotFoundException e) {
            throw new IOException("unknown intent type " + type, e);
        }
        try {
            return (Intent) JSON.treeToValue(n.path("intent"), c);
        } catch (JacksonException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** {@code line} parsed; Jackson's unchecked parse error as the {@code IOException} a reader of a log handles. */
    private static JsonNode tree(String line) throws IOException {
        try {
            return JSON.readTree(line);
        } catch (JacksonException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    @Override
    public void close() throws IOException {
        out.close();
    }
}
