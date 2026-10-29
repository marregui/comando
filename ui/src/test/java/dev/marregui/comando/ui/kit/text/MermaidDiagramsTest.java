package dev.marregui.comando.ui.kit.text;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/// [MermaidDiagrams] draws Mermaid text to cached PNGs with `mmdc`, off the EDT, one drawing at a time.
/// - A diagram is drawn once, then shown from the cache at half its pixels, with no temporary files left.
/// - The same callback handed in again while drawing runs once.
/// - A rejection, an unreadable picture or a timeout is remembered as unavailable with its reason, not retried.
/// - A timed-out `mmdc` is stopped with the processes it started.
/// - Without `mmdc` the reason says how to install it; the dark theme is asked for.
/// - The cache key ignores surrounding blank lines; the preview fits the pane or shows the source under a note.
class MermaidDiagramsTest {

    private static final String FLOW = "flowchart LR\n    A --> B\n";

    @TempDir
    Path dir;

    /**
     * A stand-in for mmdc: counts its runs, then copies a picture to the -o path, or fails as mmdc does on bad text.
     */
    private Path fakeMmdc(boolean fails) throws Exception {
        Path picture = dir.resolve("picture.png");
        ImageIO.write(new BufferedImage(400, 120, BufferedImage.TYPE_INT_ARGB), "png", picture.toFile());
        Path exe = dir.resolve("bin").resolve("mmdc");
        Files.createDirectories(exe.getParent());
        Files.writeString(exe, """
                #!/bin/sh
                echo run >> '%s'
                out=""
                while [ $# -gt 0 ]; do
                  if [ "$1" = "-o" ]; then out="$2"; fi
                  shift
                done
                %s
                """.formatted(dir.resolve("runs"), fails
                ? "echo 'Error: Parse error on line 2:' >&2; exit 1"
                : "cp '" + picture + "' \"$out\""));
        exe.toFile().setExecutable(true);
        return exe;
    }

    private long runs() throws Exception {
        Path runs = dir.resolve("runs");
        return Files.exists(runs) ? Files.readAllLines(runs).size() : 0;
    }

    /// The first ask is pending and calls back when drawn; the second is ready at the cached path with half the 400×120
    /// picture's size, `mmdc` ran once, and the cache holds only the PNG.
    @Test
    void aDiagramIsDrawnOnceThenShownFromTheCacheAtHalfItsPixels() throws Exception {
        MermaidDiagrams diagrams = new MermaidDiagrams(dir.resolve("cache"), fakeMmdc(false));
        try {
            CountDownLatch drawn = new CountDownLatch(1);
            assertThat(diagrams.diagram(FLOW, drawn::countDown)).isInstanceOf(MermaidDiagrams.Diagram.Pending.class);
            assertThat(drawn.await(10, TimeUnit.SECONDS)).as("the asker hears when it is drawn").isTrue();

            MermaidDiagrams.Diagram again = diagrams.diagram(FLOW, () -> { });
            assertThat(again).isEqualTo(new MermaidDiagrams.Diagram.Ready(
                    dir.resolve("cache").resolve(MermaidDiagrams.key(FLOW) + ".png"), 200, 60));
            assertThat(runs()).as("drawn once").isEqualTo(1);
            try (var files = Files.list(dir.resolve("cache"))) {
                assertThat(files.map(p -> p.getFileName().toString())).as("no temporary files left")
                        .containsExactly(MermaidDiagrams.key(FLOW) + ".png");
            }
        } finally {
            diagrams.close();
        }
    }

