package dev.marregui.comando.frontear.store;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// What a force of the link log's files costs on this machine's disk, for the force the log makes and the changes
/// that could shorten it, each run interleaved with the others so a drift in the machine's load reaches every
/// variant alike. Three levels: one file written at explicit positions and forced after each write, as a segment is;
/// the link log itself under a full window of appends, timing its forces and the forcer's time between a force's
/// return and the next round's first write, which is where the completions of the appends it covered run; and the
/// link log fed open-loop at the live link's rate, for the append-to-durable latency under load. A force and a gap
/// are timed alone, one after the other: a device's latency per force, not a latency under load. Prints the JDK, the
/// load average of each run and a line per variant and run. Runs only with `-Dfrontear.bench=true`.
@EnabledIfSystemProperty(named = "frontear.bench", matches = "true")
class LogForceBench {

    private static final int RUNS = 5;
    private static final int FORCES = 400;
    private static final int WARMUP = 20;
    // one 128-byte record with its frame, and a round of about 900 of them, the live link's at 120,000 msg/s
    private static final int[] WRITE_BYTES = {144, 131_072};
    private static final int RECORD = 128;
    // about 450 rounds a run at a 4 ms force, so a run's p99 rests on a handful of rounds, not one
    private static final int RECORDS = 1_000_000;
    // the appends in flight: a round takes about half of them (measured), so the forcer always finds a round due
    private static final int WINDOW = 4_096;
    // how long one append may wait for its force: far above a disk's force
    private static final long WAIT_SECONDS = 30;
    private static final int EVENTS = 1 << 20;
    // the live alice-to-bob link's rate on JDK 25.0.5.1, ADR 0193 section 3.1
    private static final int OPEN_LOOP_RATE = 120_000;
    private static final int OPEN_LOOP_RECORDS = 360_000;
    // the first appends, before the JIT and the first segment settle, are left out of the distribution
    private static final int OPEN_LOOP_SKIP = 36_000;

    @TempDir
    Path dir;

    /// One file written and forced: `force(true)` on a file that grows (the log's force today), `force(false)` on a
    /// file that grows, and `force(true)` on a file written with zeros to its full length and forced once before, so
    /// a write grows nothing; at one record and at one loaded round per force.
    @Test
    void fileForceLatencyByVariant() throws Exception {
        final Map<String, List<Stats>> byVariant = new LinkedHashMap<>();
        System.out.println("log force bench: jdk=" + Runtime.version() + " os=" + System.getProperty("os.name") + ' '
                + System.getProperty("os.version") + " arch=" + System.getProperty("os.arch"));
        for (int run = 0; run < RUNS; run++) {
            final double load = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
            for (final int bytes : WRITE_BYTES) {
                // rotate the order per run, so no variant always runs first after a size change
                for (int k = 0; k < FileVariant.values().length; k++) {
                    final FileVariant v = FileVariant.values()[(k + run) % FileVariant.values().length];
                    final Path file = dir.resolve(v.name() + '-' + bytes + '-' + run);
                    final Stats stats = fileRun(file, v, bytes, load);
                    byVariant.computeIfAbsent(v.name() + " bytes=" + bytes, _ -> new ArrayList<>()).add(stats);
                    System.out.println("log force run: run=" + run + " variant=" + v.name() + " bytes=" + bytes + ' '
                            + stats);
                }
            }
        }
        summarise(byVariant);
        assertThat(byVariant).isNotEmpty();
    }

    /// The link log under a full window of appends: its force latency, records per second, and the forcer's time
    /// between a force's return and the next round's first write, with the appends awaited plainly and with a
    /// dependant on each as a link chains one; that time bounds what completing off the forcer could give back.
    @Test
    void linkLogForcerTimeBetweenForces() throws Exception {
        final Map<String, List<Stats>> byVariant = new LinkedHashMap<>();
        for (int run = 0; run < RUNS; run++) {
            final double load = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
            for (int k = 0; k < 2; k++) {
                final boolean dependants = (k + run) % 2 == 1;
                final String name = dependants ? "LINKLOG_DEPENDANTS" : "LINKLOG_PLAIN";
                final TimingFiles files = new TimingFiles();
                final long startNanos = System.nanoTime();
                try (LinkLog log = LinkLog.open(files, dir.resolve(name + '-' + run),
                        new LogOptions(64L << 20, 1L << 32, Duration.ZERO, 64L << 20))) {
                    load(log, dependants);
                }
                final long elapsedNanos = System.nanoTime() - startNanos;
                final Stats force = files.forces(load, RECORDS * 1e9 / elapsedNanos);
                final Stats gap = files.gaps(load);
                byVariant.computeIfAbsent(name + " force", _ -> new ArrayList<>()).add(force);
                byVariant.computeIfAbsent(name + " between", _ -> new ArrayList<>()).add(gap);
                System.out.println("log force run: run=" + run + " variant=" + name + " force " + force);
                System.out.println("log force run: run=" + run + " variant=" + name + " between " + gap);
            }
        }
        summarise(byVariant);
        assertThat(byVariant).isNotEmpty();
    }

