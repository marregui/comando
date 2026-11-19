package dev.marregui.comando.frontear.control;

import dev.marregui.comando.frontear.broker.Gate;
import dev.marregui.comando.frontear.mqtt.Names;

/**
 * What a client may publish once a {@link ControlPlane} runs: anything outside {@code $frontear/}, and under it only
 * {@code $frontear/control/}, where the signature decides. Presence and state, and every other topic under
 * {@code $frontear/}, are the broker's to write. A client may subscribe to anything, so it can read state and presence.
 */
final class ControlGate implements Gate {

    @Override
    public boolean mayPublish(final String clientId, final String topic) {
        return !topic.startsWith(Names.RESERVED) || topic.startsWith(Names.CONTROL);
    }

    @Override
    public boolean maySubscribe(final String clientId, final String filter) {
        return true;
    }
}