    /// Three asks with one callback while the script waits at a gate stay pending; once a second diagram queued behind
    /// it calls back, the shared callback has run exactly once.
    @Test
    void theSameCallbackHandedInAgainWhileTheDiagramIsDrawnRunsOnce() throws Exception {
        Path picture = dir.resolve("picture.png");
        ImageIO.write(new BufferedImage(400, 120, BufferedImage.TYPE_INT_ARGB), "png", picture.toFile());
        Path gate = dir.resolve("gate");
        MermaidDiagrams diagrams = new MermaidDiagrams(dir.resolve("cache"), script("""
                out=""
                while [ $# -gt 0 ]; do
                  if [ "$1" = "-o" ]; then out="$2"; fi
                  shift
                done
                while [ ! -f '%s' ]; do sleep 0.02; done
                cp '%s' "$out"
                """.formatted(gate, picture)));
        try {
            AtomicInteger told = new AtomicInteger();
            Runnable preview = told::incrementAndGet;
            for (int i = 0; i < 3; i++) {
                assertThat(diagrams.diagram(FLOW, preview)).as("held at the gate").isInstanceOf(MermaidDiagrams.Diagram.Pending.class);
            }
            CountDownLatch other = new CountDownLatch(1);
            diagrams.diagram("flowchart LR\n    X --> Y\n", other::countDown);
            Files.createFile(gate);
            // one drawing at a time: once the next is heard, every callback of the first has run
            assertThat(other.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(told).hasValue(1);
        } finally {
            diagrams.close();
        }
    }

    /// A script that exits 1 with a parse error yields unavailable with `mmdc cannot draw it:` and the stderr line, and
    /// a second ask runs no second process.
    @Test
    void aDiagramMmdcRejectsSaysWhyAndIsNotDrawnAgain() throws Exception {
        MermaidDiagrams diagrams = new MermaidDiagrams(dir.resolve("cache"), fakeMmdc(true));
        try {
            CountDownLatch done = new CountDownLatch(1);
            diagrams.diagram("flowchart LR\n  A -->", done::countDown);
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(diagrams.diagram("flowchart LR\n  A -->", () -> { }))
                    .isEqualTo(new MermaidDiagrams.Diagram.Unavailable("mmdc cannot draw it: Error: Parse error on line 2:"));
            assertThat(runs()).isEqualTo(1);
        } finally {
            diagrams.close();
        }
    }

    /** A stand-in for mmdc that runs {@code body} as a shell script. */
    private Path script(String body) throws Exception {
        Path exe = dir.resolve("bin").resolve("mmdc");
        Files.createDirectories(exe.getParent());
        Files.writeString(exe, "#!/bin/sh\n" + body);
        exe.toFile().setExecutable(true);
        return exe;
    }

    /// `mmdc` is called with `-t dark`.
    @Test
    void aDiagramIsDrawnInMermaidsDarkTheme() throws Exception {
        Path picture = dir.resolve("picture.png");
        ImageIO.write(new BufferedImage(400, 120, BufferedImage.TYPE_INT_ARGB), "png", picture.toFile());
        MermaidDiagrams diagrams = new MermaidDiagrams(dir.resolve("cache"), script("""
                out=""
                while [ $# -gt 0 ]; do
                  if [ "$1" = "-o" ]; then out="$2"; fi
                  if [ "$1" = "-t" ]; then echo "$2" >> '%s'; fi
                  shift
                done
                cp '%s' "$out"
                """.formatted(dir.resolve("themes"), picture)));
        try {
            CountDownLatch dark = new CountDownLatch(1);
            diagrams.diagram(FLOW, dark::countDown);
            assertThat(dark.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(Files.readAllLines(dir.resolve("themes"))).containsExactly("dark");
        } finally {
            diagrams.close();
        }
    }

    /// When `mmdc` writes something that is not a PNG, the re-ask from the ready callback is unavailable, the caller is
    /// told once with no drawing queued after it, and the unreadable file is removed.
    @Test
    void aPictureThatCannotBeReadIsShownAsAFailureNotAnnouncedAgainAndAgain() throws Exception {
        MermaidDiagrams diagrams = new MermaidDiagrams(dir.resolve("cache"), script("""
                out=""
                while [ $# -gt 0 ]; do
                  if [ "$1" = "-o" ]; then out="$2"; fi
                  shift
                done
                printf 'not a png' > "$out"
                """));
        try {
            AtomicInteger told = new AtomicInteger();
            AtomicReference<MermaidDiagrams.Diagram> afterReady = new AtomicReference<>();
            CountDownLatch ready = new CountDownLatch(1);
            // as the preview does: told it is ready, it asks again
            Runnable redraw = () -> {
                told.incrementAndGet();
                afterReady.set(diagrams.diagram(FLOW, () -> told.incrementAndGet()));
                ready.countDown();
            };
            assertThat(diagrams.diagram(FLOW, redraw)).isInstanceOf(MermaidDiagrams.Diagram.Pending.class);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(afterReady.get()).isEqualTo(new MermaidDiagrams.Diagram.Unavailable("mmdc drew a picture that cannot be read"));
            assertThat(diagrams.diagram(FLOW, () -> told.incrementAndGet())).isInstanceOf(MermaidDiagrams.Diagram.Unavailable.class);
            assertThat(told).as("told once; no drawing queued after it").hasValue(1);
            assertThat(dir.resolve("cache").resolve(MermaidDiagrams.key(FLOW) + ".png")).as("the unreadable picture is removed").doesNotExist();
        } finally {
            diagrams.close();
        }
    }

    /// A script that outlives the 500 ms bound yields unavailable `mmdc did not finish within…`, and the child it
    /// started is no longer alive.
    @Test
    void mmdcThatDoesNotFinishIsStoppedWithWhatItStarted() throws Exception {
        Path child = dir.resolve("child.pid");
        MermaidDiagrams diagrams = new MermaidDiagrams(dir.resolve("cache"), script("""
                sleep 60 &
                echo $! > '%s'
                wait
                """.formatted(child)), Duration.ofMillis(500));
        try {
            CountDownLatch done = new CountDownLatch(1);
            diagrams.diagram(FLOW, done::countDown);
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(diagrams.diagram(FLOW, () -> { })).isInstanceOfSatisfying(MermaidDiagrams.Diagram.Unavailable.class,
                    u -> assertThat(u.why()).startsWith("mmdc did not finish within"));
            long pid = Long.parseLong(Files.readString(child).strip());
            Optional<ProcessHandle> sleeper = ProcessHandle.of(pid);
            if (sleeper.isPresent()) {
                sleeper.get().onExit().get(5, TimeUnit.SECONDS);
            }
            assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive)).as("the process mmdc started is stopped too")
                    .isNotEqualTo(Optional.of(true));
        } finally {
            diagrams.close();
        }
    }

