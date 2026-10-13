package dev.marregui.comando.ui.widgets.terminal;

import dev.marregui.comando.ui.widget.capability.Capabilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TerminalWidgetTest {

    private static final String HOME = System.getProperty("user.home");

    @Test
    void beforeItsShellStartsTheAreaSaysWhereAndHow() {
        TerminalWidget t = new TerminalWidget(Path.of(HOME, "edge2"));
        assertThat(t.isStarted()).isFalse();
        assertThat(t.emptyText()).isEqualTo("Shell in ~/edge2 — click or press ⌃4 to start");
        t.setDirectory(Path.of("/srv/x"));
        assertThat(t.emptyText()).isEqualTo("Shell in /srv/x — click or press ⌃4 to start");
    }

    @Test
    void theExitBarNamesTheDirectoryOfTheRestart() {
        TerminalWidget t = new TerminalWidget(Path.of(HOME, "edge"));
        t.setDirectory(Path.of(HOME, "edge2"));
        t.showExited(1);
        assertThat(t.restartButtonText()).isEqualTo("Restart in ~/edge2");
    }

    @Test
    void theExitBarAndRestartReadTheDirectorySourceWhenTheBarShows() {
        TerminalWidget t = new TerminalWidget(Path.of(HOME, "edge"));
        AtomicReference<Path> bound = new AtomicReference<>(Path.of(HOME, "edge"));
        t.setDirectorySource(bound::get);
        bound.set(Path.of(HOME, "edge2")); // the panel was rebound while the shell ran: no render reached the terminal
        t.showExited(0);
        assertThat(t.restartButtonText()).isEqualTo("Restart in ~/edge2");
        assertThat(t.directory()).isEqualTo(Path.of(HOME, "edge2"));
    }

    /** ⌃⏎ into a silent shell, then a restart: the held text must not run in the new shell. */
    @Test
    void textWaitingWhenTheShellClosesNeverReachesTheNextShell(@TempDir Path dir) throws Exception {
        AtomicReference<TerminalWidget> t = new AtomicReference<>();
        AtomicReference<Integer> waitingBefore = new AtomicReference<>();
        AtomicReference<Integer> waitingAfter = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            t.set(new TerminalWidget(dir, List.of("/bin/sh", "-c", "echo READY; exec cat")));
            t.get().start();
            t.get().send("held\n"); // the shell has not settled yet: the text waits
            waitingBefore.set(t.get().inputWaiting());
            t.get().restart();
            waitingAfter.set(t.get().inputWaiting());
        });
        try {
            assertThat(waitingBefore.get()).isEqualTo(1);
            assertThat(waitingAfter.get()).isZero();
            AtomicReference<String> screen = new AtomicReference<>("");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (System.nanoTime() < deadline && !screen.get().contains("READY")) {
                Thread.sleep(50);
                SwingUtilities.invokeAndWait(() -> screen.set(t.get().screenText()));
            }
            Thread.sleep(InputGate.SETTLE_NANOS / 1_000_000 * 3); // past the moment the gate opens for the new shell
            SwingUtilities.invokeAndWait(() -> screen.set(t.get().screenText()));
            assertThat(screen.get()).contains("READY").doesNotContain("held");
        } finally {
            SwingUtilities.invokeAndWait(() -> t.get().close());
        }
    }

    // ---- what the shell runs in front: fails closed

    @Test
    void whenPsFailsOrPrintsWhatCannotBeReadTheJobIsUnchecked() {
        assertThat(TerminalWidget.foregroundJob(4242, "/bin/zsh", args -> List.of())).contains(TerminalWidget.UNCHECKED_JOB);
        assertThat(TerminalWidget.foregroundJob(4242, "/bin/zsh", args -> List.of("4242"))).contains(TerminalWidget.UNCHECKED_JOB);
    }

    @Test
    void aShellReplacedByExecReportsTheProgram() {
        assertThat(TerminalWidget.foregroundJob(4242, "/bin/zsh", args -> List.of("4242 4242 ssh"))).contains("ssh");
        assertThat(TerminalWidget.foregroundJob(4242, "/bin/zsh", args -> List.of("4242 4242 /usr/bin/ssh"))).contains("ssh");
    }

    @Test
    void aShellInFrontRunsNoJobWhateverItsNameLooksLike() {
        assertThat(TerminalWidget.foregroundJob(4242, "/bin/zsh", args -> List.of("4242 4242 -zsh"))).isEmpty();
        assertThat(TerminalWidget.foregroundJob(4242, "/bin/zsh", args -> List.of("4242 4242 /bin/zsh"))).isEmpty();
        assertThat(TerminalWidget.foregroundJob(4242, "/bin/zsh", args -> List.of("4242 4242 bash"))).as("exec bash").isEmpty();
        assertThat(TerminalWidget.foregroundJob(4242, "/opt/homebrew/bin/fish", args -> List.of("4242 4242 fish"))).isEmpty();
    }

    @Test
    void aJobInFrontWhoseNameCannotBeReadIsStillAJob() {
        long self = ProcessHandle.current().pid();
        List<List<String>> calls = new java.util.ArrayList<>();
        assertThat(TerminalWidget.foregroundJob(self, "/bin/zsh", args -> {
            calls.add(args);
            return calls.size() == 1 ? List.of("77 76 zsh") : List.of();
        })).contains("a program");
    }

    @Test
    void zoomChangesTheFontSizeWithinBoundsAndResets() {
        TerminalWidget t = new TerminalWidget(Path.of(HOME));
        float base = t.fontSize();
        t.zoom(2);
        assertThat(t.fontSize()).isEqualTo(base + 2);
        t.zoom(-100);
        assertThat(t.fontSize()).isEqualTo(8f);
        t.resetZoom();
        assertThat(t.fontSize()).isEqualTo(base);
    }

    @Test
    void zoomAndFindReachTheTerminalThroughItsRoot() {
        TerminalWidget t = new TerminalWidget(Path.of(HOME));
        assertThat(t.component().getClientProperty(Capabilities.OWNER)).isSameAs(t);
        assertThat(t.find("anything", true)).isZero(); // no shell yet: nothing to search
    }

    /**
     * The pseudo-terminal echoes what is written at once, even while the shell is still busy: text written
     * before the shell is ready shows above its first output. Written after, it shows below.
     */
    @Test
    void textSentBeforeTheShellIsReadyArrivesAfterItsFirstOutputInOrder(@TempDir Path dir) throws Exception {
        AtomicReference<TerminalWidget> t = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            t.set(new TerminalWidget(dir, List.of("/bin/sh", "-c", "sleep 0.5; echo READY; exec cat")));
            t.get().send("first\n");
            t.get().send("second\n");
        });
        try {
            AtomicReference<String> screen = new AtomicReference<>("");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (System.nanoTime() < deadline && count(screen.get(), "second") < 2) {
                Thread.sleep(50);
                SwingUtilities.invokeAndWait(() -> screen.set(t.get().screenText()));
            }
            String text = screen.get();
            assertThat(count(text, "second")).as("echo and cat's copy of both lines: %s", text).isEqualTo(2);
            assertThat(text.indexOf("READY")).as("the shell's first output comes first: %s", text).isNotNegative()
                    .isLessThan(text.indexOf("first"));
            assertThat(text.indexOf("first")).isLessThan(text.indexOf("second"));
        } finally {
            SwingUtilities.invokeAndWait(() -> t.get().close());
        }
    }

    @Test
    void findCountsMatchesInTheBufferIgnoringCaseAndZoomRefontsALiveTerminal(@TempDir Path dir) throws Exception {
        AtomicReference<TerminalWidget> t = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            t.set(new TerminalWidget(dir, List.of("/bin/sh", "-c", "echo alpha needle; echo needle beta; exec cat")));
            t.get().start();
        });
        try {
            AtomicReference<String> screen = new AtomicReference<>("");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (System.nanoTime() < deadline && !screen.get().contains("beta")) {
                Thread.sleep(50);
                SwingUtilities.invokeAndWait(() -> screen.set(t.get().screenText()));
            }
            AtomicReference<Integer> found = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                found.set(t.get().find("NEEDLE", true));
                t.get().zoom(1);
                t.get().clearFind();
            });
            assertThat(found.get()).isEqualTo(2);
        } finally {
            SwingUtilities.invokeAndWait(() -> t.get().close());
        }
    }

    private static int count(String text, String word) {
        int n = 0;
        for (int i = text.indexOf(word); i >= 0; i = text.indexOf(word, i + 1)) {
            n++;
        }
        return n;
    }

    @Test
    void aTerminalWithoutAShellRunsNoJob() {
        assertThat(new TerminalWidget(Path.of(HOME)).hasRunningJob()).isFalse();
    }

    /** A job the shell runs in front counts; one it put in the background, like a prompt's git daemon, does not. */
    @Test
    void onlyAForegroundJobCountsAsRunning(@TempDir Path dir) throws Exception {
        Pty pty = Pty.start(List.of("/bin/sh", "-i"), dir, 80, 24);
        Thread drain = Thread.ofVirtual().start(() -> {
            try {
                pty.process().getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            } catch (java.io.IOException e) {
                // the shell ended
            }
        });
        try {
            long shell = pty.process().pid();
            var in = pty.process().getOutputStream();
            in.write("sleep 30 &\n".getBytes());
            in.flush();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (ProcessHandle.of(shell).orElseThrow().descendants().findAny().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(ProcessHandle.of(shell).orElseThrow().descendants().findAny()).as("the background sleep started").isPresent();
            assertThat(TerminalWidget.foregroundJob(shell, "/bin/sh")).as("a background job").isEmpty();
            in.write("sleep 31\n".getBytes());
            in.flush();
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (TerminalWidget.foregroundJob(shell, "/bin/sh").isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(TerminalWidget.foregroundJob(shell, "/bin/sh")).contains("sleep");
        } finally {
            ProcessHandle.of(pty.process().pid()).ifPresent(h -> h.descendants().forEach(ProcessHandle::destroyForcibly));
            pty.process().destroyForcibly();
            drain.join(2000);
        }
    }
}
