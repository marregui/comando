package dev.marregui.comando.app;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.MqttClientState;
import com.hivemq.client.mqtt.MqttGlobalPublishFilter;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.datatypes.MqttTopic;
import com.hivemq.client.mqtt.datatypes.MqttTopicFilter;
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish;
import com.hivemq.client.mqtt.mqtt5.message.subscribe.Mqtt5Subscribe;
import dev.marregui.comando.core.loop.Threads;
import dev.marregui.comando.mcp.PeerBroker;
import io.reactivex.schedulers.Schedulers;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The comando Edge as the instances' broker (record 123), on the HiveMQ MQTT client: MQTT 5 to {@code 127.0.0.1},
 * client id {@code comando-<instance>}. It follows the Edge rather than the window: every {@link #FOLLOW_EVERY}, on a
 * strand of its own, it reads whether the registry names a live Edge, whichever instance started it; it connects when
 * one does and disconnects when none does. While the Edge runs the client reconnects by itself, 1 s after a drop and
 * then at most every {@link #FOLLOW_EVERY}, and renews its subscriptions after every connect; once the Edge stops it
 * stops trying. Messages reach their handlers on one strand, in order.
 */
final class MqttPeerBroker implements PeerBroker, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(MqttPeerBroker.class.getName());
    static final Duration FOLLOW_EVERY = Duration.ofSeconds(5);
    private static final Duration CLOSE_WAIT = Duration.ofSeconds(2);
    private static final String HOST = "127.0.0.1";

    private final String clientId;
    private final int port;
    private final BooleanSupplier edgeUp;
    private final ScheduledExecutorService follow = Threads.timedStrand("peer-broker");
    private final ExecutorService messages = Threads.strand("peer-messages");
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    /** The filters subscribed, in order, with their handlers; guarded by itself. */
    private final Map<String, Consumer<Message>> handlers = new LinkedHashMap<>();
    private volatile Mqtt5Publish will;
    /** The client while the Edge runs; null while it does not. Guarded by this. */
    private Mqtt5AsyncClient client;
    private boolean closed;

    /** Client {@code clientId} on the Edge's MQTT {@code port}, connected while {@code edgeUp} says the Edge runs. */
    MqttPeerBroker(String clientId, int port, BooleanSupplier edgeUp) {
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
            boolean up = edgeUp.getAsBoolean();
            synchronized (this) {
                if (closed) {
                    return;
                }
                if (up && client == null) {
                    client = connect();
                } else if (!up && client != null) {
                    Mqtt5AsyncClient gone = client;
                    client = null;
                    gone.disconnect().whenComplete((v, e) -> LOG.log(System.Logger.Level.INFO, "left the comando Edge: it no longer runs"));
                }
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot follow the comando Edge", e);
        }
    }

    private Mqtt5AsyncClient connect() {
        AtomicReference<Mqtt5AsyncClient> self = new AtomicReference<>();
        Mqtt5AsyncClient c = MqttClient.builder().useMqttVersion5().identifier(clientId).serverHost(HOST).serverPort(port)
                // one event loop thread, and the client's callbacks on a strand rather than RxJava's pool
                .executorConfig().nettyThreads(1).applicationScheduler(Schedulers.from(messages)).applyExecutorConfig()
                .addConnectedListener(context -> messages.execute(this::onConnect))
                // reconnects by itself, a first connect that failed included, for as long as it is the client of a
                // running Edge; the client's automatic reconnect would go on after the Edge stopped, as a disconnect
                // while it waits fails
                .addDisconnectedListener(context -> {
                    if (wanted(self.get())) {
                        context.getReconnector().reconnect(true).delay(reconnectDelay(context.getReconnector().getAttempts()), TimeUnit.MILLISECONDS);
                    }
                })
                .buildAsync();
        self.set(c);
        c.publishes(MqttGlobalPublishFilter.ALL, this::deliver, messages);
        c.connectWith().cleanStart(true).sessionExpiryInterval(0).willPublish(will).send().whenComplete((ack, e) -> {
            if (e != null) {
                LOG.log(System.Logger.Level.DEBUG, "not connected to the comando Edge yet, retrying [cause=" + e.getMessage() + ']');
            }
        });
        return c;
    }

    /** Whether {@code c} is still the client to keep connected: this broker is open and has not left the Edge since. */
    private synchronized boolean wanted(Mqtt5AsyncClient c) {
        return !closed && c != null && c == client;
    }

    /** 1 s after the first failure, doubling, at most {@link #FOLLOW_EVERY}. */
    static long reconnectDelay(int attempts) {
        return Math.min(FOLLOW_EVERY.toMillis(), 1_000L << Math.min(Math.max(attempts, 0), 10));
    }

    /** Connected, first or again: the listeners first, then the subscriptions, whose retained messages follow. */
    private void onConnect() {
        LOG.log(System.Logger.Level.INFO, "connected to the comando Edge [client=" + clientId + ", port=" + port + ']');
        for (Runnable l : listeners) {
            try {
                l.run();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "a connect listener failed", e);
            }
        }
        Mqtt5AsyncClient c = current();
        if (c == null) {
            return;
        }
        List<String> filters;
        synchronized (handlers) {
            filters = List.copyOf(handlers.keySet());
        }
        for (String f : filters) {
            subscribe(c, f);
        }
    }

    private static void subscribe(Mqtt5AsyncClient c, String filter) {
        c.subscribe(Mqtt5Subscribe.builder().topicFilter(filter).qos(MqttQos.AT_LEAST_ONCE).build()).whenComplete((ack, e) -> {
            if (e != null) {
                LOG.log(System.Logger.Level.WARNING, "subscription failed [filter=" + filter + ']', e);
            }
        });
    }

    /** Hands a message to every handler whose filter matches its topic. */
    void deliver(Mqtt5Publish p) {
        Message m = message(p);
        List<Consumer<Message>> matching = new ArrayList<>();
        synchronized (handlers) {
            handlers.forEach((filter, handler) -> {
                if (MqttTopicFilter.of(filter).matches(p.getTopic())) {
                    matching.add(handler);
                }
            });
        }
        for (Consumer<Message> h : matching) {
            try {
                h.accept(m);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "a message handler failed [topic=" + m.topic() + ']', e);
            }
        }
    }

    /** A received publish as {@link PeerBroker} hands it on. */
    static Message message(Mqtt5Publish p) {
        return new Message(p.getTopic().toString(), p.getPayloadAsBytes(), p.getResponseTopic().map(MqttTopic::toString).orElse(null),
                p.getCorrelationData().map(MqttPeerBroker::bytes).orElse(null));
    }

    /** What {@link #publish} sends: at least once, with the response topic and correlation data when given. */
    static Mqtt5Publish publishOf(String topic, byte[] payload, boolean retained, String responseTopic, byte[] correlation) {
        return Mqtt5Publish.builder().topic(topic).payload(payload).qos(MqttQos.AT_LEAST_ONCE).retain(retained)
                .responseTopic(responseTopic).correlationData(correlation).build();
    }

    private static byte[] bytes(ByteBuffer b) {
        byte[] out = new byte[b.remaining()];
        b.duplicate().get(out);
        return out;
    }

    private synchronized Mqtt5AsyncClient current() {
        return client;
    }

    @Override
    public boolean connected() {
        Mqtt5AsyncClient c = current();
        return c != null && c.getState() == MqttClientState.CONNECTED;
    }

    @Override
    public void onConnected(Runnable listener) {
        listeners.add(listener);
    }

    @Override
    public void will(String topic, byte[] payload, boolean retained) {
        will = publishOf(topic, payload, retained, null, null);
    }

    @Override
    public CompletableFuture<Void> publish(String topic, byte[] payload, boolean retained, String responseTopic, byte[] correlation) {
        Mqtt5AsyncClient c = current();
        if (c == null || c.getState() != MqttClientState.CONNECTED) {
            return CompletableFuture.failedFuture(new IllegalStateException("not connected to the comando Edge"));
        }
        return c.publish(publishOf(topic, payload, retained, responseTopic, correlation)).thenAccept(r -> {
            if (r.getError().isPresent()) {
                throw new CompletionException(r.getError().get());
            }
        });
    }

    @Override
    public void subscribe(String filter, Consumer<Message> handler) {
        synchronized (handlers) {
            handlers.put(filter, handler);
        }
        Mqtt5AsyncClient c = current();
        if (c != null && c.getState() == MqttClientState.CONNECTED) {
            subscribe(c, filter);
        }
    }

    /** Disconnects, so the broker drops the last will: whoever closes this has cleared what it published. */
    @Override
    public void close() {
        Mqtt5AsyncClient c;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            c = client;
            client = null;
        }
        follow.shutdownNow();
        if (c != null) {
            try {
                c.disconnect().get(CLOSE_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (ExecutionException | TimeoutException e) {
                LOG.log(System.Logger.Level.DEBUG, "disconnect from the comando Edge incomplete [cause=" + e.getMessage() + ']');
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        messages.shutdownNow();
    }
}
