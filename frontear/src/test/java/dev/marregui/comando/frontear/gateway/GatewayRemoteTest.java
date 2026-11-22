package dev.marregui.comando.frontear.gateway;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.cert.Certificate;
import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.frontear.alexander.RemoteGate;
import dev.marregui.comando.frontear.protocol.Codec;
import dev.marregui.comando.frontear.protocol.Names;
import dev.marregui.comando.frontear.protocol.Packet;
import dev.marregui.comando.frontear.watch.Watch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/// The remote listener (ADR 0189 §2.1) of a gateway started as node `main` of machine `here`, behind a [RemoteGate]:
/// TLS 1.3 with both sides pinned, what a connection of a paired machine may do, the machine stamped on what it
/// publishes, the session its link holds, the clients closed unanswered and counted, the bounds, the connections
/// ended when their machine is unpaired, and the listener's close beside a loopback listener that goes on as before.
class GatewayRemoteTest {

    private static final String HERE = "here";
    private static final String NODE = "main";
    private static final String INBOX = "comando/v1/here/instance/x/inbox";
    // far above the remote clients any test here opens, unless it sets the bound it tests
    private static final int MAX_CONNECTIONS = 8;

    @TempDir
    static Path keys;
    private static RemoteTls.Machine here;
    private static RemoteTls.Machine there;
    private static RemoteTls.Machine yonder;
    // trusted by the listener's TLS context, but paired with no machine: machineOf names none for it
    private static RemoteTls.Machine unpaired;
    // trusted by nothing
    private static RemoteTls.Machine stranger;

    private Gateway gateway;
    private SSLContext server;
    private int remotePort;

    @BeforeAll
    static void makeKeys() throws Exception {
        here = RemoteTls.make(keys, HERE);
        there = RemoteTls.make(keys, "there");
        yonder = RemoteTls.make(keys, "yonder");
        unpaired = RemoteTls.make(keys, "unpaired");
        stranger = RemoteTls.make(keys, "stranger");
    }

    @BeforeEach
    void start() throws Exception {
        gateway = Gateway.start(new GatewayOptions(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 8,
                64 * 1024, 16, InstantSource.system(), 4), new Watch(1), NODE);
        gateway.gate(new RemoteGate(new Open(), HERE));
        server = RemoteTls.context(here, there, yonder, unpaired);
        remotePort = gateway.listenRemote(listen(MAX_CONNECTIONS, RemoteTls.HANDSHAKE));
    }

    @AfterEach
    void stop() {
        gateway.close();
    }

    /// A paired machine's link client publishes to an inbox of this machine, PUBACK 0x00, and a local subscriber gets
    /// the message with one `frontear-origin`, `@there`, in place of the two the client set; its other user
    /// properties go through as they were sent.
    @Test
    void aPairedLinkPublishesToAnInboxHereStampedWithItsMachine() throws Exception {
        try (RawClient reader = RawClient.connected(gateway.port(), "reader");
                RawClient remote = connected(there, "a~main")) {
            assertThat(reader.subscribe(new Packet.Subscription("comando/v1/here/instance/+/inbox", 1)))
                    .containsExactly(1);
            final Packet.Publish forged = withProperties(RawClient.qos1(INBOX, "question"), List.of(
                    new Packet.UserProperty(Names.ORIGIN, "@yonder"), new Packet.UserProperty("k", "v"),
                    new Packet.UserProperty(Names.ORIGIN, "@here")));
            assertThat(remote.publish(forged)).isEqualTo(Packet.SUCCESS);
            final Packet.Publish got = reader.receive();
            assertThat(new String(got.payload(), StandardCharsets.UTF_8)).isEqualTo("question");
            assertThat(origins(got)).containsExactly("@there");
            assertThat(got.meta().userProperties()).contains(new Packet.UserProperty("k", "v"));
        }
    }

    /// A link client may aim at this node as `<node>@<this machine>` too, and publish to a reply topic here and to
    /// the presence of its own machine's instances.
    @Test
    void aPairedLinkMayNameThisNodeWithThisMachineAndAnswerAndAnnounce() throws Exception {
        try (RawClient remote = connected(there, "main~main@here")) {
            assertThat(remote.publish(RawClient.qos1("comando/v1/here/instance/x/reply", "answer")))
                    .isEqualTo(Packet.NO_MATCHING_SUBSCRIBERS);
            assertThat(remote.publish(RawClient.qos1("comando/v1/there/instance/x/presence", "{}")))
                    .isEqualTo(Packet.NO_MATCHING_SUBSCRIBERS);
        }
    }

