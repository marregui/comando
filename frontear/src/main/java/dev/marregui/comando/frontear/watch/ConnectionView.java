package dev.marregui.comando.frontear.watch;

/**
 * A client connected to the broker: since {@code sinceMillis} (the node's clock), with the keep-alive its CONNECT
 * asked, its QoS 1 messages {@code inflight} beside the {@code receiveMaximum} it allows.
 */
public record ConnectionView(String clientId, long sinceMillis, int keepAliveSeconds, int inflight,
        int receiveMaximum) {
}
