package dev.marregui.comando.frontear.broker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.marregui.comando.frontear.mqtt.Packet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// PUBLISH, SUBSCRIBE and UNSUBSCRIBE over TCP: routing with `+`, `#` and the `$` rule, QoS 0 and 1, the features
/// the broker declines with their reason codes, the subscription options, retained messages, message expiry, the
/// properties passed through, both sides' Receive Maximum and Maximum Packet Size, redelivery, and PUBACK after the
/// internal subscribers with the worst of their codes.
class BrokerPublishTest {

    private TestBroker test;

    @BeforeEach
    void start() throws IOException {
        test = TestBroker.start();
    }

    @AfterEach
    void stop() {
        test.close();
    }

    /// `+` matches one level and `#` any number, the parent's included; a QoS 0 and a QoS 1 message both arrive, each
    /// at the lower of its QoS and the subscription's.
    @Test
    void singleAndMultiLevelWildcardsMatch() throws IOException {
        try (RawClient s = RawClient.connected(test.port(), "s");
                RawClient p = RawClient.connected(test.port(), "p")) {
            assertThat(s.subscribe(new Packet.Subscription("s/+/x", 1), new Packet.Subscription("m/#", 0)))
                    .containsExactly(1, 0);
            for (final String topic : List.of("s/1/x", "s/1/y", "m", "m/a/b", "s/1/x/z")) {
                p.publish(RawClient.qos1(topic, topic));
            }
            p.send(new Packet.Publish("s/2/x", new byte[0], 0, false, false, 0, null, null));
            // the broker routes a publish before it reads the next packet, so p's PINGRESP fences the QoS 0 one
            p.ping();
            final List<Packet> got = s.ping();
            assertThat(got).extracting(m -> ((Packet.Publish) m).topic()).containsExactly("s/1/x", "m", "m/a/b",
                    "s/2/x");
            assertThat(got).extracting(m -> ((Packet.Publish) m).qos()).containsExactly(1, 0, 0, 0);
        }
    }

    /// A topic that starts with `$` is not matched by a filter that starts with a wildcard (§4.7.2), only by one
    /// that names its first level.
    @Test
    void aDollarTopicIsNotMatchedByALeadingWildcard() throws IOException {
        try (RawClient s = RawClient.connected(test.port(), "s")) {
            s.subscribe(new Packet.Subscription("#", 0), new Packet.Subscription("+/x", 0),
                    new Packet.Subscription("$sys/#", 0));
            assertThat(test.publish(new Packet.Publish("$sys/x", new byte[0], 0, false, false, 0, null, null),
                    "test")).isEqualTo(Packet.SUCCESS);
            assertThat(s.ping()).extracting(m -> ((Packet.Publish) m).topic()).containsExactly("$sys/x");
        }
    }

    /// A QoS 2 PUBLISH ends the connection with DISCONNECT 0x9B, since CONNACK said Maximum QoS 1.
    @Test
    void aQos2PublishEndsTheConnectionWith0x9B() throws IOException {
        try (RawClient c = RawClient.connected(test.port(), "a")) {
            c.send(new Packet.Publish("t", new byte[0], 2, false, false, 1, null, null));
            assertThat(c.untilClosed()).containsExactly(new Packet.Disconnect(Packet.QOS_NOT_SUPPORTED, null));
        }
    }

    /// A QoS 2 subscription is granted QoS 1.
    @Test
    void aQos2SubscriptionIsGrantedQos1() throws IOException {
        try (RawClient c = RawClient.connected(test.port(), "a")) {
            assertThat(c.subscribe(new Packet.Subscription("t", 2))).containsExactly(1);
        }
    }

    /// A PUBLISH with a Topic Alias ends the connection with DISCONNECT 0x94: CONNACK granted no alias.
    @Test
    void aTopicAliasEndsTheConnectionWith0x94() throws IOException {
        try (RawClient c = RawClient.connected(test.port(), "a")) {
            // PUBLISH QoS 0 to "a" with property 0x23 Topic Alias 1
            c.sendHex("30 07 00 01 61 03 23 00 01");
            assertThat(c.untilClosed()).containsExactly(new Packet.Disconnect(Packet.TOPIC_ALIAS_INVALID, null));
        }
    }

