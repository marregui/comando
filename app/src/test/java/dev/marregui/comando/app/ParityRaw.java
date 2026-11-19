package dev.marregui.comando.app;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/// The MQTT 5 packets the parity scenarios send that frontear's codec does not write, built byte by byte as the
/// specification lays them out: a CONNECT with a User Name (§3.1.3.5) or an Authentication Method (§3.1.2.11.9), a
/// SUBSCRIBE with a Subscription Identifier (§3.8.2.1.2), a PUBLISH with a Topic Alias (§3.3.2.3.4), and the
/// PUBREL of a QoS 2 exchange (§3.6).
final class ParityRaw {

    private static final int CONNECT = 0x10;
    private static final int PUBLISH_QOS0 = 0x30;
    private static final int PUBREL = 0x62;
    private static final int SUBSCRIBE = 0x82;
    private static final int FLAG_USER_NAME = 0x80;
    private static final int FLAG_CLEAN_START = 0x02;
    private static final int AUTHENTICATION_METHOD = 0x15;
    private static final int SUBSCRIPTION_IDENTIFIER = 0x0B;
    private static final int TOPIC_ALIAS = 0x23;
    private static final int KEEP_ALIVE_SECONDS = 60;

    private ParityRaw() {
    }

    /// A clean-start CONNECT for `clientId` that carries the User Name `user` and no password.
    static byte[] connectWithUserName(final String clientId, final String user) {
        final Out payload = new Out().string(clientId).string(user);
        return connect(FLAG_USER_NAME | FLAG_CLEAN_START, new Out(), payload);
    }

    /// A clean-start CONNECT for `clientId` that asks for enhanced authentication by `method`.
    static byte[] connectWithAuthenticationMethod(final String clientId, final String method) {
        final Out properties = new Out().u8(AUTHENTICATION_METHOD).string(method);
        return connect(FLAG_CLEAN_START, properties, new Out().string(clientId));
    }

    /// A SUBSCRIBE of `filter` at `qos`, default options, carrying Subscription Identifier `identifier`.
    static byte[] subscribeWithIdentifier(final int packetId, final String filter, final int qos,
            final int identifier) {
        final Out properties = new Out().u8(SUBSCRIPTION_IDENTIFIER).vbi(identifier);
        final Out body = new Out().u16(packetId).vbi(properties.size()).bytes(properties).string(filter).u8(qos);
        return packet(SUBSCRIBE, body);
    }

    /// A QoS 0 PUBLISH of `payload` to `topic` that also sets Topic Alias `alias` for it.
    static byte[] publishWithTopicAlias(final String topic, final int alias, final byte[] payload) {
        final Out properties = new Out().u8(TOPIC_ALIAS).u16(alias);
        final Out body = new Out().string(topic).vbi(properties.size()).bytes(properties);
        body.raw(payload);
        return packet(PUBLISH_QOS0, body);
    }

    /// The PUBREL that answers a PUBREC of `packetId`, reason Success left unwritten (§3.6.2.1).
    static byte[] pubRel(final int packetId) {
        return packet(PUBREL, new Out().u16(packetId));
    }

    private static byte[] connect(final int flags, final Out properties, final Out payload) {
        final Out body = new Out().string("MQTT").u8(5).u8(flags).u16(KEEP_ALIVE_SECONDS).vbi(properties.size())
                .bytes(properties).bytes(payload);
        return packet(CONNECT, body);
    }

    private static byte[] packet(final int header, final Out body) {
        return new Out().u8(header).vbi(body.size()).bytes(body).toByteArray();
    }

    /// A growing packet in the wire's encodings (§1.5): bytes, two-byte and variable byte integers, UTF-8 strings.
    private static final class Out {

        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        Out u8(final int value) {
            bytes.write(value);
            return this;
        }

        Out u16(final int value) {
            bytes.write(value >>> 8);
            bytes.write(value & 0xFF);
            return this;
        }

        Out vbi(final int value) {
            int rest = value;
            do {
                final int digit = rest & 0x7F;
                rest >>>= 7;
                bytes.write(rest > 0 ? digit | 0x80 : digit);
            } while (rest > 0);
            return this;
        }

        Out string(final String text) {
            final byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
            u16(utf8.length);
            bytes.writeBytes(utf8);
            return this;
        }

        Out bytes(final Out other) {
            bytes.writeBytes(other.toByteArray());
            return this;
        }

        void raw(final byte[] data) {
            bytes.writeBytes(data);
        }

        int size() {
            return bytes.size();
        }

        byte[] toByteArray() {
            return bytes.toByteArray();
        }
    }
}
