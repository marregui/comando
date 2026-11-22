package dev.marregui.comando.frontear.protocol;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Every packet the client and the gateway speak, written and read back unchanged, against the specification's
/// examples where it gives one; the packets a peer could send wrong, each refused with the reason code §4.13 asks
/// for; and what frontear declines, each refused with the reason code that names it.
class CodecTest {

    private static final HexFormat HEX = HexFormat.ofDelimiter(" ").withUpperCase();

    /// The CONNECT of §3.1.2.12's example, keep alive 10 and session expiry 10, written as the example's bytes with
    /// the flags of a clean start and no user name or password (`02` where the example has `C2`), and an empty client
    /// id; every default property left out.
    @Test
    void connectIsWrittenAsTheSpecificationsExample() throws Exception {
        final Packet.Connect c = new Packet.Connect("", true, 10, 10, Packet.DEFAULT_RECEIVE_MAXIMUM,
                Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, null);
        assertThat(HEX.formatHex(Codec.encode(c))).isEqualTo("10 12 00 04 4D 51 54 54 05 02 00 0A 05 11 00 00 00 0A 00 00");
        assertThat(read(Codec.encode(c))).isEqualTo(c);
    }

    /// A CONNECT with a retained QoS 1 will carrying a response topic and correlation data, and a receive maximum and
    /// maximum packet size of its own, reads back equal; the will's flags are retain `20`, QoS 1 `08` and will `04`.
    @Test
    void connectWithAWillRoundTrips() throws Exception {
        final Packet.Publish will = new Packet.Publish("comando/v1/local/instance/a/presence", bytes("{}"), 1, true,
                false, 0, "r/t", bytes("c"));
        final Packet.Connect c = new Packet.Connect("comando-a", true, 60, 0, 10, 4_194_304, will);
        final byte[] b = Codec.encode(c);
        assertThat(b[9] & 0xFF).isEqualTo(0x20 | 0x08 | 0x04 | 0x02);
        assertThat(read(b)).isEqualTo(c);
    }

    /// A gateway passes a message's application properties on unchanged, so every one of them survives a round trip,
    /// a repeated user property name in its order, on a PUBLISH and on a will.
    @Test
    void publishAndWillPropertiesRoundTrip() throws Exception {
        final Packet.Meta meta = new Packet.Meta(1, 30, "application/json", List.of(
                new Packet.UserProperty("sig", "a"), new Packet.UserProperty("sig", "b"),
                new Packet.UserProperty("from", "zulu")));
        final Packet.Publish p = new Packet.Publish("a/b", bytes("{}"), 1, false, false, 7, "r/t", bytes("c"), meta);
        roundTrip(p);
        roundTrip(new Packet.Connect("c1", true, 60, 0, 10, 1_000, p.withPacketId(0)));
    }

    /// A CONNACK with every property this client reads, and one with none, read back equal; absent properties read as
    /// their defaults.
    @Test
    void connAckRoundTrips() throws Exception {
        roundTrip(new Packet.ConnAck(true, Packet.SUCCESS, 10, 1_024, 5, 30, "assigned", "welcome"));
        roundTrip(new Packet.ConnAck(false, 0x87, Packet.DEFAULT_RECEIVE_MAXIMUM, Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, 0,
                Packet.NO_SERVER_KEEP_ALIVE, null, null));
        assertThat(HEX.formatHex(Codec.encode(new Packet.ConnAck(false, Packet.SUCCESS, Packet.DEFAULT_RECEIVE_MAXIMUM,
                Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, 0, Packet.NO_SERVER_KEEP_ALIVE, null, null)))).isEqualTo("20 03 00 00 00");
    }

    /// The PUBLISH of §3.3.2.3's example, topic `a/b` and packet id 10 at QoS 1, is its bytes; publishes at QoS 0 and
    /// 1, retained or not, duplicate, with and without response topic and correlation data, read back equal.
    @Test
    void publishIsWrittenAsTheSpecificationsExampleAndRoundTrips() throws Exception {
        assertThat(HEX.formatHex(Codec.encode(new Packet.Publish("a/b", null, 1, false, false, 10, null, null))))
                .isEqualTo("32 08 00 03 61 2F 62 00 0A 00");
        roundTrip(new Packet.Publish("a/b", bytes("hello"), 0, false, false, 0, null, null));
        roundTrip(new Packet.Publish("a/b", bytes("hello"), 0, true, false, 0, null, null));
        roundTrip(new Packet.Publish("a/b", bytes("{\"text\":\"q\"}"), 1, false, false, 65_535, "reply/to",
                bytes("id-1")));
        roundTrip(new Packet.Publish("a/b", new byte[0], 1, true, true, 7, null, new byte[0]));
        final byte[] big = new byte[70_000];
        Arrays.fill(big, (byte) 'x');
        roundTrip(new Packet.Publish("a/b", big, 1, false, false, 1, null, null));
    }

