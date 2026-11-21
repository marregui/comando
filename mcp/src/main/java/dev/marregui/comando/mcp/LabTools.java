package dev.marregui.comando.mcp;

import java.util.Locale;

import dev.marregui.comando.core.json.ArrayNode;
import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.JsonNode;
import dev.marregui.comando.core.json.ObjectNode;
import dev.marregui.comando.core.port.LabPort;
import dev.marregui.comando.core.port.LabPort.Counts;
import dev.marregui.comando.core.port.LabPort.Frame;
import dev.marregui.comando.core.port.LabPort.Link;
import dev.marregui.comando.core.port.LabPort.Log;
import dev.marregui.comando.core.port.LabPort.Node;
import dev.marregui.comando.core.port.LabPort.Packet;
import dev.marregui.comando.core.port.LabPort.Totals;

/**
 * The {@code frontear_read} tool (record 187): one of the frontear tab's four views of this instance's node, as JSON,
 * read from the {@link LabPort} the application sets; nothing is asked of the user. The first call starts the watch,
 * so its {@code traffic} holds only what came after it.
 */
final class LabTools {

    /** The packets one {@code traffic} read answers without {@code max}, and at most (record 187). */
    static final int DEFAULT_MAX = 200;
    static final int MAX = 2000;

    private LabTools() {
    }

    /** The view {@code args.view} names, after {@code args.since}, at most {@code args.max} packets. */
    static McpTools.Outcome read(final LabPort lab, final JsonNode args) {
        final String view = args.path("view").asString("overview").toLowerCase(Locale.ROOT);
        final long since = Math.max(0L, args.path("since").asLong(0L));
        final int max = Math.clamp(args.path("max").asInt(DEFAULT_MAX), 1, MAX);
        lab.open();
        final ObjectNode out = Json.object().put("view", view);
        final Frame f = lab.read(since, "traffic".equals(view) ? max : 0);
        out.set("node", node(f.node()));
        switch (view) {
            case "overview" -> {
                out.set("totals", totals(f.node().totals()));
                out.set("counts", counts(f.node().counts()));
                final ArrayNode links = out.putArray("links");
                for (final Link l : f.node().links()) {
                    links.add(Json.object().put("id", l.id()).put("connected", l.connected())
                            .put("queued", l.queued()).put("messagesPerSecond", l.messagesPerSecond())
                            .put("bytesPerSecond", l.bytesPerSecond()));
                }
            }
            case "traffic" -> {
                out.put("since", since).put("missed", f.missed()).put("last", f.last());
                final ArrayNode packets = out.putArray("packets");
                for (final Packet p : f.packets()) {
                    packets.add(packet(p));
                }
            }
            case "octopus" -> {
                final ArrayNode links = out.putArray("links");
                for (final Link l : f.node().links()) {
                    links.add(link(l));
                }
            }
            case "store" -> {
                final ArrayNode logs = out.putArray("logs");
                for (final Log l : f.node().logs()) {
                    logs.add(log(l));
                }
            }
            default -> {
                return McpTools.Outcome.fail("unknown view [view=" + view + ", views=overview|traffic|octopus|store]");
            }
        }
        return McpTools.Outcome.ok(Json.write(out));
    }

    private static ObjectNode node(final Node n) {
        return Json.object().put("name", n.name()).put("port", n.port()).put("up", n.up())
                .put("atMillis", n.atMillis());
    }

    private static ObjectNode totals(final Totals t) {
        return Json.object().put("sessions", t.sessions()).put("connections", t.connections())
                .put("retained", t.retained()).put("retainedBytes", t.retainedBytes())
                .put("droppedFull", t.droppedFull()).put("droppedExpired", t.droppedExpired())
                .put("droppedOversize", t.droppedOversize()).put("refused", t.refused())
                .put("backlogDisconnects", t.backlogDisconnects());
    }

    private static ObjectNode counts(final Counts c) {
        return Json.object().put("windowDropped", c.windowDropped()).put("ringMissed", c.ringMissed())
                .put("sinkWritten", c.sinkWritten()).put("sinkDroppedFull", c.sinkDroppedFull())
                .put("sinkDroppedDown", c.sinkDroppedDown());
    }

    private static ObjectNode packet(final Packet p) {
        return Json.object().put("seq", p.seq()).put("atMillis", p.atMillis()).put("kind", p.kind().name())
                .put("origin", p.origin()).put("link", p.link()).put("topic", p.topic()).put("qos", p.qos())
                .put("retain", p.retain()).put("bytes", p.bytes()).put("hops", p.hops()).put("offset", p.offset())
                .put("correlation", p.correlation()).put("head", p.head()).put("detail", p.detail());
    }

    private static ObjectNode link(final Link l) {
        return Json.object().put("id", l.id()).put("connected", l.connected()).put("queued", l.queued())
                .put("cursor", l.cursor()).put("tail", l.tail()).put("sent", l.sent()).put("dropped", l.dropped())
                .put("reordered", l.reordered()).put("queuedDeliveries", l.queuedDeliveries())
                .put("queueMax", l.queueMax()).put("lastUpMillis", l.lastUpMillis())
                .put("lastDownMillis", l.lastDownMillis()).put("lastDetail", l.lastDetail());
    }

    private static ObjectNode log(final Log l) {
        return Json.object().put("link", l.link()).put("cursor", l.cursor()).put("tail", l.tail())
                .put("bytes", l.bytes()).put("segments", l.segments()).put("roundsPerSecond", l.roundsPerSecond())
                .put("lastRecords", l.lastRecords()).put("lastBytes", l.lastBytes()).put("lastMillis", l.lastMillis())
                .put("p50Millis", l.p50Millis()).put("p99Millis", l.p99Millis());
    }
}
