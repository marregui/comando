package dev.marregui.comando.app.mqtt;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The MQTT 5 control packets this client speaks (§3), one record each: what {@link Codec} writes and reads. A record
 * refuses a value its packet cannot carry with an {@link IllegalArgumentException}; {@link Codec} turns that into a
 * {@link #MALFORMED} failure for a packet it reads.
 */
public sealed interface Packet {

    /** Success, normal disconnection, or QoS 0 granted (§2.4). */
    int SUCCESS = 0x00;
    /** The first reason code that is a failure (§2.4). */
    int FAILURE = 0x80;
    /** Unspecified error. */
    int UNSPECIFIED = 0x80;
    /** Malformed Packet. */
    int MALFORMED = 0x81;
    /** Protocol Error. */
    int PROTOCOL_ERROR = 0x82;
    /** Keep Alive timeout. */
    int KEEP_ALIVE_TIMEOUT = 0x8D;
    /** Receive Maximum exceeded. */
    int RECEIVE_MAXIMUM_EXCEEDED = 0x93;
    /** Topic Alias invalid. */
    int TOPIC_ALIAS_INVALID = 0x94;
    /** Packet too large. */
    int PACKET_TOO_LARGE = 0x95;
    /** Quota exceeded. */
    int QUOTA_EXCEEDED = 0x97;
    /** The largest packet the protocol allows, in bytes: a Maximum Packet Size absent means this (§3.1.2.11.4). */
    int PROTOCOL_MAXIMUM_PACKET_SIZE = 1 + 4 + Wire.MAX_VBI;
    /** A Receive Maximum absent means this (§3.1.2.11.3). */
    int DEFAULT_RECEIVE_MAXIMUM = 65_535;
    /** {@link ConnAck#serverKeepAliveSeconds} when the CONNACK carries no Server Keep Alive. */
    int NO_SERVER_KEEP_ALIVE = -1;

    private static void check(final boolean valid, final String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * CONNECT (§3.1). A session expiry of 0, a receive maximum of {@link #DEFAULT_RECEIVE_MAXIMUM} and a maximum packet
     * size of {@link #PROTOCOL_MAXIMUM_PACKET_SIZE} are the defaults, which go unwritten; so does a Topic Alias Maximum,
     * which is always 0: this client takes no topic alias. {@code will} is null for none.
     */
    record Connect(String clientId, boolean cleanStart, int keepAliveSeconds, long sessionExpirySeconds,
            int receiveMaximum, int maximumPacketSize, Publish will) implements Packet {

        /** Refuses a value CONNECT cannot carry. */
        public Connect {
            Objects.requireNonNull(clientId, "clientId");
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
     * {@link #NO_SERVER_KEEP_ALIVE}, and null strings.
     */
    record ConnAck(boolean sessionPresent, int reasonCode, int receiveMaximum, int maximumPacketSize,
            int topicAliasMaximum, int serverKeepAliveSeconds, String assignedClientId, String reasonString)
            implements Packet {

        /** Refuses a value CONNACK cannot carry. */
        public ConnAck {
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
     * PUBLISH (§3.3), QoS 0, 1 or 2 as read, with its Response Topic and Correlation Data, null when absent. A
     * {@code null} payload is the empty one. {@code packetId} is 0 at QoS 0, and at QoS 1 until the client gives the
     * message one. Two publishes are equal when every field is, the payload and correlation data compared by content.
     */
    record Publish(String topic, byte[] payload, int qos, boolean retain, boolean dup, int packetId,
            String responseTopic, byte[] correlation) implements Packet {

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
            payload = payload == null ? new byte[0] : payload;
        }

        /** This message with packet id {@code id}. */
        public Publish withPacketId(final int id) {
            return new Publish(topic, payload, qos, retain, dup, id, responseTopic, correlation);
        }

        @Override
        public boolean equals(final Object o) {
            return o instanceof final Publish p && topic.equals(p.topic) && Arrays.equals(payload, p.payload)
                    && qos == p.qos && retain == p.retain && dup == p.dup && packetId == p.packetId
                    && Objects.equals(responseTopic, p.responseTopic) && Arrays.equals(correlation, p.correlation);
        }

        @Override
        public int hashCode() {
            return Objects.hash(topic, Arrays.hashCode(payload), qos, retain, dup, packetId, responseTopic,
                    Arrays.hashCode(correlation));
        }

        @Override
        public String toString() {
            return "Publish[topic=" + topic + ", payloadBytes=" + payload.length + ", qos=" + qos + ", retain="
                    + retain + ", dup=" + dup + ", packetId=" + packetId + ", responseTopic=" + responseTopic
                    + ", correlationBytes=" + (correlation == null ? -1 : correlation.length) + ']';
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

    /** SUBSCRIBE (§3.8): one or more filters, each with its maximum QoS and no other option set. */
    record Subscribe(int packetId, List<Subscription> subscriptions) implements Packet {

        /** Refuses a value SUBSCRIBE cannot carry. */
        public Subscribe {
            check(packetId >= 1 && packetId <= 65_535, "packet id out of range [id=" + packetId + ']');
            subscriptions = List.copyOf(subscriptions);
            check(!subscriptions.isEmpty(), "a subscribe needs a filter");
        }
    }

    /** One filter of a {@link Subscribe}, with the maximum QoS asked for it. */
    record Subscription(String filter, int qos) {

        /** Refuses a filter that is not a Topic Filter, or a QoS out of range. */
        public Subscription {
            Objects.requireNonNull(filter, "filter");
            check(Topics.validFilter(filter), "not a topic filter [filter=" + filter + ']');
            check(qos >= 0 && qos <= 2, "QoS out of range [qos=" + qos + ']');
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

    /** DISCONNECT (§3.14); {@code reasonString} null when absent. */
    record Disconnect(int reasonCode, String reasonString) implements Packet {

        /** Refuses a reason code out of range. */
        public Disconnect {
            check(reasonCode >= 0 && reasonCode <= 0xFF, "reason code out of range [code=" + reasonCode + ']');
        }
    }
}