    /// A successful PUBACK and a normal DISCONNECT leave out their reason code (§3.4.2.1, §3.14.2.1); with a code or a
    /// reason string they carry it, and every form reads back equal; PINGREQ and PINGRESP are two bytes.
    @Test
    void acknowledgementsPingsAndDisconnectsRoundTrip() throws Exception {
        assertThat(HEX.formatHex(Codec.encode(new Packet.PubAck(7, Packet.SUCCESS, null)))).isEqualTo("40 02 00 07");
        assertThat(HEX.formatHex(Codec.encode(new Packet.PubAck(7, 0x10, null)))).isEqualTo("40 03 00 07 10");
        roundTrip(new Packet.PubAck(7, Packet.SUCCESS, null));
        roundTrip(new Packet.PubAck(7, 0x87, null));
        roundTrip(new Packet.PubAck(7, 0x87, "not authorized"));
        assertThat(HEX.formatHex(Codec.encode(new Packet.Disconnect(Packet.SUCCESS, null)))).isEqualTo("E0 00");
        roundTrip(new Packet.Disconnect(Packet.SUCCESS, null));
        roundTrip(new Packet.Disconnect(0x8E, null));
        roundTrip(new Packet.Disconnect(0x8B, "shutting down"));
        assertThat(HEX.formatHex(Codec.encode(new Packet.PingReq()))).isEqualTo("C0 00");
        assertThat(HEX.formatHex(Codec.encode(new Packet.PingResp()))).isEqualTo("D0 00");
        roundTrip(new Packet.PingReq());
        roundTrip(new Packet.PingResp());
    }

    /// A DISCONNECT may carry a Session Expiry Interval (§3.14.2.2.2), written after its reason code and read back;
    /// one without the property reads as -1, no change.
    @Test
    void aDisconnectCarriesItsSessionExpiry() throws Exception {
        assertThat(HEX.formatHex(Codec.encode(new Packet.Disconnect(Packet.SUCCESS, null, 60))))
                .isEqualTo("E0 07 00 05 11 00 00 00 3C");
        roundTrip(new Packet.Disconnect(Packet.SUCCESS, null, 0));
        roundTrip(new Packet.Disconnect(0x04, "bye", 600));
        assertThat(read(HEX.parseHex("E0 00"))).isInstanceOfSatisfying(Packet.Disconnect.class,
                d -> assertThat(d.sessionExpirySeconds()).isEqualTo(-1));
    }

    /// [MQTT-3.3.4-6]: a Subscription Identifier in a PUBLISH is a Protocol Error when a client sent it, and read as
    /// it is when a gateway did.
    @Test
    void aSubscriptionIdentifierInAClientPublishIsAProtocolError() throws Exception {
        final byte[] publish = HEX.parseHex("32 09 00 01 74 00 01 02 0B 01 6D");
        assertThatThrownBy(() -> Codec.readFromClient(new ByteArrayInputStream(publish, 1, publish.length - 1),
                publish[0] & 0xFF, Packet.PROTOCOL_MAXIMUM_PACKET_SIZE)).isInstanceOfSatisfying(MqttException.class,
                        e -> assertThat(e.reasonCode()).isEqualTo(Packet.PROTOCOL_ERROR));
        assertThat(read(publish)).isInstanceOf(Packet.Publish.class);
    }

    /// SUBSCRIBE's fixed header carries its reserved flags `02` (§3.8.1); a subscribe and its acknowledgement, granted
    /// and refused, read back equal.
    @Test
    void subscribeAndItsAcknowledgementRoundTrip() throws Exception {
        final Packet.Subscribe s = new Packet.Subscribe(3, List.of(new Packet.Subscription("a/+", 1)));
        assertThat(HEX.formatHex(Codec.encode(s))).isEqualTo("82 09 00 03 00 00 03 61 2F 2B 01");
        roundTrip(s);
        roundTrip(new Packet.Subscribe(4, List.of(new Packet.Subscription("#", 0), new Packet.Subscription("a", 2))));
        roundTrip(new Packet.SubAck(3, List.of(1), null));
        roundTrip(new Packet.SubAck(3, List.of(0x87, 0), "partly"));
    }

