package dev.marregui.comando.frontear.alexander;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import dev.marregui.comando.core.json.JsonNode;
import dev.marregui.comando.frontear.octopus.Direction;
import dev.marregui.comando.frontear.octopus.LinkSpec;
import dev.marregui.comando.frontear.protocol.Packet;

/**
 * A control request, read from its method (the last level of its topic) and its JSON body. The method's name is the
 * version of its body: a body that changes shape is a new method, so a node refuses what it does not know, an unknown
 * property included, rather than drop part of a request.
 */
sealed interface Request {

    /**
     * The request {@code method} with {@code body} names.
     *
     * @throws Invalid when the method is unknown or the body is not its object
     */
    static Request parse(final String method, final JsonNode body) throws Invalid {
        return switch (method) {
            case "PutLink" -> {
                // tls is optional, absent meaning false: a node from before it refuses a body that carries it, so a
                // TLS link is never put as a plain one
                allowed(body, Set.of("id", "host", "port", "out", "in", "hopLimit", "tls"));
                final int port = integer(body, "port");
                final int hopLimit = integer(body, "hopLimit");
                if (port < 1 || port > 65_535) {
                    throw new Invalid(Refusal.BAD_REQUEST, "port out of range [port=" + port + ']');
                }
                if (hopLimit < 1) {
                    throw new Invalid(Refusal.BAD_REQUEST, "hop limit must be positive [hopLimit=" + hopLimit + ']');
                }
                yield new PutLink(new LinkSpec(text(body, "id"), text(body, "host"), port, filters(body, "out"),
                        filters(body, "in"), hopLimit, flag(body, "tls")));
            }
            case "RemoveLink" -> {
                allowed(body, Set.of("id"));
                yield new RemoveLink(text(body, "id"));
            }
            case "SetFilters" -> {
                allowed(body, Set.of("id", "direction", "filters"));
                yield new SetFilters(text(body, "id"), direction(body), filters(body, "filters"));
            }
            case "GetState" -> {
                allowed(body, Set.of());
                yield new GetState();
            }
            default -> throw new Invalid(Refusal.UNKNOWN_METHOD, "no such method [method=" + method + ']');
        };
    }

    private static void allowed(final JsonNode body, final Set<String> names) throws Invalid {
        if (!body.isObject()) {
            throw new Invalid(Refusal.BAD_REQUEST, "body is not an object");
        }
        for (final Map.Entry<String, JsonNode> property : body.properties()) {
            if (!names.contains(property.getKey())) {
                throw new Invalid(Refusal.BAD_REQUEST, "unknown property [name=" + property.getKey() + ']');
            }
        }
    }

    private static String text(final JsonNode body, final String name) throws Invalid {
        final JsonNode value = body.path(name);
        if (!value.isString() || value.stringValue().isEmpty()) {
            throw new Invalid(Refusal.BAD_REQUEST, "property is not a non-empty string [name=" + name + ']');
        }
        return value.stringValue();
    }

    private static int integer(final JsonNode body, final String name) throws Invalid {
        final JsonNode value = body.path(name);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new Invalid(Refusal.BAD_REQUEST, "property is not an int [name=" + name + ']');
        }
        return value.intValue();
    }

    /** The boolean {@code name}, false when the body does not carry it. */
    private static boolean flag(final JsonNode body, final String name) throws Invalid {
        final JsonNode value = body.path(name);
        if (value.isMissingNode()) {
            return false;
        }
        if (!value.isBoolean()) {
            throw new Invalid(Refusal.BAD_REQUEST, "property is not a boolean [name=" + name + ']');
        }
        return value.booleanValue();
    }

    private static Direction direction(final JsonNode body) throws Invalid {
        final String name = text(body, "direction");
        for (final Direction d : Direction.values()) {
            if (d.name().equals(name)) {
                return d;
            }
        }
        throw new Invalid(Refusal.BAD_REQUEST, "no such direction [direction=" + name + ']');
    }

    /** The array {@code name} of topic filters, each checked as SUBSCRIBE would check it. */
    private static Set<String> filters(final JsonNode body, final String name) throws Invalid {
        final JsonNode value = body.path(name);
        if (!value.isArray()) {
            throw new Invalid(Refusal.BAD_REQUEST, "property is not an array [name=" + name + ']');
        }
        final Set<String> filters = new HashSet<>();
        for (final JsonNode element : value.values()) {
            if (!element.isString()) {
                throw new Invalid(Refusal.BAD_REQUEST, "filter is not a string [name=" + name + ']');
            }
            try {
                filters.add(new Packet.Subscription(element.stringValue(), 0).filter());
            } catch (final IllegalArgumentException _) {
                throw new Invalid(Refusal.BAD_REQUEST, "not a topic filter [name=" + name + ", filter="
                        + element.stringValue() + ']');
            }
        }
        return Set.copyOf(filters);
    }

    /** {@code PutLink}: add or replace a link. */
    record PutLink(LinkSpec spec) implements Request {
    }

    /** {@code RemoveLink {id}}. */
    record RemoveLink(String id) implements Request {
    }

    /** {@code SetFilters {id, direction, filters}}: the link's whole filter set for one direction. */
    record SetFilters(String id, Direction direction, Set<String> filters) implements Request {
    }

    /** {@code GetState {}}: the links' state. */
    record GetState() implements Request {
    }

    /** A request refused before it reached the octopus, with the refusal and what was wrong. */
    final class Invalid extends Exception {

        private final Refusal refusal;

        Invalid(final Refusal refusal, final String message) {
            super(message, null, false, false);
            this.refusal = refusal;
        }

        Refusal refusal() {
            return refusal;
        }
    }
}
