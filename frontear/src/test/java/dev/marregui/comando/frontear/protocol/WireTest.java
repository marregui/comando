package dev.marregui.comando.frontear.protocol;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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
            assertThat(new Wire.In(bytes, 0, bytes.length).vbi()).isEqualTo(value);
        }
    }

    /// A fifth byte, a longer form than the value needs, and a stream that ends inside the integer are each refused.
    @Test
    void aVariableByteIntegerTooLongOrNotMinimalIsMalformed() {
        assertThatThrownBy(() -> Wire.readVbi(new ByteArrayInputStream(HEX.parseHex("FF FF FF FF 01"))))
                .isInstanceOfSatisfying(MqttException.class, e -> assertThat(e.reasonCode()).isEqualTo(Packet.MALFORMED));
        assertThatThrownBy(() -> Wire.readVbi(new ByteArrayInputStream(HEX.parseHex("80 00"))))
                .isInstanceOfSatisfying(MqttException.class, e -> assertThat(e.reasonCode()).isEqualTo(Packet.MALFORMED));
        assertThatThrownBy(() -> new Wire.In(HEX.parseHex("80 80 00"), 0, 3).vbi())
                .isInstanceOfSatisfying(MqttException.class, e -> assertThat(e.reasonCode()).isEqualTo(Packet.MALFORMED));
        assertThatThrownBy(() -> Wire.readVbi(new ByteArrayInputStream(HEX.parseHex("80 80"))))
                .isInstanceOf(EOFException.class);
    }

    /// The specification's UTF-8 example (§1.5.4, Figure 1-1): "A" then U+2A6D4 is the length 5 then
    /// `41 F0 AA 9B 94`, and reads back the same.
    @Test
    void theSpecificationsUtf8ExampleEncodesAndDecodes() throws Exception {
        final String s = "A\uD869\uDED4";
        final byte[] encoded = new Wire.Out().string(s).toByteArray();
        assertThat(HEX.formatHex(encoded)).isEqualTo("00 05 41 F0 AA 9B 94");
        assertThat(new Wire.In(encoded, 0, encoded.length).string()).isEqualTo(s);
    }

    /// A string with U+0000, a lone surrogate, or more than 65,535 bytes is refused on the way out; bytes that are not
    /// UTF-8 (a lone continuation byte, an overlong encoding, an encoded surrogate) and an encoded U+0000 on the way in.
    @Test
    void aStringThatBreaksTheUtf8RulesIsMalformed() throws Exception {
        for (final String bad : new String[] {"a\u0000b", "\uD800", "x".repeat(65_536)}) {
            assertThatThrownBy(() -> new Wire.Out().string(bad)).isInstanceOfSatisfying(MqttException.class,
                    e -> assertThat(e.reasonCode()).isEqualTo(Packet.MALFORMED));
        }
        assertThat(new Wire.Out().string("x".repeat(65_535)).toByteArray()).hasSize(2 + 65_535);
        for (final String bad : new String[] {"00 01 80", "00 02 C0 80", "00 03 ED A0 80", "00 01 00"}) {
            final byte[] b = HEX.parseHex(bad);
            assertThatThrownBy(() -> new Wire.In(b, 0, b.length).string()).as(bad).isInstanceOfSatisfying(
                    MqttException.class, e -> assertThat(e.reasonCode()).isEqualTo(Packet.MALFORMED));
        }
    }

    /// Integers are big-endian (§1.5.2, §1.5.3), a four-byte one unsigned; reading past the end is malformed.
    @Test
    void integersAreBigEndianAndReadingPastTheEndIsMalformed() throws Exception {
        final byte[] b = new Wire.Out().u8(0xAB).u16(0x1234).u32(0xFFFF_FFFEL).toByteArray();
        assertThat(HEX.formatHex(b)).isEqualTo("AB 12 34 FF FF FF FE");
        final Wire.In in = new Wire.In(b, 0, b.length);
        assertThat(in.u8()).isEqualTo(0xAB);
        assertThat(in.u16()).isEqualTo(0x1234);
        assertThat(in.u32()).isEqualTo(0xFFFF_FFFEL);
        assertThat(in.remaining()).isZero();
        assertThatThrownBy(in::u8).isInstanceOfSatisfying(MqttException.class,
                e -> assertThat(e.reasonCode()).isEqualTo(Packet.MALFORMED));
    }

    /// Every UTF-8 length, one to four bytes, and the code points on each side of its boundaries encode to the bytes
    /// the JDK's UTF-8 encoder gives, in the size a counting `Out` counts, and decode back to the same string, on the
    /// ASCII path and the decoder's alike.
    @Test
    void stringsEncodeAsTheJdksUtf8AtEveryLengthBoundary() throws Exception {
        final int[] codePoints = {0x01, 0x7F, 0x80, 0x7FF, 0x800, 0xD7FF, 0xE000, 0xFFFF, 0x1_0000, 0x10_FFFF};
        final StringBuilder all = new StringBuilder("ascii/");
        for (final int cp : codePoints) {
            all.appendCodePoint(cp);
        }
        final String mixed = "é€" + new String(Character.toChars(0x1F600));
        for (final String s : new String[] {"", "a/b", all.toString(), mixed}) {
            final byte[] expected = s.getBytes(StandardCharsets.UTF_8);
            final byte[] encoded = new Wire.Out().string(s).toByteArray();
            assertThat(Arrays.copyOfRange(encoded, 2, encoded.length)).as(s).isEqualTo(expected);
            assertThat(encoded.length).as(s).isEqualTo(2 + expected.length)
                    .isEqualTo(Wire.Out.counting().string(s).size());
            assertThat(new Wire.In(encoded, 0, encoded.length).string()).isEqualTo(s);
        }
    }

    /// A surrogate is refused unless a high one is followed by a low one: a low one alone, a high one followed by
    /// another character, and two high ones.
    @Test
    void aSurrogateOutOfItsPairIsMalformed() {
        final char high = 0xD83D;
        final char low = 0xDE00;
        for (final String bad : new String[] {String.valueOf(low), high + "x", "" + high + high + low,
                low + "" + high}) {
            assertThatThrownBy(() -> Wire.Out.counting().string(bad)).isInstanceOfSatisfying(MqttException.class,
                    e -> assertThat(e.reasonCode()).isEqualTo(Packet.MALFORMED));
        }
    }

    /// A counting `Out` counts what a writing one writes, and an `Out` sized to what it is given hands back its own
    /// buffer, the bytes not copied again.
    @Test
    void aCountingOutCountsWhatAnExactOneWrites() throws Exception {
        final Wire.Out counted = Wire.Out.counting().u8(1).u16(2).u32(3).vbi(16_384).string("é").binary(new byte[3])
                .bytes(new byte[5]);
        final Wire.Out exact = new Wire.Out(counted.size());
        exact.u8(1).u16(2).u32(3).vbi(16_384).string("é").binary(new byte[3]).bytes(new byte[5]);
        assertThat(exact.size()).isEqualTo(counted.size()).isEqualTo(1 + 2 + 4 + 3 + 4 + 5 + 5);
        assertThat(exact.toByteArray()).isSameAs(exact.toByteArray());
        assertThat(new Wire.Out().u8(1).toByteArray()).hasSize(1);
    }

    /// A slice reads its window of the same bytes and no further, and the reader it came from skips the window.
    @Test
    void aSliceReadsItsWindowInPlace() throws Exception {
        final byte[] b = HEX.parseHex("01 02 03 04 05");
        final Wire.In in = new Wire.In(b, 1, b.length);
        final Wire.In slice = in.slice(2);
        assertThat(in.u8()).isEqualTo(0x04);
        assertThat(slice.u16()).isEqualTo(0x0203);
        assertThatThrownBy(slice::u8).isInstanceOfSatisfying(MqttException.class,
                e -> assertThat(e.reasonCode()).isEqualTo(Packet.MALFORMED));
        assertThatThrownBy(() -> in.slice(2)).isInstanceOfSatisfying(MqttException.class,
                e -> assertThat(e.reasonCode()).isEqualTo(Packet.MALFORMED));
    }
}