    /// A will delay rides in the will's properties (§3.1.3.2.2): written only when not 0, read back equal, and absent
    /// when there is no will.
    @Test
    void aWillDelayRoundTrips() throws Exception {
        final Packet.Publish will = new Packet.Publish("w/t", bytes("gone"), 1, false, false, 0, null, null);
        final Packet.Connect delayed = new Packet.Connect("c1", false, 30, 600, 10, 1_000, will, 45);
        roundTrip(delayed);
        assertThat(Codec.encode(delayed)).hasSize(Codec.encode(new Packet.Connect("c1", false, 30, 600, 10, 1_000,
                will)).length + 5);
        assertThatThrownBy(() -> new Packet.Connect("c1", true, 30, 0, 10, 1_000, null, 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /// A CONNACK states what the gateway declines (§3.2.2.3.4, .11 to .13): Maximum QoS 1 and Subscription Identifiers
    /// and Shared Subscriptions not available are written as their bytes, a capability at its default is left out,
    /// and an absent capability reads as available.
    @Test
    void aConnAckStatesTheCapabilitiesItDeclines() throws Exception {
        final Packet.ConnAck declined = new Packet.ConnAck(false, Packet.SUCCESS, Packet.DEFAULT_RECEIVE_MAXIMUM,
                Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, 0, Packet.NO_SERVER_KEEP_ALIVE, null, null,
                new Packet.Capabilities(1, true, true, false, false));
        assertThat(HEX.formatHex(Codec.encode(declined))).isEqualTo("20 09 00 00 06 24 01 29 00 2A 00");
        roundTrip(declined);
        roundTrip(new Packet.ConnAck(true, Packet.SUCCESS, 10, 1_024, 0, 30, "a", null,
                new Packet.Capabilities(0, false, false, false, false)));
        assertThat(read(HEX.parseHex("20 03 00 00 00"))).isInstanceOfSatisfying(Packet.ConnAck.class,
                a -> assertThat(a.capabilities()).isEqualTo(Packet.Capabilities.DEFAULT));
    }

    /// A subscription's options byte (§3.8.3.1) carries QoS in bits 0-1, No Local in 2, Retain As Published in 3 and
    /// Retain Handling in 4-5: `2D` is QoS 1, No Local, Retain As Published and Retain Handling 2; every combination
    /// reads back equal.
    @Test
    void subscriptionOptionsAreWrittenAsTheirBitsAndRoundTrip() throws Exception {
        final Packet.Subscribe s = new Packet.Subscribe(3, List.of(new Packet.Subscription("a/+", 1, true, true, 2)));
        assertThat(HEX.formatHex(Codec.encode(s))).isEqualTo("82 09 00 03 00 00 03 61 2F 2B 2D");
        roundTrip(s);
        for (int handling = 0; handling <= 2; handling++) {
            roundTrip(new Packet.Subscribe(9, List.of(new Packet.Subscription("x", 0, false, true, handling),
                    new Packet.Subscription("y/#", 2, true, false, handling))));
        }
        assertThatThrownBy(() -> new Packet.Subscription("a", 1, false, false, 3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /// UNSUBSCRIBE's fixed header carries its reserved flags `02` (§3.10.1), its acknowledgement one reason code per
    /// filter (§3.11.3); both read back equal, an UNSUBSCRIBE without its flags is malformed, and one without a filter
    /// cannot be built (§3.10.3).
    @Test
    void unsubscribeAndItsAcknowledgementRoundTrip() throws Exception {
        final Packet.Unsubscribe u = new Packet.Unsubscribe(5, List.of("a/+"));
        assertThat(HEX.formatHex(Codec.encode(u))).isEqualTo("A2 08 00 05 00 00 03 61 2F 2B");
        roundTrip(u);
        roundTrip(new Packet.Unsubscribe(6, List.of("#", "a/b", "$frontear/presence/+")));
        final Packet.UnsubAck a = new Packet.UnsubAck(5, List.of(Packet.SUCCESS, Packet.NO_SUBSCRIPTION_EXISTED), null);
        assertThat(HEX.formatHex(Codec.encode(a))).isEqualTo("B0 05 00 05 00 00 11");
        roundTrip(a);
        roundTrip(new Packet.UnsubAck(6, List.of(0x87), "not yours"));
        assertThatThrownBy(() -> read(HEX.parseHex("A0 08 00 05 00 00 03 61 2F 2B")))
                .isInstanceOfSatisfying(MqttException.class,
                        e -> assertThat(e.reasonCode()).isEqualTo(Packet.MALFORMED));
        assertThatThrownBy(() -> new Packet.Unsubscribe(5, List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    /// What frontear declines is refused with the code that names it: a CONNECT with a user name and password flag
    /// (0x86), with an Authentication Method (0x8C) or at protocol level 4 (0x84), and a SUBSCRIBE carrying a
    /// Subscription Identifier (0xA1).
    @Test
    void aDeclinedFeatureIsRefusedWithItsReasonCode() {
        final String[][] cases = {
            {"10 12 00 04 4D 51 54 54 05 C2 00 0A 05 11 00 00 00 0A 00 00", "86"}, // user name and password
            {"10 15 00 04 4D 51 54 54 05 02 00 0A 08 15 00 05 53 43 52 41 4D 00 00", "8C"}, // method SCRAM
            {"10 0D 00 04 4D 51 54 54 04 02 00 0A 00 00 00", "84"}, // protocol level 4
            {"82 0B 00 03 02 0B 01 00 03 61 2F 2B 01", "A1"}, // Subscription Identifier 1
        };
        for (final String[] c : cases) {
            assertThatThrownBy(() -> read(HEX.parseHex(c[0]))).as(c[0]).isInstanceOfSatisfying(MqttException.class,
                    e -> assertThat(e.reasonCode()).isEqualTo(Integer.parseInt(c[1], 16)));
        }
        assertThat(Packet.BAD_USER_NAME_OR_PASSWORD).isEqualTo(0x86);
        assertThat(Packet.BAD_AUTHENTICATION_METHOD).isEqualTo(0x8C);
        assertThat(Packet.UNSUPPORTED_PROTOCOL_VERSION).isEqualTo(0x84);
        assertThat(Packet.SUBSCRIPTION_IDS_NOT_SUPPORTED).isEqualTo(0xA1);
    }

    /// The gateway's mistakes in a packet's form are Malformed Packet: QoS 3, a QoS 1 packet id of 0, reserved header
    /// flags set, a wildcard in a topic name, a string longer than its packet, and bytes after a packet's end.
    @Test
    void aPacketOfTheWrongFormIsMalformed() {
        for (final String bad : new String[] {
            "36 06 00 01 61 00 01 00", // QoS 3
            "32 06 00 01 61 00 00 00", // QoS 1, packet id 0
            "41 02 00 07", // PUBACK with reserved flags
            "80 06 00 03 00 00 01 61", // SUBSCRIBE without its reserved flags
            "30 06 00 03 61 2F 2B 00", // a wildcard in the topic name
            "30 02 00 05", // a topic longer than the packet
            "D0 01 00", // a byte after PINGRESP's end
            "20 03 02 00 00", // CONNACK with a reserved flag
        }) {
            assertThatThrownBy(() -> read(HEX.parseHex(bad))).as(bad).isInstanceOfSatisfying(MqttException.class,
                    e -> assertThat(e.reasonCode()).isEqualTo(Packet.MALFORMED));
        }
    }

    /// The gateway's mistakes against the protocol are Protocol Error: a property the packet may not carry, a property
    /// twice, a flag property above 1, and a packet type this client does not speak; a Topic Alias, which this client
    /// never allows, is Topic Alias invalid.
    @Test
    void aPacketAgainstTheProtocolIsAProtocolError() {
        for (final String bad : new String[] {
            "30 0A 00 01 61 05 11 00 00 00 0A 00", // PUBLISH with a Session Expiry
            "30 0C 00 01 61 08 08 00 01 62 08 00 01 63", // two Response Topics
            "30 06 00 01 61 02 01 02", // Payload Format Indicator 2
            "F0 00", // AUTH
            "00 00", // reserved type 0
        }) {
            assertThatThrownBy(() -> read(HEX.parseHex(bad))).as(bad).isInstanceOfSatisfying(MqttException.class,
                    e -> assertThat(e.reasonCode()).isEqualTo(Packet.PROTOCOL_ERROR));
        }
        assertThatThrownBy(() -> read(HEX.parseHex("30 07 00 01 61 03 23 00 01")))
                .isInstanceOfSatisfying(MqttException.class,
                        e -> assertThat(e.reasonCode()).isEqualTo(Packet.TOPIC_ALIAS_INVALID));
    }

    /// A CONNACK reads with each of the 22 Connect Reason Codes of §3.2.2.2; any other of the 256 values a byte holds,
    /// 0x01 to 0x7F included, is a Protocol Error rather than a success or a refusal.
    @Test
    void aConnectReasonCodeOutsideTheTableIsAProtocolError() throws Exception {
        final List<Integer> table = List.of(0x00, 0x80, 0x81, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89, 0x8A,
                0x8C, 0x90, 0x95, 0x97, 0x99, 0x9A, 0x9B, 0x9C, 0x9D, 0x9F);
        for (int code = 0; code <= 0xFF; code++) {
            final int sent = code;
            final byte[] connAck = {0x20, 0x03, 0x00, (byte) sent, 0x00};
            if (table.contains(sent)) {
                assertThat(read(connAck)).isInstanceOfSatisfying(Packet.ConnAck.class,
                        a -> assertThat(a.reasonCode()).isEqualTo(sent));
            } else {
                assertThatThrownBy(() -> read(connAck)).as("code %d", sent)
                        .isInstanceOfSatisfying(MqttException.class,
                                e -> assertThat(e.reasonCode()).isEqualTo(Packet.PROTOCOL_ERROR));
            }
        }
    }

    /// A packet larger than the reader's maximum is refused from its length alone, before its body is read; a stream
    /// that ends inside a packet is an end of stream, not a packet.
    @Test
    void aPacketTooLargeIsRefusedAndATruncatedOneIsAnEndOfStream() {
        final byte[] header = HEX.parseHex("80 80 01"); // remaining length 16,384, with no body behind it
        assertThatThrownBy(() -> Codec.read(new ByteArrayInputStream(header), 0x30, 1_024))
                .isInstanceOfSatisfying(MqttException.class,
                        e -> assertThat(e.reasonCode()).isEqualTo(Packet.PACKET_TOO_LARGE));
        assertThatThrownBy(() -> read(HEX.parseHex("32 08 00 03 61")))
                .isInstanceOf(EOFException.class);
    }

    /// What a record cannot carry is refused when it is built: a topic name with a wildcard, an invalid filter, QoS 3,
    /// a duplicate at QoS 0.
    @Test
    void aPacketThatCannotBeWrittenIsRefusedWhenBuilt() {
        assertThatThrownBy(() -> new Packet.Publish("a/#", null, 0, false, false, 0, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Packet.Publish("a", null, 0, false, true, 0, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Packet.Publish("a", null, 3, false, false, 1, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Packet.Subscription("a/b#", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Codec.encode(new Packet.Publish("a", null, 1, false, false, 0, null, null)))
                .isInstanceOfSatisfying(MqttException.class,
                        e -> assertThat(e.reasonCode()).isEqualTo(Packet.PROTOCOL_ERROR));
    }

    /// A PUBLISH whose property section is empty reads back with the one shared empty set of properties, not a set of
    /// its own; one with properties reads back with them.
    @Test
    void aPublishWithoutPropertiesReadsBackWithTheSharedEmptyMeta() throws Exception {
        final Packet.Publish bare = new Packet.Publish("a/b", bytes("x"), 1, false, false, 3, null, null);
        assertThat(((Packet.Publish) read(Codec.encode(bare))).meta()).isSameAs(Packet.Meta.NONE);
        final Packet.Publish dressed = new Packet.Publish("a/b", bytes("x"), 1, false, false, 3, "r/s", bytes("c"),
                new Packet.Meta(1, 60, "text/plain", List.of(new Packet.UserProperty("k", "v"))));
        assertThat(read(Codec.encode(dressed))).isEqualTo(dressed);
    }

    private static void roundTrip(final Packet p) throws Exception {
        assertThat(read(Codec.encode(p))).isEqualTo(p);
    }

    private static Packet read(final byte[] packet) throws Exception {
        return Codec.read(new ByteArrayInputStream(packet, 1, packet.length - 1), packet[0] & 0xFF,
                Packet.PROTOCOL_MAXIMUM_PACKET_SIZE);
    }

    private static byte[] bytes(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
