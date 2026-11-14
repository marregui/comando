package dev.marregui.comando.cdb;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import dev.marregui.comando.cdb.serve.CdbServer;

/**
 * Runs a cdb behind QuestDB's HTTP formats ({@code /exec}, {@code /write}) on the loopback address until the process
 * is stopped: the shadow server the parity runs and the dual writes reach. Arguments: the root folder, then the port.
 */
public final class CdbServe {

    /** How long the shutdown hook waits for the server and the store to close; a stop signal should not hang. */
    private static final long CLOSE_WAIT_SECONDS = 10;

    private CdbServe() {
    }

    /** Opens the cdb at {@code args[0]} and serves it on {@code 127.0.0.1:args[1]} until the JVM shuts down. */
    public static void main(final String[] args) throws InterruptedException {
        if (args.length != 2) {
            throw new IllegalArgumentException("expected two arguments [usage=<root> <port>]");
        }
        final Path root = Path.of(args[0]);
        final int port = Integer.parseInt(args[1]);
        final CountDownLatch stopped = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        // the JVM owns shutdown-hook threads; this one releases main, then holds the exit until main has closed
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stopped.countDown();
            try {
                closed.await(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (final InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }, "cdb-serve-shutdown"));
        try (Cdb cdb = Cdb.open(root);
             CdbServer server = CdbServer.start(cdb, new InetSocketAddress(InetAddress.getLoopbackAddress(), port))) {
            System.out.println("cdb serving [root=" + root + ", port=" + server.port() + "]");
            stopped.await();
        } finally {
            closed.countDown();
        }
    }
}