    /// The link log at the live link's rate, open-loop: appends issued on a schedule that does not wait for them,
    /// each with a dependant as a link chains one, its latency taken from when the schedule meant to issue it to when
    /// its future completed, so a stall of the forcer shows in every append it delayed.
    @Test
    void linkLogOpenLoopAppendLatency() throws Exception {
        final Map<String, List<Stats>> byVariant = new LinkedHashMap<>();
        for (int run = 0; run < RUNS; run++) {
            final double load = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
            final TimingFiles files = new TimingFiles();
            final long[] nanos = new long[OPEN_LOOP_RECORDS];
            final long startNanos = System.nanoTime();
            try (LinkLog log = LinkLog.open(files, dir.resolve("open-" + run),
                    new LogOptions(64L << 20, 1L << 32, Duration.ZERO, 64L << 20))) {
                openLoop(log, nanos);
            }
            final long elapsedNanos = System.nanoTime() - startNanos;
            final Stats latency = Stats.of(Arrays.copyOfRange(nanos, OPEN_LOOP_SKIP, OPEN_LOOP_RECORDS),
                    OPEN_LOOP_RECORDS - OPEN_LOOP_SKIP, load, OPEN_LOOP_RECORDS * 1e9 / elapsedNanos);
            final Stats force = files.forces(load, OPEN_LOOP_RECORDS * 1e9 / elapsedNanos);
            final Stats gap = files.gaps(load);
            byVariant.computeIfAbsent("OPEN_LOOP append", _ -> new ArrayList<>()).add(latency);
            byVariant.computeIfAbsent("OPEN_LOOP force", _ -> new ArrayList<>()).add(force);
            byVariant.computeIfAbsent("OPEN_LOOP between", _ -> new ArrayList<>()).add(gap);
            System.out.println("log force run: run=" + run + " variant=OPEN_LOOP append " + latency);
            System.out.println("log force run: run=" + run + " variant=OPEN_LOOP force " + force);
            System.out.println("log force run: run=" + run + " variant=OPEN_LOOP between " + gap);
        }
        summarise(byVariant);
        assertThat(byVariant).isNotEmpty();
    }

    // issues OPEN_LOOP_RECORDS appends at OPEN_LOOP_RATE, each one whose time has come at once, and waits for them
    private static void openLoop(final LinkLog log, final long[] nanos) throws Exception {
        final byte[] record = new byte[RECORD];
        final long intervalNanos = 1_000_000_000L / OPEN_LOOP_RATE;
        final CountDownLatch left = new CountDownLatch(OPEN_LOOP_RECORDS);
        final AtomicLong durable = new AtomicLong();
        final long startNanos = System.nanoTime();
        int i = 0;
        while (i < OPEN_LOOP_RECORDS) {
            while (i < OPEN_LOOP_RECORDS && System.nanoTime() - (startNanos + i * intervalNanos) >= 0L) {
                final int slot = i;
                final long intendedNanos = startNanos + i * intervalNanos;
                final CompletableFuture<Integer> done = new CompletableFuture<>();
                log.append(record).handle((at, e) -> {
                    // as a link chains one: the durable mark moved, a future the publisher waits on completed
                    if (e == null) {
                        durable.accumulateAndGet(at + 1, Math::max);
                    }
                    done.complete(e == null ? 0 : 1);
                    // written before the count down the main thread awaits
                    nanos[slot] = System.nanoTime() - intendedNanos;
                    left.countDown();
                    return null;
                });
                i++;
            }
            LockSupport.parkNanos(intervalNanos);
        }
        assertThat(left.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
    }

    private static Stats fileRun(final Path file, final FileVariant v, final int bytes, final double load)
            throws IOException {
        final long length = (long) (WARMUP + FORCES) * bytes;
        final long[] nanos = new long[FORCES];
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            if (v == FileVariant.PREALLOCATED_FULL) {
                final ByteBuffer zeros = ByteBuffer.allocate(1 << 20);
                for (long at = 0L; at < length; at += zeros.capacity()) {
                    write(channel, zeros.clear().limit((int) Math.min(zeros.capacity(), length - at)), at);
                }
                channel.force(true);
            }
            final ByteBuffer src = ByteBuffer.allocate(bytes);
            Arrays.fill(src.array(), (byte) 0x5a);
            final long startNanos = System.nanoTime();
            for (int i = 0; i < WARMUP + FORCES; i++) {
                final long t0 = System.nanoTime();
                write(channel, src.clear(), (long) i * bytes);
                channel.force(v != FileVariant.GROWING_DATA);
                if (i >= WARMUP) {
                    nanos[i - WARMUP] = System.nanoTime() - t0;
                }
            }
            final long elapsedNanos = System.nanoTime() - startNanos;
            return Stats.of(nanos, nanos.length, load, (WARMUP + FORCES) * 1e9 / elapsedNanos);
        }
    }

