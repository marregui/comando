package dev.marregui.comando.app;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

import dev.marregui.comando.core.loop.ExecutorStop;
import dev.marregui.comando.core.loop.Threads;
import dev.marregui.comando.frontear.protocol.MqttClient;
import dev.marregui.comando.frontear.protocol.Names;
import dev.marregui.comando.frontear.protocol.Packet;
import dev.marregui.comando.frontear.protocol.Topics;
import dev.marregui.comando.mcp.PeerGateway;

/**
 * frontear as the instances' broker (records 123, 185, 187), on comando's own MQTT client: MQTT 5 to {@code 127.0.0.1},
 * client id {@code comando-<instance>}. It follows this instance's own frontear node rather than the window: every
 * {@link #FOLLOW_EVERY}, on a strand of its own, it asks whether the node runs, and the answer may start it; it
 * connects when it runs, again when its port changed, and disconnects when it does not. While the node runs the
 * client reconnects by itself, 1 s after a drop and then at most every {@link #FOLLOW_EVERY}, and renews its
 * subscriptions after every connect, and at the next look when one was refused; once the node stops it stops trying.
 * Messages reach their handlers on one strand, in order.
 */
final class MqttPeerGateway implements PeerGateway, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(MqttPeerGateway.class.getName());
    static final Duration FOLLOW_EVERY = Duration.ofSeconds(5);
    private static final String HOST = "127.0.0.1";
    /** The keep alive the HiveMQ client sent, which this client replaced. */
    private static final int KEEP_ALIVE_SECONDS = 60;
    /**
     * The largest message frontear may hand this instance, in bytes: the protocol's own, so none is announced, as the
     * client before comando's own announced none. A question to another instance is not clipped, and a smaller limit
     * would make the broker drop a long one unseen while its sender waits for an answer.
     */
    static final int MAXIMUM_PACKET_SIZE = Packet.PROTOCOL_MAXIMUM_PACKET_SIZE;

    private final String clientId;
    private final IntSupplier port;
    private final BooleanSupplier octopusUp;
    private final LongSupplier generation;
    private final ScheduledExecutorService follow = Threads.timedStrand("peer-broker");
    private final ExecutorService messages = Threads.strand("peer-messages");
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final List<LongConsumer> subscribed = new CopyOnWriteArrayList<>();
    /** The filters subscribed, in order, with their handlers; guarded by itself. */
    private final Map<String, Consumer<Message>> handlers = new LinkedHashMap<>();
    /** Written by whoever sets the will, read by the follow strand when it connects. */
    private volatile Packet.Publish will;
    /** The client while frontear runs; null while it does not. Guarded by this. */
    private MqttClient client;
    /** The port {@link #client} connects to; guarded by this. */
    private int clientPort;
    /**
     * {@link #client} when a filter of its last round of subscriptions was refused, so the next look renews the round;
     * else null. Guarded by this.
     */
    private MqttClient refused;
    private boolean closed;

    /**
     * Client {@code clientId} on frontear's MQTT port, as {@code port} says it at each look, connected while
     * {@code octopusUp} says the node runs; {@code generation} says which start of the node runs now, a later start a
     * greater one, and the subscribed listeners hear it.
     */
    MqttPeerGateway(
            final String clientId,
            final IntSupplier port,
            final BooleanSupplier octopusUp,
            final LongSupplier generation
    ) {
        this.clientId = clientId;
        this.port = port;
        this.octopusUp = octopusUp;
        this.generation = generation;
    }

    /** Starts following frontear: a first look at once, then every {@link #FOLLOW_EVERY}. */
    void start() {
        follow.scheduleWithFixedDelay(this::follow, 0, FOLLOW_EVERY.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Connects when the node runs and no client exists, again when the node's port is not the client's, and
     * disconnects when the node does not run; renews a connected client's subscriptions when one of them was refused.
     */
    void follow() {
        // an exception escaping a periodic task cancels every later run
        try {
            final boolean up = octopusUp.getAsBoolean();
            final int at = port.getAsInt();
            final MqttClient again;
            final int againAt;
            synchronized (this) {
                if (closed) {
                    return;
                }
                if (up && client != null && clientPort != at) {
                    final MqttClient moved = client;
                    final int was = clientPort;
                    client = null;
                    moved.disconnect().whenComplete((v, e) -> LOG.log(System.Logger.Level.INFO,
                            "left frontear's old port [client=" + clientId + ", port=" + was + ", now=" + at + ']'));
                }
                if (up && client == null) {
                    clientPort = at;
                    client = connect(at);
                } else if (!up && client != null) {
                    final MqttClient gone = client;
                    client = null;
                    gone.disconnect().whenComplete((v, e) -> LOG.log(System.Logger.Level.INFO,
                            "left frontear: it no longer runs"));
                }
                again = client != null && client == refused && client.connected() ? client : null;
                againAt = clientPort;
                if (again != null) {
                    refused = null;
                }
            }
            if (again != null) {
                LOG.log(System.Logger.Level.INFO, "renewing the subscriptions frontear refused [client=" + clientId
                        + ", port=" + againAt + ']');
                subscribeAll(again, againAt);
            }
        } catch (final RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot follow frontear", e);
        }
    }

    private MqttClient connect(final int at) {
        final AtomicReference<MqttClient> self = new AtomicReference<>();
        final MqttClient.Options options = new MqttClient.Options(HOST, at, clientId, KEEP_ALIVE_SECONDS,
                MAXIMUM_PACKET_SIZE, will);
        // the client's callbacks on the message strand: connects and messages in the order they happened
        final MqttClient c = new MqttClient(options, new MqttClient.Handler() {

            @Override
            public void connected() {
                onConnect(at);
            }

            @Override
            public void message(final Packet.Publish message) {
                deliver(message);
            }

            // reconnects, a first connect that failed included, for as long as it is the client of a running node
            @Override
            public Duration disconnected(final int attempts, final Throwable cause) {
                return wanted(self.get()) ? Duration.ofMillis(reconnectDelay(attempts)) : null;
            }
        }, messages);
        self.set(c);
        c.connect().whenComplete((v, e) -> {
            if (e != null) {
                LOG.log(System.Logger.Level.DEBUG, "not connected to frontear yet, retrying [cause="
                        + e.getMessage() + ']');
            }
        });
        return c;
    }

    /** Whether {@code c} is still the client to keep connected: this broker is open and has not left frontear since. */
    private synchronized boolean wanted(final MqttClient c) {
        return !closed && c != null && c == client;
    }

    /** 1 s after the first failure, doubling, at most {@link #FOLLOW_EVERY}. */
    static long reconnectDelay(final int attempts) {
        return Math.min(FOLLOW_EVERY.toMillis(), 1_000L << Math.min(Math.max(attempts, 0), 10));
    }

    /** Connected, first or again: the listeners first, then the subscriptions, whose retained messages follow. */
    private void onConnect(final int at) {
        LOG.log(System.Logger.Level.INFO, "connected to frontear [client=" + clientId + ", port=" + at + ']');
        for (final Runnable l : listeners) {
            try {
                l.run();
            } catch (final RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "a connect listener failed", e);
            }
        }
        final MqttClient c = current();
        if (c == null) {
            return;
        }
        subscribeAll(c, at);
    }

    /**
     * Subscribes {@code c}, on port {@code at}, to every filter: once all are acknowledged the subscribed listeners
     * hear the node generation read before the first went; when one is refused, the next look renews the round.
     */
    private void subscribeAll(final MqttClient c, final int at) {
        // read once connected, before the subscribes go: a node takes its generation before it binds, so this is the
        // connected node's or a later one's, never an earlier one's; an acknowledgement that comes late, from a node
        // closed since, thus names that node and opens no later node's links
        final long against = generation.getAsLong();
        final List<String> filters;
        synchronized (handlers) {
            filters = List.copyOf(handlers.keySet());
        }
        final List<CompletableFuture<Void>> acks = new ArrayList<>();
        for (final String f : filters) {
            acks.add(subscribe(c, f));
        }
        CompletableFuture.allOf(acks.toArray(CompletableFuture[]::new)).whenComplete((v, e) -> {
            roundEnded(c, e == null);
            if (e == null) {
                subscribed(at, against);
            }
        });
    }

    /** {@code c}'s round ended, every filter acknowledged or not: while it is the client, the next look knows. */
    private synchronized void roundEnded(final MqttClient c, final boolean acknowledged) {
        if (c == client) {
            refused = acknowledged ? null : c;
        }
    }

    private static CompletableFuture<Void> subscribe(final MqttClient c, final String filter) {
        return c.subscribe(filter, 1).whenComplete((v, e) -> {
            if (e != null) {
                LOG.log(System.Logger.Level.WARNING, "subscription failed [filter=" + filter + ']', e);
            }
        });
    }

    /**
     * Every filter of a connect to port {@code at}, made against node generation {@code against}, is acknowledged:
     * the listeners hear the generation.
     */
    private void subscribed(final int at, final long against) {
        for (final LongConsumer l : subscribed) {
            try {
                l.accept(against);
            } catch (final RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "a subscribed listener failed [port=" + at + ", generation="
                        + against + ']', e);
            }
        }
    }

    /**
     * Runs {@code listener} with the node's generation once every filter a connect renewed is acknowledged, at each
     * connect, on the client's thread, or at the look that renewed them after one was refused; not for a filter
     * subscribed later. The node's host lets the other nodes' links in then, so what they queued for this instance
     * finds its subscribers in place.
     */
    void onSubscribed(final LongConsumer listener) {
        subscribed.add(listener);
    }

    /** Hands a message to every handler whose filter matches its topic. */
    void deliver(final Packet.Publish p) {
        final Message m = message(p);
        final List<Consumer<Message>> matching = new ArrayList<>();
        synchronized (handlers) {
            handlers.forEach((filter, handler) -> {
                if (Topics.matches(filter, p.topic())) {
                    matching.add(handler);
                }
            });
        }
        for (final Consumer<Message> h : matching) {
            try {
                h.accept(m);
            } catch (final RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "a message handler failed [topic=" + m.topic() + ']', e);
            }
        }
    }

    /**
     * A received publish as {@link PeerGateway} hands it on, with the machine its {@link Names#ORIGIN} user property
     * names, the last one when several came: the broker removes every one a remote client sent and adds its own after
     * them.
     */
    static Message message(final Packet.Publish p) {
        String origin = null;
        for (final Packet.UserProperty u : p.meta().userProperties()) {
            if (Names.ORIGIN.equals(u.name())) {
                origin = u.value();
            }
        }
        return new Message(p.topic(), p.payload(), p.responseTopic(), p.correlation(), origin);
    }

    /** What {@link #publish} sends: at least once, with the response topic and correlation data when given. */
    static Packet.Publish publishOf(final String topic, final byte[] payload, final boolean retained,
            final String responseTopic, final byte[] correlation) {
        return publishOf(topic, payload, retained, responseTopic, correlation, 0L);
    }

    /** As above, with MQTT 5 message expiry {@code expirySeconds} when positive, none at 0. */
    static Packet.Publish publishOf(
            final String topic,
            final byte[] payload,
            final boolean retained,
            final String responseTopic,
            final byte[] correlation,
            final long expirySeconds
    ) {
        final Packet.Publish p = new Packet.Publish(topic, payload, 1, retained, false, 0, responseTopic, correlation);
        return expirySeconds > 0 ? p.withMeta(Packet.Meta.NONE.withMessageExpiry(expirySeconds)) : p;
    }

    private synchronized MqttClient current() {
        return client;
    }

    @Override
    public boolean connected() {
        final MqttClient c = current();
        return c != null && c.connected();
    }

    @Override
    public void onConnected(final Runnable listener) {
        listeners.add(listener);
    }

    @Override
    public void will(final String topic, final byte[] payload, final boolean retained) {
        will = publishOf(topic, payload, retained, null, null);
    }

    @Override
    public CompletableFuture<Void> publish(
            final String topic,
            final byte[] payload,
            final boolean retained,
            final String responseTopic,
            final byte[] correlation,
            final long expirySeconds
    ) {
        final MqttClient c = current();
        if (c == null || !c.connected()) {
            return CompletableFuture.failedFuture(new IllegalStateException("not connected to frontear"));
        }
        return c.publish(publishOf(topic, payload, retained, responseTopic, correlation, expirySeconds));
    }

    @Override
    public void subscribe(final String filter, final Consumer<Message> handler) {
        synchronized (handlers) {
            handlers.put(filter, handler);
        }
        final MqttClient c = current();
        if (c != null && c.connected()) {
            subscribe(c, filter);
        }
    }

    /** Disconnects, so the broker drops the last will: whoever closes this has cleared what it published. */
    @Override
    public void close() {
        final MqttClient c;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            c = client;
            client = null;
        }
        ExecutorStop.now("peer-broker", follow);
        if (c != null) {
            c.close();
        }
        ExecutorStop.now("peer-broker", messages);
    }
}
