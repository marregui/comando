package dev.marregui.comando.app;

import java.awt.EventQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.LogManager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// [Logging] records uncaught exceptions from application and event-dispatch threads.
///
/// - installs a default uncaught-exception handler
/// - writes a stack trace for each unhandled exception
class LoggingTest {

    /// Uncaught worker and event-dispatch exceptions each reach the rotating application log with their thread names.
    @Test
    void anExceptionNoCodeCaughtIsLoggedFromTheEventThreadAndFromAnyOther(@TempDir final Path dir) throws Exception {
        final Thread.UncaughtExceptionHandler before = Thread.getDefaultUncaughtExceptionHandler();
        try {
            Logging.configure(dir);
            final Thread worker = Thread.ofPlatform().name("worker-boom").start(() -> {
                throw new IllegalStateException("from a worker");
            });
            worker.join();
            EventQueue.invokeLater(() -> {
                throw new IllegalStateException("from the event thread");
            });
            EventQueue.invokeAndWait(() -> {
            });
        } finally {
            LogManager.getLogManager().reset();
            Thread.setDefaultUncaughtExceptionHandler(before);
        }
        final String log = Files.readString(dir.resolve("comando-0.log"));
        assertThat(log).contains("SEVERE Uncaught uncaught in thread worker-boom", "java.lang.IllegalStateException: from a worker");
        assertThat(log).contains("SEVERE Uncaught uncaught in thread AWT-EventQueue", "java.lang.IllegalStateException: from the event thread");
    }
}