    /// A SUBSCRIBE with a Subscription Identifier ends the connection with DISCONNECT 0xA1, as CONNACK said
    /// Subscription Identifiers Available 0.
    @Test
    void aSubscriptionIdentifierEndsTheConnectionWith0xA1() throws IOException {
        try (RawClient c = RawClient.connected(test.port(), "a")) {
            // SUBSCRIBE packet 3, property 0x0B Subscription Identifier 1, filter "a/+" QoS 1
            c.sendHex("82 0B 00 03 02 0B 01 00 03 61 2F 2B 01");
            assertThat(c.untilClosed()).containsExactly(
                    new Packet.Disconnect(Packet.SUBSCRIPTION_IDS_NOT_SUPPORTED, null));
        }
    }

    /// A `$share/` filter is refused with SUBACK 0x9E, and the other filters of the same SUBSCRIBE are granted.
    @Test
    void aSharedSubscriptionIsRefusedWith0x9E() throws IOException {
        try (RawClient c = RawClient.connected(test.port(), "a")) {
            assertThat(c.subscribe(new Packet.Subscription("$share/g/t", 1), new Packet.Subscription("t", 1)))
                    .containsExactly(Packet.SHARED_SUBSCRIPTIONS_NOT_SUPPORTED, 1);
        }
    }

    /// No Local keeps a client's own messages from it, and only from it.
    @Test
    void noLocalKeepsAClientsOwnMessagesFromIt() throws IOException {
        try (RawClient a = RawClient.connected(test.port(), "a");
                RawClient b = RawClient.connected(test.port(), "b")) {
            a.subscribe(new Packet.Subscription("t", 1, true, false, 0));
            b.subscribe(new Packet.Subscription("t", 1, true, false, 0));
            assertThat(a.publish(RawClient.qos1("t", "from a"))).isEqualTo(Packet.SUCCESS);
            assertThat(a.ping()).isEmpty();
            assertThat(b.receive().topic()).isEqualTo("t");
        }
    }

    /// Retain As Published keeps a live message's retain flag; without it the flag is cleared (§3.3.1.3).
    @Test
    void retainAsPublishedKeepsTheRetainFlag() throws IOException {
        try (RawClient plain = RawClient.connected(test.port(), "plain");
                RawClient rap = RawClient.connected(test.port(), "rap");
                RawClient p = RawClient.connected(test.port(), "p")) {
            plain.subscribe(new Packet.Subscription("t", 1, false, false, 2));
            rap.subscribe(new Packet.Subscription("t", 1, false, true, 2));
            p.publish(new Packet.Publish("t", new byte[] {1}, 1, true, false, 1, null, null));
            assertThat(plain.receive().retain()).isFalse();
            assertThat(rap.receive().retain()).isTrue();
        }
    }

    /// Retain Handling decides which subscribes get the retained messages, sent after the SUBACK with their retain
    /// flag set: 0 at every subscribe, 1 only when the subscription is new, 2 never.
    @Test
    void retainHandlingDecidesWhichSubscribesGetRetainedMessages() throws IOException {
        test.publish(new Packet.Publish("r/1", new byte[] {1}, 1, true, false, 0, null, null), "test");
        try (RawClient c = RawClient.connected(test.port(), "a")) {
            for (int i = 0; i < 2; i++) {
                c.subscribe(new Packet.Subscription("r/#", 1, false, false, 0));
                final Packet.Publish p = c.receive();
                assertThat(p.topic()).isEqualTo("r/1");
                assertThat(p.retain()).isTrue();
            }
            c.subscribe(new Packet.Subscription("r/+", 1, false, false, 1));
            assertThat(c.receive().topic()).isEqualTo("r/1");
            c.subscribe(new Packet.Subscription("r/+", 1, false, false, 1));
            assertThat(c.ping()).isEmpty();
            c.subscribe(new Packet.Subscription("r/1", 1, false, false, 2));
            assertThat(c.ping()).isEmpty();
        }
    }