    /// The same client is refused, 0x87, a publish to this machine's presence, to `$frontear/control/...`, to another
    /// machine's presence or inbox, and to a topic one level off; and a `SUBSCRIBE #`.
    @Test
    void aPairedLinkIsRefusedEveryOtherTopicAndEverySubscription() throws Exception {
        try (RawClient remote = connected(there, "a~main")) {
            for (final String topic : List.of("comando/v1/here/instance/x/presence", Names.CONTROL + "main/put",
                    "comando/v1/other/instance/x/presence", "comando/v1/there/instance/x/inbox", INBOX + "/more",
                    "comando/v1/here/instances/x/inbox")) {
                assertThat(remote.publish(RawClient.qos1(topic, "x"))).as(topic).isEqualTo(Packet.NOT_AUTHORIZED);
            }
            assertThat(remote.subscribe(new Packet.Subscription("#", 1))).containsExactly(Packet.NOT_AUTHORIZED);
            assertThat(remote.subscribe(new Packet.Subscription(INBOX, 1))).containsExactly(Packet.NOT_AUTHORIZED);
        }
    }

    /// A remote CONNECT whose client id is no link's, or a link's aimed at another node or machine, is refused: 0x87
    /// by the gate, 0x85 for a node this one is not; the connection closes after the CONNACK.
    @Test
    void aRemoteClientMustConnectAsALinkToThisNode() throws Exception {
        for (final String id : List.of("a", "", "a~b~main", "a@there~main", "~main", "a~", "a~main@elsewhere",
                "a~@here")) {
            assertThat(refusal(there, id)).as(id).isEqualTo(Packet.NOT_AUTHORIZED);
        }
        for (final String id : List.of("a~other", "a~other@here")) {
            assertThat(refusal(there, id)).as(id).isEqualTo(Packet.CLIENT_ID_NOT_VALID);
        }
    }

    /// A will a remote client set is stamped with its machine too: when the connection drops, the local subscriber
    /// gets it with `frontear-origin` `@there` alone.
    @Test
    void theWillOfARemoteClientIsStampedWithItsMachine() throws Exception {
        final String presence = "comando/v1/there/instance/a/presence";
        try (RawClient reader = RawClient.connected(gateway.port(), "reader")) {
            assertThat(reader.subscribe(new Packet.Subscription(presence, 1))).containsExactly(1);
            final Packet.Publish will = withProperties(new Packet.Publish(presence, "{}".getBytes(
                    StandardCharsets.UTF_8), 1, false, false, 0, null, null), List.of(new Packet.UserProperty(
                    Names.ORIGIN, "@here")));
            final RawClient remote = connected(there, new Packet.Connect("a~main", true, 0, 0,
                    Packet.DEFAULT_RECEIVE_MAXIMUM, Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, will));
            remote.close();
            assertThat(origins(reader.receive())).containsExactly("@there");
        }
    }

    /// A remote link holds a session of its own machine, `<slug>@<machine>~<node>`: a local link and two machines'
    /// links with one client id connect side by side, and none takes another's session over. The same machine
    /// naming this node with this machine's id holds the same session.
    @Test
    void aRemoteLinkHoldsASessionOfItsOwnMachine() throws Exception {
        final List<String> accepted = new CopyOnWriteArrayList<>();
        gateway.addConnectListener(accepted::add);
        try (RawClient local = RawClient.connected(gateway.port(), "a~main");
                RawClient fromThere = connected(there, "a~main");
                RawClient fromYonder = connected(yonder, "a~main")) {
            assertThat(local.ping()).isEmpty();
            assertThat(fromThere.ping()).isEmpty();
            assertThat(fromYonder.ping()).isEmpty();
            try (RawClient again = connected(there, "a~main@here")) {
                assertThat(again.ping()).isEmpty();
                assertThat(fromThere.untilClosed()).containsExactly(new Packet.Disconnect(Packet.SESSION_TAKEN_OVER,
                        null));
            }
            assertThat(local.ping()).isEmpty();
        }
        assertThat(accepted).containsExactly("a~main", "a@there~main", "a@yonder~main", "a@there~main");
    }

