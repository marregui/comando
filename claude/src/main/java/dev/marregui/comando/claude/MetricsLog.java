package dev.marregui.comando.claude;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import dev.marregui.comando.core.loop.Queues;
import dev.marregui.comando.core.port.IntentSink;

/**
 * What the sessions spend and how their work ends, kept to calibrate who does what (record 110) in the machine's
 * QuestDB alone (records 146, 149). Each event is a JSON object with {@code ts} (UTC, ISO 8601), {@code event}
 * ({@code turn}, {@code agent}, {@code usage}, {@code queues}) and the event's fields, handed to a {@link MetricsSink},
 * which never holds up a write and keeps nothing on disk: an event QuestDB does not take is lost, and the sink says so
 * in a notice once an outage. Written from the sessions' strands and the plan-usage strand. Money fields are kept as
 * they arrive and shown nowhere. {@link #close} ends the sink.
 */
public final class MetricsLog implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(MetricsLog.class.getName());

    /** Keeps nothing: tests, comando run with its QuestDB left out, and a window without a data root. */
    public static final MetricsLog NONE =
            new MetricsLog(new ObjectMapper(), System::currentTimeMillis, null, null, null);

    /** How long {@link #lastContext} waits for QuestDB: the contract's bound (record 146). */
    static final Duration CONTEXT_QUERY_WITHIN = Duration.ofSeconds(2);

    /** A conversation id as Claude Code makes them; nothing else is put into a query. */
    private static final Pattern CONVERSATION = Pattern.compile("[0-9a-f-]{8,64}");

    private final ObjectMapper json;
    private final LongSupplier clock;
    private final QuestDbEndpoint qdb;
    private final MetricsSink sink;

    /**
     * Events of the comando instance whose data root is {@code instance}, an absolute path, sent to the machine's
     * QuestDB at {@code qdb}; a write QuestDB does not take is told to {@code notices}. Starts the sink's errand, which
     * {@link #close} ends.
     */
    public MetricsLog(final String instance, final QuestDbEndpoint qdb, final IntentSink notices) {
        this(new ObjectMapper(), System::currentTimeMillis, instance, qdb, notices);
    }

    /** As the public constructor, stamped by {@code clock}; with no {@code qdb}, keeps nothing, as {@link #NONE}. */
    MetricsLog(
            final ObjectMapper json,
            final LongSupplier clock,
            final String instance,
            final QuestDbEndpoint qdb,
            final IntentSink notices
    ) {
        this.json = json;
        this.clock = clock;
        this.qdb = qdb;
        this.sink = qdb == null ? null : new MetricsSink(qdb, new MetricsLines(json, instance), json, System::nanoTime,
                notices);
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

    /**
     * The context and its window the last {@code turn} of conversation {@code id} ended with (record 120), as QuestDB
     * keeps it; empty when it has none, when no server runs or answers within {@link #CONTEXT_QUERY_WITHIN}, and for a
     * log that keeps nothing. Waits on QuestDB: off the EDT.
     */
    Optional<long[]> lastContext(final String id) {
        if (sink == null || id == null || !CONVERSATION.matcher(id).matches()) {
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
            // handled: the session bar shows no context until the first turn reports one
            LOG.log(System.Logger.Level.DEBUG, "QuestDB did not answer, no context kept [conversation=" + id
                    + ", cause=" + e + ']');
        } catch (final InterruptedException _) {
            Thread.currentThread().interrupt();
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

    /** Hands {@code event} to the sink, which never blocks; {@code event} must not change after. */
    void write(final ObjectNode event) {
        if (sink != null) {
            sink.offer(event);
        }
    }

    /** Ends the sink, letting it send what is queued for a moment; idempotent. */
    @Override
    public void close() {
        if (sink != null) {
            sink.close();
        }
    }
}
