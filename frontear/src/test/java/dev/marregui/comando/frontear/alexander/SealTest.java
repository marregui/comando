package dev.marregui.comando.frontear.alexander;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.marregui.comando.frontear.protocol.Names;
import dev.marregui.comando.frontear.protocol.Packet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The signed form of a control message and the receiver's check, the format's one definition:
/// - the canonical bytes are topic, ts, nonce, content type and response topic, each ended by 0x00, then the
///   correlation data after its 2-byte big-endian length, then the payload
/// - a sealed message carries the four properties and an Ed25519 signature over those bytes
/// - a missing, repeated or ill-formed property, an untrusted key, a ts outside 30 s, a change to any signed part
///   and a replayed nonce are each refused with their own reason, and a refused message spends no nonce
/// - a ts before the receiver was made is stale, so a restart, which forgets the nonces, reopens no replay
/// - nonces are forgotten once their window passes, a clock that steps back reopens none, and the memory is bounded
class SealTest {

    private static final String TOPIC = "$frontear/control/n1/PutLink";
    private static final byte[] PAYLOAD = "{\"id\":\"a\"}".getBytes(StandardCharsets.UTF_8);
    private static final long START_MILLIS = 1_800_000_000_000L;

    private static KeyPair trustedKeys;
    private static KeyPair otherKeys;
    private static String trustedId;

    private TestClock clock;
    private Seal seal;
    private int nonces;