    /// A client whose key is pinned by no one fails its handshake, and one whose key the listener trusts but no
    /// machine is paired with is closed past it: neither gets a CONNACK, each is counted under its kind.
    @Test
    void anUnpinnedOrUnpairedPeerGetsNoConnAckAndIsCounted() throws Exception {
        final long failures = gateway.remoteStats().handshakeFailures();
        assertThat(unanswered(RemoteTls.dial(RemoteTls.context(stranger, here), remotePort))).isEmpty();
        await().atMost(TestGateway.WAIT).until(() -> gateway.remoteStats().handshakeFailures() == failures + 1);
        assertThat(gateway.remoteStats().unpinned()).isZero();
        assertThat(unanswered(RemoteTls.dial(RemoteTls.context(unpaired, here), remotePort))).isEmpty();
        await().atMost(TestGateway.WAIT).until(() -> gateway.remoteStats().unpinned() == 1);
        assertThat(gateway.remoteStats().handshakeFailures()).isEqualTo(failures + 1);
    }

    /// A plain TCP client on the TLS port sends a CONNECT the handshake cannot read: it gets no CONNACK, only the
    /// socket's close, and the failed handshake is counted.
    @Test
    void aPlainTcpClientOnTheTlsPortGetsNoConnAckAndIsCounted() throws Exception {
        final long failures = gateway.remoteStats().handshakeFailures();
        try (Socket plain = new Socket(InetAddress.getLoopbackAddress(), remotePort)) {
            plain.setSoTimeout((int) TestGateway.WAIT.toMillis());
            plain.getOutputStream().write(Codec.encode(RawClient.connect("a~main", true, 0)));
            plain.getOutputStream().flush();
            assertThat(isConnAck(untilEnd(plain.getInputStream()))).isFalse();
        }
        await().atMost(TestGateway.WAIT).until(() -> gateway.remoteStats().handshakeFailures() == failures + 1);
    }

    /// A client that opens the TLS port and sends nothing is closed once the handshake bound passes, and counted.
    @Test
    void aHandshakeThatOutlastsItsBoundIsClosedAndCounted() throws Exception {
        gateway.closeRemote();
        remotePort = gateway.listenRemote(listen(MAX_CONNECTIONS, Duration.ofMillis(200)));
        final long failures = gateway.remoteStats().handshakeFailures();
        try (Socket silent = new Socket(InetAddress.getLoopbackAddress(), remotePort)) {
            silent.setSoTimeout((int) TestGateway.WAIT.toMillis());
            // what comes before the close is at most the TLS alerts of an abandoned handshake, never an MQTT packet
            assertThat(isConnAck(untilEnd(silent.getInputStream()))).isFalse();
        }
        await().atMost(TestGateway.WAIT).until(() -> gateway.remoteStats().handshakeFailures() == failures + 1);
    }

    /// A client that trickles a byte every 200 ms, each read in the handshake well within its bound, is closed once
    /// the 1 s bound has passed since its accept, and counted; its place, the only one, takes the next client.
    @Test
    void aHandshakeThatTricklesIsClosedAtItsBoundFromTheAccept() throws Exception {
        gateway.closeRemote();
        remotePort = gateway.listenRemote(listen(1, Duration.ofSeconds(1)));
        final long failures = gateway.remoteStats().handshakeFailures();
        final long started = System.nanoTime();
        long closedNanos = -1;
        try (Socket slow = new Socket(InetAddress.getLoopbackAddress(), remotePort)) {
            // the read timeout paces the trickle
            slow.setSoTimeout(200);
            // a TLS handshake record header announcing 16 KiB, then its body a byte at a time
            slow.getOutputStream().write(new byte[] {0x16, 0x03, 0x01, 0x40, 0x00});
            while (System.nanoTime() - started < Duration.ofSeconds(5).toNanos()) {
                try {
                    slow.getOutputStream().write(1);
                    if (slow.getInputStream().read() < 0) {
                        closedNanos = System.nanoTime() - started;
                        break;
                    }
                } catch (final SocketTimeoutException _) {
                    // still open: the next byte
                } catch (final SocketException _) {
                    closedNanos = System.nanoTime() - started;
                    break;
                }
            }
        }
        assertThat(closedNanos).as("closed, in ns").isBetween(Duration.ofMillis(800).toNanos(),
                Duration.ofSeconds(3).toNanos());
        await().atMost(TestGateway.WAIT).until(() -> gateway.remoteStats().handshakeFailures() == failures + 1);
        await().atMost(TestGateway.WAIT).until(() -> gateway.remoteStats().connections() == 0);
        try (RawClient next = connected(there, "a~main")) {
            assertThat(next.ping()).isEmpty();
        }
    }

