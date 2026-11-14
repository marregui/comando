package dev.marregui.comando.app;

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
import java.util.function.IntSupplier;

import dev.marregui.comando.core.files.Mapped;
import dev.marregui.comando.core.files.OwnerOnly;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.JsonException;
import dev.marregui.comando.core.json.JsonNode;
import dev.marregui.comando.core.json.ObjectNode;
import dev.marregui.comando.core.json.bind.Binder;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.model.ApprovalDecision;
import dev.marregui.comando.core.port.Clock;
import dev.marregui.comando.core.reduce.Reducer;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.view.ViewSpec;

/**
 * Writes every intent, in order, as one JSON line with its time and type: the record of a session for
 * diagnosing a UI defect, and the input of replay tests ({@link #read}). The first line holds the starting state, the
 * log's schema {@code version} (record 133) and the checkpoint threshold the reducer reads; a change of the threshold
 * is a line of its own before the next intent, so the log says which threshold each intent was applied under.
 * Enabled with {@code -Dcomando.recordIntents=true}; runs on the reducer thread.
 */
final class IntentLog implements StateLoop.IntentObserver, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(IntentLog.class.getName());

    /**
     * Records by their fields, so an accessor that hides a value from the screen does not hide it from the log;
     * values typed by an interface carry their record's class name, read back only from comando's own core types.
     */
    static final Binder BINDER = Binder.builder()
            .fields()
            .typed(ViewSpec.class, ApprovalDecision.class)
            .allowPrefix("dev.marregui.comando.core.")
            .build();

    private final BufferedWriter out;
    /** The checkpoint threshold in force, in percent of the context window, as the window's setting gives it. */
    private final IntSupplier threshold;
    /** The threshold last written; the reducer thread's own. */
    private int thresholdWritten;
    /**
     * The threshold read for the intent observed last, once: the value logged for it and the value the reducer applies
     * it under ({@link #applied}), so a change of setting between the two cannot make a replay diverge from the run.
     * Written and read on the reducer thread, observers running before the reducer.
     */
    private int applied;

    /**
     * A log: the state the intents start from, the intents, and the checkpoint threshold in force as each was applied
     * ({@code thresholds.get(i)} for {@code intents.get(i)}; 0 for a log written before the threshold was recorded, which
     * {@link #loop} replays with a reducer that never checkpoints).
     */
    record Replay(AppState initial, List<Intent> intents, List<Integer> thresholds) {
        Replay {
            intents = List.copyOf(intents);
            thresholds = List.copyOf(thresholds);
        }

        /** The threshold in force as the first intent was applied; 0 when the log has none. */
        int threshold() {
            return thresholds.isEmpty() ? 0 : thresholds.getFirst();
        }

        /**
         * A loop that starts from {@link #initial} and applies each of {@link #intents}, dispatched to it, under the
         * threshold logged for that intent, as the run applied it; an intent that is not one of the log's (the window's
         * own, in a test that drives it after the replay) keeps the threshold in force. A log that recorded no threshold
         * (every log written before it was) replays with a reducer that never checkpoints.
         */
        StateLoop loop(final Clock clock, final StateLoop.EffectHandler effects) {
            if (threshold() <= 0) {
                return new StateLoop(initial, new Reducer(clock), effects);
            }
            final Thresholds inForce = new Thresholds(intents, thresholds);
            final StateLoop loop = new StateLoop(initial, new Reducer(clock, inForce::percent), effects);
            loop.addObserver(inForce);
            return loop;
        }
    }

    /**
     * The threshold a replay's reducer reads: it moves to the one logged for each of the log's intents as the loop is
     * about to apply it (observers see an intent before the reducer does). The log's intents are told apart by
     * identity, so an equal intent the window sends does not advance it. The reducer thread's own.
     */
    private static final class Thresholds implements StateLoop.IntentObserver {

        private final List<Intent> intents;
        private final List<Integer> thresholds;
        /** The index of the log's next intent. */
        private int next;
        private int percent;

        Thresholds(final List<Intent> intents, final List<Integer> thresholds) {
            this.intents = intents;
            this.thresholds = thresholds;
            this.percent = thresholds.getFirst();
        }

        int percent() {
            return percent;
        }

        @Override
        public void observe(final Intent intent) {
            if (next < intents.size() && intents.get(next) == intent) {
                percent = thresholds.get(next);
                next++;
            }
        }
    }

    /** The log's schema version (record 133); a log without one is version 1. */
    static final int VERSION = 1;
    static final String THRESHOLD = "@threshold";

    /**
     * Opens a log whose first line is {@code initial}, the state the logged intents apply to, with the checkpoint
     * threshold {@code threshold} gives now; a later change is logged before the next intent.
     */
    IntentLog(final Path dir, final AppState initial, final IntSupplier threshold) {
        this.threshold = threshold;
        this.thresholdWritten = threshold.getAsInt();
        this.applied = thresholdWritten;
        try {
            OwnerOnly.folder(dir);
            out = Files.newBufferedWriter(dir.resolve("intents-" + Instant.now().toEpochMilli() + ".jsonl"), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
            final ObjectNode n = Json.object();
            n.put("at", Instant.now().toString());
            n.put("type", STATE);
            n.put("version", VERSION);
            n.put("checkpointThreshold", thresholdWritten);
            n.set("state", redact(BINDER.toTree(initial)));
            out.write(Json.write(n));
            out.write('\n');
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot open the intent log in " + dir, e);
        }
    }

    static final String STATE = "@state";

    static String line(final Intent intent) throws IOException {
        final ObjectNode n = Json.object();
        n.put("at", Instant.now().toString());
        n.put("type", intent.getClass().getSimpleName());
        n.set("intent", redact(BINDER.toTree(intent)));
        return Json.write(n);
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
            final JsonNode inner = Json.readFirst(text);
            return inner.isContainer() ? Json.write(redact(inner)) : text;
        } catch (final RuntimeException e) {
            return text; // not JSON: free text keeps its words
        }
    }

    /**
     * The threshold the intent observed last is applied under, as it was logged for it: the reducer's, which reads it
     * after the observers.
     */
    IntSupplier applied() {
        return () -> applied;
    }

    @Override
    public void observe(final Intent intent) {
        final int now = threshold.getAsInt();
        applied = now;
        try {
            if (now != thresholdWritten) {
                final ObjectNode t = Json.object();
                t.put("at", Instant.now().toString());
                t.put("type", THRESHOLD);
                t.put("percent", now);
                out.write(Json.write(t));
                out.write('\n');
                thresholdWritten = now;
            }
            out.write(line(intent));
            out.write('\n');
            // to the operating system at once: the log is for diagnosing a crash or a kill, which loses a buffer
            out.flush();
        } catch (final IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot write the intent log [type=" + intent.getClass().getSimpleName() + ']', e);
        }
    }

    /** The starting state, the intents of a log in order, and the threshold each was applied under. */
    static Replay read(final Path file) throws IOException {
        AppState initial = null;
        int inForce = 0;
        final List<Intent> out = new ArrayList<>();
        final List<Integer> thresholds = new ArrayList<>();
        for (final String line : Mapped.lines(file)) {
            if (line.isBlank()) {
                continue;
            }
            final JsonNode n = tree(line);
            final String type = n.path("type").asString("");
            if (type.equals(STATE)) {
                if (n.path("version").asInt(1) > VERSION) {
                    throw new IOException("the log is of version " + n.path("version") + ", written by a later comando; this one reads version "
                            + VERSION + ": " + file);
                }
                inForce = n.path("checkpointThreshold").asInt(0);
                try {
                    initial = BINDER.fromTree(n.path("state"), AppState.class);
                } catch (final JsonException e) {
                    throw new IOException(e.getMessage(), e);
                }
            } else if (type.equals(THRESHOLD)) {
                inForce = n.path("percent").asInt(0);
            } else {
                out.add(parse(line));
                thresholds.add(inForce);
            }
        }
        if (initial == null) {
            throw new IOException("the log has no starting state: " + file);
        }
        return new Replay(initial, out, thresholds);
    }

    static Intent parse(final String line) throws IOException {
        final JsonNode n = tree(line);
        final String type = n.path("type").asString("");
        Class<?> c;
        try {
            c = Class.forName(Intent.class.getName() + "$" + type);
        } catch (final ClassNotFoundException e) {
            throw new IOException("unknown intent type " + type, e);
        }
        try {
            return (Intent) BINDER.fromTree(n.path("intent"), c);
        } catch (final JsonException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** {@code line} parsed; its unchecked parse error as the {@code IOException} a reader of a log handles. */
    private static JsonNode tree(final String line) throws IOException {
        try {
            return Json.readFirst(line);
        } catch (final JsonException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    @Override
    public void close() throws IOException {
        out.close();
    }
}
