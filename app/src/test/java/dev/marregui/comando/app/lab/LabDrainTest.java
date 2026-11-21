package dev.marregui.comando.app.lab;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.loop.ExecutorStop;
import dev.marregui.comando.core.loop.Threads;
import dev.marregui.comando.core.port.LabPort.Frame;
import dev.marregui.comando.core.port.LabPort.Kind;
import dev.marregui.comando.core.port.LabPort.Link;
import dev.marregui.comando.core.port.LabPort.Log;
import dev.marregui.comando.core.port.LabPort.Packet;
import dev.marregui.comando.frontear.gateway.Broker;
import dev.marregui.comando.frontear.octopus.LinkState;
import dev.marregui.comando.frontear.watch.ForceRound;
import dev.marregui.comando.frontear.watch.LinkStep;
import dev.marregui.comando.frontear.watch.LogView;
import dev.marregui.comando.frontear.watch.NodeSnapshot;
import dev.marregui.comando.frontear.watch.Observation;
import dev.marregui.comando.frontear.watch.Published;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// The lab's drain over a fake source: a burst of 50 000 events never has more than one update waiting for the EDT,
/// the window keeps the last 10 000 and counts the rest, and the node's links and logs carry the rates, the last
/// connect and disconnect and the force rounds the events say.
/// - nothing is read before the drain is opened, and opening twice starts one errand
class LabDrainTest {

    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final int BURSTS = 10;
    private static final int BURST = 5_000;

    private ExecutorService strand;
    private final AtomicInteger depth = new AtomicInteger();
    private final AtomicInteger deepest = new AtomicInteger();
    private final AtomicInteger runs = new AtomicInteger();
    private Executor edt;

    @BeforeEach
    void start() {
        strand = Threads.strand("lab-drain-test-edt");
        depth.set(0);
        deepest.set(0);
        runs.set(0);
        // an EDT that counts the updates waiting for it
        edt = task -> {
            deepest.accumulateAndGet(depth.incrementAndGet(), Math::max);
            strand.execute(() -> {
                depth.decrementAndGet();
                task.run();
            });
        };
    }

    @AfterEach
    void stop() {
        ExecutorStop.now("lab-drain-test", strand);
    }

    /// Ten bursts of 5 000 publishes, each released at once, to a listener that takes 2 ms a run as the tab's
    /// render would: at most one update is ever waiting for the EDT, the window holds the last 10 000 (seq 40 001 to
    /// 50 000), counts 40 000 dropped, and the ring missed none.
    @Test
    void fiftyThousandEventsInBurstsNeverQueueMoreThanOneUpdate() {
        final BurstSource source = new BurstSource();
        try (LabDrain drain = new LabDrain(source, edt, PacketSink.off())) {
            drain.listen(() -> {
                runs.incrementAndGet();
                drain.read(0L, LabWindow.CAPACITY);
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
            });
            drain.open();
            for (int b = 1; b <= BURSTS; b++) {
                final long upTo = (long) b * BURST;
                source.released.set(upTo);
                await().atMost(WAIT).until(() -> source.asked.get() == upTo);
            }
            await().atMost(WAIT).until(() -> drain.read(49_999L, 1).last() == 50_000L);
            final Frame all = drain.read(0L, LabWindow.CAPACITY * 2);
            assertThat(all.packets()).hasSize(LabWindow.CAPACITY);
            assertThat(all.packets().getFirst().seq()).isEqualTo(40_001L);
            assertThat(all.packets().getLast().seq()).isEqualTo(50_000L);
            assertThat(all.packets().getLast().topic()).isEqualTo("t/50000");
            assertThat(all.node().counts().windowDropped()).isEqualTo(40_000L);
            assertThat(all.node().counts().ringMissed()).isZero();
        }
        assertThat(deepest.get()).isEqualTo(1);
        assertThat(runs.get()).isPositive();
    }

