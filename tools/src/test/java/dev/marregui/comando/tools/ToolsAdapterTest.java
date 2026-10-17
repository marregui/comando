package dev.marregui.comando.tools;

import dev.marregui.comando.core.console.ConsoleRegistry;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.testkit.RecordingSink;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ToolsAdapterTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    private final RecordingSink sink = new RecordingSink();
    private final ConsoleRegistry consoles = new ConsoleRegistry();

    private ToolsAdapter adapter() {
        return new ToolsAdapter(sink, consoles, () -> new ToolSettings(Optional.empty(), System.getProperty("java.home"), ""));
    }

    private static FolderRoot folder(String name, Path path, String... excludes) {
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
    void aRunAskedForAfterCloseStartsNoProcess(@TempDir Path dir) throws Exception {
        fakeBuild(dir);
        ToolsAdapter tools = adapter();
        tools.close();
        tools.runGradle(8L, dir.toString(), "test");
        Intent.CommandFinished end = sink.await(Intent.CommandFinished.class, WAIT);
        assertThat(end.runId()).isEqualTo(8L);
        assertThat(consoles.of(8L).text(0)).contains("Not started: comando is closing").doesNotContain("args:");
    }

    @Test
    void gradleRunsInTheBuildRootAndReportsFailedTests(@TempDir Path dir) throws Exception {
        fakeBuild(dir);
        adapter().runGradle(7L, dir.resolve("db/sub").toString(), ":db:test --tests 'Lane*'");
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
            adapter().runGradle(4L, dir.toString(), "test");
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
        Assertions.assertThatThrownBy(() -> Args.split("a 'b")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noBuildAboveTheDirectoryIsReported(@TempDir Path dir) throws Exception {
        adapter().runGradle(3L, dir.toString(), "build");
        assertThat(sink.await(Intent.CommandFinished.class, WAIT).exitCode()).isEqualTo(2);
        assertThat(consoles.of(3L).text(0)).contains("No gradlew");
    }

    @Test
    void cancelStopsTheProcessTree(@TempDir Path dir) throws Exception {
        Path gradlew = dir.resolve("gradlew");
        Files.writeString(gradlew, "#!/bin/sh\necho started\nsleep 30 &\nwait\n");
        Files.setPosixFilePermissions(gradlew, PosixFilePermissions.fromString("rwxr-xr-x"));
        ToolsAdapter tools = adapter();
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
        ToolsAdapter tools = adapter();
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
        ToolsAdapter tools = adapter();
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
        adapter().runGradle(8L, dir.toString(), "build");
        Intent.CommandFinished end = sink.await(Intent.CommandFinished.class, WAIT);
        assertThat(end.exitCode()).isZero();
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(6));
        assertThat(consoles.of(8L).text(0)).contains("done", "still holds the output open", "Exit code 0");
    }

    @Test
    void eachRunReadsTheSettingsAndShowsWhatWasWrongWithThemFirst(@TempDir Path dir) throws Exception {
        fakeBuild(dir);
        Path serial = dir.resolve("serial");
        Files.writeString(serial, "#!/bin/sh\necho \"serial $*\"\n");
        Files.setPosixFilePermissions(serial, PosixFilePermissions.fromString("rwxr-xr-x"));
        List<ToolSettings> settings = new ArrayList<>(List.of(
                new ToolSettings(Optional.empty(), System.getProperty("java.home"), "tools.json: not JSON; using the defaults"),
                new ToolSettings(Optional.of(serial), System.getProperty("java.home"), "")));
        ToolsAdapter tools = new ToolsAdapter(sink, consoles, settings::removeFirst);

        tools.runGradle(1L, dir.toString(), "build");
        sink.await(Intent.CommandFinished.class, e -> e.runId() == 1L, WAIT);
        tools.runGradle(2L, dir.toString(), "build");
        sink.await(Intent.CommandFinished.class, e -> e.runId() == 2L, WAIT);

        assertThat(consoles.of(1L).text(0)).startsWith("tools.json: not JSON; using the defaults\n$ " + dir.resolve("gradlew"));
        assertThat(consoles.of(2L).text(0)).as("the settings changed between runs").startsWith("$ " + serial + " " + dir + " build")
                .contains("serial " + dir + " build");
    }

    @Test
    void aReadSendsEveryTreeAndAWatchSendsAnOpenFolderOnlyWhenItChanged(@TempDir Path dir) throws Exception {
        FolderRoot a = folder("A", Files.createDirectories(dir.resolve("a")));
        FolderRoot b = folder("B", Files.createDirectories(dir.resolve("b")));
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
            Intent.FolderLoaded first = sink.await(Intent.FolderLoaded.class, WAIT);
            assertThat(first.folder()).as("a folder newly watched is read at once").isEqualTo(a);
            tools.pollFolders(Duration.ofMillis(20));
            Files.createDirectory(dir.resolve("b").resolve("unwatched"));
            Files.createDirectory(dir.resolve("a").resolve("changed"));
            sink.await(Intent.FolderLoaded.class, l -> l.tree().children().size() == 2, WAIT);
            assertThat(sink.of(Intent.FolderLoaded.class)).as("unchanged polls send nothing; a folder not watched is not read")
                    .extracting(l -> l.folder().name()).containsExactly("A", "A");

            sink.clear();
            tools.watchFolders(List.of(folder("A", dir.resolve("a"), "changed")));
            Intent.FolderLoaded edited = sink.await(Intent.FolderLoaded.class, WAIT);
            assertThat(edited.tree().children()).as("a folder watched with new settings is read with them at once")
                    .extracting(ViewSpec.TreeNode::label).containsExactly("PLAN.md");
        }
    }

    @Test
    void aFolderThatCannotBeReadSaysSoInItsTreeWithoutANotice(@TempDir Path dir) throws Exception {
        adapter().readFolders(List.of(folder("Gone", dir.resolve("missing"))));
        ViewSpec.TreeNode tree = sink.await(Intent.FolderLoaded.class, WAIT).tree();
        assertThat(tree.label()).isEqualTo("Gone");
        assertThat(tree.children()).singleElement().extracting(ViewSpec.TreeNode::label).isEqualTo("✗ cannot be read: no such folder");
        assertThat(FolderTree.isUnreadable(tree)).isTrue();
        assertThat(sink.of(Intent.Notify.class)).as("no notice at every refresh").isEmpty();
    }

    @Test
    void aFolderTreeOrdersDaysAndDocumentsAndLeavesOutHiddenBuildAndExcludedEntries(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("PLAN.md"), "# plan");
        Files.createDirectories(dir.resolve("datahub"));
        for (String left : List.of(".git", "build", "node_modules", "Tools")) {
            Files.createDirectories(dir.resolve(left));
        }
        Files.writeString(dir.resolve(".DS_Store"), "x");
        for (String day : List.of("2026-10-11", "2026-10-12")) {
            Path d = Files.createDirectories(dir.resolve(day));
            Files.writeString(d.resolve("ALPHA.md"), "a");
            Files.writeString(d.resolve("READ-01-X.md"), "r");
            Files.createDirectories(d.resolve("work")).resolve("spec.md").toFile().createNewFile();
            Files.writeString(Files.createDirectories(d.resolve("work").resolve("deep")).resolve("deeper.md"), "d");
        }
        adapter().readFolders(List.of(folder("Scratchpad", dir, "tools")));
        ViewSpec.TreeNode root = sink.await(Intent.FolderLoaded.class, WAIT).tree();
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

    @Test
    void aFileIsCopiedNeverOverAnotherAndTheOpenFoldersShowIt(@TempDir Path dir) throws Exception {
        Path notes = Files.writeString(dir.resolve("notes.md"), "# notes");
        ToolsAdapter tools = adapter();
        tools.watchFolders(List.of(folder("Notes", dir)));
        sink.await(Intent.FolderLoaded.class, WAIT);
        tools.copyFile(notes.toString(), dir.resolve("notes copy.md").toString());
        assertThat(sink.await(Intent.Notify.class, n -> n.text().startsWith("Copied"), WAIT).text()).isEqualTo("Copied notes.md to notes copy.md");
        assertThat(dir.resolve("notes copy.md")).hasContent("# notes");
        sink.await(Intent.FolderLoaded.class, l -> l.tree().children().size() == 2, WAIT);

        Files.writeString(dir.resolve("other.md"), "other");
        tools.copyFile(notes.toString(), dir.resolve("other.md").toString());
        assertThat(sink.await(Intent.Notify.class, n -> n.level() == Notice.Level.ERROR, WAIT).text()).isEqualTo("Cannot copy notes.md: other.md exists");
        assertThat(dir.resolve("other.md")).hasContent("other");
    }

    @Test
    void aFileGoesToTheTrashOnlyWhereThereIsOneAndIsDeletedForGoodOnlyWhenAsked(@TempDir Path dir) throws Exception {
        Path a = Files.writeString(dir.resolve("a.md"), "a");
        Path b = Files.writeString(dir.resolve("b.md"), "b");
        Files.createDirectories(dir.resolve("sub"));
        List<Path> trashed = new CopyOnWriteArrayList<>();
        ToolsAdapter withTrash = new ToolsAdapter(sink, consoles, () -> new ToolSettings(Optional.empty(), "", ""),
                file -> trashed.add(file));
        withTrash.deleteFile(a.toString(), true);
        assertThat(sink.await(Intent.Notify.class, n -> n.text().startsWith("Moved"), WAIT).text()).isEqualTo("Moved a.md to the Trash");
        assertThat(trashed).containsExactly(a);

        ToolsAdapter noTrash = adapter();
        noTrash.deleteFile(b.toString(), true);
        assertThat(sink.await(Intent.Notify.class, n -> n.level() == Notice.Level.ERROR, WAIT).text())
                .isEqualTo("Cannot delete b.md: this desktop has no Trash");
        assertThat(b).as("a Trash asked for and missing: never deleted for good instead").exists();
        noTrash.deleteFile(b.toString(), false);
        assertThat(sink.await(Intent.Notify.class, n -> n.text().equals("Deleted b.md"), WAIT)).isNotNull();
        assertThat(b).doesNotExist();
        noTrash.deleteFile(dir.resolve("sub").toString(), false);
        assertThat(sink.await(Intent.Notify.class, n -> n.text().startsWith("Cannot delete sub"), WAIT).text()).isEqualTo("Cannot delete sub: not a file");
        assertThat(dir.resolve("sub")).isDirectory();
    }

    @Test
    void theIndexFileLeadsItsFolderThenFoldersThenFiles(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("feedback.md"), "f");
        Files.writeString(dir.resolve("MEMORY.md"), "m");
        Files.writeString(dir.resolve("agent.md"), "a");
        Files.createDirectories(dir.resolve("archive")).resolve("MEMORY.md").toFile().createNewFile();
        Files.writeString(dir.resolve("archive").resolve("a.md"), "a");
        ViewSpec.TreeNode root = FolderTree.tree(new FolderRoot("Memory", dir.toString(), "MEMORY.md", List.of()));
        assertThat(root.children()).extracting(ViewSpec.TreeNode::label).containsExactly("MEMORY.md", "archive", "agent.md", "feedback.md");
        assertThat(root.children().get(1).children()).as("a file named as the index deeper down is any file")
                .extracting(ViewSpec.TreeNode::label).containsExactly("a.md", "MEMORY.md");
        assertThat(FolderTree.tree(folder("Memory", dir)).children()).as("no index set: folders first")
                .extracting(ViewSpec.TreeNode::label).containsExactly("archive", "agent.md", "feedback.md", "MEMORY.md");
    }

    @Test
    void aLargeFolderIsReadUpToTheBoundAndEndsSayingSo(@TempDir Path dir) throws Exception {
        for (int i = 0; i < FolderTree.MAX_ENTRIES + 10; i++) {
            Files.createFile(dir.resolve("f" + i + ".md"));
        }
        Files.createDirectories(dir.resolve("sub")).resolve("x.md").toFile().createNewFile();
        ViewSpec.TreeNode tree = FolderTree.tree(folder("Big", dir));
        assertThat(tree.children()).hasSize(FolderTree.MAX_ENTRIES + 1);
        assertThat(tree.children().getLast().label()).isEqualTo(ViewSpec.TreeNode.MORE);
    }

    @Test
    void aFolderOfAMillionNamesIsListedOnlyUpToTheBoundShownOrHidden(@TempDir Path dir) throws Exception {
        for (String prefix : List.of("note", ".hidden")) {
            AtomicInteger pulled = new AtomicInteger();
            FolderTree.Lister million = d -> IntStream.range(0, 1_000_000).mapToObj(i -> d.resolve(prefix + i)).peek(p -> pulled.incrementAndGet());
            ViewSpec.TreeNode tree = FolderTree.tree(folder("Big", dir), million);
            assertThat(pulled.get()).as(prefix + ": one past the bound is listed, to know there are more").isEqualTo(FolderTree.MAX_LISTED + 1);
            assertThat(tree.children().getLast().label()).isEqualTo(ViewSpec.TreeNode.MORE);
            assertThat(tree.children()).hasSize(prefix.startsWith(".") ? 1 : FolderTree.MAX_ENTRIES + 1);
        }
    }

    @Test
    void onceTheBoundIsSpentNoFurtherFolderIsListed(@TempDir Path dir) throws Exception {
        for (int i = 0; i < 50; i++) {
            Files.createDirectories(dir.resolve(String.format("d%02d", i)));
        }
        AtomicInteger listedAfter = new AtomicInteger();
        FolderTree.Lister lister = d -> {
            if (d.equals(dir)) {
                return Files.list(d);
            }
            if (d.getFileName().toString().equals("d00")) {
                return IntStream.range(0, 1_000_000).mapToObj(i -> d.resolve(".hidden" + i));
            }
            listedAfter.incrementAndGet();
            return Files.list(d);
        };
        ViewSpec.TreeNode tree = FolderTree.tree(folder("Many", dir), lister);
        assertThat(listedAfter).as("the first folder spent the bound: none after it is listed").hasValue(0);
        assertThat(tree.children().get(1).children()).singleElement().extracting(ViewSpec.TreeNode::label).isEqualTo(ViewSpec.TreeNode.MORE);
    }

    @Test
    void aSubfolderThatCannotBeReadSaysSoAndTheRestStands(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("README.md"), "r");
        Path locked = Files.createDirectories(dir.resolve("locked"));
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            ViewSpec.TreeNode tree = FolderTree.tree(folder("Notes", dir));
            assertThat(tree.children()).extracting(ViewSpec.TreeNode::label).containsExactly("locked", "README.md");
            ViewSpec.TreeNode lockedNode = tree.children().getFirst();
            assertThat(lockedNode.detail()).isEqualTo("cannot be read");
            assertThat(lockedNode.children()).singleElement().extracting(ViewSpec.TreeNode::label).isEqualTo("✗ cannot be read: permission denied");
            assertThat(FolderTree.isUnreadable(tree)).isFalse();
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void aFolderPastTheDepthBoundIsListedAndSaysItWasNotRead(@TempDir Path dir) throws Exception {
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

    @Test
    void aLinkToAFolderIsListedAsAFolderAndNotFollowed(@TempDir Path dir) throws Exception {
        Path target = Files.createDirectories(dir.resolve("target"));
        Files.writeString(target.resolve("inside.md"), "i");
        Path root = Files.createDirectories(dir.resolve("root"));
        Files.createSymbolicLink(root.resolve("docs"), target);
        ViewSpec.TreeNode docs = FolderTree.tree(folder("Root", root)).children().getFirst();
        assertThat(docs.label()).isEqualTo("docs");
        assertThat(docs.link()).as("not offered as a file").isEmpty();
        assertThat(docs.children()).as("not followed").isEmpty();
        assertThat(docs.detail()).isEqualTo("link to a folder, not followed");
    }
}
