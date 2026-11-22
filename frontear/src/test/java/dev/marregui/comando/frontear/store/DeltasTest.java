package dev.marregui.comando.frontear.store;

import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import static org.assertj.core.api.Assertions.assertThat;

/// A record as a delta of the one before it: every delta decodes to its record, records that differ in a few places
/// or by a few bytes inserted or removed are stored small, records with nothing to share are not stored as deltas,
/// and a delta that is cut short or made of random bytes decodes to nothing, never to an exception. Seed in the
/// output; replay with `-Dfrontear.store.seed=<seed>`.
class DeltasTest {

    private static final long SEED = Long.getLong("frontear.store.seed", System.nanoTime());

    private Random random;

    /// A generator per test, from the run's seed and the test's name, so one test replays alone.
    @BeforeEach
    void seed(final TestInfo info) {
        System.out.println(info.getDisplayName() + " seed=" + SEED);
        random = new Random(SEED ^ info.getDisplayName().hashCode());
    }

    /// Records made from the one before by random edits (bytes changed, inserted or removed, the tail replaced)
    /// decode from their delta byte for byte; most edits leave enough in common for a delta.
    @Test
    void everyDeltaDecodesToItsRecord() {
        int deltas = 0;
        final int rounds = 20_000;
        for (int n = 0; n < rounds; n++) {
            final byte[] previous = bytes(1 + random.nextInt(400));
            final byte[] record = edited(previous);
            final byte[] delta = Deltas.encode(previous, record);
            if (delta != null) {
                deltas++;
                assertThat(delta.length).isLessThan(record.length);
                assertThat(Deltas.decode(previous, delta)).as("round " + n).isEqualTo(record);
            }
        }
        assertThat(deltas).as("deltas made").isGreaterThan(rounds / 4);
    }

    /// Two records that share a long header and differ in a counter, a length that grew by a byte and their tails
    /// are stored as the bytes they differ by and a handful of step numbers.
    @Test
    void aRecordDifferingInAFewPlacesTakesLittleMoreThanItsOwnBytes() {
        final byte[] header = bytes(80);
        final byte[] previous = concat(header, new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, bytes(128));
        final byte[] tail = bytes(128);
        final byte[] record = concat(header, new byte[] {1, 2, 9, 4, 5, 6, 7, 8, 8}, tail);
        final byte[] delta = Deltas.encode(previous, record);
        assertThat(delta).isNotNull();
        // the tail, the changed counter and the inserted byte are the record's own: 130 bytes, the rest numbers
        assertThat(delta.length).isLessThanOrEqualTo(tail.length + 2 + 16);
        assertThat(Deltas.decode(previous, delta)).isEqualTo(record);
    }

    /// A record with nothing to share with the one before, or too short for a delta to save anything, is not stored
    /// as a delta.
    @Test
    void aRecordWithNothingToShareIsNotADelta() {
        for (int n = 0; n < 1_000; n++) {
            final byte[] previous = bytes(random.nextInt(300));
            final byte[] record = bytes(random.nextInt(300));
            final byte[] delta = Deltas.encode(previous, record);
            if (delta != null) {
                // random bytes can share a run by chance; then the delta must still decode and save
                assertThat(delta.length).isLessThan(record.length);
                assertThat(Deltas.decode(previous, delta)).isEqualTo(record);
            }
        }
        final byte[] small = bytes(16);
        assertThat(Deltas.encode(small, small.clone())).as("a record of 16 bytes").isNull();
        assertThat(Deltas.encode(bytes(300), new byte[300])).as("zeros after random bytes").isNull();
    }

    /// Every delta cut short decodes to nothing, and random bytes decode to nothing or to some record, never to an
    /// exception, against previous records of any size.
    @Test
    void aMalformedDeltaDecodesToNothing() {
        for (int n = 0; n < 2_000; n++) {
            final byte[] previous = bytes(1 + random.nextInt(300));
            final byte[] record = edited(previous);
            final byte[] delta = Deltas.encode(previous, record);
            if (delta != null) {
                for (int cut = 0; cut < delta.length; cut++) {
                    assertThat(Deltas.decode(previous, Arrays.copyOf(delta, cut))).as("cut at " + cut).isNull();
                }
            }
            final byte[] garbage = bytes(random.nextInt(40));
            final byte[] decoded = Deltas.decode(previous, garbage);
            if (decoded != null) {
                assertThat(decoded.length).isLessThanOrEqualTo(previous.length * 8 + garbage.length);
            }
        }
        // a length past what any delta of that previous record makes
        assertThat(Deltas.decode(new byte[4], new byte[] {(byte) 0xFF, (byte) 0xFF, 0x7F})).isNull();
        // a varint in a longer form than it needs
        assertThat(Deltas.decode(new byte[4], new byte[] {(byte) 0x81, 0x00, 0, 1, 0})).isNull();
    }

    // previous with random edits: bytes changed, a few inserted or removed, the tail replaced
    private byte[] edited(final byte[] previous) {
        byte[] record = previous.clone();
        final int edits = random.nextInt(4);
        for (int e = 0; e < edits && record.length > 0; e++) {
            final int at = random.nextInt(record.length);
            switch (random.nextInt(4)) {
                case 0 -> record[at] = (byte) random.nextInt();
                case 1 -> record = concat(Arrays.copyOf(record, at), bytes(1 + random.nextInt(3)),
                        Arrays.copyOfRange(record, at, record.length));
                case 2 -> record = concat(Arrays.copyOf(record, at),
                        Arrays.copyOfRange(record, Math.min(record.length, at + 1 + random.nextInt(3)),
                                record.length));
                default -> record = concat(Arrays.copyOf(record, at), bytes(random.nextInt(200)));
            }
        }
        return record;
    }

    private byte[] bytes(final int n) {
        final byte[] b = new byte[n];
        random.nextBytes(b);
        return b;
    }

    private static byte[] concat(final byte[]... parts) {
        int n = 0;
        for (final byte[] p : parts) {
            n += p.length;
        }
        final byte[] out = new byte[n];
        int at = 0;
        for (final byte[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }
}
