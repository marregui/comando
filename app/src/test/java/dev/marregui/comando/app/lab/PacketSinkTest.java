package dev.marregui.comando.app.lab;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.loop.Threads;
import dev.marregui.comando.core.port.LabPort.Kind;
import dev.marregui.comando.core.port.LabPort.Packet;
import dev.marregui.comando.frontear.watch.Watch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// The lab's cdb sink: an offer never waits for the writer, a full queue drops and counts, a failing writer is backed
/// off and the lines it did not land counted as dropped, and each packet becomes one line of table
/// `frontear_packet`, its payload in the JSON column or the text one.
class PacketSinkTest {

    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final long ONE_MILLI_NANOS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final int OFFERS = 10_000;

    /// A writer blocked on its first line: 10 000 offers more each return within 1 ms at the 99th percentile and
    /// none waits for the writer (the slowest well under the writer's 30 s); the queue holds 8 192 and the other
    /// 1 808 are dropped and counted. The close interrupts the writer, counts its line as dropped, and drops what is
    /// offered after it.
    @Test
    void aBlockedWriterNeverBlocksAnOfferAndAFullQueueDropsAndCounts() {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch released = new CountDownLatch(1);
        final PacketSink.Writer blocked = _ -> {
            entered.countDown();
            if (!released.await(30, TimeUnit.SECONDS)) {
                throw new IOException("not released");
            }
            return 1;
        };
        final PacketSink sink = new PacketSink(blocked);
        try (sink) {
            sink.offer("a", published(0));
            await().atMost(WAIT).until(() -> entered.getCount() == 0);
            final long[] took = new long[OFFERS];
            for (int i = 0; i < OFFERS; i++) {
                final Packet p = published(i + 1);
                final long start = System.nanoTime();
                sink.offer("a", p);
                took[i] = System.nanoTime() - start;
            }
            Arrays.sort(took);
            assertThat(took[(int) (OFFERS * 0.99) - 1]).isLessThan(ONE_MILLI_NANOS);
            assertThat(took[OFFERS - 1]).isLessThan(TimeUnit.MILLISECONDS.toNanos(100));
            assertThat(sink.queued()).isEqualTo(PacketSink.CAPACITY);
            assertThat(sink.droppedFull()).isEqualTo(OFFERS - PacketSink.CAPACITY);
            assertThat(sink.written()).isZero();
        }
        assertThat(sink.droppedDown()).isEqualTo(1L);
        sink.offer("a", published(1));
        assertThat(sink.droppedFull()).isEqualTo(OFFERS - PacketSink.CAPACITY + 1L);
        sink.close();
        assertThat(Threads.alive("frontear-lab-sink")).isEmpty();
    }

    /// A writer that fails: its batch is counted as dropped, and the lines offered during the first second of
    /// backoff are dropped and counted without another write.
    @Test
    void aFailingWriterIsBackedOffAndItsLinesCounted() {
        final AtomicInteger calls = new AtomicInteger();
        try (PacketSink sink = new PacketSink(_ -> {
            calls.incrementAndGet();
            throw new IOException("cdb is not running");
        })) {
            sink.offer("a", published(1));
            await().atMost(WAIT).until(() -> sink.droppedDown() == 1L);
            for (int i = 0; i < 5; i++) {
                sink.offer("a", published(i + 2));
            }
            await().atMost(WAIT).until(() -> sink.droppedDown() == 6L);
            assertThat(calls.get()).isEqualTo(1);
            assertThat(sink.written()).isZero();
        }
    }

    /// A writer that fails once, then answers: after the backoff the lines are written again.
    @Test
    void aWriterThatAnswersAgainIsWrittenToAfterTheBackoff() {
        final AtomicInteger calls = new AtomicInteger();
        final List<String> lines = new CopyOnWriteArrayList<>();
        try (PacketSink sink = new PacketSink(batch -> {
            if (calls.incrementAndGet() == 1) {
                throw new IOException("cdb is starting");
            }
            lines.addAll(batch);
            return batch.size();
        })) {
            sink.offer("a", published(1));
            await().atMost(WAIT).until(() -> sink.droppedDown() == 1L);
            final AtomicInteger next = new AtomicInteger(2);
            await().atMost(WAIT).pollInterval(Duration.ofMillis(100)).until(() -> {
                sink.offer("a", published(next.getAndIncrement()));
                return sink.written() > 0;
            });
            assertThat(calls.get()).isGreaterThanOrEqualTo(2);
            assertThat(lines).isNotEmpty().allSatisfy(l -> assertThat(l).startsWith("frontear_packet,node=a,"));
        }
    }

