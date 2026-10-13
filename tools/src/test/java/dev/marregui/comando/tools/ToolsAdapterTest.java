package dev.marregui.comando.tools;

import dev.marregui.comando.core.console.ConsoleRegistry;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.testkit.RecordingSink;
import jdk.jfr.Recording;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ToolsAdapterTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    private final RecordingSink sink = new RecordingSink();
    private final ConsoleRegistry consoles = new ConsoleRegistry();

    private ToolsAdapter adapter(Path scratchpad, Path jfrq) {
        return new ToolsAdapter(sink, consoles, new ToolSettings(Optional.empty(), jfrq, scratchpad, System.getProperty("java.home")));
    }

    private static final String REPORT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="dev.x.LaneTest" tests="2" skipped="0" failures="1" errors="0" time="0.5">
              <testcase name="cancelReturnsFast()" classname="dev.x.LaneTest" time="0.4">
                <failure message="expected: 1 but was: 2" type="org.opentest4j.AssertionFailedError">org.opentest4j.AssertionFailedError: expected: 1 but was: 2
            \tat dev.x.LaneTest.cancelReturnsFast(LaneTest.java:42)
            </failure>
              </testcase>
              <testcase name="opens()" classname="dev.x.LaneTest" time="0.1"/>
            </testsuite>
            """;

    /** A project whose gradlew prints its arguments and writes one JUnit report with a failure. */
    private static Path fakeBuild(Path dir) throws Exception {
        Path src = dir.resolve("db/src/test/java/dev/x");
        Files.createDirectories(src);
        Files.writeString(src.resolve("LaneTest.java"), "class LaneTest {}\n");
        Path report = dir.resolve("db/build/test-results/test");
        Files.createDirectories(report);
        Files.writeString(dir.resolve("report.xml"), REPORT);
        Path gradlew = dir.resolve("gradlew");
        Files.writeString(gradlew, "#!/bin/sh\necho \"args: $*\"\ncp report.xml db/build/test-results/test/TEST-dev.x.LaneTest.xml\nexit 1\n");
        Files.setPosixFilePermissions(gradlew, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.createDirectories(dir.resolve("db/sub"));
        return dir;
    }

    @Test
    void gradleRunsInTheBuildRootAndReportsFailedTests(@TempDir Path dir) throws Exception {
        fakeBuild(dir);
        adapter(dir, dir.resolve("none")).runGradle(7L, dir.resolve("db/sub").toString(), ":db:test --tests 'Lane*'");
        Intent.CommandFinished end = sink.await(Intent.CommandFinished.class, WAIT);
        assertThat(end.runId()).isEqualTo(7L);
        assertThat(end.exitCode()).isEqualTo(1);
        assertThat(end.tests().label()).isEqualTo("1 of 2 tests failed");
        ViewSpec.TreeNode suite = end.tests().children().getFirst();
        assertThat(suite.label()).isEqualTo("LaneTest");
        assertThat(suite.children().getFirst().label()).isEqualTo("✗ cancelReturnsFast()");
        assertThat(suite.children().getFirst().detail()).isEqualTo("expected: 1 but was: 2");
        assertThat(suite.children().getFirst().link()).endsWith("db/src/test/java/dev/x/LaneTest.java#42");
        assertThat(end.failures()).containsKey("LaneTest.cancelReturnsFast()");
        // the quotes group the argument as a shell would and do not reach Gradle
        assertThat(consoles.of(7L).text(0)).contains("--console=plain :db:test --tests Lane*").contains("args: --console=plain :db:test --tests Lane*")
                .contains("Exit code 1");
    }

    @Test
    void anUnreadableDirectoryInTheBuildStillEndsTheRun(@TempDir Path dir) throws Exception {
        fakeBuild(dir);
        Path locked = Files.createDirectories(dir.resolve("db/build/test-results/locked"));
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            adapter(dir, dir.resolve("none")).runGradle(4L, dir.toString(), "test");
            Intent.CommandFinished end = sink.await(Intent.CommandFinished.class, WAIT);
            assertThat(end.runId()).isEqualTo(4L);
            assertThat(end.tests().label()).as("the readable reports still count").isEqualTo("1 of 2 tests failed");
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void argumentsSplitLikeAShell() {
        assertThat(Args.split("stalls --thread 'event-loop-*'")).containsExactly("stalls", "--thread", "event-loop-*");
        assertThat(Args.split("--thread \"C2 CompilerThread*\" x\\ y")).containsExactly("--thread", "C2 CompilerThread*", "x y");
        assertThat(Args.split("  ")).isEmpty();
        assertThat(Args.split("a '' b")).containsExactly("a", "", "b");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Args.split("a 'b")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noBuildAboveTheDirectoryIsReported(@TempDir Path dir) throws Exception {
        adapter(dir, dir.resolve("none")).runGradle(3L, dir.toString(), "build");
        assertThat(sink.await(Intent.CommandFinished.class, WAIT).exitCode()).isEqualTo(2);
        assertThat(consoles.of(3L).text(0)).contains("No gradlew");
    }

    @Test
    void cancelStopsTheProcessTree(@TempDir Path dir) throws Exception {
        Path gradlew = dir.resolve("gradlew");
        Files.writeString(gradlew, "#!/bin/sh\necho started\nsleep 30 &\nwait\n");
        Files.setPosixFilePermissions(gradlew, PosixFilePermissions.fromString("rwxr-xr-x"));
        ToolsAdapter tools = adapter(dir, dir.resolve("none"));
        tools.runGradle(5L, dir.toString(), "build");
        long deadline = System.currentTimeMillis() + 10_000;
        while (!consoles.of(5L).text(0).contains("started") && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        long t0 = System.nanoTime();
        tools.cancelRun(5L);
        Intent.CommandFinished end = sink.await(Intent.CommandFinished.class, WAIT);
        assertThat(end.cancelled()).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void aConsoleClosedWhileItsRunGoesIsReleasedWhenTheRunEnds(@TempDir Path dir) throws Exception {
        Path gradlew = dir.resolve("gradlew");
        Files.writeString(gradlew, "#!/bin/sh\necho started\nsleep 1\n");
        Files.setPosixFilePermissions(gradlew, PosixFilePermissions.fromString("rwxr-xr-x"));
        ToolsAdapter tools = adapter(dir, dir.resolve("none"));
        tools.runGradle(3L, dir.toString(), "build");
        long deadline = System.currentTimeMillis() + 10_000;
        while (!consoles.of(3L).text(0).contains("started") && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        tools.forgetRun(3L); // the view closed; the run goes on
        assertThat(consoles.size()).isEqualTo(1);
        sink.await(Intent.CommandFinished.class, WAIT);
        assertThat(consoles.size()).isZero();
    }

    @Test
    void cancelKillsAProcessThatIgnoresSigterm(@TempDir Path dir) throws Exception {
        Path gradlew = dir.resolve("gradlew");
        Files.writeString(gradlew, "#!/bin/sh\ntrap '' TERM\necho started\nsleep 30\n");
        Files.setPosixFilePermissions(gradlew, PosixFilePermissions.fromString("rwxr-xr-x"));
        ToolsAdapter tools = adapter(dir, dir.resolve("none"));
        tools.runGradle(6L, dir.toString(), "build");
        long deadline = System.currentTimeMillis() + 10_000;
        while (!consoles.of(6L).text(0).contains("started") && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        long t0 = System.nanoTime();
        tools.cancelRun(6L);
        Intent.CommandFinished end = sink.await(Intent.CommandFinished.class, WAIT);
        assertThat(end.cancelled()).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - t0))
                .isGreaterThanOrEqualTo(Duration.ofMillis(ToolsAdapter.KILL_AFTER_MILLIS - 100))
                .isLessThan(Duration.ofSeconds(6));
    }

    @Test
    void aChildHoldingTheOutputOpenDoesNotKeepTheRunGoing(@TempDir Path dir) throws Exception {
        Path gradlew = dir.resolve("gradlew");
        Files.writeString(gradlew, "#!/bin/sh\necho done\nsleep 8 &\nexit 0\n");
        Files.setPosixFilePermissions(gradlew, PosixFilePermissions.fromString("rwxr-xr-x"));
        long t0 = System.nanoTime();
        adapter(dir, dir.resolve("none")).runGradle(8L, dir.toString(), "build");
        Intent.CommandFinished end = sink.await(Intent.CommandFinished.class, WAIT);
        assertThat(end.exitCode()).isZero();
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(6));
        assertThat(consoles.of(8L).text(0)).contains("done", "still holds the output open", "Exit code 0");
    }

    @Test
    void jfrqGetsTheCommandTheRecordingAndTheOptions(@TempDir Path dir) throws Exception {
        Path jfrq = dir.resolve("jfrq");
        Files.writeString(jfrq, "#!/bin/sh\necho \"jfrq $* java=$JAVA_HOME\"\n");
        Files.setPosixFilePermissions(jfrq, PosixFilePermissions.fromString("rwxr-xr-x"));
        adapter(dir, jfrq).runJfrq(9L, "/r/app.jfr", "health --top 5");
        assertThat(sink.await(Intent.CommandFinished.class, WAIT).exitCode()).isZero();
        assertThat(consoles.of(9L).text(0)).contains("jfrq health /r/app.jfr --top 5 java=" + System.getProperty("java.home"));
    }

    @Test
    void recordingBecomesATableAndPlots(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("r.jfr");
        try (Recording r = new Recording()) {
            r.enable("jdk.CPULoad").withPeriod(Duration.ofMillis(100));
            r.enable("jdk.GCHeapSummary");
            r.start();
            for (int i = 0; i < 5; i++) {
                byte[][] garbage = new byte[2000][];
                for (int j = 0; j < garbage.length; j++) {
                    garbage[j] = new byte[1024];
                }
                System.gc();
                Thread.sleep(150);
            }
            r.stop();
            r.dump(file);
        }
        adapter(dir, dir.resolve("none")).openRecording(file.toString());
        Intent.ShowView table = sink.await(Intent.ShowView.class, v -> v.title().startsWith("jfr · "), WAIT);
        assertThat(((ViewSpec.Table) table.spec()).rows()).extracting(List::getFirst).contains("jdk.CPULoad", "jdk.GCHeapSummary");
        Intent.ShowView cpu = sink.await(Intent.ShowView.class, v -> v.title().startsWith("cpu · "), WAIT);
        assertThat(cpu.placement()).isEqualTo(Placement.LOWER);
        assertThat(((ViewSpec.Plot) cpu.spec()).series()).hasSize(3);
        sink.await(Intent.ShowView.class, v -> v.title().startsWith("heap · "), WAIT);
    }

    @Test
    void scratchpadTreeOrdersDaysAndDocuments(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("PLAN.md"), "# plan");
        Files.createDirectories(dir.resolve("datahub"));
        for (String day : List.of("2026-10-11", "2026-10-12")) {
            Path d = Files.createDirectories(dir.resolve(day));
            Files.writeString(d.resolve("ALPHA.md"), "a");
            Files.writeString(d.resolve("READ-01-X.md"), "r");
            Files.createDirectories(d.resolve("work")).resolve("spec.md").toFile().createNewFile();
        }
        adapter(dir, dir.resolve("none")).browseScratchpad();
        Intent.ShowView v = sink.await(Intent.ShowView.class, WAIT);
        assertThat(v.placement()).isEqualTo(Placement.SIDE);
        ViewSpec.TreeNode root = ((ViewSpec.Tree) v.spec()).root();
        assertThat(root.children()).extracting(ViewSpec.TreeNode::label).containsExactly("PLAN.md", "2026-10-12/", "2026-10-11/", "datahub/");
        assertThat(root.children().get(1).children()).extracting(ViewSpec.TreeNode::label).containsExactly("READ-01-X.md", "ALPHA.md", "work/");
        assertThat(root.children().getFirst().link()).isEqualTo("comando-file:" + dir.resolve("PLAN.md") + "#0");
    }
}