    /// A retained message with an empty payload clears its topic: a later subscriber gets nothing retained.
    @Test
    void anEmptyRetainedMessageClearsTheTopic() throws IOException {
        try (RawClient p = RawClient.connected(test.port(), "p")) {
            // the client's own presence is retained too, published on the broker's presence strand after CONNACK
            await().atMost(TestBroker.WAIT).until(() -> test.broker.stats().retained() == 1);
            final int before = 1;
            p.publish(new Packet.Publish("r", new byte[] {1}, 1, true, false, 1, null, null));
            assertThat(test.broker.stats().retained()).isEqualTo(before + 1);
            p.publish(new Packet.Publish("r", new byte[0], 1, true, false, 1, null, null));
            assertThat(test.broker.stats().retained()).isEqualTo(before);
        }
        try (RawClient s = RawClient.connected(test.port(), "s")) {
            s.subscribe(new Packet.Subscription("r", 1));
            assertThat(s.ping()).isEmpty();
        }
    }

    /// A message's expiry is lowered by the time it waited before it is sent, and a message past its expiry is
    /// dropped and counted, queued or retained (§3.3.2.3.3).
    @Test
    void messageExpiryIsLoweredByTheTimeQueuedAndAnExpiredMessageIsDropped() throws IOException {
        try (RawClient a = RawClient.connected(test.port(), RawClient.connect("a", true, 60))) {
            a.subscribe(new Packet.Subscription("t", 1));
            a.send(new Packet.Disconnect(Packet.SUCCESS, null));
            a.untilClosed();
        }
        try (RawClient p = RawClient.connected(test.port(), "p")) {
            p.publish(expiring("t", "keep", 10, false));
            p.publish(expiring("t", "drop", 3, false));
            p.publish(expiring("r", "retained", 3, true));
        }
        test.clock.advance(Duration.ofSeconds(4));
        try (RawClient a = RawClient.open(test.port(), RawClient.connect("a", false, 60))) {
            a.read(Packet.ConnAck.class);
            final Packet.Publish kept = a.receive();
            assertThat(kept.payload()).isEqualTo("keep".getBytes(StandardCharsets.UTF_8));
            assertThat(kept.meta().messageExpirySeconds()).isEqualTo(6);
            assertThat(a.ping()).isEmpty();
            assertThat(a.subscribe(new Packet.Subscription("r", 1))).containsExactly(1);
            assertThat(a.ping()).isEmpty();
        }
        assertThat(test.broker.stats().droppedExpired()).isEqualTo(2);
    }

    /// Response topic, correlation data, user properties, content type and payload format pass unchanged.
    @Test
    void propertiesPassUnchanged() throws IOException {
        final Packet.Meta meta = new Packet.Meta(1, -1, "text/plain",
                List.of(new Packet.UserProperty("k", "v"), new Packet.UserProperty("k", "w")));
        try (RawClient s = RawClient.connected(test.port(), "s");
                RawClient p = RawClient.connected(test.port(), "p")) {
            s.subscribe(new Packet.Subscription("t", 1));
            p.publish(new Packet.Publish("t", new byte[] {7}, 1, false, false, 1, "reply/p", new byte[] {1, 2},
                    meta));
            final Packet.Publish got = s.receive();
            assertThat(got.responseTopic()).isEqualTo("reply/p");
            assertThat(got.correlation()).containsExactly(1, 2);
            assertThat(got.meta()).isEqualTo(meta);
            assertThat(got.payload()).containsExactly(7);
        }
    }

