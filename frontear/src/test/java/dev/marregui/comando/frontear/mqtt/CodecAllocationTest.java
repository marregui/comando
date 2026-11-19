package dev.marregui.comando.frontear.mqtt;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// The codec allocates what it hands out and little more: a PUBLISH encodes into the one array it returns, sized
/// before it is written, where a buffer grown by doubling and copied out cost several times the packet; and a
/// PUBLISH record reads in place, copying only its payload out, where reading it through a stream copied the whole
/// record first.
class CodecAllocationTest {

    /// Enough calls for C2 to compile encode and decode, so the rounds measure compiled code.
    private static final int WARM_UP_CALLS = 20_000;
    private static final int CALLS_PER_ROUND = 200;
    /// The fewest bytes of 25 rounds drops a round that a GC or a TLAB refill inflated.
    private static final int ROUNDS = 25;

    /// Encoding a PUBLISH of 1 KiB with properties allocates its encoded bytes and a few small objects besides.
    @Test
    void aPublishEncodesIntoOneArrayOfItsSize() throws Exception {
        final Packet.Publish p = new Packet.Publish("sensors/line-1/temperature", payload(1_024), 1, false, false, 42,
                "replies/line-1", payload(16), new Packet.Meta(1, 60, "application/octet-stream",
                        List.of(new Packet.UserProperty("site", "Köln"))));
        final int length = Codec.encode(p).length;
        final long bytes = leastBytesPerCall(() -> Codec.encode(p).length);
        System.out.println("encode [encodedBytes=" + length + ", allocatedBytes=" + bytes + ']');
        // 512 B: the packet, its Meta and list, a few small objects; doubling a buffer and copying it cost more than
        // the packet itself (5,584 B for this one before)
        assertThat(bytes).isLessThanOrEqualTo(length + 512L);
    }

    /// Reading a PUBLISH record of 64 KiB back allocates its payload's copy and the packet, not a copy of the record.
    @Test
    void aPublishRecordReadsInPlace() throws Exception {
        final byte[] record = PublishRecord.encode(new Packet.Publish("sensors/line-1/image", payload(65_536), 1,
                false, false, 7, null, null));
        final long bytes = leastBytesPerCall(() -> PublishRecord.decode(record).payload().length);
        System.out.println("decode [recordBytes=" + record.length + ", allocatedBytes=" + bytes + ']');
        // 1.5x: the payload's copy and the packet stay under it; a second copy of the record would make it 2x or more
        assertThat(bytes).isLessThan(65_536L * 3 / 2);
    }

    /// The fewest bytes one call of `call` allocated on this thread, over [#ROUNDS] rounds after a warm-up, so a
    /// round that a class load or the compiler disturbed does not count.
    private static long leastBytesPerCall(final Call call) throws MqttException {
        final ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long sink = 0;
        for (int i = 0; i < WARM_UP_CALLS; i++) {
            sink += call.run();
        }
        long least = Long.MAX_VALUE;
        for (int round = 0; round < ROUNDS; round++) {
            final long before = threads.getCurrentThreadAllocatedBytes();
            for (int i = 0; i < CALLS_PER_ROUND; i++) {
                sink += call.run();
            }
            least = Math.min(least, (threads.getCurrentThreadAllocatedBytes() - before) / CALLS_PER_ROUND);
        }
        assertThat(sink).isPositive();
        return least;
    }

    private static byte[] payload(final int n) {
        final byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) i;
        }
        return b;
    }

    /// One codec call, answering a number that depends on its result so the call cannot be dropped.
    @FunctionalInterface
    private interface Call {
        int run() throws MqttException;
    }
}
