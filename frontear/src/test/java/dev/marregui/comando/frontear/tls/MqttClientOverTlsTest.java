package dev.marregui.comando.frontear.tls;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.loop.ExecutorStop;
import dev.marregui.comando.core.loop.Threads;
import dev.marregui.comando.frontear.protocol.FakeGateway;
import dev.marregui.comando.frontear.protocol.MqttClient;
import dev.marregui.comando.frontear.protocol.Packet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// The client given a TLS context: MQTT over TLS 1.3 through a terminator in front of [FakeGateway], both sides
/// pinned; a plain broker, a server the client does not pin, a server that does not pin the client and a server that
/// never answers the handshake each end the attempt before CONNECT, told to the handler as the cause.
class MqttClientOverTlsTest {

    /// How long one step between two sockets on this machine may take before the test fails.
    private static final Duration WAIT = Duration.ofSeconds(10);
    /// The client's connect bound, 10 s, plus room for a loaded machine.
    private static final Duration HANDSHAKE_BOUND = Duration.ofSeconds(20);

    private static ExecutorService callbacks;

    @TempDir
    Path dir;

    @BeforeAll
    static void startCallbacks() {
        callbacks = Threads.strand("mqtt-tls-test-callbacks");
    }

    @AfterAll
    static void stopCallbacks() {
        if (callbacks != null) {
            ExecutorStop.now("mqtt-tls-test", callbacks);
        }
    }

    /// Over TLS the client connects, subscribes, receives and publishes at QoS 1 as over TCP; the session is TLS 1.3
    /// and the server saw the client's machine key.
    @Test
    void theClientSpeaksMqttOverTls() throws Exception {
        final MachineKey server = key("server");
        final MachineKey client = key("client");
        final Heard heard = new Heard();
        try (FakeGateway gateway = new FakeGateway();
                Terminator terminator = new Terminator(Tls.context(server, pins(client)), gateway.port());
                MqttClient mqtt = client(terminator.port(), heard, Tls.context(client, pins(server)))) {
            mqtt.connect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            mqtt.subscribe("a/#", 1).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            gateway.send(new Packet.Publish("a/1", bytes("in"), 1, false, false, 0, null, null));
            await().atMost(WAIT).until(() -> heard.messages.size() == 1);
            assertThat(heard.messages.getFirst().payload()).isEqualTo(bytes("in"));
            mqtt.publishAcknowledged(new Packet.Publish("b/1", bytes("out"), 1, false, false, 0, null, null))
                    .get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(gateway.received(Packet.Connect.class)).hasSize(1);
            assertThat(heard.connects.get()).isEqualTo(1);
            assertThat(terminator.protocols()).containsExactly(Tls.PROTOCOL);
            assertThat(terminator.peers()).containsExactly(client.fingerprint());
        }
    }

    /// A plain broker does not speak TLS: the handshake fails, the handler hears why, and no CONNECT reached it.
    @Test
    void aPlainGatewayIsRefused() throws Exception {
        final MachineKey client = key("client");
        final Heard heard = new Heard();
        try (FakeGateway gateway = new FakeGateway();
                MqttClient mqtt = client(gateway.port(), heard, Tls.context(client, pins(key("server"))))) {
            mqtt.connect();
            await().atMost(WAIT).until(() -> !heard.causes.isEmpty());
            assertThat(heard.causes.getFirst()).isInstanceOf(SSLException.class);
            assertThat(heard.connects.get()).isZero();
            assertThat(gateway.received(Packet.Connect.class)).isEmpty();
        }
    }

    /// A server whose key the client does not pin is refused by the client at the handshake.
    @Test
    void aServerTheClientDoesNotPinIsRefused() throws Exception {
        final MachineKey server = key("server");
        final MachineKey client = key("client");
        final Heard heard = new Heard();
        try (FakeGateway gateway = new FakeGateway();
                Terminator terminator = new Terminator(Tls.context(server, pins(client)), gateway.port());
                MqttClient mqtt = client(terminator.port(), heard, Tls.context(client, new Pins()))) {
            mqtt.connect();
            await().atMost(WAIT).until(() -> !heard.causes.isEmpty());
            assertThat(heard.causes.getFirst()).isInstanceOf(SSLHandshakeException.class);
            assertThat(heard.connects.get()).isZero();
            assertThat(gateway.received(Packet.Connect.class)).isEmpty();
        }
    }

    /// A server that does not pin the client refuses it at the handshake, before any CONNECT reached the broker,
    /// and the client's handler is told. In TLS 1.3 the client's handshake is done before the server checks the
    /// client's certificate, so the client hears of the refusal at its next read or write: as the server's alert or
    /// as the connection the server closed, whichever comes first.
    @Test
    void aServerThatDoesNotPinTheClientRefusesIt() throws Exception {
        final MachineKey server = key("server");
        final MachineKey client = key("client");
        final Heard heard = new Heard();
        try (FakeGateway gateway = new FakeGateway();
                Terminator terminator = new Terminator(Tls.context(server, new Pins()), gateway.port());
                MqttClient mqtt = client(terminator.port(), heard, Tls.context(client, pins(server)))) {
            mqtt.connect();
            await().atMost(WAIT).until(() -> !heard.causes.isEmpty());
            assertThat(heard.causes.getFirst()).isInstanceOf(IOException.class);
            assertThat(heard.connects.get()).isZero();
            assertThat(gateway.received(Packet.Connect.class)).isEmpty();
            assertThat(terminator.failures()).singleElement().isInstanceOf(SSLHandshakeException.class);
        }
    }

    /// A server that takes the TCP connection and never answers the handshake does not hold the client: the attempt
    /// ends at the connect bound, as a timeout.
    @Test
    void aSilentServerEndsTheAttemptAtTheConnectBound() throws Exception {
        final MachineKey client = key("client");
        final Heard heard = new Heard();
        try (ServerSocket silent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                MqttClient mqtt = client(silent.getLocalPort(), heard, Tls.context(client, pins(key("server"))))) {
            mqtt.connect();
            await().atMost(HANDSHAKE_BOUND).until(() -> !heard.causes.isEmpty());
            assertThat(heard.causes.getFirst()).isInstanceOf(SocketTimeoutException.class);
            assertThat(heard.connects.get()).isZero();
        }
    }

    private MachineKey key(final String machine) throws IOException {
        return MachineKey.loadOrCreate(dir.resolve(machine), machine);
    }

    private static Pins pins(final MachineKey peer) {
        final Pins pins = new Pins();
        pins.set(Map.of(peer.fingerprint(), peer.machine()));
        return pins;
    }

    private static MqttClient client(final int port, final Heard heard, final SSLContext tls) {
        return new MqttClient(new MqttClient.Options("127.0.0.1", port, "tls-client", 60,
                Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, null), heard, callbacks, tls);
    }

    private static byte[] bytes(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /// What the client told its handler; it asks for no reconnect, so one failed attempt is the whole story.
    private static final class Heard implements MqttClient.Handler {

        private final List<Packet.Publish> messages = new CopyOnWriteArrayList<>();
        private final List<Throwable> causes = new CopyOnWriteArrayList<>();
        private final AtomicInteger connects = new AtomicInteger();

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
            causes.add(cause);
            return null;
        }
    }
}
