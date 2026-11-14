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

import dev.marregui.comando.app.mqtt.MqttClient;
import dev.marregui.comando.app.mqtt.Packet;
import dev.marregui.comando.app.mqtt.Topics;
import dev.marregui.comando.core.loop.ExecutorStop;
import dev.marregui.comando.core.loop.Threads;
import dev.marregui.comando.mcp.PeerBroker;

/**
 * The comando Edge as the instances' broker (record 123), on comando's own MQTT client: MQTT 5 to {@code 127.0.0.1},
 * client id {@code comando-<instance>}. It follows the Edge rather than the window: every {@link #FOLLOW_EVERY}, on a
 * strand of its own, it reads whether the registry names a live Edge, whichever instance started it; it connects when
 * one does and disconnects when none does. While the Edge runs the client reconnects by itself, 1 s after a drop and
 * then at most every {@link #FOLLOW_EVERY}, and renews its subscriptions after every connect; once the Edge stops it
 * stops trying. Messages reach their handlers on one strand, in order.
 */
final class MqttPeerBroker implements PeerBroker, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(MqttPeerBroker.class.getName());
    static final Duration FOLLOW_EVERY = Duration.ofSeconds(5);
    private static final String HOST = "127.0.0.1";
    /** The keep alive the HiveMQ client sent, which this client replaced. */
    private static final int KEEP_ALIVE_SECONDS = 60;
    /**
     * The largest message the Edge may hand this instance, in bytes: the protocol's own, so none is announced, as the
     * client before comando's own announced none. A question to another instance is not clipped, and a smaller limit
     * would make the broker drop a long one unseen while its sender waits for an answer.
     */
    static final int MAXIMUM_PACKET_SIZE = Packet.PROTOCOL_MAXIMUM_PACKET_SIZE;

    private final String clientId;
    private final int port;
    private final BooleanSupplier edgeUp;
    private final ScheduledExecutorService follow = Threads.timedStrand("peer-broker");
    private final ExecutorService messages = Threads.strand("peer-messages");
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    /** The filters subscribed, in order, with their handlers; guarded by itself. */
    private final Map<String, Consumer<Message>> handlers = new LinkedHashMap<>();
    /** Written by whoever sets the will, read by the follow strand when it connects. */
    private volatile Packet.Publish will;
    /** The client while the Edge runs; null while it does not. Guarded by this. */
    private MqttClient client;
    private boolean closed;

    /** Client {@code clientId} on the Edge's MQTT {@code port}, connected while {@code edgeUp} says the Edge runs. */
    MqttPeerBroker(final String clientId, final int port, final BooleanSupplier edgeUp) {
        this.clientId = clientId;
        this.port = port;
        this.edgeUp = edgeUp;
    }

    /** Starts following the Edge: a first look at once, then every {@link #FOLLOW_EVERY}. */
    void start() {
        follow.scheduleWithFixedDelay(this::follow, 0, FOLLOW_EVERY.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Connects when the registry names a live Edge and no client exists, disconnects when it names none. */
    void follow() {
        // an exception escaping a periodic task cancels every later run
        try {
            final boolean up = edgeUp.getAsBoolean();
            synchronized (this) {
                if (closed) {
                    return;
                }
                if (up && client == null) {
                    client = connect();
                } else if (!up && client != null) {
                    final MqttClient gone = client;
                    client = null;
                    gone.disconnect().whenComplete((v, e) -> LOG.log(System.Logger.Level.INFO,
                            "left the comando Edge: it no longer runs"));
                }
            }
        } catch (final RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot follow the comando Edge", e);
        }
    }

    private MqttClient connect() {
        final AtomicReference<MqttClient> self = new AtomicReference<>();
        final MqttClient.Options options = new MqttClient.Options(HOST, port, clientId, KEEP_ALIVE_SECONDS,
                MAXIMUM_PACKET_SIZE, will);
        // the client's callbacks on the message strand: connects and messages in the order they happened
        final MqttClient c = new MqttClient(options, new MqttClient.Handler() {

            @Override
            public void connected() {
                onConnect();
            }

            @Override
            public void message(final Packet.Publish message) {
                deliver(message);
            }

            // reconnects, a first connect that failed included, for as long as it is the client of a running Edge
            @Override
            public Duration disconnected(final int attempts, final Throwable cause) {
                return wanted(self.get()) ? Duration.ofMillis(reconnectDelay(attempts)) : null;
            }
        }, messages);
        self.set(c);
        c.connect().whenComplete((v, e) -> {
            if (e != null) {
                LOG.log(System.Logger.Level.DEBUG, "not connected to the comando Edge yet, retrying [cause="
                        + e.getMessage() + ']');
            }
        });
        return c;
    }

    /** Whether {@code c} is still the client to keep connected: this broker is open and has not left the Edge since. */
    private synchronized boolean wanted(final MqttClient c) {
        return !closed && c != null && c == client;
    }

    /** 1 s after the first failure, doubling, at most {@link #FOLLOW_EVERY}. */
    static long reconnectDelay(final int attempts) {
        return Math.min(FOLLOW_EVERY.toMillis(), 1_000L << Math.min(Math.max(attempts, 0), 10));
    }

    /** Connected, first or again: the listeners first, then the subscriptions, whose retained messages follow. */
    private void onConnect() {
        LOG.log(System.Logger.Level.INFO, "connected to the comando Edge [client=" + clientId + ", port=" + port + ']');
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
        final List<String> filters;
        synchronized (handlers) {
            filters = List.copyOf(handlers.keySet());
        }
        for (final String f : filters) {
            subscribe(c, f);
        }
    }

    private static void subscribe(final MqttClient c, final String filter) {
        c.subscribe(filter, 1).whenComplete((v, e) -> {
            if (e != null) {
                LOG.log(System.Logger.Level.WARNING, "subscription failed [filter=" + filter + ']', e);
            }
        });
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

    /** A received publish as {@link PeerBroker} hands it on. */
    static Message message(final Packet.Publish p) {
        return new Message(p.topic(), p.payload(), p.responseTopic(), p.correlation());
    }

    /** What {@link #publish} sends: at least once, with the response topic and correlation data when given. */
    static Packet.Publish publishOf(final String topic, final byte[] payload, final boolean retained,
            final String responseTopic, final byte[] correlation) {
        return new Packet.Publish(topic, payload, 1, retained, false, 0, responseTopic, correlation);
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
    public CompletableFuture<Void> publish(final String topic, final byte[] payload, final boolean retained,
            final String responseTopic, final byte[] correlation) {
        final MqttClient c = current();
        if (c == null || !c.connected()) {
            return CompletableFuture.failedFuture(new IllegalStateException("not connected to the comando Edge"));
        }
        return c.publish(publishOf(topic, payload, retained, responseTopic, correlation));
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
