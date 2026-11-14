package dev.marregui.comando.app.mqtt;

import java.io.IOException;

/**
 * An MQTT failure with its MQTT 5 reason code (§2.4): a packet that breaks the protocol, read or about to be written,
 * a broker's refusal of a connect, a publish or a subscription, or a lost connection. The code, not the message, says
 * what happened.
 */
public final class MqttException extends IOException {

    private static final long serialVersionUID = 1L;

    private final int reasonCode;

    /** {@code message}, for reason code {@code reasonCode}. */
    public MqttException(final int reasonCode, final String message) {
        super(message);
        this.reasonCode = reasonCode;
    }

    /** {@code message} and its {@code cause}, for reason code {@code reasonCode}. */
    public MqttException(final int reasonCode, final String message, final Throwable cause) {
        super(message, cause);
        this.reasonCode = reasonCode;
    }

    /** The MQTT 5 reason code, {@code 0x80} or above for a failure. */
    public int reasonCode() {
        return reasonCode;
    }
}
