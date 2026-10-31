package dev.marregui.comando.db;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;

import io.questdb.ServerMain;

/** A QuestDB server in a temporary directory, Postgres wire on a free port, everything else off. */
final class EmbeddedQuestDb implements AutoCloseable {

    private final ServerMain server;
    private final int pgPort;

    EmbeddedQuestDb(final Path root) throws IOException {
        pgPort = freePort();
        final Path conf = Files.createDirectories(root.resolve("conf"));
        Files.writeString(conf.resolve("server.conf"), String.join("\n",
                "pg.net.bind.to=127.0.0.1:" + pgPort,
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
    }

    int pgPort() {
        return pgPort;
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Override
    public void close() {
        server.close();
    }
}
