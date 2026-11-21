package dev.marregui.comando.frontear.channels;

import java.util.Objects;

/**
 * One subscription of one subscriber: its owner (client id, or a link's id) decides No Local. {@code qos} is the
 * maximum QoS granted on the filter, 0 to 2.
 */
public record Entry(String subscriberId, String owner, int qos, boolean noLocal, boolean retainAsPublished) {

    /** Refuses a null id or owner, or a QoS out of range. */
    public Entry {
        Objects.requireNonNull(subscriberId, "subscriberId");
        Objects.requireNonNull(owner, "owner");
        if (qos < 0 || qos > 2) {
            throw new IllegalArgumentException("QoS out of range [qos=" + qos + ']');
        }
    }
}
