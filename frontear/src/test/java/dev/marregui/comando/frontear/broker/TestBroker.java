package dev.marregui.comando.frontear.broker;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import dev.marregui.comando.frontear.mqtt.Packet;

import static org.awaitility.Awaitility.await;

/// A broker on a free loopback port for one test, timed by a clock the test moves by hand: message expiry, will
/// delay and session expiry fall due when the test says, never by waiting. Its limits are small, so a test reaches
/// them in a few packets.
final class TestBroker implements AutoCloseable {

    /// How long a test waits for something the broker does on its own threads.
    static final Duration WAIT = Duration.ofSeconds(10);
    /// The broker's Receive Maximum.
    static final int RECEIVE_MAXIMUM = 8;
    /// The broker's Maximum Packet Size.
    static final int MAXIMUM_PACKET_SIZE = 64 * 1024;
    /// The QoS 1 messages a session queues beyond its client's Receive Maximum.
    static final int MAX_QUEUED = 4;

    final Clock clock = new Clock();
    final Broker broker;

    private TestBroker(final int maxSessions) throws IOException {
        broker = Broker.start(new BrokerOptions(loopback(), RECEIVE_MAXIMUM, MAXIMUM_PACKET_SIZE, maxSessions, clock,
                MAX_QUEUED));
    }

    private TestBroker(final Bounds b) throws IOException {
        broker = Broker.start(new BrokerOptions(loopback(), RECEIVE_MAXIMUM, MAXIMUM_PACKET_SIZE, 16, clock,
                b.maxQueued(), b.maxQueuedBytes(), b.maxBacklogBytes(), b.maxRetained(), b.maxRetainedBytes(),
                b.maxTopicLevels(), b.maxFiltersPerSubscribe(), b.maxSubscriptions()));
    }

    @Override
    public void close() {
        broker.close();
    }

    /// A broker that keeps up to 16 sessions.
    static TestBroker start() throws IOException {
        return new TestBroker(16);
    }

    /// A broker that keeps up to `maxSessions` sessions.
    static TestBroker start(final int maxSessions) throws IOException {
        return new TestBroker(maxSessions);
    }

    /// A broker that keeps up to 16 sessions, with the bounds `b` sets.
    static TestBroker start(final Bounds b) throws IOException {
        return new TestBroker(b);
    }

    int port() {
        return broker.port();
    }

    /// Publishes `message` through the broker as `origin` and returns its code, waiting at most [#WAIT] for it.
    int publish(final Packet.Publish message, final String origin) {
        return broker.publish(message, origin).orTimeout(WAIT.toMillis(), TimeUnit.MILLISECONDS).join();
    }

    /// Returns once every sweep that could see the clock as it is now has finished, its publishes included: a sweep
    /// ends a marker session past its expiry, and a second marker, made after the first is gone, can only end in a
    /// sweep that started after that one finished.
    void sweepsDone() throws IOException {
        for (int i = 0; i < 2; i++) {
            final int before = broker.stats().sessions();
            try (RawClient m = RawClient.connected(port(), RawClient.connect("sweep-marker-" + i, true, 1))) {
                m.send(new Packet.Disconnect(Packet.SUCCESS, null));
                m.untilClosed();
            }
            clock.advance(Duration.ofSeconds(2));
            await().atMost(WAIT).until(() -> broker.stats().sessions() == before);
        }
    }

    private static InetSocketAddress loopback() {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
    }

    /// The bounds of [BrokerOptions] a test sets small, so it reaches the one it tests in a few packets.
    record Bounds(int maxQueued, long maxQueuedBytes, long maxBacklogBytes, int maxRetained, long maxRetainedBytes,
            int maxTopicLevels, int maxFiltersPerSubscribe, int maxSubscriptions) {

        // far above the filters any test subscribes, unless it sets the caps it tests
        private static final int LOOSE_FILTERS = 10_000;

        /// These bounds, with subscription caps no test reaches.
        Bounds(final int maxQueued, final long maxQueuedBytes, final long maxBacklogBytes, final int maxRetained,
                final long maxRetainedBytes, final int maxTopicLevels) {
            this(maxQueued, maxQueuedBytes, maxBacklogBytes, maxRetained, maxRetainedBytes, maxTopicLevels,
                    LOOSE_FILTERS, LOOSE_FILTERS);
        }
    }

    /// A clock that moves only when the test moves it.
    static final class Clock implements InstantSource {

        private final AtomicLong millis = new AtomicLong(1_000_000_000L);

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }

        @Override
        public long millis() {
            return millis.get();
        }

        /// Moves the clock forward by `d`.
        void advance(final Duration d) {
            millis.addAndGet(d.toMillis());
        }
    }

    /// An internal subscriber that keeps each message it is given with its origin, and completes the delivery with
    /// `code`.
    static final class Recording implements InternalSubscriber {

        final List<Delivery> deliveries = new CopyOnWriteArrayList<>();
        /// Written by the test thread, read by the thread that routes a message.
        volatile int code = Packet.SUCCESS;

        @Override
        public CompletionStage<Integer> deliver(final Packet.Publish message, final String origin) {
            deliveries.add(new Delivery(message, origin));
            return CompletableFuture.completedFuture(code);
        }

        /// The topics delivered, in order.
        List<String> topics() {
            return deliveries.stream().map(d -> d.message().topic()).toList();
        }
    }

    /// One message an internal subscriber was given.
    record Delivery(Packet.Publish message, String origin) {
    }
}
