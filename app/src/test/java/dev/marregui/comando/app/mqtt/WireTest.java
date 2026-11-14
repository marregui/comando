package dev.marregui.comando.app.mqtt;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// MQTT 5's data representations (§1.5) against the specification's own examples, and every malformed form refused
/// with reason code Malformed Packet.
class WireTest {

    private static final HexFormat HEX = HexFormat.ofDelimiter(" ").withUpperCase();

    /// Every boundary of the Variable Byte Integer table (§1.5.5, Table 1-1) encodes to the bytes the table gives, in
    /// as many bytes as `vbiSize` says, and reads back to its value.
    @Test
    void variableByteIntegersMatchTheSpecificationsTable() throws Exception {
        final Object[][] table = {
            {0, "00"}, {127, "7F"}, {128, "80 01"}, {16_383, "FF 7F"}, {16_384, "80 80 01"}, {2_097_151, "FF FF 7F"},
            {2_097_152, "80 80 80 01"}, {268_435_455, "FF FF FF 7F"},
        };
        for (final Object[] row : table) {
            final int value = (int) row[0];
            final byte[] bytes = new Wire.Out().vbi(value).toByteArray();
            assertThat(HEX.formatHex(bytes)).as("value %d", value).isEqualTo(row[1]);
            assertThat(Wire.vbiSize(value)).isEqualTo(bytes.length);
            assertThat(Wire.readVbi(new ByteArrayInputStream(bytes))).isEqualTo(value);
            assertThat(new Wire.In(bytes, bytes.length).vbi()).isEqualTo(value);
        }
    }

    /// A fifth byte, a longer form than the value needs, and a stream that ends inside the integer are each refused.
    @Test
    void aVariableByteIntegerTooLongOrNotMinimalIsMalformed() {
        assertThatThrownBy(() -> Wire.readVbi(new ByteArrayInputStream(HEX.parseHex("FF FF FF FF 01"))))
                .isInstanceOfSatisfying(MqttException.class, e -> assertThat(e.reasonCode()).isEqualTo(Packet.MALFORMED));
        assertThatThrownBy(() -> Wire.readVbi(new ByteArrayInputStream(HEX.parseHex("80 00"))))
                .isInstanceOfSatisfying(MqttException.class, e -> assertThat(e.reasonCode()).isEqualTo(Packet.MALFORMED));
        assertThatThrownBy(() -> new Wire.In(HEX.parseHex("80 80 00"), 3).vbi())
                .isInstanceOfSatisfying(MqttException.class, e -> assertThat(e.reasonCode()).isEqualTo(Packet.MALFORMED));
        assertThatThrownBy(() -> Wire.readVbi(new ByteArrayInputStream(HEX.parseHex("80 80"))))
                .isInstanceOf(EOFException.class);
    }

    /// The specification's UTF-8 example (§1.5.4, Figure 1-1): "A" then U+2A6D4 is the length 5 then
    /// `41 F0 AA 9B 94`, and reads back the same.
    @Test
    void theSpecificationsUtf8ExampleEncodesAndDecodes() throws Exception {
        final String s = "A\uD869\uDED4";
        final byte[] encoded = new Wire.Out().string(Wire.utf8(s)).toByteArray();
        assertThat(HEX.formatHex(encoded)).isEqualTo("00 05 41 F0 AA 9B 94");
        assertThat(new Wire.In(encoded, encoded.length).string()).isEqualTo(s);
    }

    /// A string with U+0000, a lone surrogate, or more than 65,535 bytes is refused on the way out; bytes that are not
    /// UTF-8 (a lone continuation byte, an overlong encoding, an encoded surrogate) and an encoded U+0000 on the way in.
    @Test
    void aStringThatBreaksTheUtf8RulesIsMalformed() throws Exception {
        for (final String bad : new String[] {"a\u0000b", "\uD800", "x".repeat(65_536)}) {
            assertThatThrownBy(() -> Wire.utf8(bad)).isInstanceOfSatisfying(MqttException.class,
                    e -> assertThat(e.reasonCode()).isEqualTo(Packet.MALFORMED));
        }
        assertThat(Wire.utf8("x".repeat(65_535))).hasSize(65_535);
        for (final String bad : new String[] {"00 01 80", "00 02 C0 80", "00 03 ED A0 80", "00 01 00"}) {
            final byte[] b = HEX.parseHex(bad);
            assertThatThrownBy(() -> new Wire.In(b, b.length).string()).as(bad).isInstanceOfSatisfying(
                    MqttException.class, e -> assertThat(e.reasonCode()).isEqualTo(Packet.MALFORMED));
        }
    }

    /// Integers are big-endian (§1.5.2, §1.5.3), a four-byte one unsigned; reading past the end is malformed.
    @Test
    void integersAreBigEndianAndReadingPastTheEndIsMalformed() throws Exception {
        final byte[] b = new Wire.Out().u8(0xAB).u16(0x1234).u32(0xFFFF_FFFEL).toByteArray();
        assertThat(HEX.formatHex(b)).isEqualTo("AB 12 34 FF FF FF FE");
        final Wire.In in = new Wire.In(b, b.length);
        assertThat(in.u8()).isEqualTo(0xAB);
        assertThat(in.u16()).isEqualTo(0x1234);
        assertThat(in.u32()).isEqualTo(0xFFFF_FFFEL);
        assertThat(in.remaining()).isZero();
        assertThatThrownBy(in::u8).isInstanceOfSatisfying(MqttException.class,
                e -> assertThat(e.reasonCode()).isEqualTo(Packet.MALFORMED));
    }
}
