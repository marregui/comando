package dev.marregui.comando.claude;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import org.jetbrains.annotations.TestOnly;

import dev.marregui.comando.core.files.Mapped;
import dev.marregui.comando.core.json.ArrayNode;
import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.JsonNode;
import dev.marregui.comando.core.json.ObjectNode;
import dev.marregui.comando.core.loop.Queues;
import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.port.WaitLog;

/**
 * What the sessions spend and how their work ends, kept to calibrate who does what (record 110) in the machine's
 * QuestDB alone (records 146, 149). Each event is a JSON object with {@code ts} (UTC, ISO 8601), {@code event}
 * ({@code turn}, {@code agent}, {@code usage}, {@code queues}, {@code wait}) and the event's fields, handed to a
 * {@link MetricsSink}, which never holds up a write and keeps nothing on disk: an event QuestDB does not take is lost,
 * and the sink says so in a notice once an outage. Written from the sessions' strands, the plan-usage strand and the
 * MCP request threads. Money fields are kept as they arrive and shown nowhere. {@link #close} ends the sink.
 */
public final class MetricsLog implements AutoCloseable, WaitLog {

    private static final System.Logger LOG = System.getLogger(MetricsLog.class.getName());

    /** Keeps nothing: tests, comando run with its QuestDB left out, and a window without a data root. */
    public static final MetricsLog NONE =
            new MetricsLog(System::currentTimeMillis, null, null, null);

    /**
     * How long the query of {@link #lastContext} waits for QuestDB, its connect included: the contract's bound (record
     * 146). The live check before it is the endpoint's and waits on its own bound.
     */
    static final Duration CONTEXT_QUERY_WITHIN = Duration.ofSeconds(2);

    /** The setting that moves the machine's QuestDB folder, as the kit reads it. */
    private static final String QDB_HOME_ENV = "KIT_QDB_HOME";

    /** The file of the machine's QuestDB folder whose first line is the shadow server's base URL. */
    private static final String SHADOW_FILE = "shadow";

    /** The machine's QuestDB folder under the user's home, where its server runs (record 146). */
    private static final String MACHINE_FOLDER = ".comando-questdb";

    /** The most of {@link #SHADOW_FILE} read: a base URL is a few dozen characters, so a larger file is no switch. */
    private static final int SHADOW_FILE_BYTES = 1024;

    /** A conversation id as Claude Code makes them; nothing else is put into a query. */
    private static final Pattern CONVERSATION = Pattern.compile("[0-9a-f-]{8,64}");

    private final LongSupplier clock;
    private final QuestDbEndpoint qdb;
    private final MetricsSink sink;
    /**
     * What {@link #queues} last wrote for each queue name. Touched only by the {@code kit-check} strand of the one
     * {@code App} that owns this log, the one caller of {@link #queues}, so it needs no lock; {@link #NONE}, shared,
     * never touches it.
     */
    private final Map<String, Queues.Reading> queuesWritten = new HashMap<>();

    /**
     * Events of the comando instance whose data root is {@code instance}, an absolute path, sent to the machine's
     * QuestDB at {@code qdb}; a write QuestDB does not take is told to {@code notices}. Starts the sink's errand, which
     * {@link #close} ends. Each batch also goes to {@code shadow}, a base URL, when it is one: the caller reads it with
     * {@link #machineShadow} where the app builds its log, so that no test of the sink or the log inherits it from the
     * shell or the machine's folder.
     */
    public MetricsLog(
            final String instance,
            final QuestDbEndpoint qdb,
            final IntentSink notices,
            final String shadow
    ) {
        this(System::currentTimeMillis, instance, qdb, notices, shadow);
    }

    /** As the public constructor, stamped by {@code clock}, with no shadow; with no {@code qdb}, keeps nothing. */
    MetricsLog(
            final LongSupplier clock,
            final String instance,
            final QuestDbEndpoint qdb,
            final IntentSink notices
    ) {
        this(clock, instance, qdb, notices, null);
    }

    /** As above, each batch also sent to {@code shadow}, a base URL, when it is one. */
    MetricsLog(
            final LongSupplier clock,
            final String instance,
            final QuestDbEndpoint qdb,
            final IntentSink notices,
            final String shadow
    ) {
        this.clock = clock;
        this.qdb = qdb;
        this.sink = qdb == null ? null : new MetricsSink(qdb, new MetricsLines(instance), System::nanoTime,
                notices, shadow);
    }