    /// The client's Receive Maximum bounds the QoS 1 messages in flight to it; each PUBACK lets the next one go.
    @Test
    void theClientsReceiveMaximumBoundsWhatIsInFlight() throws IOException {
        try (RawClient s = RawClient.connected(test.port(),
                new Packet.Connect("s", true, 0, 0, 2, Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, null));
                RawClient p = RawClient.connected(test.port(), "p")) {
            s.subscribe(new Packet.Subscription("t", 1));
            for (int i = 0; i < 3; i++) {
                p.publish(RawClient.qos1("t", Integer.toString(i)));
            }
            final List<Packet> inFlight = s.ping();
            assertThat(inFlight).hasSize(2);
            final Packet.Publish first = (Packet.Publish) inFlight.getFirst();
            s.send(new Packet.PubAck(first.packetId(), Packet.SUCCESS, null));
            assertThat(s.read(Packet.Publish.class).payload()).isEqualTo("2".getBytes(StandardCharsets.UTF_8));
        }
    }

    /// A client with more QoS 1 publishes unacknowledged than the broker's Receive Maximum is sent DISCONNECT 0x93.
    @Test
    void aPublishPastTheBrokersReceiveMaximumEndsTheConnection() throws IOException {
        test.broker.subscribe("held", "slow", 1, false, (m, o) -> new CompletableFuture<>());
        try (RawClient c = RawClient.connected(test.port(), "a")) {
            for (int i = 1; i <= TestBroker.RECEIVE_MAXIMUM + 1; i++) {
                c.send(RawClient.qos1("slow", "m").withPacketId(i));
            }
            assertThat(c.untilClosed()).containsExactly(new Packet.Disconnect(Packet.RECEIVE_MAXIMUM_EXCEEDED, null));
        }
    }

    /// A message larger than the client's Maximum Packet Size is not sent to it, and is counted (§3.1.2.25).
    @Test
    void aMessageLargerThanTheClientsMaximumPacketSizeIsDroppedAndCounted() throws IOException {
        try (RawClient s = RawClient.connected(test.port(), new Packet.Connect("s", true, 0, 0, 10, 64, null));
                RawClient p = RawClient.connected(test.port(), "p")) {
            s.subscribe(new Packet.Subscription("t", 1));
            p.publish(new Packet.Publish("t", new byte[100], 1, false, false, 1, null, null));
            p.publish(new Packet.Publish("t", new byte[10], 1, false, false, 1, null, null));
            assertThat(s.receive().payload()).hasSize(10);
            assertThat(test.broker.stats().droppedOversize()).isEqualTo(1);
        }
    }

    /// A packet larger than the broker's Maximum Packet Size ends the connection with DISCONNECT 0x95, refused on its
    /// header before its body is read.
    @Test
    void aPacketLargerThanTheBrokersMaximumIsRefusedWith0x95() throws IOException {
        try (RawClient c = RawClient.connected(test.port(), "a")) {
            // a PUBLISH header announcing 70,000 bytes: 0xF0 0xA2 0x04 as a variable byte integer
            c.sendHex("30 F0 A2 04");
            assertThat(c.untilClosed()).containsExactly(new Packet.Disconnect(Packet.PACKET_TOO_LARGE, null));
        }
    }

    /// A QoS 1 message sent and not acknowledged is sent again, as a duplicate with its packet id, when the client
    /// returns to its session (§4.4).
    @Test
    void anUnacknowledgedMessageIsSentAgainOnReturn() throws IOException {
        final Packet.Publish first;
        try (RawClient a = RawClient.connected(test.port(), RawClient.connect("a", true, 60));
                RawClient p = RawClient.connected(test.port(), "p")) {
            a.subscribe(new Packet.Subscription("t", 1));
            p.publish(RawClient.qos1("t", "m"));
            first = a.read(Packet.Publish.class);
            assertThat(first.dup()).isFalse();
        }
        try (RawClient a = RawClient.open(test.port(), RawClient.connect("a", false, 60))) {
            assertThat(a.read(Packet.ConnAck.class).sessionPresent()).isTrue();
            final Packet.Publish again = a.receive();
            assertThat(again.dup()).isTrue();
            assertThat(again.packetId()).isEqualTo(first.packetId());
            assertThat(a.ping()).isEmpty();
        }
    }

