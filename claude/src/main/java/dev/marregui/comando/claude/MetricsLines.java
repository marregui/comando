package dev.marregui.comando.claude;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The metrics events as the lines QuestDB's line protocol takes (record 146), and the tables they go to: a
 * {@code turn} is a {@code comando_turn} row and a {@code comando_turn_model} row per model it names, an {@code agent}
 * a {@code comando_agent} row, a {@code usage} a {@code comando_usage} row, a {@code queues} a {@code comando_queue} row
 * per queue. SYMBOL columns are tags, every other column a field; a null or absent value, and an empty tag value, is
 * left out, and a row left with no field is not sent. Mapping only: nothing here reads or writes.
 */
final class MetricsLines {

    private static final String SYMBOL = " SYMBOL CAPACITY 4096, ";
    private static final String TABLE_END = ") TIMESTAMP(ts) PARTITION BY MONTH WAL DEDUP UPSERT KEYS(";

    /** The tables comando writes, created before its first write to a server and after one is found missing. */
    static final List<String> DDL = List.of(
            "CREATE TABLE IF NOT EXISTS comando_turn (ts TIMESTAMP, instance" + SYMBOL + "session" + SYMBOL
                    + "conversation SYMBOL CAPACITY 65536, model" + SYMBOL + "effort" + SYMBOL + "subtype" + SYMBOL
                    + "service_tier" + SYMBOL + "duration_ms LONG, api_ms LONG, calls LONG, context LONG, "
                    + "context_window LONG, queued LONG, input_tokens LONG, cache_creation_input_tokens LONG, "
                    + "cache_read_input_tokens LONG, output_tokens LONG, thinking_tokens LONG, cache_creation_5m LONG, "
                    + "cache_creation_1h LONG, web_search_requests LONG, web_fetch_requests LONG, error BOOLEAN, "
                    + "cost_usd_total DOUBLE, usage_raw VARCHAR" + TABLE_END + "ts, instance, conversation)",
            "CREATE TABLE IF NOT EXISTS comando_turn_model (ts TIMESTAMP, instance" + SYMBOL
                    + "conversation SYMBOL CAPACITY 65536, model" + SYMBOL + "provider" + SYMBOL + "cost_basis" + SYMBOL
                    + "input_tokens LONG, output_tokens LONG, thinking_tokens LONG, cache_read_input_tokens LONG, "
                    + "cache_creation_input_tokens LONG, cache_creation_5m LONG, cache_creation_1h LONG, "
                    + "web_search_requests LONG, context_window LONG, max_output_tokens LONG, cost_usd DOUBLE, "
                    + "raw VARCHAR" + TABLE_END + "ts, instance, conversation, model)",
            "CREATE TABLE IF NOT EXISTS comando_agent (ts TIMESTAMP, instance" + SYMBOL + "session" + SYMBOL
                    + "conversation SYMBOL CAPACITY 65536, type" + SYMBOL + "model_asked" + SYMBOL + "parent_model"
                    + SYMBOL + "status" + SYMBOL + "tool_use_id VARCHAR, description VARCHAR, spend_models VARCHAR, "
                    + "background BOOLEAN, started TIMESTAMP, duration_ms LONG, tools LONG, spend_calls LONG, "
                    + "spend_input LONG, spend_output LONG, spend_cache_read LONG, spend_cache_create LONG"
                    + TABLE_END + "ts, instance, tool_use_id)",
            "CREATE TABLE IF NOT EXISTS comando_usage (ts TIMESTAMP, instance" + SYMBOL + "provider" + SYMBOL
                    + "window_name" + SYMBOL + "pct DOUBLE, resets VARCHAR" + TABLE_END
                    + "ts, instance, provider, window_name)",
            "CREATE TABLE IF NOT EXISTS comando_queue (ts TIMESTAMP, instance" + SYMBOL + "name" + SYMBOL
                    + "depth LONG, peak LONG" + TABLE_END + "ts, instance, name)");

    private static final Set<String> KNOWN = Set.of("turn", "agent", "usage", "queues");

    private final String instance;
    private final ObjectMapper json;

    /** Lines for the events of the comando instance whose data root is {@code instance}, an absolute path. */
    MetricsLines(final ObjectMapper json, final String instance) {
        this.json = json;
        this.instance = instance;
    }

