package dev.marregui.comando.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.loop.Threads;
import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.SearchHit;
import dev.marregui.comando.core.model.SearchQuery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/// A search of the Files tab's folders.
/// - file names first, then lines, each with its folder, line number and marked match
/// - what the Files tab leaves out is left out here too, and a binary file is not read
/// - a snippet is one line of at most 200 chars with the match inside it, whatever the line's length or encoding
/// - a hit goes over in the walk's order whichever fork read it first; refusing a hit or ending the search stops the
/// scan
/// - what could not be read is counted, and no fork outlives the scan
class FolderSearchTest {

    private static final int SCANNERS = 3;
    private static final int FILE_HITS = 50;

    @TempDir
    Path dir;

    private final List<SearchHit> hits = new CopyOnWriteArrayList<>();

    private Path write(final String name, final String text) throws IOException {
        final Path file = dir.resolve(name);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, text);
    }

    private FolderRoot folder() {
        return new FolderRoot("Notes", dir.toString(), "", List.of());
    }

    private int scan(final String text) {
        return scan(text, folder());
    }

    private int scan(final String text, final FolderRoot... folders) {
        return new FolderSearch(SCANNERS, FILE_HITS).scan(new SearchQuery(text, List.of(folders), false), hits::add,
                () -> false);
    }

    private static String marked(final SearchHit hit) {
        return hit.snippet().substring(hit.matchStart(), hit.matchStart() + hit.matchLength());
    }

    /// A line that holds the text is a hit with its line number from 1, the folder's name, the path and the match
    /// marked,
    /// in either case, with `\r\n` ends counted as lines and left out of the snippet.
    @Test
    void aLineHitSaysWhereTheTextIs() throws IOException {
        final Path file = write("plan.md", "first\r\n\r\n  the Budget stands\r\nlast\r\n");

        assertThat(scan("budget")).isZero();

        assertThat(hits).hasSize(1);
        final SearchHit hit = hits.getFirst();
        assertThat(hit.kind()).isEqualTo(SearchHit.Kind.FILE);
        assertThat(hit.source()).isEqualTo(file.toString());
        assertThat(hit.conversation()).isEmpty();
        assertThat(hit.group()).isEqualTo("Notes");
        assertThat(hit.line()).isEqualTo(3);
        assertThat(hit.snippet()).isEqualTo("the Budget stands");
        assertThat(marked(hit)).isEqualTo("Budget");
        assertThat(hit.modifiedMillis()).isEqualTo(Files.getLastModifiedTime(file).toMillis());
    }

    /// A hit says the folder walked to it, and in a Markdown file the headings above its line, outermost first: a
    /// heading of the same or a higher level replaces the ones below it, a `#` line inside a fence is code, a closing
    /// run of `#` and a `#tag` paragraph are no part of the path, and a file of another kind names no heading.
    @Test
    void aMarkdownHitNamesTheHeadingsAboveIt() throws IOException {
        write("guide.md", String.join("\n",
                "# Guide ##",
                "intro plan",
                "## Setup",
                "### macOS",
                "```",
                "# not a heading",
                "```",
                "#tag paragraph",
                "the plan here",
                "## Use",
                "plan again"));
        write("code.py", "# Setup\nplan = 1\n");

        scan("plan");

        assertThat(hits).allSatisfy(h -> assertThat(h.root()).isEqualTo(dir.toAbsolutePath().toString()));
        assertThat(hits.stream().filter(h -> h.source().endsWith("guide.md")))
                .extracting(SearchHit::line, SearchHit::heading)
                .containsExactly(
                        tuple(2, "Guide"),
                        tuple(9, "Guide › Setup › macOS"),
                        tuple(11, "Guide › Use"));
        assertThat(hits.stream().filter(h -> h.source().endsWith("code.py"))).extracting(SearchHit::heading)
                .containsExactly("");
    }

    /// A heading ends the sections below its level, so a later deeper heading does not inherit them; a fence closes
    /// only on as many of its own marks, so a shorter or other fence inside it is code.
    @Test
    void aHeadingEndsTheDeeperSectionsAndAFenceClosesOnItsOwnMark() throws IOException {
        write("deep.md", String.join("\n",
                "# A", "## B", "### C", "# D", "### E", "plan one",
                "````", "```", "# inside", "~~~", "````", "## F", "plan two"));

        scan("plan");

        assertThat(hits).extracting(SearchHit::heading).containsExactly("D › E", "D › F");
    }

    /// A folder of the tab that is a link names its hits under the link, where the tab shows them.
    @Test
    void aLinkedFolderNamesItsHitsWhereTheTabShowsThem() throws IOException {
        final Path real = Files.createDirectories(dir.resolve("real"));
        Files.writeString(real.resolve("n.txt"), "plan\n");
        final Path link = Files.createSymbolicLink(dir.resolve("link"), real);

        scan("plan", new FolderRoot("Linked", link.toString(), "", List.of()));

        assertThat(hits).singleElement().satisfies(h -> {
            assertThat(h.source()).isEqualTo(link.resolve("n.txt").toAbsolutePath().toString());
            assertThat(h.root()).isEqualTo(link.toAbsolutePath().toString());
        });
    }

    /// The line number of each hit counts every newline before it: the first and last lines of a file with no line end
    /// at its close are both right, and a line holding the text twice gives one hit.
    @Test
    void lineNumbersCountEveryNewlineAndALineGivesOneHit() throws IOException {
        write("a.txt", "plan one plan\nno\n\nplan");

        scan("plan");

        assertThat(hits).extracting(SearchHit::line).containsExactly(1, 4);
    }

    /// A file named for the text comes before every line of every file, however the walk met them, with line 0 and its
    /// name as the snippet.
    @Test
    void fileNamesComeBeforeLines() throws IOException {
        write("a.txt", "the plan\n");
        write("deep/z-PLAN.md", "nothing here\n");

        scan("plan");

        assertThat(hits).extracting(SearchHit::kind).containsExactly(SearchHit.Kind.NAME, SearchHit.Kind.FILE);
        assertThat(hits.getFirst().line()).isZero();
        assertThat(hits.getFirst().snippet()).isEqualTo("z-PLAN.md");
        assertThat(marked(hits.getFirst())).isEqualTo("PLAN");
    }

    /// Hidden entries, a folder's excluded names and the names always excluded are not walked, a hit inside them or by
    /// their name; a folder of the tab that is itself hidden is read.
    @Test
    void whatTheTabLeavesOutIsNotSearched() throws IOException {
        write(".git/config", "plan\n");
        write(".plan", "plan\n");
        write("node_modules/plan.txt", "plan\n");
        write("build/plan.txt", "plan\n");
        write("archive/plan.txt", "plan\n");
        write("kept/.hidden/plan.txt", "plan\n");
        final Path kept = write("kept/notes.txt", "plan\n");

        scan("plan", new FolderRoot("Notes", dir.toString(), "", List.of("archive")));

        assertThat(hits).extracting(SearchHit::source).containsExactly(kept.toString());

        hits.clear();
        final Path inside = write(".kit/notes.txt", "plan\n");
        scan("plan", new FolderRoot("Kit", dir.resolve(".kit").toString(), "", List.of()));
        assertThat(hits).extracting(SearchHit::source).containsExactly(inside.toString());
    }

    /// A file with a NUL in its first 8 kB is not read past that, whatever it holds later, but its name still counts; a
    /// NUL after the first 8 kB does not make a file binary.
    @Test
    void aBinaryFileIsSkippedOnANulInItsHead() throws IOException {
        final byte[] binary = ("a\0" + "the plan\n").getBytes(StandardCharsets.UTF_8);
        Files.write(dir.resolve("data.bin"), binary);
        Files.write(dir.resolve("plan.bin"), binary);
        write("late.txt", "x".repeat(8192) + "\n\0the plan\n");

        assertThat(scan("plan")).isZero();

        assertThat(hits).extracting(h -> Path.of(h.source()).getFileName().toString(), SearchHit::kind)
                .containsExactlyInAnyOrder(tuple("plan.bin", SearchHit.Kind.NAME),
                        tuple("late.txt", SearchHit.Kind.FILE));
    }

    /// A line far longer than a snippet gives at most 200 chars around the match, the match kept whole and marked, with
    /// text before and after it; tabs are spaces, so the snippet stays on one line.
    @Test
    void aLongLineIsClippedAroundTheMatch() throws IOException {
        write("long.txt", "x".repeat(100_000) + "\tneedle\t" + "y".repeat(100_000) + "\n");

        scan("needle");

        final SearchHit hit = hits.getFirst();
        assertThat(hit.snippet()).hasSizeLessThanOrEqualTo(FolderSearch.SNIPPET_CHARS);
        assertThat(marked(hit)).isEqualTo("needle");
        assertThat(hit.snippet()).startsWith("x").endsWith("y").doesNotContain("\t");
    }

    /// A clip that lands inside a UTF-8 sequence leaves no replacement character: the snippet starts and ends on whole
    /// chars, and the mark counts chars, not bytes.
    @Test
    void aClipKeepsUtf8Whole() throws IOException {
        write("long.txt", "ñ".repeat(5_000) + "needle" + "€".repeat(5_000) + "\n");

        scan("needle");

        final SearchHit hit = hits.getFirst();
        assertThat(hit.snippet()).doesNotContain("�").hasSizeLessThanOrEqualTo(FolderSearch.SNIPPET_CHARS);
        assertThat(marked(hit)).isEqualTo("needle");
    }

    /// A match after text that is not ASCII is marked at the right char, and a query that is not ASCII finds itself.
    @Test
    void theMarkCountsCharsNotBytes() throws IOException {
        write("a.txt", "mañana Plan €\n");

        scan("plan");
        scan("€");

        assertThat(hits).extracting(FolderSearchTest::marked).containsExactly("Plan", "€");
    }

    /// With more files than forks and forks finishing in any order, the hits go over in the walk's order, every file
    /// once and on the thread that called the scan.
    @Test
    void hitsGoOverInTheWalksOrderOnTheScanningThread() throws IOException {
        final List<String> expected = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            write("f" + i + ".txt", "x".repeat((300 - i) * 100) + " plan\n");
        }
        new FolderSearch(1, FILE_HITS).scan(new SearchQuery("plan", List.of(folder()), false),
                h -> expected.add(h.source()), () -> false);
        final Thread caller = Thread.currentThread();
        final AtomicBoolean elsewhere = new AtomicBoolean();

        new FolderSearch(SCANNERS, FILE_HITS).scan(new SearchQuery("plan", List.of(folder()), false), h -> {
            elsewhere.compareAndSet(false, Thread.currentThread() != caller);
            return hits.add(h);
        }, () -> false);

        assertThat(hits).extracting(SearchHit::source).containsExactlyElementsOf(expected).hasSize(300);
        assertThat(elsewhere).isFalse();
    }

    /// The first refused hit ends the scan: nothing more is offered and no fork is left running.
    @Test
    void refusingAHitEndsTheScan() throws IOException {
        for (int i = 0; i < 200; i++) {
            write("f" + i + ".txt", "plan\n");
        }
        final List<SearchHit> offered = new ArrayList<>();

        new FolderSearch(SCANNERS, FILE_HITS).scan(new SearchQuery("plan", List.of(folder()), false), h -> {
            offered.add(h);
            return offered.size() < 5;
        }, () -> false);

        assertThat(offered).hasSize(5);
        assertThat(forks()).isEmpty();
    }

    /// A refused name hit ends the scan before any file is read.
    @Test
    void refusingANameHitReadsNoFile() throws IOException {
        write("plan.txt", "plan\n");
        final List<SearchHit> offered = new ArrayList<>();

        new FolderSearch(SCANNERS, FILE_HITS).scan(new SearchQuery("plan", List.of(folder()), false), h -> {
            offered.add(h);
            return false;
        }, () -> false);

        assertThat(offered).extracting(SearchHit::kind).containsExactly(SearchHit.Kind.NAME);
    }

    /// A search ended part way finds nothing more and the scan returns with its forks gone, also inside a file of
    /// several chunks that holds no hit yet.
    @Test
    void anEndedSearchStopsReading() throws IOException {
        for (int i = 0; i < 50; i++) {
            write("f" + i + ".txt", "plan\n");
        }
        final AtomicBoolean ended = new AtomicBoolean();

        new FolderSearch(SCANNERS, FILE_HITS).scan(new SearchQuery("plan", List.of(folder()), false), h -> {
            ended.set(true);
            return hits.add(h);
        }, ended::get);

        assertThat(hits).hasSizeLessThan(50);
        assertThat(forks()).isEmpty();

        hits.clear();
        assertThat(new FolderSearch(SCANNERS, FILE_HITS).scan(new SearchQuery("plan", List.of(folder()), false),
                hits::add, () -> true)).isZero();
        assertThat(hits).isEmpty();
    }

    /// A file stops giving hits at the bound it was given, so one file cannot hold more than a searcher would list.
    @Test
    void aFileGivesHitsUpToItsBound() throws IOException {
        write("many.txt", "plan\n".repeat(FILE_HITS * 3));

        scan("plan");

        assertThat(hits).hasSize(FILE_HITS);
    }

    /// A folder that is not there counts as failed and the next folder is still read, and a file behind no permission
    /// counts too. A user the file system does not refuse has nothing to prove here.
    @Test
    void whatCannotBeReadIsCountedAndTheRestStands() throws IOException {
        write("notes/a.txt", "plan\n");
        final Path locked = write("notes/locked.txt", "plan\n");
        final FolderRoot notes = new FolderRoot("Notes", dir.resolve("notes").toString(), "", List.of());
        assertThat(locked.toFile().setReadable(false)).isTrue();
        try {
            assumeFalse(Files.isReadable(locked));
            assertThat(scan("plan", new FolderRoot("Gone", dir.resolve("gone").toString(), "", List.of()),
                    notes)).isEqualTo(2);
        } finally {
            assertThat(locked.toFile().setReadable(true)).isTrue();
        }

        assertThat(hits).extracting(SearchHit::group).containsExactly("Notes");
    }

    /// A folder of the tab that is a link is read, a link to a folder inside one is not followed, a link to a file is
    /// read, and a FIFO is passed over rather than opened.
    @Test
    void linksAndSpecialFilesAreHandled() throws Exception {
        final Path real = Files.createDirectories(dir.resolve("real"));
        Files.writeString(real.resolve("a.txt"), "plan\n");
        Files.createSymbolicLink(dir.resolve("real/loop"), real);
        final Path outside = write("outside/b.txt", "plan\n");
        Files.createSymbolicLink(real.resolve("linked.txt"), outside);
        assertThat(new ProcessBuilder("mkfifo", real.resolve("pipe.txt").toString()).start().waitFor()).isZero();
        final Path link = Files.createSymbolicLink(dir.resolve("link"), real);

        assertThat(scan("plan", new FolderRoot("Link", link.toString(), "", List.of()))).isZero();

        assertThat(hits).extracting(h
                -> Path.of(h.source()).getFileName().toString()).containsExactlyInAnyOrder("a.txt", "linked.txt");
    }

    /// Two folders that overlap read a file once, under the first folder.
    @Test
    void overlappingFoldersReadAFileOnce() throws IOException {
        write("inner/a.txt", "plan\n");

        scan("plan", new FolderRoot("All", dir.toString(), "", List.of()),
                new FolderRoot("Inner", dir.resolve("inner").toString(), "", List.of()));

        assertThat(hits).extracting(SearchHit::group).containsExactly("All");
    }

    /// A match that spans the seam between two chunks of the scan is found, at the right line.
    @Test
    void aMatchAcrossTheChunkSeamIsFound() throws IOException {
        final int chunk = 1 << 20;
        final String head = "x".repeat(99) + "\n";
        write("big.txt", head.repeat(chunk / head.length()) + "y".repeat(chunk % head.length() - 3) + "needle\n");

        scan("needle");

        assertThat(hits).extracting(SearchHit::line).containsExactly(chunk / head.length() + 1);
    }

    private static List<Thread> forks() {
        return Threads.alive().stream().filter(t -> t.getName().startsWith("folder-search-")).toList();
    }
}
