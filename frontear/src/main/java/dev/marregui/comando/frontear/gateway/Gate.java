package dev.marregui.comando.frontear.gateway;

/**
 * Decides what a client may publish and subscribe to. A {@link Broker} asks it for every PUBLISH, will and SUBSCRIBE
 * filter a client sends, never for {@link Broker#publish}; until one is installed, a client may publish and subscribe
 * anywhere outside {@code $frontear/}. It is called on the client's reader thread and must not block.
 */
public interface Gate {

    /** Whether {@code clientId} may publish to {@code topic}; a refused QoS 1 PUBLISH is acknowledged with 0x87. */
    boolean mayPublish(String clientId, String topic);

    /** Whether {@code clientId} may subscribe to {@code filter}; a refused filter is acknowledged with 0x87. */
    boolean maySubscribe(String clientId, String filter);
}
