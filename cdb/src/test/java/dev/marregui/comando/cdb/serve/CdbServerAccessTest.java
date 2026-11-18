package dev.marregui.comando.cdb.serve;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbAppender;
import dev.marregui.comando.cdb.CdbServe;
import dev.marregui.comando.cdb.CdbType;

import static org.assertj.core.api.Assertions.assertThat;

/// The server's gate over raw loopback sockets, in front of a fake backend: the secret a request carries, the host it
/// names and whether a browser page sent it, each refusal in its endpoint's JSON error shape and the backend never
/// run for it; and the launcher, which serves the network only with secrets and hands them to the server it starts.
class CdbServerAccessTest {

    /// A launched server answers in a second on loopback; thirty only bound a hung one.
    private static final long LAUNCH_SECONDS = 30;
    private static final Pattern SERVING = Pattern.compile("cdb serving \\[.*, port=(\\d+), auth=(true|false)]");

    @TempDir
    private Path root;

    private Fake backend;
    private String owner;
    private String readOnly;
    private CdbServer server;

    /// Secrets in a fresh auth folder, a fresh backend, and a server asking for the secrets on a free loopback port.
    @BeforeEach
    void startServer() throws IOException {
        final Path auth = root.resolve("auth");
        final Secrets secrets = Secrets.load(auth);
        owner = Files.readString(auth.resolve(Secrets.OWNER_FILE), StandardCharsets.US_ASCII).strip();
        readOnly = Files.readString(auth.resolve(Secrets.READ_ONLY_FILE), StandardCharsets.US_ASCII).strip();
        backend = new Fake();
        server = CdbServer.start(backend, loopback(), secrets, CdbServer.Bounds.DEFAULT);
    }

    /// Stops the server.
    @AfterEach
    void stopServer() {
        server.close();
    }

    /// A request with no secret, a wrong one or a secret of another scheme is answered 401 with the Bearer challenge
    /// and QuestDB's error shape, on `/exec` and on `/write`; the backend runs nothing.
    @Test
    void withoutTheSecretARequestIs401() throws IOException {
        final String refusal = "refuse request without a valid secret";
        for (final Map<String, String> headers : List.of(Map.<String, String>of(),
                Map.of("Authorization", "Bearer " + "0".repeat(64)),
                Map.of("Authorization", "Basic " + owner),
                Map.of("Authorization", owner))) {
            final Answer exec = Raw.exec(server.port(), host(), "select 1", headers);
            assertThat(exec.status()).as(headers.toString()).isEqualTo(401);
            assertThat(exec.head()).containsIgnoringCase("WWW-Authenticate: Bearer realm=\"cdb\"");
            assertThat(exec.body()).isEqualTo("{\"query\":\"\",\"error\":\"" + refusal + "\",\"position\":-1}");

            final Answer write = Raw.send(server.port(), "POST", "/write", with(headers, "Host", host()),
                    "t f=1i\n".getBytes(StandardCharsets.UTF_8));
            assertThat(write.status()).as(headers.toString()).isEqualTo(401);
            assertThat(write.body()).isEqualTo("{\"code\":\"invalid\",\"message\":\"" + refusal + "\",\"line\":0}");
        }
        assertThat(backend.calls).isEmpty();
        assertThat(backend.appenders).isEmpty();
    }

    /// The owner's secret runs every statement and writes line protocol; the scheme's name matches in any case.
    @Test
    void theOwnersSecretRunsEverything() throws IOException {
        for (final String sql : List.of("select 1", "update t set x = 1", "drop table t")) {
            assertThat(Raw.exec(server.port(), host(), sql, bearer(owner)).status()).as(sql).isEqualTo(200);
        }
        assertThat(Raw.exec(server.port(), host(), "select 2", Map.of("Authorization", "bearer " + owner)).status())
                .isEqualTo(200);
        final Answer write = Raw.send(server.port(), "POST", "/write", with(bearer(owner), "Host", host()),
                "t f=1i 1\n".getBytes(StandardCharsets.UTF_8));

        assertThat(write.status()).isEqualTo(204);
        assertThat(backend.calls).containsExactly("select 1", "update t set x = 1", "drop table t", "select 2");
        assertThat(backend.appenders).hasSize(1);
    }

