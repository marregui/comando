package dev.marregui.comando.mcp;

import java.util.Locale;

import dev.marregui.comando.core.json.ArrayNode;
import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.JsonNode;
import dev.marregui.comando.core.json.ObjectNode;
import dev.marregui.comando.core.port.MachinesPort;

/**
 * The {@code machines} tool (record 189): this machine and the machines paired with it, through the
 * {@link MachinesPort} the application sets. The views {@code self} and {@code list} ask nothing of the user; the
 * actions {@code listen} on an address and {@code pair} open this machine to another, so {@link McpTools} puts the
 * card {@link #card} words before {@link #run} does them, and {@code unpair} and a {@code listen} that stops close it,
 * without a card.
 */
final class MachinesTools {

    static final String SELF = "self";
    static final String LIST = "list";
    static final String LISTEN = "listen";
    static final String PAIR = "pair";
    static final String UNPAIR = "unpair";

    private MachinesTools() {
    }

    /** Why {@code args} cannot run, empty when they can: one of the views or actions, with what it needs. */
    static String refusal(final JsonNode args) {
        final String what = what(args);
        return switch (what) {
            case SELF, LIST, LISTEN -> "";
            case PAIR -> args.path("token").asString("").isBlank() ? "pair needs the other machine's token"
                    : grant(args) == null ? "unknown grant [grant=" + args.path("grant").asString("")
                            + ", grants=peer|overseer]" : "";
            case UNPAIR -> args.path("machine").asString("").isBlank() ? "unpair needs the machine's id" : "";
            default -> "unknown view or action [name=" + what + ", views=self|list, actions=listen|pair|unpair]";
        };
    }

    /**
     * The card's text for {@code args}, empty when the call needs no card: listening on an address, and pairing, open
     * this machine to another, and the user says yes to each, the fingerprint of a pairing in sight. The card is a
     * connection's, which only the user answers; its title reads "wants to change connection", then this text.
     */
    static String card(final MachinesPort port, final JsonNode args) {
        final String what = what(args);
        if (LISTEN.equals(what) && !host(args).isEmpty()) {
            return "of this machine: listen on " + host(args) + ':' + port.self().port()
                    + ", so the machines paired with it reach its instances";
        }
        if (PAIR.equals(what)) {
            return "to another machine: pair it as "
                    + (grant(args) == MachinesPort.Grant.OVERSEER
                            ? "overseer, so the questions of its main run here as the user's own"
                            : "peer, so the questions of its instances are answered here in plan mode")
                    + "; its fingerprint is the token's last part: " + args.path("token").asString("").strip();
        }
        return "";
    }

    /** Runs {@code args}, once {@link #refusal} found nothing and the user said yes to its {@link #card}. */
    static McpTools.Outcome run(final MachinesPort port, final long dropped, final JsonNode args) {
        final String what = what(args);
        final ObjectNode out = Json.object();
        switch (what) {
            case SELF -> self(out, port.self(), dropped);
            case LIST -> {
                final ArrayNode list = out.putArray("machines");
                for (final MachinesPort.Paired p : port.paired()) {
                    list.add(paired(p));
                }
            }
            case LISTEN -> {
                port.listen(host(args));
                self(out, port.self(), dropped);
            }
            case PAIR -> out.set("paired", paired(port.pair(args.path("token").asString("").strip(), grant(args))));
            case UNPAIR -> {
                final String machine = args.path("machine").asString("").strip();
                out.put("machine", machine).put("unpaired", port.unpair(machine));
            }
            default -> {
                return McpTools.Outcome.fail(refusal(args));
            }
        }
        return McpTools.Outcome.ok(out.toString());
    }

    /** The view or action {@code args} name: {@code action}, else {@code view}, else {@link #SELF}. */
    private static String what(final JsonNode args) {
        final String action = args.path("action").asString("").strip().toLowerCase(Locale.ROOT);
        return action.isEmpty() ? args.path("view").asString(SELF).strip().toLowerCase(Locale.ROOT) : action;
    }

    private static String host(final JsonNode args) {
        return args.path("host").asString("").strip();
    }

    /** The grant {@code args} name, {@code peer} when none; null for an unknown one. */
    private static MachinesPort.Grant grant(final JsonNode args) {
        final String text = args.path("grant").asString("");
        return text.isBlank() ? MachinesPort.Grant.PEER : MachinesPort.Grant.of(text).orElse(null);
    }

    private static void self(final ObjectNode out, final MachinesPort.Self s, final long dropped) {
        out.put("machine", s.machine()).put("host", s.host()).put("port", s.port()).put("fingerprint", s.fingerprint())
                .put("token", s.token()).put("dropped", dropped);
    }

    private static ObjectNode paired(final MachinesPort.Paired p) {
        return Json.object().put("machine", p.machine()).put("host", p.host()).put("port", p.port())
                .put("fingerprint", p.fingerprint()).put("grant", p.grant().text())
                .put("connected", p.connected()).put("lastConnectedMillis", p.lastConnectedMillis());
    }
}
