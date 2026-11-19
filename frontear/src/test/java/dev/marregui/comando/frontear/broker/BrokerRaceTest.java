package dev.marregui.comando.frontear.broker;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.frontear.mqtt.Names;
import dev.marregui.comando.frontear.mqtt.Packet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// Races the broker must win every time, each hunted by repeating it: a client's presence after a drop and an
/// immediate reconnect, and the code a publish gets while its only subscriber comes and goes.
class BrokerRaceTest {

    // sessions enough for the presence test's subscribers, its client and a sweep's markers
    private static final int SESSIONS = 128;
    private static final int SUBSCRIBERS = 100;
    // the reviewers saw the stale clear win 18 of 20 rounds with a will this large going to 400 subscribers
    private static final int ROUNDS = 10;
    private static final int WILL_BYTES = 60_000;
    private static final int PUBLISHES = 50_000;
    // how long a presence must hold for the test to call it settled; a stale clear lands within microseconds
    private static final Duration SETTLED = Duration.ofMillis(50);

    /// A client that drops and reconnects at once, while its old connection's will goes to many subscribers, reads
    /// online once the broker settles: the old connection's presence clear never lands after the new one's online.
    @Test
    void presenceIsOnlineAfterADropAndAnImmediateReconnect() throws IOException {
        final String presence = Names.PRESENCE + "x";
        try (TestBroker test = TestBroker.start(SESSIONS)) {
            final List<RawClient> subscribers = new ArrayList<>();
            try {
                for (int i = 0; i < SUBSCRIBERS; i++) {
                    final RawClient s = RawClient.connected(test.port(), "s" + i);
                    subscribers.add(s);
                    assertThat(s.subscribe(new Packet.Subscription("w", 0))).containsExactly(0);
                }
                for (int round = 0; round < ROUNDS; round++) {
                    final Packet.Publish will = new Packet.Publish("w", new byte[WILL_BYTES], 0, false, false, 0,
                            null, null);
                    final RawClient a = RawClient.connected(test.port(), new Packet.Connect("x", false, 0, 300,
                            Packet.DEFAULT_RECEIVE_MAXIMUM, Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, will));
                    a.close();
                    try (RawClient b = RawClient.connected(test.port(), RawClient.connect("x", false, 300))) {
                        for (final RawClient s : subscribers) {
                            s.ping();
                        }
                        await("presence in round " + round).during(SETTLED).atMost(TestBroker.WAIT)
                                .until(() -> !test.broker.retained(presence, 1).isEmpty());
                        b.send(new Packet.Disconnect(Packet.SUCCESS, null));
                        b.untilClosed();
                    }
                    await().atMost(TestBroker.WAIT).until(() -> test.broker.retained(presence, 1).isEmpty());
                }
            } finally {
                for (final RawClient s : subscribers) {
                    s.close();
                }
            }
        }
    }

    /// A publish whose only subscriber unsubscribes and subscribes again on another thread gets 0x00 only when the
    /// subscriber was given it, and 0x10 when it reached nothing, however the two interleave.
    @Test
    void aPublishThatReachedNoSubscriberIsNotAcknowledgedAsDelivered() throws Exception {
        final AtomicInteger delivered = new AtomicInteger();
        final InternalSubscriber counting = (_, _) -> {
            delivered.incrementAndGet();
            return CompletableFuture.completedFuture(Packet.SUCCESS);
        };
        final AtomicBoolean stop = new AtomicBoolean();
        final CountDownLatch started = new CountDownLatch(1);
        try (TestBroker test = TestBroker.start()) {
            final Thread churn = Thread.ofVirtual().name("broker-race-test-churn").start(() -> {
                started.countDown();
                while (!stop.get()) {
                    test.broker.subscribe("x", "t", 1, false, counting);
                    test.broker.unsubscribeAll("x");
                }
            });
            int acknowledged = 0;
            try {
                assertThat(started.await(TestBroker.WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
                for (int i = 0; i < PUBLISHES; i++) {
                    if (code(test.broker.publish(new Packet.Publish("t", new byte[] {1}, 1, false, false, 0, null,
                            null), "test")) == Packet.SUCCESS) {
                        acknowledged++;
                    }
                }
            } finally {
                stop.set(true);
                assertThat(churn.join(TestBroker.WAIT)).isTrue();
            }
            assertThat(acknowledged).isEqualTo(delivered.get());
        }
    }

    private static int code(final CompletableFuture<Integer> publish) throws InterruptedException,
            ExecutionException, TimeoutException {
        return publish.get(TestBroker.WAIT.toMillis(), TimeUnit.MILLISECONDS);
    }
}