    /// Ten SENT of 100 bytes in the five seconds before the snapshot make 2 messages and 200 bytes a second on link b;
    /// its last connect and disconnect carry their times and the last detail; ten force rounds of 1 to 10 ms make 2
    /// rounds a second, a median of 5 ms and a 99th percentile of 10 ms; a force round is no packet, and every packet
    /// goes to the sink as a line of node a.
    @Test
    void theNodeCarriesRatesMarksAndRoundsFromTheEvents() {
        final List<Observation> events = new ArrayList<>();
        long seq = 1;
        events.add(new Published(seq++, 8_000L, "pub", "t/x", 1, true, 5, 1, new byte[] {0x0a, 0x0b}, "hello", 0));
        events.add(new LinkStep(seq++, 1_000L, "b", LinkStep.Kind.CONNECTED, -1L, null, 0, ""));
        for (int i = 0; i < 10; i++) {
            events.add(new LinkStep(seq++, 9_000L + i * 100L, "b", LinkStep.Kind.SENT, i, "t/x", 100, ""));
            events.add(new ForceRound(seq++, 9_000L + i * 100L, "b", 2, 64L, TimeUnit.MILLISECONDS.toNanos(i + 1)));
        }
        events.add(new LinkStep(seq, 9_950L, "b", LinkStep.Kind.DISCONNECTED, -1L, null, 0, "peer went away"));
        final List<String> lines = new CopyOnWriteArrayList<>();
        try (PacketSink sink = new PacketSink(lines::addAll);
                LabDrain drain = new LabDrain(new ScriptSource(events), edt, sink)) {
            drain.open();
            // the rates are taken at the snapshot after the events, a second after the first
            await().atMost(WAIT).until(() -> drain.read(0L, 0).node().links().getFirst().messagesPerSecond() > 0);
            final Frame f = drain.read(0L, 100);
            assertThat(f.node().up()).isTrue();
            assertThat(f.node().name()).isEqualTo("a");
            assertThat(f.node().port()).isEqualTo(1883);
            assertThat(f.node().totals().sessions()).isEqualTo(3);
            assertThat(f.node().totals().refused()).isEqualTo(6L);
            final Link b = f.node().links().getFirst();
            assertThat(b.messagesPerSecond()).isEqualTo(2.0);
            assertThat(b.bytesPerSecond()).isEqualTo(200.0);
            assertThat(b.lastUpMillis()).isEqualTo(1_000L);
            assertThat(b.lastDownMillis()).isEqualTo(9_950L);
            assertThat(b.lastDetail()).isEqualTo("peer went away");
            assertThat(b.cursor()).isEqualTo(10L);
            final Log log = f.node().logs().getFirst();
            assertThat(log.roundsPerSecond()).isEqualTo(2.0);
            assertThat(log.lastRecords()).isEqualTo(2);
            assertThat(log.lastBytes()).isEqualTo(64L);
            assertThat(log.lastMillis()).isEqualTo(10.0);
            assertThat(log.p50Millis()).isEqualTo(5.0);
            assertThat(log.p99Millis()).isEqualTo(10.0);

            assertThat(f.packets()).hasSize(13);
            final Packet published = f.packets().getFirst();
            assertThat(published.kind()).isEqualTo(Kind.PUBLISHED);
            assertThat(published.correlation()).isEqualTo("0a0b");
            assertThat(published.head()).isEqualTo("hello");
            assertThat(published.link()).isEmpty();
            final Packet connected = f.packets().get(1);
            assertThat(connected.kind()).isEqualTo(Kind.CONNECTED);
            assertThat(connected.topic()).isEmpty();
            assertThat(connected.qos()).isEqualTo(-1);
            assertThat(connected.hops()).isEqualTo(-1);
            assertThat(f.packets()).filteredOn(p -> p.kind() == Kind.SENT).hasSize(10);
            await().atMost(WAIT).until(() -> lines.size() == 13);
            assertThat(lines).allSatisfy(l -> assertThat(l).startsWith("frontear_packet,node=a,kind="));
        }
    }

    /// Until it is opened the drain reads nothing and answers no node; opened twice, one errand runs, and the close
    /// ends it.
    @Test
    void nothingIsReadUntilOpenedAndOneErrandRuns() {
        final BurstSource source = new BurstSource();
        try (LabDrain drain = new LabDrain(source, edt, PacketSink.off())) {
            assertThat(drain.read(0L, 10).node().up()).isFalse();
            assertThat(source.calls.get()).isZero();
            drain.open();
            drain.open();
            await().atMost(WAIT).until(() -> source.calls.get() > 0);
            assertThat(Threads.alive("frontear-lab-drain")).hasSize(1);
        }
        assertThat(Threads.alive("frontear-lab-drain")).isEmpty();
    }

    /// Node a with link b and its log, as a snapshot at 10 000 ms.
    private static NodeSnapshot snapshot() {
        final Broker.Stats stats = new Broker.Stats(3, 2, 1, 0L, 0L, 0L, 0L, 10L, 1L, 2L, 3L, 0, 0L, 0L, 0L);
        return new NodeSnapshot("a", 1883, 10_000L, stats, List.of(),
                List.of(new LinkState("b", false, 0L, 10L, 10L, 10L, 0L, 0L, 0, 64)),
                List.of(new LogView("b", 10L, 10L, 4_096L, 1)));
    }

    /// Publishes numbered from 1 up to `released`, made as they are read; `asked` is the last `after` read past.
    private static final class BurstSource implements LabSource {

        final AtomicLong released = new AtomicLong();
        final AtomicLong asked = new AtomicLong();
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public Chunk since(final long after, final int max) {
            calls.incrementAndGet();
            final long to = Math.min(released.get(), after + max);
            final List<Observation> events = new ArrayList<>((int) Math.max(0L, to - after));
            for (long s = after + 1; s <= to; s++) {
                events.add(new Published(s, s, "pub", "t/" + s, 0, false, 4, 1, null, "m", 0));
            }
            asked.set(to);
            return new Chunk(events, 0L, Math.max(after, to), false);
        }

        @Override
        public NodeSnapshot snapshot() {
            return LabDrainTest.snapshot();
        }
    }

    /// The given events once, then none.
    private static final class ScriptSource implements LabSource {

        private final List<Observation> events;

        ScriptSource(final List<Observation> events) {
            this.events = events;
        }

        @Override
        public Chunk since(final long after, final int max) {
            final List<Observation> out = events.stream().filter(e -> e.seq() > after).limit(max).toList();
            return new Chunk(out, 0L, out.isEmpty() ? after : out.getLast().seq(), false);
        }

        @Override
        public NodeSnapshot snapshot() {
            return LabDrainTest.snapshot();
        }
    }
}
