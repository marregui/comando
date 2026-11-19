package dev.marregui.comando.frontear.broker;

import java.util.concurrent.CompletionStage;

import dev.marregui.comando.frontear.mqtt.Packet;

/**
 * Receives the messages matching its subscriptions, inside the broker. It is called on the publisher's thread and must
 * not block: it hands the message off and completes the stage when it is done with it. A client's PUBACK waits for
 * every internal subscriber the message reached, so a stage that never completes holds that client's later PUBACKs.
 */
public interface InternalSubscriber {

    /**
     * Takes {@code message}, published by {@code origin} (a client id, or the origin given to {@link Broker#publish});
     * completes with a reason code: 0x00 accepted (durable where it persists), 0x97 quota exceeded, another code at or
     * above 0x80 for a refusal. A stage that fails counts as 0x80.
     */
    CompletionStage<Integer> deliver(Packet.Publish message, String origin);
}
