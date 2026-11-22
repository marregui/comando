package dev.marregui.comando.frontear.bench;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.JsonNode;
import dev.marregui.comando.core.loop.ExecutorStop;
import dev.marregui.comando.core.loop.Queues;
import dev.marregui.comando.core.loop.Threads;
import dev.marregui.comando.frontear.Frontear;
import dev.marregui.comando.frontear.alexander.Signer;
import dev.marregui.comando.frontear.protocol.MqttClient;
import dev.marregui.comando.frontear.protocol.MqttException;
import dev.marregui.comando.frontear.protocol.Packet;
import dev.marregui.comando.frontear.protocol.PublishRecord;
import dev.marregui.comando.frontear.watch.Watch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// A sustained open-loop stress run of two linked frontear nodes in this JVM: node a links to node b over
/// `stress/#`; publishers on a send QoS 1 messages on a fixed schedule, each keeping at most a window of them
/// unacknowledged, and subscribers on b take them at QoS 1. Each message carries the time it was scheduled, so its
/// latency runs from that time to its arrival on b (G-13.11): a stall is charged to every message it holds back,
/// including the sends the window held back. The run is one or more steps of a rate each, for a length each; every
/// 10 s it prints the offered (scheduled), sent and delivered messages a second, the latency's p50, p99, p99.9 and
/// maximum, the link's backlog in messages and in bytes, its `reordered` count and the strands' queues; at the end, a
/// table with one row per step and the highest rate a step kept up with. `main` takes
/// `--minutes M --rate R[,R...] [--payload 128|4096|mix] [--publishers N] [--window W] [--warmup S] [--watch on|off]
/// --data DIR`; the test runs it only with `COMANDO_STRESS=true`, its arguments from `COMANDO_STRESS_ARGS` (default:
/// one minute at 2,000 msg/s), and asserts that every message arrived and every publish was acknowledged 0x00. With
/// `--watch on` both nodes' watches keep events and a drainer reads them every 250 ms, as the lab's view does, so the
/// run measures what watching costs the publishers. The link's id is b, the node it reaches, and both nodes refuse a
/// link client aimed at another node.
@EnabledIfEnvironmentVariable(named = "COMANDO_STRESS", matches = "true")
class FrontearStress {

    private static final String DEFAULT_ARGS = "--minutes 1 --rate 2000";
    // how long the run waits after the last send for the backlog to drain: a minute of backlog at the knee
    private static final Duration DRAIN = Duration.ofSeconds(120);
    private static final int HEADER_BYTES = Long.BYTES + 2 * Integer.BYTES;
    // a step keeps up when it delivers this share of its offered rate and ends with under a second of backlog
    private static final double KEEPS_UP = 0.98;
    private static final int LARGE = 4_096;
    // the first send's lead over the schedule's start, so the publishers' loops run when the schedule begins
    private static final long LEAD_NANOS = TimeUnit.MILLISECONDS.toNanos(200);
    private static final String LINK = "b";
    private static final Duration REPORT_EVERY = Duration.ofSeconds(10);
    private static final Duration SETUP = Duration.ofSeconds(15);
    private static final int SMALL = 128;
    private static final String STATE = "$frontear/state/a/links";
    // how often the drainer reads the watches: the lab's view's poll (ADR 0187 §2.3)
    private static final Duration WATCH_EVERY = Duration.ofMillis(250);
    // how long a publisher may wait for its window: far above any acknowledgement at the rates run, so reaching it
    // means the run is stuck, not slow
    private static final Duration WINDOW_WAIT = Duration.ofSeconds(60);

    @TempDir
    Path dir;

    /// Runs the stress run `args` describe and prints its lines and its table; exits 0 when it ran to the end.
    public static void main(final String[] args) throws Exception {
        final Options options = Options.parse(args);
        System.out.printf(Locale.ROOT, "frontear stress [pid=%d, machine=%s, cpus=%d, jdk=%s, os=%s %s, %s]%n",
                ProcessHandle.current().pid(), System.getProperty("os.arch"),
                Runtime.getRuntime().availableProcessors(), Runtime.version(), System.getProperty("os.name"),
                System.getProperty("os.version"), options);
        final Totals totals = run(options);
        System.out.printf(Locale.ROOT, "totals [sent=%d, arrived=%d, refused=%d, failed=%d, outOfOrder=%d,"
                + " wallSeconds=%.1f]%n", totals.sent(), totals.arrived(), totals.refused(), totals.failed(),
                totals.outOfOrder(), totals.wallNanos() / 1e9);
    }

