package dev.marregui.comando.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.console.ConsoleRegistry;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.testkit.RecordingSink;

import static org.assertj.core.api.Assertions.assertThat;

/// [ToolsAdapter] runs Gradle with its console, reads the watched folders' trees, and moves entries to the Trash.
/// - Gradle runs in the build root and reports failed tests with their place; a run after close starts nothing.
/// - Arguments split like a shell; a missing build is reported.
/// - Cancel stops the whole process tree, even one that ignores SIGTERM; a child holding the output open does not keep
///   the run going.
/// - Each run reads the settings again; a console closed during its run is released when the run ends.
/// - Folder trees are sent when read and when changed, ordered (the index first, folders, day folders by date, files),
///   without hidden, build and excluded entries.
/// - An unreadable folder or subfolder says so in its tree; a large, deep or linked folder is bounded and says so.
/// - Entries go to the Trash and leave their lines in the nearest listing; nothing goes without a Trash or outside the
///   folder.
class ToolsAdapterTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    private final RecordingSink sink = new RecordingSink();
    private final ConsoleRegistry consoles = new ConsoleRegistry();

    private ToolsAdapter adapter() {
        return new ToolsAdapter(sink, consoles, () -> new ToolSettings(Optional.empty(), System.getProperty("java.home")));
    }

    private static FolderRoot folder(final String name, final Path path, final String... excludes) {
        return new FolderRoot(name, path.toString(), "", List.of(excludes));
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
    private static Path fakeBuild(final Path dir) throws Exception {
        final Path src = dir.resolve("db/src/test/java/dev/x");
        Files.createDirectories(src);
        Files.writeString(src.resolve("LaneTest.java"), "class LaneTest {}\n");
        final Path report = dir.resolve("db/build/test-results/test");
        Files.createDirectories(report);
        Files.writeString(dir.resolve("report.xml"), REPORT);
        final Path gradlew = dir.resolve("gradlew");
        Files.writeString(gradlew, "#!/bin/sh\necho \"args: $*\"\ncp report.xml db/build/test-results/test/TEST-dev.x.LaneTest.xml\nexit 1\n");
        Files.setPosixFilePermissions(gradlew, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.createDirectories(dir.resolve("db/sub"));
        return dir;
    }

    /// A run asked for after the adapter closed ends at once with `Not started: comando is closing` and no command
    /// line.
    @Test
    void aRunAskedForAfterCloseStartsNoProcess(@TempDir final Path dir) throws Exception {
        fakeBuild(dir);
        final ToolsAdapter tools = adapter();
        tools.close();
        tools.runGradle(8L, dir.toString(), "test");
        final Intent.CommandFinished end = sink.await(Intent.CommandFinished.class, WAIT);
        assertThat(end.runId()).isEqualTo(8L);
        assertThat(consoles.of(8L).text(0)).contains("Not started: comando is closing").doesNotContain("args:");
    }

    /// A Gradle run with one failing test of two ends with exit code 1, `1 of 2 tests failed`, a suite tree whose
    /// failed test carries its message and a link to its source line, and the quoted `--tests` argument passed without
    /// its quotes.
    @Test
    void gradleRunsInTheBuildRootAndReportsFailedTests(@TempDir final Path dir) throws Exception {
        fakeBuild(dir);
        adapter().runGradle(7L, dir.resolve("db/sub").toString(), ":db:test --tests 'Lane*'");
        final Intent.CommandFinished end = sink.await(Intent.CommandFinished.class, WAIT);
        assertThat(end.runId()).isEqualTo(7L);
        assertThat(end.exitCode()).isEqualTo(1);
        assertThat(end.tests().label()).isEqualTo("1 of 2 tests failed");
        final ViewSpec.TreeNode suite = end.tests().children().getFirst();
        assertThat(suite.label()).isEqualTo("LaneTest");
        assertThat(suite.children().getFirst().label()).isEqualTo("✗ cancelReturnsFast()");
        assertThat(suite.children().getFirst().detail()).isEqualTo("expected: 1 but was: 2");
        assertThat(suite.children().getFirst().link()).endsWith("db/src/test/java/dev/x/LaneTest.java#42");
        assertThat(end.failures()).containsKey("LaneTest.cancelReturnsFast()");
        // the quotes group the argument as a shell would and do not reach Gradle
        assertThat(consoles.of(7L).text(0)).contains("--console=plain :db:test --tests Lane*").contains("args: --console=plain :db:test --tests Lane*")
                .contains("Exit code 1");
    }

    /// A build folder the run cannot read does not stop it: the run ends and the readable reports still count.
    @Test
    void anUnreadableDirectoryInTheBuildStillEndsTheRun(@TempDir final Path dir) throws Exception {
        fakeBuild(dir);
        final Path locked = Files.createDirectories(dir.resolve("db/build/test-results/locked"));
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            adapter().runGradle(4L, dir.toString(), "test");
            final Intent.CommandFinished end = sink.await(Intent.CommandFinished.class, WAIT);
            assertThat(end.runId()).isEqualTo(4L);
            assertThat(end.tests().label()).as("the readable reports still count").isEqualTo("1 of 2 tests failed");
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    /// [Args#split] honours single and double quotes and backslash escapes, keeps an empty quoted argument, gives
    /// nothing for blanks, and refuses an unclosed quote.
    @Test
    void argumentsSplitLikeAShell() {
        assertThat(Args.split("stalls --thread 'event-loop-*'")).containsExactly("stalls", "--thread", "event-loop-*");
        assertThat(Args.split("--thread \"C2 CompilerThread*\" x\\ y")).containsExactly("--thread", "C2 CompilerThread*", "x y");
        assertThat(Args.split("  ")).isEmpty();
        assertThat(Args.split("a '' b")).containsExactly("a", "", "b");
        Assertions.assertThatThrownBy(() -> Args.split("a 'b")).isInstanceOf(IllegalArgumentException.class);
    }

    /// A Gradle run in a folder with no `gradlew` above it ends with exit code 2 and says `No gradlew`.
    @Test
    void noBuildAboveTheDirectoryIsReported(@TempDir final Path dir) throws Exception {
        adapter().runGradle(3L, dir.toString(), "build");
        assertThat(sink.await(Intent.CommandFinished.class, WAIT).exitCode()).isEqualTo(2);
        assertThat(consoles.of(3L).text(0)).contains("No gradlew");
    }

    /// Cancelling a run whose script started a child ends it cancelled within 5 s, the child with it.
    @Test
    void cancelStopsTheProcessTree(@TempDir final Path dir) throws Exception {
        final Path gradlew = dir.resolve("gradlew");
        Files.writeString(gradlew, "#!/bin/sh\necho started\nsleep 30 &\nwait\n");
        Files.setPosixFilePermissions(gradlew, PosixFilePermissions.fromString("rwxr-xr-x"));
        final ToolsAdapter tools = adapter();
        tools.runGradle(5L, dir.toString(), "build");
        final long deadline = System.currentTimeMillis() + 10_000;
        while (!consoles.of(5L).text(0).contains("started") && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        final long t0 = System.nanoTime();
        tools.cancelRun(5L);
        final Intent.CommandFinished end = sink.await(Intent.CommandFinished.class, WAIT);
        assertThat(end.cancelled()).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(5));
    }

    /// Forgetting a run while it goes keeps its console until the run ends, then releases it.
    @Test
    void aConsoleClosedWhileItsRunGoesIsReleasedWhenTheRunEnds(@TempDir final Path dir) throws Exception {
        final Path gradlew = dir.resolve("gradlew");
        Files.writeString(gradlew, "#!/bin/sh\necho started\nsleep 1\n");
        Files.setPosixFilePermissions(gradlew, PosixFilePermissions.fromString("rwxr-xr-x"));
        final ToolsAdapter tools = adapter();
        tools.runGradle(3L, dir.toString(), "build");
        final long deadline = System.currentTimeMillis() + 10_000;
        while (!consoles.of(3L).text(0).contains("started") && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        tools.forgetRun(3L); // the view closed; the run goes on
        assertThat(consoles.size()).isEqualTo(1);
        sink.await(Intent.CommandFinished.class, WAIT);
        assertThat(consoles.size()).isZero();
    }

    /// A process that traps SIGTERM is killed after the grace period, and the run ends cancelled.
    @Test
    void cancelKillsAProcessThatIgnoresSigterm(@TempDir final Path dir) throws Exception {
        final Path gradlew = dir.resolve("gradlew");
        Files.writeString(gradlew, "#!/bin/sh\ntrap '' TERM\necho started\nsleep 30\n");
        Files.setPosixFilePermissions(gradlew, PosixFilePermissions.fromString("rwxr-xr-x"));
        final ToolsAdapter tools = adapter();
        tools.runGradle(6L, dir.toString(), "build");
        final long deadline = System.currentTimeMillis() + 10_000;
        while (!consoles.of(6L).text(0).contains("started") && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        final long t0 = System.nanoTime();
        tools.cancelRun(6L);
        final Intent.CommandFinished end = sink.await(Intent.CommandFinished.class, WAIT);
        assertThat(end.cancelled()).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - t0))
                .isGreaterThanOrEqualTo(Duration.ofMillis(ToolsAdapter.KILL_AFTER_MILLIS - 100))
                .isLessThan(Duration.ofSeconds(6));
    }

    /// A script that exits while its child keeps the output open for 8 s ends with exit code 0 in under 6 s, its output
    /// and exit code in the console.
    @Test
    void aChildHoldingTheOutputOpenDoesNotKeepTheRunGoing(@TempDir final Path dir) throws Exception {
        final Path gradlew = dir.resolve("gradlew");
        Files.writeString(gradlew, "#!/bin/sh\necho done\nsleep 8 &\nexit 0\n");
        Files.setPosixFilePermissions(gradlew, PosixFilePermissions.fromString("rwxr-xr-x"));
        final long t0 = System.nanoTime();
        adapter().runGradle(8L, dir.toString(), "build");
        final Intent.CommandFinished end = sink.await(Intent.CommandFinished.class, WAIT);
        assertThat(end.exitCode()).isZero();
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).as("not the child's 8 s").isLessThan(Duration.ofSeconds(6));
        // the notice shows when the reader is still waiting on the pipe; at the exit the JDK's process reaper drains
        // and closes the pipe (on Linux and macOS alike) unless the reader is inside a read then, so which one happens
        // is a matter of thread timing, and in the drained case the reader ends with nothing to report
        assertThat(consoles.of(8L).text(0)).contains("done", "Exit code 0");
    }

    /// Each run reads the settings anew: a wrapper script that appears between two runs is used by the second.
    @Test
    void eachRunReadsTheSettings(@TempDir final Path dir) throws Exception {
        fakeBuild(dir);
        final Path serial = dir.resolve("serial");
        Files.writeString(serial, "#!/bin/sh\necho \"serial $*\"\n");
        Files.setPosixFilePermissions(serial, PosixFilePermissions.fromString("rwxr-xr-x"));
        final List<ToolSettings> settings = new ArrayList<>(List.of(
                new ToolSettings(Optional.empty(), System.getProperty("java.home")),
                new ToolSettings(Optional.of(serial), System.getProperty("java.home"))));
        final ToolsAdapter tools = new ToolsAdapter(sink, consoles, settings::removeFirst);

        tools.runGradle(1L, dir.toString(), "build");
        sink.await(Intent.CommandFinished.class, e -> e.runId() == 1L, WAIT);
        tools.runGradle(2L, dir.toString(), "build");
        sink.await(Intent.CommandFinished.class, e -> e.runId() == 2L, WAIT);

        assertThat(consoles.of(1L).text(0)).startsWith("$ " + dir.resolve("gradlew"));
        assertThat(consoles.of(2L).text(0)).as("the wrapper appeared between runs").startsWith("$ " + serial + " " + dir + " build")
                .contains("serial " + dir + " build");
    }

    /// A read sends every tree, unchanged ones too; a newly watched folder is read at once; unchanged polls send
    /// nothing and an unwatched folder is not read; a folder watched with new settings is read with them at once.
    @Test
    void aReadSendsEveryTreeAndAWatchSendsAnOpenFolderOnlyWhenItChanged(@TempDir final Path dir) throws Exception {
        final FolderRoot a = folder("A", Files.createDirectories(dir.resolve("a")));
        final FolderRoot b = folder("B", Files.createDirectories(dir.resolve("b")));
        Files.writeString(dir.resolve("a").resolve("PLAN.md"), "# plan");
        try (ToolsAdapter tools = adapter()) {
            tools.readFolders(List.of(a, b));
            sink.await(Intent.FolderLoaded.class, l -> l.folder().name().equals("B"), WAIT);
            sink.await(Intent.FolderLoaded.class, l -> l.folder().name().equals("A"), WAIT);
            tools.readFolders(List.of(a));
            sink.await(Intent.FolderLoaded.class, l -> l.folder().name().equals("A") && sink.of(Intent.FolderLoaded.class).size() == 3, WAIT);
            assertThat(sink.of(Intent.FolderLoaded.class)).as("a read sends even an unchanged tree").hasSize(3);

            sink.clear();
            tools.watchFolders(List.of(a));
            final Intent.FolderLoaded first = sink.await(Intent.FolderLoaded.class, WAIT);
            assertThat(first.folder()).as("a folder newly watched is read at once").isEqualTo(a);
            tools.pollFolders(Duration.ofMillis(20));
            Files.createDirectory(dir.resolve("b").resolve("unwatched"));
            Files.createDirectory(dir.resolve("a").resolve("changed"));
            sink.await(Intent.FolderLoaded.class, l -> l.tree().children().size() == 2, WAIT);
            assertThat(sink.of(Intent.FolderLoaded.class)).as("unchanged polls send nothing; a folder not watched is not read")
                    .extracting(l -> l.folder().name()).containsExactly("A", "A");

            sink.clear();
            tools.watchFolders(List.of(folder("A", dir.resolve("a"), "changed")));
            final Intent.FolderLoaded edited = sink.await(Intent.FolderLoaded.class, WAIT);
            assertThat(edited.tree().children()).as("a folder watched with new settings is read with them at once")
                    .extracting(ViewSpec.TreeNode::label).containsExactly("PLAN.md");
        }
    }

    /// A missing folder's tree holds one `✗ cannot be read: no such folder` node, reads as unreadable, and raises no
    /// notice.
    @Test
    void aFolderThatCannotBeReadSaysSoInItsTreeWithoutANotice(@TempDir final Path dir) throws Exception {
        adapter().readFolders(List.of(folder("Gone", dir.resolve("missing"))));
        final ViewSpec.TreeNode tree = sink.await(Intent.FolderLoaded.class, WAIT).tree();
        assertThat(tree.label()).isEqualTo("Gone");
        assertThat(tree.children()).singleElement().extracting(ViewSpec.TreeNode::label).isEqualTo("✗ cannot be read: no such folder");
        assertThat(FolderTree.isUnreadable(tree)).isTrue();
        assertThat(sink.of(Intent.Notify.class)).as("no notice at every refresh").isEmpty();
    }

    /// A scratchpad tree lists folders before files, day folders in date order, every level, files with a link to line
    /// 0 and folders without one, and leaves out hidden, build and excluded entries.
    @Test
    void aFolderTreeOrdersDaysAndDocumentsAndLeavesOutHiddenBuildAndExcludedEntries(@TempDir final Path dir) throws Exception {
        Files.writeString(dir.resolve("PLAN.md"), "# plan");
        Files.createDirectories(dir.resolve("datahub"));
        for (final String left : List.of(".git", "build", "node_modules", "Tools")) {
            Files.createDirectories(dir.resolve(left));
        }
        Files.writeString(dir.resolve(".DS_Store"), "x");
        for (final String day : List.of("2026-10-11", "2026-10-12")) {
            final Path d = Files.createDirectories(dir.resolve(day));
            Files.writeString(d.resolve("ALPHA.md"), "a");
            Files.writeString(d.resolve("READ-01-X.md"), "r");
            Files.createDirectories(d.resolve("work")).resolve("spec.md").toFile().createNewFile();
            Files.writeString(Files.createDirectories(d.resolve("work").resolve("deep")).resolve("deeper.md"), "d");
        }
        adapter().readFolders(List.of(folder("Scratchpad", dir, "tools")));
        final ViewSpec.TreeNode root = sink.await(Intent.FolderLoaded.class, WAIT).tree();
        assertThat(root.label()).isEqualTo("Scratchpad");
        assertThat(root.children()).extracting(ViewSpec.TreeNode::label).containsExactly("datahub", "2026-10-11", "2026-10-12", "PLAN.md");
        assertThat(root.children().get(2).children()).as("folders first, then files by name").extracting(ViewSpec.TreeNode::label)
                .containsExactly("work", "ALPHA.md", "READ-01-X.md");
        assertThat(root.children().get(2).children().getFirst().children()).as("every level, not three")
                .extracting(ViewSpec.TreeNode::label).containsExactly("deep", "spec.md");
        assertThat(root.children().get(2).children().getFirst().children().getFirst().children())
                .extracting(ViewSpec.TreeNode::label).containsExactly("deeper.md");
        assertThat(root.children().getLast().link()).isEqualTo("comando-file:" + dir.resolve("PLAN.md") + "#0");
        assertThat(root.children().getFirst().link()).as("a folder has no link").isEmpty();
    }

    /** A Trash that moves each entry into {@code bin}, as the desktop's would, and records it. */
    private ToolsAdapter withTrash(final Path bin, final List<Path> trashed) {
        return new ToolsAdapter(sink, consoles, () -> new ToolSettings(Optional.empty(), System.getProperty("java.home")), file -> {
            Files.move(file, bin.resolve(file.getFileName() + "-" + trashed.size()));
            trashed.add(file);
            return true;
        });
    }

    /// Moving a file and then a folder to the Trash removes their lines from the nearest listing file, says how many
    /// lines went, and leaves no temporary file beside the listing.
    @Test
    void entriesGoToTheTrashAndLeaveTheirOwnLinesInTheNearestListingAndTheOpenFoldersShowIt(@TempDir final Path dir, @TempDir final Path bin) throws Exception {
        final Path day = Files.createDirectories(dir.resolve("2026-10-18"));
        final Path work = Files.createDirectories(day.resolve("work"));
        final Path read = Files.writeString(day.resolve("READ-01-X-OCT-18.md"), "# x");
        final Path mine = Files.writeString(work.resolve("mine.py"), "print()");
        Files.writeString(day.resolve("ALPHA.md"), """
                ## To read

                1. `READ-01-X-OCT-18.md`: the result.

                | Artefact | Holds |
                |---|---|
                | `work/` | `mine.py` |
                | READ-01-X-OCT-18.md | the result |
                """);
        final FolderRoot pad = new FolderRoot("Scratchpad", dir.toString(), "", List.of(), "scratchpad").withTrash("ALPHA.md");
        final List<Path> trashed = new CopyOnWriteArrayList<>();
        final ToolsAdapter tools = withTrash(bin, trashed);
        tools.watchFolders(List.of(pad));
        sink.await(Intent.FolderLoaded.class, WAIT);

        tools.deleteFiles(pad, List.of(read.toString()));
        final Intent.Notify done = sink.await(Intent.Notify.class, n -> n.text().startsWith("Moved"), WAIT);
        assertThat(done.level()).isEqualTo(Notice.Level.INFO);
        assertThat(done.text()).isEqualTo("Moved READ-01-X-OCT-18.md to the Trash; 2 lines out of 2026-10-18/ALPHA.md");
        assertThat(trashed).containsExactly(read);
        assertThat(day.resolve("ALPHA.md")).content().doesNotContain("READ-01").contains("| `work/` |");
        sink.await(Intent.FolderLoaded.class, l -> l.tree().children().getFirst().children().stream().noneMatch(c -> c.label().startsWith("READ")), WAIT);

        tools.deleteFiles(pad, List.of(mine.toString(), work.toString()));
        final Intent.Notify folder = sink.await(Intent.Notify.class, n -> n.text().startsWith("Moved work"), WAIT);
        assertThat(folder.text()).as("a file inside a folder asked for goes with it").isEqualTo("Moved work to the Trash; 1 line out of 2026-10-18/ALPHA.md");
        assertThat(trashed).containsExactly(read, work);
        assertThat(day.resolve("ALPHA.md")).content().doesNotContain("work/");
        try (var left = Files.list(day)) {
            assertThat(left.map(p -> p.getFileName().toString())).as("no temporary file left").containsExactly("ALPHA.md");
        }
    }

    /// Without a Trash nothing is moved and nothing deleted; a missing entry, a path outside the folder, and an entry
    /// of a folder not marked deletable are refused.
    @Test
    void nothingGoesWithoutATrashAndNeitherTheFolderNorAnythingOutsideItEverGoes(@TempDir final Path dir, @TempDir final Path bin) throws Exception {
        final Path a = Files.writeString(dir.resolve("a.md"), "a");
        final Path outside = Files.writeString(bin.resolve("outside.md"), "o");
        final FolderRoot pad = new FolderRoot("Scratchpad", dir.toString(), "", List.of()).withTrash("");
        adapter().deleteFiles(pad, List.of(a.toString()));
        final Intent.Notify none = sink.await(Intent.Notify.class, n -> n.level() == Notice.Level.ERROR, WAIT);
        assertThat(none.text()).isEqualTo("Nothing moved to the Trash; cannot move a.md: this desktop has no Trash");
        assertThat(a).as("a Trash missing: never deleted for good instead").exists();

        final List<Path> trashed = new CopyOnWriteArrayList<>();
        final ToolsAdapter tools = withTrash(Files.createDirectories(bin.resolve("bin")), trashed);
        tools.deleteFiles(pad, List.of(dir.toString(), outside.toString(), dir.resolve("gone.md").toString()));
        final Intent.Notify refused = sink.await(Intent.Notify.class, n -> n.level() == Notice.Level.ERROR && n.text().contains("outside.md"), WAIT);
        assertThat(refused.text()).startsWith("Nothing moved to the Trash; cannot move ").contains("gone.md: no such file or folder");
        assertThat(trashed).isEmpty();
        assertThat(outside).exists();

        tools.deleteFiles(new FolderRoot("KB", dir.toString(), "", List.of()), List.of(a.toString()));
        sink.await(Intent.Notify.class, n -> n.text().contains("a.md: not an entry of folder KB"), WAIT);
        assertThat(a).as("a folder not marked deletable").exists();
    }

    /// When a day's listing goes to the Trash with its entries, the listing above is left as it was, and an entry the
    /// Trash refuses is named while the others still go.
    @Test
    void aListingThatGoesWithItsEntriesIsNotReplacedByOneAboveAndARefusedEntryDoesNotStopTheRest(@TempDir final Path dir, @TempDir final Path bin) throws Exception {
        final Path day = Files.createDirectories(dir.resolve("2026-10-18"));
        final Path alpha = Files.writeString(day.resolve("ALPHA.md"), "- `x.md`: x\n");
        final Path x = Files.writeString(day.resolve("x.md"), "x");
        final Path locked = Files.writeString(day.resolve("locked.md"), "l");
        Files.writeString(dir.resolve("ALPHA.md"), "- `2026-10-18/x.md`: listed above too\n");
        final FolderRoot pad = new FolderRoot("Scratchpad", dir.toString(), "", List.of()).withTrash("ALPHA.md");
        final List<Path> trashed = new CopyOnWriteArrayList<>();
        final ToolsAdapter tools = new ToolsAdapter(sink, consoles, () -> new ToolSettings(Optional.empty(), System.getProperty("java.home")), file -> {
            if (file.equals(locked)) {
                throw new IOException("the Trash refused it");
            }
            Files.move(file, bin.resolve(file.getFileName()));
            trashed.add(file);
            return true;
        });
        tools.deleteFiles(pad, List.of(alpha.toString(), locked.toString(), x.toString()));
        final Intent.Notify n = sink.await(Intent.Notify.class, WAIT);
        assertThat(n.text()).isEqualTo("Moved 2 entries to the Trash; cannot move locked.md: the Trash refused it");
        assertThat(trashed).containsExactly(alpha, x);
        assertThat(dir.resolve("ALPHA.md")).as("the day's listing went too: the one above is not the day's").hasContent("- `2026-10-18/x.md`: listed above too\n");
    }

    /// The folder's index file comes first at the top level only, then folders, then files; without an index, folders
    /// first.
    @Test
    void theIndexFileLeadsItsFolderThenFoldersThenFiles(@TempDir final Path dir) throws Exception {
        Files.writeString(dir.resolve("feedback.md"), "f");
        Files.writeString(dir.resolve("MEMORY.md"), "m");
        Files.writeString(dir.resolve("agent.md"), "a");
        Files.createDirectories(dir.resolve("archive")).resolve("MEMORY.md").toFile().createNewFile();
        Files.writeString(dir.resolve("archive").resolve("a.md"), "a");
        final ViewSpec.TreeNode root = FolderTree.tree(new FolderRoot("Memory", dir.toString(), "MEMORY.md", List.of()));
        assertThat(root.children()).extracting(ViewSpec.TreeNode::label).containsExactly("MEMORY.md", "archive", "agent.md", "feedback.md");
        assertThat(root.children().get(1).children()).as("a file named as the index deeper down is any file")
                .extracting(ViewSpec.TreeNode::label).containsExactly("a.md", "MEMORY.md");
        assertThat(FolderTree.tree(folder("Memory", dir)).children()).as("no index set: folders first")
                .extracting(ViewSpec.TreeNode::label).containsExactly("archive", "agent.md", "feedback.md", "MEMORY.md");
    }

    /// A folder with more than [FolderTree#MAX_ENTRIES] entries lists that many and ends with the more marker.
    @Test
    void aLargeFolderIsReadUpToTheBoundAndEndsSayingSo(@TempDir final Path dir) throws Exception {
        for (int i = 0; i < FolderTree.MAX_ENTRIES + 10; i++) {
            Files.createFile(dir.resolve("f" + i + ".md"));
        }
        Files.createDirectories(dir.resolve("sub")).resolve("x.md").toFile().createNewFile();
        final ViewSpec.TreeNode tree = FolderTree.tree(folder("Big", dir));
        assertThat(tree.children()).hasSize(FolderTree.MAX_ENTRIES + 1);
        assertThat(tree.children().getLast().label()).isEqualTo(ViewSpec.TreeNode.MORE);
    }

    /// A folder of very many names, shown or hidden, is listed only one past [FolderTree#MAX_LISTED] and ends with the
    /// more marker.
    @Test
    void aFolderOfAMillionNamesIsListedOnlyUpToTheBoundShownOrHidden(@TempDir final Path dir) throws Exception {
        for (final String prefix : List.of("note", ".hidden")) {
            final AtomicInteger pulled = new AtomicInteger();
            final FolderTree.Lister million = d -> IntStream.range(0, 1_000_000).mapToObj(i -> d.resolve(prefix + i)).peek(p -> pulled.incrementAndGet());
            final ViewSpec.TreeNode tree = FolderTree.tree(folder("Big", dir), million);
            assertThat(pulled.get()).as(prefix + ": one past the bound is listed, to know there are more").isEqualTo(FolderTree.MAX_LISTED + 1);
            assertThat(tree.children().getLast().label()).isEqualTo(ViewSpec.TreeNode.MORE);
            assertThat(tree.children()).hasSize(prefix.startsWith(".") ? 1 : FolderTree.MAX_ENTRIES + 1);
        }
    }

    /// Once one folder spends the listing bound, no later folder is listed: it shows only the more marker.
    @Test
    void onceTheBoundIsSpentNoFurtherFolderIsListed(@TempDir final Path dir) throws Exception {
        for (int i = 0; i < 50; i++) {
            Files.createDirectories(dir.resolve(String.format("d%02d", i)));
        }
        final AtomicInteger listedAfter = new AtomicInteger();
        final FolderTree.Lister lister = d -> {
            if (d.equals(dir)) {
                return Files.list(d);
            }
            if (d.getFileName().toString().equals("d00")) {
                return IntStream.range(0, 1_000_000).mapToObj(i -> d.resolve(".hidden" + i));
            }
            listedAfter.incrementAndGet();
            return Files.list(d);
        };
        final ViewSpec.TreeNode tree = FolderTree.tree(folder("Many", dir), lister);
        assertThat(listedAfter).as("the first folder spent the bound: none after it is listed").hasValue(0);
        assertThat(tree.children().get(1).children()).singleElement().extracting(ViewSpec.TreeNode::label).isEqualTo(ViewSpec.TreeNode.MORE);
    }

    /// A subfolder without read permission shows `cannot be read` with a `permission denied` child, its siblings stand,
    /// and the tree as a whole is not unreadable.
    @Test
    void aSubfolderThatCannotBeReadSaysSoAndTheRestStands(@TempDir final Path dir) throws Exception {
        Files.writeString(dir.resolve("README.md"), "r");
        final Path locked = Files.createDirectories(dir.resolve("locked"));
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            final ViewSpec.TreeNode tree = FolderTree.tree(folder("Notes", dir));
            assertThat(tree.children()).extracting(ViewSpec.TreeNode::label).containsExactly("locked", "README.md");
            final ViewSpec.TreeNode lockedNode = tree.children().getFirst();
            assertThat(lockedNode.detail()).isEqualTo("cannot be read");
            assertThat(lockedNode.children()).singleElement().extracting(ViewSpec.TreeNode::label).isEqualTo("✗ cannot be read: permission denied");
            assertThat(FolderTree.isUnreadable(tree)).isFalse();
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    /// The folder at [FolderTree#MAX_DEPTH] is listed with the detail `not read: N levels deep` and only the more
    /// marker.
    @Test
    void aFolderPastTheDepthBoundIsListedAndSaysItWasNotRead(@TempDir final Path dir) throws Exception {
        Path deep = dir;
        for (int i = 0; i < FolderTree.MAX_DEPTH + 1; i++) {
            deep = Files.createDirectories(deep.resolve("d" + i));
        }
        Files.writeString(deep.resolve("deep.md"), "d");
        ViewSpec.TreeNode node = FolderTree.tree(folder("Deep", dir));
        for (int i = 0; i < FolderTree.MAX_DEPTH; i++) {
            node = node.children().getFirst();
        }
        assertThat(node.label()).isEqualTo("d" + (FolderTree.MAX_DEPTH - 1));
        assertThat(node.detail()).isEqualTo("not read: " + FolderTree.MAX_DEPTH + " levels deep");
        assertThat(node.children()).singleElement().extracting(ViewSpec.TreeNode::label).isEqualTo(ViewSpec.TreeNode.MORE);
    }

    /// A symbolic link to a folder is listed under its name with no file link, no children, and the detail `link to a
    /// folder, not followed`.
    @Test
    void aLinkToAFolderIsListedAsAFolderAndNotFollowed(@TempDir final Path dir) throws Exception {
        final Path target = Files.createDirectories(dir.resolve("target"));
        Files.writeString(target.resolve("inside.md"), "i");
        final Path root = Files.createDirectories(dir.resolve("root"));
        Files.createSymbolicLink(root.resolve("docs"), target);
        final ViewSpec.TreeNode docs = FolderTree.tree(folder("Root", root)).children().getFirst();
        assertThat(docs.label()).isEqualTo("docs");
        assertThat(docs.link()).as("not offered as a file").isEmpty();
        assertThat(docs.children()).as("not followed").isEmpty();
        assertThat(docs.detail()).isEqualTo("link to a folder, not followed");
    }
}
