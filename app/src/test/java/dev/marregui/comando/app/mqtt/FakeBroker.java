package dev.marregui.comando.app.mqtt;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import dev.marregui.comando.core.loop.ExecutorStop;
import dev.marregui.comando.core.loop.Threads;

/// An MQTT 5 broker for tests, on `127.0.0.1` and a port of the system's choosing, speaking through [Codec]: it
/// answers CONNECT, SUBSCRIBE, PUBLISH and PINGREQ as a broker would, routes publishes to the subscriptions that match,
/// keeps retained messages and sends a will when a client goes without its DISCONNECT. Every packet a client sends is
/// recorded in arrival order. The knobs (a refused connect, a server keep alive, a receive maximum, unanswered pings,
/// unacknowledged publishes) apply to the connects and packets after they are set; [#send], [#sendRaw] and [#drop]
/// act on the latest connection. One broker serves a test class: [#reset] gives each test a fresh one, whose
/// connections, records and retained messages are its own.
public final class FakeBroker implements AutoCloseable {

    private final ServerSocket server;
    private final ExecutorService accepting = Threads.strand("fake-broker-accept");
    private final List<ExecutorService> reading = new CopyOnWriteArrayList<>();
    private final List<Session> everySession = new CopyOnWriteArrayList<>();
    /// Written by the test thread at a reset, read by the accept strand and the test thread.
    private volatile Generation generation = new Generation();
    /// Written by the accept strand and a reset, read by the test thread.
    private volatile Session latest;
    // the knobs below: written by the test thread, read by the session strands
    private volatile int connectCode = Packet.SUCCESS;
    private volatile int serverKeepAliveSeconds = Packet.NO_SERVER_KEEP_ALIVE;
    private volatile int receiveMaximum = Packet.DEFAULT_RECEIVE_MAXIMUM;
    private volatile int subscribeCode = 1;
    private volatile boolean answerPings = true;
    private volatile boolean ackPublishes = true;

