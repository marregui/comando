package dev.marregui.comando.cdb.serve;

/**
 * The bounds both listeners apply to a client's work. None is measured: each is set an order of magnitude past what
 * comando's and the kit's own requests take (a metrics batch is under 1 MiB, a report query under a second and under
 * 100,000 rows), so that only a runaway or hostile request meets one.
 */
final class Limits {

    /** A query runs at most this long, then is cancelled: HTTP answers 408, PG wire SQLSTATE 57014. */
    static final long QUERY_DEADLINE_MILLIS = 60_000;
    /** A result holds at most this many rows: HTTP answers 400, PG wire SQLSTATE 54000. */
    static final int MAX_ROWS = 1_000_000;
    /** The bytes of request bodies held at once; a body that cannot get its share within 5 s is answered 503. */
    static final long BODY_BUDGET_BYTES = 64L << 20;
    /** How long a body waits for its share of {@link #BODY_BUDGET_BYTES}. */
    static final long BODY_WAIT_MILLIS = 5_000;
    /**
     * The longest {@code /write} body read into pooled buffers: a metrics batch fits; a longer body is read into an
     * array of its own. A sizing hint only, as every body under the body limit is taken either way.
     */
    static final int POOLED_BODY_BYTES = 1 << 20;
    /**
     * The size of a pooled {@code /write} body buffer, a body spanning as many as it needs: half a G1 region at the
     * server's 256 MiB heap, whose regions are 1 MiB, is an object G1 allocates as humongous, and this is below it.
     */
    static final int BODY_CHUNK_BYTES = 256 << 10;
    /** The JDK HTTP server's request read bound, in seconds ({@code sun.net.httpserver.maxReqTime}). */
    static final int HTTP_MAX_REQUEST_SECONDS = 30;
    /** The JDK HTTP server's response write bound, in seconds ({@code sun.net.httpserver.maxRspTime}). */
    static final int HTTP_MAX_RESPONSE_SECONDS = 120;
    /** Open HTTP connections at most ({@code jdk.httpserver.maxConnections}). */
    static final int HTTP_MAX_CONNECTIONS = 128;

    private Limits() {
    }
}
