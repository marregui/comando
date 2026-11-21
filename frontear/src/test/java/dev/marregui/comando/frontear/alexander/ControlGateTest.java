package dev.marregui.comando.frontear.alexander;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// What a client may publish and subscribe to once a control plane runs:
/// - presence, state and every other topic under `$frontear/` are refused, the control topics allowed
/// - topics outside `$frontear/` are allowed
/// - every subscription is allowed, so a client can read state and presence
class ControlGateTest {

    private final ControlGate gate = new ControlGate();

    /// Presence, state and any other reserved branch are refused, at any depth and for any client.
    @Test
    void aClientMayNotPublishToPresenceOrState() {
        for (final String topic : List.of("$frontear/presence/n1", "$frontear/state/n1/links", "$frontear/state/",
                "$frontear/", "$frontear/other/x", "$frontear/controller/n1/PutLink")) {
            assertThat(gate.mayPublish("pod", topic)).as(topic).isFalse();
        }
    }

    /// The control topics and ordinary topics, a look-alike of the reserved prefix included, are allowed.
    @Test
    void aClientMayPublishToControlAndOrdinaryTopics() {
        for (final String topic : List.of("$frontear/control/n1/PutLink", "$frontear/control/", "a/b",
                "frontear/state/n1/links", "$frontearx/state", "$SYS/x")) {
            assertThat(gate.mayPublish("pod", topic)).as(topic).isTrue();
        }
    }

    /// Subscriptions are not restricted.
    @Test
    void aClientMaySubscribeToAnything() {
        for (final String filter : List.of("$frontear/state/#", "$frontear/presence/+", "#", "$frontear/#")) {
            assertThat(gate.maySubscribe("pod", filter)).as(filter).isTrue();
        }
    }
}