    /// Once the pairings change, `recheckRemote` ends with DISCONNECT 0x87 the connection of a machine no longer
    /// paired and the one whose key now names another machine, and keeps the one still paired.
    @Test
    void aConnectionWhoseMachineIsNoLongerPairedEndsAtTheRecheck() throws Exception {
        final AtomicReference<Function<Certificate, String>> paired = new AtomicReference<>(
                RemoteTls.machineOf(there, yonder, unpaired));
        gateway.closeRemote();
        remotePort = gateway.listenRemote(RemoteTls.listen(server, peer -> paired.get().apply(peer), HERE,
                MAX_CONNECTIONS, RemoteTls.HANDSHAKE));
        try (RawClient dropped = connected(there, "a~main");
                RawClient renamed = connected(unpaired, "b~main");
                RawClient kept = connected(yonder, "c~main")) {
            gateway.recheckRemote();
            assertThat(kept.ping()).isEmpty();
            paired.set(peer -> peer.getPublicKey().equals(yonder.key()) ? "yonder"
                    : peer.getPublicKey().equals(unpaired.key()) ? "there" : null);
            gateway.recheckRemote();
            assertThat(dropped.untilClosed()).containsExactly(new Packet.Disconnect(Packet.NOT_AUTHORIZED, null));
            assertThat(renamed.untilClosed()).containsExactly(new Packet.Disconnect(Packet.NOT_AUTHORIZED, null));
            assertThat(kept.ping()).isEmpty();
            await().atMost(TestGateway.WAIT).until(() -> gateway.remoteStats().connections() == 1);
        }
    }

    /// A paired machine has the gateway retain at most 64 presences at once: the next one is refused 0x87, and a
    /// retained will past them refuses its CONNECT; a presence it holds is retained again.
    @Test
    void aRemoteMachineRetainsAtMost64Presences() throws Exception {
        try (RawClient remote = connected(there, "a~main")) {
            for (int i = 0; i < 64; i++) {
                assertThat(remote.publish(retained("comando/v1/there/instance/i" + i + "/presence"))).as("i" + i)
                        .isEqualTo(Packet.NO_MATCHING_SUBSCRIBERS);
            }
            assertThat(remote.publish(retained("comando/v1/there/instance/x/presence")))
                    .isEqualTo(Packet.NOT_AUTHORIZED);
            assertThat(remote.publish(retained("comando/v1/there/instance/i0/presence")))
                    .isEqualTo(Packet.NO_MATCHING_SUBSCRIBERS);
        }
        final Packet.Publish will = new Packet.Publish("comando/v1/there/instance/x/presence",
                "{}".getBytes(StandardCharsets.UTF_8), 1, true, false, 0, null, null);
        try (RawClient c = RemoteTls.dial(RemoteTls.context(there, here), remotePort)) {
            c.send(new Packet.Connect("b~main", true, 0, 0, Packet.DEFAULT_RECEIVE_MAXIMUM,
                    Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, will));
            assertThat(c.read(Packet.ConnAck.class).reasonCode()).isEqualTo(Packet.NOT_AUTHORIZED);
        }
    }

    /// Past `maxConnections` a client is closed before its handshake and counted; once a connection ends, another
    /// gets in.
    @Test
    void pastItsBoundAClientIsClosedUnansweredAndCounted() throws Exception {
        gateway.closeRemote();
        remotePort = gateway.listenRemote(listen(1, RemoteTls.HANDSHAKE));
        try (RawClient first = connected(there, "a~main")) {
            assertThat(gateway.remoteStats().connections()).isEqualTo(1);
            assertThat(gateway.remoteStats().maxConnections()).isEqualTo(1);
            assertThat(unanswered(RemoteTls.dial(RemoteTls.context(yonder, here), remotePort))).isEmpty();
            await().atMost(TestGateway.WAIT).until(() -> gateway.remoteStats().refused() == 1);
            assertThat(first.ping()).isEmpty();
        }
        await().atMost(TestGateway.WAIT).until(() -> gateway.remoteStats().connections() == 0);
        try (RawClient next = connected(yonder, "a~main")) {
            assertThat(next.ping()).isEmpty();
        }
    }

