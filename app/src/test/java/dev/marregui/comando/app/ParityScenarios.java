package dev.marregui.comando.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import dev.marregui.comando.frontear.mqtt.Packet;

import static dev.marregui.comando.app.ParityProbe.clean;

/// The scripted client actions of the differential parity test, each run as written against both brokers: connect
/// variants, subscribe with each option, publish at QoS 0 and 1, retained messages set and cleared, wills with and
/// without delay, message expiry, request and response with user properties, Receive Maximum, a session takeover, and
/// the features frontear declines (QoS 2, Topic Aliases, Subscription Identifiers, Shared Subscriptions, enhanced
/// authentication, a user name). Section numbers are the MQTT 5.0 specification's.
final class ParityScenarios {

    /// A message expiry the scenario waits out: the interval is in seconds, so 1 is the shortest.
    private static final long EXPIRES_SECONDS = 1;
    /// Longer than [#EXPIRES_SECONDS] by more than a second, as a broker may count whole seconds.
    private static final Duration EXPIRY_WAIT = Duration.ofMillis(2_500);
    private static final int LARGEST = Packet.PROTOCOL_MAXIMUM_PACKET_SIZE;
    private static final System.Logger LOG = System.getLogger(ParityScenarios.class.getName());
    private static final int RECEIVE_ALL = Packet.DEFAULT_RECEIVE_MAXIMUM;
    /// The Receive Maximum the scenario that checks it asks for: the smallest that still lets two messages fly.
    private static final int RECEIVE_TWO = 2;
    /// A session that outlives every scenario's reconnects, and ends with it.
    private static final long SESSION_SECONDS = 300;
    /// A will delay the scenario can measure: whole seconds, as the property is, and short enough to wait out.
    private static final long WILL_DELAY_SECONDS = 2;
    /// What a scenario allows beyond the will delay before it takes the will as never published: the delay was met
    /// within 0.3 s by both brokers on the machine this was written on (2026-11-19).
    private static final long WILL_MARGIN_SECONDS = 8;

