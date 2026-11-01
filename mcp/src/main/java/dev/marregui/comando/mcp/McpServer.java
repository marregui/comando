package dev.marregui.comando.mcp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import dev.marregui.comando.core.loop.ExecutorStop;
import dev.marregui.comando.core.loop.Threads;
import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.ApprovalDecision;
import dev.marregui.comando.core.port.ApprovalPort;
import dev.marregui.comando.core.port.McpGateway;

/**
 * comando's MCP server: streamable HTTP on 127.0.0.1, one JSON response per request, no server-initiated
 * stream. Each session's CLI authenticates with its own bearer token, which names the calling session.
 * Requests run on virtual threads, so an approval that waits for the user holds no operating-system thread; at most
 * {@link #MAX_REQUESTS} run at once, and at most {@link #MAX_PER_SESSION} of one session's token, and one past either
 * is answered 503 at once, so a flood of requests does not grow without a bound and one session's waiting calls leave
 * room for the others. A request has {@link #BODY_READ_TIMEOUT} to arrive, its request line and headers and body
 * included, so a client that sends part of a request and stalls holds a connection and a thread only that long; at most
 * {@link #MAX_CONNECTIONS} connections are open at once, idle ones included.
 */
public final class McpServer implements McpGateway, ApprovalPort, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(McpServer.class.getName());
    static final String PATH = "/mcp";
    static final Set<String> PROTOCOLS = Set.of("2025-03-26", "2025-06-18", "2025-11-25");
    static final String DEFAULT_PROTOCOL = "2025-06-18";
    private static final int MAX_BODY = 64 * 1024 * 1024;
    /**
     * The requests that run at once. Each session's CLI sends a few at a time, and an approval holds one while the user
     * decides, so the bound is far above use; past it a request is refused with 503 and {@code Retry-After}, not queued.
     */
    static final int MAX_REQUESTS = 256;
    /**
     * The requests one session's token runs at once. A {@code session_read} or {@code instance_ask} waits up to 600 s
     * and an approval up to 55 minutes, each holding a request; this bound keeps one session's waits, or a client of it
     * that stalls, from taking all of {@link #MAX_REQUESTS} (16 sessions at this bound fill it). Unmeasured: how many
     * calls Claude Code runs at once per session, sub-agents included, is not known; 16 is a guess well above the few a
     * session is seen to send.
     */
    static final int MAX_PER_SESSION = 16;
    /**
     * How long a request has to arrive, from its first byte to its body's last, before its connection is closed. A
     * session's CLI writes the request at once over loopback. Unmeasured: a few seconds, expected to be far above any
     * real request's write.
     */
    static final Duration BODY_READ_TIMEOUT = Duration.ofSeconds(5);
    /**
     * The connections open at once, idle ones and those that have sent nothing yet included; one more is closed by the
     * JDK's server as it is accepted. Four times {@link #MAX_REQUESTS}: room for every request that may run at once and,
     * beside each, the idle keep-alive connections the sessions' CLIs hold between calls, so the bound is far above use
     * and only a flood of connections that send nothing reaches it. Unmeasured: how many connections Claude Code keeps
     * per session.
     */
    static final int MAX_CONNECTIONS = 4 * MAX_REQUESTS;
    /**
     * The JDK's server reads its connection bound from this property once, when its first server is made in the JVM;
     * comando's is the only one there.
     */
    static final String MAX_CONNECTIONS_PROPERTY = "jdk.httpserver.maxConnections";
    /**
     * The arrival deadline of the request the current thread reads: armed when its exchange starts, before the JDK reads
     * the request line and headers on that thread, and read by {@link #handle}, which disarms it once the body is in.
     */
    private static final ScopedValue<Deadline> ARRIVAL = ScopedValue.newInstance();

    /**
     * The bounds requests are held to.
     *
     * @param requests   the requests that run at once, over every session
     * @param perSession the requests that run at once for one session's token
     * @param bodyRead   how long a request has to arrive, its request line, headers and body included
     */
    record Bounds(int requests, int perSession, Duration bodyRead) {

        static final Bounds DEFAULT = new Bounds(MAX_REQUESTS, MAX_PER_SESSION, BODY_READ_TIMEOUT);
    }

    /** A token's session and the requests it may still run, at most {@link Bounds#perSession}. */
    private record Caller(String session, Semaphore running) {
    }

    /**
     * The place one request holds, given back once. A request whose body has been read whole gives it back before its
     * answer is sent, so a client that sends its next request the moment it has the answer never finds its own place
     * still taken; any other keeps it until the exchange is closed, as closing drains a body not read.
     */
    private final class Place {
        private final Caller caller;
        private boolean held = true;

        private Place(final Caller caller) {
            this.caller = caller;
        }

        private void release() {
            if (held) {
                held = false;
                McpServer.this.release(caller);
            }
        }
    }

    private final McpTools tools;
    private final HttpServer http;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    /** Ends the requests that have not arrived in time ({@link Deadline}). */
    private final ScheduledExecutorService deadlines = Threads.timedStrand("mcp-deadlines");
    private final ObjectMapper json = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, Caller> callerByToken = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> tokenBySession = new ConcurrentHashMap<>();
    private final String version;
    private final Bounds bounds;
    /** The requests running, at most {@link Bounds#requests}. */
    private final Semaphore running;
    private volatile boolean closed;

    private McpServer(final McpTools tools, final HttpServer http, final String version, final Bounds bounds) {
        this.tools = tools;
        this.http = http;
        this.version = version;
        this.bounds = bounds;
        this.running = new Semaphore(bounds.requests());
        tools.live(tokenBySession::containsKey);
    }

    /**
     * Starts on {@code port} of {@code 127.0.0.1}, the address {@link #url} names; 0 picks a free port. Named rather
     * than the JVM's loopback address, which is {@code ::1} when IPv6 addresses are preferred.
     */
    public static McpServer start(final McpTools tools, final int port, final String version) throws IOException {
        return start(tools, port, version, Bounds.DEFAULT);
    }

    /** As {@link #start(McpTools, int, String)}, with at most {@code maxRequests} running at once; for tests. */
    static McpServer start(final McpTools tools, final int port, final String version, final int maxRequests) throws IOException {
        return start(tools, port, version, new Bounds(maxRequests, MAX_PER_SESSION, BODY_READ_TIMEOUT));
    }

    /** As {@link #start(McpTools, int, String)}, held to {@code bounds}; for tests. */
    static McpServer start(final McpTools tools, final int port, final String version, final Bounds bounds) throws IOException {
        // set before the JDK's server is made, which reads it once; a value the JVM was started with is kept
        if (System.getProperty(MAX_CONNECTIONS_PROPERTY) == null) {
            System.setProperty(MAX_CONNECTIONS_PROPERTY, Integer.toString(MAX_CONNECTIONS));
        }
        final HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.ofLiteral("127.0.0.1"), port), 64);
        final McpServer server = new McpServer(tools, http, version, bounds);
        http.createContext(PATH, server::handle);
        http.setExecutor(server::exchange);
        http.start();
        LOG.log(System.Logger.Level.INFO, "mcp server listening [url=" + server.url() + ']');
        return server;
    }

    public String url() {
        return "http://127.0.0.1:" + http.getAddress().getPort() + PATH;
    }

    @Override
    public Ticket register(final String session) {
        if (closed) {
            return null;
        }
        // a fresh token per process: a late revocation of an earlier process's token cannot reach this one
        final byte[] b = new byte[32];
        random.nextBytes(b);
        final String token = HexFormat.of().formatHex(b);
        callerByToken.put(token, new Caller(session, new Semaphore(bounds.perSession())));
        tokenBySession.put(session, token);
        return new Ticket(url(), token);
    }

    @Override
    public void unregister(final String session, final Ticket ticket) {
        if (ticket == null) {
            return;
        }
        callerByToken.computeIfPresent(ticket.token(), (t, c) -> c.session().equals(session) ? null : c);
        // an earlier process's late unregister finds a newer token here and leaves the newer process's cards alone
        if (tokenBySession.remove(session, ticket.token())) {
            tools.sessionEnded(session);
        }
    }

    @Override
    public void resolve(final Approval approval, final ApprovalDecision decision) {
        tools.resolve(approval, decision);
    }

    /**
     * Runs one exchange of a connection on a virtual thread, under its arrival deadline: the JDK reads the request line
     * and headers in {@code task} before {@link #handle} runs, so a client that never ends its headers is cut off at the
     * deadline as one that never sends its body is. Once the server is closing, the exchange is dropped: the server's
     * stop closes its connection.
     */
    private void exchange(final Runnable task) {
        executor.execute(() -> {
            final Deadline arrival;
            try {
                arrival = Deadline.arm(deadlines, bounds.bodyRead());
            } catch (final RejectedExecutionException e) {
                return;
            }
            try {
                ScopedValue.where(ARRIVAL, arrival).run(task);
            } finally {
                arrival.disarm();
            }
        });
    }

    private void handle(final HttpExchange ex) throws IOException {
        // armed on every path, from the request's first byte: closing an exchange drains a body not read, which a
        // stalled client never ends either
        final Deadline arrival = ARRIVAL.get();
        try {
            final Caller caller = authenticate(ex);
            if (!acquire(caller)) {
                try (ex) {
                    ex.getResponseHeaders().set("Retry-After", "1");
                    send(ex, 503, null);
                }
                return;
            }
            final Place place = new Place(caller);
            try (ex) {
                if (caller == null) {
                    send(ex, 401, null);
                    return;
                }
                if (!originAllowed(ex.getRequestHeaders().getFirst("Origin"))) {
                    send(ex, 403, null);
                    return;
                }
                switch (ex.getRequestMethod()) {
                    case "POST" -> post(ex, place, caller.session(), arrival);
                    case "DELETE" -> send(ex, 200, null);
                    default -> send(ex, 405, null); // no server-initiated stream
                }
            } catch (final RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "cannot answer an MCP request [method=" + ex.getRequestMethod() + ", path=" + ex.getRequestURI().getPath() + ']', e);
            } finally {
                place.release();
            }
        } finally {
            arrival.disarm();
        }
    }

    /**
     * Takes a place for one request: one of the caller's own ({@link Bounds#perSession}, {@link #MAX_PER_SESSION} by
     * default), then one of every session's ({@link Bounds#requests}, {@link #MAX_REQUESTS}); {@code false}, holding
     * neither, when either is full, and the request is answered 503. A request with no known token takes only the shared
     * one, held while it is answered 401: its body is never read, and the JDK closes the connection with the exchange.
     * A place is taken once the headers are in; the whole request, from its first byte to its body's last, has {@link
     * Bounds#bodyRead} ({@link #BODY_READ_TIMEOUT} by default), on one of at most {@link #MAX_CONNECTIONS} connections.
     */
    private boolean acquire(final Caller caller) {
        if (caller != null && !caller.running().tryAcquire()) {
            return false;
        }
        if (running.tryAcquire()) {
            return true;
        }
        if (caller != null) {
            caller.running().release();
        }
        return false;
    }

    private void release(final Caller caller) {
        running.release();
        if (caller != null) {
            caller.running().release();
        }
    }

    private Caller authenticate(final HttpExchange ex) {
        final String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            return null;
        }
        return callerByToken.get(auth.substring("Bearer ".length()).strip());
    }

    /**
     * Browsers send Origin; a page on another site must not reach a local server (DNS rebinding). A client that is not
     * a browser sends none. {@code null} is a browser's opaque origin (a sandboxed frame, a {@code file:} page, a
     * redirect across sites): a page, and not a loopback one, so it is refused.
     */
    static boolean originAllowed(final String origin) {
        if (origin == null || origin.isBlank()) {
            return true;
        }
        try {
            final String host = URI.create(origin).getHost();
            return host != null && (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1"));
        } catch (final IllegalArgumentException e) {
            return false;
        }
    }

    private void post(final HttpExchange ex, final Place place, final String session, final Deadline arrival) throws IOException {
        final byte[] body;
        try (final InputStream in = ex.getRequestBody()) {
            body = in.readNBytes(MAX_BODY + 1);
        } catch (final IOException e) {
            if (arrival.fired()) {
                // the deadline closed the connection: there is no one left to answer
                LOG.log(System.Logger.Level.WARNING, "an MCP request's body did not arrive in time, its connection is closed [session=" + session + ", timeout="
                        + bounds.bodyRead() + ']');
                return;
            }
            send(ex, 400, error(null, -32700, "parse error"));
            return;
        }
        if (body.length > MAX_BODY) {
            send(ex, 413, null);
            return;
        }
        // the whole body is in: from here a call may wait as long as its tool does
        arrival.disarm();
        final JsonNode request;
        try {
            request = json.readTree(body);
        } catch (final JacksonException e) {
            send(ex, place, 400, error(null, -32700, "parse error"));
            return;
        }
        if (request == null || !request.isObject()) {
            send(ex, place, 400, error(null, -32600, "a request is one JSON-RPC object"));
            return;
        }
        if (!request.has("id")) {
            if ("notifications/cancelled".equals(request.path("method").asString(""))) {
                tools.cancelled(callKey(session, request.path("params").path("requestId")));
            }
            send(ex, place, 202, null); // a notification or a response; nothing to answer
            return;
        }
        final JsonNode id = request.get("id");
        final String method = request.path("method").asString("");
        final JsonNode params = request.path("params");
        final ObjectNode response = switch (method) {
            case "initialize" -> result(id, initialize(params));
            case "ping" -> result(id, json.createObjectNode());
            case "tools/list" -> {
                final ObjectNode r = json.createObjectNode();
                r.set("tools", tools.definitions());
                yield result(id, r);
            }
            case "tools/call" -> result(id, call(session, params, callKey(session, id)));
            default -> error(id, -32601, "method not found: " + method);
        };
        send(ex, place, 200, response);
    }

    private ObjectNode initialize(final JsonNode params) {
        final String asked = params.path("protocolVersion").asString("");
        final ObjectNode r = json.createObjectNode();
        r.put("protocolVersion", PROTOCOLS.contains(asked) ? asked : DEFAULT_PROTOCOL);
        r.putObject("capabilities").putObject("tools").put("listChanged", false);
        final ObjectNode info = r.putObject("serverInfo");
        info.put("name", "comando");
        info.put("version", version);
        r.put("instructions", "comando's UI and database tools. Views replace earlier views with the same title unless the user pinned them.");
        return r;
    }

    /** A JSON-RPC request id is unique per client; the session's token tells the clients apart. */
    private static String callKey(final String session, final JsonNode requestId) {
        return requestId == null || requestId.isMissingNode() || requestId.isNull() ? null : session + '#' + requestId;
    }

    private ObjectNode call(final String session, final JsonNode params, final String callKey) {
        final String name = params.path("name").asString("");
        final JsonNode args = params.path("arguments");
        final McpTools.Outcome out = tools.call(session, name, args.isObject() ? args : json.createObjectNode(), callKey);
        final ObjectNode r = json.createObjectNode();
        final ObjectNode text = r.putArray("content").addObject();
        text.put("type", "text");
        text.put("text", out.text());
        if (out.error()) {
            r.put("isError", true);
        }
        return r;
    }

    private ObjectNode result(final JsonNode id, final JsonNode result) {
        final ObjectNode r = json.createObjectNode();
        r.put("jsonrpc", "2.0");
        r.set("id", id);
        r.set("result", result);
        return r;
    }

    private ObjectNode error(final JsonNode id, final int code, final String message) {
        final ObjectNode r = json.createObjectNode();
        r.put("jsonrpc", "2.0");
        r.set("id", id);
        final ObjectNode e = r.putObject("error");
        e.put("code", code);
        e.put("message", message);
        return r;
    }

    /** Gives back the place of a request whose body has been read whole, then answers it. */
    private void send(final HttpExchange ex, final Place place, final int status, final JsonNode body) throws IOException {
        place.release();
        send(ex, status, body);
    }

    private void send(final HttpExchange ex, final int status, final JsonNode body) throws IOException {
        if (body == null) {
            ex.sendResponseHeaders(status, -1);
            return;
        }
        final byte[] bytes = json.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (final OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** Denies waiting approvals, stops the listener and the request threads. Idempotent. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        tools.denyAll("comando is shutting down.");
        http.stop(1);
        ExecutorStop.now("mcp-server", executor, deadlines);
        callerByToken.clear();
        tokenBySession.clear();
    }

    /**
     * A request's deadline to arrive: past it the request's thread is interrupted, which closes the connection it reads
     * (the server reads a body from an interruptible channel), so a client that announces a body and sends none holds
     * its bounds no longer. Armed and disarmed by the request's own thread.
     */
    private static final class Deadline {

        private final Thread request;
        /** Guards {@link #fired} and {@link #disarmed}: the timer's thread fires, the request's thread disarms. */
        private final Object lock = new Object();
        private boolean fired;
        private boolean disarmed;
        /** Set by {@link #arm} and read by {@link #disarm}, both on the request's thread. */
        private Future<?> timer;

        private Deadline(final Thread request) {
            this.request = request;
        }

        /** A deadline for the calling thread, {@code after} from now. */
        static Deadline arm(final ScheduledExecutorService timers, final Duration after) {
            final Deadline d = new Deadline(Thread.currentThread());
            d.timer = timers.schedule(d::fire, after.toNanos(), TimeUnit.NANOSECONDS);
            return d;
        }

        private void fire() {
            synchronized (lock) {
                if (!disarmed) {
                    fired = true;
                    request.interrupt();
                }
            }
        }

        /** Whether the deadline passed before it was disarmed. */
        boolean fired() {
            synchronized (lock) {
                return fired;
            }
        }

        /** No interrupt comes after this; one that came is cleared, so it reaches nothing the request does next. Idempotent. */
        void disarm() {
            final boolean interrupted;
            synchronized (lock) {
                if (disarmed) {
                    return;
                }
                disarmed = true;
                interrupted = fired;
            }
            timer.cancel(false);
            if (interrupted) {
                Thread.interrupted();
            }
        }
    }
}