    /// A broker listening, accepting connections at once.
    public FakeBroker() {
        try {
            server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        accepting.execute(this::accept);
    }

    /// The port it listens on.
    public int port() {
        return server.getLocalPort();
    }

    /// The knobs back at their defaults, every connection closed, and nothing received, retained or accepted: what a
    /// connection from before records goes to its own generation, not this one.
    public void reset() {
        connectCode = Packet.SUCCESS;
        serverKeepAliveSeconds = Packet.NO_SERVER_KEEP_ALIVE;
        receiveMaximum = Packet.DEFAULT_RECEIVE_MAXIMUM;
        subscribeCode = 1;
        answerPings = true;
        ackPublishes = true;
        final Generation old = generation;
        generation = new Generation();
        latest = null;
        for (final Session s : old.open) {
            s.close();
        }
    }

    /// The connections accepted so far.
    public int accepted() {
        return generation.accepted.get();
    }

    /// Whether the latest connection is open and its client has been answered a successful CONNACK.
    public boolean connected() {
        final Session s = latest;
        return s != null && s.connected && !s.socket.isClosed();
    }

    /// The packets of `type` clients sent, in arrival order.
    public <T extends Packet> List<T> received(final Class<T> type) {
        return generation.received.stream().filter(type::isInstance).map(type::cast).toList();
    }

    /// Refuses later connects with `reasonCode`; [Packet#SUCCESS] accepts them again.
    public void connectCode(final int reasonCode) {
        connectCode = reasonCode;
    }

    /// Sends a Server Keep Alive of `seconds` in later CONNACKs.
    public void serverKeepAliveSeconds(final int seconds) {
        serverKeepAliveSeconds = seconds;
    }

    /// Sends a Receive Maximum of `max` in later CONNACKs.
    public void receiveMaximum(final int max) {
        receiveMaximum = max;
    }

    /// Answers later subscriptions with `reasonCode`, 1 (QoS 1 granted) by default.
    public void subscribeCode(final int reasonCode) {
        subscribeCode = reasonCode;
    }

    /// Whether to answer PINGREQs.
    public void answerPings(final boolean answer) {
        answerPings = answer;
    }

    /// Whether to acknowledge QoS 1 publishes; when not, a test sends the PUBACK with [#send].
    public void ackPublishes(final boolean ack) {
        ackPublishes = ack;
    }

    /// Sends `packet` on the latest connection; a QoS 1 publish with packet id 0 gets the connection's next id.
    public void send(final Packet packet) {
        latestOrFail().send(packet);
    }

    /// Writes `bytes` as they are on the latest connection.
    public void sendRaw(final byte[] bytes) {
        latestOrFail().write(bytes);
    }

    /// Closes the latest connection without a DISCONNECT, as a broker that went away.
    public void drop() {
        final Session s = latest;
        if (s != null) {
            s.close();
        }
    }

    /// Stops listening and closes every connection.
    @Override
    public void close() {
        try {
            server.close();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            for (final Session s : everySession) {
                s.close();
            }
            ExecutorStop.now("fake-broker", accepting);
            for (final ExecutorService e : reading) {
                ExecutorStop.now("fake-broker", e);
            }
        }
    }

    private Session latestOrFail() {
        final Session s = latest;
        if (s == null) {
            throw new IllegalStateException("no connection");
        }
        return s;
    }

    private void accept() {
        while (!server.isClosed()) {
            final Socket socket;
            try {
                socket = server.accept();
            } catch (final IOException _) {
                return;
            }
            final Generation g = generation;
            g.accepted.incrementAndGet();
            final Session s = new Session(socket, g);
            everySession.add(s);
            g.open.add(s);
            latest = s;
            final ExecutorService strand = Threads.strand("fake-broker-session");
            reading.add(strand);
            strand.execute(s::read);
        }
    }

    /// What one test sees of the broker: its connections, the packets they sent, and the messages retained.
    private static final class Generation {

        private final List<Session> open = new CopyOnWriteArrayList<>();
        private final List<Packet> received = new CopyOnWriteArrayList<>();
        private final Map<String, Packet.Publish> retained = new ConcurrentHashMap<>();
        private final AtomicInteger accepted = new AtomicInteger();

        /// The publish handed to every open session whose subscriptions match its topic, at the lower QoS of the two.
        void route(final Packet.Publish p) {
            for (final Session s : open) {
                for (final Map.Entry<String, Integer> sub : s.subscriptions.entrySet()) {
                    if (Topics.matches(sub.getKey(), p.topic())) {
                        s.send(new Packet.Publish(p.topic(), p.payload(), Math.min(p.qos(), sub.getValue()), false,
                                false, 0, p.responseTopic(), p.correlation()));
                        break;
                    }
                }
            }
        }

        void keep(final Packet.Publish p) {
            if (p.payload().length == 0) {
                retained.remove(p.topic());
            } else {
                retained.put(p.topic(), p);
            }
        }
    }

    /// One client's connection: read on its own strand, written by whoever sends.
    private final class Session {

        private final Socket socket;
        private final Generation generation;
        private final Map<String, Integer> subscriptions = new ConcurrentHashMap<>();
        private final AtomicInteger nextId = new AtomicInteger(1);
        /// Written by the session's strand, read by the test thread.
        private volatile boolean connected;
        /// The session's strand only.
        private Packet.Publish will;
        /// The session's strand only.
        private boolean graceful;

        Session(final Socket socket, final Generation generation) {
            this.socket = socket;
            this.generation = generation;
        }

        void read() {
            try (InputStream in = new BufferedInputStream(socket.getInputStream())) {
                while (true) {
                    final int first = in.read();
                    if (first < 0) {
                        return;
                    }
                    final Packet packet = Codec.read(in, first, Packet.PROTOCOL_MAXIMUM_PACKET_SIZE);
                    generation.received.add(packet);
                    if (!handle(packet)) {
                        return;
                    }
                }
            } catch (final IOException _) {
                // the client went, or sent what this broker does not read: the connection ends either way
            } finally {
                close();
                generation.open.remove(this);
                if (!graceful && will != null) {
                    if (will.retain()) {
                        generation.keep(will);
                    }
                    generation.route(will);
                }
            }
        }

        /// Answers `packet`; false when the connection is to end.
        private boolean handle(final Packet packet) {
            switch (packet) {
                case Packet.Connect c -> {
                    final int code = connectCode;
                    send(new Packet.ConnAck(false, code, receiveMaximum, Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, 0,
                            serverKeepAliveSeconds, null, null));
                    if (code >= Packet.FAILURE) {
                        return false;
                    }
                    will = c.will();
                    connected = true;
                }
                case Packet.Subscribe s -> {
                    final int code = subscribeCode;
                    send(new Packet.SubAck(s.packetId(), s.subscriptions().stream().map(_ -> code).toList(), null));
                    if (code < Packet.FAILURE) {
                        for (final Packet.Subscription sub : s.subscriptions()) {
                            subscriptions.put(sub.filter(), Math.min(sub.qos(), code));
                            for (final Packet.Publish r : generation.retained.values()) {
                                if (Topics.matches(sub.filter(), r.topic())) {
                                    send(new Packet.Publish(r.topic(), r.payload(), Math.min(r.qos(), code), true,
                                            false, 0, r.responseTopic(), r.correlation()));
                                }
                            }
                        }
                    }
                }
                case Packet.Publish p -> {
                    if (p.retain()) {
                        generation.keep(p);
                    }
                    if (p.qos() == 1 && ackPublishes) {
                        send(new Packet.PubAck(p.packetId(), Packet.SUCCESS, null));
                    }
                    generation.route(p);
                }
                case Packet.PingReq _ -> {
                    if (answerPings) {
                        send(new Packet.PingResp());
                    }
                }
                case Packet.Disconnect _ -> {
                    graceful = true;
                    return false;
                }
                case Packet.PubAck _, Packet.ConnAck _, Packet.SubAck _, Packet.PingResp _ -> {
                    // recorded; nothing to answer
                }
            }
            return true;
        }

        void send(final Packet packet) {
            final Packet numbered = packet instanceof final Packet.Publish p && p.qos() > 0 && p.packetId() == 0
                    ? p.withPacketId(nextId.getAndIncrement()) : packet;
            try {
                write(Codec.encode(numbered));
            } catch (final MqttException e) {
                throw new UncheckedIOException(e);
            }
        }

        synchronized void write(final byte[] bytes) {
            try {
                final OutputStream out = socket.getOutputStream();
                out.write(bytes);
            } catch (final IOException _) {
                close();
            }
        }

        void close() {
            try {
                socket.close();
            } catch (final IOException _) {
                // closing is all that is left to do
            }
        }
    }
}
