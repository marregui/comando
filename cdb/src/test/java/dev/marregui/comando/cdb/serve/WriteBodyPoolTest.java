package dev.marregui.comando.cdb.serve;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbAppender;

import static org.assertj.core.api.Assertions.assertThat;

/// `/write` bodies read into pooled buffers: sequential writes reuse one buffer, concurrent ones keep at most the
/// pool's bound once answered, a body in a buffer holds the buffer's size of the body budget, and a body longer than
/// a buffer, of known length or chunked, is still taken whole.
class WriteBodyPoolTest {

    /// Thirty seconds bounds a hung run; every request here answers in milliseconds when the server works.
    private static final long TIMEOUT_SECONDS = 30L;
    /// Short, so a body refused for want of budget answers quickly; long beside a loopback write's milliseconds.
    private static final long BODY_WAIT_MILLIS = 300L;
    private static Http http;

    private Backend backend;
    private CdbServer server;

    /// One client for the class.
    @BeforeAll
    static void openClient() {
        http = new Http();
    }

    /// Closes the client.
    @AfterAll
    static void closeClient() {
        http.close();
    }

    /// A fresh backend and server per test, on a free loopback port, so no request of another test is in flight.
    @BeforeEach
    void startServer() {
        backend = new Backend();
        server = CdbServer.start(backend, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
    }

    /// Stops the server.
    @AfterEach
    void stopServer() {
        server.close();
    }

    /// A thousand writes one after another: each answered 204 with its row written, and one buffer kept.
    @Test
    void sequentialWritesReuseOneBuffer() throws Exception {
        for (int w = 0; w < 1_000; w++) {
            assertThat(http.post(server.port(), "/write?precision=u", "m,w=" + w + " f=1.5 " + w + '\n')
                    .statusCode()).as("write %d", w).isEqualTo(204);
        }

        CommitQueueTest.awaitTrue(() -> server.pooledBodies() == 1);
        assertThat(backend.rows.get()).isEqualTo(1_000L);
    }

    /// Sixty-four writes in flight at once, the server's bound: the first one's commit waits until the other 63 are
    /// queued behind it, each holding its body, so every buffer is in use and the rest read into arrays of their own;
    /// once all are answered 204 the pool holds its bound, no more.
    @Test
    void concurrentWritesLeaveThePoolAtItsBound() throws Exception {
        final int writers = CdbServer.MAX_REQUESTS;
        backend.holdFirst = true;
        final AtomicInteger answered = new AtomicInteger();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CyclicBarrier go = new CyclicBarrier(writers - 1);
        final CountDownLatch done = new CountDownLatch(writers);
        final Runnable first = () -> post(0, null, answered, failure, done);
        Thread.ofVirtual().name("pool-test-0").start(first);
        assertThat(backend.committing.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        for (int w = 1; w < writers; w++) {
            final int n = w;
            Thread.ofVirtual().name("pool-test-" + w).start(() -> post(n, go, answered, failure, done));
        }
        CommitQueueTest.awaitTrue(() -> server.queued("m") == writers - 1);
        backend.release.countDown();

        assertThat(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(failure.get()).isNull();
        assertThat(answered.get()).isEqualTo(writers);
        CommitQueueTest.awaitTrue(() -> server.pooledBodies() == CdbServer.POOLED_BODIES);
        assertThat(backend.rows.get()).isEqualTo(writers);
    }

    /// Under a budget of three buffers, three small writes in flight, each in a buffer, hold all of it: a fourth is
    /// answered 503 once its wait runs out, and is taken once the three are answered.
    @Test
    void aBodyInABufferHoldsTheBuffersShareOfTheBudget() throws Exception {
        server.close();
        server = CdbServer.start(backend, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null,
                new CdbServer.Bounds(Limits.QUERY_DEADLINE_MILLIS, Limits.MAX_ROWS, 3L * Limits.POOLED_BODY_BYTES,
                        BODY_WAIT_MILLIS));
        backend.holdFirst = true;
        final int held = 3;
        final AtomicInteger answered = new AtomicInteger();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(held);
        Thread.ofVirtual().name("pool-test-0").start(() -> post(0, null, answered, failure, done));
        assertThat(backend.committing.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        for (int w = 1; w < held; w++) {
            final int n = w;
            Thread.ofVirtual().name("pool-test-" + w).start(() -> post(n, null, answered, failure, done));
        }
        CommitQueueTest.awaitTrue(() -> server.queued("m") == held - 1);

        final int refused = http.post(server.port(), "/write?precision=u", "m,w=3 f=1.5 3\n").statusCode();
        backend.release.countDown();
        assertThat(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        final int taken = http.post(server.port(), "/write?precision=u", "m,w=4 f=1.5 4\n").statusCode();

        assertThat(refused).isEqualTo(503);
        assertThat(failure.get()).isNull();
        assertThat(answered.get()).isEqualTo(held);
        assertThat(taken).isEqualTo(204);
        assertThat(backend.rows.get()).isEqualTo(held + 1L);
    }

    /// A 2 MiB body with its length, twice a buffer: read into an array of its own, no buffer made, every row written.
    @Test
    void aBodyLongerThanABufferIsTaken() throws IOException, InterruptedException {
        final byte[] body = body(2 * Limits.POOLED_BODY_BYTES);

        final HttpResponse<String> response = http.post(server.port(), "/write?precision=u", body);

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(backend.rows.get()).isEqualTo(rows(body));
        assertThat(server.pooledBodies()).as("buffers made for it").isZero();
    }

    /// Chunked bodies of no stated length, one exactly a buffer long and one longer: each taken whole.
    @Test
    void chunkedBodiesAtAndPastABufferAreTaken() throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            long expected = 0L;
            for (final int size : new int[] {Limits.POOLED_BODY_BYTES, Limits.POOLED_BODY_BYTES + 4_096,
                    3 * Limits.POOLED_BODY_BYTES}) {
                final byte[] body = body(size);
                expected += rows(body);
                final HttpRequest request = HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + server.port() + "/write?precision=u"))
                        .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                        .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)))
                        .build();

                final HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                assertThat(response.statusCode()).as("size %d", size).isEqualTo(204);
                assertThat(backend.rows.get()).as("size %d", size).isEqualTo(expected);
            }
        }
    }

    private void post(final int w, final CyclicBarrier go, final AtomicInteger answered,
            final AtomicReference<Throwable> failure, final CountDownLatch done) {
        try {
            if (go != null) {
                go.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
            final int status = http.post(server.port(), "/write?precision=u", "m,w=" + w + " f=2.5 " + w + '\n')
                    .statusCode();
            if (status == 204) {
                answered.incrementAndGet();
            } else {
                failure.compareAndSet(null, new AssertionError("write answered [w=" + w + ", status=" + status + ']'));
            }
        } catch (final InterruptedException e) {
            // the test is stopping: the write is recorded as failed and the thread ends
            Thread.currentThread().interrupt();
            failure.compareAndSet(null, e);
        } catch (final Exception e) {
            failure.compareAndSet(null, e);
        } finally {
            done.countDown();
        }
    }

    // whole lines of about 40 bytes, then one line whose string field pads the body to exactly size bytes
    private static byte[] body(final int size) {
        final StringBuilder text = new StringBuilder(size);
        long row = 0L;
        while (text.length() < size - 64) {
            text.append("m,host=h").append(row % 7).append(" f=").append(row).append(".25 ").append(row).append('\n');
            row++;
        }
        final String head = "m,host=last s=\"";
        final String tail = "\" " + row + '\n';
        text.append(head).append("x".repeat(size - text.length() - head.length() - tail.length())).append(tail);
        return text.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static long rows(final byte[] body) {
        long lines = 0L;
        for (final byte b : body) {
            if (b == '\n') {
                lines++;
            }
        }
        return lines;
    }

    /// Counts the rows written; with `holdFirst`, the first appender, the first commit's, waits for `release`.
    private static final class Backend implements CdbServer.Backend {

        final AtomicLong rows = new AtomicLong();
        // written by the test thread before its first request, read by the request threads
        volatile boolean holdFirst;
        final CountDownLatch committing = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger opened = new AtomicInteger();

        @Override
        public Rows run(final String sql, final int maxRows, final Long maxLagMillis) {
            throw new UnsupportedOperationException("refuse queries [backend=write]");
        }

        @Override
        public CdbAppender appender() {
            if (holdFirst && opened.getAndIncrement() == 0) {
                committing.countDown();
                try {
                    if (!release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("commit not released");
                    }
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted", e);
                }
            }
            return new Counting(rows);
        }
    }

    /// Counts the rows it is given; drops them on discard.
    private static final class Counting implements CdbAppender {

        private final AtomicLong written;
        private long pending;

        Counting(final AtomicLong written) {
            this.written = written;
        }

        @Override
        public CdbAppender table(final String table) {
            return this;
        }

        @Override
        public CdbAppender symbol(final String column, final String value) {
            return this;
        }

        @Override
        public CdbAppender stringColumn(final String column, final String value) {
            return this;
        }

        @Override
        public CdbAppender longColumn(final String column, final long value) {
            return this;
        }

        @Override
        public CdbAppender doubleColumn(final String column, final double value) {
            return this;
        }

        @Override
        public CdbAppender booleanColumn(final String column, final boolean value) {
            return this;
        }

        @Override
        public CdbAppender timestampColumn(final String column, final long micros) {
            return this;
        }

        @Override
        public void at(final long micros) {
            pending++;
        }

        @Override
        public void flush() {
            written.addAndGet(pending);
            pending = 0L;
        }

        @Override
        public void discard() {
            pending = 0L;
        }

        @Override
        public void close() {
            flush();
        }
    }
}