    /** Whether events of {@code type} have a table. */
    static boolean known(final String type) {
        return KNOWN.contains(type);
    }

    /**
     * The lines of {@code event}, each without its line end; none for an event of a type with no table or without a
     * readable {@code ts}.
     */
    List<String> of(final JsonNode event) {
        final List<String> out = new ArrayList<>();
        final long ts;
        try {
            ts = nanos(Instant.parse(event.path("ts").asString("")));
        } catch (final DateTimeException | ArithmeticException _) {
            return out;
        }
        switch (event.path("event").asString("")) {
            case "turn" -> turn(event, ts, out);
            case "agent" -> agent(event, ts, out);
            case "usage" -> add(out, row("comando_usage").tag("provider", event.path("provider"))
                    .tag("window_name", event.path("window")).doubleField("pct", event.path("pct"))
                    .stringField("resets", event.path("resets")), ts);
            case "queues" -> {
                for (final JsonNode q : event.path("queues")) {
                    add(out, row("comando_queue").tag("name", q.path("name")).longField("depth", q.path("depth"))
                            .longField("peak", q.path("peak")), ts);
                }
            }
            default -> {
                // a type with no table is not sent; the sink counts it
            }
        }
        return out;
    }

    /** A line's escape of a tag value: a backslash before {@code \ , space =}, a line break made an escaped space. */
    private static String tagValue(final String value) {
        final StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            switch (c) {
                case '\\', ',', ' ', '=' -> out.append('\\').append(c);
                case '\n', '\r' -> out.append("\\ ");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /** A line's string field: quoted, a backslash before {@code \ "} and before a line feed or carriage return. */
    private static String stringValue(final String value) {
        final StringBuilder out = new StringBuilder(value.length() + 8).append('"');
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c == '\\' || c == '"' || c == '\n' || c == '\r') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.append('"').toString();
    }

    private static void add(final List<String> out, final Row row, final long ts) {
        if (row.hasFields()) {
            out.add(row.end(ts));
        }
    }

    private static long nanos(final Instant at) {
        return Math.addExact(Math.multiplyExact(at.getEpochSecond(), 1_000_000_000L), at.getNano());
    }

    private void turn(final JsonNode e, final long ts, final List<String> out) {
        final JsonNode usage = e.path("usage");
        add(out, row("comando_turn").tag("session", e.path("session")).tag("conversation", e.path("conversation"))
                .tag("model", e.path("model")).tag("effort", e.path("effort")).tag("subtype", e.path("subtype"))
                .tag("service_tier", usage.path("service_tier"))
                .longField("duration_ms", e.path("duration_ms"))
                .longField("api_ms", e.path("api_ms"))
                .longField("calls", e.path("calls"))
                .longField("context", e.path("context"))
                .longField("context_window", e.path("window"))
                .longField("queued", e.path("queued"))
                .longField("input_tokens", usage.path("input_tokens"))
                .longField("cache_creation_input_tokens", usage.path("cache_creation_input_tokens"))
                .longField("cache_read_input_tokens", usage.path("cache_read_input_tokens"))
                .longField("output_tokens", usage.path("output_tokens"))
                .longField("thinking_tokens", usage.path("output_tokens_details").path("thinking_tokens"))
                .longField("cache_creation_5m", usage.path("cache_creation").path("ephemeral_5m_input_tokens"))
                .longField("cache_creation_1h", usage.path("cache_creation").path("ephemeral_1h_input_tokens"))
                .longField("web_search_requests", usage.path("server_tool_use").path("web_search_requests"))
                .longField("web_fetch_requests", usage.path("server_tool_use").path("web_fetch_requests"))
                .boolField("error", e.path("error"))
                .doubleField("cost_usd_total", e.path("cost_usd"))
                .stringField("usage_raw", usage.isObject() ? json.writeValueAsString(usage) : null), ts);
        final JsonNode models = e.path("models");
        if (!models.isObject()) {
            return;
        }
        for (final Map.Entry<String, JsonNode> entry : models.properties()) {
            final JsonNode m = entry.getValue();
            add(out, row("comando_turn_model").tag("conversation", e.path("conversation"))
                    .tag("model", entry.getKey()).tag("provider", m.path("provider"))
                    .tag("cost_basis", m.path("costBasis"))
                    .longField("input_tokens", m.path("inputTokens"))
                    .longField("output_tokens", m.path("outputTokens"))
                    .longField("thinking_tokens", m.path("thinkingTokens"))
                    .longField("cache_read_input_tokens", m.path("cacheReadInputTokens"))
                    .longField("cache_creation_input_tokens", m.path("cacheCreationInputTokens"))
                    .longField("cache_creation_5m", m.path("cacheCreationEphemeral5mInputTokens"))
                    .longField("cache_creation_1h", m.path("cacheCreationEphemeral1hInputTokens"))
                    .longField("web_search_requests", m.path("webSearchRequests"))
                    .longField("context_window", m.path("contextWindow"))
                    .longField("max_output_tokens", m.path("maxOutputTokens"))
                    .doubleField("cost_usd", m.path("costUSD"))
                    .stringField("raw", m.isObject() ? json.writeValueAsString(m) : null), ts);
        }
    }

    private void agent(final JsonNode e, final long ts, final List<String> out) {
        final JsonNode spend = e.path("spend");
        String spendModels = null;
        if (spend.path("models").isArray()) {
            final List<String> names = new ArrayList<>();
            for (final JsonNode m : spend.path("models")) {
                names.add(m.asString(""));
            }
            spendModels = String.join(",", names);
        }
        add(out, row("comando_agent").tag("session", e.path("session")).tag("conversation", e.path("conversation"))
                .tag("type", e.path("type")).tag("model_asked", e.path("model_asked"))
                .tag("parent_model", e.path("parent_model")).tag("status", e.path("status"))
                .stringField("tool_use_id", e.path("tool_use_id"))
                .stringField("description", e.path("description"))
                .stringField("spend_models", spendModels)
                .boolField("background", e.path("background"))
                .timestampField("started", e.path("started"))
                .longField("duration_ms", e.path("duration_ms"))
                .longField("tools", e.path("tools"))
                .longField("spend_calls", spend.path("calls"))
                .longField("spend_input", spend.path("input"))
                .longField("spend_output", spend.path("output"))
                .longField("spend_cache_read", spend.path("cache_read"))
                .longField("spend_cache_create", spend.path("cache_create")), ts);
    }

    private Row row(final String table) {
        return new Row(table).tag("instance", instance);
    }

    /** One line being built: its table and tags, then its fields, then its timestamp. */
    private static final class Row {

        private final StringBuilder fields = new StringBuilder();
        private final StringBuilder head;

        Row(final String table) {
            head = new StringBuilder(table);
        }

        Row tag(final String name, final JsonNode value) {
            return value.isString() ? tag(name, value.asString()) : this;
        }

        Row tag(final String name, final String value) {
            if (!value.isEmpty()) {
                head.append(',').append(name).append('=').append(tagValue(value));
            }
            return this;
        }

        Row longField(final String name, final JsonNode value) {
            return value.isIntegralNumber() && value.canConvertToLong() ? field(name, value.longValue() + "i") : this;
        }

        Row doubleField(final String name, final JsonNode value) {
            if (!value.isNumber() || !Double.isFinite(value.doubleValue())) {
                return this;
            }
            return field(name, Double.toString(value.doubleValue()));
        }

        Row boolField(final String name, final JsonNode value) {
            return value.isBoolean() ? field(name, value.booleanValue() ? "t" : "f") : this;
        }

        Row stringField(final String name, final JsonNode value) {
            return value.isString() ? stringField(name, value.asString()) : this;
        }

        Row stringField(final String name, final String value) {
            return value == null ? this : field(name, stringValue(value));
        }

        Row timestampField(final String name, final JsonNode value) {
            if (!value.isString()) {
                return this;
            }
            try {
                final Instant at = Instant.parse(value.asString());
                return field(name, Math.addExact(Math.multiplyExact(at.getEpochSecond(), 1_000_000L),
                        at.getNano() / 1_000L) + "t");
            } catch (final DateTimeException | ArithmeticException _) {
                return this;
            }
        }

        boolean hasFields() {
            return !fields.isEmpty();
        }

        String end(final long tsNanos) {
            return head + " " + fields + " " + tsNanos;
        }

        private Row field(final String name, final String value) {
            if (!fields.isEmpty()) {
                fields.append(',');
            }
            fields.append(name).append('=').append(value);
            return this;
        }
    }
}
