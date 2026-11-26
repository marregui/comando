package dev.marregui.comando.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;

/// A page that keeps state in `localStorage` loads again in a later run, as the HTML pages comando's work makes do
/// (the learn-map pages keep their side panel's width there). On OpenJFX 25.0.5 and 25.0.5.1 the second run's page
/// never loads: WebKit's main thread, which is JavaFX's and AppKit's on macOS, waits for the storage thread, and the
/// EDT waits for the main thread, so comando freezes. The storage outlives a JVM, so each run is a child JVM of its own
/// over one data folder. JavaFX needs a display, so this runs in `./gradlew :app:uiTest`.
/// - the first run makes the page's storage, the second run opens it and reads the first run's count, and both load
///   the page
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class StoredPageUiTest {

    /**
     * The bound on one child's start and page load: the first page of a JVM, which starts WebKit, took 4.2 s on the
     * MacBook Pro {@code WebPageViewUiTest} was written on (2026-11-22); a JVM start on top of that, and over three
     * times both. A deadlocked load never ends, so the bound only decides how long the failure takes.
     */
    private static final Duration LOAD = Duration.ofSeconds(30);

    /** A killed child is gone at once; chosen, as in {@code UiTestLockTest}, to fail rather than hang if it is not. */
    private static final long END_SECONDS = 10L;

    @TempDir
    private Path dir;

    /// Two runs over one data folder: the page writes and reads `localStorage` in each, and each run loads it.
    @Test
    void aPageLoadsAgainOverTheStorageAnEarlierRunLeft() throws Exception {
        final Path page = Files.writeString(dir.resolve("stored.html"), """
                <!doctype html><html><head><title>Stored</title></head><body><p id="out">waiting</p>
                <script>
                const runs = Number(localStorage.getItem('runs') || '0') + 1;
                localStorage.setItem('runs', String(runs));
                document.getElementById('out').textContent = 'run ' + runs;
                </script></body></html>
                """);
        final Path data = Files.createDirectories(dir.resolve("webview"));

        // WebKit writes the storage on a timer after the page has set it: the first child lives until the file is there
        final Path stored = data.resolve("localstorage").resolve("file__0.localstorage");
        assertThat(open(data, page, "first", () -> Files.isRegularFile(stored) && Files.size(stored) > 0))
                .isEqualTo("run 1");
        assertThat(open(data, page, "second", () -> true)).as("the second run read the first run's storage")
                .isEqualTo("run 2");
    }

    /**
     * Runs one child over {@code data}, waits for its page and then for {@code settled}, ends it and returns the page's
     * text; fails with the child's output if either does not come.
     */
    private String open(final Path data, final Path page, final String run, final Callable<Boolean> settled)
            throws IOException, InterruptedException {
        final Path loaded = dir.resolve(run + ".loaded");
        final Path log = dir.resolve(run + ".log");
        final Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED", "-Djava.awt.headless=false", "-Dapple.awt.UIElement=true", "-cp",
                System.getProperty("java.class.path"), StoredPageOpener.class.getName(), data.toString(),
                page.toString(), loaded.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            await().atMost(LOAD).until(() -> Files.exists(loaded) || !child.isAlive());
            if (!Files.exists(loaded)) {
                fail("the " + run + " run's page did not load [exited=" + !child.isAlive() + ", output="
                        + Files.readString(log) + ']');
            }
            await().atMost(LOAD).until(settled);
            return Files.readString(loaded);
        } catch (final ConditionTimeoutException _) {
            return fail("the " + run + " run did not load and settle within " + LOAD + " [output="
                    + Files.readString(log) + ']');
        } finally {
            child.destroyForcibly();
            assertThat(child.waitFor(END_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }
}
