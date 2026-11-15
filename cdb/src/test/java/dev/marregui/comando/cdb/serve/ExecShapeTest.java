package dev.marregui.comando.cdb.serve;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.Cdb;

import static org.assertj.core.api.Assertions.assertThat;

/// `/exec` answers as QuestDB's does where cdb differed: a request with no query text answered 200 with its error in
/// the body, and `limit=lo,hi` serving rows lo to hi, counted from 1.
class ExecShapeTest {

    private static Http http;

    @TempDir
    private Path root;

    private Cdb cdb;
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

    /// A fresh cdb and server per test, on a free loopback port.
    @BeforeEach
    void start() {
        cdb = Cdb.open(root);
        server = CdbServer.start(cdb, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
    }

    /// Stops the server and closes the cdb.
    @AfterEach
    void stop() {
        server.close();
        cdb.close();
    }

    /// No query and a blank one are answered 200 with QuestDB's empty-query error, position as text.
    @Test
    void anEmptyQueryIsAnsweredWithAnErrorBody() throws IOException, InterruptedException {
        final HttpResponse<String> none = http.get(server.port(), "/exec");
        final HttpResponse<String> blank = http.exec(server.port(), " ", "");

        assertThat(none.statusCode()).isEqualTo(200);
        assertThat(none.body()).isEqualTo("{\"error\":\"empty query\",\"query\":\"\",\"position\":\"0\"}");
        assertThat(blank.statusCode()).isEqualTo(200);
        assertThat(blank.body()).isEqualTo("{\"error\":\"empty query\",\"query\":\" \",\"position\":\"0\"}");
    }

    /// `limit=2,4` serves rows 2 to 4 of the answer, counted from 1, and counts the rows it skipped as QuestDB does;
    /// `limit=4,` and `limit=4,x` serve from the fourth on; a bound below the start, `5,2` or `3,0`, serves one row.
    @Test
    void aRangeLimitServesRowsFromTheLowerBound() throws IOException, InterruptedException {
        final String query = "select x from long_sequence(6)";

        assertThat(http.exec(server.port(), query, "&limit=2,4").body())
                .contains("\"dataset\":[[2],[3],[4]],\"count\":4}");
        assertThat(http.exec(server.port(), query, "&limit=4,x").body()).contains("\"dataset\":[[4],[5],[6]]");
        assertThat(http.exec(server.port(), query, "&limit=3,0").body()).contains("\"dataset\":[[3]]");
        assertThat(http.exec(server.port(), query, "&limit=4,").body()).contains("\"dataset\":[[4],[5],[6]]");
        assertThat(http.exec(server.port(), query, "&limit=0,2").body()).contains("\"dataset\":[[1],[2]]");
        assertThat(http.exec(server.port(), query, "&limit=5,2").body()).contains("\"dataset\":[[5]],\"count\":5}");
    }
}