    /**
     * The shadow server's base URL as this process's environment and the user's machine folder set it
     * ({@link #shadowSetting}); reads the user's home, so a test never calls it.
     */
    public static String machineShadow() {
        return shadowSetting(System::getenv, Path.of(System.getProperty("user.home")));
    }

    /**
     * The shadow server's base URL as the machine has it set: the value of {@link MetricsSink#SHADOW_ENV} when
     * {@code env} has it (blank: none), else the first line of {@link #SHADOW_FILE} in the machine's QuestDB folder,
     * {@link #QDB_HOME_ENV} or else {@code .comando-questdb} under {@code userHome}, the file the kit's writers read.
     * Null when the file is missing or unreadable: the file is never an error. A file with no line end is one line.
     */
    static String shadowSetting(final UnaryOperator<String> env, final Path userHome) {
        final String named = env.apply(MetricsSink.SHADOW_ENV);
        if (named != null) {
            return named;
        }
        final String home = env.apply(QDB_HOME_ENV);
        try {
            final Path folder = home == null || home.isBlank() ? userHome.resolve(MACHINE_FOLDER) : Path.of(home);
            final String text = new String(Mapped.head(folder.resolve(SHADOW_FILE), SHADOW_FILE_BYTES),
                    StandardCharsets.UTF_8);
            final int end = text.indexOf('\n');
            return (end < 0 ? text : text.substring(0, end)).strip();
        } catch (final IOException | RuntimeException _) {
            // handled: no file, or one that cannot be read, is no shadow
            return null;
        }
    }

    /** A new event of {@code type}, stamped now; {@link #write} keeps it. */
    ObjectNode event(final String type) {
        final ObjectNode e = Json.object();
        e.put("ts", Instant.ofEpochMilli(clock.getAsLong()).toString());
        e.put("event", type);
        return e;
    }

    /** The current time, as the events are stamped. */
    long now() {
        return clock.getAsLong();
    }

    /** The shadow server each batch also goes to; null with none, and for a log that keeps nothing. */
    @TestOnly
    public URI shadow() {
        return sink == null ? null : sink.shadow();
    }

    /**
     * The context and its window the last {@code turn} of conversation {@code id} ended with (record 120), as QuestDB
     * keeps it; empty when it has none, when no server runs or answers within {@link #CONTEXT_QUERY_WITHIN}, and for a
     * log that keeps nothing. Waits on QuestDB for the endpoint's live check (the machine's: its token, at most 2 s),
     * then at most {@link #CONTEXT_QUERY_WITHIN} for the query: off the EDT.
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
     * ({@link Queues}), so each can be bounded later from how it is used. A queue is entered only when its depth or
     * peak differs from its entry last written (a queue not written before has none): its last row holds until the
     * next, so a reading that repeats adds nothing, idle or busy (a timed strand always holds its next run, depth 1 or
     * more); a return to idle is written, and its peak falling to 0 after it. With no queue entered, nothing is
     * written. Called from one strand, the
     * {@code kit-check} strand of {@code App}. The state moves before the sink takes the event, so an entry the sink
     * drops (counted there) is not written again. {@link #NONE} keeps nothing and holds no state.
     */
    public void queues(final List<Queues.Reading> readings) {
        if (sink == null) {
            return;
        }
        final ObjectNode e = event("queues");
        final ArrayNode list = e.putArray("queues");
        final Map<String, Queues.Reading> before = new HashMap<>(queuesWritten);
        queuesWritten.clear();
        for (final Queues.Reading r : readings) {
            if (!r.equals(before.get(r.name()))) {
                list.addObject().put("name", r.name()).put("depth", r.depth()).put("peak", r.peak());
            }
            queuesWritten.put(r.name(), r);
        }
        if (!list.isEmpty()) {
            write(e);
        }
    }

    /**
     * A {@code wait} event, stamped when the card closed: the session that asked, what the card asked and through which
     * tool, how it ended, its id, when it was shown and how long it waited: the time work sits waiting on the user, by
     * kind of card. Called from the MCP request thread that waited; never blocks.
     */
    @Override
    public void waited(final Approval card, final WaitLog.Outcome outcome, final long waitMs) {
        if (sink == null) {
            return;
        }
        final ObjectNode e = event("wait");
        e.put("session", card.session());
        e.put("kind", card.kind().name().toLowerCase(Locale.ROOT));
        e.put("tool", card.toolName());
        e.put("outcome", outcome.name().toLowerCase(Locale.ROOT));
        e.put("card", card.id());
        e.put("opened", Instant.ofEpochMilli(card.createdMillis()).toString());
        e.put("wait_ms", waitMs);
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
