package dev.marregui.comando.frontear.bench;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.loop.ExecutorStop;
import dev.marregui.comando.core.loop.Threads;
import dev.marregui.comando.frontear.Frontear;
import dev.marregui.comando.frontear.alexander.Signer;
import dev.marregui.comando.frontear.protocol.MqttClient;
import dev.marregui.comando.frontear.protocol.Packet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// What a QoS 1 message costs through frontear, publisher to subscriber: across a link from node a to node b, and on
/// node a alone, so the link's share shows. One publisher on a keeps at most [#WINDOW] publishes unacknowledged; one
/// subscriber takes them at QoS 1 on b (the link) or on a (the node alone); [#MESSAGES] messages of 128 B and of
/// 4 KiB. Two runs per case. Saturated: the publisher sends as fast as its window lets it, and the throughput is the
/// messages over the time from the first send to the last arrival. Open-loop: the publisher follows a fixed schedule
/// of [#RATE] messages a second, and each message's latency runs from its scheduled send to its arrival, so a stall
/// is charged to every message it holds back, not only to the one that met it. Runs only with `COMANDO_BENCH=true`;
/// with `COMANDO_BENCH_JFR=<file>` as well, the link's runs are recorded with JFR's profile settings into that file.
@EnabledIfEnvironmentVariable(named = "COMANDO_BENCH", matches = "true")
class FrontearBench {

    private static final int WINDOW = 100;
    private static final int MESSAGES = 100_000;
    private static final int[] PAYLOADS = {128, 4_096};
    // below the link's saturated rate on the machine measured, so the open-loop run measures latency under load, not
    // a queue that grows without end
    private static final int RATE = 2_000;
    // messages through each path before any is measured, so the JIT has compiled the paths measured
    private static final int WARMUP = 20_000;
    // how long a run may take to deliver everything: far above a run at the rates measured
    private static final Duration WAIT = Duration.ofMinutes(3);
    private static final Duration SETUP = Duration.ofSeconds(15);
    // the first send's lead over the schedule's start, so the publisher's loop is running when the schedule begins
    private static final long LEAD_NANOS = TimeUnit.MILLISECONDS.toNanos(20);
    private static final String LINK = "bench/link";
    private static final String ALONE = "bench-alone/a";
    // this bench's run of 2026-11-19 before the link log's cursor save waited for the appends waiting on a force, on
    // an Apple M2 Max, 12 cpus, 32 GiB, macOS 27.2.1, JDK 25+36-LTS, APFS on the internal SSD; printed beside each
    // run's own table, so the two compare at a glance
    private static final String BEFORE = """
            | path | payload B | saturated msg/s | open-loop p50 ms | p99 ms | p99.9 ms | max ms |
            |---|---|---|---|---|---|---|
            | link a->b | 128 | 7522 | 14.94 | 23.15 | 27.59 | 38.83 |
            | link a->b | 4096 | 4145 | 15.23 | 25.09 | 34.59 | 47.55 |
            | node a alone | 128 | 114352 | 0.31 | 0.43 | 0.56 | 1.82 |
            | node a alone | 4096 | 97577 | 0.23 | 0.38 | 0.94 | 6.86 |
            """;

    @TempDir
    Path dir;

