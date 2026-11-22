package dev.marregui.comando.app.lab;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.JsonException;
import dev.marregui.comando.core.loop.Threads;
import dev.marregui.comando.core.port.LabPort.Kind;
import dev.marregui.comando.core.port.LabPort.Packet;
import dev.marregui.comando.frontear.watch.Watch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// The lab's cdb sink: an offer never waits for the writer, a full queue drops and counts, a failing writer is backed
/// off and the lines it did not land counted as dropped, a node above the sampling rate is kept 1 in N, a table
/// cdb says takes no writes is named, and each packet becomes one line of table `frontear_packet`, its payload in
/// the JSON column or the text one, deciding which without an exception.
class PacketSinkTest {

    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final long ONE_MILLI_NANOS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final int OFFERS = 10_000;
    private static final int BINARY_BYTES = 64;
    /// Packets a second under the sampling rate.
    private static final int QUIET = 1_500;
    private static final Pattern NODE_AND_TOPIC =
            Pattern.compile("^frontear_packet,node=([^,]+),.* topic=\"t/(\\d+)\"");

    /// A writer blocked on its first line: 10 000 offers more each return within 1 ms at the 99th percentile and
    /// none waits for the writer (the slowest well under the writer's 30 s); the queue holds 8 192 and the other
    /// 1 808 are dropped and counted. The close interrupts the writer, counts its line as dropped, and drops what is
    /// offered after it. Sampling is off, so every offer reaches the queue.
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
        final PacketSink sink = new PacketSink(blocked, PacketSink.CAPACITY, Integer.MAX_VALUE, System::nanoTime);
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

    /// The JSON column takes a payload kept whole that parses as one object or array, whitespace before it allowed; a
    /// bare value goes to text, and so does a cut payload even when its first bytes parse, whitespace, two documents
    /// and text that is not JSON.
    @Test
    void onlyAWholePayloadThatParsesGoesToTheJsonColumn() {
        assertThat(PacketSink.json(payload("{\"a\":{\"b\":[1,true,null]}}", 25))).isTrue();
        assertThat(PacketSink.json(payload(" \n\t[1,{}]", 9))).isTrue();
        assertThat(PacketSink.json(payload("42", 2))).isFalse();
        assertThat(PacketSink.json(payload("\"text\"", 6))).isFalse();
        assertThat(PacketSink.json(payload("true", 4))).isFalse();
        assertThat(PacketSink.json(payload("{\"a\":1}", Watch.PAYLOAD_BYTES))).isTrue();
        assertThat(PacketSink.json(payload("{\"a\":1}", Watch.PAYLOAD_BYTES + 1))).isFalse();
        assertThat(PacketSink.json(payload("  \n", 3))).isFalse();
        assertThat(PacketSink.json(payload("{} {}", 5))).isFalse();
        assertThat(PacketSink.json(payload("{\"a\":1,}", 8))).isFalse();
        assertThat(PacketSink.json(payload("temperature=21.5", 16))).isFalse();
    }

    /// 10 000 binary payloads, decoded as UTF-8 decodes bytes that are not text, and 10 000 cut JSON objects through
    /// the sink make no `JsonException` there: a JFR recording of every exception made sees none whose stack has a
    /// frame of the sink. It sees the one `Json.readTree` makes for the same broken text, so it does count them. Every
    /// line carries its payload in the text column.
    @Test
    void payloadsThatAreNotJsonMakeNoException(@TempDir final Path dir) throws Exception {
        assertThat(Json.tryReadTree("{")).isNull(); // the parser's one failure is made before the recording starts
        final List<String> lines = new CopyOnWriteArrayList<>();
        final Path file = dir.resolve("sink.jfr");
        try (Recording recording = new Recording()) {
            recording.enable("jdk.JavaExceptionThrow").withStackTrace();
            recording.start();
            try (PacketSink sink = new PacketSink(batch -> {
                lines.addAll(batch);
                return batch.size();
            }, 2 * OFFERS, Integer.MAX_VALUE, System::nanoTime)) {
                for (int i = 0; i < OFFERS; i++) {
                    sink.offer("a", payload(binary(i), BINARY_BYTES));
                    sink.offer("a", payload("{\"t\":" + i, 8));
                }
                await().atMost(WAIT).until(() -> sink.written() == 2L * OFFERS);
            }
            try {
                Json.readTree("{\"t\":1");
            } catch (final JsonException _) {
                // the control: one the recording must see
            }
            recording.stop();
            recording.dump(file);
        }
        final List<RecordedEvent> made = RecordingFile.readAllEvents(file).stream()
                .filter(e -> e.getClass("thrownClass").getName().equals(JsonException.class.getName()))
                .toList();
        assertThat(made).filteredOn(e -> madeIn(e, PacketSink.class)).isEmpty();
        assertThat(made).filteredOn(e -> madeIn(e, PacketSinkTest.class)).hasSize(1);
        assertThat(lines).hasSize(2 * OFFERS)
                .allSatisfy(l -> assertThat(l).contains(",payload_text=").doesNotContain(",payload="));
    }

