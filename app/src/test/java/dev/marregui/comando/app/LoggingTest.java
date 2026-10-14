package dev.marregui.comando.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.EventQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.LogManager;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingTest {

    @Test
    void anExceptionNoCodeCaughtIsLoggedFromTheEventThreadAndFromAnyOther(@TempDir Path dir) throws Exception {
        Thread.UncaughtExceptionHandler before = Thread.getDefaultUncaughtExceptionHandler();
        try {
            Logging.configure(dir);
            Thread worker = Thread.ofPlatform().name("worker-boom").start(() -> {
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
        String log = Files.readString(dir.resolve("comando-0.log"));
        assertThat(log).contains("SEVERE Uncaught uncaught in thread worker-boom", "java.lang.IllegalStateException: from a worker");
        assertThat(log).contains("SEVERE Uncaught uncaught in thread AWT-EventQueue", "java.lang.IllegalStateException: from the event thread");
    }
}