    /// `closeRemote` sends a connected remote client DISCONNECT 0x8B and closes it, frees the port (the listener opens
    /// on it again), and leaves the loopback listener and its client as they were; a second call does nothing.
    @Test
    void closeRemoteEndsTheRemoteConnectionsAndFreesThePort() throws Exception {
        try (RawClient local = RawClient.connected(gateway.port(), "local");
                RawClient remote = connected(there, "a~main");
                RawClient handshaking = RemoteTls.dial(RemoteTls.context(there, here), remotePort)) {
            gateway.closeRemote();
            assertThat(remote.untilClosed()).containsExactly(new Packet.Disconnect(Packet.SERVER_SHUTTING_DOWN,
                    null));
            assertThat(unanswered(handshaking)).isEmpty();
            gateway.closeRemote();
            assertThat(gateway.remoteStats().port()).isZero();
            assertThat(gateway.remoteStats().connections()).isZero();
            assertThat(local.ping()).isEmpty();
            try (RawClient late = RawClient.connected(gateway.port(), "late")) {
                assertThat(late.ping()).isEmpty();
            }
        }
        assertThat(gateway.listenRemote(listen(remotePort, MAX_CONNECTIONS, RemoteTls.HANDSHAKE)))
                .isEqualTo(remotePort);
        try (RawClient again = connected(there, "a~main")) {
            assertThat(again.ping()).isEmpty();
        }
    }

