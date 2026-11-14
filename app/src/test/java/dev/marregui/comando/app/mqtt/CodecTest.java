package dev.marregui.comando.app.mqtt;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Every packet the client speaks, written and read back unchanged, against the specification's examples where it
/// gives one; and the packets a broker could send wrong, each refused with the reason code §4.13 asks for.
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

    /// The broker's mistakes in a packet's form are Malformed Packet: QoS 3, a QoS 1 packet id of 0, reserved header
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

    /// The broker's mistakes against the protocol are Protocol Error: a property the packet may not carry, a property
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
