package dev.marregui.comando.frontear.mqtt;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Packets of every type with every property the codec writes, strings of one to four UTF-8 bytes per character, and
/// sizes on both sides of each Variable Byte Integer boundary: of a packet's Remaining Length and of a property
/// section's length. Each is named for what it covers.
final class EncodeCorpus {

    private EncodeCorpus() {
    }

    /// The corpus, by name, in a fixed order.
    static Map<String, Packet> packets() {
        final Map<String, Packet> m = new LinkedHashMap<>();
        final Packet.Meta all = new Packet.Meta(1, 3_600, "application/json", List.of(
                new Packet.UserProperty("k", "v"), new Packet.UserProperty("k", "again"),
                new Packet.UserProperty("café", "€ 😀")));
        final Packet.Publish will = new Packet.Publish("will/é", bytes(5), 2, true, false, 0, "will/reply",
                bytes(3), all);
        m.put("connect-minimal", new Packet.Connect("c", true, 60, 0, Packet.DEFAULT_RECEIVE_MAXIMUM,
                Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, null));
        m.put("connect-empty-client-id", new Packet.Connect("", false, 0, 0, Packet.DEFAULT_RECEIVE_MAXIMUM,
                Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, null));
        m.put("connect-all-properties", new Packet.Connect("client-é", false, 65_535, 0xFFFF_FFFFL, 10,
                1_048_576, null));
        m.put("connect-will", new Packet.Connect("c", true, 30, 120, 100, 65_536, will));
        m.put("connect-will-delay", new Packet.Connect("c", false, 30, 0, Packet.DEFAULT_RECEIVE_MAXIMUM,
                Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, new Packet.Publish("w", null, 0, false, false, 0, null, null),
                0xFFFF_FFFFL));
        m.put("connack-default", new Packet.ConnAck(false, Packet.SUCCESS, Packet.DEFAULT_RECEIVE_MAXIMUM,
                Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, 0, Packet.NO_SERVER_KEEP_ALIVE, null, null));
        m.put("connack-all-properties", new Packet.ConnAck(true, Packet.SUCCESS, 1_024, 16_777_216, 10, 0,
                "assigned-€", "welcome", new Packet.Capabilities(1, false, false, false, false)));
        m.put("connack-refused", new Packet.ConnAck(false, 0x87, Packet.DEFAULT_RECEIVE_MAXIMUM,
                Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, 0, 65_535, null, "not authorized",
                new Packet.Capabilities(0, true, false, true, false)));
        m.put("publish-qos0", new Packet.Publish("a/b", bytes(16), 0, false, false, 0, null, null));
        m.put("publish-qos1-retain", new Packet.Publish("a/b", bytes(16), 1, true, false, 7, null, null));
        m.put("publish-qos2-dup", new Packet.Publish("a/b", bytes(16), 2, false, true, 65_535, null, null));
        m.put("publish-empty-payload", new Packet.Publish("a", null, 0, false, false, 0, null, null));
        m.put("publish-all-properties", new Packet.Publish("a/😀", bytes(100), 1, false, false, 1,
                "reply/to", bytes(20), all));
        m.put("publish-empty-correlation", new Packet.Publish("a", bytes(1), 0, false, false, 0, "r", new byte[0],
                new Packet.Meta(0, 0, "", List.of(new Packet.UserProperty("", "")))));
        // a QoS 0 PUBLISH to "t" with no property holds 4 bytes besides its payload: topic 3, section length 1
        for (final int remaining : new int[] {127, 128, 16_383, 16_384, 2_097_151, 2_097_152}) {
            m.put("publish-remaining-" + remaining, new Packet.Publish("t", bytes(remaining - 4), 0, false, false, 0,
                    null, null));
        }
        // a Content Type alone takes 3 bytes of its section besides its characters: id 1, length 2
        for (final int section : new int[] {127, 128, 16_383, 16_384}) {
            m.put("publish-section-" + section, new Packet.Publish("t", bytes(1), 0, false, false, 0, null, null,
                    new Packet.Meta(-1, -1, "x".repeat(section - 3), List.of())));
        }
        // a User Property named "n" takes 6 bytes of its section besides its value: id 1, two lengths of 2, the name
        for (final int section : new int[] {127, 128, 16_383, 16_384}) {
            m.put("publish-user-section-" + section, new Packet.Publish("t", bytes(1), 0, false, false, 0, null,
                    null, new Packet.Meta(-1, -1, null, List.of(new Packet.UserProperty("n",
                            "é".repeat((section - 6) / 2) + "v".repeat((section - 6) % 2))))));
        }
        m.put("publish-longest-string", new Packet.Publish("t", bytes(1), 0, false, false, 0, null, null,
                new Packet.Meta(-1, -1, "x".repeat(Wire.MAX_LENGTH), List.of())));
        m.put("puback-success", new Packet.PubAck(1, Packet.SUCCESS, null));
        m.put("puback-code", new Packet.PubAck(2, 0x10, null));
        m.put("puback-code-string", new Packet.PubAck(65_535, 0x80, "unspecified érror"));
        m.put("puback-success-string", new Packet.PubAck(3, Packet.SUCCESS, "ok"));
        m.put("subscribe", new Packet.Subscribe(9, List.of(new Packet.Subscription("a/+", 0),
                new Packet.Subscription("b/#", 1, true, false, 1), new Packet.Subscription("€/c", 2, false, true,
                        2))));
        m.put("suback", new Packet.SubAck(9, List.of(0, 1, 2, 0x80), null));
        m.put("suback-string", new Packet.SubAck(9, List.of(0x87), "denied"));
        m.put("unsubscribe", new Packet.Unsubscribe(10, List.of("a/+", "b/#", "€")));
        m.put("unsuback", new Packet.UnsubAck(10, List.of(0, 0x11), null));
        m.put("unsuback-string", new Packet.UnsubAck(10, List.of(0x80), "no"));
        m.put("pingreq", new Packet.PingReq());
        m.put("pingresp", new Packet.PingResp());
        m.put("disconnect-success", new Packet.Disconnect(Packet.SUCCESS, null));
        m.put("disconnect-code", new Packet.Disconnect(0x8E, null));
        m.put("disconnect-code-string", new Packet.Disconnect(0x8E, "taken over"));
        m.put("disconnect-session-expiry", new Packet.Disconnect(Packet.SUCCESS, null, 0));
        m.put("disconnect-session-expiry-string", new Packet.Disconnect(0x04, "bye", 0xFFFF_FFFFL));
        return m;
    }

    /// `n` bytes counting up from 0, so a misplaced copy shows in the digest.
    private static byte[] bytes(final int n) {
        final byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) i;
        }
        return b;
    }
}