    /// Every scenario, in the order the test runs them; declared after the constants its scenarios read.
    static final List<ParityScenario> ALL = List.of(
            new ParityScenario("connect-capabilities", "the CONNACK of a plain clean-start CONNECT", r -> {
                final ParityProbe c = r.open("c");
                c.connectWithCapabilities(clean(r.clientId("c")));
                c.disconnect(Packet.SUCCESS, -1);
            }),
            new ParityScenario("connect-empty-client-id", "an empty client id gets one assigned (MQTT 3.1.3.1)", r -> {
                final ParityProbe c = r.open("c");
                c.connect(clean(""));
                c.send(new Packet.PingReq());
                c.next();
            }),
            new ParityScenario("connect-session-resumed", "a session kept across a disconnect gets the QoS 1 message "
                    + "published meanwhile (MQTT 3.1.2.4, MQTT 4.1)", r -> {
                final String topic = r.topic("a");
                final ParityProbe s = r.open("sub");
                s.connect(session(r.clientId("sub"), true, SESSION_SECONDS));
                s.subscribe(new Packet.Subscription(topic, 1));
                s.disconnect(Packet.SUCCESS, -1);
                final ParityProbe p = connected(r, "pub");
                p.publish(message(topic, "offline", 1, false));
                p.next();
                final ParityProbe again = r.open("sub-again");
                again.connect(session(r.clientId("sub"), false, SESSION_SECONDS));
                again.next();
                again.drain();
                again.disconnect(Packet.SUCCESS, 0);
            }),
            new ParityScenario("connect-clean-start-ends-session", "Clean Start discards the session and its "
                    + "subscriptions (MQTT 3.1.2.4)", r -> {
                final String topic = r.topic("a");
                final ParityProbe s = r.open("sub");
                s.connect(session(r.clientId("sub"), true, SESSION_SECONDS));
                s.subscribe(new Packet.Subscription(topic, 1));
                s.disconnect(Packet.SUCCESS, -1);
                final ParityProbe again = r.open("sub-again");
                again.connect(session(r.clientId("sub"), true, 0));
                final ParityProbe p = connected(r, "pub");
                p.publish(message(topic, "nobody", 1, false));
                p.next();
                again.drain();
            }),
            new ParityScenario("connect-maximum-packet-size", "a message larger than the client's Maximum Packet "
                    + "Size is not sent to it (MQTT 3.1.2.11.4)", r -> {
                final String topic = r.topic("a");
                final ParityProbe s = r.open("sub");
                s.connect(new Packet.Connect(r.clientId("sub"), true, 60, 0, RECEIVE_ALL, 256, null));
                s.subscribe(new Packet.Subscription(topic, 1));
                final ParityProbe p = connected(r, "pub");
                p.publish(new Packet.Publish(topic, new byte[1_000], 1, false, false, 0, null, null));
                p.next();
                p.publish(message(topic, "small", 1, false));
                p.next();
                s.drain();
            }),
            new ParityScenario("connect-user-name", "a CONNECT with a User Name (MQTT 3.1.3.5)", r -> {
                final ParityProbe c = r.open("c");
                c.raw(ParityRaw.connectWithUserName(r.clientId("c"), "parity"));
                c.next();
                c.drain();
            }),
            new ParityScenario("connect-authentication-method", "a CONNECT asking for enhanced authentication "
                    + "(MQTT 4.12)", r -> {
                final ParityProbe c = r.open("c");
                c.raw(ParityRaw.connectWithAuthenticationMethod(r.clientId("c"), "SCRAM-SHA-256"));
                c.next();
                c.drain();
            }),
            new ParityScenario("connect-will-qos-2", "a will at QoS 2 (MQTT 3.1.2.6)", r -> {
                final ParityProbe c = r.open("c");
                c.connect(new Packet.Connect(r.clientId("c"), true, 60, 0, RECEIVE_ALL, LARGEST,
                        message(r.topic("will"), "gone", 2, false)));
                c.drain();
            }),
            new ParityScenario("subscribe-granted-qos", "a subscription at QoS 0, 1 and 2 (MQTT 3.9.3)", r -> {
                final ParityProbe s = connected(r, "sub");
                s.subscribe(new Packet.Subscription(r.topic("zero"), 0), new Packet.Subscription(r.topic("one"), 1),
                        new Packet.Subscription(r.topic("two"), 2));
            }),
            new ParityScenario("subscribe-no-local", "No Local keeps a client's own messages from it (MQTT 3.8.3.1)",
                    r -> {
                final String topic = r.topic("a");
                final ParityProbe s = connected(r, "sub");
                s.subscribe(new Packet.Subscription(topic, 1, true, false, 0));
                s.publish(message(topic, "own", 1, false));
                s.next();
                final ParityProbe o = connected(r, "other");
                o.publish(message(topic, "other", 1, false));
                o.next();
                s.drain();
            }),
            new ParityScenario("subscribe-retain-as-published", "Retain As Published keeps a forwarded message's "
                    + "retain flag; without it the flag is cleared; a retained message sent at subscribe keeps it "
                    + "(MQTT 3.3.1.3)", r -> {
                final String topic = r.topic("a");
                final ParityProbe p = connected(r, "pub");
                p.publish(message(topic, "stored", 1, true));
                p.next();
                final ParityProbe kept = connected(r, "rap");
                kept.subscribe(new Packet.Subscription(topic, 1, false, true, 0));
                kept.drain();
                final ParityProbe cleared = connected(r, "plain");
                cleared.subscribe(new Packet.Subscription(topic, 1));
                cleared.drain();
                p.publish(message(topic, "live", 1, true));
                p.next();
                kept.drain();
                cleared.drain();
                p.publish(message(topic, "", 1, true));
                p.next();
            }),
            new ParityScenario("subscribe-retain-handling", "Retain Handling 0 sends the retained message at every "
                    + "subscribe, 1 only when the subscription is new, 2 never (MQTT 3.8.3.1)", r -> {
                final String topic = r.topic("a");
                final ParityProbe p = connected(r, "pub");
                p.publish(message(topic, "stored", 1, true));
                p.next();
                final ParityProbe zero = connected(r, "rh0");
                for (int i = 0; i < 2; i++) {
                    zero.subscribe(new Packet.Subscription(topic, 1, false, false, 0));
                    zero.drain();
                }
                final ParityProbe one = connected(r, "rh1");
                for (int i = 0; i < 2; i++) {
                    one.subscribe(new Packet.Subscription(topic, 1, false, false, 1));
                    one.drain();
                }
                final ParityProbe two = connected(r, "rh2");
                two.subscribe(new Packet.Subscription(topic, 1, false, false, 2));
                two.drain();
                p.publish(message(topic, "", 1, true));
                p.next();
            }),
            new ParityScenario("subscribe-shared", "a Shared Subscription (MQTT 4.8.2)", r -> {
                final ParityProbe s = connected(r, "sub");
                s.subscribe(new Packet.Subscription("$share/parity/" + r.topic("a"), 1));
                s.drain();
            }),
            new ParityScenario("subscribe-subscription-identifier", "a SUBSCRIBE with a Subscription Identifier "
                    + "(MQTT 3.8.2.1.2)", r -> {
                final String topic = r.topic("a");
                final ParityProbe s = connected(r, "sub");
                s.raw(ParityRaw.subscribeWithIdentifier(s.packetId(), topic, 1, 7));
                s.next();
                final ParityProbe p = connected(r, "pub");
                p.publish(message(topic, "tagged", 0, false));
                s.drain();
            }),
            new ParityScenario("subscribe-wildcard-skips-dollar", "a filter starting with + matches no topic "
                    + "starting with $ (MQTT 4.7.2)", r -> {
                final ParityProbe s = connected(r, "sub");
                s.subscribe(new Packet.Subscription("+/" + r.topic("a"), 1));
                final ParityProbe p = connected(r, "pub");
                p.publish(message("$dollar/" + r.topic("a"), "hidden", 1, false));
                p.next();
                p.publish(message("plain/" + r.topic("a"), "seen", 1, false));
                p.next();
                s.drain();
            }),
            new ParityScenario("unsubscribe", "UNSUBACK answers 0x00 for a subscription and 0x11 for none "
                    + "(MQTT 3.11.3)", r -> {
                final ParityProbe s = connected(r, "sub");
                s.subscribe(new Packet.Subscription(r.topic("a"), 1));
                s.send(new Packet.Unsubscribe(s.packetId(), List.of(r.topic("a"), r.topic("missing"))));
                s.next();
            }),
            new ParityScenario("publish-qos-0-and-1", "QoS 0 and 1 messages reach a subscriber at their QoS; a "
                    + "QoS 1 one nobody subscribes to is acknowledged 0x10 (MQTT 3.4.2.1)", r -> {
                final String topic = r.topic("a");
                final ParityProbe s = connected(r, "sub");
                s.subscribe(new Packet.Subscription(topic, 1));
                final ParityProbe p = connected(r, "pub");
                p.publish(message(topic, "zero", 0, false));
                p.publish(message(topic, "one", 1, false));
                p.next();
                p.publish(message(r.topic("nobody"), "lost", 1, false));
                p.next();
                s.drain();
            }),
            new ParityScenario("retained-set-and-clear", "a retained message reaches a later subscriber; an empty "
                    + "retained one clears it and is forwarded (MQTT 3.3.1.3)", r -> {
                final String topic = r.topic("a");
                final ParityProbe p = connected(r, "pub");
                p.publish(message(topic, "stored", 1, true));
                p.next();
                final ParityProbe first = connected(r, "first");
                first.subscribe(new Packet.Subscription(topic, 1));
                first.drain();
                p.publish(message(topic, "", 1, true));
                p.next();
                first.drain();
                final ParityProbe later = connected(r, "later");
                later.subscribe(new Packet.Subscription(topic, 1));
                later.drain();
            }),
            new ParityScenario("will-on-cut", "a connection cut without DISCONNECT publishes its will (MQTT 3.1.2.5)",
                    r -> {
                final ParityProbe o = observer(r);
                final ParityProbe c = r.open("c");
                c.connect(willing(r, 0, 0));
                c.cut();
                o.next();
            }),
            new ParityScenario("will-not-after-disconnect", "DISCONNECT 0x00 discards the will (MQTT 3.14.4)", r -> {
                final ParityProbe o = observer(r);
                final ParityProbe c = r.open("c");
                c.connect(willing(r, 0, 0));
                c.disconnect(Packet.SUCCESS, -1);
                o.drain();
            }),
            new ParityScenario("will-on-disconnect-with-will", "DISCONNECT 0x04 publishes the will "
                    + "(MQTT 3.14.2.1)", r -> {
                final ParityProbe o = observer(r);
                final ParityProbe c = r.open("c");
                c.connect(willing(r, 0, 0));
                c.disconnect(Packet.DISCONNECT_WITH_WILL, -1);
                o.next();
            }),
            new ParityScenario("will-delayed", "a will with a delay is published no sooner than the delay after "
                    + "the cut (MQTT 3.1.3.2.2)", r -> {
                final ParityProbe o = observer(r);
                final ParityProbe c = r.open("c");
                c.connect(willing(r, 10, WILL_DELAY_SECONDS));
                final long cutNanos = System.nanoTime();
                c.cut();
                final Packet will = o.next(Duration.ofSeconds(WILL_DELAY_SECONDS + WILL_MARGIN_SECONDS));
                final Duration after = Duration.ofNanos(System.nanoTime() - cutNanos);
                LOG.log(System.Logger.Level.INFO, "will published after the cut [broker=" + r.broker()
                        + ", delaySeconds=" + WILL_DELAY_SECONDS + ", afterMillis=" + after.toMillis() + ']');
                r.note("o", "publishedNoSoonerThanDelay", will != null
                        && after.compareTo(Duration.ofSeconds(WILL_DELAY_SECONDS)) >= 0);
            }),
            new ParityScenario("will-delay-cancelled-by-reconnect", "a reconnect within the will delay keeps the "
                    + "will unpublished (MQTT 3.1.3.2.2)", r -> {
                final ParityProbe o = observer(r);
                final ParityProbe c = r.open("c");
                c.connect(willing(r, 30, WILL_DELAY_SECONDS));
                c.cut();
                final ParityProbe again = r.open("c-again");
                again.connect(session(r.clientId("c"), false, 30));
                o.next(Duration.ofSeconds(WILL_DELAY_SECONDS + WILL_MARGIN_SECONDS));
                again.disconnect(Packet.SUCCESS, 0);
            }),
            new ParityScenario("message-expiry-forwarded", "a forwarded message keeps a Message Expiry Interval, "
                    + "no greater than the one published (MQTT 3.3.2.3.3)", r -> {
                final String topic = r.topic("a");
                final ParityProbe s = connected(r, "sub");
                s.subscribe(new Packet.Subscription(topic, 1));
                final ParityProbe p = connected(r, "pub");
                p.publish(message(topic, "expiring", 1, false).withMeta(Packet.Meta.NONE.withMessageExpiry(60)));
                p.next();
                final Packet got = s.next();
                r.note("sub", "expiryWithinPublished", got instanceof final Packet.Publish m
                        && m.meta().messageExpirySeconds() > 0 && m.meta().messageExpirySeconds() <= 60);
            }),
            new ParityScenario("message-expiry-retained", "an expired retained message is not sent to a later "
                    + "subscriber (MQTT 3.3.2.3.3)", r -> {
                final String topic = r.topic("a");
                final ParityProbe p = connected(r, "pub");
                p.publish(message(topic, "short", 1, true).withMeta(
                        Packet.Meta.NONE.withMessageExpiry(EXPIRES_SECONDS)));
                p.next();
                p.quietFor(EXPIRY_WAIT);
                final ParityProbe s = connected(r, "sub");
                s.subscribe(new Packet.Subscription(topic, 1));
                s.drain();
            }),
            new ParityScenario("message-expiry-queued", "a message that expires while queued for an offline "
                    + "session is not delivered; one without expiry is (MQTT 3.3.2.3.3)", r -> {
                final String topic = r.topic("a");
                final ParityProbe s = r.open("sub");
                s.connect(session(r.clientId("sub"), true, SESSION_SECONDS));
                s.subscribe(new Packet.Subscription(topic, 1));
                s.disconnect(Packet.SUCCESS, -1);
                final ParityProbe p = connected(r, "pub");
                p.publish(message(topic, "stale", 1, false).withMeta(
                        Packet.Meta.NONE.withMessageExpiry(EXPIRES_SECONDS)));
                p.next();
                p.publish(message(topic, "fresh", 1, false));
                p.next();
                p.quietFor(EXPIRY_WAIT);
                final ParityProbe again = r.open("sub-again");
                again.connect(session(r.clientId("sub"), false, SESSION_SECONDS));
                again.drain();
                again.disconnect(Packet.SUCCESS, 0);
            }),
            new ParityScenario("request-response", "Response Topic, Correlation Data, Content Type, Payload "
                    + "Format and User Properties, repeated names in order, pass unchanged both ways "
                    + "(MQTT 4.10, MQTT 3.3.2.3.7)", r -> {
                final String request = r.topic("request");
                final String response = r.topic("response");
                final ParityProbe asker = connected(r, "asker");
                asker.subscribe(new Packet.Subscription(response, 1));
                final ParityProbe answerer = connected(r, "answerer");
                answerer.subscribe(new Packet.Subscription(request, 1));
                asker.publish(new Packet.Publish(request, bytes("question"), 1, false, false, 0, response,
                        bytes("c-1"), new Packet.Meta(1, -1, "text/plain", List.of(new Packet.UserProperty("a", "1"),
                        new Packet.UserProperty("a", "2"), new Packet.UserProperty("b", "3")))));
                asker.next();
                final Packet asked = answerer.next();
                if (asked instanceof final Packet.Publish q && q.responseTopic() != null) {
                    answerer.publish(new Packet.Publish(q.responseTopic(), bytes("answer"), 1, false, false, 0, null,
                            q.correlation(), new Packet.Meta(-1, -1, null,
                            List.of(new Packet.UserProperty("status", "ok")))));
                    answerer.next();
                }
                asker.drain();
            }),
            new ParityScenario("receive-maximum", "no more unacknowledged QoS 1 messages than the client's Receive "
                    + "Maximum are sent to it, and every one is sent once acknowledged (MQTT 3.3.4); how many a broker "
                    + "sends before the first acknowledgement is its own, so the order is noted, not compared", r -> {
                final String topic = r.topic("a");
                final ParityProbe s = r.open("sub");
                s.connect(new Packet.Connect(r.clientId("sub"), true, 60, 0, RECEIVE_TWO, LARGEST, null));
                s.autoAck(false);
                s.subscribe(new Packet.Subscription(topic, 1));
                s.recording(false);
                final ParityProbe p = connected(r, "pub");
                for (int i = 1; i <= 4; i++) {
                    p.publish(message(topic, "m" + i, 1, false));
                    p.next();
                }
                final List<Packet> sent = new ArrayList<>(s.drain());
                final int beforeFirstAck = sent.size();
                int inFlight = beforeFirstAck;
                int mostInFlight = inFlight;
                for (int acked = 0; acked < sent.size(); acked++) {
                    if (sent.get(acked) instanceof final Packet.Publish m) {
                        s.send(new Packet.PubAck(m.packetId(), Packet.SUCCESS, null));
                        final List<Packet> more = s.drain();
                        sent.addAll(more);
                        inFlight += more.size() - 1;
                        mostInFlight = Math.max(mostInFlight, inFlight);
                    }
                }
                LOG.log(System.Logger.Level.INFO, "receive maximum held [broker=" + r.broker() + ", receiveMaximum="
                        + RECEIVE_TWO + ", mostInFlight=" + mostInFlight + ", beforeFirstAck=" + beforeFirstAck + ']');
                r.note("sub", "withinReceiveMaximum", mostInFlight >= 1 && mostInFlight <= RECEIVE_TWO);
                r.note("sub", "delivered", sent.stream().map(m -> m instanceof final Packet.Publish q
                        ? new String(q.payload(), StandardCharsets.UTF_8) : "?").toList());
            }),
            new ParityScenario("session-takeover", "a second connection of a client id takes the session over; "
                    + "the first gets DISCONNECT 0x8E (MQTT 3.1.4)", r -> {
                final ParityProbe first = r.open("first");
                first.connect(clean(r.clientId("same")));
                final ParityProbe second = r.open("second");
                second.connect(clean(r.clientId("same")));
                first.next();
                first.next();
            }),
            new ParityScenario("publish-qos-2", "a QoS 2 PUBLISH (MQTT 4.3.3)", r -> {
                final String topic = r.topic("a");
                final ParityProbe s = connected(r, "sub");
                s.subscribe(new Packet.Subscription(topic, 1));
                final ParityProbe p = connected(r, "pub");
                p.publish(message(topic, "exactly-once", 2, false));
                p.drain();
                s.drain();
            }),
            new ParityScenario("publish-topic-alias", "a PUBLISH that sets a Topic Alias (MQTT 3.3.2.3.4)", r -> {
                final String topic = r.topic("a");
                final ParityProbe s = connected(r, "sub");
                s.subscribe(new Packet.Subscription(topic, 1));
                final ParityProbe p = connected(r, "pub");
                p.raw(ParityRaw.publishWithTopicAlias(topic, 1, bytes("aliased")));
                p.drain();
                s.drain();
            }));

