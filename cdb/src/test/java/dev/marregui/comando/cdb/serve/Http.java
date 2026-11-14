package dev.marregui.comando.cdb.serve;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/// A loopback HTTP client for the server's tests, as the kit's clients call it: form-encoded query parameters, bodies
/// as UTF-8.
final class Http implements AutoCloseable {

    /// Loopback answers in milliseconds; ten seconds only bounds a hung server.
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(TIMEOUT)
            .build();

    /// `GET /exec?query=<sql>` with `extra` appended as it is (`&limit=2`).
    HttpResponse<String> exec(final int port, final String sql, final String extra)
            throws IOException, InterruptedException {
        return get(port, "/exec?query=" + URLEncoder.encode(sql, StandardCharsets.UTF_8) + extra);
    }

    /// `GET` of `pathAndQuery` as it is.
    HttpResponse<String> get(final int port, final String pathAndQuery) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(uri(port, pathAndQuery)).GET());
    }

    /// `POST` of `body` to `pathAndQuery`.
    HttpResponse<String> post(final int port, final String pathAndQuery, final String body)
            throws IOException, InterruptedException {
        return post(port, pathAndQuery, body.getBytes(StandardCharsets.UTF_8));
    }

    /// `POST` of `body` to `pathAndQuery`.
    HttpResponse<String> post(final int port, final String pathAndQuery, final byte[] body)
            throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(uri(port, pathAndQuery)).POST(HttpRequest.BodyPublishers.ofByteArray(body)));
    }

    @Override
    public void close() {
        client.close();
    }

    private HttpResponse<String> send(final HttpRequest.Builder request) throws IOException, InterruptedException {
        return client.send(request.timeout(TIMEOUT).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static URI uri(final int port, final String pathAndQuery) {
        return URI.create("http://127.0.0.1:" + port + pathAndQuery);
    }
}
