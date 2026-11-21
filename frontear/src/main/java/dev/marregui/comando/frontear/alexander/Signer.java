package dev.marregui.comando.frontear.alexander;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.time.InstantSource;
import java.util.HexFormat;
import java.util.Objects;

import dev.marregui.comando.frontear.protocol.Packet;

/**
 * Signs control messages for a {@link ControlPlane}: what the comando control pod publishes to
 * {@code $frontear/control/<node>/<Method>}. The plane accepts them when it trusts the key whose id the signer was
 * given, which is {@link #keyId} of the key's public half. Safe for use by several threads.
 */
public final class Signer {

    private static final HexFormat HEX = HexFormat.of();

    private final PrivateKey key;
    private final String keyId;
    private final InstantSource clock = InstantSource.system();
    private final SecureRandom random = new SecureRandom();

    /**
     * A signer with the Ed25519 {@code key}, naming it {@code keyId}.
     *
     * @throws IllegalArgumentException when {@code key} is not an Ed25519 private key or {@code keyId} is empty
     */
    public Signer(final PrivateKey key, final String keyId) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(keyId, "keyId");
        Seal.checkPrivate(key);
        if (keyId.isEmpty()) {
            throw new IllegalArgumentException("a key id cannot be empty");
        }
        this.key = key;
        this.keyId = keyId;
    }

    /**
     * The key id a {@link ControlPlane} gives {@code key}: the lower-case hex of the SHA-256 of its X.509 encoding.
     *
     * @throws IllegalArgumentException when {@code key} is not an Ed25519 public key
     */
    public static String keyId(final PublicKey key) {
        return Seal.keyId(Objects.requireNonNull(key, "key"));
    }

    /**
     * A QoS 1 message to {@code topic} carrying {@code payload}, content type {@code application/json}, signed now
     * with a fresh nonce. {@code responseTopic} and {@code correlation} are null for none; the signature covers
     * them.
     *
     * @throws IllegalArgumentException when {@code topic} or {@code responseTopic} is not a topic name, or
     *     {@code correlation} is longer than 65,535 bytes
     */
    public Packet.Publish sign(final String topic, final byte[] payload, final String responseTopic,
            final byte[] correlation) {
        Objects.requireNonNull(topic, "topic");
        final byte[] nonce = new byte[Seal.NONCE_BYTES];
        random.nextBytes(nonce);
        return Seal.seal(key, keyId, topic, clock.millis(), HEX.formatHex(nonce), payload == null ? new byte[0]
                : payload, responseTopic, correlation);
    }
}
