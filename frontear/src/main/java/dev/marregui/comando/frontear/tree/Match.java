package dev.marregui.comando.frontear.tree;

/** A subscriber's merged match for one topic: the highest QoS over its matching filters; retainAsPublished if any. */
public record Match(String subscriberId, int qos, boolean retainAsPublished) {
}