    /// A node above the sampling rate, the clock held still: a burst of 10 000 in its first second keeps fewer than
    /// all, the stride rising as the count passes the limit; in the next second, after that rate, 1 in 5 of 10 000
    /// is kept, 2 000 rows, and the 8 000 others are counted as sampled, none as dropped. Another node at 1 500 in the
    /// same second is kept whole, and the first, quiet for two seconds, is kept whole again.
    @Test
    void aNodeAboveTheRateIsKeptOneInN() {
        final AtomicLong clock = new AtomicLong();
        final List<String> lines = new CopyOnWriteArrayList<>();
        try (PacketSink sink = new PacketSink(batch -> {
            lines.addAll(batch);
            return batch.size();
        }, 4 * OFFERS, PacketSink.SAMPLE_ABOVE, clock::get)) {
            for (int i = 0; i < OFFERS; i++) {
                sink.offer("a", published(i));
            }
            final long burst = sink.sampled();
            assertThat(burst).isBetween(1L, (long) OFFERS - PacketSink.SAMPLE_ABOVE);
            clock.set(PacketSink.SAMPLE_WINDOW.toNanos());
            for (int i = OFFERS; i < 2 * OFFERS; i++) {
                sink.offer("a", published(i));
            }
            for (int i = 0; i < QUIET; i++) {
                sink.offer("b", published(i));
            }
            assertThat(sink.sampled() - burst).isEqualTo(OFFERS - OFFERS / 5L);
            clock.set(4L * PacketSink.SAMPLE_WINDOW.toNanos());
            for (int i = 2 * OFFERS; i < 2 * OFFERS + QUIET; i++) {
                sink.offer("a", published(i));
            }
            assertThat(sink.sampled() - burst).isEqualTo(OFFERS - OFFERS / 5L);
            final long kept = 2L * OFFERS + 2L * QUIET - sink.sampled();
            await().atMost(WAIT).until(() -> sink.written() == kept);
            assertThat(sink.droppedFull() + sink.droppedDown()).isZero();
        }
        assertThat(rows(lines, "a", OFFERS, 2 * OFFERS)).isEqualTo(OFFERS / 5);
        assertThat(rows(lines, "b", 0, QUIET)).isEqualTo(QUIET);
        assertThat(rows(lines, "a", 2 * OFFERS, 2 * OFFERS + QUIET)).isEqualTo(QUIET);
    }

    /// A refusal that names the table as its cause, here a failed one, is kept as the sink's table fault; a failure
    /// that is no answer leaves it as it was, and a write that lands clears it. The clock moves past each backoff.
    @Test
    void aTableCdbSaysTakesNoWritesIsNamedUntilAWriteLands() {
        final AtomicLong clock = new AtomicLong();
        final AtomicBoolean answers = new AtomicBoolean(true);
        final AtomicBoolean lands = new AtomicBoolean();
        final String reason = "refuse commit to failed table [table=frontear_packet]";
        try (PacketSink sink = new PacketSink(batch -> {
            if (lands.get()) {
                return batch.size();
            }
            if (answers.get()) {
                throw new PacketSink.PartialWrite("cdb refused frontear packets [status=400]", 0, null, reason);
            }
            throw new IOException("cdb is not running");
        }, PacketSink.CAPACITY, PacketSink.SAMPLE_ABOVE, clock::get)) {
            assertThat(sink.tableFault()).isEmpty();
            sink.offer("a", published(1));
            await().atMost(WAIT).until(() -> sink.droppedDown() == 1L);
            assertThat(sink.tableFault()).isEqualTo(reason);
            answers.set(false);
            clock.addAndGet(PacketSink.BACKOFF_MOST.toNanos());
            sink.offer("a", published(2));
            await().atMost(WAIT).until(() -> sink.droppedDown() == 2L);
            assertThat(sink.tableFault()).isEqualTo(reason);
            lands.set(true);
            clock.addAndGet(PacketSink.BACKOFF_MOST.toNanos());
            sink.offer("a", published(3));
            await().atMost(WAIT).until(() -> sink.written() == 1L);
            assertThat(sink.tableFault()).isEmpty();
        }
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

    /// 64 bytes that are not text, made from `i`, decoded as UTF-8: a sequence it cannot read becomes the replacement
    /// character, and a byte under 0x80 stays itself, a brace or a bracket among them.
    private static String binary(final int i) {
        final byte[] bytes = new byte[BINARY_BYTES];
        for (int j = 0; j < bytes.length; j++) {
            bytes[j] = (byte) (i * 31 + j * 17 + 0x80);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /// The lines of `node` whose topic is `t/n` with `n` from `from` up to `to`, `to` left out.
    private static long rows(final List<String> lines, final String node, final int from, final int to) {
        return lines.stream().map(NODE_AND_TOPIC::matcher).filter(Matcher::find)
                .filter(m -> m.group(1).equals(node))
                .mapToInt(m -> Integer.parseInt(m.group(2)))
                .filter(n -> n >= from && n < to)
                .count();
    }

    /// Whether a frame of `type`, not of a class nested in it, is on the stack the event recorded.
    private static boolean madeIn(final RecordedEvent event, final Class<?> type) {
        return event.getStackTrace() != null && event.getStackTrace().getFrames().stream()
                .map(RecordedFrame::getMethod)
                .anyMatch(m -> m.getType().getName().equals(type.getName()));
    }

    private static Packet payload(final String text, final int bytes) {
        return new Packet(1L, 1L, Kind.PUBLISHED, "pub", "", "t", 0, false, bytes, 0, -1L, "", "", "", text);
    }

    private static Packet dropped(final String detail) {
        return new Packet(1L, 1L, Kind.DROPPED, "", "b", "t", -1, false, 1, -1, 0L, "", "", detail);
    }
}