    /// A second `listenRemote` while one is open is refused, and so is one after the gateway closed; the close ends
    /// the remote listener with the gateway's.
    @Test
    void theRemoteListenerOpensOnceAndClosesWithTheGateway() throws Exception {
        assertThatThrownBy(() -> gateway.listenRemote(listen(MAX_CONNECTIONS, RemoteTls.HANDSHAKE)))
                .isInstanceOf(IllegalStateException.class);
        try (RawClient remote = connected(there, "a~main")) {
            gateway.close();
            assertThat(remote.untilClosed()).containsExactly(new Packet.Disconnect(Packet.SERVER_SHUTTING_DOWN,
                    null));
        }
        assertThat(gateway.remoteStats().port()).isZero();
        assertThatThrownBy(() -> gateway.listenRemote(listen(MAX_CONNECTIONS, RemoteTls.HANDSHAKE)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new Socket(InetAddress.getLoopbackAddress(), remotePort).close())
                .isInstanceOf(IOException.class);
    }

    /// Without a gate that knows other machines, every remote CONNECT is refused 0x87: a [Gate]'s defaults let no
    /// remote connection in. A local client stays as it was: what it publishes carries the `frontear-origin` it set.
    @Test
    void aGateThatKnowsNoMachineLetsNoRemoteClientIn() throws Exception {
        gateway.gate(new Open());
        assertThat(refusal(there, "a~main")).isEqualTo(Packet.NOT_AUTHORIZED);
        try (RawClient reader = RawClient.connected(gateway.port(), "reader");
                RawClient local = RawClient.connected(gateway.port(), "local")) {
            assertThat(reader.subscribe(new Packet.Subscription(INBOX, 1))).containsExactly(1);
            assertThat(local.publish(withProperties(RawClient.qos1(INBOX, "q"), List.of(new Packet.UserProperty(
                    Names.ORIGIN, "@there"))))).isEqualTo(Packet.SUCCESS);
            assertThat(origins(reader.receive())).containsExactly("@there");
        }
    }

    /// A failure kind is logged the first time, then at most once a minute with the count since; each is counted,
    /// and the count not yet told is told at the listener's close.
    @Test
    void aRecurringFailureIsCountedAlwaysAndToldOnceAMinute() {
        final Gateway.Failures f = new Gateway.Failures("test failure");
        final long minute = Gateway.Failures.TELL_EVERY_NANOS;
        assertThat(f.add("c", "first", 5)).isEqualTo(1);
        assertThat(f.add("c", "second", 6)).isZero();
        assertThat(f.add("c", "third", 5 + minute - 1)).isZero();
        assertThat(f.add("c", "fourth", 5 + minute)).isEqualTo(3);
        assertThat(f.flush()).isZero();
        assertThat(f.add("c", "fifth", 6 + minute)).isZero();
        assertThat(f.flush()).isEqualTo(1);
        assertThat(f.count()).isEqualTo(5);
    }

    private RemoteListen listen(final int maxConnections, final Duration handshake) {
        return RemoteTls.listen(server, RemoteTls.machineOf(there, yonder), HERE, maxConnections, handshake);
    }

    private RemoteListen listen(final int port, final int maxConnections, final Duration handshake) {
        return RemoteTls.listen(server, RemoteTls.machineOf(there, yonder), HERE, port, maxConnections, handshake);
    }

    /// A client of machine `m`, pinning this machine's key, that the gateway accepted as `clientId`.
    private RawClient connected(final RemoteTls.Machine m, final String clientId) throws Exception {
        return connected(m, RawClient.connect(clientId, true, 0));
    }

    private RawClient connected(final RemoteTls.Machine m, final Packet.Connect connect) throws Exception {
        final RawClient c = RemoteTls.dial(RemoteTls.context(m, here), remotePort);
        c.send(connect);
        assertThat(c.read(Packet.ConnAck.class).reasonCode()).as(connect.clientId()).isEqualTo(Packet.SUCCESS);
        return c;
    }

    /// The reason code of the CONNACK machine `m`'s client gets for `clientId`, the connection closed after it.
    private int refusal(final RemoteTls.Machine m, final String clientId) throws Exception {
        try (RawClient c = RemoteTls.dial(RemoteTls.context(m, here), remotePort)) {
            c.send(RawClient.connect(clientId, true, 0));
            final int code = c.read(Packet.ConnAck.class).reasonCode();
            assertThat(c.untilClosed()).as(clientId).isEmpty();
            return code;
        }
    }

    /// What the gateway sent `c` after its CONNECT until it closed the connection; a handshake refused while the
    /// CONNECT was written counts as nothing sent.
    private static List<Packet> unanswered(final RawClient c) throws IOException {
        try (c) {
            try {
                c.send(RawClient.connect("a~main", true, 0));
            } catch (final SSLException | SocketException _) {
                // the gateway closed the connection in the handshake, before the CONNECT went out
                return List.of();
            }
            return c.untilClosed();
        }
    }

    /// The bytes `in` gives until the peer closes, a reset taken as a close.
    private static byte[] untilEnd(final InputStream in) throws IOException {
        try {
            return in.readAllBytes();
        } catch (final SocketException _) {
            return new byte[0];
        }
    }

    /// Whether `bytes` start as a CONNACK: packet type 2 (§2.1.2).
    private static boolean isConnAck(final byte[] bytes) {
        return bytes.length > 0 && (bytes[0] & 0xF0) == 0x20;
    }

    /// A retained QoS 1 presence on `topic`.
    private static Packet.Publish retained(final String topic) {
        final Packet.Publish p = RawClient.qos1(topic, "{}");
        return new Packet.Publish(p.topic(), p.payload(), 1, true, false, p.packetId(), null, null);
    }

    private static Packet.Publish withProperties(final Packet.Publish p, final List<Packet.UserProperty> properties) {
        return p.withMeta(new Packet.Meta(-1, -1, null, properties));
    }

    /// The values of `p`'s `frontear-origin` user properties, in order.
    private static List<String> origins(final Packet.Publish p) {
        return p.meta().userProperties().stream().filter(u -> u.name().equals(Names.ORIGIN))
                .map(Packet.UserProperty::value).toList();
    }

    /// A local gate that lets a client of this machine publish and subscribe anywhere outside `$frontear/`.
    private static final class Open implements Gate {

        @Override
        public boolean mayPublish(final String clientId, final String topic) {
            return !topic.startsWith(Names.RESERVED);
        }

        @Override
        public boolean maySubscribe(final String clientId, final String filter) {
            return !filter.startsWith(Names.RESERVED);
        }
    }
}
