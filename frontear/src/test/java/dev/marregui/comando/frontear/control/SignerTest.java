package dev.marregui.comando.frontear.control;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.InstantSource;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.marregui.comando.frontear.mqtt.Names;
import dev.marregui.comando.frontear.mqtt.Packet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The signer the control pod uses:
/// - what it signs passes the check of a receiver that trusts its key, with its response topic and correlation data
/// - the signature covers the response topic and the correlation data
/// - each message draws a fresh nonce
/// - a key that is not an Ed25519 private key, and an empty key id, are refused
class SignerTest {

    private static KeyPair keys;

    @BeforeAll
    static void keys() throws Exception {
        keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    /// A signed message passes a receiver trusting the key by its key id, and carries the response topic and the
    /// correlation data it was given.
    @Test
    void aSignedMessagePassesAReceiverThatTrustsTheKey() {
        final Signer signer = new Signer(keys.getPrivate(), Signer.keyId(keys.getPublic()));
        final Seal seal = new Seal(Set.of(keys.getPublic()), InstantSource.system());
        final byte[] correlation = {1, 2, 3};
        final Packet.Publish message = signer.sign("$frontear/control/n1/GetState",
                "{}".getBytes(StandardCharsets.UTF_8), "replies/pod", correlation);
        assertThat(message.responseTopic()).isEqualTo("replies/pod");
        assertThat(message.correlation()).isEqualTo(correlation);
        assertThat(message.meta().contentType()).isEqualTo(Names.JSON);
        assertThat(seal.check(message)).isEmpty();
    }

    /// The signature covers the reply route: a signed message with its response topic or its correlation data
    /// changed does not verify.
    @Test
    void theResponseTopicAndTheCorrelationDataAreSigned() {
        final Signer signer = new Signer(keys.getPrivate(), Signer.keyId(keys.getPublic()));
        final Seal seal = new Seal(Set.of(keys.getPublic()), InstantSource.system());
        final byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);
        final Packet.Publish toOther = SealTest.route(signer.sign("$frontear/control/n1/GetState", payload,
                "replies/pod", new byte[] {1, 2, 3}), "replies/other", new byte[] {1, 2, 3});
        assertThat(seal.check(toOther)).as("response topic").contains(Refusal.BAD_SIGNATURE);
        final Packet.Publish otherCorrelation = SealTest.route(signer.sign("$frontear/control/n1/GetState", payload,
                "replies/pod", new byte[] {1, 2, 3}), "replies/pod", new byte[] {1, 2, 4});
        assertThat(seal.check(otherCorrelation)).as("correlation").contains(Refusal.BAD_SIGNATURE);
    }

    /// Two messages with the same content carry different nonces of 16 bytes in hex.
    @Test
    void eachMessageDrawsAFreshNonce() {
        final Signer signer = new Signer(keys.getPrivate(), Signer.keyId(keys.getPublic()));
        final byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);
        final String first = SealTest.property(signer.sign("t", payload, null, null), Seal.NONCE);
        final String second = SealTest.property(signer.sign("t", payload, null, null), Seal.NONCE);
        assertThat(first).hasSize(2 * Seal.NONCE_BYTES).isNotEqualTo(second);
        assertThat(second).hasSize(2 * Seal.NONCE_BYTES);
    }

    /// A key other than an Ed25519 private key, and an empty key id, are refused at construction.
    @Test
    void anotherKindOfKeyAndAnEmptyKeyIdAreRefused() throws Exception {
        final KeyPair rsa = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        assertThatThrownBy(() -> new Signer(rsa.getPrivate(), "k")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Signer(keys.getPrivate(), "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Signer.keyId(rsa.getPublic())).isInstanceOf(IllegalArgumentException.class);
    }
}
