package dev.marregui.comando.frontear.gateway;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.net.ssl.SSLException;

import dev.marregui.comando.frontear.protocol.Codec;
import dev.marregui.comando.frontear.protocol.Packet;

import static org.assertj.core.api.Assertions.assertThat;

/// An MQTT client written by hand on a socket: it sends what a test builds, raw bytes included, and reads back each
/// packet the broker sends, every read bounded so a missing answer fails the test instead of hanging it.
final class RawClient implements AutoCloseable {

    // a read waiting longer than this fails the test; the broker answers a local client in milliseconds
    private static final int READ_MILLIS = 10_000;
    private static final HexFormat HEX = HexFormat.ofDelimiter(" ");

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    private RawClient(final int port) throws IOException {
        this(new Socket(InetAddress.getLoopbackAddress(), port));
        socket.setSoTimeout(READ_MILLIS);
    }

    private RawClient(final Socket socket) throws IOException {
        this.socket = socket;
        in = new BufferedInputStream(socket.getInputStream());
        out = socket.getOutputStream();
    }

    /// A client on `socket`, connected and with its read bound set, that has sent nothing yet: a TLS one, say.
    static RawClient over(final Socket socket) throws IOException {
        return new RawClient(socket);
    }

    /// A connection to `port` that has sent nothing yet.
    static RawClient open(final int port) throws IOException {
        return new RawClient(port);
    }

    /// A connection to `port` that sent `connect`; the CONNACK is the caller's to read.
    static RawClient open(final int port, final Packet.Connect connect) throws IOException {
        final RawClient c = new RawClient(port);
        c.send(connect);
        return c;
    }

    /// A client the broker accepted as `clientId`, with a clean start and a session that ends with the connection.
    static RawClient connected(final int port, final String clientId) throws IOException {
        return connected(port, connect(clientId, true, 0));
    }

    /// A client the broker accepted for `connect`.
    static RawClient connected(final int port, final Packet.Connect connect) throws IOException {
        final RawClient c = open(port, connect);
        assertThat(c.read(Packet.ConnAck.class).reasonCode()).isEqualTo(Packet.SUCCESS);
        return c;
    }

    /// A CONNECT with no keep alive, no will, and the protocol's Receive Maximum and Maximum Packet Size.
    static Packet.Connect connect(final String clientId, final boolean cleanStart, final long sessionExpirySeconds) {
        return new Packet.Connect(clientId, cleanStart, 0, sessionExpirySeconds, Packet.DEFAULT_RECEIVE_MAXIMUM,
                Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, null);
    }

    /// A QoS 1 message on `topic` with `payload` as UTF-8, packet id 1.
    static Packet.Publish qos1(final String topic, final String payload) {
        return new Packet.Publish(topic, payload.getBytes(StandardCharsets.UTF_8), 1, false, false, 1, null, null);
    }

    /// Sends `packet`.
    void send(final Packet packet) throws IOException {
        out.write(Codec.encode(packet));
        out.flush();
    }

    /// Sends the bytes `hex` spells, space-separated.
    void sendHex(final String hex) throws IOException {
        out.write(HEX.parseHex(hex));
        out.flush();
    }

    /// The next packet the broker sent.
    Packet read() throws IOException {
        final int header = in.read();
        if (header < 0) {
            throw new EOFException("broker closed the connection");
        }
        return Codec.read(in, header, Packet.PROTOCOL_MAXIMUM_PACKET_SIZE);
    }

    /// The next packet the broker sent, which must be a `type`.
    <T extends Packet> T read(final Class<T> type) throws IOException {
        final Packet p = read();
        assertThat(p).isInstanceOf(type);
        return type.cast(p);
    }

    /// The next packet, a PUBLISH, acknowledged when it is QoS 1.
    Packet.Publish receive() throws IOException {
        final Packet.Publish p = read(Packet.Publish.class);
        if (p.qos() == 1) {
            send(new Packet.PubAck(p.packetId(), Packet.SUCCESS, null));
        }
        return p;
    }

    /// Publishes `message` at QoS 1 and returns its PUBACK's reason code; the PUBACK must be the next packet.
    int publish(final Packet.Publish message) throws IOException {
        send(message);
        final Packet.PubAck a = read(Packet.PubAck.class);
        assertThat(a.packetId()).isEqualTo(message.packetId());
        return a.reasonCode();
    }

    /// Subscribes with `subscriptions` as packet 1 and returns the SUBACK's reason codes.
    List<Integer> subscribe(final Packet.Subscription... subscriptions) throws IOException {
        send(new Packet.Subscribe(1, List.of(subscriptions)));
        final Packet.SubAck a = read(Packet.SubAck.class);
        assertThat(a.packetId()).isEqualTo(1);
        return a.reasonCodes();
    }

    /// Sends PINGREQ and returns what came before its PINGRESP: everything the broker had queued for this client.
    List<Packet> ping() throws IOException {
        send(new Packet.PingReq());
        final List<Packet> before = new ArrayList<>();
        for (Packet p = read(); !(p instanceof Packet.PingResp); p = read()) {
            before.add(p);
        }
        return before;
    }

    /// Reads until the broker closes the connection and returns what it sent before that.
    List<Packet> untilClosed() throws IOException {
        final List<Packet> before = new ArrayList<>();
        try {
            while (true) {
                before.add(read());
            }
        } catch (final EOFException | SocketException | SSLException _) {
            // the broker closed it, by FIN or by reset, or a TLS one by an alert
            return before;
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