    /// UNSUBSCRIBE acknowledges each filter, 0x00 for one the client had and 0x11 for one it had not; a publish to a
    /// topic nobody subscribes to is acknowledged 0x10.
    @Test
    void unsubscribeAcknowledgesEachFilter() throws IOException {
        try (RawClient c = RawClient.connected(test.port(), "a")) {
            c.subscribe(new Packet.Subscription("t", 1));
            c.send(new Packet.Unsubscribe(2, List.of("t", "u")));
            assertThat(c.read(Packet.UnsubAck.class)).isEqualTo(new Packet.UnsubAck(2,
                    List.of(Packet.SUCCESS, Packet.NO_SUBSCRIPTION_EXISTED), null));
            assertThat(c.publish(RawClient.qos1("t", "m"))).isEqualTo(Packet.NO_MATCHING_SUBSCRIBERS);
        }
    }

    /// A client's PUBACK waits until every internal subscriber the message reached completed, carries the worst of
    /// their codes (0x97 surfaces as 0x97), and PUBACKs leave in the order the publishes came.
    @Test
    void pubAckWaitsForTheInternalSubscribersAndCarriesTheWorstCode() throws IOException {
        final List<CompletableFuture<Integer>> held = new CopyOnWriteArrayList<>();
        test.broker.subscribe("held", "t", 1, false, (m, o) -> hold(held));
        final TestBroker.Recording quick = new TestBroker.Recording();
        test.broker.subscribe("quick", "t", 1, false, quick);
        try (RawClient c = RawClient.connected(test.port(), "a")) {
            c.send(RawClient.qos1("t", "m").withPacketId(1));
            c.send(RawClient.qos1("nobody", "m").withPacketId(2));
            assertThat(c.ping()).isEmpty();
            assertThat(quick.deliveries).hasSize(1);
            held.getFirst().complete(Packet.QUOTA_EXCEEDED);
            assertThat(c.read(Packet.PubAck.class)).isEqualTo(new Packet.PubAck(1, Packet.QUOTA_EXCEEDED, null));
            assertThat(c.read(Packet.PubAck.class)).isEqualTo(new Packet.PubAck(2, Packet.NO_MATCHING_SUBSCRIBERS,
                    null));
        }
    }

    /// An internal subscriber that throws or fails its stage counts as 0x80 in the PUBACK, and is counted.
    @Test
    void aFailingInternalSubscriberCountsAsUnspecified() throws IOException {
        test.broker.subscribe("throws", "t", 1, false, (m, o) -> {
            throw new IllegalStateException("subscriber failure for the test");
        });
        test.broker.subscribe("fails", "u", 1, false,
                (m, o) -> CompletableFuture.failedFuture(new IllegalStateException("failed stage for the test")));
        try (RawClient c = RawClient.connected(test.port(), "a")) {
            assertThat(c.publish(RawClient.qos1("t", "m"))).isEqualTo(Packet.UNSPECIFIED);
            assertThat(c.publish(RawClient.qos1("u", "m"))).isEqualTo(Packet.UNSPECIFIED);
        }
        assertThat(test.broker.stats().subscriberFailures()).isEqualTo(2);
    }

    /// A stage the test completes by hand, kept in `held`.
    private static CompletionStage<Integer> hold(final List<CompletableFuture<Integer>> held) {
        final CompletableFuture<Integer> f = new CompletableFuture<>();
        held.add(f);
        return f;
    }

    /// A QoS 1 message, packet id 1, with a Message Expiry Interval of `expirySeconds`.
    private static Packet.Publish expiring(final String topic, final String payload, final long expirySeconds,
            final boolean retain) {
        return new Packet.Publish(topic, payload.getBytes(StandardCharsets.UTF_8), 1, retain, false, 1, null, null,
                Packet.Meta.NONE.withMessageExpiry(expirySeconds));
    }
}
