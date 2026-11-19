package dev.marregui.comando.cdb.serve;

import java.io.IOException;
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbAppender;
import dev.marregui.comando.cdb.CdbType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/// A large `/exec` result streamed: the same characters as the whole-answer form, written in chunks with no copy of
/// the answer held whole, and answered 200 over HTTP with the same bytes, chunked; a failure once it streams drops the
/// connection, so its client sees an error rather than an answer that ends cleanly.
class ExecStreamTest {

    private static final int ROWS = 200_000;
    private static final String QUERY = "select name, n from big";
    // the client's own timeout ends once the status is in, so a body left open would be read for ever
    private static final Duration DROP_WAIT = Duration.ofSeconds(10);
    private static Http http;
    private static FakeRows rows;

    private CdbServer server;
    private volatile Rows answer;

    /// One client and one 200,000-row result of a 40-odd character text column, some of it escaped or non-ASCII, and
    /// a long column, for the class.
    @BeforeAll
    static void setUp() {
        http = new Http();
        final List<List<Object>> cells = new ArrayList<>(ROWS);
        for (int row = 0; row < ROWS; row++) {
            final String name = switch (row % 4) {
                case 0 -> "sensor-" + row + "-temperature-reading-of-the-hall";
                case 1 -> "Gerät \"" + row + "\" mit Ümlaut, über den Wert";
                case 2 -> "tab\tand newline\n and backslash \\ " + row;
                default -> "plain ascii text of about forty chars " + row;
            };
            cells.add(List.of(name, (long) row * 1_000_003L));
        }
        rows = FakeRows.query(-1, List.of("name", CdbType.VARCHAR, "n", CdbType.LONG), cells);
    }

    /// Closes the client.
    @AfterAll
    static void tearDown() {
        http.close();
    }