    /// With no executable the diagram is unavailable at once, and the reason names the `npm install` command.
    @Test
    void withoutMmdcTheSourceShowsWithHowToGetIt() {
        MermaidDiagrams diagrams = new MermaidDiagrams(dir.resolve("cache"), null);
        assertThat(diagrams.diagram(FLOW, () -> { })).isInstanceOfSatisfying(MermaidDiagrams.Diagram.Unavailable.class,
                u -> assertThat(u.why()).contains("npm install -g @mermaid-js/mermaid-cli"));
    }

    /// [MermaidDiagrams#key] is a 32-character key equal for the text with blank lines around it and different for
    /// other text; [MermaidDiagrams#compareVersions] compares Node versions numerically.
    @Test
    void theKeyFollowsTheTextNotItsSurroundingBlankLines() {
        assertThat(MermaidDiagrams.key(FLOW)).isEqualTo(MermaidDiagrams.key("\n" + FLOW + "\n\n"))
                .isNotEqualTo(MermaidDiagrams.key("flowchart LR\n    A --> C\n")).hasSize(32);
        assertThat(MermaidDiagrams.compareVersions("v22.20.0", "v20.9.1")).isPositive();
        assertThat(MermaidDiagrams.compareVersions("v9.0.0", "v10.0.0")).isNegative();
    }

    /// [HtmlPane#diagramHtml] scales a ready picture to the pane's width keeping its ratio, shows a pending one's
    /// escaped source under `drawing the diagram…`; [MarkdownHtml#render] hands mermaid fences to the diagram function,
    /// or renders them as code without one.
    @Test
    void thePreviewShowsThePictureNoWiderThanThePaneElseTheSourceUnderANote() {
        Path png = dir.resolve("d.png");
        String ready = HtmlPane.diagramHtml(FLOW, new MermaidDiagrams.Diagram.Ready(png, 800, 100), 400);
        assertThat(ready).contains("<img src=\"" + png.toUri() + "\" width=\"400\" height=\"50\"");
        assertThat(HtmlPane.diagramHtml("A --> <b>", new MermaidDiagrams.Diagram.Pending(), 400))
                .contains("drawing the diagram…").contains("A --&gt; &lt;b&gt;").doesNotContain("<b>");
        assertThat(MarkdownHtml.render("# Plan\n\n```mermaid\n" + FLOW + "```\n", source -> "[diagram of " + source.strip() + "]"))
                .contains("[diagram of flowchart LR").doesNotContain("<pre");
        assertThat(MarkdownHtml.render("```mermaid\n" + FLOW + "```\n")).as("no diagram function: code")
                .contains("<pre class=\"code\"><code>flowchart LR");
    }
}
