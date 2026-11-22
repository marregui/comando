package dev.marregui.comando.frontear.gateway;

import dev.marregui.comando.frontear.protocol.Packet;

/**
 * Decides what a client may publish and subscribe to. A {@link Gateway} asks it for every PUBLISH, will and SUBSCRIBE
 * filter a client sends, never for {@link Gateway#publish}; until one is installed, a client may publish and subscribe
 * anywhere outside {@code $frontear/}. It is called on the client's reader thread and must not block.
 * <p>
 * The broker asks the three methods that take a {@code principal}: null for a client of the loopback listener, the
 * machine its certificate names for one of the remote listener ({@link RemoteListen}). Their defaults refuse every
 * remote connection and pass a local one to the two methods without a principal, so a gate that knows nothing of
 * other machines lets none in. A PUBLISH or will with its retain flag set that may be published is then asked of
 * {@link #mayRetain}.
 */
public interface Gate {

    /** Whether {@code clientId} may publish to {@code topic}; a refused QoS 1 PUBLISH is acknowledged with 0x87. */
    boolean mayPublish(String clientId, String topic);

    /** Whether {@code clientId} may subscribe to {@code filter}; a refused filter is acknowledged with 0x87. */
    boolean maySubscribe(String clientId, String filter);

    /** Whether a client of {@code principal} may connect as {@code clientId}; a refused CONNECT is answered 0x87. */
    default boolean mayConnect(final String principal, final String clientId) {
        return principal == null;
    }

    /** As {@link #mayPublish(String, String)}, for a client of {@code principal}. */
    default boolean mayPublish(final String principal, final String clientId, final String topic) {
        return principal == null && mayPublish(clientId, topic);
    }

    /** As {@link #maySubscribe(String, String)}, for a client of {@code principal}. */
    default boolean maySubscribe(final String principal, final String clientId, final String filter) {
        return principal == null && maySubscribe(clientId, filter);
    }

    /**
     * Whether a client of {@code principal} may have the broker retain {@code message}, whose topic it may publish
     * to: a PUBLISH with its retain flag set, an empty one clearing its topic's retained message, or a will with a
     * payload and its retain flag set, asked at its CONNECT. A refused PUBLISH is acknowledged with 0x87, a refused
     * will refuses its CONNECT with 0x87. The broker's own bounds hold either way, so by default every one may.
     */
    default boolean mayRetain(final String principal, final String clientId, final Packet.Publish message) {
        return true;
    }
}
