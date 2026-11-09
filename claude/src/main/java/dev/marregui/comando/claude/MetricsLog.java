package dev.marregui.comando.claude;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import dev.marregui.comando.core.files.Mapped;
import dev.marregui.comando.core.files.OwnerOnly;
import dev.marregui.comando.core.loop.Queues;

/**
 * What the sessions spend and how their work ends, kept to calibrate who does what (record 110): one JSON object a
 * line, {@code metrics-<UTC date>.jsonl} in the data root's {@code metrics} folder, appended whole. Each object has
 * {@code ts} (UTC, ISO 8601), {@code event} ({@code turn}, {@code agent}, {@code usage}, {@code queues}) and the
 * event's fields.
 * Written from the sessions' strands and the plan-usage strand; one line a write, under a lock, so lines never
 * interleave. Money fields are kept as they arrive and shown nowhere.
 * <p>
 * Built with a {@link QuestDbEndpoint}, each event written also goes to the machine's QuestDB (record 146) through a
 * {@link MetricsSink}, which never holds up a write; the file stays the record. {@link #close} ends the sink.
 */
public final class MetricsLog implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(MetricsLog.class.getName());

    /** Keeps nothing: tests, and a window without a data root. */
    public static final MetricsLog NONE = new MetricsLog(null, new ObjectMapper(), System::currentTimeMillis);

    /** How long {@link #lastContext} waits for QuestDB before it reads the files: the contract's bound (record 146). */
    static final Duration CONTEXT_QUERY_WITHIN = Duration.ofSeconds(2);

    /** A conversation id as Claude Code makes them; nothing else is put into a query. */
    private static final Pattern CONVERSATION = Pattern.compile("[0-9a-f-]{8,64}");

    private final Path dir;
    private final ObjectMapper json;
    private final LongSupplier clock;
    private final QuestDbEndpoint qdb;
    private final MetricsSink sink;

    /** Files only. */
    public MetricsLog(final Path dir) {
        this(dir, new ObjectMapper(), System::currentTimeMillis, null);
    }

    /** Files, and the machine's QuestDB at {@code qdb}; starts the sink's errand, which {@link #close} ends. */
    public MetricsLog(final Path dir, final QuestDbEndpoint qdb) {
        this(dir, new ObjectMapper(), System::currentTimeMillis, qdb);
    }

    MetricsLog(final Path dir, final ObjectMapper json, final LongSupplier clock) {
        this(dir, json, clock, null);
    }

    MetricsLog(final Path dir, final ObjectMapper json, final LongSupplier clock, final QuestDbEndpoint qdb) {
        this.dir = dir;
        this.json = json;
        this.clock = clock;
        this.qdb = dir == null ? null : qdb;
        this.sink = this.qdb == null ? null : new MetricsSink(dir, this.qdb, new MetricsLines(json, instance(dir)), json,
                clock, System::nanoTime);
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
     * The context and its window the last {@code turn} of conversation {@code id} ended with (record 120): QuestDB's
     * answer when it has one, else the files', newest first, within {@link #CONTEXT_DAYS}; empty when none is kept.
     * Waits up to {@link #CONTEXT_QUERY_WITHIN} on QuestDB, then reads files: off the EDT.
     */
    Optional<long[]> lastContext(final String id) {
        if (dir == null || id == null || id.isEmpty()) {
            return Optional.empty();
        }
        final Optional<long[]> kept = lastContextKept(id);
        if (kept.isPresent() || !Files.isDirectory(dir)) {
            return kept;
        }
        LocalDate day = LocalDate.ofInstant(Instant.ofEpochMilli(clock.getAsLong()), ZoneOffset.UTC);
        for (int i = 0; i < CONTEXT_DAYS; i++, day = day.minusDays(1)) {
            final Path file = dir.resolve("metrics-" + day + ".jsonl");
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                // the bytes only skip lines that cannot be the one; the event's own fields decide
                final List<String> lines = Mapped.linesWith(file,
                        id.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
                for (final String line : lines.reversed()) {
                    final JsonNode e = json.readTree(line);
                    if (e.path("event").asString("").equals("turn") && e.path("conversation").asString("").equals(id)) {
                        final long context = e.path("context").asLong(-1);
                        if (context > 0) {
                            return Optional.of(new long[]{context, e.path("window").asLong(0)});
                        }
                    }
                }
            } catch (final IOException | RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "cannot read metrics [file=" + file + ']', e);
            }
        }
        return Optional.empty();
    }

    /**
     * A {@code queues} event: each unbounded queue's depth when read and its peak since the reading before
     * ({@link Queues}), so each can be bounded later from how it is used.
     */
    public void queues(final List<Queues.Reading> readings) {
        final ObjectNode e = event("queues");
        final ArrayNode list = e.putArray("queues");
        for (final Queues.Reading r : readings) {
            list.addObject().put("name", r.name()).put("depth", r.depth()).put("peak", r.peak());
        }
        write(e);
    }

    /**
     * Appends {@code event} as one line to today's file, then hands it to the sink; a failure is logged, never thrown,
     * and the sink never blocks. {@code event} must not change after.
     */
    synchronized void write(final ObjectNode event) {
        if (dir == null) {
            return;
        }
        final Path file = dir.resolve("metrics-" + LocalDate.ofInstant(Instant.ofEpochMilli(clock.getAsLong()), ZoneOffset.UTC) + ".jsonl");
        try {
            // owner-only (rule 15), also when the folder was deleted while comando runs
            if (!Files.isDirectory(dir)) {
                OwnerOnly.folder(dir);
            }
            Files.writeString(file, json.writeValueAsString(event) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (final IOException | JacksonException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot write metrics [file=" + file + ']', e);
        }
        if (sink != null) {
            sink.offer(event);
        }
    }

    /** Ends the sink, letting it send what is queued for a moment; idempotent. The files need no closing. */
    @Override
    public void close() {
        if (sink != null) {
            sink.close();
        }
    }

    /** The data root the metrics folder sits in, as the rows name their instance (record 146). */
    private static String instance(final Path dir) {
        final Path absolute = dir.toAbsolutePath();
        final Path root = absolute.getParent();
        return (root == null ? absolute : root).toString();
    }

    /** QuestDB's answer to {@link #lastContext}, empty when it has none or cannot be asked now. */
    private Optional<long[]> lastContextKept(final String id) {
        if (sink == null || !CONVERSATION.matcher(id).matches()) {
            return Optional.empty();
        }
        final Optional<URI> base = qdb.live();
        if (base.isEmpty()) {
            return Optional.empty();
        }
        try {
            final JsonNode row = sink.exec(base.get(), "SELECT context, context_window FROM comando_turn"
                    + " WHERE conversation = '" + id + "' AND context > 0 ORDER BY ts DESC LIMIT 1",
                    CONTEXT_QUERY_WITHIN).path("dataset").path(0);
            final long context = row.path(0).asLong(-1);
            if (context > 0) {
                return Optional.of(new long[]{context, row.path(1).asLong(0)});
            }
        } catch (final IOException | RuntimeException e) {
            // handled: the files answer instead
            LOG.log(System.Logger.Level.DEBUG, "QuestDB did not answer, the files do [conversation=" + id + ", cause="
                    + e + ']');
        } catch (final InterruptedException _) {
            Thread.currentThread().interrupt();
        }
        return Optional.empty();
    }
}
