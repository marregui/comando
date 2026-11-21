package dev.marregui.comando.frontear.protocol;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The MQTT 5 control packets the client and the broker speak (§3), one record each: what {@link Codec} writes and
 * reads. A record refuses a value its packet cannot carry with an {@link IllegalArgumentException}; {@link Codec}
 * turns that into a {@link #MALFORMED} failure for a packet it reads.
 */
public sealed interface Packet {

    /** Success, normal disconnection, or QoS 0 granted (§2.4). */
    int SUCCESS = 0x00;
    /** Disconnect with Will Message: the client asks the broker to publish its will (§3.14.2.1). */
    int DISCONNECT_WITH_WILL = 0x04;
    /** No matching subscribers: the message was accepted and nobody was subscribed (§3.4.2.1). */
    int NO_MATCHING_SUBSCRIBERS = 0x10;
    /** No subscription existed for a filter an UNSUBSCRIBE named (§3.11.3). */
    int NO_SUBSCRIPTION_EXISTED = 0x11;
    /** The first reason code that is a failure (§2.4). */
    int FAILURE = 0x80;
    /** Unspecified error. */
    int UNSPECIFIED = 0x80;
    /** Malformed Packet. */
    int MALFORMED = 0x81;
    /** Protocol Error. */
    int PROTOCOL_ERROR = 0x82;
    /** Implementation specific error: the receiver does not accept a valid packet. */
    int IMPLEMENTATION_SPECIFIC_ERROR = 0x83;
    /** Unsupported Protocol Version: a CONNECT of a protocol level other than 5. */
    int UNSUPPORTED_PROTOCOL_VERSION = 0x84;
    /** Client Identifier not valid. */
    int CLIENT_ID_NOT_VALID = 0x85;
    /** Bad User Name or Password: a CONNECT that carries either, which a broker without authentication refuses. */
    int BAD_USER_NAME_OR_PASSWORD = 0x86;
    /** Not authorized. */
    int NOT_AUTHORIZED = 0x87;
    /** Server unavailable (§3.2.2.2): a CONNECT the server cannot take now, though it runs. */
    int SERVER_UNAVAILABLE = 0x88;
    /** Server shutting down. */
    int SERVER_SHUTTING_DOWN = 0x8B;
    /** Bad authentication method: a CONNECT with an Authentication Method, which a broker without AUTH refuses. */
    int BAD_AUTHENTICATION_METHOD = 0x8C;
    /** Keep Alive timeout. */
    int KEEP_ALIVE_TIMEOUT = 0x8D;
    /** Session taken over: another connection connected with the same client id. */
    int SESSION_TAKEN_OVER = 0x8E;
    /** Topic Filter invalid: a filter the receiver does not accept, in a SUBACK (§3.9.3). */
    int TOPIC_FILTER_INVALID = 0x8F;
    /** Topic Name invalid: a name the receiver does not accept, in a PUBACK or DISCONNECT (§3.4.2.1). */
    int TOPIC_NAME_INVALID = 0x90;
    /** Packet Identifier in use. */
    int PACKET_ID_IN_USE = 0x91;
    /** Receive Maximum exceeded. */
    int RECEIVE_MAXIMUM_EXCEEDED = 0x93;
    /** Topic Alias invalid. */
    int TOPIC_ALIAS_INVALID = 0x94;
    /** Packet too large. */
    int PACKET_TOO_LARGE = 0x95;
    /** Quota exceeded. */
    int QUOTA_EXCEEDED = 0x97;
    /** Payload format invalid. */
    int PAYLOAD_FORMAT_INVALID = 0x99;
    /** Retain not supported: a retained PUBLISH to a server whose CONNACK said Retain Available 0. */
    int RETAIN_NOT_SUPPORTED = 0x9A;
    /** QoS not supported: a QoS above the Maximum QoS the CONNACK stated. */
    int QOS_NOT_SUPPORTED = 0x9B;
    /** Shared Subscriptions not supported. */
    int SHARED_SUBSCRIPTIONS_NOT_SUPPORTED = 0x9E;
    /** Subscription Identifiers not supported. */
    int SUBSCRIPTION_IDS_NOT_SUPPORTED = 0xA1;
    /** The largest packet the protocol allows, in bytes: a Maximum Packet Size absent means this (§3.1.2.11.4). */
    int PROTOCOL_MAXIMUM_PACKET_SIZE = 1 + 4 + Wire.MAX_VBI;
    /** A Receive Maximum absent means this (§3.1.2.11.3). */
    int DEFAULT_RECEIVE_MAXIMUM = 65_535;
    /** {@link ConnAck#serverKeepAliveSeconds} when the CONNACK carries no Server Keep Alive. */
    int NO_SERVER_KEEP_ALIVE = -1;

    /** A reason code as the logs show it: {@code 0x} and two upper-case hex digits, as the specification writes it. */
    static String hex(final int reasonCode) {
        return String.format("0x%02X", reasonCode);
    }

    private static void check(final boolean valid, final String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * CONNECT (§3.1). A session expiry of 0, a receive maximum of {@link #DEFAULT_RECEIVE_MAXIMUM} and a maximum
     * packet size of {@link #PROTOCOL_MAXIMUM_PACKET_SIZE} are the defaults, which go unwritten; so does a Topic
     * Alias Maximum, which is always 0: this client takes no topic alias. {@code will} is null for none;
     * {@code willDelaySeconds} is its Will Delay Interval (§3.1.3.2.2), 0 without a will.
     */
    record Connect(String clientId, boolean cleanStart, int keepAliveSeconds, long sessionExpirySeconds,
            int receiveMaximum, int maximumPacketSize, Publish will, long willDelaySeconds) implements Packet {

        /** A CONNECT whose will, if any, is published without delay. */
        public Connect(final String clientId, final boolean cleanStart, final int keepAliveSeconds,
                final long sessionExpirySeconds, final int receiveMaximum, final int maximumPacketSize,
                final Publish will) {
            this(clientId, cleanStart, keepAliveSeconds, sessionExpirySeconds, receiveMaximum, maximumPacketSize, will,
                    0);
        }

        /** Refuses a value CONNECT cannot carry. */
        public Connect {
            Objects.requireNonNull(clientId, "clientId");
            check(willDelaySeconds >= 0 && willDelaySeconds <= 0xFFFF_FFFFL, "will delay out of range [seconds="
                    + willDelaySeconds + ']');
            check(will != null || willDelaySeconds == 0, "a will delay without a will [seconds=" + willDelaySeconds
                    + ']');
            check(keepAliveSeconds >= 0 && keepAliveSeconds <= 65_535, "keep alive out of range [seconds="
                    + keepAliveSeconds + ']');
            check(sessionExpirySeconds >= 0 && sessionExpirySeconds <= 0xFFFF_FFFFL,
                    "session expiry out of range [seconds=" + sessionExpirySeconds + ']');
            check(receiveMaximum >= 1 && receiveMaximum <= 65_535, "receive maximum out of range [value="
                    + receiveMaximum + ']');
            check(maximumPacketSize >= 1 && maximumPacketSize <= PROTOCOL_MAXIMUM_PACKET_SIZE,
                    "maximum packet size out of range [bytes=" + maximumPacketSize + ']');
            check(will == null || !will.dup(), "a will cannot be a duplicate");
        }
    }

    /**
     * CONNACK (§3.2): the broker's answer and the limits it sets. A property absent holds its default:
     * {@link #DEFAULT_RECEIVE_MAXIMUM}, {@link #PROTOCOL_MAXIMUM_PACKET_SIZE}, a topic alias maximum of 0,
     * {@link #NO_SERVER_KEEP_ALIVE}, null strings, and {@link Capabilities#DEFAULT}.
     */
    record ConnAck(boolean sessionPresent, int reasonCode, int receiveMaximum, int maximumPacketSize,
            int topicAliasMaximum, int serverKeepAliveSeconds, String assignedClientId, String reasonString,
            Capabilities capabilities) implements Packet {

        /** A CONNACK that declines none of the features a broker may decline. */
        public ConnAck(final boolean sessionPresent, final int reasonCode, final int receiveMaximum,
                final int maximumPacketSize, final int topicAliasMaximum, final int serverKeepAliveSeconds,
                final String assignedClientId, final String reasonString) {
            this(sessionPresent, reasonCode, receiveMaximum, maximumPacketSize, topicAliasMaximum,
                    serverKeepAliveSeconds, assignedClientId, reasonString, Capabilities.DEFAULT);
        }

        /** Refuses a value CONNACK cannot carry. */
        public ConnAck {
            Objects.requireNonNull(capabilities, "capabilities");
            check(reasonCode >= 0 && reasonCode <= 0xFF, "reason code out of range [code=" + reasonCode + ']');
            check(receiveMaximum >= 1 && receiveMaximum <= 65_535, "receive maximum out of range [value="
                    + receiveMaximum + ']');
            check(maximumPacketSize >= 1 && maximumPacketSize <= PROTOCOL_MAXIMUM_PACKET_SIZE,
                    "maximum packet size out of range [bytes=" + maximumPacketSize + ']');
            check(topicAliasMaximum >= 0 && topicAliasMaximum <= 65_535, "topic alias maximum out of range [value="
                    + topicAliasMaximum + ']');
            check(serverKeepAliveSeconds >= NO_SERVER_KEEP_ALIVE && serverKeepAliveSeconds <= 65_535,
                    "server keep alive out of range [seconds=" + serverKeepAliveSeconds + ']');
            check(!sessionPresent || reasonCode == SUCCESS, "session present on a refused connect");
        }
    }

    /**
     * The features a CONNACK states the broker supports (§3.2.2.3.4 to .13): its Maximum QoS, 0 to 2, and whether
     * retained messages, wildcard subscriptions, Subscription Identifiers and Shared Subscriptions are available.
     */
    record Capabilities(int maximumQos, boolean retainAvailable, boolean wildcardAvailable,
            boolean subscriptionIdsAvailable, boolean sharedAvailable) {

        /** What a CONNACK without any of these properties states: everything available, QoS 2 included. */
        public static final Capabilities DEFAULT = new Capabilities(2, true, true, true, true);

        /** Refuses a Maximum QoS out of range. */
        public Capabilities {
            check(maximumQos >= 0 && maximumQos <= 2, "maximum QoS out of range [qos=" + maximumQos + ']');
        }
    }

    /**
     * PUBLISH (§3.3), QoS 0, 1 or 2 as read, with its Response Topic and Correlation Data, null when absent. A
     * {@code null} payload is the empty one. {@code packetId} is 0 at QoS 0, and at QoS 1 until the client gives the
     * message one. {@code meta} holds the other application properties a broker passes on unchanged (§3.3.2.3).
     * Two publishes are equal when every field is, the payload and correlation data compared by content. Nothing
     * writes into the payload or the correlation data once the record is built (the codec decodes each into an array
     * of its own), so every holder, a session's queue, a link, the watch, shares them by reference.
     */
    record Publish(String topic, byte[] payload, int qos, boolean retain, boolean dup, int packetId,
            String responseTopic, byte[] correlation, Meta meta) implements Packet {

        // the highest character a compact string stores in one byte; a string with any character above it stores
        // every character in two (java.lang.String's LATIN1 and UTF16 coders)
        private static final char LATIN1_MAX = 0xFF;
        // the object and array headers a held message carries besides its content, on a 64-bit JVM with compressed
        // references: this record (48 bytes), its topic's String and array (24 + 16), its payload array's header (16),
        // a Meta with its property list and array (32 + 16 + 16), and the record that queues or retains it (24)
        private static final long MESSAGE_OVERHEAD_BYTES = 192;
        // what one user property holds besides its characters: the UserProperty record (16), two Strings (24 each)
        // with their arrays' headers (16 each, less when the JDK shares an empty one), and its list slot (4); a
        // PUBLISH of 1,000,000 empty user properties decoded to 76 bytes of heap per property
        private static final long PROPERTY_OVERHEAD_BYTES = 80;

        /** A publish without payload format, message expiry, content type or user properties. */
        public Publish(final String topic, final byte[] payload, final int qos, final boolean retain,
                final boolean dup, final int packetId, final String responseTopic, final byte[] correlation) {
            this(topic, payload, qos, retain, dup, packetId, responseTopic, correlation, Meta.NONE);
        }

        /** Refuses a value PUBLISH cannot carry. */
        public Publish {
            Objects.requireNonNull(topic, "topic");
            check(Topics.validName(topic), "not a topic name [topic=" + topic + ']');
            check(qos >= 0 && qos <= 2, "QoS out of range [qos=" + qos + ']');
            check(!dup || qos > 0, "a QoS 0 message cannot be a duplicate");
            check(qos > 0 ? packetId >= 0 && packetId <= 65_535 : packetId == 0, "packet id out of range [id="
                    + packetId + ", qos=" + qos + ']');
            check(responseTopic == null || Topics.validName(responseTopic), "response topic not a topic name [topic="
                    + responseTopic + ']');
            check(correlation == null || correlation.length <= Wire.MAX_LENGTH,
                    "correlation data too long [bytes=" + (correlation == null ? 0 : correlation.length) + ']');
            Objects.requireNonNull(meta, "meta");
            payload = payload == null ? new byte[0] : payload;
        }

        /** This message with packet id {@code id}. */
        public Publish withPacketId(final int id) {
            return new Publish(topic, payload, qos, retain, dup, id, responseTopic, correlation, meta);
        }

        /** This message with {@code properties} in place of its own. */
        public Publish withMeta(final Meta properties) {
            return new Publish(topic, payload, qos, retain, dup, packetId, responseTopic, correlation, properties);
        }

        /**
         * What holding this message costs a byte bound: the bytes of its payload and correlation data, the characters
         * of its topic, response topic, content type and every user property's name and value, and a fixed overhead
         * for the message and for each user property, so a message of many empty properties weighs what it holds. A
         * string's characters count one byte each when all of them are Latin-1 and two each otherwise, as the JDK's
         * compact strings hold them.
         */
        public long weight() {
            long bytes = MESSAGE_OVERHEAD_BYTES + payload.length + charBytes(topic);
            bytes += charBytes(responseTopic);
            bytes += correlation == null ? 0 : correlation.length;
            bytes += charBytes(meta.contentType());
            for (final UserProperty p : meta.userProperties()) {
                bytes += PROPERTY_OVERHEAD_BYTES + charBytes(p.name()) + charBytes(p.value());
            }
            return bytes;
        }

        @Override
        public boolean equals(final Object o) {
            return o instanceof final Publish p && topic.equals(p.topic) && Arrays.equals(payload, p.payload)
                    && qos == p.qos && retain == p.retain && dup == p.dup && packetId == p.packetId
                    && Objects.equals(responseTopic, p.responseTopic) && Arrays.equals(correlation, p.correlation)
                    && meta.equals(p.meta);
        }

        @Override
        public int hashCode() {
            return Objects.hash(topic, Arrays.hashCode(payload), qos, retain, dup, packetId, responseTopic,
                    Arrays.hashCode(correlation), meta);
        }

        @Override
        public String toString() {
            return "Publish[topic=" + topic + ", payloadBytes=" + payload.length + ", qos=" + qos + ", retain="
                    + retain + ", dup=" + dup + ", packetId=" + packetId + ", responseTopic=" + responseTopic
                    + ", correlationBytes=" + (correlation == null ? -1 : correlation.length) + ", meta=" + meta + ']';
        }

        /** The bytes {@code s}'s characters hold with compact strings, 0 for null. */
        private static long charBytes(final String s) {
            if (s == null) {
                return 0;
            }
            final int length = s.length();
            for (int i = 0; i < length; i++) {
                if (s.charAt(i) > LATIN1_MAX) {
                    return 2L * length;
                }
            }
            return length;
        }
    }

    /**
     * A PUBLISH's (or a will's) Payload Format Indicator, Message Expiry Interval, Content Type and User Properties
     * (§3.3.2.3.2, .3, .9, .7): {@code payloadFormat} 0 or 1, -1 when absent; {@code messageExpirySeconds} -1 when
     * absent; {@code contentType} null when absent; the user properties in the order read, a name may repeat.
     */
    record Meta(int payloadFormat, long messageExpirySeconds, String contentType, List<UserProperty> userProperties) {

        /** No property set. */
        public static final Meta NONE = new Meta(-1, -1, null, List.of());

        /** Refuses a value PUBLISH cannot carry. */
        public Meta {
            check(payloadFormat >= -1 && payloadFormat <= 1, "payload format out of range [value=" + payloadFormat
                    + ']');
            check(messageExpirySeconds >= -1 && messageExpirySeconds <= 0xFFFF_FFFFL,
                    "message expiry out of range [seconds=" + messageExpirySeconds + ']');
            userProperties = List.copyOf(userProperties);
        }

        /** These properties with message expiry {@code seconds}, -1 for none. */
        public Meta withMessageExpiry(final long seconds) {
            return new Meta(payloadFormat, seconds, contentType, userProperties);
        }
    }

    /** One User Property (§3.3.2.3.7): a UTF-8 string pair. */
    record UserProperty(String name, String value) {

        /** Refuses a null name or value. */
        public UserProperty {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
        }
    }

    /** PUBACK (§3.4); {@code reasonString} null when absent. */
    record PubAck(int packetId, int reasonCode, String reasonString) implements Packet {

        /** Refuses a value PUBACK cannot carry. */
        public PubAck {
            check(packetId >= 1 && packetId <= 65_535, "packet id out of range [id=" + packetId + ']');
            check(reasonCode >= 0 && reasonCode <= 0xFF, "reason code out of range [code=" + reasonCode + ']');
        }
    }

    /** SUBSCRIBE (§3.8): one or more filters, each with its options. */
    record Subscribe(int packetId, List<Subscription> subscriptions) implements Packet {

        /** Refuses a value SUBSCRIBE cannot carry. */
        public Subscribe {
            check(packetId >= 1 && packetId <= 65_535, "packet id out of range [id=" + packetId + ']');
            subscriptions = List.copyOf(subscriptions);
            check(!subscriptions.isEmpty(), "a subscribe needs a filter");
        }
    }

    /**
     * One filter of a {@link Subscribe} with its Subscription Options (§3.8.3.1): the maximum QoS asked for it; No
     * Local, not to be sent the subscriber's own messages; Retain As Published, to keep a forwarded message's retain
     * flag; and Retain Handling, 0 to send the retained messages at every subscribe, 1 only when the subscription is
     * new, 2 never.
     */
    record Subscription(String filter, int qos, boolean noLocal, boolean retainAsPublished, int retainHandling) {

        /** A subscription with the default options: own messages sent, retain flag cleared, retained sent. */
        public Subscription(final String filter, final int qos) {
            this(filter, qos, false, false, 0);
        }

        /** Refuses a filter that is not a Topic Filter, or a QoS or Retain Handling out of range. */
        public Subscription {
            Objects.requireNonNull(filter, "filter");
            check(Topics.validFilter(filter), "not a topic filter [filter=" + filter + ']');
            check(qos >= 0 && qos <= 2, "QoS out of range [qos=" + qos + ']');
            check(retainHandling >= 0 && retainHandling <= 2, "retain handling out of range [value=" + retainHandling
                    + ']');
        }
    }

    /** UNSUBSCRIBE (§3.10): one or more filters, each as it was subscribed. */
    record Unsubscribe(int packetId, List<String> filters) implements Packet {

        /** Refuses a value UNSUBSCRIBE cannot carry. */
        public Unsubscribe {
            check(packetId >= 1 && packetId <= 65_535, "packet id out of range [id=" + packetId + ']');
            filters = List.copyOf(filters);
            check(!filters.isEmpty(), "an unsubscribe needs a filter");
            for (final String f : filters) {
                check(Topics.validFilter(f), "not a topic filter [filter=" + f + ']');
            }
        }
    }

    /** UNSUBACK (§3.11): a reason code per filter of the UNSUBSCRIBE, in its order; {@code reasonString} or null. */
    record UnsubAck(int packetId, List<Integer> reasonCodes, String reasonString) implements Packet {

        /** Refuses a value UNSUBACK cannot carry. */
        public UnsubAck {
            check(packetId >= 1 && packetId <= 65_535, "packet id out of range [id=" + packetId + ']');
            reasonCodes = List.copyOf(reasonCodes);
            check(!reasonCodes.isEmpty(), "an unsubscribe acknowledgement needs a reason code");
        }
    }

    /** SUBACK (§3.9): a reason code per filter of the SUBSCRIBE, in its order. */
    record SubAck(int packetId, List<Integer> reasonCodes, String reasonString) implements Packet {

        /** Refuses a value SUBACK cannot carry. */
        public SubAck {
            check(packetId >= 1 && packetId <= 65_535, "packet id out of range [id=" + packetId + ']');
            reasonCodes = List.copyOf(reasonCodes);
            check(!reasonCodes.isEmpty(), "a subscribe acknowledgement needs a reason code");
        }
    }

    /** PINGREQ (§3.12). */
    record PingReq() implements Packet {
    }

    /** PINGRESP (§3.13). */
    record PingResp() implements Packet {
    }

    /**
     * DISCONNECT (§3.14); {@code reasonString} null when absent, {@code sessionExpirySeconds} -1 when absent, else the
     * Session Expiry Interval that replaces the one the CONNECT gave (§3.14.2.2.2).
     */
    record Disconnect(int reasonCode, String reasonString, long sessionExpirySeconds) implements Packet {

        /** Refuses a reason code or a session expiry out of range. */
        public Disconnect {
            check(reasonCode >= 0 && reasonCode <= 0xFF, "reason code out of range [code=" + reasonCode + ']');
            check(sessionExpirySeconds >= -1 && sessionExpirySeconds <= 0xFFFF_FFFFL,
                    "session expiry out of range [seconds=" + sessionExpirySeconds + ']');
        }

        /** A DISCONNECT without a Session Expiry Interval. */
        public Disconnect(final int reasonCode, final String reasonString) {
            this(reasonCode, reasonString, -1);
        }
    }
}
