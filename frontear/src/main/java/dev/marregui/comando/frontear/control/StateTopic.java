package dev.marregui.comando.frontear.control;

import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

import dev.marregui.comando.core.json.ArrayNode;
import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.ObjectNode;
import dev.marregui.comando.frontear.link.LinkState;
import dev.marregui.comando.frontear.link.Mesh;
import dev.marregui.comando.frontear.mqtt.Names;
import dev.marregui.comando.frontear.mqtt.Packet;

/**
 * A node's retained {@code $frontear/state/<node>/links}: the mesh's links as {@link #json}, published when they
 * changed and at most once per {@link #EVERY}, read by the {@code clock} it was given. The mesh is asked at each
 * {@link #publishIfChanged}; its owner calls that after each change it makes and on a timer, which catches the changes
 * the links make themselves (a connect, a message sent). Used by one thread at a time.
 */
final class StateTopic {

    /** The least time between two publishes: the control contract's once a second. */
    static final Duration EVERY = Duration.ofSeconds(1);
    private static final Comparator<LinkState> BY_ID = Comparator.comparing(LinkState::id);

    private final Mesh mesh;
    private final String topic;
    private final InstantSource clock;
    private final Consumer<Packet.Publish> out;
    /** The links last published, by id; null before the first publish. */
    private List<LinkState> last;
    private long lastMillis;

    /** The state topic of {@code node}, its messages handed to {@code out}. */
    StateTopic(final Mesh mesh, final String node, final InstantSource clock, final Consumer<Packet.Publish> out) {
        this.mesh = mesh;
        this.topic = Names.STATE + node + "/links";
        this.clock = clock;
        this.out = out;
    }

    /**
     * Publishes the mesh's links when they differ from the last published and {@link #EVERY} has passed since; a
     * clock that stepped back counts as passed. Returns whether it published.
     */
    boolean publishIfChanged() {
        final List<LinkState> links = new ArrayList<>(mesh.state());
        links.sort(BY_ID);
        if (links.equals(last)) {
            return false;
        }
        final long nowMillis = clock.millis();
        if (last != null && nowMillis >= lastMillis && nowMillis - lastMillis < EVERY.toMillis()) {
            return false;
        }
        out.accept(new Packet.Publish(topic, Json.writeBytes(json(links)), 1, true, false, 0, null, null,
                new Packet.Meta(1, -1, Names.JSON, List.of())));
        last = List.copyOf(links);
        lastMillis = nowMillis;
        return true;
    }

    /**
     * {@code {"links":[{"id", "connected", "queued", "cursor", "tail", "sent", "dropped", "reordered"}, ...]}}, in
     * their order.
     */
    static ObjectNode json(final List<LinkState> links) {
        final ObjectNode root = Json.object();
        final ArrayNode array = root.putArray("links");
        for (final LinkState link : links) {
            final ObjectNode o = Json.object();
            o.put("id", link.id());
            o.put("connected", link.connected());
            o.put("queued", link.queued());
            o.put("cursor", link.cursor());
            o.put("tail", link.tail());
            o.put("sent", link.sent());
            o.put("dropped", link.dropped());
            o.put("reordered", link.reordered());
            array.add(o);
        }
        return root;
    }
}
