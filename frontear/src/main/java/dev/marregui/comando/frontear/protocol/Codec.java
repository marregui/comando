package dev.marregui.comando.frontear.protocol;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Writes and reads the {@link Packet}s as MQTT 5 puts them on the wire (§2, §3): a fixed header, its Remaining Length
 * as a Variable Byte Integer, then the packet's variable header and payload. Reading checks every rule a receiver
 * must: the fixed header's flags, each property allowed in its packet and not repeated where it cannot be, the
 * strings well-formed, the packet within the size the reader allows; a packet that breaks one fails with
 * {@link Packet#MALFORMED} or {@link Packet#PROTOCOL_ERROR}. A property neither side acts on is read, checked and
 * dropped; a property at its default is not written. What frontear declines fails with the reason code that says so:
 * a CONNECT with a user name or password ({@link Packet#BAD_USER_NAME_OR_PASSWORD}), an Authentication Method
 * ({@link Packet#BAD_AUTHENTICATION_METHOD}) or a protocol level other than 5
 * ({@link Packet#UNSUPPORTED_PROTOCOL_VERSION}); a PUBLISH with a Topic Alias ({@link Packet#TOPIC_ALIAS_INVALID});
 * a SUBSCRIBE with a Subscription Identifier ({@link Packet#SUBSCRIPTION_IDS_NOT_SUPPORTED}).
 */
public final class Codec {

    private static final int CONNECT = 1;
    private static final int CONNACK = 2;
    static final int PUBLISH = 3;
    private static final int PUBACK = 4;
    private static final int SUBSCRIBE = 8;
    private static final int SUBACK = 9;
    private static final int UNSUBSCRIBE = 10;
    private static final int UNSUBACK = 11;
    private static final int PINGREQ = 12;
    private static final int PINGRESP = 13;
    private static final int DISCONNECT = 14;

    private static final int PAYLOAD_FORMAT = 0x01;
    private static final int MESSAGE_EXPIRY = 0x02;
    private static final int CONTENT_TYPE = 0x03;
    private static final int RESPONSE_TOPIC = 0x08;
    private static final int CORRELATION_DATA = 0x09;
    private static final int SUBSCRIPTION_ID = 0x0B;
    private static final int SESSION_EXPIRY = 0x11;
    private static final int ASSIGNED_CLIENT_ID = 0x12;
    private static final int SERVER_KEEP_ALIVE = 0x13;
    private static final int AUTH_METHOD = 0x15;
    private static final int AUTH_DATA = 0x16;
    private static final int REQUEST_PROBLEM_INFO = 0x17;
    private static final int WILL_DELAY = 0x18;
    private static final int REQUEST_RESPONSE_INFO = 0x19;
    private static final int RESPONSE_INFO = 0x1A;
    private static final int SERVER_REFERENCE = 0x1C;
    private static final int REASON_STRING = 0x1F;
    private static final int RECEIVE_MAXIMUM = 0x21;
    private static final int TOPIC_ALIAS_MAXIMUM = 0x22;
    private static final int TOPIC_ALIAS = 0x23;
    private static final int MAXIMUM_QOS = 0x24;
    private static final int RETAIN_AVAILABLE = 0x25;
    private static final int USER_PROPERTY = 0x26;
    private static final int MAXIMUM_PACKET_SIZE = 0x27;
    private static final int WILDCARD_AVAILABLE = 0x28;
    private static final int SUBSCRIPTION_IDS_AVAILABLE = 0x29;
    private static final int SHARED_AVAILABLE = 0x2A;

    private static final int BYTE = 1;
    private static final int TWO = 2;
    private static final int FOUR = 3;
    private static final int VBI = 4;
    private static final int STRING = 5;
    private static final int BINARY = 6;
    private static final int PAIR = 7;
    /** Each property identifier's data type (§2.2.2.2), 0 for an identifier MQTT 5 does not define. */
    private static final int[] TYPE = new int[SHARED_AVAILABLE + 1];

    private static final long CONNECT_PROPERTIES = mask(SESSION_EXPIRY, AUTH_METHOD, AUTH_DATA, REQUEST_PROBLEM_INFO,
            REQUEST_RESPONSE_INFO, RECEIVE_MAXIMUM, TOPIC_ALIAS_MAXIMUM, USER_PROPERTY, MAXIMUM_PACKET_SIZE);
    private static final long WILL_PROPERTIES = mask(PAYLOAD_FORMAT, MESSAGE_EXPIRY, CONTENT_TYPE, RESPONSE_TOPIC,
            CORRELATION_DATA, WILL_DELAY, USER_PROPERTY);
    private static final long CONNACK_PROPERTIES = mask(SESSION_EXPIRY, ASSIGNED_CLIENT_ID, SERVER_KEEP_ALIVE,
            AUTH_METHOD, AUTH_DATA, RESPONSE_INFO, SERVER_REFERENCE, REASON_STRING, RECEIVE_MAXIMUM,
            TOPIC_ALIAS_MAXIMUM, MAXIMUM_QOS, RETAIN_AVAILABLE, USER_PROPERTY, MAXIMUM_PACKET_SIZE, WILDCARD_AVAILABLE,
            SUBSCRIPTION_IDS_AVAILABLE, SHARED_AVAILABLE);
    private static final long PUBLISH_PROPERTIES = mask(PAYLOAD_FORMAT, MESSAGE_EXPIRY, CONTENT_TYPE, RESPONSE_TOPIC,
            CORRELATION_DATA, SUBSCRIPTION_ID, TOPIC_ALIAS, USER_PROPERTY);
    private static final long ACK_PROPERTIES = mask(REASON_STRING, USER_PROPERTY);
    private static final long SUBSCRIBE_PROPERTIES = mask(SUBSCRIPTION_ID, USER_PROPERTY);
    private static final long UNSUBSCRIBE_PROPERTIES = mask(USER_PROPERTY);
    private static final long DISCONNECT_PROPERTIES = mask(SESSION_EXPIRY, SERVER_REFERENCE, REASON_STRING,
            USER_PROPERTY);
    /** Properties whose value is a byte holding 0 or 1: any other value is a protocol error. */
    private static final long FLAGS = mask(PAYLOAD_FORMAT, REQUEST_PROBLEM_INFO, REQUEST_RESPONSE_INFO, MAXIMUM_QOS,
            RETAIN_AVAILABLE, WILDCARD_AVAILABLE, SUBSCRIPTION_IDS_AVAILABLE, SHARED_AVAILABLE);
    /** Properties whose value 0 is a protocol error. */
    private static final long NON_ZERO = mask(SUBSCRIPTION_ID, RECEIVE_MAXIMUM, MAXIMUM_PACKET_SIZE, TOPIC_ALIAS);

    private static final byte[] PROTOCOL_NAME = {'M', 'Q', 'T', 'T'};
    private static final int PROTOCOL_LEVEL = 5;

    static {
        for (final int id : new int[] {PAYLOAD_FORMAT, REQUEST_PROBLEM_INFO, REQUEST_RESPONSE_INFO, MAXIMUM_QOS,
                RETAIN_AVAILABLE, WILDCARD_AVAILABLE, SUBSCRIPTION_IDS_AVAILABLE, SHARED_AVAILABLE}) {
            TYPE[id] = BYTE;
        }
        for (final int id : new int[] {SERVER_KEEP_ALIVE, RECEIVE_MAXIMUM, TOPIC_ALIAS_MAXIMUM, TOPIC_ALIAS}) {
            TYPE[id] = TWO;
        }
        for (final int id : new int[] {MESSAGE_EXPIRY, SESSION_EXPIRY, WILL_DELAY, MAXIMUM_PACKET_SIZE}) {
            TYPE[id] = FOUR;
        }
        TYPE[SUBSCRIPTION_ID] = VBI;
        for (final int id : new int[] {CONTENT_TYPE, RESPONSE_TOPIC, ASSIGNED_CLIENT_ID, AUTH_METHOD, RESPONSE_INFO,
                SERVER_REFERENCE, REASON_STRING}) {
            TYPE[id] = STRING;
        }
        TYPE[CORRELATION_DATA] = BINARY;
        TYPE[AUTH_DATA] = BINARY;
        TYPE[USER_PROPERTY] = PAIR;
    }

    private Codec() {
    }

    /**
     * {@code packet}'s bytes, fixed header included, in an array of their exact size: the body is counted first, then
     * written after the header. Fails when a string or the whole packet is too long.
     */
    public static byte[] encode(final Packet packet) throws MqttException {
        final Wire.Out counted = Wire.Out.counting();
        final int header = body(packet, counted);
        final int remaining = counted.size();
        if (remaining > Wire.MAX_VBI) {
            throw new MqttException(Packet.PACKET_TOO_LARGE, "packet too large to encode [remainingBytes="
                    + remaining + ", max=" + Wire.MAX_VBI + ']');
        }
        final int size = 1 + Wire.vbiSize(remaining) + remaining;
        final Wire.Out out = new Wire.Out(size).u8(header).vbi(remaining);
        body(packet, out);
        assert out.size() == size : "packet written in other than its counted size [counted=" + size + ", written="
                + out.size() + ']';
        return out.toByteArray();
    }

    /**
     * The packet whose fixed header's first byte, {@code header}, was read from {@code in}: its Remaining Length, then
     * its body. Fails with {@link Packet#PACKET_TOO_LARGE} before reading the body when the packet is larger than
     * {@code maximumPacketSize} bytes, and with an {@link EOFException} when the stream ends inside it.
     */
    public static Packet read(final InputStream in, final int header, final int maximumPacketSize) throws IOException {
        return read(in, header, maximumPacketSize, false);
    }

    /**
     * As {@link #read}, for a server reading what a client sent: a PUBLISH that carries a Subscription Identifier,
     * which only a server sends, fails with {@link Packet#PROTOCOL_ERROR} [MQTT-3.3.4-6].
     */
    public static Packet readFromClient(final InputStream in, final int header, final int maximumPacketSize)
            throws IOException {
        return read(in, header, maximumPacketSize, true);
    }

    /**
     * The packet of fixed-header byte {@code header} whose body, everything after the Remaining Length, is what
     * {@code in} holds, read by a server when {@code fromClient}. The bytes are read in place; what the packet keeps
     * (its payload, binary properties) is copied out of them.
     */
    static Packet decode(final int header, final Wire.In in, final boolean fromClient) throws MqttException {
        final int type = header >>> 4;
        final int flags = header & 0x0F;
        if (type != PUBLISH && flags != (type == SUBSCRIBE || type == UNSUBSCRIBE ? 0x02 : 0)) {
            throw new MqttException(Packet.MALFORMED, "fixed header flags reserved [type=" + type + ", flags=" + flags
                    + ']');
        }
        final Packet packet;
        try {
            packet = switch (type) {
                case CONNECT -> connect(in);
                case CONNACK -> connAck(in);
                case PUBLISH -> publish(flags, in, fromClient);
                case PUBACK -> pubAck(in);
                case SUBSCRIBE -> subscribe(in);
                case SUBACK -> subAck(in);
                case UNSUBSCRIBE -> unsubscribe(in);
                case UNSUBACK -> unsubAck(in);
                case PINGREQ -> new Packet.PingReq();
                case PINGRESP -> new Packet.PingResp();
                case DISCONNECT -> disconnect(in);
                default -> throw new MqttException(Packet.PROTOCOL_ERROR, "packet type not supported [type=" + type
                        + ']');
            };
        } catch (final IllegalArgumentException e) {
            throw new MqttException(Packet.MALFORMED, "packet field out of range [type=" + type + ']', e);
        }
        if (in.remaining() != 0) {
            throw new MqttException(Packet.MALFORMED, "bytes after the packet's end [type=" + type + ", extra="
                    + in.remaining() + ']');
        }
        return packet;
    }

    private static Packet read(final InputStream in, final int header, final int maximumPacketSize,
            final boolean fromClient) throws IOException {
        final int length = Wire.readVbi(in);
        final long size = 1L + Wire.vbiSize(length) + length;
        if (size > maximumPacketSize) {
            throw new MqttException(Packet.PACKET_TOO_LARGE, "packet larger than the maximum [bytes=" + size + ", max="
                    + maximumPacketSize + ']');
        }
        final byte[] body = in.readNBytes(length);
        if (body.length < length) {
            throw new EOFException("stream ended inside a packet [needed=" + length + ", read=" + body.length + ']');
        }
        return decode(header, new Wire.In(body, 0, body.length), fromClient);
    }

    /** {@code packet}'s fixed-header byte, with {@code body} given the packet's body: what follows the length. */
    private static int body(final Packet packet, final Wire.Out body) throws MqttException {
        return switch (packet) {
            case Packet.Connect c -> connect(c, body);
            case Packet.ConnAck a -> connAck(a, body);
            case Packet.Publish p -> publish(p, body);
            case Packet.PubAck a -> pubAck(a, body);
            case Packet.Subscribe s -> subscribe(s, body);
            case Packet.SubAck a -> subAck(a, body);
            case Packet.Unsubscribe u -> unsubscribe(u, body);
            case Packet.UnsubAck a -> unsubAck(a, body);
            case Packet.PingReq _ -> PINGREQ << 4;
            case Packet.PingResp _ -> PINGRESP << 4;
            case Packet.Disconnect d -> disconnect(d, body);
        };
    }

    private static int connect(final Packet.Connect c, final Wire.Out body) throws MqttException {
        final Packet.Publish will = c.will();
        int flags = c.cleanStart() ? 0x02 : 0;
        if (will != null) {
            flags |= 0x04 | will.qos() << 3 | (will.retain() ? 0x20 : 0);
        }
        body.u16(PROTOCOL_NAME.length).bytes(PROTOCOL_NAME).u8(PROTOCOL_LEVEL).u8(flags).u16(c.keepAliveSeconds());
        body.vbi(connectProperties(c, Wire.Out.counting()).size());
        connectProperties(c, body).string(c.clientId());
        if (will != null) {
            body.vbi(willProperties(c, Wire.Out.counting()).size());
            willProperties(c, body).string(will.topic()).binary(will.payload());
        }
        return CONNECT << 4;
    }

    private static int connAck(final Packet.ConnAck a, final Wire.Out body) throws MqttException {
        body.u8(a.sessionPresent() ? 1 : 0).u8(a.reasonCode());
        body.vbi(connAckProperties(a, Wire.Out.counting()).size());
        connAckProperties(a, body);
        return CONNACK << 4;
    }

    private static int publish(final Packet.Publish p, final Wire.Out body) throws MqttException {
        if (p.qos() > 0 && p.packetId() == 0) {
            throw new MqttException(Packet.PROTOCOL_ERROR, "a QoS 1 or 2 message needs a packet id [topic="
                    + p.topic() + ']');
        }
        body.string(p.topic());
        if (p.qos() > 0) {
            body.u16(p.packetId());
        }
        body.vbi(publishProperties(p, Wire.Out.counting()).size());
        publishProperties(p, body).bytes(p.payload());
        return PUBLISH << 4 | (p.dup() ? 0x08 : 0) | p.qos() << 1 | (p.retain() ? 0x01 : 0);
    }

    private static int pubAck(final Packet.PubAck a, final Wire.Out body) throws MqttException {
        body.u16(a.packetId());
        reasonAndString(a.reasonCode(), a.reasonString(), body);
        return PUBACK << 4;
    }

    private static int subscribe(final Packet.Subscribe s, final Wire.Out body) throws MqttException {
        body.u16(s.packetId()).vbi(0);
        for (final Packet.Subscription sub : s.subscriptions()) {
            body.string(sub.filter()).u8(sub.qos() | (sub.noLocal() ? 0x04 : 0) | (sub.retainAsPublished() ? 0x08 : 0)
                    | sub.retainHandling() << 4);
        }
        return SUBSCRIBE << 4 | 0x02;
    }

    private static int unsubscribe(final Packet.Unsubscribe u, final Wire.Out body) throws MqttException {
        body.u16(u.packetId()).vbi(0);
        for (final String filter : u.filters()) {
            body.string(filter);
        }
        return UNSUBSCRIBE << 4 | 0x02;
    }

    private static int subAck(final Packet.SubAck a, final Wire.Out body) throws MqttException {
        body.u16(a.packetId()).vbi(reasonString(a.reasonString(), Wire.Out.counting()).size());
        reasonString(a.reasonString(), body);
        for (final int code : a.reasonCodes()) {
            body.u8(code);
        }
        return SUBACK << 4;
    }

    private static int unsubAck(final Packet.UnsubAck a, final Wire.Out body) throws MqttException {
        body.u16(a.packetId()).vbi(reasonString(a.reasonString(), Wire.Out.counting()).size());
        reasonString(a.reasonString(), body);
        for (final int code : a.reasonCodes()) {
            body.u8(code);
        }
        return UNSUBACK << 4;
    }

    private static int disconnect(final Packet.Disconnect d, final Wire.Out body) throws MqttException {
        if (d.sessionExpirySeconds() < 0) {
            reasonAndString(d.reasonCode(), d.reasonString(), body);
            return DISCONNECT << 4;
        }
        body.u8(d.reasonCode()).vbi(disconnectProperties(d, Wire.Out.counting()).size());
        disconnectProperties(d, body);
        return DISCONNECT << 4;
    }

    /** PUBACK's and DISCONNECT's tail: nothing for success with no reason string (§3.4.2.1, §3.14.2.1). */
    private static void reasonAndString(final int reasonCode, final String reasonString, final Wire.Out body)
            throws MqttException {
        if (reasonCode == Packet.SUCCESS && reasonString == null) {
            return;
        }
        body.u8(reasonCode);
        if (reasonString != null) {
            body.vbi(reasonString(reasonString, Wire.Out.counting()).size());
            reasonString(reasonString, body);
        }
    }

    // a property section's writer writes its properties into the Out it is given and returns that Out: a packet runs
    // it over a counting Out for the section's length, then over its own to write the section after that length
    private static Wire.Out connectProperties(final Packet.Connect c, final Wire.Out props) {
        if (c.sessionExpirySeconds() != 0) {
            props.u8(SESSION_EXPIRY).u32(c.sessionExpirySeconds());
        }
        if (c.receiveMaximum() != Packet.DEFAULT_RECEIVE_MAXIMUM) {
            props.u8(RECEIVE_MAXIMUM).u16(c.receiveMaximum());
        }
        if (c.maximumPacketSize() != Packet.PROTOCOL_MAXIMUM_PACKET_SIZE) {
            props.u8(MAXIMUM_PACKET_SIZE).u32(c.maximumPacketSize());
        }
        return props;
    }

    private static Wire.Out willProperties(final Packet.Connect c, final Wire.Out props) throws MqttException {
        if (c.willDelaySeconds() != 0) {
            props.u8(WILL_DELAY).u32(c.willDelaySeconds());
        }
        return publishProperties(c.will(), props);
    }

    private static Wire.Out connAckProperties(final Packet.ConnAck a, final Wire.Out props) throws MqttException {
        if (a.receiveMaximum() != Packet.DEFAULT_RECEIVE_MAXIMUM) {
            props.u8(RECEIVE_MAXIMUM).u16(a.receiveMaximum());
        }
        if (a.maximumPacketSize() != Packet.PROTOCOL_MAXIMUM_PACKET_SIZE) {
            props.u8(MAXIMUM_PACKET_SIZE).u32(a.maximumPacketSize());
        }
        if (a.topicAliasMaximum() != 0) {
            props.u8(TOPIC_ALIAS_MAXIMUM).u16(a.topicAliasMaximum());
        }
        if (a.serverKeepAliveSeconds() != Packet.NO_SERVER_KEEP_ALIVE) {
            props.u8(SERVER_KEEP_ALIVE).u16(a.serverKeepAliveSeconds());
        }
        if (a.assignedClientId() != null) {
            props.u8(ASSIGNED_CLIENT_ID).string(a.assignedClientId());
        }
        final Packet.Capabilities can = a.capabilities();
        if (can.maximumQos() != Packet.Capabilities.DEFAULT.maximumQos()) {
            props.u8(MAXIMUM_QOS).u8(can.maximumQos());
        }
        flag(RETAIN_AVAILABLE, can.retainAvailable(), props);
        flag(WILDCARD_AVAILABLE, can.wildcardAvailable(), props);
        flag(SUBSCRIPTION_IDS_AVAILABLE, can.subscriptionIdsAvailable(), props);
        flag(SHARED_AVAILABLE, can.sharedAvailable(), props);
        return reasonString(a.reasonString(), props);
    }

    private static Wire.Out disconnectProperties(final Packet.Disconnect d, final Wire.Out props)
            throws MqttException {
        props.u8(SESSION_EXPIRY).u32(d.sessionExpirySeconds());
        return reasonString(d.reasonString(), props);
    }

    /** A byte property that is 1 when absent (§3.2.2.3.5, .11, .12, .13): written only when false. */
    private static void flag(final int id, final boolean value, final Wire.Out props) {
        if (!value) {
            props.u8(id).u8(0);
        }
    }

    private static Wire.Out reasonString(final String reasonString, final Wire.Out props) throws MqttException {
        if (reasonString != null) {
            props.u8(REASON_STRING).string(reasonString);
        }
        return props;
    }

    private static Wire.Out publishProperties(final Packet.Publish p, final Wire.Out props) throws MqttException {
        final Packet.Meta meta = p.meta();
        if (meta.payloadFormat() >= 0) {
            props.u8(PAYLOAD_FORMAT).u8(meta.payloadFormat());
        }
        if (meta.messageExpirySeconds() >= 0) {
            props.u8(MESSAGE_EXPIRY).u32(meta.messageExpirySeconds());
        }
        if (meta.contentType() != null) {
            props.u8(CONTENT_TYPE).string(meta.contentType());
        }
        for (final Packet.UserProperty u : meta.userProperties()) {
            props.u8(USER_PROPERTY).string(u.name()).string(u.value());
        }
        if (p.responseTopic() != null) {
            props.u8(RESPONSE_TOPIC).string(p.responseTopic());
        }
        if (p.correlation() != null) {
            props.u8(CORRELATION_DATA).binary(p.correlation());
        }
        return props;
    }

    private static Packet.Connect connect(final Wire.In in) throws MqttException {
        final int nameLength = in.u16();
        final byte[] name = in.bytes(nameLength);
        if (!Arrays.equals(name, PROTOCOL_NAME)) {
            throw new MqttException(Packet.PROTOCOL_ERROR, "protocol name not MQTT");
        }
        final int level = in.u8();
        if (level != PROTOCOL_LEVEL) {
            throw new MqttException(Packet.UNSUPPORTED_PROTOCOL_VERSION, "protocol level not 5 [level=" + level + ']');
        }
        final int flags = in.u8();
        final boolean willFlag = (flags & 0x04) != 0;
        final int willQos = flags >>> 3 & 0x03;
        final boolean willRetain = (flags & 0x20) != 0;
        if ((flags & 0x01) != 0 || willQos == 3 || !willFlag && (willQos != 0 || willRetain)) {
            throw new MqttException(Packet.MALFORMED, "connect flags invalid [flags=" + flags + ']');
        }
        if ((flags & 0xC0) != 0) {
            throw new MqttException(Packet.BAD_USER_NAME_OR_PASSWORD, "user name and password not supported [flags="
                    + flags + ']');
        }
        final int keepAlive = in.u16();
        final Props props = properties(in, CONNECT_PROPERTIES);
        if (props.has(AUTH_METHOD)) {
            throw new MqttException(Packet.BAD_AUTHENTICATION_METHOD, "authentication not supported [method="
                    + props.string(AUTH_METHOD) + ']');
        }
        final String clientId = in.string();
        Packet.Publish will = null;
        long willDelaySeconds = 0;
        if (willFlag) {
            final Props willProps = properties(in, WILL_PROPERTIES);
            final String topic = in.string();
            final byte[] payload = in.binary();
            will = new Packet.Publish(topic, payload, willQos, willRetain, false, 0, willProps.string(RESPONSE_TOPIC),
                    willProps.binary(CORRELATION_DATA), meta(willProps));
            willDelaySeconds = willProps.number(WILL_DELAY, 0);
        }
        return new Packet.Connect(clientId, (flags & 0x02) != 0, keepAlive, props.number(SESSION_EXPIRY, 0),
                (int) props.number(RECEIVE_MAXIMUM, Packet.DEFAULT_RECEIVE_MAXIMUM),
                maximumPacketSize(props), will, willDelaySeconds);
    }

    private static Packet.ConnAck connAck(final Wire.In in) throws MqttException {
        final int flags = in.u8();
        if ((flags & 0xFE) != 0) {
            throw new MqttException(Packet.MALFORMED, "connect acknowledge flags reserved [flags=" + flags + ']');
        }
        final int reasonCode = in.u8();
        if (!connectReasonCode(reasonCode)) {
            // the code parses, but the protocol allows only the table's (§1.2, §3.2.2.2)
            throw new MqttException(Packet.PROTOCOL_ERROR, "connect reason code not in the specification's table "
                    + "[reasonCode=" + reasonCode + ']');
        }
        final Props props = properties(in, CONNACK_PROPERTIES);
        return new Packet.ConnAck((flags & 0x01) != 0, reasonCode,
                (int) props.number(RECEIVE_MAXIMUM, Packet.DEFAULT_RECEIVE_MAXIMUM),
                maximumPacketSize(props),
                (int) props.number(TOPIC_ALIAS_MAXIMUM, 0),
                (int) props.number(SERVER_KEEP_ALIVE, Packet.NO_SERVER_KEEP_ALIVE),
                props.string(ASSIGNED_CLIENT_ID), props.string(REASON_STRING), new Packet.Capabilities(
                        (int) props.number(MAXIMUM_QOS, Packet.Capabilities.DEFAULT.maximumQos()),
                        props.number(RETAIN_AVAILABLE, 1) == 1, props.number(WILDCARD_AVAILABLE, 1) == 1,
                        props.number(SUBSCRIPTION_IDS_AVAILABLE, 1) == 1, props.number(SHARED_AVAILABLE, 1) == 1));
    }

    private static Packet.Publish publish(final int flags, final Wire.In in, final boolean fromClient)
            throws MqttException {
        final int qos = flags >>> 1 & 0x03;
        if (qos == 3) {
            throw new MqttException(Packet.MALFORMED, "publish QoS 3");
        }
        final String topic = in.string();
        final int packetId = qos > 0 ? in.u16() : 0;
        if (qos > 0 && packetId == 0) {
            throw new MqttException(Packet.MALFORMED, "publish packet id 0 [qos=" + qos + ']');
        }
        final Props props = properties(in, PUBLISH_PROPERTIES);
        if (fromClient && props.has(SUBSCRIPTION_ID)) {
            throw new MqttException(Packet.PROTOCOL_ERROR, "subscription identifier in a client publish [topic="
                    + topic + ']');
        }
        if (props.has(TOPIC_ALIAS)) {
            // the CONNECT this client sends allows no alias: its Topic Alias Maximum is absent, so 0 (§3.1.2.11.5)
            throw new MqttException(Packet.TOPIC_ALIAS_INVALID, "topic alias not allowed [topic=" + topic + ']');
        }
        return new Packet.Publish(topic, in.bytes(in.remaining()), qos, (flags & 0x01) != 0, (flags & 0x08) != 0,
                packetId, props.string(RESPONSE_TOPIC), props.binary(CORRELATION_DATA), meta(props));
    }

    /** The application properties of a PUBLISH or a will read into {@code props}. */
    private static Packet.Meta meta(final Props props) {
        return new Packet.Meta((int) props.number(PAYLOAD_FORMAT, -1), props.number(MESSAGE_EXPIRY, -1),
                props.string(CONTENT_TYPE), props.userProperties());
    }

    private static Packet.PubAck pubAck(final Wire.In in) throws MqttException {
        final int packetId = in.u16();
        if (in.remaining() == 0) {
            return new Packet.PubAck(packetId, Packet.SUCCESS, null);
        }
        final int reasonCode = in.u8();
        final String reasonString = in.remaining() == 0 ? null : properties(in, ACK_PROPERTIES).string(REASON_STRING);
        return new Packet.PubAck(packetId, reasonCode, reasonString);
    }

    private static Packet.Subscribe subscribe(final Wire.In in) throws MqttException {
        final int packetId = in.u16();
        if (properties(in, SUBSCRIBE_PROPERTIES).has(SUBSCRIPTION_ID)) {
            // the CONNACK of frontear's broker states Subscription Identifiers Available 0 (§3.8.2.1.2)
            throw new MqttException(Packet.SUBSCRIPTION_IDS_NOT_SUPPORTED, "subscription identifier not supported "
                    + "[packetId=" + packetId + ']');
        }
        final List<Packet.Subscription> subscriptions = new ArrayList<>();
        while (in.remaining() > 0) {
            final String filter = in.string();
            final int options = in.u8();
            if ((options & 0xC0) != 0 || (options & 0x03) == 3 || (options >>> 4 & 0x03) == 3) {
                throw new MqttException(Packet.MALFORMED, "subscription options invalid [options=" + options + ']');
            }
            subscriptions.add(new Packet.Subscription(filter, options & 0x03, (options & 0x04) != 0,
                    (options & 0x08) != 0, options >>> 4 & 0x03));
        }
        return new Packet.Subscribe(packetId, subscriptions);
    }

    private static Packet.Unsubscribe unsubscribe(final Wire.In in) throws MqttException {
        final int packetId = in.u16();
        properties(in, UNSUBSCRIBE_PROPERTIES);
        final List<String> filters = new ArrayList<>();
        while (in.remaining() > 0) {
            filters.add(in.string());
        }
        return new Packet.Unsubscribe(packetId, filters);
    }

    private static Packet.SubAck subAck(final Wire.In in) throws MqttException {
        final int packetId = in.u16();
        final String reasonString = properties(in, ACK_PROPERTIES).string(REASON_STRING);
        final List<Integer> codes = new ArrayList<>();
        while (in.remaining() > 0) {
            codes.add(in.u8());
        }
        return new Packet.SubAck(packetId, codes, reasonString);
    }

    private static Packet.UnsubAck unsubAck(final Wire.In in) throws MqttException {
        final int packetId = in.u16();
        final String reasonString = properties(in, ACK_PROPERTIES).string(REASON_STRING);
        final List<Integer> codes = new ArrayList<>();
        while (in.remaining() > 0) {
            codes.add(in.u8());
        }
        return new Packet.UnsubAck(packetId, codes, reasonString);
    }

    private static Packet.Disconnect disconnect(final Wire.In in) throws MqttException {
        if (in.remaining() == 0) {
            return new Packet.Disconnect(Packet.SUCCESS, null);
        }
        final int reasonCode = in.u8();
        if (in.remaining() == 0) {
            return new Packet.Disconnect(reasonCode, null);
        }
        final Props props = properties(in, DISCONNECT_PROPERTIES);
        return new Packet.Disconnect(reasonCode, props.string(REASON_STRING), props.number(SESSION_EXPIRY, -1));
    }

    /** A property section: its length, then properties of the ids {@code allowed} holds, each checked (§2.2.2). */
    private static Props properties(final Wire.In in, final long allowed) throws MqttException {
        final Wire.In section = in.slice(in.vbi());
        final Props props = new Props();
        while (section.remaining() > 0) {
            final int id = section.vbi();
            if (id >= TYPE.length || TYPE[id] == 0 || (allowed & 1L << id) == 0) {
                throw new MqttException(Packet.PROTOCOL_ERROR, "property not allowed here [id=" + id + ']');
            }
            final Object value = switch (TYPE[id]) {
                case BYTE -> (long) section.u8();
                case TWO -> (long) section.u16();
                case FOUR -> section.u32();
                case VBI -> (long) section.vbi();
                case STRING -> section.string();
                case BINARY -> section.binary();
                default -> List.of(section.string(), section.string());
            };
            if (value instanceof final Long n && ((FLAGS & 1L << id) != 0 && n > 1 || (NON_ZERO & 1L << id) != 0
                    && n == 0)) {
                throw new MqttException(Packet.PROTOCOL_ERROR, "property value invalid [id=" + id + ", value=" + n
                        + ']');
            }
            // a PUBLISH carries one Subscription Identifier per subscription it matched (§3.3.2.3.8)
            final boolean repeats = id == USER_PROPERTY || id == SUBSCRIPTION_ID && allowed == PUBLISH_PROPERTIES;
            if (!repeats && props.has(id)) {
                throw new MqttException(Packet.PROTOCOL_ERROR, "property repeated [id=" + id + ']');
            }
            if (id == USER_PROPERTY) {
                @SuppressWarnings("unchecked")
                final List<String> pair = (List<String>) value;
                props.users.add(new Packet.UserProperty(pair.get(0), pair.get(1)));
            }
            props.values[id] = value;
        }
        return props;
    }

    /** A Maximum Packet Size read, a four-byte integer, within what the protocol allows; absent, that maximum. */
    private static int maximumPacketSize(final Props props) {
        return (int) Math.min(props.number(MAXIMUM_PACKET_SIZE, Packet.PROTOCOL_MAXIMUM_PACKET_SIZE),
                Packet.PROTOCOL_MAXIMUM_PACKET_SIZE);
    }

    /** Whether {@code code} is one of the Connect Reason Codes (§3.2.2.2): Success, or one of the refusals. */
    private static boolean connectReasonCode(final int code) {
        return switch (code) {
            case 0x00, 0x80, 0x81, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89, 0x8A, 0x8C, 0x90, 0x95, 0x97, 0x99,
                    0x9A, 0x9B, 0x9C, 0x9D, 0x9F -> true;
            default -> false;
        };
    }

    private static long mask(final int... ids) {
        long m = 0;
        for (final int id : ids) {
            m |= 1L << id;
        }
        return m;
    }

    /** The properties read from one section, by identifier. */
    private static final class Props {

        private final Object[] values = new Object[TYPE.length];
        private final List<Packet.UserProperty> users = new ArrayList<>();

        List<Packet.UserProperty> userProperties() {
            return users;
        }

        boolean has(final int id) {
            return values[id] != null;
        }

        long number(final int id, final long absent) {
            return values[id] instanceof final Long n ? n : absent;
        }

        String string(final int id) {
            return values[id] instanceof final String s ? s : null;
        }

        byte[] binary(final int id) {
            return values[id] instanceof final byte[] b ? b : null;
        }
    }
}
