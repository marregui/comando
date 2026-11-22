package dev.marregui.comando.mcp;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.jetbrains.annotations.TestOnly;

/**
 * The broker the comando instances on one machine reach each other through (record 123): frontear, over MQTT 5.
 * {@code app} implements it on an MQTT client; {@link Peers} sees only this, so its tests run without a broker.
 */
public interface PeerGateway {

    /**
     * A message as it arrived: topic, payload, the MQTT 5 response topic and correlation data, or null, and the machine
     * it came from as the broker stamped it ({@code @<machine>}, record 189), null for a message of this machine.
     */
    record Message(String topic, byte[] payload, String responseTopic, byte[] correlation, String origin) {

        /** A message of this machine: no origin. */
        @TestOnly
        public Message(final String topic, final byte[] payload, final String responseTopic, final byte[] correlation) {
            this(topic, payload, responseTopic, correlation, null);
        }
    }

    /** Whether the broker is connected now. */
    boolean connected();

    /** Runs {@code listener} at each connect, a reconnect included, before the subscriptions are renewed. */
    void onConnected(Runnable listener);

    /**
     * The message the broker publishes when this client goes without disconnecting; takes effect at the next connect.
     */
    void will(String topic, byte[] payload, boolean retained);

    /** As {@link #publish(String, byte[], boolean, String, byte[], long)}, with no message expiry. */
    default CompletableFuture<Void> publish(
            final String topic,
            final byte[] payload,
            final boolean retained,
            final String responseTopic,
            final byte[] correlation
    ) {
        return publish(topic, payload, retained, responseTopic, correlation, 0L);
    }

    /**
     * At least once; fails at once when not connected. {@code responseTopic} and {@code correlation} may be null; a
     * positive {@code expirySeconds} is the MQTT 5 message expiry, after which no broker or link delivers it, 0 none.
     */
    CompletableFuture<Void> publish(
            String topic,
            byte[] payload,
            boolean retained,
            String responseTopic,
            byte[] correlation,
            long expirySeconds
    );

    /**
     * Delivers messages on topics matching {@code filter} to {@code handler}, now and after every reconnect, at least
     * once: a message may come again, after a reconnect or a redelivery, so {@code handler} must take a repeat as a
     * no-op (a retained message comes again at every subscribe).
     */
    void subscribe(String filter, Consumer<Message> handler);
}
