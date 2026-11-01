package dev.marregui.comando.db;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.questdb.ServerMain;

/**
 * A QuestDB server in a temporary directory, Postgres wire on a port the system picks at the bind (no port is chosen
 * first and bound later, which another test fork could take in between), everything else off.
 */
final class EmbeddedQuestDb implements AutoCloseable {

    private final ServerMain server;
    private final int pgPort;

    EmbeddedQuestDb(final Path root) throws IOException {
        final Path conf = Files.createDirectories(root.resolve("conf"));
        Files.writeString(conf.resolve("server.conf"), String.join("\n",
                "pg.net.bind.to=127.0.0.1:0",
                "http.enabled=false",
                "http.min.enabled=false",
                "line.tcp.enabled=false",
                "line.udp.enabled=false",
                "telemetry.enabled=false",
                "shared.worker.count=2",
                "pg.worker.count=1",
                ""));
        server = new ServerMain("-d", root.toString());
        server.start(false);
        pgPort = server.getPgWireServerPort();
        if (pgPort <= 0) {
            server.close();
            throw new IOException("QuestDB did not report its Postgres port [port=" + pgPort + ']');
        }
    }

    int pgPort() {
        return pgPort;
    }

    @Override
    public void close() {
        server.close();
    }
}