    @BeforeAll
    static void keys() throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        trustedKeys = generator.generateKeyPair();
        otherKeys = generator.generateKeyPair();
        trustedId = Seal.keyId(trustedKeys.getPublic());
    }

    @BeforeEach
    void receiver() {
        clock = new TestClock(START_MILLIS);
        seal = new Seal(Set.of(trustedKeys.getPublic()), clock);
    }

    /// The canonical bytes, written out by hand: each of the five strings in UTF-8 ended by one 0x00, the
    /// correlation data after its length in two big-endian bytes, then the payload as is; an absent content type or
    /// response topic is an empty string and absent correlation data has length 0.
    @Test
    void theCanonicalBytesAreTheFiveStringsEachEndedByZeroThenTheCorrelationThenThePayload() throws Exception {
        final ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.write("a/b".getBytes(StandardCharsets.UTF_8));
        expected.write(0);
        expected.write("12".getBytes(StandardCharsets.UTF_8));
        expected.write(0);
        expected.write("00ff".getBytes(StandardCharsets.UTF_8));
        expected.write(0);
        expected.write("application/json".getBytes(StandardCharsets.UTF_8));
        expected.write(0);
        expected.write("replies/r".getBytes(StandardCharsets.UTF_8));
        expected.write(0);
        expected.write(new byte[] {0x01, 0x02});
        final byte[] correlation = new byte[0x0102];
        correlation[0] = 7;
        correlation[correlation.length - 1] = 9;
        expected.write(correlation);
        expected.write(new byte[] {1, 0, 2});
        assertThat(Seal.canonical("a/b", "12", "00ff", "application/json", "replies/r", correlation,
                new byte[] {1, 0, 2})).isEqualTo(expected.toByteArray());
        assertThat(Seal.canonical("t/é", "1", "n", null, null, null, new byte[0]))
                .isEqualTo(new byte[] {'t', '/', (byte) 0xC3, (byte) 0xA9, 0, '1', 0, 'n', 0, 0, 0, 0, 0});
        assertThat(Seal.canonical("t", "1", "n", null, "", new byte[0], new byte[0]))
                .as("empty is the same as absent").isEqualTo(Seal.canonical("t", "1", "n", null, null, null,
                        new byte[0]));
        assertThatThrownBy(() -> Seal.canonical("t", "1", "n", null, null, new byte[0x10000], new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /// A sealed message is QoS 1 JSON with the four properties, keeps its response topic and correlation data, and
    /// its signature verifies with the plain JDK over the canonical bytes, so the format is what the javadoc says.
    @Test
    void aSealedMessageCarriesAnEd25519SignatureOverTheCanonicalBytes() throws Exception {
        final byte[] correlation = {9, 8, 7};
        final Packet.Publish message = Seal.seal(trustedKeys.getPrivate(), trustedId, TOPIC, START_MILLIS,
                nonce(), PAYLOAD, "replies/r", correlation);
        assertThat(message.qos()).isEqualTo(1);
        assertThat(message.retain()).isFalse();
        assertThat(message.responseTopic()).isEqualTo("replies/r");
        assertThat(message.correlation()).isEqualTo(correlation);
        assertThat(message.meta().contentType()).isEqualTo(Names.JSON);
        assertThat(message.meta().userProperties()).extracting(Packet.UserProperty::name)
                .containsExactly(Seal.KEY, Seal.TS, Seal.NONCE, Seal.SIG);
        assertThat(property(message, Seal.KEY)).isEqualTo(trustedId);
        assertThat(property(message, Seal.TS)).isEqualTo(Long.toString(START_MILLIS));

        final Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(trustedKeys.getPublic());
        verifier.update(Seal.canonical(TOPIC, property(message, Seal.TS), property(message, Seal.NONCE), Names.JSON,
                "replies/r", correlation, PAYLOAD));
        assertThat(verifier.verify(Base64.getDecoder().decode(property(message, Seal.SIG)))).isTrue();
        assertThat(seal.check(message)).isEmpty();
    }

    /// Each of the four properties missing is unsigned; one given twice is malformed, so a receiver never picks one
    /// of two values the signer may not have meant.
    @Test
    void aMissingPropertyIsUnsignedAndARepeatedOneMalformed() {
        final Packet.Publish message = sealed(START_MILLIS);
        for (final String name : List.of(Seal.KEY, Seal.TS, Seal.NONCE, Seal.SIG)) {
            final List<Packet.UserProperty> without = new ArrayList<>(message.meta().userProperties());
            without.removeIf(p -> p.name().equals(name));
            assertThat(seal.check(withProperties(message, without))).as(name).contains(Refusal.UNSIGNED);
        }
        assertThat(seal.check(withProperties(message, List.of()))).contains(Refusal.UNSIGNED);
        final List<Packet.UserProperty> twice = new ArrayList<>(message.meta().userProperties());
        twice.add(new Packet.UserProperty(Seal.TS, property(message, Seal.TS)));
        assertThat(seal.check(withProperties(message, twice))).contains(Refusal.MALFORMED);
        assertThat(seal.check(message)).as("the genuine message after them").isEmpty();
    }

    /// A ts that is not 1 to 18 decimal digits, a nonce that is not 32 lower-case hex digits and a signature that is
    /// not base64 are malformed.
    @Test
    void anIllFormedPropertyIsMalformed() {
        final Packet.Publish message = sealed(START_MILLIS);
        for (final String ts : List.of("", "abc", "-5", "+5", "1".repeat(19), " 1")) {
            assertThat(seal.check(replace(message, Seal.TS, ts))).as("ts '%s'", ts).contains(Refusal.MALFORMED);
        }
        for (final String nonce : List.of("", "00", "0".repeat(31), "0".repeat(33), "A".repeat(32),
                "g".repeat(32))) {
            assertThat(seal.check(replace(message, Seal.NONCE, nonce))).as("nonce '%s'", nonce)
                    .contains(Refusal.MALFORMED);
        }
        assertThat(seal.check(replace(message, Seal.SIG, "!!!"))).contains(Refusal.MALFORMED);
        assertThat(seal.check(replace(message, Seal.SIG, ""))).as("an empty signature is base64 and does not verify")
                .contains(Refusal.BAD_SIGNATURE);
    }

    /// A key id the receiver does not trust is refused, and a trusted key id on a message another key signed does
    /// not verify.
    @Test
    void anUntrustedKeyIsRefused() {
        final Packet.Publish byOther = Seal.seal(otherKeys.getPrivate(), Seal.keyId(otherKeys.getPublic()), TOPIC,
                START_MILLIS, nonce(), PAYLOAD, null, null);
        assertThat(seal.check(byOther)).contains(Refusal.UNKNOWN_KEY);
        final Packet.Publish claimed = Seal.seal(otherKeys.getPrivate(), trustedId, TOPIC, START_MILLIS, nonce(),
                PAYLOAD, null, null);
        assertThat(seal.check(claimed)).contains(Refusal.BAD_SIGNATURE);
    }

    /// The window is 30 s either way, inclusive: a ts 30,000 ms off is accepted, 30,001 ms off is stale.
    @Test
    void aTsOutsideThirtySecondsIsStale() {
        final long window = Seal.WINDOW.toMillis();
        clock.advance(Seal.WINDOW.plusSeconds(1));
        final long nowMillis = clock.millis();
        assertThat(seal.check(sealed(nowMillis - window))).isEmpty();
        assertThat(seal.check(sealed(nowMillis + window))).isEmpty();
        assertThat(seal.check(sealed(nowMillis - window - 1))).contains(Refusal.STALE);
        assertThat(seal.check(sealed(nowMillis + window + 1))).contains(Refusal.STALE);
    }

    /// A ts within the window but 1 ms before the receiver was made is stale, and one at that moment is accepted.
    @Test
    void aTsBeforeTheReceiverStartedIsStale() {
        clock.advance(Duration.ofSeconds(5));
        assertThat(seal.check(sealed(START_MILLIS - 1))).contains(Refusal.STALE);
        assertThat(seal.check(sealed(START_MILLIS))).isEmpty();
    }

    /// A message the receiver accepted is stale to the receiver made after it, 5 s later as after a restart, though
    /// its ts is within the window and the new receiver never saw its nonce.
    @Test
    void aMessageAcceptedBeforeARestartIsStaleAfterIt() {
        final Packet.Publish message = sealed(START_MILLIS);
        assertThat(seal.check(message)).isEmpty();
        clock.advance(Duration.ofSeconds(5));
        final Seal restarted = new Seal(Set.of(trustedKeys.getPublic()), clock);
        assertThat(restarted.check(message)).contains(Refusal.STALE);
        assertThat(restarted.check(sealed(clock.millis()))).as("a message signed after the restart").isEmpty();
    }

    /// A change to the payload, the topic, the ts, the nonce, the content type, the response topic or the correlation
    /// data after signing does not verify, so a copy of a request cannot be answered on another route.
    @Test
    void aChangeToAnySignedPartDoesNotVerify() {
        final Packet.Publish message = Seal.seal(trustedKeys.getPrivate(), trustedId, TOPIC, START_MILLIS, nonce(),
                PAYLOAD, "replies/r", new byte[] {9, 8, 7});
        final Packet.Meta meta = message.meta();
        assertThat(seal.check(with(message, TOPIC, "{\"id\":\"b\"}".getBytes(StandardCharsets.UTF_8), meta)))
                .as("payload").contains(Refusal.BAD_SIGNATURE);
        assertThat(seal.check(with(message, "$frontear/control/n2/PutLink", PAYLOAD, meta))).as("topic")
                .contains(Refusal.BAD_SIGNATURE);
        assertThat(seal.check(with(message, "$frontear/control/n1/RemoveLink", PAYLOAD, meta))).as("method")
                .contains(Refusal.BAD_SIGNATURE);
        assertThat(seal.check(replace(message, Seal.TS, Long.toString(START_MILLIS + 1)))).as("ts")
                .contains(Refusal.BAD_SIGNATURE);
        assertThat(seal.check(replace(message, Seal.NONCE, nonce()))).as("nonce").contains(Refusal.BAD_SIGNATURE);
        assertThat(seal.check(with(message, TOPIC, PAYLOAD, new Packet.Meta(meta.payloadFormat(),
                meta.messageExpirySeconds(), "text/plain", meta.userProperties())))).as("content type")
                .contains(Refusal.BAD_SIGNATURE);
        assertThat(seal.check(with(message, TOPIC, PAYLOAD, new Packet.Meta(meta.payloadFormat(),
                meta.messageExpirySeconds(), null, meta.userProperties())))).as("no content type")
                .contains(Refusal.BAD_SIGNATURE);
        assertThat(seal.check(route(message, "replies/attacker", message.correlation()))).as("response topic")
                .contains(Refusal.BAD_SIGNATURE);
        assertThat(seal.check(route(message, null, message.correlation()))).as("no response topic")
                .contains(Refusal.BAD_SIGNATURE);
        assertThat(seal.check(route(message, message.responseTopic(), new byte[] {9, 8, 6}))).as("correlation")
                .contains(Refusal.BAD_SIGNATURE);
        assertThat(seal.check(route(message, message.responseTopic(), null))).as("no correlation")
                .contains(Refusal.BAD_SIGNATURE);
        assertThat(seal.check(message)).as("the genuine message after them").isEmpty();
    }

    /// A nonce accepted once is refused as replayed, also on a message re-signed with it; a message refused before
    /// the nonce check does not spend the nonce of the genuine one.
    @Test
    void aReplayedNonceIsRefusedAndARefusedMessageSpendsNoNonce() {
        final String nonce = nonce();
        final Packet.Publish genuine = Seal.seal(trustedKeys.getPrivate(), trustedId, TOPIC, START_MILLIS, nonce,
                PAYLOAD, null, null);
        final Packet.Publish forged = with(genuine, TOPIC, "{}".getBytes(StandardCharsets.UTF_8), genuine.meta());
        assertThat(seal.check(forged)).contains(Refusal.BAD_SIGNATURE);
        assertThat(seal.check(genuine)).isEmpty();
        assertThat(seal.check(genuine)).contains(Refusal.REPLAYED);
        clock.advance(Duration.ofSeconds(10));
        final Packet.Publish again = Seal.seal(trustedKeys.getPrivate(), trustedId, TOPIC, clock.millis(), nonce,
                PAYLOAD, null, null);
        assertThat(seal.check(again)).contains(Refusal.REPLAYED);
    }

    /// Once a nonce's window has passed, its message is stale rather than replayed, and a clock that steps back
    /// inside the window does not make it acceptable again although the nonce was forgotten.
    @Test
    void aForgottenNonceStaysRefusedWhenTheClockStepsBack() {
        final Packet.Publish first = sealed(START_MILLIS);
        assertThat(seal.check(first)).isEmpty();
        clock.advance(Duration.ofSeconds(40));
        assertThat(seal.check(first)).contains(Refusal.STALE);
        assertThat(seal.check(sealed(clock.millis()))).as("a fresh message forgets the old nonces").isEmpty();
        clock.set(START_MILLIS + Duration.ofSeconds(10).toMillis());
        assertThat(seal.check(first)).contains(Refusal.STALE);
        assertThat(seal.check(sealed(clock.millis()))).as("a message within the latest time's window").isEmpty();
    }

    /// The receiver remembers at most NONCES_MAX nonces: past them a valid message is refused as busy, and once
    /// their window has passed they are forgotten and messages are accepted again.
    @Test
    void theNonceMemoryIsBoundedAndForgetsWhenTheWindowPasses() {
        for (int i = 0; i < Seal.NONCES_MAX; i++) {
            assertThat(seal.check(sealed(START_MILLIS))).isEmpty();
        }
        assertThat(seal.check(sealed(START_MILLIS))).contains(Refusal.BUSY);
        clock.advance(Seal.WINDOW.plusMillis(1));
        assertThat(seal.check(sealed(clock.millis()))).isEmpty();
    }

    /// A key id is the lower-case hex SHA-256 of the key's X.509 encoding; a key that is not Ed25519 is refused, as
    /// a trusted key and as a signing key.
    @Test
    void aKeyIdIsTheSha256OfTheEncodedKeyAndOnlyEd25519IsTaken() throws Exception {
        final String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(trustedKeys.getPublic().getEncoded()));
        assertThat(Seal.keyId(trustedKeys.getPublic())).isEqualTo(expected).hasSize(64);
        assertThat(Seal.keyId(otherKeys.getPublic())).isNotEqualTo(expected);
        final KeyPair ed448 = KeyPairGenerator.getInstance("Ed448").generateKeyPair();
        final KeyPair rsa = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        assertThatThrownBy(() -> Seal.keyId(ed448.getPublic())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Seal.keyId(rsa.getPublic())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Seal(Set.of(rsa.getPublic()), clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Seal.checkPrivate(ed448.getPrivate())).isInstanceOf(IllegalArgumentException.class);
        Seal.checkPrivate(trustedKeys.getPrivate());
    }

    static String property(final Packet.Publish message, final String name) {
        for (final Packet.UserProperty property : message.meta().userProperties()) {
            if (property.name().equals(name)) {
                return property.value();
            }
        }
        return null;
    }

    static Packet.Publish replace(final Packet.Publish message, final String name, final String value) {
        final List<Packet.UserProperty> properties = new ArrayList<>();
        for (final Packet.UserProperty property : message.meta().userProperties()) {
            properties.add(property.name().equals(name) ? new Packet.UserProperty(name, value) : property);
        }
        return withProperties(message, properties);
    }

    static Packet.Publish withProperties(final Packet.Publish message, final List<Packet.UserProperty> properties) {
        final Packet.Meta meta = message.meta();
        return with(message, message.topic(), message.payload(), new Packet.Meta(meta.payloadFormat(),
                meta.messageExpirySeconds(), meta.contentType(), properties));
    }

    /// `message` with its response topic and correlation data replaced, everything else kept.
    static Packet.Publish route(final Packet.Publish message, final String responseTopic, final byte[] correlation) {
        return new Packet.Publish(message.topic(), message.payload(), message.qos(), message.retain(), message.dup(),
                message.packetId(), responseTopic, correlation, message.meta());
    }

    static Packet.Publish with(final Packet.Publish message, final String topic, final byte[] payload,
            final Packet.Meta meta) {
        return new Packet.Publish(topic, payload, message.qos(), message.retain(), message.dup(), message.packetId(),
                message.responseTopic(), message.correlation(), meta);
    }

    private Packet.Publish sealed(final long tsMillis) {
        return Seal.seal(trustedKeys.getPrivate(), trustedId, TOPIC, tsMillis, nonce(), PAYLOAD, null, null);
    }

    private String nonce() {
        return "%032x".formatted(++nonces);
    }
}
