package dev.marregui.comando.frontear.sim;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.awaitility.core.ConditionTimeoutException;

import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.JsonNode;
import dev.marregui.comando.core.json.ObjectNode;
import dev.marregui.comando.core.loop.ExecutorStop;
import dev.marregui.comando.core.loop.Queues;
import dev.marregui.comando.core.loop.Threads;
import dev.marregui.comando.frontear.Frontear;
import dev.marregui.comando.frontear.broker.Broker;
import dev.marregui.comando.frontear.broker.BrokerOptions;
import dev.marregui.comando.frontear.control.Signer;
import dev.marregui.comando.frontear.link.LinkOptions;
import dev.marregui.comando.frontear.link.LinkState;
import dev.marregui.comando.frontear.mqtt.MqttClient;
import dev.marregui.comando.frontear.mqtt.Names;
import dev.marregui.comando.frontear.mqtt.Packet;
import dev.marregui.comando.frontear.mqtt.Relay;
import dev.marregui.comando.frontear.mqtt.Topics;

import static org.awaitility.Awaitility.await;

/// The nodes of a [Schedule]'s topology as real frontear nodes on loopback, running the schedule: every link's peer
/// address is a [Relay] in front of the far node's broker, every link is put with its OUT and IN filters, removed and
/// refiltered by a control message signed with a pod's key, and each node has a control client, a publishing client,
/// a subscribing client on `sim/#` and two internal subscribers with different filters that live as long as the run,
/// a restart included. [#run] plays the steps, checks the bounds after each, heals the mesh (links put back, every
/// log drained) and returns every violation the [Ledger] and the bounds show; a schedule checked on every walk first
/// waits until each link's IN filters carry a probe. One thread drives it.
final class SimCluster implements AutoCloseable {

    /// How long an acknowledgement or a control reply may take: milliseconds on this machine, seconds on a loaded one.
    private static final Duration WAIT = Duration.ofSeconds(15);
    /// How long the mesh may show no change while it heals: a link whose peer refused a window and then a resend waits
    /// the links' longest backoff (30 s) before it sends again; 5 s more for a loaded machine.
    private static final Duration STILL = Duration.ofSeconds(35);
    /// How long the mesh may take to settle after the last step, moving all along: measured up to 46 s on an Apple M2
    /// Max, where a node whose own links wait refuses another link's resend so the waits chain; about twice that.
    private static final Duration HEAL = Duration.ofSeconds(90);
    private static final Duration POLL = Duration.ofMillis(25);
    /// How often a probe is published again until it comes back: a probe published before its filter was in place
    /// is lost, and at this rate the far node sees at most 150 probes a filter within [#WAIT].
    private static final Duration PROBE_POLL = Duration.ofMillis(100);
    private static final String HOST = "127.0.0.1";
    private static final String REPLIES = "sim-replies/";
    /// The head of a probe's payload, which no node's sequence starts with: the subscribers leave probes out.
    private static final String PROBE = "probe ";
    /// The origin of what a link publishes locally, before its id: the link contract's owner `$link/<id>`.
    private static final String LINK_ORIGIN = "$link/";
    /// The internal subscribers' filters: everything, and a part of each family, so a filter decides who must get
    /// what.
    private static final List<List<String>> RECORDERS = List.of(List.of("sim/#", "simb/#"),
            List.of("sim/0", "sim/2", "simb/1"));
    private static final CompletableFuture<Integer> ACCEPTED = CompletableFuture.completedFuture(Packet.SUCCESS);
    private static final BrokerOptions BOUNDS = BrokerOptions.defaults(new InetSocketAddress(
            InetAddress.getLoopbackAddress(), 0));
    private static final int QUEUE_MAX = LinkOptions.defaults().queueMax();

