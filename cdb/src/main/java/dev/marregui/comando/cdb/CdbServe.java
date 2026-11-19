package dev.marregui.comando.cdb;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import dev.marregui.comando.cdb.exec.BackupRunner;
import dev.marregui.comando.cdb.exec.QueryPool;
import dev.marregui.comando.cdb.plan.Plan;
import dev.marregui.comando.cdb.serve.CdbServer;
import dev.marregui.comando.cdb.serve.PgServer;
import dev.marregui.comando.cdb.serve.Secrets;
import dev.marregui.comando.cdb.store.Store;
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
 * answered; see {@link StoreOptions}), and {@code --auth-dir DIR}, the folder of the secrets both servers ask their
 * clients for ({@link Secrets}; none by default: every client is served). An address other than a loopback one is
 * refused without {@code --auth-dir}: the process exits with status 2 before it opens anything.
 *
 * <p>{@code --restore <backup> <root>} instead restores a backup ({@code BACKUP TO}) into a data root that is absent or
 * an empty folder, then exits; it starts no server ({@link #restore}).
 */
public final class CdbServe {

    /** How long the shutdown hook waits for the server and the store to close; a stop signal should not hang. */
    private static final long CLOSE_WAIT_SECONDS = 10;
    private static final String USAGE = "<root> <port> [--pg-port <port>] [--bind <address>]"
            + " [--read-max-lag-ms <millis>] [--query-threads <threads>] [--log-force-ms <millis>]"
            + " [--auth-dir <folder>]";
    private static final String RESTORE = "--restore";
    private static final String RESTORE_USAGE = RESTORE + " <backup> <root>";
    /** The exit status of a command line refused before anything is opened. */
    private static final int EXIT_REFUSED = 2;

    private CdbServe() {
    }

    /**
     * Opens the cdb at {@code args[0]} and serves it on {@code args[1]} (and {@code --pg-port}) until the JVM ends. A
     * secret file whose content is not a secret fails the start, naming the file. With {@code --restore} first,
     * restores the backup {@code args[1]} into the data root {@code args[2]}, prints one line naming the tables
     * restored and returns; a refused or failed restore throws.
     */
    public static void main(final String[] args) throws InterruptedException, IOException {
        if (args.length > 0 && RESTORE.equals(args[0])) {
            if (args.length != 3) {
                throw new IllegalArgumentException("expected a backup and a data root [usage=" + RESTORE_USAGE + ']');
            }
            final Path backup = Path.of(args[1]);
            final Path root = Path.of(args[2]);
            final List<String> tables = restore(backup, root);
            System.out.println("cdb restored [backup=" + backup + ", root=" + root + ", tables=" + tables + ']');
            return;
        }
        final Options options = parse(args);
        final InetAddress address = options.bind() == null
                ? InetAddress.getLoopbackAddress() : InetAddress.getByName(options.bind());
        if (!address.isLoopbackAddress() && options.authDir() == null) {
            // a server other machines reach without a secret serves every change to anyone on the network
            System.err.println("refuse to bind a non-loopback address without --auth-dir [bind=" + options.bind()
                    + ']');
            System.exit(EXIT_REFUSED);
            return;
        }
        CdbServer.applyJdkLimits();
        final Secrets secrets = options.authDir() == null ? null : Secrets.load(options.authDir());
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
             final CdbServer server = CdbServer.start(cdb, new InetSocketAddress(address, options.port()), secrets);
             final PgServer pg = options.pgPort() < 0
                     ? null : PgServer.start(cdb, new InetSocketAddress(address, options.pgPort()), secrets)) {
            System.out.println("cdb serving [root=" + options.root() + ", address=" + address.getHostAddress()
                    + ", port=" + server.port() + (pg == null ? "" : ", pgPort=" + pg.port()) + ", auth="
                    + (secrets != null) + ']');
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
        Path authDir = null;
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
                case "--auth-dir" -> authDir = Path.of(value(args, i++));
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
                readMaxLagMillis, queryThreads, logForceMillis, authDir);
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

    /**
     * Restores the backup at {@code backup} into the data root {@code root}, which must be absent or an empty folder
     * and no store may hold: opens the backup as a store, which proves it, and copies it as {@code BACKUP TO} copies a
     * store, into a folder beside the root that is renamed to it once whole, an empty root removed first. Answers the
     * tables restored. A refusal or a failure throws and leaves the root as it was, but an empty root found gone.
     */
    static List<String> restore(final Path backup, final Path root) {
        final Path from = backup.toAbsolutePath().normalize();
        final Path to = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(from)) {
            throw new CdbException("cannot restore from a backup that is not a folder [backup=" + from + ']', -1);
        }
        if (to.startsWith(from)) {
            throw new CdbException("refuse to restore into a folder inside the backup [backup=" + from + ", root=" + to
                    + ']', -1);
        }
        if (!absentOrEmpty(to)) {
            throw new CdbException("refuse to restore into a data root that is not empty [root=" + to + ']', -1);
        }
        try (Store store = Store.open(from)) {
            final List<String> tables = store.tables();
            try {
                // the copy is renamed to the root, which must not exist then; a root filled meanwhile stays, refused
                if (Files.isDirectory(to, LinkOption.NOFOLLOW_LINKS)) {
                    Files.delete(to);
                }
            } catch (final IOException e) {
                throw new CdbException("refuse to restore into a data root that is not empty [root=" + to + ']', e);
            }
            BackupRunner.run(new Plan.Backup(to.toString()), store);
            return tables;
        }
    }

    private static boolean absentOrEmpty(final Path root) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return true;
        }
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            return !entries.iterator().hasNext();
        } catch (final IOException e) {
            throw new CdbException("cannot list data root [root=" + root + ']', e);
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
     * What the command line asked for; {@code pgPort} and {@code queryThreads} are -1 and {@code bind} and
     * {@code authDir} null when not given, {@code logForceMillis} 0.
     */
    public record Options(Path root, int port, int pgPort, String bind, long readMaxLagMillis, int queryThreads,
            long logForceMillis, Path authDir) {
    }
}
