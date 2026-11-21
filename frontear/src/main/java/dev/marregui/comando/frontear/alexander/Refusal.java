package dev.marregui.comando.frontear.alexander;

import dev.marregui.comando.frontear.protocol.Packet;

/**
 * Why {@link ControlPlane} dropped a control message. A reply names it by {@link #code()}, never by its ordinal; the
 * PUBACK to the publishing client carries {@link #reasonCode()}.
 */
public enum Refusal {

    /** A signature property is missing: the key id, the ts, the nonce or the signature. */
    UNSIGNED("unsigned", Packet.NOT_AUTHORIZED),
    /** A signature property repeats or does not read: a ts not decimal, a nonce not 32 hex digits, a bad base64. */
    MALFORMED("malformed", Packet.NOT_AUTHORIZED),
    /** The key id names no trusted key. */
    UNKNOWN_KEY("unknown-key", Packet.NOT_AUTHORIZED),
    /** The ts is more than {@link Seal#WINDOW} from the receiver's clock, or earlier than the receiver's start. */
    STALE("stale", Packet.NOT_AUTHORIZED),
    /** The signature does not verify over the canonical bytes: topic, ts, nonce, content type or payload changed. */
    BAD_SIGNATURE("bad-signature", Packet.NOT_AUTHORIZED),
    /** The nonce was seen within the window. */
    REPLAYED("replayed", Packet.NOT_AUTHORIZED),
    /**
     * Too many messages or bytes wait to be handled, in all or from the message's origin, or too many nonces are
     * remembered.
     */
    BUSY("busy", Packet.QUOTA_EXCEEDED),
    /** The topic names no method. */
    UNKNOWN_METHOD("unknown-method", Refusal.TOPIC_NAME_INVALID),
    /** The body is not the method's JSON object, or its content type is not {@code application/json}. */
    BAD_REQUEST("bad-request", Refusal.PAYLOAD_FORMAT_INVALID),
    /** The mesh refused the change. */
    FAILED("failed", Refusal.IMPLEMENTATION_SPECIFIC);

    // MQTT 5 reason codes (§2.4) a PUBACK carries for a refusal, those Packet does not name
    private static final int IMPLEMENTATION_SPECIFIC = 0x83;
    private static final int TOPIC_NAME_INVALID = 0x90;
    private static final int PAYLOAD_FORMAT_INVALID = 0x99;

    private final String code;
    private final int reasonCode;

    Refusal(final String code, final int reasonCode) {
        this.code = code;
        this.reasonCode = reasonCode;
    }

    /** The name a reply carries in its {@code reason}. */
    public String code() {
        return code;
    }

    /** The MQTT 5 reason code of the PUBACK to the client that published the refused message. */
    int reasonCode() {
        return reasonCode;
    }
}
