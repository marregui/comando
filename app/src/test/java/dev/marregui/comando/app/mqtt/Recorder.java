package dev.marregui.comando.app.mqtt;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/// What a client told its handler, for a test to read: connects, messages in arrival order, and each end with its
/// attempts and cause. It asks for a reconnect after `retry`, none when null.
final class Recorder implements MqttClient.Handler {

    final List<Packet.Publish> messages = new CopyOnWriteArrayList<>();
    final AtomicInteger connects = new AtomicInteger();
    final List<Integer> attempts = new CopyOnWriteArrayList<>();
    final List<Throwable> causes = new CopyOnWriteArrayList<>();
    /// Written by the test thread, read by the client's reader.
    volatile Duration retry = Duration.ofMillis(50);

    @Override
    public void connected() {
        connects.incrementAndGet();
    }

    @Override
    public void message(final Packet.Publish message) {
        messages.add(message);
    }

    @Override
    public Duration disconnected(final int attempts, final Throwable cause) {
        this.attempts.add(attempts);
        causes.add(cause);
        return retry;
    }
}
