package dev.marregui.comando.app;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import dev.marregui.comando.frontear.mqtt.Codec;
import dev.marregui.comando.frontear.mqtt.MqttException;
import dev.marregui.comando.frontear.mqtt.Packet;

/// One raw MQTT 5 connection of a parity scenario: it sends what the scenario writes, frontear's codec encoding it or
/// [ParityRaw] where the codec does not, and reads what the broker sends back on the scenario's own thread, recording
/// every packet in the [ParityRun]'s transcript. A QoS 1 PUBLISH it reads is acknowledged at once unless the scenario
/// turned that off. A QoS 2 PUBREC or PUBCOMP, which the codec does not read, is read here.
final class ParityProbe implements AutoCloseable {

    /// How long a connection must stay silent for a scenario to take that nothing more is coming: both brokers answer
    /// on loopback in milliseconds, and send a retained or queued message within one answer; the rest is margin for
    /// a busy machine.
    static final Duration QUIET = Duration.ofMillis(500);
    /// How long a read waits for a packet the scenario expects: ten times [#QUIET], so a slow answer is a difference
    /// the transcript shows rather than a silence.
    static final Duration WAIT = Duration.ofSeconds(5);

    private static final int PUBREC = 5;
    private static final int PUBREL = 6;
    private static final int PUBCOMP = 7;

    private final InputStream in;
    private final String label;
    private final OutputStream out;
    private final ParityRun run;
    private final Socket socket;
    private boolean autoAck = true;
    private boolean closed;
    private int nextPacketId = 1;
    private boolean recording = true;