    /// A published packet and a dropped one as lines: tags escaped and left out when empty, string fields quoted,
    /// integers marked, offset and correlation only when the packet has them, the time in nanoseconds.
    @Test
    void eachPacketIsOneLineOfTheTable() {
        final Packet published = new Packet(9L, 1_700_000_000_123L, Kind.PUBLISHED, "pub one", "", "t/a b", 1, true,
                12, 0, -1L, "0a0b", "hi", "");
        assertThat(PacketSink.line("a,b", published)).isEqualTo("frontear_packet,node=a\\,b,kind=published,"
                + "origin=pub\\ one topic=\"t/a b\",qos=1i,retain=t,bytes=12i,hops=0i,correlation=\"0a0b\",code=0i"
                + " 1700000000123000000");
        final Packet dropped = new Packet(10L, 5L, Kind.DROPPED, "", "b", "t/\"q\"", -1, false, 7, -1, 3L, "", "",
                "the link's queue is full [queued=64]");
        assertThat(PacketSink.line("a", dropped)).isEqualTo("frontear_packet,node=a,kind=dropped,link=b"
                + " topic=\"t/\\\"q\\\"\",qos=-1i,retain=f,bytes=7i,hops=-1i,offset=3i,code=1i 5000000");
    }

    /// A publish's payload that is not JSON, here an unknown escape, as a string field of the text column after the
    /// correlation: quotes, backslashes and line breaks escaped, other text as it is; the head stays out of the line.
    @Test
    void aPublishWhosePayloadIsNotJsonCarriesItInTheTextColumn() {
        final Packet published = new Packet(9L, 2L, Kind.PUBLISHED, "pub", "", "t", 1, false, 17, 0, -1L, "", "{\"a\"",
                "", "{\"a\":\"b\\c\"}\nend é");
        assertThat(PacketSink.line("a", published)).isEqualTo("frontear_packet,node=a,kind=published,origin=pub"
                + " topic=\"t\",qos=1i,retain=f,bytes=17i,hops=0i,"
                + "payload_text=\"{\\\"a\\\":\\\"b\\\\c\\\"}\\\nend é\",code=0i 2000000");
    }

    /// A payload kept whole that is one JSON document goes to the JSON column, escaped the same way.
    @Test
    void aPublishWhosePayloadIsJsonCarriesItInTheJsonColumn() {
        final Packet published = new Packet(9L, 2L, Kind.PUBLISHED, "pub", "", "t", 1, false, 16, 0, -1L, "", "{",
                "", "{\"k\":[\"v\",1.5]}\n");
        assertThat(PacketSink.line("a", published)).isEqualTo("frontear_packet,node=a,kind=published,origin=pub"
                + " topic=\"t\",qos=1i,retain=f,bytes=16i,hops=0i,"
                + "payload=\"{\\\"k\\\":[\\\"v\\\",1.5]}\\\n\",code=0i 2000000");
    }

    /// The JSON column takes a payload kept whole that parses as one document, a bare value included; a cut payload
    /// goes to text even when its first bytes parse, and so do whitespace, two documents and text that is not JSON.
    @Test
    void onlyAWholePayloadThatParsesGoesToTheJsonColumn() {
        assertThat(PacketSink.json(payload("{\"a\":{\"b\":[1,true,null]}}", 25))).isTrue();
        assertThat(PacketSink.json(payload("42", 2))).isTrue();
        assertThat(PacketSink.json(payload("\"text\"", 6))).isTrue();
        assertThat(PacketSink.json(payload("{\"a\":1}", Watch.PAYLOAD_BYTES))).isTrue();
        assertThat(PacketSink.json(payload("{\"a\":1}", Watch.PAYLOAD_BYTES + 1))).isFalse();
        assertThat(PacketSink.json(payload("  \n", 3))).isFalse();
        assertThat(PacketSink.json(payload("{} {}", 5))).isFalse();
        assertThat(PacketSink.json(payload("{\"a\":1,}", 8))).isFalse();
        assertThat(PacketSink.json(payload("temperature=21.5", 16))).isFalse();
    }