    private static void write(final FileChannel channel, final ByteBuffer src, final long position)
            throws IOException {
        long at = position;
        while (src.hasRemaining()) {
            at += channel.write(src, at);
        }
    }

    // keeps WINDOW appends in flight: each append past the window first awaits the one WINDOW before it
    private static void load(final LinkLog log, final boolean dependants) throws Exception {
        final byte[] record = new byte[RECORD];
        final CompletableFuture<?>[] ring = new CompletableFuture<?>[WINDOW];
        final AtomicLong durable = new AtomicLong();
        for (int i = 0; i < RECORDS; i++) {
            final int slot = i % WINDOW;
            if (ring[slot] != null) {
                ring[slot].get(WAIT_SECONDS, TimeUnit.SECONDS);
            }
            final CompletableFuture<Long> appended = log.append(record);
            if (dependants) {
                // as a link chains one: the durable mark moved, a future the publisher waits on completed
                final CompletableFuture<Integer> done = new CompletableFuture<>();
                appended.handle((at, e) -> {
                    if (e == null) {
                        durable.accumulateAndGet(at + 1, Math::max);
                        done.complete(0);
                    } else {
                        done.complete(1);
                    }
                    return null;
                });
                ring[slot] = done;
            } else {
                ring[slot] = appended;
            }
        }
        for (final CompletableFuture<?> f : ring) {
            if (f != null) {
                f.get(WAIT_SECONDS, TimeUnit.SECONDS);
            }
        }
    }

    private static void summarise(final Map<String, List<Stats>> byVariant) {
        for (final Map.Entry<String, List<Stats>> e : byVariant.entrySet()) {
            final List<Stats> runs = e.getValue();
            System.out.println(String.format(Locale.ROOT, "log force summary: variant=%s runs=%d p50us=%s p99us=%s"
                    + " maxUs=%s perSecond=%s load=%s", e.getKey(), runs.size(),
                    spread(runs, Stats::p50Micros), spread(runs, Stats::p99Micros), spread(runs, Stats::maxMicros),
                    spread(runs, Stats::perSecond), spread(runs, Stats::load)));
        }
    }

    // the median of the runs with their least and greatest
    private static String spread(final List<Stats> runs, final Field field) {
        final double[] values = new double[runs.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = field.of(runs.get(i));
        }
        Arrays.sort(values);
        return String.format(Locale.ROOT, "%.0f[%.0f..%.0f]", values[values.length / 2], values[0],
                values[values.length - 1]);
    }

    /// The candidates for one file's force.
    private enum FileVariant {
        GROWING_FULL,
        GROWING_DATA,
        PREALLOCATED_FULL
    }

    /// Reads one figure of a run.
    @FunctionalInterface
    private interface Field {
        double of(Stats stats);
    }

