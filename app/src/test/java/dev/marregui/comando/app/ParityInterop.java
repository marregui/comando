package dev.marregui.comando.app;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import dev.marregui.comando.core.loop.Processes;
import dev.marregui.comando.frontear.mqtt.Relay;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/// frontear's `BrokerInteropTest`, unchanged, against a broker of the parity test's: it reads the broker from the
/// environment variable `COMANDO_MQTT_BROKER`, so [#run] starts a JVM of its own with that variable set, frontear's
/// compiled tests on its class path beside this one's, and [#main] there runs the class through the JUnit launcher.
/// What the run gave is its exit code ([#ALL_PASSED] when every test ran and passed), never its text (rule 12 of
/// comando's Apply section); the text is kept for the report.
final class ParityInterop {

    /// Every test of the class ran and passed.
    static final int ALL_PASSED = 0;
    /// A test failed.
    static final int FAILED = 1;
    /// The class has no test on the class path: frontear's tests are not compiled.
    static final int NOT_FOUND = 4;
    /// A test was skipped or aborted: the broker variable did not reach it, or an assumption did not hold.
    static final int NOT_RUN = 3;
    static final String TEST_CLASS = "dev.marregui.comando.frontear.mqtt.BrokerInteropTest";
    /// The run's output kept for the report, far above the class's summary and its failures' traces.
    private static final int MAX_OUTPUT = 1 << 20;
    /// The class's six tests took 7.4 s against each broker on the machine this was written on (2026-11-19), the
    /// child JVM's start included; the bound leaves room for a slow host.
    private static final Duration WITHIN = Duration.ofMinutes(5);

    private ParityInterop() {
    }

    /// Runs [#TEST_CLASS] and exits with what it gave, the summary printed to standard output.
    public static void main(final String[] args) {
        final Launcher launcher = LauncherFactory.create();
        final SummaryGeneratingListener listener = new SummaryGeneratingListener();
        launcher.execute(LauncherDiscoveryRequestBuilder.request().selectors(selectClass(TEST_CLASS)).build(),
                listener);
        final TestExecutionSummary s = listener.getSummary();
        final PrintWriter out = new PrintWriter(System.out, true);
        s.printTo(out);
        s.printFailuresTo(out, 20);
        final int code = s.getTestsFoundCount() == 0 ? NOT_FOUND
                : s.getTotalFailureCount() > 0 ? FAILED
                : s.getTestsSkippedCount() + s.getTestsAbortedCount() > 0 ? NOT_RUN : ALL_PASSED;
        out.flush();
        System.exit(code);
    }

    /// Runs the class against the broker at `at` in a JVM of its own; returns what the run gave.
    static Processes.Result run(final InetSocketAddress at) throws IOException, InterruptedException {
        final String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        final String classPath = System.getProperty("java.class.path") + File.pathSeparator + frontearTests();
        final String broker = at.getHostString() + ':' + at.getPort();
        return Processes.run(List.of(java, "-cp", classPath, "-Dcomando.peers=false", ParityInterop.class.getName()),
                env -> env.put("COMANDO_MQTT_BROKER", broker), null, null, WITHIN, MAX_OUTPUT);
    }

    /// Where Gradle compiles frontear's tests, beside its test fixtures: `frontear/build/classes/java/test`.
    static Path frontearTests() {
        final Path fixtures;
        try {
            fixtures = Path.of(Relay.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (final URISyntaxException e) {
            throw new IllegalStateException("frontear's test fixtures have no path", e);
        }
        for (Path p = fixtures; p != null; p = p.getParent()) {
            if (p.getFileName() != null && p.getFileName().toString().equals("build")) {
                final Path tests = p.resolve("classes").resolve("java").resolve("test");
                if (!Files.isDirectory(tests)) {
                    throw new IllegalStateException("frontear's tests are not compiled; run :frontear:testClasses "
                            + "[folder=" + tests + ']');
                }
                return tests;
            }
        }
        throw new IllegalStateException("frontear's test fixtures are not in a build folder [path=" + fixtures + ']');
    }
}