    /// A connection labelled `label` of `run`, to the broker at `at`.
    ParityProbe(final ParityRun run, final String label, final InetSocketAddress at) throws IOException {
        this.run = run;
        this.label = label;
        final Socket s = new Socket();
        try {
            s.connect(at, (int) WAIT.toMillis());
            s.setTcpNoDelay(true);
            this.in = new BufferedInputStream(s.getInputStream());
            this.out = s.getOutputStream();
        } catch (final IOException | RuntimeException e) {
            s.close();
            throw e;
        }
        this.socket = s;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            socket.close();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// Sends `connect` and reads the CONNACK, recorded without its capabilities; returns it, or null for none.
    Packet connect(final Packet.Connect connect) {
        send(connect);
        return next(false);
    }

    /// As [#connect], the CONNACK's capabilities recorded too.
    Packet connectWithCapabilities(final Packet.Connect connect) {
        send(connect);
        return next(true);
    }

    /// A clean-start CONNECT for client id `id`: keep alive 60 s, no session expiry, the protocol's defaults.
    static Packet.Connect clean(final String id) {
        return new Packet.Connect(id, true, 60, 0, Packet.DEFAULT_RECEIVE_MAXIMUM, Packet.PROTOCOL_MAXIMUM_PACKET_SIZE,
                null);
    }

    /// Whether QoS 1 messages read are acknowledged at once; on by default.
    void autoAck(final boolean on) {
        autoAck = on;
    }

    /// Whether what this connection reads goes into the transcript; on by default. A scenario turns it off where the
    /// order of packets is the broker's to choose, and notes what it checked instead.
    void recording(final boolean on) {
        recording = on;
    }

    /// A packet id not yet used on this connection.
    int packetId() {
        return nextPacketId++;
    }

    /// Writes `packet`.
    void send(final Packet packet) {
        try {
            raw(Codec.encode(packet));
        } catch (final MqttException e) {
            throw new IllegalArgumentException("cannot encode [packet=" + packet + ']', e);
        }
    }

    /// Writes `bytes`, a whole packet or several. A write to a connection the broker closed is not a failure: the
    /// transcript shows the close at the next read.
    void raw(final byte[] bytes) {
        try {
            out.write(bytes);
            out.flush();
        } catch (final IOException e) {
            if (!(e instanceof SocketException)) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /// Publishes `message` at its QoS, a QoS 1 one under a new packet id; the PUBACK is read by the scenario.
    void publish(final Packet.Publish message) {
        send(message.qos() > 0 ? message.withPacketId(packetId()) : message);
    }

    /// Subscribes to `subscriptions` and reads the SUBACK, or whatever came first.
    Packet subscribe(final Packet.Subscription... subscriptions) {
        send(new Packet.Subscribe(packetId(), List.of(subscriptions)));
        return next();
    }

    /// The next packet within [#WAIT], recorded; null, recorded as `NOTHING`, when none came.
    Packet next() {
        return next(false);
    }

    /// The next packet within `within`, recorded; null when none came, recorded as `NOTHING`.
    Packet next(final Duration within) {
        return read(within, true, false);
    }

    /// Every packet that comes until the connection has been silent for [#QUIET], each recorded.
    List<Packet> drain() {
        final List<Packet> got = new ArrayList<>();
        for (Packet p; (p = read(QUIET, false, false)) != null; ) {
            got.add(p);
        }
        return got;
    }

    /// Reads, and records, every packet that comes within `period`, however long the gaps between them: the wait a
    /// scenario needs for a broker's clock to pass a deadline, spent on the socket's read timeout.
    void quietFor(final Duration period) {
        final long startNanos = System.nanoTime();
        for (long leftNanos = period.toNanos(); leftNanos > 0;
                leftNanos = period.toNanos() - (System.nanoTime() - startNanos)) {
            if (read(Duration.ofNanos(leftNanos).plusMillis(1), false, false) == null && closed) {
                return;
            }
        }
    }

    /// Closes the connection without DISCONNECT, as a network that failed does: the broker must publish the will.
    void cut() {
        close();
    }

    /// Sends DISCONNECT with `reasonCode` and `sessionExpirySeconds` (-1 to keep the CONNECT's), then closes.
    void disconnect(final int reasonCode, final long sessionExpirySeconds) {
        send(new Packet.Disconnect(reasonCode, null, sessionExpirySeconds));
        close();
    }

    private Packet next(final boolean capabilities) {
        return read(WAIT, true, capabilities);
    }

    /// Reads one packet within `within` and records it; a QoS 1 PUBLISH acknowledged when [#autoAck] is on. Returns
    /// null when none came, recording the silence when `expected`, and when the connection ended, recording the end
    /// once: a read of a connection already closed records nothing. A packet read but not one the codec reads, or a
    /// QoS 2 one, is recorded and returned as a PINGRESP, the marker that something came.
    private Packet read(final Duration within, final boolean expected, final boolean capabilities) {
        if (closed) {
            return null;
        }
        final int header;
        try {
            socket.setSoTimeout((int) within.toMillis());
            header = in.read();
        } catch (final SocketTimeoutException _) {
            if (expected && recording) {
                run.recordOther(label, "NOTHING", "");
            }
            return null;
        } catch (final IOException _) {
            return ended();
        }
        if (header < 0) {
            return ended();
        }
        try {
            socket.setSoTimeout((int) WAIT.toMillis());
            final int type = header >>> 4;
            if (type == PUBREC || type == PUBREL || type == PUBCOMP) {
                return qos2(type);
            }
            final Packet p = Codec.read(in, header, Packet.PROTOCOL_MAXIMUM_PACKET_SIZE);
            if (recording) {
                run.record(label, p, capabilities);
            }
            if (autoAck && p instanceof final Packet.Publish m && m.qos() == 1) {
                send(new Packet.PubAck(m.packetId(), Packet.SUCCESS, null));
            }
            return p;
        } catch (final MqttException e) {
            if (recording) {
                run.recordOther(label, "UNREADABLE", "type=" + (header >>> 4) + " reason="
                        + Packet.hex(e.reasonCode()));
            }
            return new Packet.PingResp();
        } catch (final IOException _) {
            return ended();
        }
    }

    /// The rest of a PUBREC (answered with PUBREL), PUBREL or PUBCOMP: packet id, then a reason code, Success when
    /// absent (§3.5.2.1).
    private Packet qos2(final int type) throws IOException {
        final byte[] body = in.readNBytes(vbi());
        if (body.length < 2) {
            throw new EOFException("stream ended inside a QoS 2 packet [type=" + type + ']');
        }
        final int packetId = (body[0] & 0xFF) << 8 | body[1] & 0xFF;
        final int reasonCode = body.length > 2 ? body[2] & 0xFF : Packet.SUCCESS;
        if (recording) {
            run.recordQos2(label, type == PUBREC ? "PUBREC" : type == PUBREL ? "PUBREL" : "PUBCOMP", reasonCode);
        }
        if (type == PUBREC && reasonCode < Packet.FAILURE) {
            raw(ParityRaw.pubRel(packetId));
        }
        return new Packet.PingResp();
    }

    private int vbi() throws IOException {
        int value = 0;
        for (int shift = 0; shift < 28; shift += 7) {
            final int b = in.read();
            if (b < 0) {
                throw new EOFException("stream ended inside a length");
            }
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new IOException("malformed variable byte integer");
    }

    private Packet ended() {
        if (recording) {
            run.recordOther(label, "CLOSED", "");
        }
        close();
        return null;
    }
}
