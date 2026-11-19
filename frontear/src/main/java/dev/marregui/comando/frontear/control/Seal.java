package dev.marregui.comando.frontear.control;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.interfaces.EdECPrivateKey;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.NamedParameterSpec;
import java.time.Duration;
import java.time.InstantSource;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import dev.marregui.comando.frontear.mqtt.Names;
import dev.marregui.comando.frontear.mqtt.Packet;

/**
 * The signed form of a control message, its one definition: the bytes a signature covers, the user properties that
 * carry it, and the check a receiver makes.
 *
 * <p>A signed message carries four user properties: {@value #KEY} (the signer's key id, {@link #keyId}),
 * {@value #TS} (epoch milliseconds, decimal), {@value #NONCE} ({@value #NONCE_BYTES} random bytes as lower-case hex)
 * and {@value #SIG} (base64 of the Ed25519 signature). The signature covers the canonical bytes: the UTF-8 of the
 * topic, of the ts as written, of the nonce, of the content type and of the response topic (each empty when absent),
 * each followed by a 0x00 byte; then the correlation data as a 2-byte big-endian length and its bytes (length 0 when
 * absent); then the payload. The reply route is signed too, so a copy of a request cannot be answered elsewhere.
 *
 * <p>A receiver accepts a message whose key id names a trusted key, whose ts is within {@link #WINDOW} of its clock,
 * whose signature verifies and whose nonce it has not seen within the window. It checks the nonce last, so a message
 * that fails an earlier check cannot spend the nonce of a genuine one. An instance is used by one thread at a time.
 *
 * <p>The nonces live in memory only, so a receiver also refuses as stale any ts earlier than the moment it was made:
 * a message signed before a restart may have been accepted by the receiver before it, whose nonces are gone, and
 * every message signed since is one this receiver remembers. A signer whose clock runs behind the receiver's has its
 * messages refused for that long after the receiver starts.
 */
final class Seal {

    /** The user property of the signer's key id. */
    static final String KEY = "frontear-key";
    /** The user property of the signing time, epoch milliseconds. */
    static final String TS = "frontear-ts";
    /** The user property of the nonce. */
    static final String NONCE = "frontear-nonce";
    /** The user property of the signature. */
    static final String SIG = "frontear-sig";
    /** How far a message's ts may be from the receiver's clock, either way: the control contract's 30 s. */
    static final Duration WINDOW = Duration.ofSeconds(30);
    /** The nonce's length in bytes, before hex: 128 bits, so two signers never draw the same one. */
    static final int NONCE_BYTES = 16;
    /**
     * How many nonces a receiver remembers at most; a message past them is refused as {@link Refusal#BUSY}.
     * Unmeasured: control messages come from one pod at human rates, so this many within a minute is an attack.
     */
    static final int NONCES_MAX = 10_000;
    /** How often the receiver forgets the nonces whose window has passed. Unmeasured: a scan of at most NONCES_MAX. */
    private static final Duration FORGET_EVERY = Duration.ofSeconds(1);
    private static final List<String> SIGNATURE_PROPERTIES = List.of(KEY, TS, NONCE, SIG);
    private static final String ALGORITHM = "Ed25519";
    private static final HexFormat HEX = HexFormat.of();
    private static final byte SEPARATOR = 0x00;
    /** The most digits a ts may have: every 18-digit number fits a long, so parsing one cannot overflow. */
    private static final int MAX_TS_DIGITS = 18;
    /**
     * The most correlation bytes: MQTT 5 Binary Data has a 2-byte length (§1.5.6), as does the canonical form. The
     * mqtt package's own bound is not visible here.
     */
    private static final int MAX_CORRELATION = 0xFFFF;

    private final Map<String, PublicKey> trusted;
    private final InstantSource clock;
    /** When the receiver was made: an earlier ts may carry a nonce a receiver before a restart accepted. */
    private final long startMillis;
    /** Each nonce seen, with the epoch millisecond after which its ts is outside the window. */
    private final Map<String, Long> seen = new HashMap<>();
    /** The latest time the clock gave: a ts older than it by more than the window is stale even after a step back. */
    private long latestMillis = Long.MIN_VALUE;
    /** When the nonces were last forgotten; 0 makes the first check forget, as no clock reads before 1970. */
    private long forgotMillis;

    /**
     * A receiver's seal, trusting {@code trusted} and reading the time from {@code clock}.
     *
     * @throws IllegalArgumentException when a key is not an Ed25519 key
     */
    Seal(final Set<PublicKey> trusted, final InstantSource clock) {
        final Map<String, PublicKey> byId = new HashMap<>();
        for (final PublicKey key : trusted) {
            byId.put(keyId(key), key);
        }
        this.trusted = Map.copyOf(byId);
        this.clock = clock;
        this.startMillis = clock.millis();
    }

