package dev.marregui.comando.claude;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the sessions spend and how their work ends, kept to calibrate who does what (record 110): one JSON object a
 * line, {@code metrics-<UTC date>.jsonl} in the data root's {@code metrics} folder, appended whole. Each object has
 * {@code ts} (UTC, ISO 8601), {@code event} ({@code turn}, {@code agent}, {@code usage}) and the event's fields.
 * Written from the sessions' strands and the plan-usage strand; one line a write, under a lock, so lines never
 * interleave. Money fields are kept as they arrive and shown nowhere.
 */
public final class MetricsLog {

    private static final System.Logger LOG = System.getLogger(MetricsLog.class.getName());

    /** Keeps nothing: tests, and a window without a data root. */
    public static final MetricsLog NONE = new MetricsLog(null, new ObjectMapper(), System::currentTimeMillis);

    private final Path dir;
    private final ObjectMapper json;
    private final LongSupplier clock;

    public MetricsLog(final Path dir) {
        this(dir, new ObjectMapper(), System::currentTimeMillis);
    }

    MetricsLog(final Path dir, final ObjectMapper json, final LongSupplier clock) {
        this.dir = dir;
        this.json = json;
        this.clock = clock;
    }

    /** A new event of {@code type}, stamped now; {@link #write} keeps it. */
    ObjectNode event(final String type) {
        final ObjectNode e = json.createObjectNode();
        e.put("ts", Instant.ofEpochMilli(clock.getAsLong()).toString());
        e.put("event", type);
        return e;
    }

    /** The current time, as the events are stamped. */
    long now() {
        return clock.getAsLong();
    }

    /** Days of files {@link #lastContext} looks back through. */
    static final int CONTEXT_DAYS = 14;

    /**
     * The context and its window the last {@code turn} of conversation {@code id} ended with, newest file first, within
     * {@link #CONTEXT_DAYS}; empty when none is kept (record 120). Reads files: off the EDT.
     */
    Optional<long[]> lastContext(final String id) {
        if (dir == null || id == null || id.isEmpty() || !Files.isDirectory(dir)) {
            return Optional.empty();
        }
        LocalDate day = LocalDate.ofInstant(Instant.ofEpochMilli(clock.getAsLong()), ZoneOffset.UTC);
        final String needle = "\"conversation\":\"" + id + "\"";
        for (int i = 0; i < CONTEXT_DAYS; i++, day = day.minusDays(1)) {
            final Path file = dir.resolve("metrics-" + day + ".jsonl");
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int j = lines.size() - 1; j >= 0; j--) {
                    final String line = lines.get(j);
                    if (line.contains(needle) && line.contains("\"event\":\"turn\"")) {
                        final JsonNode e = json.readTree(line);
                        final long context = e.path("context").asLong(-1);
                        if (context > 0) {
                            return Optional.of(new long[]{context, e.path("window").asLong(0)});
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "cannot read metrics [file=" + file + ']', e);
            }
        }
        return Optional.empty();
    }

    /** Appends {@code event} as one line to today's file; a failure is logged, never thrown. */
    synchronized void write(final ObjectNode event) {
        if (dir == null) {
            return;
        }
        final Path file = dir.resolve("metrics-" + LocalDate.ofInstant(Instant.ofEpochMilli(clock.getAsLong()), ZoneOffset.UTC) + ".jsonl");
        try {
            Files.createDirectories(dir);
            Files.writeString(file, json.writeValueAsString(event) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | JacksonException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot write metrics [file=" + file + ']', e);
        }
    }
}
