package dev.marregui.comando.cdb;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import dev.marregui.comando.cdb.serve.CdbServer;
import dev.marregui.comando.cdb.serve.PgServer;

/**
 * Runs a cdb behind QuestDB's HTTP formats ({@code /exec}, {@code /write}) on the loopback address until the process
 * is stopped: the shadow server the parity runs and the dual writes reach. Arguments: the root folder, then the port;
 * then, optionally, {@code --pg-port N} to serve the PostgreSQL wire too (none by default) and {@code --bind ADDR}, the
 * address both servers listen on ({@code 127.0.0.1} by default; {@code 0.0.0.0} in a container).
 */
public final class CdbServe {

    /** How long the shutdown hook waits for the server and the store to close; a stop signal should not hang. */
    private static final long CLOSE_WAIT_SECONDS = 10;
    private static final String USAGE = "<root> <port> [--pg-port <port>] [--bind <address>]";

    private CdbServe() {
    }

    /** Opens the cdb at {@code args[0]} and serves it on {@code args[1]} (and {@code --pg-port}) until the JVM ends. */
    public static void main(final String[] args) throws InterruptedException, UnknownHostException {
        String bind = null;
        int pgPort = -1;
        final List<String> positional = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--pg-port" -> pgPort = Integer.parseInt(value(args, i++));
                case "--bind" -> bind = value(args, i++);
                default -> positional.add(args[i]);
            }
        }
        if (positional.size() != 2) {
            throw new IllegalArgumentException("expected two arguments [usage=" + USAGE + ']');
        }
        final Path root = Path.of(positional.get(0));
        final int port = Integer.parseInt(positional.get(1));
        final InetAddress address = bind == null ? InetAddress.getLoopbackAddress() : InetAddress.getByName(bind);
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
             CdbServer server = CdbServer.start(cdb, new InetSocketAddress(address, port));
             PgServer pg = pgPort < 0 ? null : PgServer.start(cdb, new InetSocketAddress(address, pgPort))) {
            System.out.println("cdb serving [root=" + root + ", address=" + address.getHostAddress() + ", port="
                    + server.port() + (pg == null ? "" : ", pgPort=" + pg.port()) + ']');
            stopped.await();
        } finally {
            closed.countDown();
        }
    }

    private static String value(final String[] args, final int option) {
        if (option + 1 >= args.length) {
            throw new IllegalArgumentException("missing option value [option=" + args[option] + ", usage=" + USAGE
                    + ']');
        }
        return args[option + 1];
    }
}