    /// A fresh server per test, on a free loopback port, answering every query with `answer`.
    @BeforeEach
    void startServer() {
        server = CdbServer.start(new Backend(), new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
    }

    /// Stops the server.
    @AfterEach
    void stopServer() {
        server.close();
    }

    /// The streamed answer is the whole-answer form character for character, in writes of at most one chunk.
    @Test
    void streamsTheSameCharactersInChunks() throws IOException {
        final String whole = JsonWriter.result(QUERY, rows, 0L);
        final Recording out = new Recording(true);

        JsonWriter.result(QUERY, rows, 0L, out);

        assertThat(out.text.toString()).isEqualTo(whole);
        assertThat(out.chars).isEqualTo(whole.length());
        assertThat(out.longestWrite).isPositive().isLessThanOrEqualTo(JsonWriter.CHUNK_CHARS);
        assertThat(out.writes).isGreaterThan(whole.length() / JsonWriter.CHUNK_CHARS);
    }

    /// Streaming the answer allocates a small fraction of its size on the writing thread: no String, builder or
    /// array of the answer's size is made. Measured after one warm-up run, so class loading does not count.
    @Test
    void streamingAllocatesFarLessThanTheAnswer() throws IOException {
        final com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assumeThat(threads.isThreadAllocatedMemorySupported() && threads.isThreadAllocatedMemoryEnabled()).isTrue();
        final Recording warmUp = new Recording(false);
        JsonWriter.result(QUERY, rows, 0L, warmUp);
        final Recording out = new Recording(false);
        final long threadId = Thread.currentThread().threadId();

        final long before = threads.getThreadAllocatedBytes(threadId);
        JsonWriter.result(QUERY, rows, 0L, out);
        final long allocated = threads.getThreadAllocatedBytes(threadId) - before;

        assertThat(out.chars).isGreaterThan(10_000_000L);
        assertThat(allocated).as("bytes allocated for %d characters", out.chars).isLessThan(out.chars / 8);
    }

    /// Over HTTP the result answers 200, chunked rather than with a length, its body the whole-answer form's bytes.
    @Test
    void execAnswersTheSameBytesChunked() throws IOException, InterruptedException {
        answer = rows;

        final HttpResponse<String> response = http.exec(server.port(), QUERY, "");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json; charset=utf-8");
        assertThat(response.headers().firstValue("Content-Length")).isEmpty();
        assertThat(response.body()).isEqualTo(JsonWriter.result(QUERY, rows, 0L));
    }

    /// A row that throws an exception once the answer is streaming: its status is already sent, so the server drops
    /// the connection before the body's end, the client's read fails, and the server answers the next request.
    @Test
    void anExceptionMidStreamDropsTheConnection() throws IOException, InterruptedException {
        assertDropped(new Failing(rows, ROWS / 2, false));
    }

    /// A row that throws an `Error` once the answer is streaming: the connection is dropped as for an exception, not
    /// left open with the client waiting.
    @Test
    void anErrorMidStreamDropsTheConnection() throws IOException, InterruptedException {
        assertDropped(new Failing(rows, ROWS / 2, true));
    }

    private void assertDropped(final Rows failing) throws IOException, InterruptedException {
        answer = failing;

        final FutureTask<HttpResponse<String>> call = new FutureTask<>(() -> http.exec(server.port(), QUERY, ""));
        Thread.ofVirtual().name("exec-stream-client").start(call);
        try {
            assertThat(call).failsWithin(DROP_WAIT).withThrowableOfType(ExecutionException.class)
                    .withCauseInstanceOf(IOException.class);
        } finally {
            call.cancel(true);
        }

        answer = FakeRows.query(-1, List.of("x", CdbType.LONG), List.of(List.of(1L)));
        final HttpResponse<String> next = http.exec(server.port(), "select x", "");
        assertThat(next.statusCode()).isEqualTo(200);
        assertThat(next.body()).isEqualTo(JsonWriter.result("select x", answer, 0L));
    }

    /// Counts what is written to it, and keeps it when asked.
    private static final class Recording extends Writer {

        final StringBuilder text = new StringBuilder();
        private final boolean keep;
        long chars;
        long writes;
        int longestWrite;

        Recording(final boolean keep) {
            this.keep = keep;
        }

        @Override
        public void write(final char[] buffer, final int offset, final int length) {
            chars += length;
            writes++;
            longestWrite = Math.max(longestWrite, length);
            if (keep) {
                text.append(buffer, offset, length);
            }
        }

        @Override
        public void write(final String string) {
            write(string.toCharArray(), 0, string.length());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    /// `rows` whose text cell of row `failAt` throws, an `Error` when `error`, else an exception.
    private record Failing(Rows rows, int failAt, boolean error) implements Rows {

        @Override
        public int columnCount() {
            return rows.columnCount();
        }

        @Override
        public String columnName(final int column) {
            return rows.columnName(column);
        }

        @Override
        public CdbType type(final int column) {
            return rows.type(column);
        }

        @Override
        public int rowCount() {
            return rows.rowCount();
        }

        @Override
        public boolean isNull(final int row, final int column) {
            return rows.isNull(row, column);
        }

        @Override
        public long getLong(final int row, final int column) {
            return rows.getLong(row, column);
        }

        @Override
        public double getDouble(final int row, final int column) {
            return rows.getDouble(row, column);
        }

        @Override
        public boolean getBoolean(final int row, final int column) {
            return rows.getBoolean(row, column);
        }

        @Override
        public String getText(final int row, final int column) {
            if (row == failAt && error) {
                throw new AssertionError("fail row [row=" + row + ']');
            }
            if (row == failAt) {
                throw new IllegalStateException("fail row [row=" + row + ']');
            }
            return rows.getText(row, column);
        }

        @Override
        public long updateCount() {
            return rows.updateCount();
        }

        @Override
        public boolean isDdl() {
            return rows.isDdl();
        }

        @Override
        public int timestampIndex() {
            return rows.timestampIndex();
        }
    }

    /// Answers every query with the test's `answer`; takes no writes.
    private final class Backend implements CdbServer.Backend {

        @Override
        public Rows run(final String sql, final int maxRows, final Long maxLagMillis) {
            return answer;
        }

        @Override
        public CdbAppender appender() {
            throw new UnsupportedOperationException("refuse writes [backend=exec]");
        }
    }
}
