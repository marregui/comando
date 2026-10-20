package dev.marregui.comando.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Routes {@link System.Logger} (backed by java.util.logging) to a rotating file under the data root and
 * warnings to stderr. One line per record: time, level, logger, message, then the stack trace. An exception no
 * code caught, on the event thread or any other, is logged too: without a handler it reaches stderr only.
 */
final class Logging {

    private Logging() {
    }

    static void configure(Path dir) {
        LogManager.getLogManager().reset();
        Logger root = Logger.getLogger("");
        root.setLevel(Level.INFO);
        Formatter format = new Line();
        try {
            Files.createDirectories(dir);
            // 1 MiB a file, so the log always opens in a panel (Tools ▸ Help ▸ Show the log), whose limit is 2 MiB; 10
            // files, the count a notice's click searches for its entry (HelpFiles.LOGS)
            FileHandler file = new FileHandler(dir.resolve("comando-%g.log").toString(), 1024 * 1024, 10, true);
            file.setFormatter(format);
            file.setLevel(Level.INFO);
            root.addHandler(file);
        } catch (IOException e) {
            System.err.println("comando: cannot write logs to " + dir + ": " + e.getMessage());
        }
        Handler console = new ConsoleHandler();
        console.setFormatter(format);
        console.setLevel(Level.WARNING);
        root.addHandler(console);
        Thread.setDefaultUncaughtExceptionHandler(Logging::uncaught);
    }

    static void uncaught(Thread t, Throwable e) {
        try {
            Logger.getLogger("dev.marregui.comando.Uncaught").log(Level.SEVERE, "uncaught in thread " + t.getName(), e);
        } catch (Throwable logging) {
            // a logger that cannot load or write still leaves the trace on stderr
            e.printStackTrace();
        }
    }

    private static final class Line extends Formatter {
        @Override
        public String format(LogRecord r) {
            StringBuilder sb = new StringBuilder(160);
            sb.append(Instant.ofEpochMilli(r.getMillis())).append(' ')
                    .append(r.getLevel().getName()).append(' ')
                    .append(shortName(r.getLoggerName())).append(' ')
                    .append(formatMessage(r)).append('\n');
            if (r.getThrown() != null) {
                StringWriter w = new StringWriter();
                r.getThrown().printStackTrace(new PrintWriter(w));
                sb.append(w);
            }
            return sb.toString();
        }

        private static String shortName(String name) {
            if (name == null) {
                return "-";
            }
            int dot = name.lastIndexOf('.');
            return dot < 0 ? name : name.substring(dot + 1);
        }
    }
}