    private ParityScenarios() {
    }

    /// A clean-start connection labelled `label`, connected.
    private static ParityProbe connected(final ParityRun r, final String label) throws IOException {
        final ParityProbe p = r.open(label);
        p.connect(clean(r.clientId(label)));
        return p;
    }

    /// A connection subscribed at QoS 1 to the run's will topic, labelled `o`.
    private static ParityProbe observer(final ParityRun r) throws IOException {
        final ParityProbe o = connected(r, "o");
        o.subscribe(new Packet.Subscription(r.topic("will"), 1));
        return o;
    }

    /// A CONNECT of client `c` with a QoS 1 will on the run's will topic, delayed `delaySeconds`, and a session of
    /// `sessionSeconds`.
    private static Packet.Connect willing(final ParityRun r, final long sessionSeconds, final long delaySeconds) {
        return new Packet.Connect(r.clientId("c"), true, 60, sessionSeconds, RECEIVE_ALL, LARGEST,
                message(r.topic("will"), "gone", 1, false), delaySeconds);
    }

    private static Packet.Connect session(final String id, final boolean cleanStart, final long expirySeconds) {
        return new Packet.Connect(id, cleanStart, 60, expirySeconds, RECEIVE_ALL, LARGEST, null);
    }

    private static Packet.Publish message(final String topic, final String payload, final int qos,
            final boolean retain) {
        return new Packet.Publish(topic, bytes(payload), qos, retain, false, 0, null, null);
    }

    private static byte[] bytes(final String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /// One scenario: its name in the report, what it checks, and the client actions.
    record ParityScenario(String name, String checks, Body body) {
    }

    /// A scenario's client actions against the broker of `run`.
    @FunctionalInterface
    interface Body {

        void run(ParityRun run) throws Exception;
    }
}