    /// The read-only secret runs a query, a `SHOW` and an `EXPLAIN`; a statement that changes the store is answered
    /// 403 with the statement in the error, and `/write` 403 in the line-protocol error shape; neither reaches the
    /// backend.
    @Test
    void theReadOnlySecretOnlyReads() throws IOException {
        for (final String sql : List.of("select 1", "show tables", "explain update t set x = 1")) {
            assertThat(Raw.exec(server.port(), host(), sql, bearer(readOnly)).status()).as(sql).isEqualTo(200);
        }
        for (final String sql : List.of("update t set x = 1", "create table u (x long)", "drop table t",
                "truncate table t", "delete from t", "insert into t values (1)")) {
            final Answer refused = Raw.exec(server.port(), host(), sql, bearer(readOnly));
            assertThat(refused.status()).as(sql).isEqualTo(403);
            assertThat(refused.body()).isEqualTo("{\"query\":\"" + sql + "\",\"error\":\"refuse a statement that"
                    + " changes the store with the read-only secret\",\"position\":-1}");
        }
        final Answer write = Raw.send(server.port(), "POST", "/write", with(bearer(readOnly), "Host", host()),
                "t f=1i 1\n".getBytes(StandardCharsets.UTF_8));

        assertThat(write.status()).isEqualTo(403);
        assertThat(write.body()).isEqualTo("{\"code\":\"invalid\",\"message\":\"refuse line-protocol writes with the"
                + " read-only secret\",\"line\":0}");
        assertThat(backend.calls).containsExactly("select 1", "show tables", "explain update t set x = 1");
        assertThat(backend.appenders).isEmpty();
    }

    /// Bound to loopback, the server answers only a `Host` of a loopback name with its own port, in any case: a page
    /// whose name was rebound to 127.0.0.1 names its own host and is answered 403, the owner's secret or not.
    @Test
    void aHostOtherThanLoopbackIs403() throws IOException {
        final int port = server.port();
        for (final String host : List.of("127.0.0.1:" + port, "localhost:" + port, "LocalHost:" + port,
                "[::1]:" + port)) {
            assertThat(Raw.exec(port, host, "select 1", bearer(owner)).status()).as(host).isEqualTo(200);
        }
        for (final String host : List.of("rebound.example:" + port, "127.0.0.1:" + (port == 1 ? 2 : port - 1),
                "127.0.0.1", "localhost", "127.0.0.2:" + port)) {
            final Answer refused = Raw.exec(port, host, "select 1", bearer(owner));
            assertThat(refused.status()).as(host).isEqualTo(403);
            assertThat(refused.body()).isEqualTo("{\"query\":\"\",\"error\":\"refuse request for a host other than"
                    + " loopback [host=" + host + "]\",\"position\":-1}");
        }
        assertThat(backend.calls).hasSize(4);
    }

    /// A request with an `Origin`, which only a browser sends, is answered 403 whatever the origin, the owner's
    /// secret or not.
    @Test
    void aRequestWithAnOriginIs403() throws IOException {
        for (final String origin : List.of("http://127.0.0.1:" + server.port(), "https://rebound.example", "null")) {
            final Answer refused = Raw.exec(server.port(), host(), "select 1", with(bearer(owner), "Origin", origin));
            assertThat(refused.status()).as(origin).isEqualTo(403);
            assertThat(refused.body()).isEqualTo("{\"query\":\"\",\"error\":\"refuse request from a browser page"
                    + " [origin=" + origin + "]\",\"position\":-1}");
        }
        assertThat(backend.calls).isEmpty();
    }

    /// Without secrets every client is served with no `Authorization`, and the host and origin checks still hold.
    @Test
    void withoutSecretsHostAndOriginStillHold() throws IOException {
        try (CdbServer open = CdbServer.start(backend, loopback())) {
            final String host = "127.0.0.1:" + open.port();
            assertThat(Raw.exec(open.port(), host, "update t set x = 1", Map.of()).status()).isEqualTo(200);
            assertThat(Raw.exec(open.port(), "rebound.example:" + open.port(), "select 1", Map.of()).status())
                    .isEqualTo(403);
            assertThat(Raw.exec(open.port(), host, "select 1", Map.of("Origin", "http://x")).status())
                    .isEqualTo(403);
        }
        assertThat(backend.calls).containsExactly("update t set x = 1");
    }

