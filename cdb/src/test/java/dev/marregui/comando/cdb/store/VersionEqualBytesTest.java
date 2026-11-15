package dev.marregui.comando.cdb.store;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [Version#equalBytes] agrees with [Arrays#equals] for every length up to three words, every offset into the data
/// and a difference at every position, so the word loop and the byte tail meet without a gap or an overlap.
class VersionEqualBytesTest {

    /// A text that ends at the data's last byte takes the byte tail, as no whole word follows it; equal and one bit off.
    @Test
    void comparesATextEndingAtTheLastByteOfTheData() {
        try (Arena arena = Arena.ofConfined()) {
            for (int length = 1; length <= 15; length++) {
                final MemorySegment data = arena.allocate(length + 2L);
                final byte[] bytes = new byte[length];
                for (int i = 0; i < length; i++) {
                    bytes[i] = (byte) (i * 17 + 3);
                    data.set(ValueLayout.JAVA_BYTE, 2L + i, bytes[i]);
                }
                assertThat(Version.equalBytes(data, 2L, bytes)).as("len=%d", length).isTrue();
                final byte[] other = bytes.clone();
                other[length - 1] ^= (byte) 0x01;
                assertThat(Version.equalBytes(data, 2L, other)).as("len=%d", length).isFalse();
            }
        }
    }

    /// Equal bytes compare equal and one flipped bit anywhere compares unequal, at lengths 0 to 24 and offsets 0 to 7.
    @Test
    void agreesWithArraysEqualsAtEveryLengthOffsetAndDifference() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment data = arena.allocate(64L);
            for (int length = 0; length <= 24; length++) {
                for (int offset = 0; offset < 8; offset++) {
                    final byte[] bytes = new byte[length];
                    for (int i = 0; i < length; i++) {
                        bytes[i] = (byte) (i * 31 + 7);
                        data.set(ValueLayout.JAVA_BYTE, offset + i, bytes[i]);
                    }
                    assertThat(Version.equalBytes(data, offset, bytes)).as("len=%d off=%d", length, offset).isTrue();
                    for (int at = 0; at < length; at++) {
                        final byte[] other = bytes.clone();
                        other[at] ^= (byte) 0x40;
                        assertThat(Version.equalBytes(data, offset, other))
                                .as("len=%d off=%d diff=%d", length, offset, at)
                                .isEqualTo(Arrays.equals(bytes, other));
                    }
                }
            }
        }
    }
}