    /**
     * The key id of {@code key}: the lower-case hex of the SHA-256 of its X.509 encoding.
     *
     * @throws IllegalArgumentException when {@code key} is not an Ed25519 key
     */
    static String keyId(final PublicKey key) {
        if (!(key instanceof final EdECPublicKey ed) || !isEd25519(ed.getParams())) {
            throw new IllegalArgumentException("not an Ed25519 public key [algorithm=" + key.getAlgorithm() + ']');
        }
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(key.getEncoded()));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("the JDK has no SHA-256", e);
        }
    }

    /** Refuses a key that is not an Ed25519 private key. */
    static void checkPrivate(final PrivateKey key) {
        if (!(key instanceof final EdECPrivateKey ed) || !isEd25519(ed.getParams())) {
            throw new IllegalArgumentException("not an Ed25519 private key [algorithm=" + key.getAlgorithm() + ']');
        }
    }

    /**
     * The bytes a signature covers: topic, ts, nonce, content type and response topic, each ended by 0x00; the
     * correlation data with its 2-byte big-endian length; then the payload. A null content type, response topic or
     * correlation is empty.
     *
     * @throws IllegalArgumentException when {@code correlation} is longer than 65,535 bytes
     */
    static byte[] canonical(final String topic, final String ts, final String nonce, final String contentType,
            final String responseTopic, final byte[] correlation, final byte[] payload) {
        final byte[][] parts = {
            topic.getBytes(StandardCharsets.UTF_8),
            ts.getBytes(StandardCharsets.UTF_8),
            nonce.getBytes(StandardCharsets.UTF_8),
            (contentType == null ? "" : contentType).getBytes(StandardCharsets.UTF_8),
            (responseTopic == null ? "" : responseTopic).getBytes(StandardCharsets.UTF_8),
        };
        final byte[] data = correlation == null ? new byte[0] : correlation;
        if (data.length > MAX_CORRELATION) {
            throw new IllegalArgumentException("correlation data too long [bytes=" + data.length + ", max="
                    + MAX_CORRELATION + ']');
        }
        int length = 2 + data.length + payload.length;
        for (final byte[] part : parts) {
            length += part.length + 1;
        }
        final byte[] bytes = new byte[length];
        int at = 0;
        for (final byte[] part : parts) {
            System.arraycopy(part, 0, bytes, at, part.length);
            at += part.length;
            bytes[at++] = SEPARATOR;
        }
        bytes[at++] = (byte) (data.length >>> Byte.SIZE);
        bytes[at++] = (byte) data.length;
        System.arraycopy(data, 0, bytes, at, data.length);
        at += data.length;
        System.arraycopy(payload, 0, bytes, at, payload.length);
        return bytes;
    }

    /**
     * A QoS 1 message to {@code topic}, content type {@value Names#JSON}, signed with {@code key} as {@code keyId} at
     * {@code tsMillis} with {@code nonce}; {@code responseTopic} and {@code correlation} null for none, both signed.
     *
     * @throws IllegalArgumentException when {@code correlation} is longer than 65,535 bytes
     */
    static Packet.Publish seal(final PrivateKey key, final String keyId, final String topic, final long tsMillis,
            final String nonce, final byte[] payload, final String responseTopic, final byte[] correlation) {
        final String ts = Long.toString(tsMillis);
        final byte[] signature;
        try {
            final Signature signer = Signature.getInstance(ALGORITHM);
            signer.initSign(key);
            signer.update(canonical(topic, ts, nonce, Names.JSON, responseTopic, correlation, payload));
            signature = signer.sign();
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException("cannot sign a control message [keyId=" + keyId + ']', e);
        }
        final List<Packet.UserProperty> properties = List.of(new Packet.UserProperty(KEY, keyId),
                new Packet.UserProperty(TS, ts), new Packet.UserProperty(NONCE, nonce),
                new Packet.UserProperty(SIG, Base64.getEncoder().encodeToString(signature)));
        return new Packet.Publish(topic, payload, 1, false, false, 0, responseTopic, correlation,
                new Packet.Meta(-1, -1, Names.JSON, properties));
    }

    /** Why {@code message} is refused, or empty when it is accepted, its nonce then remembered. */
    Optional<Refusal> check(final Packet.Publish message) {
        final String[] values = signatureProperties(message);
        if (values == null) {
            return Optional.of(Refusal.MALFORMED);
        }
        for (final String value : values) {
            if (value == null) {
                return Optional.of(Refusal.UNSIGNED);
            }
        }
        final String keyId = values[0];
        final String ts = values[1];
        final String nonce = values[2];
        final long tsMillis = parseTs(ts);
        final byte[] signature = parseSignature(values[3]);
        final String contentType = message.meta().contentType();
        // a Publish holds no response topic with 0x00 and no correlation data past 65,535 bytes: Packet refuses them
        if (tsMillis < 0 || !validNonce(nonce) || signature == null
                || contentType != null && contentType.indexOf((char) SEPARATOR) >= 0) {
            return Optional.of(Refusal.MALFORMED);
        }
        final PublicKey key = trusted.get(keyId);
        if (key == null) {
            return Optional.of(Refusal.UNKNOWN_KEY);
        }
        final long nowMillis = clock.millis();
        latestMillis = Math.max(latestMillis, nowMillis);
        final long windowMillis = WINDOW.toMillis();
        if (Math.abs(nowMillis - tsMillis) > windowMillis || latestMillis - tsMillis > windowMillis
                || tsMillis < startMillis) {
            return Optional.of(Refusal.STALE);
        }
        if (!verifies(key, canonical(message.topic(), ts, nonce, contentType, message.responseTopic(),
                message.correlation(), message.payload()), signature)) {
            return Optional.of(Refusal.BAD_SIGNATURE);
        }
        forget(nowMillis);
        if (seen.containsKey(nonce)) {
            return Optional.of(Refusal.REPLAYED);
        }
        if (seen.size() >= NONCES_MAX) {
            return Optional.of(Refusal.BUSY);
        }
        seen.put(nonce, tsMillis + windowMillis);
        return Optional.empty();
    }

    private static boolean isEd25519(final NamedParameterSpec params) {
        return NamedParameterSpec.ED25519.getName().equalsIgnoreCase(params.getName());
    }

    /**
     * Key id, ts, nonce and signature in that order, each null when absent; null when one of them repeats, since a
     * receiver that picked one of two values could check a different one than the signer meant.
     */
    private static String[] signatureProperties(final Packet.Publish message) {
        final String[] values = new String[SIGNATURE_PROPERTIES.size()];
        for (final Packet.UserProperty property : message.meta().userProperties()) {
            final int i = SIGNATURE_PROPERTIES.indexOf(property.name());
            if (i < 0) {
                continue;
            }
            if (values[i] != null) {
                return null;
            }
            values[i] = property.value();
        }
        return values;
    }

    /** The ts in epoch milliseconds, or -1 when it is not 1 to 18 decimal digits. */
    private static long parseTs(final String ts) {
        if (ts.isEmpty() || ts.length() > MAX_TS_DIGITS) {
            return -1;
        }
        for (int i = 0; i < ts.length(); i++) {
            if (ts.charAt(i) < '0' || ts.charAt(i) > '9') {
                return -1;
            }
        }
        return Long.parseLong(ts);
    }

    private static boolean validNonce(final String nonce) {
        if (nonce.length() != 2 * NONCE_BYTES) {
            return false;
        }
        for (int i = 0; i < nonce.length(); i++) {
            final char c = nonce.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                return false;
            }
        }
        return true;
    }

    /** The signature's bytes, or null when it is not base64. */
    private static byte[] parseSignature(final String base64) {
        try {
            return Base64.getDecoder().decode(base64);
        } catch (final IllegalArgumentException _) {
            return null;
        }
    }

    private static boolean verifies(final PublicKey key, final byte[] canonical, final byte[] signature) {
        try {
            final Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(key);
            verifier.update(canonical);
            return verifier.verify(signature);
        } catch (final SignatureException _) {
            // a signature of the wrong length or encoding: it does not verify
            return false;
        } catch (final NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("cannot verify with a trusted key [algorithm=" + key.getAlgorithm() + ']',
                    e);
        }
    }

    /** Forgets the nonces whose ts left the window, at most once per {@link #FORGET_EVERY}. */
    private void forget(final long nowMillis) {
        if (nowMillis - forgotMillis < FORGET_EVERY.toMillis() && nowMillis >= forgotMillis
                && seen.size() < NONCES_MAX) {
            return;
        }
        forgotMillis = nowMillis;
        // a nonce is forgotten against the latest time seen, which the staleness check also holds every ts to
        final long horizon = latestMillis;
        seen.values().removeIf(until -> until < horizon);
    }
}