    /// Prints, for both payloads, the saturated throughput and the open-loop latency (p50, p99, p99.9, max) through
    /// the link and on node a alone; every message arrives, and every publish is acknowledged 0x00.
    @Test
    void linkAndNodeAloneThroughputAndOpenLoopLatency() throws Exception {
        final KeyPair pod = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        final Signer signer = new Signer(pod.getPrivate(), Signer.keyId(pod.getPublic()));
        final ExecutorService publisherCallbacks = Threads.strand("frontear-bench-publisher");
        final ExecutorService subscriberCallbacks = Threads.strand("frontear-bench-subscriber");
        final List<Packet.Publish> replies = new CopyOnWriteArrayList<>();
        final List<Row> rows = new ArrayList<>();
        final String jfr = System.getenv("COMANDO_BENCH_JFR");
        try (Frontear b = Frontear.start("b", 0, dir.resolve("b"), Set.of(pod.getPublic()), InstantSource.system());
                Frontear a = Frontear.start("a", 0, dir.resolve("a"), Set.of(pod.getPublic()), InstantSource.system());
                MqttClient publisher = new MqttClient(new MqttClient.Options("127.0.0.1", a.port(), "bench-pub", 0,
                        Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, null), new Sink(replies, 0), publisherCallbacks)) {
            publisher.connect().get(SETUP.toMillis(), TimeUnit.MILLISECONDS);
            publisher.subscribe("replies/#", 1).get(SETUP.toMillis(), TimeUnit.MILLISECONDS);
            final String link = "{\"id\":\"ab\",\"host\":\"127.0.0.1\",\"port\":" + b.port()
                    + ",\"out\":[\"bench/#\"],\"in\":[],\"hopLimit\":3}";
            assertThat(publisher.publishAcknowledged(signer.sign("$frontear/control/a/PutLink",
                    link.getBytes(StandardCharsets.UTF_8), "replies/ab", new byte[] {1}))
                    .get(SETUP.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo(Packet.SUCCESS);
            await().atMost(SETUP).until(() -> !replies.isEmpty());
            assertThat(new String(replies.getFirst().payload(), StandardCharsets.UTF_8)).contains("\"ok\":true");
            await().atMost(SETUP).until(() -> b.gateway().stats().connections() == 1);

            run(publisher, b.port(), LINK, PAYLOADS[0], WARMUP, 0L, subscriberCallbacks);
            run(publisher, a.port(), ALONE, PAYLOADS[0], WARMUP, 0L, subscriberCallbacks);
            try (Recording recording = jfr == null ? null : new Recording(Configuration.getConfiguration("profile"))) {
                if (recording != null) {
                    recording.start();
                }
                for (final int payload : PAYLOADS) {
                    rows.add(row("link a->b", publisher, b.port(), LINK, payload, subscriberCallbacks));
                }
                if (recording != null) {
                    recording.dump(Path.of(jfr));
                }
            }
            for (final int payload : PAYLOADS) {
                rows.add(row("node a alone", publisher, a.port(), ALONE, payload, subscriberCallbacks));
            }
        } finally {
            ExecutorStop.now("frontear-bench", publisherCallbacks, subscriberCallbacks);
        }
        print(rows);
        assertThat(rows).allSatisfy(r -> assertThat(r.messagesPerSecond()).isPositive());
    }

    // the saturated and the open-loop run of one case
    private static Row row(final String path, final MqttClient publisher, final int port, final String topic,
            final int payload, final ExecutorService callbacks) throws Exception {
        final Run saturated = run(publisher, port, topic, payload, MESSAGES, 0L, callbacks);
        final Run paced = run(publisher, port, topic, payload, MESSAGES, TimeUnit.SECONDS.toNanos(1) / RATE,
                callbacks);
        return new Row(path, payload, saturated.messagesPerSecond(), paced);
    }

    /// One run: a fresh subscriber on `port` for `topic`, then `count` messages of `payload` bytes from `publisher`,
    /// the i-th scheduled `intervalNanos` times i after the start (all at the start when it is 0), at most [#WINDOW]
    /// unacknowledged; waits for every arrival and every acknowledgement.
    private static Run run(final MqttClient publisher, final int port, final String topic, final int payload,
            final int count, final long intervalNanos, final ExecutorService callbacks) throws Exception {
        final Sink sink = new Sink(null, count);
        try (MqttClient subscriber = new MqttClient(new MqttClient.Options("127.0.0.1", port, "bench-sub-"
                + System.nanoTime(), 0, Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, null), sink, callbacks)) {
            subscriber.connect().get(SETUP.toMillis(), TimeUnit.MILLISECONDS);
            subscriber.subscribe(topic, 1).get(SETUP.toMillis(), TimeUnit.MILLISECONDS);
            final Semaphore window = new Semaphore(WINDOW);
            final AtomicInteger refused = new AtomicInteger();
            final long start = System.nanoTime() + LEAD_NANOS;
            for (int i = 0; i < count; i++) {
                final long due = start + i * intervalNanos;
                for (long left = due - System.nanoTime(); left > 0L; left = due - System.nanoTime()) {
                    LockSupport.parkNanos(left);
                }
                assertThat(window.tryAcquire(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
                publisher.publishAcknowledged(message(topic, i, payload)).whenComplete((code, e) -> {
                    if (e != null || code != Packet.SUCCESS) {
                        refused.incrementAndGet();
                    }
                    window.release();
                });
            }
            assertThat(sink.all.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            assertThat(window.tryAcquire(WINDOW, WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            assertThat(refused).hasValue(0);
            long last = start;
            final long[] latencyNanos = new long[count];
            for (int i = 0; i < count; i++) {
                last = Math.max(last, sink.arrivedNanos[i]);
                latencyNanos[i] = sink.arrivedNanos[i] - (start + i * intervalNanos);
            }
            Arrays.sort(latencyNanos);
            return new Run(count * 1e9 / (last - start), latencyNanos);
        }
    }

    private static Packet.Publish message(final String topic, final int seq, final int payload) {
        final byte[] body = new byte[payload];
        ByteBuffer.wrap(body).putInt(seq);
        return new Packet.Publish(topic, body, 1, false, false, 0, null, null);
    }

    private static void print(final List<Row> rows) {
        final StringBuilder out = new StringBuilder(String.format(Locale.ROOT, "frontear bench [machine=%s, cpus=%d,"
                + " jdk=%s, os=%s %s, window=%d, messages=%d, openLoopRate=%d/s]%n", System.getProperty("os.arch"),
                Runtime.getRuntime().availableProcessors(), Runtime.version(), System.getProperty("os.name"),
                System.getProperty("os.version"), WINDOW, MESSAGES, RATE));
        out.append("before, recorded 2026-11-19 on an Apple M2 Max:\n").append(BEFORE);
        out.append("after, this run:\n");
        out.append("| path | payload B | saturated msg/s | open-loop p50 ms | p99 ms | p99.9 ms | max ms |\n");
        out.append("|---|---|---|---|---|---|---|\n");
        for (final Row r : rows) {
            out.append(String.format(Locale.ROOT, "| %s | %d | %.0f | %.2f | %.2f | %.2f | %.2f |%n", r.path(),
                    r.payload(), r.messagesPerSecond(), r.paced().percentileMillis(0.50),
                    r.paced().percentileMillis(0.99), r.paced().percentileMillis(0.999),
                    r.paced().percentileMillis(1.0)));
        }
        System.out.print(out);
    }

    /** One case's figures: the saturated rate and the open-loop run. */
    private record Row(String path, int payload, double messagesPerSecond, Run paced) {
    }

    /** A run's rate and its latencies, sorted. */
    private record Run(double messagesPerSecond, long[] latencyNanos) {

        double percentileMillis(final double p) {
            final int at = (int) Math.min(latencyNanos.length - 1L, (long) Math.ceil(p * latencyNanos.length) - 1L);
            return latencyNanos[Math.max(0, at)] / 1e6;
        }
    }

    /**
     * A client's handler: with {@code into}, keeps every message; with {@code count}, takes the arrival time of each
     * sequence number below it, once, and counts {@code all} down. On the client's callbacks strand; {@code all}'s
     * count-down publishes the arrival times to the thread that awaits it.
     */
    private static final class Sink implements MqttClient.Handler {

        private final List<Packet.Publish> into;
        private final long[] arrivedNanos;
        private final boolean[] arrived;
        private final CountDownLatch all;

        Sink(final List<Packet.Publish> into, final int count) {
            this.into = into;
            this.arrivedNanos = new long[count];
            this.arrived = new boolean[count];
            this.all = new CountDownLatch(count);
        }

        @Override
        public void connected() {
        }

        @Override
        public void message(final Packet.Publish message) {
            final long now = System.nanoTime();
            if (into != null) {
                into.add(message);
                return;
            }
            if (message.payload().length < Integer.BYTES) {
                return;
            }
            final int seq = ByteBuffer.wrap(message.payload()).getInt();
            if (seq >= 0 && seq < arrived.length && !arrived[seq]) {
                arrived[seq] = true;
                arrivedNanos[seq] = now;
                all.countDown();
            }
        }

        @Override
        public Duration disconnected(final int attempts, final Throwable cause) {
            return null;
        }
    }
}
