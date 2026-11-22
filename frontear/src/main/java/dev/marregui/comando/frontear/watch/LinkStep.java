package dev.marregui.comando.frontear.watch;

/**
 * A step of link {@code link}: a message {@code kind} at log offset {@code offset} (-1 when it has none) on
 * {@code topic} with a payload {@code bytes} long, or a connection change, whose {@code topic} is null and
 * {@code bytes} 0. {@code detail} is empty, or says why a message was dropped or a connection ended.
 */
public record LinkStep(long seq, long atMillis, String link, Kind kind, long offset, String topic, int bytes,
        String detail) implements Observation {

    /** What the link did. */
    public enum Kind {
        /** A message is durable in the link's log at {@code offset}. */
        APPENDED,
        /** The peer accepted the message at {@code offset}, and the cursor may pass it. */
        SENT,
        /** A message came in over a link and the gateway routed it. */
        ARRIVED,
        /** A message will not reach the peer, or the local gateway, for the reason in {@code detail}. */
        DROPPED,
        /** The peer accepted the link's connection. */
        CONNECTED,
        /** The link's connection ended or was refused, for the cause in {@code detail}. */
        DISCONNECTED
    }
}