    private final Path dir;
    private final Schedule schedule;
    private final Ledger ledger;
    private final Signer signer;
    private final Set<PublicKey> trusted;
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final Map<String, Planned> links = new LinkedHashMap<>();
    private final Map<String, Long> reorderedNow = new HashMap<>();
    private final Set<String> restarted = new HashSet<>();
    private final List<String> events = new ArrayList<>();
    private final List<String> violations = new ArrayList<>();
    private final long startNanos = System.nanoTime();
    private long correlations;
    private long healMillis = -1;

    /// A cluster for `schedule` with its nodes' data under `dir`; nothing runs before [#run].
    SimCluster(final Path dir, final Schedule schedule) throws GeneralSecurityException {
        this.dir = dir;
        this.schedule = schedule;
        this.ledger = schedule.check() == Schedule.Check.FIRST_HOP ? new Ledger(schedule.hopLimit())
                : new Ledger(new Paths(schedule.hopLimit(), schedule.topology().links(), schedule.filters(),
                        schedule.in()), schedule.check() == Schedule.Check.EXACT);
        final KeyPair pod = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        this.signer = new Signer(pod.getPrivate(), Signer.keyId(pod.getPublic()));
        this.trusted = Set.of(pod.getPublic());
        for (final String n : schedule.topology().nodes()) {
            nodes.put(n, new Node(n));
        }
        for (final Ledger.Link l : schedule.topology().links()) {
            ledger.link(l.id(), l.from(), l.to());
            links.put(l.id(), new Planned(l, schedule.filters().get(l.id()), schedule.in().get(l.id())));
        }
    }

    @Override
    public void close() {
        final List<Exception> failures = new ArrayList<>();
        for (final Node n : nodes.values()) {
            try {
                n.stop();
            } catch (final RuntimeException e) {
                failures.add(e);
            }
        }
        for (final Planned p : links.values()) {
            if (p.relay != null) {
                p.relay.close();
            }
        }
        if (!failures.isEmpty()) {
            final IllegalStateException e = new IllegalStateException("cluster close failed [dir=" + dir + ']',
                    failures.getFirst());
            failures.subList(1, failures.size()).forEach(e::addSuppressed);
            throw e;
        }
    }

    /// The events so far, one line each, milliseconds from the cluster's start first.
    List<String> events() {
        return List.copyOf(events);
    }

    /// What the run published, per node.
    String published() {
        return ledger.published();
    }

    /// The refusals overtaken per link, as the links counted them.
    Map<String, Long> reordered() {
        return Map.copyOf(reorderedNow);
    }

    /// How long the heal took, milliseconds; -1 before it ran.
    long healMillis() {
        return healMillis;
    }

    /// Starts the nodes, puts the links, plays the steps and heals; returns every violation seen.
    List<String> run() throws IOException {
        for (final Node n : nodes.values()) {
            n.start();
        }
        for (final Planned p : links.values()) {
            p.relay = new Relay(HOST, nodes.get(p.link.to()).frontear.port());
            put(p);
        }
        event("started " + schedule.topology().name() + " hopLimit=" + schedule.hopLimit() + " filters="
                + schedule.filters() + " in=" + schedule.in() + " check=" + schedule.check());
        if (schedule.check() == Schedule.Check.EXACT) {
            awaitProbes();
        }
        int i = 0;
        for (final Schedule.Step step : schedule.steps()) {
            event("step " + i++ + ": " + step);
            play(step);
            sample("after " + step);
        }
        heal();
        violations.addAll(ledger.violations());
        return List.copyOf(violations);
    }

    /// `"<node> <seq>"` and a padding of 0 to 96 bytes, so messages differ in size.
    private static byte[] payload(final String node, final long seq) {
        return (node + ' ' + seq + ' ' + "x".repeat((int) (seq % 97))).getBytes(StandardCharsets.US_ASCII);
    }

