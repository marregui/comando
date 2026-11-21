package dev.marregui.comando.frontear.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// A PUBLISH kept as a record: what a link's queue log stores and reads back.
class PublishRecordTest {

    /// Every field and property of a QoS 1 message survives encode and decode, so a queued message leaves the log as it
    /// entered.
    @Test
    void aPublishRoundTripsWithItsProperties() throws Exception {
        final Packet.Meta meta = new Packet.Meta(1, 60, "application/json",
                List.of(new Packet.UserProperty("frontear-hops", "1")));
        final Packet.Publish p = new Packet.Publish("a/b", "{}".getBytes(StandardCharsets.UTF_8), 1, true, false, 42,
                "r", new byte[] {1, 2}, meta);
        assertThat(PublishRecord.decode(PublishRecord.encode(p))).isEqualTo(p);
    }

    /// Bytes that are not one whole PUBLISH are refused as malformed: another packet, a cut record, trailing bytes.
    @Test
    void anythingButOneWholePublishIsMalformed() throws Exception {
        final byte[] record = PublishRecord.encode(new Packet.Publish("t", new byte[] {7}, 0, false, false, 0, null,
                null));
        final byte[] trailing = Arrays.copyOf(record, record.length + 1);
        for (final byte[] bad : List.of(Codec.encode(new Packet.PingReq()), Arrays.copyOf(record, record.length - 1),
                trailing)) {
            assertThatThrownBy(() -> PublishRecord.decode(bad)).isInstanceOfSatisfying(MqttException.class,
                    e -> assertThat(e.reasonCode()).isEqualTo(Packet.MALFORMED));
        }
    }
}
