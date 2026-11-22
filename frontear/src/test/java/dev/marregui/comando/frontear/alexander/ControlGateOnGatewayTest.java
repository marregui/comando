package dev.marregui.comando.frontear.alexander;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.InstantSource;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.loop.ExecutorStop;
import dev.marregui.comando.core.loop.Threads;
import dev.marregui.comando.frontear.gateway.Gateway;
import dev.marregui.comando.frontear.gateway.GatewayOptions;
import dev.marregui.comando.frontear.protocol.MqttClient;
import dev.marregui.comando.frontear.protocol.Packet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// The gate a control plane installs, seen by an MQTT client over TCP:
/// - a client's publish to `$frontear/presence/` or `$frontear/state/` is refused with 0x87 and reaches no one
/// - a client's publish to `$frontear/control/` reaches the plane, which judges its signature
class ControlGateOnGatewayTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    /// Presence and state publishes are acknowledged with 0x87 and not delivered; an unsigned control publish
    /// passes the gate and is refused by the plane as unsigned.
    @Test
    void aClientMayNotPublishPresenceOrStateButReachesControl() throws Exception {
        final List<Packet.Publish> reserved = new CopyOnWriteArrayList<>();
        final ExecutorService callbacks = Threads.strand("control-gate-test-callbacks");
        try (Gateway gateway = Gateway.start(new GatewayOptions(new InetSocketAddress(InetAddress.getLoopbackAddress(),
                0), 65_535, Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, 100, InstantSource.system()));
                ControlPlane plane = new ControlPlane(gateway, new FakeOctopus(), "n1",
                        Set.of(KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic()),
                        new TestClock(System.currentTimeMillis()));
                MqttClient client = new MqttClient(new MqttClient.Options("127.0.0.1", gateway.port(), "pod", 0,
                        Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, null), new Quiet(), callbacks)) {
            gateway.subscribe("test-presence", "$frontear/presence/#", 1, false, (message, _) -> {
                reserved.add(message);
                return CompletableFuture.completedFuture(Packet.SUCCESS);
            });
            gateway.subscribe("test-state", "$frontear/state/+/forged", 1, false, (message, _) -> {
                reserved.add(message);
                return CompletableFuture.completedFuture(Packet.SUCCESS);
            });
            client.connect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            // the gateway's own presence for the client shows the subscribers receive what reaches them
            await().atMost(WAIT).until(() -> reserved.stream()
                    .anyMatch(message -> message.topic().equals("$frontear/presence/pod")));
            final byte[] forged = "{\"forged\":true}".getBytes(StandardCharsets.UTF_8);
            for (final String topic : List.of("$frontear/presence/n1", "$frontear/state/n1/forged")) {
                assertThat(client.publishAcknowledged(new Packet.Publish(topic, forged, 1, false, false, 0, null,
                        null)).get(WAIT.toMillis(), TimeUnit.MILLISECONDS)).as(topic).isEqualTo(Packet.NOT_AUTHORIZED);
            }
            assertThat(client.publishAcknowledged(new Packet.Publish("$frontear/control/n1/GetState", forged, 1, false,
                    false, 0, null, null)).get(WAIT.toMillis(), TimeUnit.MILLISECONDS))
                    .isEqualTo(Refusal.UNSIGNED.reasonCode());
            assertThat(plane.refused(Refusal.UNSIGNED)).isEqualTo(1);
            assertThat(reserved).noneMatch(message -> Arrays.equals(message.payload(), forged));
        } finally {
            ExecutorStop.now("control-gate-test", callbacks);
        }
    }

    /// A handler that asks for no reconnect.
    private static final class Quiet implements MqttClient.Handler {

        @Override
        public void connected() {
        }

        @Override
        public void message(final Packet.Publish message) {
        }

        @Override
        public Duration disconnected(final int attempts, final Throwable cause) {
            return null;
        }
    }
}
