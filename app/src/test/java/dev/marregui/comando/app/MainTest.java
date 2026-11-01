package dev.marregui.comando.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// [Main] describes the message shown when a data-root instance lock is already held.
///
/// - includes the contended root path
/// - explains that the earlier process may still be closing
/// - refuses the data root's lock while another process holds it
class MainTest {

    /// A contended root names its exact path and directs the user to the existing window or a later retry.
    @Test
    void aSecondInstanceSaysTheFirstMayStillBeClosing() {
        assertThat(Main.lockedMessage(Path.of("/w/.comando")))
                .isEqualTo("comando is already running on /w/.comando, or still closing; switch to its window, or try again in a few seconds.");
    }

    /// The data root's `instance.json` names the instance; a missing, unreadable or nameless file names none.
    @Test
    void theDataRootsInstanceFileNamesTheInstance(@TempDir final Path home) throws IOException {
        assertThat(Main.nameFile(home)).as("no file").isNull();
        Files.writeString(home.resolve("instance.json"), "{\"name\": \"Comando Expert\", \"slug\": \"b\"}");
        assertThat(Main.nameFile(home)).isEqualTo("Comando Expert");
        Files.writeString(home.resolve("instance.json"), "{\"slug\": \"b\"}");
        assertThat(Main.nameFile(home)).as("no name").isNull();
        Files.writeString(home.resolve("instance.json"), "{not json");
        assertThat(Main.nameFile(home)).as("unreadable").isNull();
    }

    /// While another process holds the data root's `comando.lock`, a second instance cannot take it, so it starts no
    /// second window on the same files.
    @Test
    void theDataRootsLockHeldByAnotherProcessIsRefused(@TempDir final Path dir) throws Exception {
        final Path home = Files.createDirectories(dir.resolve("root"));
        final Path locker = Files.writeString(dir.resolve("Locker.java"), """
                import java.nio.channels.FileChannel;
                import java.nio.file.Path;
                import java.nio.file.StandardOpenOption;

                public class Locker {
                    public static void main(final String[] args) throws Exception {
                        try (FileChannel c = FileChannel.open(Path.of(args[0]), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                             var held = c.lock()) {
                            System.out.println("locked");
                            System.out.flush();
                            System.in.read();
                        }
                    }
                }
                """);
        final String java = ProcessHandle.current().info().command().orElseThrow();
        final Process p = new ProcessBuilder(java, locker.toString(), home.resolve("comando.lock").toString()).redirectErrorStream(true).start();
        try {
            final BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            assertThat(CompletableFuture.supplyAsync(() -> {
                try {
                    return out.readLine();
                } catch (final IOException e) {
                    return e.toString();
                }
            }).get(60, TimeUnit.SECONDS)).isEqualTo("locked");
            assertThat(Main.lock(home)).isFalse();
        } finally {
            p.getOutputStream().close();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        }
    }
}