    private static <T> T get(final CompletableFuture<T> f, final String node, final String what) {
        try {
            return f.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (final TimeoutException | ExecutionException e) {
            throw new IllegalStateException("no answer [node=" + node + ", request=" + what + ", waitMillis="
                    + WAIT.toMillis() + ']', e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted [node=" + node + ", request=" + what + ']', e);
        }
    }

    private static CompletionStage<Integer> record(final Ledger.Sink r, final Packet.Publish m) {
        if (!probe(m)) {
            r.arrived(Ledger.Arrival.of(m));
        }
        return ACCEPTED;
    }

    private static boolean probe(final Packet.Publish m) {
        return new String(m.payload(), StandardCharsets.US_ASCII).startsWith(PROBE);
    }

    /// A topic `filter` matches: each wildcard level as one plain level.
    private static String probeTopic(final String filter) {
        return filter.replace("#", "p").replace("+", "p");
    }

    private void play(final Schedule.Step step) throws IOException {
        switch (step) {
            case Schedule.Batch(final String node, final List<String> topics, final String cut) -> batch(node, topics,
                    cut);
            case Schedule.Burst(final String node, final int count, final String topic) -> burst(node, count, topic);
            case Schedule.Cut(final String link) -> links.get(link).relay.cut();
            case Schedule.Restart(final String node) -> restart(nodes.get(node));
            case Schedule.Remove(final String link) -> remove(links.get(link));
            case Schedule.PutBack(final String link) -> putBack(links.get(link));
            case Schedule.Refilter(final String link, final List<String> filters, final List<String> topics) ->
                    refilter(links.get(link), filters, topics);
        }
    }

    private void batch(final String node, final List<String> topics, final String cut) {
        final Node n = nodes.get(node);
        final long[] seqs = new long[topics.size()];
        final List<CompletableFuture<Integer>> acks = new ArrayList<>(topics.size());
        for (int i = 0; i < topics.size(); i++) {
            final String topic = topics.get(i);
            seqs[i] = ledger.publish(node, topic, covering(node, topic));
            acks.add(n.publisher.publishAcknowledged(new Packet.Publish(topic, payload(node, seqs[i]), 1, false,
                    false, 0, null, null)));
        }
        if (cut != null) {
            links.get(cut).relay.cut();
        }
        settle(node, seqs, acks, "batch");
    }

    private void burst(final String node, final int count, final String topic) {
        final Broker broker = nodes.get(node).frontear.broker();
        final Set<String> covering = covering(node, topic);
        final long[] seqs = new long[count];
        final List<CompletableFuture<Integer>> acks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            seqs[i] = ledger.publish(node, topic, covering);
            acks.add(broker.publish(new Packet.Publish(topic, payload(node, seqs[i]), 1, false, false, 0, null, null),
                    "sim-burst-" + node));
        }
        settle(node, seqs, acks, "burst");
    }

    /// Waits for `acks` and records each code; one that does not come within [#WAIT] is a violation.
    private void settle(final String node, final long[] seqs, final List<CompletableFuture<Integer>> acks,
            final String what) {
        try {
            CompletableFuture.allOf(acks.toArray(CompletableFuture[]::new)).handle((_, _) -> null)
                    .get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (final TimeoutException _) {
            violation("ack: " + what + " on " + node + " left PUBACKs unanswered past the wait [waitMillis="
                    + WAIT.toMillis() + ", pending=" + acks.stream().filter(f -> !f.isDone()).count() + ']');
        } catch (final ExecutionException e) {
            throw new IllegalStateException("acknowledgements not collected [node=" + node + ']', e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
        final Map<Integer, Integer> codes = new HashMap<>();
        for (int i = 0; i < seqs.length; i++) {
            final CompletableFuture<Integer> f = acks.get(i);
            final int code = f.isDone() && !f.isCompletedExceptionally() ? f.join() : Ledger.NO_ACK;
            ledger.acked(node, seqs[i], code);
            codes.merge(code, 1, Integer::sum);
        }
        event("  codes " + codes);
    }

    private void restart(final Node n) throws IOException {
        sample("before restart " + n.name);
        for (final Planned p : links.values()) {
            if (p.link.from().equals(n.name)) {
                ledger.reordered(p.link.id(), reorderedNow.getOrDefault(p.link.id(), 0L));
                reorderedNow.remove(p.link.id());
            }
            if (p.link.to().equals(n.name)) {
                // no link connects to the node again before its subscribers are back
                p.relay.close();
            }
        }
        n.stop();
        n.start();
        restarted.add(n.name);
        n.client.unchecked();
        for (final Planned p : links.values()) {
            if (p.link.from().equals(n.name) && p.present) {
                put(p);
            }
        }
        for (final Planned p : links.values()) {
            if (p.link.to().equals(n.name)) {
                p.relay = new Relay(HOST, n.frontear.port());
                if (p.present) {
                    put(p);
                }
            }
        }
        event("  restarted " + n.name + " port=" + n.frontear.port());
    }

    private void remove(final Planned p) {
        if (!p.present) {
            event("  skipped: not present");
            return;
        }
        sample("before remove " + p.link.id());
        ledger.reordered(p.link.id(), reorderedNow.getOrDefault(p.link.id(), 0L));
        reorderedNow.remove(p.link.id());
        control(p.link.from(), "RemoveLink", Json.object().put("id", p.link.id()));
        p.present = false;
    }

    private void putBack(final Planned p) {
        if (p.present) {
            event("  skipped: present");
            return;
        }
        put(p);
    }

    private void refilter(final Planned p, final List<String> filters, final List<String> topics) {
        if (!p.present) {
            event("  skipped: not present");
            return;
        }
        p.relay.cut();
        batch(p.link.from(), topics, null);
        final long queued = state(p.link.from()).getOrDefault(p.link.id(), new LinkState(p.link.id(), false, -1, 0,
                0, 0, 0)).queued();
        final ObjectNode body = Json.object().put("id", p.link.id()).put("direction", "OUT");
        filters.forEach(body.putArray("filters")::add);
        control(p.link.from(), "SetFilters", body);
        p.out = filters;
        event("  refiltered with queued=" + queued);
    }

    private void put(final Planned p) {
        final ObjectNode body = Json.object().put("id", p.link.id()).put("host", HOST).put("port", p.relay.port())
                .put("hopLimit", schedule.hopLimit());
        p.out.forEach(body.putArray("out")::add);
        p.in.forEach(body.putArray("in")::add);
        control(p.link.from(), "PutLink", body);
        p.present = true;
    }

    /// Waits until every link is connected, then until each IN filter of each link carried a probe: a message on a
    /// topic the filter matches, published on the far node again each poll until the near node sees it come in as
    /// the link. A link asks the peer for its IN filters back to back on one connection, which the peer reads in
    /// order, so a probe through each filter leaves none still on its way. A probe that does not come within
    /// [#WAIT] fails the run.
    private void awaitProbes() {
        await().pollInSameThread().pollInterval(POLL).atMost(WAIT).until(() -> {
            for (final Node n : nodes.values()) {
                for (final LinkState s : state(n.name).values()) {
                    if (!s.connected()) {
                        return false;
                    }
                }
            }
            return true;
        });
        for (final Planned p : links.values()) {
            final Node near = nodes.get(p.link.from());
            final Broker far = nodes.get(p.link.to()).frontear.broker();
            for (final String f : p.in) {
                final String payload = PROBE + p.link.id() + ' ' + f;
                final String seen = LINK_ORIGIN + p.link.id() + ' ' + payload;
                await().pollInSameThread().pollInterval(PROBE_POLL).atMost(WAIT).until(() -> {
                    if (!near.probes.contains(seen)) {
                        far.publish(new Packet.Publish(probeTopic(f), payload.getBytes(StandardCharsets.US_ASCII), 1,
                                false, false, 0, null, null), "sim-probe");
                    }
                    return near.probes.contains(seen);
                });
            }
        }
        event("  probed every IN filter");
    }

    /// Puts back every removed link and waits until each subscriber has what it must get, every link is connected
    /// with nothing queued and every node has exactly its clients' and links' connections. A mesh that shows no change
    /// for [#STILL], or has not settled within [#HEAL], is a violation.
    private void heal() {
        for (final Planned p : links.values()) {
            if (!p.present) {
                event("heal: put back " + p.link.id());
                put(p);
            }
        }
        final long[] missing = {-1};
        final String[] unsettled = {""};
        final long healNanos = System.nanoTime();
        final long[] movedNanos = {healNanos};
        final boolean[] still = {false};
        try {
            await().pollInSameThread().pollInterval(POLL).atMost(HEAL).until(() -> {
                markOverflowed();
                final long m = ledger.missing();
                final String u = unsettled();
                final long now = System.nanoTime();
                if (m != missing[0] || !u.equals(unsettled[0])) {
                    movedNanos[0] = now;
                }
                missing[0] = m;
                unsettled[0] = u;
                still[0] = now - movedNanos[0] > STILL.toNanos();
                return still[0] || m == 0 && u.isEmpty();
            });
            healMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - healNanos);
            if (still[0]) {
                violation("heal: the mesh stopped moving before it settled [stillMillis=" + STILL.toMillis()
                        + ", healMillis=" + healMillis + ", missing=" + missing[0] + ", unsettled=" + unsettled[0]
                        + ']');
            } else {
                event("healed in " + healMillis + " ms");
            }
        } catch (final ConditionTimeoutException _) {
            healMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - healNanos);
            violation("heal: the mesh did not settle [healMillis=" + HEAL.toMillis() + ", missing=" + missing[0]
                    + ", unsettled=" + unsettled[0] + ']');
        }
        sample("healed");
        for (final Map.Entry<String, Long> e : reorderedNow.entrySet()) {
            ledger.reordered(e.getKey(), e.getValue());
        }
    }

    /// What keeps the mesh from being settled: a link not connected or with messages queued, a node with other than
    /// its connections; empty when settled.
    private String unsettled() {
        final StringBuilder b = new StringBuilder();
        for (final Node n : nodes.values()) {
            for (final LinkState s : state(n.name).values()) {
                if (!s.connected() || s.queued() != 0) {
                    b.append(s.id()).append(s.connected() ? " queued=" + s.queued() + " sent=" + s.sent()
                            + " reordered=" + s.reordered() : " down").append(' ');
                }
            }
            final long expected = 3 + links.values().stream().filter(p -> p.present && p.link.to().equals(n.name))
                    .count();
            final int connections = n.frontear.broker().stats().connections();
            if (connections != expected) {
                b.append(n.name).append(" connections=").append(connections).append("/").append(expected).append(' ');
            }
        }
        return b.toString().strip();
    }

    /// Checks the bounds of every broker and link now, and keeps each link's `reordered`.
    private void sample(final String when) {
        markOverflowed();
        for (final Node n : nodes.values()) {
            final Broker.Stats s = n.frontear.broker().stats();
            bound(when, n.name, "sessions", s.sessions(), BOUNDS.maxSessions());
            bound(when, n.name, "retained", s.retained(), BOUNDS.maxRetained());
            bound(when, n.name, "retainedBytes", s.retainedBytes(), BOUNDS.maxRetainedBytes());
            bound(when, n.name, "largestSessionBytes", s.largestSessionBytes(), BOUNDS.maxQueuedBytes());
            bound(when, n.name, "largestBacklogBytes", s.largestBacklogBytes(), BOUNDS.maxBacklogBytes());
            bound(when, n.name, "backlogDisconnects", s.backlogDisconnects(), 0);
            bound(when, n.name, "subscriberFailures", s.subscriberFailures(), 0);
            bound(when, n.name, "droppedOversize", s.droppedOversize(), 0);
            for (final LinkState l : state(n.name).values()) {
                if (l.cursor() < 0 || l.cursor() > l.tail() || l.queued() != l.tail() - l.cursor()) {
                    violation("bounds " + when + ": link " + l.id() + " state is not a log window [cursor="
                            + l.cursor() + ", tail=" + l.tail() + ", queued=" + l.queued() + ']');
                }
                reorderedNow.put(l.id(), l.reordered());
            }
        }
        for (final Queues.Reading r : Queues.read()) {
            if (r.name().startsWith("link-") && r.name().endsWith("-deliveries")) {
                bound(when, "-", r.name() + ".peak", r.peak(), QUEUE_MAX);
            }
        }
    }

    /// Leaves out of the checks a client subscriber on a node whose sessions dropped messages past their bound.
    private void markOverflowed() {
        for (final Node n : nodes.values()) {
            if (n.frontear.broker().stats().droppedFull() > 0) {
                n.client.unchecked();
            }
        }
    }

    private void bound(final String when, final String node, final String what, final long value, final long max) {
        if (value > max) {
            violation("bounds " + when + ": " + node + ' ' + what + " over its bound [value=" + value + ", max=" + max
                    + ']');
        }
    }

    /// The links of `node` by id, as its `GetState` reply gives them.
    private Map<String, LinkState> state(final String node) {
        final Map<String, LinkState> out = new LinkedHashMap<>();
        for (final JsonNode l : control(node, "GetState", Json.object()).path("links").values()) {
            out.put(l.path("id").asString(), new LinkState(l.path("id").asString(), l.path("connected").asBoolean(),
                    l.path("queued").asLong(), l.path("cursor").asLong(), l.path("tail").asLong(),
                    l.path("sent").asLong(), l.path("dropped").asLong(), l.path("reordered").asLong()));
        }
        return out;
    }

    /// Sends `method` with `body` to `node`, signed; returns the reply once the node applied it.
    private JsonNode control(final String node, final String method, final ObjectNode body) {
        final Node n = nodes.get(node);
        final byte[] correlation = Long.toString(++correlations).getBytes(StandardCharsets.US_ASCII);
        final CompletableFuture<JsonNode> reply = new CompletableFuture<>();
        n.replies.put(HexFormat.of().formatHex(correlation), reply);
        final int code = get(n.control.publishAcknowledged(signer.sign(Names.CONTROL + node + '/' + method,
                Json.writeBytes(body), REPLIES + node, correlation)), node, method);
        if (code != Packet.SUCCESS) {
            throw new IllegalStateException("control request not applied [node=" + node + ", method=" + method
                    + ", reasonCode=" + Packet.hex(code) + ']');
        }
        final JsonNode r = get(reply, node, method);
        if (!r.path("ok").asBoolean(false)) {
            throw new IllegalStateException("control request refused [node=" + node + ", method=" + method
                    + ", reply=" + Json.write(r) + ']');
        }
        if (!"GetState".equals(method)) {
            event("  " + node + ' ' + method + ' ' + Json.write(body));
        }
        return r;
    }

    private Set<String> covering(final String node, final String topic) {
        final Set<String> out = new HashSet<>();
        for (final Planned p : links.values()) {
            if (p.present && p.link.from().equals(node)) {
                for (final String f : p.out) {
                    if (Topics.matches(f, topic)) {
                        out.add(p.link.id());
                    }
                }
            }
        }
        return out;
    }

    private void event(final String what) {
        events.add("+" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos) + "ms " + what);
    }

    private void violation(final String what) {
        violations.add(what);
        event("VIOLATION " + what);
    }

    /// A link as planned: where it goes, its IN filters, the relay in front of its peer, its OUT filters, whether it
    /// is put.
    private static final class Planned {

        private final Ledger.Link link;
        private final List<String> in;
        private Relay relay;
        private List<String> out;
        private boolean present;

        private Planned(final Ledger.Link link, final List<String> out, final List<String> in) {
            this.link = link;
            this.out = out;
            this.in = in;
        }
    }

    /// One node: its frontear, its clients on their own callbacks strand, and its subscribers in the ledger.
    private final class Node {

        private final String name;
        private final List<Ledger.Sink> recorders = new ArrayList<>();
        private final Ledger.Sink client;
        private final Map<String, CompletableFuture<JsonNode>> replies = new ConcurrentHashMap<>();
        /// The probes that came in through a link, `"<origin> <payload>"`; from the broker's threads.
        private final Set<String> probes = ConcurrentHashMap.newKeySet();
        private Frontear frontear;
        private ExecutorService callbacks;
        private MqttClient control;
        private MqttClient publisher;
        private MqttClient subscriber;

        private Node(final String name) {
            this.name = name;
            for (int i = 0; i < RECORDERS.size(); i++) {
                recorders.add(ledger.sink(name, "recorder" + i, RECORDERS.get(i)));
            }
            this.client = ledger.sink(name, "client", List.of("sim/#"));
        }

        /// Starts the node on its data folder, its internal subscribers registered before any link can connect.
        private void start() throws IOException {
            frontear = Frontear.start(name, 0, dir.resolve(name), trusted, InstantSource.system());
            for (final Ledger.Sink r : recorders) {
                for (final String f : r.filters()) {
                    frontear.broker().subscribe("sim-" + r, f, 1, false, (m, _) -> record(r, m));
                }
            }
            if (schedule.check() == Schedule.Check.EXACT) {
                frontear.broker().subscribe("sim-" + name + "/probes", "#", 1, false, (m, origin) -> {
                    if (probe(m)) {
                        probes.add(origin + ' ' + new String(m.payload(), StandardCharsets.US_ASCII));
                    }
                    return ACCEPTED;
                });
            }
            callbacks = Threads.strand("sim-" + name + "-callbacks");
            control = client("pod", m -> {
                final CompletableFuture<JsonNode> f = m.correlation() == null ? null
                        : replies.remove(HexFormat.of().formatHex(m.correlation()));
                if (f != null) {
                    f.complete(Json.readTree(m.payload()));
                }
            });
            publisher = client("publisher", _ -> { });
            subscriber = client("subscriber", m -> {
                if (!probe(m)) {
                    client.arrived(Ledger.Arrival.of(m));
                }
            });
            get(control.connect(), name, "connect pod");
            get(publisher.connect(), name, "connect publisher");
            get(subscriber.connect(), name, "connect subscriber");
            get(control.subscribe(REPLIES + name, 1), name, "subscribe pod");
            get(subscriber.subscribe("sim/#", 1), name, "subscribe subscriber");
        }

        /// Closes the clients, then the node: its links' logs force.
        private void stop() {
            RuntimeException failure = null;
            for (final MqttClient c : new MqttClient[] {control, publisher, subscriber}) {
                if (c != null) {
                    c.close();
                }
            }
            try {
                if (frontear != null) {
                    frontear.close();
                }
            } catch (final RuntimeException e) {
                failure = e;
            }
            if (callbacks != null) {
                ExecutorStop.now("sim-" + name, callbacks);
            }
            control = null;
            publisher = null;
            subscriber = null;
            frontear = null;
            callbacks = null;
            if (failure != null) {
                throw failure;
            }
        }

        private MqttClient client(final String role, final Consumer<Packet.Publish> into) {
            return new MqttClient(new MqttClient.Options(HOST, frontear.port(), name + '-' + role, 0,
                    Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, null), new MqttClient.Handler() {

                        @Override
                        public void connected() {
                        }

                        @Override
                        public void message(final Packet.Publish message) {
                            into.accept(message);
                        }

                        @Override
                        public Duration disconnected(final int attempts, final Throwable cause) {
                            return null;
                        }
                    }, callbacks);
        }

    }
}
