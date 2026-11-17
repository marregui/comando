package dev.marregui.comando.cdb;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import dev.marregui.comando.cdb.exec.QueryPool;
import dev.marregui.comando.cdb.serve.CdbServer;
import dev.marregui.comando.cdb.serve.PgServer;
import dev.marregui.comando.cdb.store.StoreOptions;

/**
 * Runs a cdb behind QuestDB's HTTP formats ({@code /exec}, {@code /write}) on the loopback address until the process
 * is stopped: the machine's server, which comando's {@code start.sh} runs (record 180). Arguments: the root folder,
 * then the port; then, optionally, {@code --pg-port N} to serve the PostgreSQL wire too (none by default) and
 * {@code --bind ADDR}, the address both servers listen on ({@code 127.0.0.1} by default; {@code 0.0.0.0} in a
 * container), and {@code --read-max-lag-ms N}, the most a read may lag the answered commits when its caller names none
 * (0 by default: every read sees every answered commit), {@code --query-threads N}, the workers of the process's query
 * pool (one fewer than the cores by default; 0 runs every query on its caller's thread), and {@code --log-force-ms N},
 * the most a table log's written records wait for their force (0 by default: each commit is forced before it is
 * answered; see {@link StoreOptions}).
 */
public final class CdbServe {

    /** How long the shutdown hook waits for the server and the store to close; a stop signal should not hang. */
    private static final long CLOSE_WAIT_SECONDS = 10;
    private static final String USAGE = "<root> <port> [--pg-port <port>] [--bind <address>]"
            + " [--read-max-lag-ms <millis>] [--query-threads <threads>] [--log-force-ms <millis>]";

    private CdbServe() {
    }

    /** Opens the cdb at {@code args[0]} and serves it on {@code args[1]} (and {@code --pg-port}) until the JVM ends. */
    public static void main(final String[] args) throws InterruptedException, UnknownHostException {
        final Options options = parse(args);
        final InetAddress address = options.bind() == null
                ? InetAddress.getLoopbackAddress() : InetAddress.getByName(options.bind());
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
        try (final Cdb cdb = open(options);
             final CdbServer server = CdbServer.start(cdb, new InetSocketAddress(address, options.port()));
             final PgServer pg = options.pgPort() < 0
                     ? null : PgServer.start(cdb, new InetSocketAddress(address, options.pgPort()))) {
            System.out.println("cdb serving [root=" + options.root() + ", address=" + address.getHostAddress()
                    + ", port=" + server.port() + (pg == null ? "" : ", pgPort=" + pg.port()) + ']');
            stopped.await();
        } finally {
            closed.countDown();
        }
    }

    /**
     * The command line, checked: two positional arguments, the options, a read lag, a query pool size and a log force
     * interval not negative.
     */
    public static Options parse(final String[] args) {
        String bind = null;
        int pgPort = -1;
        long readMaxLagMillis = 0L;
        int queryThreads = -1;
        long logForceMillis = 0L;
        final List<String> positional = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--pg-port" -> pgPort = Integer.parseInt(value(args, i++));
                case "--bind" -> bind = value(args, i++);
                case "--read-max-lag-ms" -> readMaxLagMillis = Long.parseLong(value(args, i++));
                case "--query-threads" -> queryThreads = notNegative("--query-threads",
                        Integer.parseInt(value(args, i++)));
                case "--log-force-ms" -> logForceMillis = notNegative("--log-force-ms",
                        Long.parseLong(value(args, i++)));
                default -> positional.add(args[i]);
            }
        }
        if (positional.size() != 2) {
            throw new IllegalArgumentException("expected two arguments [usage=" + USAGE + ']');
        }
        if (readMaxLagMillis < 0L) {
            throw new IllegalArgumentException("refuse negative read lag [millis=" + readMaxLagMillis + ']');
        }
        return new Options(Path.of(positional.get(0)), Integer.parseInt(positional.get(1)), pgPort, bind,
                readMaxLagMillis, queryThreads, logForceMillis);
    }

    /**
     * Sizes the query pool when {@code options} name a size, then opens the store at their root with their log force
     * interval and read lag: what {@link #main} serves. A pool already running with another size fails it, nothing
     * opened.
     */
    public static Cdb open(final Options options) {
        // the pool is sized before the store opens, so nothing has made it with the default size yet
        if (options.queryThreads() >= 0) {
            QueryPool.configure(options.queryThreads());
        }
        // the store's default read lag is set before anything serves
        final Cdb cdb = Cdb.open(options.root(), new StoreOptions(options.logForceMillis()));
        try {
            cdb.readMaxLagMillis(options.readMaxLagMillis());
            return cdb;
        } catch (final RuntimeException | Error e) {
            cdb.close();
            throw e;
        }
    }

    private static int notNegative(final String option, final int value) {
        if (value < 0) {
            throw new IllegalArgumentException("refuse negative option value [option=" + option + ", value=" + value
                    + ']');
        }
        return value;
    }

    private static long notNegative(final String option, final long value) {
        if (value < 0L) {
            throw new IllegalArgumentException("refuse negative option value [option=" + option + ", value=" + value
                    + ']');
        }
        return value;
    }

    private static String value(final String[] args, final int option) {
        if (option + 1 >= args.length) {
            throw new IllegalArgumentException("missing option value [option=" + args[option] + ", usage=" + USAGE
                    + ']');
        }
        return args[option + 1];
    }

    /**
     * What the command line asked for; {@code pgPort} and {@code queryThreads} are -1 and {@code bind} null when not
     * given, {@code logForceMillis} 0.
     */
    public record Options(Path root, int port, int pgPort, String bind, long readMaxLagMillis, int queryThreads,
            long logForceMillis) {
    }
}