    /// The launcher refuses an address other than loopback without `--auth-dir`: exit status 2, the refusal naming the
    /// address on standard error, and the store's folder never made.
    @Test
    void theLauncherRefusesTheNetworkWithoutSecrets() throws IOException, InterruptedException {
        final Path store = root.resolve("store");
        final Process launched = launch(store.toString(), "0", "--bind", "0.0.0.0");
        try {
            assertThat(launched.waitFor(LAUNCH_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThat(launched.exitValue()).isEqualTo(2);
            assertThat(new String(launched.getErrorStream().readAllBytes(), StandardCharsets.UTF_8))
                    .contains("refuse to bind a non-loopback address without --auth-dir [bind=0.0.0.0]");
            assertThat(store).doesNotExist();
        } finally {
            launched.destroyForcibly();
        }
    }

    /// The launcher with `--auth-dir` makes the secrets, serves `/exec` to the owner's secret only, says so on its
    /// start line, and stops on a stop signal.
    @Test
    void theLauncherServesWithTheSecretsOfItsAuthFolder() throws Exception {
        final Path auth = root.resolve("launched-auth");
        final Process launched = launch(root.resolve("launched").toString(), "0", "--auth-dir", auth.toString());
        try {
            final BufferedReader out = new BufferedReader(new InputStreamReader(launched.getInputStream(),
                    StandardCharsets.UTF_8));
            final String line = CompletableFuture.supplyAsync(() -> {
                try {
                    return out.readLine();
                } catch (final IOException e) {
                    throw new IllegalStateException("could not read the launcher's output", e);
                }
            }).get(LAUNCH_SECONDS, TimeUnit.SECONDS);
            final Matcher serving = SERVING.matcher(line == null ? "" : line);
            assertThat(serving.matches()).as(String.valueOf(line)).isTrue();
            assertThat(serving.group(2)).isEqualTo("true");
            final int port = Integer.parseInt(serving.group(1));
            final String secret = Files.readString(auth.resolve(Secrets.OWNER_FILE), StandardCharsets.US_ASCII)
                    .strip();
            final String host = "127.0.0.1:" + port;

            assertThat(Raw.exec(port, host, "select 1", Map.of()).status()).isEqualTo(401);
            assertThat(Raw.exec(port, host, "select 1", bearer(secret)).status()).isEqualTo(200);

            launched.destroy();
            assertThat(launched.waitFor(LAUNCH_SECONDS, TimeUnit.SECONDS)).isTrue();
        } finally {
            launched.destroyForcibly();
        }
    }

    private String host() {
        return "127.0.0.1:" + server.port();
    }

    private static InetSocketAddress loopback() {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
    }

    private static Map<String, String> bearer(final String secret) {
        return Map.of("Authorization", "Bearer " + secret);
    }

    private static Map<String, String> with(final Map<String, String> headers, final String name, final String value) {
        final Map<String, String> more = new LinkedHashMap<>(headers);
        more.put(name, value);
        return more;
    }

    // the launcher in a JVM of its own, on this test's class path, as start.sh runs it
    private static Process launch(final String... args) throws IOException {
        final String java = ProcessHandle.current().info().command().orElseThrow();
        final List<String> command = new ArrayList<>(List.of(java, "-cp", System.getProperty("java.class.path"),
                CdbServe.class.getName()));
        command.addAll(List.of(args));
        return new ProcessBuilder(command).start();
    }

    /// A request's answer: its status, its status line and headers as sent, and its body.
    record Answer(int status, String head, String body) {
    }

    /// One HTTP/1.1 request on a socket of its own with every header as given, `Host` included, which the JDK's client
    /// sets itself: what a browser page or a renamed host sends. The connection closes after the answer.
    static final class Raw {

        /// Loopback answers in milliseconds; ten seconds only bound a hung server.
        private static final int TIMEOUT_MILLIS = 10_000;

        private Raw() {
        }

        /// `GET /exec?query=<sql>` naming `host`, with `headers`.
        static Answer exec(final int port, final String host, final String sql, final Map<String, String> headers)
                throws IOException {
            return send(port, "GET", "/exec?query=" + URLEncoder.encode(sql, StandardCharsets.UTF_8),
                    with(headers, "Host", host), new byte[0]);
        }

        /// `method target` with `headers` and `body`, then the answer.
        static Answer send(final int port, final String method, final String target,
                final Map<String, String> headers, final byte[] body) throws IOException {
            try (Socket socket = open(port)) {
                final OutputStream out = socket.getOutputStream();
                head(out, method, target, headers, body.length);
                out.write(body);
                out.flush();
                return read(socket);
            }
        }

        /// A connection to the server on loopback, its reads bounded.
        static Socket open(final int port) throws IOException {
            final Socket socket = new Socket(InetAddress.getLoopbackAddress(), port);
            socket.setSoTimeout(TIMEOUT_MILLIS);
            return socket;
        }

        /// Writes the request line and headers, a `Content-Length` of `length` when the method sends a body.
        static void head(final OutputStream out, final String method, final String target,
                final Map<String, String> headers, final long length) throws IOException {
            final StringBuilder head = new StringBuilder(method).append(' ').append(target).append(" HTTP/1.1\r\n");
            headers.forEach((name, value) -> head.append(name).append(": ").append(value).append("\r\n"));
            if (!method.equals("GET")) {
                head.append("Content-Length: ").append(length).append("\r\n");
            }
            head.append("Connection: close\r\n\r\n");
            out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
        }

        /// The answer on `socket`, read to the end the server's close marks.
        static Answer read(final Socket socket) throws IOException {
            final String text = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            final int split = text.indexOf("\r\n\r\n");
            assertThat(split).as(text).isPositive();
            final String head = text.substring(0, split);
            return new Answer(Integer.parseInt(head.substring(9, 12)), head, text.substring(split + 4));
        }
    }

    /// Answers every statement with one row, records each statement run, and hands out recording appenders.
    static final class Fake implements CdbServer.Backend {

        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<RecordingAppender> appenders = new CopyOnWriteArrayList<>();

        @Override
        public Rows run(final String sql, final int maxRows, final Long maxLagMillis) {
            calls.add(sql);
            return FakeRows.query(-1, List.of("n", CdbType.LONG), List.of(List.of(1L)));
        }

        @Override
        public CdbAppender appender() {
            final RecordingAppender appender = new RecordingAppender();
            appenders.add(appender);
            return appender;
        }
    }
}