    /// A writer that lands some of a batch and drops a line too long for a request: the sink counts what it landed
    /// as written and the dropped line as dropped, the batches as the writer saw them.
    @Test
    void theLinesAWriterDropsAreCountedAsDroppedAndTheRestAsWritten() {
        try (PacketSink sink = new PacketSink(batch -> (int) batch.stream()
                .filter(l -> !l.contains("topic=\"t/2\""))
                .count())) {
            for (int i = 1; i <= 3; i++) {
                sink.offer("a", published(i));
            }
            await().atMost(WAIT).until(() -> sink.written() + sink.droppedDown() == 3L);
            assertThat(sink.written()).isEqualTo(2L);
            assertThat(sink.droppedDown()).isEqualTo(1L);
        }
    }

    /// A batch of three whose write fails after one landed: one line written, two dropped, not the whole batch
    /// either way. The first write holds the writer until all three are queued, so the second takes them together.
    @Test
    void aWriteThatFailsPartWayCountsWhatLandedAsWritten() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch released = new CountDownLatch(1);
        final List<Integer> sizes = new CopyOnWriteArrayList<>();
        try (PacketSink sink = new PacketSink(batch -> {
            sizes.add(batch.size());
            if (sizes.size() == 1) {
                entered.countDown();
                if (!released.await(30, TimeUnit.SECONDS)) {
                    throw new IOException("not released");
                }
                return batch.size();
            }
            throw new PacketSink.PartialWrite("second request refused", 1, null);
        })) {
            sink.offer("a", published(0));
            assertThat(entered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            for (int i = 1; i <= 3; i++) {
                sink.offer("a", published(i));
            }
            released.countDown();
            await().atMost(WAIT).until(() -> sink.written() + sink.droppedDown() == 4L);
            assertThat(sizes).containsExactly(1, 3);
            assertThat(sink.written()).isEqualTo(2L);
            assertThat(sink.droppedDown()).isEqualTo(2L);
        }
    }

    /// A dropped packet's code from the detail its link gave: one number per known reason, 0 for a new one and for
    /// a packet that was not dropped.
    @Test
    void aDroppedPacketCarriesTheCodeOfItsReason() {
        assertThat(PacketSink.code(dropped("the link's queue is full"))).isEqualTo(1);
        assertThat(PacketSink.code(dropped("past the hop limit [hops=4]"))).isEqualTo(2);
        assertThat(PacketSink.code(dropped("the link's log is full"))).isEqualTo(3);
        assertThat(PacketSink.code(dropped("the peer refused it [reasonCode=0x97]"))).isEqualTo(4);
        assertThat(PacketSink.code(dropped("expired in the link's log"))).isEqualTo(5);
        assertThat(PacketSink.code(dropped("larger than the peer accepts"))).isEqualTo(6);
        assertThat(PacketSink.code(dropped("the record is unreadable"))).isEqualTo(7);
        assertThat(PacketSink.code(dropped("a reason not known yet"))).isZero();
        assertThat(PacketSink.code(new Packet(1L, 1L, Kind.SENT, "", "b", "t", -1, false, 1, -1, 0L, "", "",
                "the link's queue is full"))).isZero();
    }

    /// A sink that is off keeps, counts and starts nothing, and closes twice.
    @Test
    void aSinkThatIsOffKeepsNothing() {
        final PacketSink sink = PacketSink.off();
        sink.offer("a", published(1));
        assertThat(sink.queued()).isZero();
        assertThat(sink.droppedFull()).isZero();
        assertThat(sink.droppedDown()).isZero();
        assertThat(sink.written()).isZero();
        assertThat(Threads.alive("frontear-lab-sink")).isEmpty();
        sink.close();
        sink.close();
    }

    private static Packet published(final int i) {
        return new Packet(i, 1_000L + i, Kind.PUBLISHED, "pub", "", "t/" + i, 1, false, 3, 0, -1L, "", "m", "");
    }

    private static Packet payload(final String text, final int bytes) {
        return new Packet(1L, 1L, Kind.PUBLISHED, "pub", "", "t", 0, false, bytes, 0, -1L, "", "", "", text);
    }

    private static Packet dropped(final String detail) {
        return new Packet(1L, 1L, Kind.DROPPED, "", "b", "t", -1, false, 1, -1, 0L, "", "", detail);
    }
}
