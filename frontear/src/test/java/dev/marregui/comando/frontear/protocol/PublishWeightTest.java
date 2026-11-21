package dev.marregui.comando.frontear.protocol;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// What holding a PUBLISH costs a byte bound: every field it carries, and what the objects around them hold.
class PublishWeightTest {

    // the first character a compact string holds in two bytes, and the last one before it
    private static final char FIRST_BEYOND_LATIN1 = 0x100;
    private static final char LAST_LATIN1 = 0xFF;
    // the heap one decoded empty user property took, measured over a PUBLISH of 1,000,000 of them
    private static final long MEASURED_EMPTY_PROPERTY_BYTES = 76;

    /// Each field a message carries adds its length: topic, payload, response topic, correlation data, content type,
    /// and each user property's name and value.
    @Test
    void everyFieldAddsItsLength() {
        final Packet.Publish bare = new Packet.Publish("t", new byte[0], 1, false, false, 1, null, null);
        final Packet.Publish full = new Packet.Publish("t/xyz", new byte[10], 1, false, false, 1, "reply",
                new byte[7], new Packet.Meta(-1, -1, "text/plain", List.of(new Packet.UserProperty("name", "va"))));
        final Packet.Publish noValues = new Packet.Publish("t", new byte[0], 1, false, false, 1, null, null,
                new Packet.Meta(-1, -1, null, List.of(new Packet.UserProperty("", ""))));
        final long fields = 4 + 10 + "reply".length() + 7 + "text/plain".length() + "name".length() + "va".length();
        assertThat(full.weight() - noValues.weight()).isEqualTo(fields);
        assertThat(bare.weight()).isGreaterThan(bare.topic().length());
    }

    /// An empty user property, 5 bytes on the wire, weighs at least what one took on the heap, and a message weighs at
    /// least what it takes on the wire.
    @Test
    void anEmptyUserPropertyWeighsWhatItHolds() throws MqttException {
        final Packet.Publish bare = new Packet.Publish("t", new byte[0], 1, false, false, 1, null, null);
        final Packet.Publish many = new Packet.Publish("t", new byte[0], 1, false, false, 1, null, null,
                new Packet.Meta(-1, -1, null, Collections.nCopies(1_000, new Packet.UserProperty("", ""))));
        assertThat(many.weight() - bare.weight()).isGreaterThanOrEqualTo(1_000 * MEASURED_EMPTY_PROPERTY_BYTES);
        assertThat(many.weight()).isGreaterThanOrEqualTo(Codec.encode(many).length);
        assertThat(bare.weight()).isGreaterThanOrEqualTo(Codec.encode(bare).length);
    }

    /// A string whose characters are all Latin-1, up to its last one, counts one byte per character, as an ASCII
    /// string of its length does.
    @Test
    void aLatin1StringCountsOneBytePerCharacter() {
        final Packet.Publish ascii = everyString('a');
        final Packet.Publish latin1 = everyString(LAST_LATIN1);
        assertThat(latin1.weight()).isEqualTo(ascii.weight());
    }

    /// One character beyond Latin-1 makes its whole string count two bytes per character, in every string a message
    /// carries, as the JDK then holds the string: the weight grows by the length of each such string.
    @Test
    void aStringBeyondLatin1CountsTwoBytesPerCharacter() {
        final Packet.Publish ascii = everyString('a');
        final Packet.Publish wide = everyString(FIRST_BEYOND_LATIN1);
        final long lengths = "t/xyz".length() + "reply/x".length() + "text/x".length() + "name-x".length()
                + "value-xx".length();
        assertThat(wide.weight() - ascii.weight()).isEqualTo(lengths);
    }

    /// A message whose topic, response topic, content type and one user property's name and value each end in `last`.
    private static Packet.Publish everyString(final char last) {
        return new Packet.Publish("t/xy" + last, new byte[3], 1, false, false, 1, "reply/" + last, new byte[2],
                new Packet.Meta(-1, -1, "text/" + last, List.of(new Packet.UserProperty("name-" + last,
                        "value-x" + last))));
    }
}
