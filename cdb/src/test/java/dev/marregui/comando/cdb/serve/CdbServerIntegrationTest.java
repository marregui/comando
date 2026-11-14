package dev.marregui.comando.cdb.serve;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.Cdb;

import static org.assertj.core.api.Assertions.assertThat;

/// The server in front of a real cdb under a temporary folder: a table created over `/exec`, rows written over
/// `/write` as the kit's `qdb` writes them, read back as QuestDB's JSON. Needs the store, the planner and the
/// executor; skipped while any of them is still a stub.
class CdbServerIntegrationTest {

    @TempDir
    private Path root;

    /// Create, write, read back, update and a refused query, end to end.
    @Test
    void servesTheKitsRoundTrip() throws IOException, InterruptedException {
        try (Cdb cdb = openOrSkip(root);
             CdbServer server = CdbServer.start(cdb, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
             Http http = new Http()) {
            final int port = server.port();

            final HttpResponse<String> created = http.exec(port, "CREATE TABLE IF NOT EXISTS probe (ts TIMESTAMP,"
                    + " host SYMBOL, n LONG, x DOUBLE, ok BOOLEAN, note VARCHAR) TIMESTAMP(ts) PARTITION BY DAY", "");
            assertThat(created.statusCode()).isEqualTo(200);
            assertThat(created.body()).isEqualTo("{\"ddl\":\"OK\"}");

            final HttpResponse<String> written = http.post(port, "/write?precision=n",
                    "probe,host=a n=1i,x=1.5,ok=t,note=\"hi\" 1700000000000000000\n"
                            + "probe,host=b n=2i,x=2.5,ok=f,note=\"a\\\"b\" 1700000001000000000\n");
            assertThat(written.statusCode()).isEqualTo(204);

            final HttpResponse<String> read = http.exec(port, "select ts, host, n, x, ok, note from probe", "");
            assertThat(read.statusCode()).isEqualTo(200);
            assertThat(read.body()).isEqualTo("{\"query\":\"select ts, host, n, x, ok, note from probe\",\"columns\":["
                    + "{\"name\":\"ts\",\"type\":\"TIMESTAMP\"},{\"name\":\"host\",\"type\":\"SYMBOL\"},"
                    + "{\"name\":\"n\","
                    + "\"type\":\"LONG\"},{\"name\":\"x\",\"type\":\"DOUBLE\"},{\"name\":\"ok\",\"type\":\"BOOLEAN\"},"
                    + "{\"name\":\"note\",\"type\":\"VARCHAR\"}],\"timestamp\":0,\"dataset\":["
                    + "[\"2023-11-14T22:13:20.000000Z\",\"a\",1,1.5,true,\"hi\"],"
                    + "[\"2023-11-14T22:13:21.000000Z\",\"b\",2,2.5,false,\"a\\\"b\"]],\"count\":2}");

            final HttpResponse<String> updated = http.exec(port, "UPDATE probe SET n = 3 WHERE host = 'a'", "");
            assertThat(updated.body()).isEqualTo("{\"dml\":\"OK\",\"updated\":1}");

            assertThat(http.exec(port, "select nosuch from probe", "").statusCode()).isEqualTo(400);
        }
    }

    // a part not built yet throws UnsupportedOperationException on open or on the first statement
    private static Cdb openOrSkip(final Path root) {
        final Cdb cdb;
        try {
            cdb = Cdb.open(root);
        } catch (final UnsupportedOperationException e) {
            Assumptions.abort("cdb not built yet [reason=" + e.getMessage() + ']');
            throw e;
        }
        try {
            cdb.run("select 1 x from long_sequence(1)");
        } catch (final UnsupportedOperationException e) {
            cdb.close();
            Assumptions.abort("cdb not built yet [reason=" + e.getMessage() + ']');
            throw e;
        }
        return cdb;
    }
}
