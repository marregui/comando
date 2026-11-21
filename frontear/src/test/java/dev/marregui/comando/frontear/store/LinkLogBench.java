package dev.marregui.comando.frontear.store;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// What a durable append costs on this machine's disk: the latency of one append awaited alone, and the throughput
/// when the writer has 1, 16 or 256 appends in flight, which the forcer covers with one force. Closed-loop, so the
/// latency is per append with nothing queued behind it, not under load. Runs only with `-Dfrontear.bench=true`.
@EnabledIfSystemProperty(named = "frontear.bench", matches = "true")
class LinkLogBench {

    // a record the size of a small MQTT publish
    private static final int RECORD = 128;
    private static final int WARMUP = 200;
    private static final int SINGLES = 2_000;
    private static final int BATCHED = 8_192;
    private static final int[] BATCHES = {1, 16, 256};
    // how long one append may wait for its force: far above a disk's force
    private static final long WAIT_SECONDS = 30;
    private static final LogOptions OPTIONS = new LogOptions(64L << 20, 1L << 30, Duration.ZERO);

    @TempDir
    Path dir;

    /// The p50 and p99 of one durable append, awaited before the next starts.
    @Test
    void durableSingleAppendLatency() throws Exception {
        final byte[] record = new byte[RECORD];
        final long[] nanos = new long[SINGLES];
        try (LinkLog log = LinkLog.open(dir.resolve("single"), OPTIONS)) {
            for (int i = 0; i < WARMUP; i++) {
                log.append(record).get(WAIT_SECONDS, TimeUnit.SECONDS);
            }
            for (int i = 0; i < SINGLES; i++) {
                final long start = System.nanoTime();
                log.append(record).get(WAIT_SECONDS, TimeUnit.SECONDS);
                nanos[i] = System.nanoTime() - start;
            }
        }
        Arrays.sort(nanos);
        System.out.println(String.format(Locale.ROOT, "link log durable append: p50=%.1f us p99=%.1f us n=%d",
                nanos[SINGLES / 2] / 1e3, nanos[SINGLES * 99 / 100] / 1e3, SINGLES));
        assertThat(nanos[0]).isPositive();
    }

    /// Records per second with 1, 16 and 256 appends in flight, each batch awaited on its last append.
    @Test
    void durableAppendThroughputByBatch() throws Exception {
        final byte[] record = new byte[RECORD];
        for (final int batch : BATCHES) {
            try (LinkLog log = LinkLog.open(dir.resolve("batch-" + batch), OPTIONS)) {
                run(log, record, batch, WARMUP);
                final long start = System.nanoTime();
                run(log, record, batch, BATCHED);
                final long elapsedNanos = System.nanoTime() - start;
                System.out.println(String.format(Locale.ROOT, "link log durable throughput: batch=%d records/s=%.0f"
                        + " MB/s=%.2f n=%d", batch, BATCHED * 1e9 / elapsedNanos,
                        BATCHED * (double) (RECORD + LinkLog.FRAME) * 1e3 / elapsedNanos, BATCHED));
                assertThat(log.tail()).isEqualTo(WARMUP + BATCHED);
            }
        }
    }

    private static void run(final LinkLog log, final byte[] record, final int batch, final int count)
            throws Exception {
        for (int done = 0; done < count; done += batch) {
            CompletableFuture<Long> last = null;
            for (int i = 0; i < batch && done + i < count; i++) {
                last = log.append(record);
            }
            last.get(WAIT_SECONDS, TimeUnit.SECONDS);
        }
    }
}