    /// One run's distribution of op times, with its rate and the load average at its start; the rate of a gap run is
    /// the share of the run's time, in percent, the forcer spent between a force and the next write.
    private record Stats(int n, double p50Micros, double p99Micros, double maxMicros, double perSecond, double load) {

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "n=%d p50us=%.0f p99us=%.0f maxUs=%.0f perSecond=%.0f load=%.2f", n,
                    p50Micros, p99Micros, maxMicros, perSecond, load);
        }

        static Stats of(final long[] nanos, final int n, final double load, final double perSecond) {
            final long[] sorted = Arrays.copyOf(nanos, n);
            Arrays.sort(sorted);
            if (n == 0) {
                return new Stats(0, 0.0, 0.0, 0.0, perSecond, load);
            }
            return new Stats(n, sorted[n / 2] / 1e3, sorted[(int) Math.min(n - 1L, n * 99L / 100L)] / 1e3,
                    sorted[n - 1] / 1e3, perSecond, load);
        }
    }

    /// The disk, with every write and force of a segment timed in the order the forcer made them. Only the forcer
    /// writes and forces once the log is open, and the figures are read after the close, which joins it.
    private static final class TimingFiles implements LogFiles {

        private static final byte WRITE = 1;
        private static final byte FORCE = 2;
        private static final byte FOLDER = 3;

        private final byte[] kinds = new byte[EVENTS];
        private final long[] starts = new long[EVENTS];
        private final long[] ends = new long[EVENTS];
        private int events;

        @Override
        public void createDirectories(final Path dir) throws IOException {
            DiskFiles.INSTANCE.createDirectories(dir);
        }

        @Override
        public List<Path> list(final Path dir) throws IOException {
            return DiskFiles.INSTANCE.list(dir);
        }

        @Override
        public Writer create(final Path file) throws IOException {
            return new TimedWriter(DiskFiles.INSTANCE.create(file));
        }

        @Override
        public Writer open(final Path file) throws IOException {
            return new TimedWriter(DiskFiles.INSTANCE.open(file));
        }

        @Override
        public <T> T read(final Path file, final Body<T> body) throws IOException {
            return DiskFiles.INSTANCE.read(file, body);
        }

        @Override
        public Mapping map(final Path file) throws IOException {
            return DiskFiles.INSTANCE.map(file);
        }

        @Override
        public void delete(final Path file) throws IOException {
            DiskFiles.INSTANCE.delete(file);
        }

        @Override
        public void move(final Path from, final Path to) throws IOException {
            DiskFiles.INSTANCE.move(from, to);
        }

        @Override
        public void forceFolder(final Path dir) throws IOException {
            final long t0 = System.nanoTime();
            DiskFiles.INSTANCE.forceFolder(dir);
            record(FOLDER, t0);
        }

        // the durations of the forces, with the run's records per second
        Stats forces(final double load, final double perSecond) {
            final long[] nanos = new long[events];
            int n = 0;
            for (int i = 0; i < events; i++) {
                if (kinds[i] == FORCE) {
                    nanos[n++] = ends[i] - starts[i];
                }
            }
            return Stats.of(nanos, n, load, perSecond);
        }

        // from each round's last force, a segment's or the folder's, to the next round's first write: the
        // completions, the round's bookkeeping, and any wait for an append to arm the next round
        Stats gaps(final double load) {
            final long[] nanos = new long[events];
            int n = 0;
            for (int i = 0; i + 1 < events; i++) {
                if (kinds[i] != WRITE && kinds[i + 1] == WRITE) {
                    nanos[n++] = starts[i + 1] - ends[i];
                }
            }
            long total = 0L;
            for (int i = 0; i < n; i++) {
                total += nanos[i];
            }
            final double share = 100.0 * total / Math.max(1L, ends[events - 1] - starts[0]);
            return Stats.of(nanos, n, load, share);
        }

        private void record(final byte kind, final long startNanos) {
            if (events < EVENTS) {
                kinds[events] = kind;
                starts[events] = startNanos;
                ends[events] = System.nanoTime();
                events++;
            }
        }

        /// A segment's writer, its writes and forces timed.
        private final class TimedWriter implements Writer {

            private final Writer writer;

            private TimedWriter(final Writer writer) {
                this.writer = writer;
            }

            @Override
            public void write(final ByteBuffer src, final long position) throws IOException {
                final long t0 = System.nanoTime();
                writer.write(src, position);
                record(WRITE, t0);
            }

            @Override
            public void force() throws IOException {
                final long t0 = System.nanoTime();
                writer.force();
                record(FORCE, t0);
            }

            @Override
            public void truncate(final long size) throws IOException {
                writer.truncate(size);
            }

            @Override
            public void close() throws IOException {
                writer.close();
            }
        }
    }
}
