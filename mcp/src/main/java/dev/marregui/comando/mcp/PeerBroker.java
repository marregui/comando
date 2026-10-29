package dev.marregui.comando.mcp;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The broker the comando instances on one machine reach each other through (record 123): the comando Edge, over MQTT 5.
 * {@code app} implements it on an MQTT client; {@link Peers} sees only this, so its tests run without a broker.
 */
public interface PeerBroker {

    /** A message as it arrived: topic, payload, and the MQTT 5 response topic and correlation data, or null. */
    record Message(String topic, byte[] payload, String responseTopic, byte[] correlation) {
    }

    /** Whether the broker is connected now. */
    boolean connected();

    /** Runs {@code listener} at each connect, a reconnect included, before the subscriptions are renewed. */
    void onConnected(Runnable listener);

    /**
     * The message the broker publishes when this client goes without disconnecting; takes effect at the next connect.
     */
    void will(String topic, byte[] payload, boolean retained);

    /** At least once; fails at once when not connected. {@code responseTopic} and {@code correlation} may be null. */
    CompletableFuture<Void> publish(String topic, byte[] payload, boolean retained, String responseTopic, byte[] correlation);

    /** Delivers messages on topics matching {@code filter} to {@code handler}, now and after every reconnect. */
    void subscribe(String filter, Consumer<Message> handler);
}
