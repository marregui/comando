package dev.marregui.comando.claude;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.function.LongSupplier;

/**
 * What the sessions spend and how their work ends, kept to calibrate who does what (record 110): one JSON object a
 * line, {@code metrics-<UTC date>.jsonl} in the data root's {@code metrics} folder, appended whole. Each object has
 * {@code ts} (UTC, ISO 8601), {@code event} ({@code turn}, {@code agent}, {@code usage}) and the event's fields. Written
 * from the sessions' strands and the plan-usage strand; one line a write, under a lock, so lines never interleave.
 * Money fields are kept as they arrive and shown nowhere.
 */
public final class MetricsLog {

    private static final System.Logger LOG = System.getLogger(MetricsLog.class.getName());

    /** Keeps nothing: tests, and a window without a data root. */
    public static final MetricsLog NONE = new MetricsLog(null, new ObjectMapper(), System::currentTimeMillis);

    private final Path dir;
    private final ObjectMapper json;
    private final LongSupplier clock;

    public MetricsLog(Path dir) {
        this(dir, new ObjectMapper(), System::currentTimeMillis);
    }

    MetricsLog(Path dir, ObjectMapper json, LongSupplier clock) {
        this.dir = dir;
        this.json = json;
        this.clock = clock;
    }

    /** A new event of {@code type}, stamped now; {@link #write} keeps it. */
    ObjectNode event(String type) {
        ObjectNode e = json.createObjectNode();
        e.put("ts", Instant.ofEpochMilli(clock.getAsLong()).toString());
        e.put("event", type);
        return e;
    }

    /** The current time, as the events are stamped. */
    long now() {
        return clock.getAsLong();
    }

    /** Appends {@code event} as one line to today's file; a failure is logged, never thrown. */
    synchronized void write(ObjectNode event) {
        if (dir == null) {
            return;
        }
        Path file = dir.resolve("metrics-" + LocalDate.ofInstant(Instant.ofEpochMilli(clock.getAsLong()), ZoneOffset.UTC) + ".jsonl");
        try {
            Files.createDirectories(dir);
            Files.writeString(file, json.writeValueAsString(event) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot write metrics [file=" + file + "]: " + e.getMessage());
        }
    }
}
