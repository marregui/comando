package dev.marregui.comando.cdb.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// A store's mapping count against the process's limit and its warning.
///
/// - no readable limit (the file absent, as on macOS, or not a number): no limit, no warning at any count
/// - a limit of 100: one warning at 82, none more until the count fell under 76, then one again past 81
/// - threads crossing the threshold together log one warning
class MappingsTest {

    private static final String STORE = "/data/cdb";

    /// Without the limit file the limit reads 0 and no count warns.
    @Test
    void anAbsentLimitFileWatchesNoLimit(@TempDir final Path dir) {
        final Captured log = new Captured();
        final Mappings mappings = new Mappings(dir.resolve("max_map_count"), STORE, log);
        mappings.mapped(1_000_000);
        assertThat(mappings.limit()).isZero();
        assertThat(mappings.count()).isEqualTo(1_000_000L);
        assertThat(log.warnings).isEmpty();
    }

    /// A limit file that does not hold a number is no limit either.
    @Test
    void anUnreadableLimitIsNoLimit(@TempDir final Path dir) throws Exception {
        final Path file = Files.writeString(dir.resolve("max_map_count"), "unlimited\n");
        final Captured log = new Captured();
        final Mappings mappings = new Mappings(file, STORE, log);
        mappings.mapped(1_000);
        assertThat(mappings.limit()).isZero();
        assertThat(log.warnings).isEmpty();
    }

    /// With a limit of 100: 81 mappings do not warn, the 82nd does, once; falling to 76 does not re-arm, so 82 again
    /// is silent; falling to 75 re-arms, and passing 81 again warns a second time.
    @Test
    void theWarningFiresOncePerCrossingWithHysteresis(@TempDir final Path dir) throws Exception {
        final Path file = Files.writeString(dir.resolve("max_map_count"), "100\n");
        final Captured log = new Captured();
        final Mappings mappings = new Mappings(file, STORE, log);
        assertThat(mappings.limit()).isEqualTo(100L);

        mappings.mapped(81);
        assertThat(log.warnings).isEmpty();
        mappings.mapped(1);
        assertThat(log.warnings).containsExactly(warning(82L));
        mappings.mapped(10);
        assertThat(log.warnings).hasSize(1);

        mappings.unmapped(16);
        assertThat(mappings.count()).isEqualTo(76L);
        mappings.mapped(6);
        assertThat(log.warnings).hasSize(1);

        mappings.unmapped(7);
        assertThat(mappings.count()).isEqualTo(75L);
        mappings.mapped(6);
        assertThat(log.warnings).hasSize(1);
        mappings.mapped(1);
        assertThat(log.warnings).containsExactly(warning(82L), warning(82L));
    }

    /// Eight threads, started together by a barrier, map one at a time from 0 to 800 against a limit of 100: the count
    /// passes 81 once and stays above it, so exactly one warning is logged, by whichever map crossed.
    @Test
    void threadsCrossingTogetherWarnOnce(@TempDir final Path dir) throws Exception {
        final Path file = Files.writeString(dir.resolve("max_map_count"), "100");
        final Captured log = new Captured();
        final Mappings mappings = new Mappings(file, STORE, log);
        final int threads = 8;
        final CyclicBarrier start = new CyclicBarrier(threads);
        final CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            Thread.ofPlatform().name("mappings-test-" + t).start(() -> {
                try {
                    start.await(10L, TimeUnit.SECONDS);
                    for (int i = 0; i < 100; i++) {
                        mappings.mapped(1);
                    }
                } catch (final Exception _) {
                    // a broken barrier leaves the count short, which the assertion below reports
                } finally {
                    done.countDown();
                }
            });
        }
        assertThat(done.await(10L, TimeUnit.SECONDS)).isTrue();
        assertThat(mappings.count()).isEqualTo(800L);
        assertThat(log.warnings).hasSize(1);
    }

    private static String warning(final long count) {
        return "mappings near the process limit [mappings=" + count + ", limit=100, store=" + STORE + ']';
    }

    /// A logger keeping the messages logged at WARNING, so the test reads them without a `LoggerFinder`.
    private static final class Captured implements System.Logger {

        final List<String> warnings = new CopyOnWriteArrayList<>();

        @Override
        public String getName() {
            return "captured";
        }

        @Override
        public boolean isLoggable(final Level level) {
            return true;
        }

        @Override
        public void log(final Level level, final ResourceBundle bundle, final String msg, final Throwable thrown) {
            if (level == Level.WARNING) {
                warnings.add(msg);
            }
        }

        @Override
        public void log(final Level level, final ResourceBundle bundle, final String format, final Object... params) {
            if (level == Level.WARNING) {
                warnings.add(format);
            }
        }
    }
}