    /// Runs the stress run `COMANDO_STRESS_ARGS` describes (one minute at 2,000 msg/s by default) with its logs in a
    /// temporary folder; every message sent arrives on b, and every publish is acknowledged 0x00.
    @Test
    void everyMessageScheduledArrivesAndIsAcknowledged() throws Exception {
        final String env = System.getenv("COMANDO_STRESS_ARGS");
        final String args = (env == null || env.isBlank() ? DEFAULT_ARGS : env) + " --data " + dir;
        final Totals totals = run(Options.parse(args.strip().split("\\s+")));
        assertThat(totals.failed()).isZero();
        assertThat(totals.refused()).isZero();
        assertThat(totals.arrived()).isGreaterThanOrEqualTo(totals.sent());
    }

    private static Totals run(final Options o) throws Exception {
        final long wallStart = System.nanoTime();
        final KeyPair pod = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        final Signer signer = new Signer(pod.getPrivate(), Signer.keyId(pod.getPublic()));
        final List<ExecutorService> strands = new ArrayList<>();
        final List<MqttClient> clients = new ArrayList<>();
        final Control control = new Control();
        final ExecutorService controlCallbacks = Threads.strand("frontear-stress-control");
        strands.add(controlCallbacks);
        try (Frontear b = Frontear.start("b", 0, o.data().resolve("b"), Set.of(pod.getPublic()),
                InstantSource.system(), true);
                Frontear a = Frontear.start("a", 0, o.data().resolve("a"), Set.of(pod.getPublic()),
                        InstantSource.system(), true);
                MqttClient controller = new MqttClient(new MqttClient.Options("127.0.0.1", a.port(),
                        "stress-control", 0, Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, null), control,
                        controlCallbacks)) {
            controller.connect().get(SETUP.toMillis(), TimeUnit.MILLISECONDS);
            controller.subscribe("replies/#", 1).get(SETUP.toMillis(), TimeUnit.MILLISECONDS);
            controller.subscribe(STATE, 1).get(SETUP.toMillis(), TimeUnit.MILLISECONDS);
            final String link = "{\"id\":\"" + LINK + "\",\"host\":\"127.0.0.1\",\"port\":" + b.port()
                    + ",\"out\":[\"stress/#\"],\"in\":[],\"hopLimit\":3}";
            assertThat(controller.publishAcknowledged(signer.sign("$frontear/control/a/PutLink",
                    link.getBytes(StandardCharsets.UTF_8), "replies/" + LINK, new byte[] {1}))
                    .get(SETUP.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo(Packet.SUCCESS);
            await().atMost(SETUP).until(() -> control.reply != null);
            assertThat(control.reply).contains("\"ok\":true");
            await().atMost(SETUP).until(() -> b.gateway().stats().connections() == 1);

            final List<Sink> sinks = new ArrayList<>();
            for (int j = 0; j < o.publishers(); j++) {
                final Sink sink = new Sink(o.publishers());
                final ExecutorService callbacks = Threads.strand("frontear-stress-sub-" + j);
                strands.add(callbacks);
                final MqttClient subscriber = new MqttClient(new MqttClient.Options("127.0.0.1", b.port(),
                        "stress-sub-" + j, 0, Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, null), sink, callbacks);
                clients.add(subscriber);
                subscriber.connect().get(SETUP.toMillis(), TimeUnit.MILLISECONDS);
                subscriber.subscribe("stress/" + j + "/#", 1).get(SETUP.toMillis(), TimeUnit.MILLISECONDS);
                sinks.add(sink);
            }
            final List<Publisher> publishers = new ArrayList<>();
            for (int i = 0; i < o.publishers(); i++) {
                final ExecutorService callbacks = Threads.strand("frontear-stress-pub-" + i);
                strands.add(callbacks);
                final MqttClient client = new MqttClient(new MqttClient.Options("127.0.0.1", a.port(),
                        "stress-pub-" + i, 0, Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, null), new Sink(0), callbacks);
                clients.add(client);
                client.connect().get(SETUP.toMillis(), TimeUnit.MILLISECONDS);
                publishers.add(new Publisher(i, client, o));
            }
            final Report report = new Report(o, sinks, publishers, control, a);
            final List<Step> steps = o.steps(System.nanoTime() + LEAD_NANOS);
            report.header(steps);
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final long sendsEnded;
            try (Drainer _ = Drainer.start(o.watch(), a.watch(), b.watch())) {
                final List<Thread> forks = new ArrayList<>();
                for (final Publisher p : publishers) {
                    forks.add(Threads.fork("frontear-stress-send-" + p.index, () -> {
                        try {
                            p.send(steps);
                        } catch (final InterruptedException _) {
                            Thread.currentThread().interrupt();
                        } catch (final RuntimeException | MqttException e) {
                            failure.compareAndSet(null, e);
                        }
                    }));
                }
                report.follow(steps, forks, failure);
                sendsEnded = System.nanoTime();
                for (final Thread fork : forks) {
                    if (!fork.join(WINDOW_WAIT)) {
                        throw new IllegalStateException("publisher did not end [thread=" + fork.getName() + ']');
                    }
                }
                report.drain();
            }
            report.table(steps);
            if (failure.get() != null) {
                throw new IllegalStateException("stress run failed", failure.get());
            }
            System.out.printf(Locale.ROOT, "stress wall-clock [scheduled=%.1fs, drain=%.1fs]%n",
                    (sendsEnded - steps.getFirst().startNanos()) / 1e9, (System.nanoTime() - sendsEnded) / 1e9);
            return report.totals(System.nanoTime() - wallStart);
        } finally {
            for (final MqttClient client : clients) {
                client.close();
            }
            ExecutorStop.now("frontear-stress", strands.toArray(ExecutorService[]::new));
        }
    }

    /// The bytes a payload of `size` takes in the link's log, header included: how backlog messages become bytes.
    private static int recordBytes(final int size) {
        try {
            return PublishRecord.encode(new Packet.Publish("stress/0/0", new byte[size], 1, false, false, 0, null,
                    null).withPacketId(1)).length;
        } catch (final MqttException e) {
            throw new IllegalStateException("cannot encode a stress record [size=" + size + ']', e);
        }
    }

    /// What the command line asks: a step per rate, each `minutes` long, after a warm-up at the first rate.
    private record Options(double minutes, List<Integer> rates, String payload, int publishers, int window,
            int warmupSeconds, boolean watch, Path data) {

        private static final int DEFAULT_PUBLISHERS = 4;
        // long enough for the JIT to compile the paths measured at the rates run (the bench's 20,000 messages)
        private static final int DEFAULT_WARMUP_SECONDS = 10;
        // as the bench's publisher: enough in flight to cover a force, few enough that the link's window is the bound
        private static final int DEFAULT_WINDOW = 100;

        static Options parse(final String[] args) {
            double minutes = 1;
            List<Integer> rates = List.of(2_000);
            String payload = "128";
            int publishers = DEFAULT_PUBLISHERS;
            int window = DEFAULT_WINDOW;
            int warmup = DEFAULT_WARMUP_SECONDS;
            String watch = "off";
            Path data = null;
            for (int i = 0; i + 1 < args.length; i += 2) {
                final String value = args[i + 1];
                switch (args[i]) {
                    case "--minutes" -> minutes = Double.parseDouble(value);
                    case "--rate" -> rates = Arrays.stream(value.split(",")).map(Integer::valueOf).toList();
                    case "--payload" -> payload = value;
                    case "--publishers" -> publishers = Integer.parseInt(value);
                    case "--window" -> window = Integer.parseInt(value);
                    case "--warmup" -> warmup = Integer.parseInt(value);
                    case "--watch" -> watch = value;
                    case "--data" -> data = Path.of(value);
                    default -> throw new IllegalArgumentException("unknown option [option=" + args[i] + ']');
                }
            }
            if (args.length % 2 != 0 || data == null || minutes <= 0 || publishers < 1 || window < 1 || warmup < 0
                    || !Set.of("128", "4096", "mix").contains(payload) || rates.stream().anyMatch(r -> r < 1)
                    || !Set.of("on", "off").contains(watch)) {
                throw new IllegalArgumentException("usage: --minutes M --rate R[,R...] [--payload 128|4096|mix]"
                        + " [--publishers N] [--window W] [--warmup S] [--watch on|off] --data DIR [args="
                        + String.join(" ", args) + ']');
            }
            return new Options(minutes, rates, payload, publishers, window, warmup, "on".equals(watch), data);
        }

        /// The payload of a publisher's `seq`-th message: half of each size for `mix`, alternating.
        int size(final long seq) {
            return switch (payload) {
                case "128" -> SMALL;
                case "4096" -> LARGE;
                default -> seq % 2 == 0 ? SMALL : LARGE;
            };
        }

        /// The log bytes of one message, on average over the mix.
        double meanRecordBytes() {
            return switch (payload) {
                case "128" -> recordBytes(SMALL);
                case "4096" -> recordBytes(LARGE);
                default -> (recordBytes(SMALL) + recordBytes(LARGE)) / 2.0;
            };
        }

        /// The steps from `startNanos` on: the warm-up, when asked, then one per rate; each a whole number of reports.
        List<Step> steps(final long startNanos) {
            final List<Step> steps = new ArrayList<>();
            final long every = REPORT_EVERY.toNanos();
            long at = startNanos;
            if (warmupSeconds > 0) {
                final long length = Math.max(1, Math.round(TimeUnit.SECONDS.toNanos(warmupSeconds) / (double) every))
                        * every;
                steps.add(new Step("warmup", rates.getFirst(), at, length));
                at += length;
            }
            final long length = Math.max(1, Math.round(minutes * 60e9 / every)) * every;
            for (int k = 0; k < rates.size(); k++) {
                steps.add(new Step("step" + (k + 1), rates.get(k), at, length));
                at += length;
            }
            return steps;
        }

    }

    /// One step of the schedule: `rate` messages a second over all publishers, from its start for its length.
    private record Step(String name, int rate, long startNanos, long lengthNanos) {

        long endNanos() {
            return startNanos + lengthNanos;
        }
    }

    /// What the run did in all: for the test's assertions and the last line.
    private record Totals(long sent, long arrived, long refused, long failed, long outOfOrder, long wallNanos) {
    }

    /// A latency histogram in nanoseconds, log-linear: 64 sub-buckets per power of two, so a percentile is read to
    /// within 1/64 of its value, above; the maximum is exact. Not thread-safe: its owner guards it.
    private static final class Histogram {

        private static final int SUB_BITS = 6;
        private static final int SUB = 1 << SUB_BITS;
        private static final int BUCKETS = (Long.SIZE - SUB_BITS) * SUB;

        private final long[] counts = new long[BUCKETS];
        private long count;
        private long max;

        void record(final long nanos) {
            final long v = Math.max(0, nanos);
            counts[index(v)]++;
            count++;
            max = Math.max(max, v);
        }

        void add(final Histogram other) {
            for (int i = 0; i < BUCKETS; i++) {
                counts[i] += other.counts[i];
            }
            count += other.count;
            max = Math.max(max, other.max);
        }

        void clear() {
            Arrays.fill(counts, 0);
            count = 0;
            max = 0;
        }

        long count() {
            return count;
        }

        /// The value at quantile `p` in milliseconds, the bucket's upper end; the exact maximum for 1; 0 when empty.
        double millis(final double p) {
            if (count == 0) {
                return 0;
            }
            if (p >= 1) {
                return max / 1e6;
            }
            final long rank = Math.max(1, (long) Math.ceil(p * count));
            long seen = 0;
            for (int i = 0; i < BUCKETS; i++) {
                seen += counts[i];
                if (seen >= rank) {
                    return Math.min(max, upper(i)) / 1e6;
                }
            }
            return max / 1e6;
        }

        private static int index(final long v) {
            if (v < SUB) {
                return (int) v;
            }
            final int exponent = Long.SIZE - 1 - Long.numberOfLeadingZeros(v);
            return (exponent - SUB_BITS + 1) * SUB + (int) (v >>> (exponent - SUB_BITS)) - SUB;
        }

        private static long upper(final int index) {
            if (index < SUB) {
                return index;
            }
            final int shift = index / SUB - 1;
            final long mantissa = index % SUB + SUB;
            return ((mantissa + 1) << shift) - 1;
        }
    }

    /// A subscriber on b: per report, its arrivals, their latency from the time each was scheduled, and how many came
    /// before one of an earlier sequence number from the same publisher (reordered or sent again). Written on the
    /// client's callbacks strand, read by the report, both under this object's lock.
    private static final class Sink implements MqttClient.Handler {

        private final Histogram latency = new Histogram();
        private final long[] nextSeq;
        private long arrived;
        private long arrivedTotal;
        private long outOfOrder;

        Sink(final int publishers) {
            this.nextSeq = new long[publishers];
        }

        @Override
        public void connected() {
        }

        @Override
        public void message(final Packet.Publish message) {
            final long now = System.nanoTime();
            if (nextSeq.length == 0 || message.payload().length < HEADER_BYTES) {
                return;
            }
            final ByteBuffer header = ByteBuffer.wrap(message.payload());
            final long scheduledNanos = header.getLong();
            final int publisher = header.getInt();
            final int seq = header.getInt();
            synchronized (this) {
                latency.record(now - scheduledNanos);
                arrived++;
                arrivedTotal++;
                if (seq < nextSeq[publisher]) {
                    outOfOrder++;
                } else {
                    nextSeq[publisher] = seq + 1L;
                }
            }
        }

        @Override
        public Duration disconnected(final int attempts, final Throwable cause) {
            return null;
        }

        /// Adds this report's latencies into `into`, clears them, and returns the arrivals since the last call.
        synchronized long drainTo(final Histogram into) {
            into.add(latency);
            latency.clear();
            final long n = arrived;
            arrived = 0;
            return n;
        }

        synchronized long arrivedTotal() {
            return arrivedTotal;
        }

        synchronized long outOfOrder() {
            return outOfOrder;
        }
    }

    /// The control client's handler: the reply to the link's `PutLink`, and the link's latest state from a's
    /// retained state topic.
    private static final class Control implements MqttClient.Handler {

        /// Written on the control client's callbacks strand; read by the report on the run's thread.
        volatile JsonNode link;
        /// Written on the control client's callbacks strand; read by the run's thread.
        volatile String reply;

        @Override
        public void connected() {
        }

        @Override
        public void message(final Packet.Publish message) {
            if (STATE.equals(message.topic())) {
                for (final JsonNode l : Json.readTree(message.payload()).path("links")) {
                    if (LINK.equals(l.path("id").asString(""))) {
                        link = l;
                    }
                }
            } else {
                reply = new String(message.payload(), StandardCharsets.UTF_8);
            }
        }

        @Override
        public Duration disconnected(final int attempts, final Throwable cause) {
            return null;
        }

        long field(final String name) {
            final JsonNode l = link;
            return l == null ? -1 : l.path(name).asLong(-1);
        }
    }

    /// One publisher on a: its share of each step's schedule, a window of unacknowledged sends, and per report its
    /// sends, how late each left against its schedule, and its acknowledgements' latency from the schedule. Sends are
    /// written by its fork, acknowledgements on its client's callbacks strand, both read by the report, all under this
    /// object's lock.
    private static final class Publisher {

        private final Histogram ack = new Histogram();
        private final MqttClient client;
        private final int index;
        private final Histogram lag = new Histogram();
        private final Options options;
        private final Semaphore window;
        private long failed;
        private long refused;
        private long sent;
        private long sentTotal;

        Publisher(final int index, final MqttClient client, final Options options) {
            this.index = index;
            this.client = client;
            this.options = options;
            this.window = new Semaphore(options.window());
        }

        /// Sends this publisher's share of every step: the step's k-th message over all publishers is due at
        /// k / rate after its start, and this publisher sends those whose k is its index modulo the publishers.
        void send(final List<Step> steps) throws InterruptedException, MqttException {
            final String topic = "stress/" + index + "/" + index;
            final int n = options.publishers();
            int seq = 0;
            for (final Step step : steps) {
                final double intervalNanos = 1e9 / step.rate();
                for (long k = index; ; k += n) {
                    final long due = step.startNanos() + (long) (k * intervalNanos);
                    if (due - step.endNanos() >= 0) {
                        break;
                    }
                    for (long left = due - System.nanoTime(); left > 0; left = due - System.nanoTime()) {
                        LockSupport.parkNanos(left);
                    }
                    if (!window.tryAcquire(WINDOW_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                        throw new MqttException(Packet.QUOTA_EXCEEDED, "publisher window stuck [publisher=" + index
                                + ", window=" + options.window() + ", waitedMillis=" + WINDOW_WAIT.toMillis() + ']');
                    }
                    final byte[] body = new byte[options.size(seq)];
                    ByteBuffer.wrap(body).putLong(due).putInt(index).putInt(seq);
                    seq++;
                    final long left = System.nanoTime();
                    synchronized (this) {
                        sent++;
                        sentTotal++;
                        lag.record(left - due);
                    }
                    final CompletableFuture<Integer> acked = client.publishAcknowledged(new Packet.Publish(topic, body,
                            1, false, false, 0, null, null));
                    acked.whenComplete((code, e) -> {
                        final long now = System.nanoTime();
                        synchronized (this) {
                            if (e != null) {
                                failed++;
                            } else if (code != Packet.SUCCESS) {
                                refused++;
                            }
                            ack.record(now - due);
                        }
                        window.release();
                    });
                }
            }
        }

        /// Adds this report's acknowledgement and send-lag latencies into the arguments, clears them, and returns the
        /// sends since the last call.
        synchronized long drainTo(final Histogram acks, final Histogram lags) {
            acks.add(ack);
            ack.clear();
            lags.add(lag);
            lag.clear();
            final long n = sent;
            sent = 0;
            return n;
        }

        synchronized long sentTotal() {
            return sentTotal;
        }

        synchronized long refused() {
            return refused;
        }

        synchronized long failed() {
            return failed;
        }
    }

    /// The run's reports, on the run's thread alone: a line every {@link #REPORT_EVERY} on the schedule's own clock, so
    /// a step's lines are exactly its reports, and each step's figures merged from them.
    private static final class Report {

        private final Frontear a;
        private final List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
        private final Control control;
        private final List<StepFigures> figures = new ArrayList<>();
        private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        private final Options options;
        private final OperatingSystemMXBean os = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
        private final List<Publisher> publishers;
        private final double recordBytes;
        private final List<Sink> sinks;
        private long gcMillis;
        private long lastReportNanos;

        Report(final Options options, final List<Sink> sinks, final List<Publisher> publishers, final Control control,
                final Frontear a) {
            this.options = options;
            this.sinks = sinks;
            this.publishers = publishers;
            this.control = control;
            this.a = a;
            this.recordBytes = options.meanRecordBytes();
        }

        void header(final List<Step> steps) {
            final StringBuilder plan = new StringBuilder();
            for (final Step s : steps) {
                plan.append(String.format(Locale.ROOT, " %s=%d/s x %ds", s.name(), s.rate(), s.lengthNanos()
                        / 1_000_000_000L));
            }
            System.out.printf(Locale.ROOT, "schedule [%s, recordBytes=%.0f]%n", plan.toString().strip(),
                    recordBytes);
            System.out.println("t_s step offered/s sent/s delivered/s e2e_p50_ms e2e_p99_ms e2e_p999_ms e2e_max_ms"
                    + " ack_p99_ms ack_max_ms lag_max_ms backlog_msgs backlog_bytes reordered dropped refused failed"
                    + " out_of_order gc_ms heap_mib cpu_pct a_droppedFull queues(depth/peak)");
            gcMillis = gcMillis();
            lastReportNanos = steps.getFirst().startNanos();
            for (final Step s : steps) {
                figures.add(new StepFigures(s));
            }
        }

        /// Prints a line at every report of the schedule until its end, or until a publisher failed.
        void follow(final List<Step> steps, final List<Thread> forks, final AtomicReference<Throwable> failure) {
            final long every = REPORT_EVERY.toNanos();
            final long start = steps.getFirst().startNanos();
            final long end = steps.getLast().endNanos();
            for (long due = start + every; due - end <= 0 && failure.get() == null; due += every) {
                for (long left = due - System.nanoTime(); left > 0; left = due - System.nanoTime()) {
                    LockSupport.parkNanos(left);
                }
                final Step step = stepAt(steps, due - 1);
                line((due - start) / 1e9, step, figures.get(steps.indexOf(step)));
            }
            if (failure.get() != null) {
                for (final Thread fork : forks) {
                    fork.interrupt();
                }
            }
        }

        /// Reports every [#REPORT_EVERY] after the last send until every message accepted has arrived, or [#DRAIN]
        /// passed: a message refused at a's door never arrives, so waiting for it would only spend the run's time.
        void drain() {
            final long until = System.nanoTime() + DRAIN.toNanos();
            final StepFigures drain = new StepFigures(new Step("drain", 0, lastReportNanos, 0));
            while (arrivedTotal() < acceptedTotal() && System.nanoTime() - until < 0) {
                final long due = Math.min(until, System.nanoTime() + REPORT_EVERY.toNanos());
                // arrivals are read every 100 ms, so the wait ends as soon as the backlog is gone
                while (arrivedTotal() < acceptedTotal() && System.nanoTime() - due < 0) {
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));
                }
                line(-1, drain.step, drain);
            }
            System.out.printf(Locale.ROOT, "drain [sent=%d, accepted=%d, arrived=%d, missing=%d]%n", sentTotal(),
                    acceptedTotal(), arrivedTotal(), Math.max(0, acceptedTotal() - arrivedTotal()));
        }

        void table(final List<Step> steps) {
            final StringBuilder out = new StringBuilder(String.format(Locale.ROOT, "%nfrontear stress table"
                    + " [payload=%s, publishers=%d, window=%d, latency from the scheduled send to the arrival on b]%n",
                    options.payload(), options.publishers(), options.window()));
            out.append("| step | offered msg/s | sent msg/s | delivered msg/s | p50 ms | p99 ms | p99.9 ms | max ms"
                    + " | ack p99 ms | backlog at end msgs | backlog at end bytes | reordered | keeps up |\n");
            out.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
            int knee = 0;
            for (final StepFigures f : figures) {
                final double seconds = f.step.lengthNanos() / 1e9;
                final double delivered = f.arrived / seconds;
                final boolean keepsUp = delivered >= KEEPS_UP * f.step.rate() && f.sent / seconds >= KEEPS_UP
                        * f.step.rate() && f.backlogEnd >= 0 && f.backlogEnd < f.step.rate();
                if (keepsUp && !"warmup".equals(f.step.name())) {
                    knee = Math.max(knee, f.step.rate());
                }
                out.append(String.format(Locale.ROOT, "| %s | %d | %.0f | %.0f | %.2f | %.2f | %.2f | %.2f | %.2f"
                        + " | %d | %.0f | %d | %s |%n", f.step.name(), f.step.rate(), f.sent / seconds, delivered,
                        f.latency.millis(0.50), f.latency.millis(0.99), f.latency.millis(0.999),
                        f.latency.millis(1.0), f.ack.millis(0.99), f.backlogEnd, f.backlogEnd * recordBytes,
                        f.reorderedEnd, keepsUp ? "yes" : "no"));
            }
            out.append(String.format(Locale.ROOT, "highest rate kept up [payload=%s, msgPerSecond=%d]%n",
                    options.payload(), knee));
            System.out.print(out);
        }

        Totals totals(final long wallNanos) {
            long refused = 0;
            long failed = 0;
            for (final Publisher p : publishers) {
                refused += p.refused();
                failed += p.failed();
            }
            return new Totals(sentTotal(), arrivedTotal(), refused, failed, outOfOrder(), wallNanos);
        }

        private static Step stepAt(final List<Step> steps, final long nanos) {
            for (final Step s : steps) {
                if (nanos - s.endNanos() < 0) {
                    return s;
                }
            }
            return steps.getLast();
        }

        private void line(final double atSeconds, final Step step, final StepFigures into) {
            final long now = System.nanoTime();
            final double seconds = (now - lastReportNanos) / 1e9;
            lastReportNanos = now;
            final Histogram latency = new Histogram();
            final Histogram acks = new Histogram();
            final Histogram lags = new Histogram();
            long arrived = 0;
            for (final Sink s : sinks) {
                arrived += s.drainTo(latency);
            }
            long sent = 0;
            for (final Publisher p : publishers) {
                sent += p.drainTo(acks, lags);
            }
            final long queued = control.field("queued");
            final long reordered = control.field("reordered");
            final long gc = gcMillis();
            final StringBuilder queues = new StringBuilder();
            final List<Queues.Reading> readings = new ArrayList<>(Queues.read());
            readings.sort(Comparator.comparingInt(Queues.Reading::peak).reversed());
            for (final Queues.Reading r : readings.subList(0, Math.min(4, readings.size()))) {
                queues.append(r.name()).append(':').append(r.depth()).append('/').append(r.peak()).append(' ');
            }
            long refused = 0;
            long failed = 0;
            for (final Publisher p : publishers) {
                refused += p.refused();
                failed += p.failed();
            }
            System.out.printf(Locale.ROOT, "%.0f %s %d %.0f %.0f %.2f %.2f %.2f %.2f %.2f %.2f %.2f %d %.0f %d %d %d %d"
                    + " %d %d %d %.0f %d %s%n", atSeconds, step.name(), step.rate(), sent / seconds,
                    arrived / seconds, latency.millis(0.50), latency.millis(0.99), latency.millis(0.999),
                    latency.millis(1.0), acks.millis(0.99), acks.millis(1.0), lags.millis(1.0), queued,
                    queued * recordBytes, reordered, control.field("dropped"), refused, failed, outOfOrder(),
                    gc - gcMillis, memory.getHeapMemoryUsage().getUsed() >> 20, os.getProcessCpuLoad() * 100,
                    a.gateway().stats().droppedFull(), queues.toString().strip());
            System.out.flush();
            gcMillis = gc;
            into.latency.add(latency);
            into.ack.add(acks);
            into.arrived += arrived;
            into.sent += sent;
            into.backlogEnd = queued;
            into.reorderedEnd = reordered;
        }

        private long arrivedTotal() {
            long n = 0;
            for (final Sink s : sinks) {
                n += s.arrivedTotal();
            }
            return n;
        }

        private long sentTotal() {
            long n = 0;
            for (final Publisher p : publishers) {
                n += p.sentTotal();
            }
            return n;
        }

        /// The messages sent that a refused or failed acknowledgement did not turn away: those that should arrive.
        private long acceptedTotal() {
            long n = 0;
            for (final Publisher p : publishers) {
                n += p.sentTotal() - p.refused() - p.failed();
            }
            return n;
        }

        private long outOfOrder() {
            long n = 0;
            for (final Sink s : sinks) {
                n += s.outOfOrder();
            }
            return n;
        }

        private long gcMillis() {
            long n = 0;
            for (final GarbageCollectorMXBean c : collectors) {
                n += Math.max(0, c.getCollectionTime());
            }
            return n;
        }
    }

    /// The watches' reader for `--watch on`: turns them on and reads each from where it left off every
    /// [#WATCH_EVERY], at most a ring at a time, counting the events read and those the rings overwrote first. For
    /// `--watch off` it does nothing, and the watches stay off.
    private static final class Drainer implements AutoCloseable {

        private final List<Watch> watches;
        private final long[] last;
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final Thread thread;
        private long events;
        private long missed;

        private Drainer(final List<Watch> watches) {
            this.watches = watches;
            this.last = new long[watches.size()];
            this.thread = watches.isEmpty() ? null : Threads.fork("frontear-stress-watch", this::loop);
        }

        static Drainer start(final boolean on, final Watch... watches) {
            if (!on) {
                return new Drainer(List.of());
            }
            for (final Watch w : watches) {
                w.on(true);
            }
            return new Drainer(List.of(watches));
        }

        /// Ends the reads, waits for the drainer and prints what it read; its counts are its thread's until the join.
        /// An interrupt while waiting is kept on the run's thread, and the drainer, told to stop, ends on its own.
        @Override
        public void close() {
            if (thread == null) {
                return;
            }
            stopped.set(true);
            thread.interrupt();
            try {
                if (!thread.join(WINDOW_WAIT)) {
                    throw new IllegalStateException("watch drainer did not end [thread=" + thread.getName() + ']');
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted waiting for the watch drainer", e);
            }
            for (final Watch w : watches) {
                w.on(false);
            }
            System.out.printf(Locale.ROOT, "watch [events=%d, missed=%d, everyMillis=%d]%n", events, missed,
                    WATCH_EVERY.toMillis());
        }

        private void loop() {
            while (!stopped.get()) {
                for (int i = 0; i < watches.size(); i++) {
                    final Watch.Batch batch = watches.get(i).since(last[i], Frontear.WATCH_CAPACITY);
                    events += batch.events().size();
                    missed += batch.missed();
                    last[i] = batch.last();
                }
                LockSupport.parkNanos(WATCH_EVERY.toNanos());
            }
        }
    }

    /// A step's figures, merged from its reports; on the report's thread alone.
    private static final class StepFigures {

        private final Histogram ack = new Histogram();
        private final Histogram latency = new Histogram();
        private final Step step;
        private long arrived;
        private long backlogEnd = -1;
        private long reorderedEnd = -1;
        private long sent;

        StepFigures(final Step step) {
            this.step = step;
        }
    }
}
