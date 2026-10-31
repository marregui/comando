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
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import dev.marregui.comando.core.loop.ExecutorStop;
import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.ApprovalDecision;
import dev.marregui.comando.core.port.ApprovalPort;
import dev.marregui.comando.core.port.McpGateway;

/**
 * comando's MCP server: streamable HTTP on 127.0.0.1, one JSON response per request, no server-initiated
 * stream. Each session's CLI authenticates with its own bearer token, which names the calling session.
 * Requests run on virtual threads, so an approval that waits for the user holds no operating-system thread.
 */
public final class McpServer implements McpGateway, ApprovalPort, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(McpServer.class.getName());
    static final String PATH = "/mcp";
    static final Set<String> PROTOCOLS = Set.of("2025-03-26", "2025-06-18", "2025-11-25");
    static final String DEFAULT_PROTOCOL = "2025-06-18";
    private static final int MAX_BODY = 64 * 1024 * 1024;

    private final McpTools tools;
    private final HttpServer http;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ObjectMapper json = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, String> sessionByToken = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> tokenBySession = new ConcurrentHashMap<>();
    private final String version;
    private volatile boolean closed;

    private McpServer(final McpTools tools, final HttpServer http, final String version) {
        this.tools = tools;
        this.http = http;
        this.version = version;
        tools.live(tokenBySession::containsKey);
    }

    /**
     * Starts on {@code port} of {@code 127.0.0.1}, the address {@link #url} names; 0 picks a free port. Named rather
     * than the JVM's loopback address, which is {@code ::1} when IPv6 addresses are preferred.
     */
    public static McpServer start(final McpTools tools, final int port, final String version) throws IOException {
        final HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.ofLiteral("127.0.0.1"), port), 64);
        final McpServer server = new McpServer(tools, http, version);
        http.createContext(PATH, server::handle);
        http.setExecutor(server.executor);
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
        sessionByToken.put(token, session);
        tokenBySession.put(session, token);
        return new Ticket(url(), token);
    }

    @Override
    public void unregister(final String session, final Ticket ticket) {
        if (ticket == null) {
            return;
        }
        sessionByToken.remove(ticket.token(), session);
        // an earlier process's late unregister finds a newer token here and leaves the newer process's cards alone
        if (tokenBySession.remove(session, ticket.token())) {
            tools.sessionEnded(session);
        }
    }

    @Override
    public void resolve(final Approval approval, final ApprovalDecision decision) {
        tools.resolve(approval, decision);
    }

    private void handle(final HttpExchange ex) throws IOException {
        try (ex) {
            final String session = authenticate(ex);
            if (session == null) {
                send(ex, 401, null);
                return;
            }
            if (!originAllowed(ex.getRequestHeaders().getFirst("Origin"))) {
                send(ex, 403, null);
                return;
            }
            switch (ex.getRequestMethod()) {
                case "POST" -> post(ex, session);
                case "DELETE" -> send(ex, 200, null);
                default -> send(ex, 405, null); // no server-initiated stream
            }
        } catch (final RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "cannot answer an MCP request [method=" + ex.getRequestMethod() + ", path=" + ex.getRequestURI().getPath() + ']', e);
        }
    }

    private String authenticate(final HttpExchange ex) {
        final String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            return null;
        }
        return sessionByToken.get(auth.substring("Bearer ".length()).strip());
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

    private void post(final HttpExchange ex, final String session) throws IOException {
        JsonNode request;
        try (InputStream in = ex.getRequestBody()) {
            final byte[] body = in.readNBytes(MAX_BODY + 1);
            if (body.length > MAX_BODY) {
                send(ex, 413, null);
                return;
            }
            request = json.readTree(body);
        } catch (IOException | JacksonException e) {
            send(ex, 400, error(null, -32700, "parse error"));
            return;
        }
        if (request == null || !request.isObject()) {
            send(ex, 400, error(null, -32600, "a request is one JSON-RPC object"));
            return;
        }
        if (!request.has("id")) {
            if ("notifications/cancelled".equals(request.path("method").asString(""))) {
                tools.cancelled(callKey(session, request.path("params").path("requestId")));
            }
            send(ex, 202, null); // a notification or a response; nothing to answer
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
        send(ex, 200, response);
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

    private void send(final HttpExchange ex, final int status, final JsonNode body) throws IOException {
        if (body == null) {
            ex.sendResponseHeaders(status, -1);
            return;
        }
        final byte[] bytes = json.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
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
        ExecutorStop.now("mcp-server", executor);
        sessionByToken.clear();
        tokenBySession.clear();
    }
}
