package dev.marregui.comando.ui.widgets.terminal;

import com.jediterm.terminal.SubstringFinder;
import com.jediterm.terminal.ui.TerminalPanel;
import dev.marregui.comando.ui.kit.desktop.Platform;
import dev.marregui.comando.ui.kit.format.KeyText;
import dev.marregui.comando.ui.widget.capability.Capabilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/// [TerminalWidget]: a panel's shell, started on demand, with its exit bar, input gate, job check, zoom and find.
/// - before its shell starts the area says where and how; the exit bar names the directory of the restart
/// - text held for a shell that closes never reaches the next; text sent before the shell is ready arrives in order
/// - the job in front fails closed: unreadable `ps` output is an unchecked job; a shell in front is no job
/// - a job is named as typed, a script by its own name, clipped
/// - zoom stays in bounds and resets; find counts case-insensitively and searches again after new output
/// - only a foreground job counts as running
/// - on macOS ⌘A selects the terminal buffer
class TerminalWidgetTest {

    private static final String HOME = System.getProperty("user.home");

    /// An unstarted terminal names its directory, with `~` for home, and the key that starts it; a new directory updates
    /// the text.
    @Test
    void beforeItsShellStartsTheAreaSaysWhereAndHow() {
        TerminalWidget t = new TerminalWidget(Path.of(HOME, "lane2"));
        assertThat(t.isStarted()).isFalse();
        assertThat(t.emptyText()).isEqualTo(KeyText.of("Shell in ~/lane2 — click or press ⌃3 to start"));
        t.setDirectory(Path.of("/srv/x"));
        assertThat(t.emptyText()).isEqualTo(KeyText.of("Shell in /srv/x — click or press ⌃3 to start"));
    }

    /// After the shell exits, the restart button names the directory set since.
    @Test
    void theExitBarNamesTheDirectoryOfTheRestart() {
        TerminalWidget t = new TerminalWidget(Path.of(HOME, "edge"));
        t.setDirectory(Path.of(HOME, "lane2"));
        t.showExited(1);
        assertThat(t.restartButtonText()).isEqualTo("Restart in ~/lane2");
    }

    /// The exit bar reads the directory source when it shows, so a rebinding no render reached is used for the restart.
    @Test
    void theExitBarAndRestartReadTheDirectorySourceWhenTheBarShows() {
        TerminalWidget t = new TerminalWidget(Path.of(HOME, "edge"));
        AtomicReference<Path> bound = new AtomicReference<>(Path.of(HOME, "edge"));
        t.setDirectorySource(bound::get);
        bound.set(Path.of(HOME, "lane2")); // the panel was rebound while the shell ran: no render reached the terminal
        t.showExited(0);
        assertThat(t.restartButtonText()).isEqualTo("Restart in ~/lane2");
        assertThat(t.directory()).isEqualTo(Path.of(HOME, "lane2"));
    }

    /// Text sent before the shell settles waits; a restart drops it, and the new shell's screen never shows it.
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

    /// Empty `ps` output, or a line without a command, reports [TerminalWidget#UNCHECKED_JOB].
    @Test
    void whenPsFailsOrPrintsWhatCannotBeReadTheJobIsUnchecked() {
        assertThat(TerminalWidget.foregroundJob(4242, "/bin/zsh", args -> List.of())).contains(TerminalWidget.UNCHECKED_JOB);
        assertThat(TerminalWidget.foregroundJob(4242, "/bin/zsh", args -> List.of("4242"))).contains(TerminalWidget.UNCHECKED_JOB);
    }

    /// A shell process whose command became `ssh` by `exec` reports `ssh`, also from a full path.
    @Test
    void aShellReplacedByExecReportsTheProgram() {
        assertThat(TerminalWidget.foregroundJob(4242, "/bin/zsh", args -> List.of("4242 4242 ssh"))).contains("ssh");
        assertThat(TerminalWidget.foregroundJob(4242, "/bin/zsh", args -> List.of("4242 4242 /usr/bin/ssh"))).contains("ssh");
    }

    /// A login shell's `-zsh`, a full shell path, another shell started by `exec` and a shell under its install path are
    /// no job.
    @Test
    void aShellInFrontRunsNoJobWhateverItsNameLooksLike() {
        assertThat(TerminalWidget.foregroundJob(4242, "/bin/zsh", args -> List.of("4242 4242 -zsh"))).isEmpty();
        assertThat(TerminalWidget.foregroundJob(4242, "/bin/zsh", args -> List.of("4242 4242 /bin/zsh"))).isEmpty();
        assertThat(TerminalWidget.foregroundJob(4242, "/bin/zsh", args -> List.of("4242 4242 bash"))).as("exec bash").isEmpty();
        assertThat(TerminalWidget.foregroundJob(4242, "/opt/homebrew/bin/fish", args -> List.of("4242 4242 fish"))).isEmpty();
    }

    /// [TerminalWidget#jobName] drops an interpreter in front of a script, keeps a shell given an option, keeps a program's
    /// arguments, and clips at [TerminalWidget#JOB_CHARS] with `…`.
    @Test
    void aJobIsNamedAsTypedAScriptByItsOwnNameNotItsInterpreters() {
        assertThat(TerminalWidget.jobName("/bin/sh /usr/bin/man cat")).isEqualTo("man cat");
        assertThat(TerminalWidget.jobName("/usr/bin/python3 /opt/tools/serve.py --port 8080")).isEqualTo("serve.py --port 8080");
        assertThat(TerminalWidget.jobName("/bin/bash -c make")).as("an option to the shell keeps the shell").isEqualTo("bash -c make");
        assertThat(TerminalWidget.jobName("/usr/bin/less -s")).isEqualTo("less -s");
        assertThat(TerminalWidget.jobName("vim")).isEqualTo("vim");
        assertThat(TerminalWidget.jobName("java " + "-D".repeat(60))).hasSize(TerminalWidget.JOB_CHARS).endsWith("…");
    }

    /// The job in front is named from its process group's leader, a script such as `man` rather than `sh` or `less`; with
    /// the leader gone, another member of the group names it.
    @Test
    void theJobInFrontIsReadFromItsGroupsLeader() throws Exception {
        // the test's own process, given a child, stands for a shell with a job; ps is told what that job is
        Process child = new ProcessBuilder("sleep", "30").start();
        try {
            long self = ProcessHandle.current().pid();
            List<List<String>> calls = new ArrayList<>();
            assertThat(TerminalWidget.foregroundJob(self, "/bin/zsh", args -> {
                calls.add(args);
                return calls.size() == 1 ? List.of("80 76 zsh")
                        : List.of("82 80 /usr/bin/less -s", "80 80 /bin/sh /usr/bin/man cat", "81 80 /bin/sh /usr/bin/man cat");
            })).as("man is a script: the leader names it, not sh or less").contains("man cat");
            assertThat(TerminalWidget.foregroundJob(self, "/bin/zsh", args -> {
                calls.add(args);
                return calls.size() % 2 == 1 ? List.of("80 76 zsh") : List.of("82 80 /usr/bin/less -s");
            })).as("the leader gone: another of its group").contains("less -s");
        } finally {
            child.destroyForcibly();
        }
    }

    /// A foreground group whose members cannot be listed is reported as "a program".
    @Test
    void aJobInFrontWhoseNameCannotBeReadIsStillAJob() {
        long self = ProcessHandle.current().pid();
        List<List<String>> calls = new ArrayList<>();
        assertThat(TerminalWidget.foregroundJob(self, "/bin/zsh", args -> {
            calls.add(args);
            return calls.size() == 1 ? List.of("77 76 zsh") : List.of();
        })).contains("a program");
    }

    /// Zoom adds points, stops at 8 pt, and reset returns to the base size.
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

    /// The widget's component names the widget as its capability owner, and find without a shell finds nothing.
    @Test
    void zoomAndFindReachTheTerminalThroughItsRoot() {
        TerminalWidget t = new TerminalWidget(Path.of(HOME));
        assertThat(t.component().getClientProperty(Capabilities.OWNER)).isSameAs(t);
        assertThat(t.find("anything", true)).isZero(); // no shell yet: nothing to search
    }

    /// Two lines sent before a slow shell prints its first output appear after that output, in order.
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

    /// Find counts both matches of a lower-case word for an upper-case query, and zoom and clear find on a live terminal do
    /// not fail.
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

    /// With no new output, find steps through the same results; after new output on the same screen it counts the new
    /// matches and steps on from the match the user was on.
    @Test
    void findNextSearchesAgainOnceTheOutputChanged(@TempDir Path dir) throws Exception {
        AtomicReference<TerminalWidget> t = new AtomicReference<>();
        Semaphore changed = new Semaphore(0);
        SwingUtilities.invokeAndWait(() -> {
            t.set(new TerminalWidget(dir, List.of("/bin/sh", "-c", "echo alpha needle; echo needle beta; exec cat")));
            t.get().start();
            t.get().buffer().addModelListener(changed::release);
        });
        try {
            awaitScreen(t.get(), changed, s -> s.contains("beta"));
            AtomicReference<Integer> found = new AtomicReference<>();
            AtomicReference<Object> first = new AtomicReference<>();
            AtomicReference<Object> next = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                found.set(t.get().find("needle", true));
                first.set(panel(t.get().component()).getFindResult());
                t.get().find("needle", true);
                next.set(panel(t.get().component()).getFindResult());
            });
            assertThat(found.get()).isEqualTo(2);
            assertThat(next.get()).as("with no new output the next find steps through the same results").isSameAs(first.get());

            // new output on the same screen: the scrollback's line count does not change
            SwingUtilities.invokeAndWait(() -> t.get().send("gamma needle\n"));
            awaitScreen(t.get(), changed, s -> count(s, "gamma") == 2); // the echo and cat's copy
            SwingUtilities.invokeAndWait(() -> found.set(t.get().find("needle", true)));
            assertThat(found.get()).as("the next find counts the new matches").isEqualTo(4);
            AtomicReference<Object> on = new AtomicReference<>();
            AtomicReference<Object> beta = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                on.set(panel(t.get().component()).getFindResult().selectedItem().getStart());
                beta.set(((SubstringFinder.FindResult) first.get()).getItems().get(1).getStart());
            });
            assertThat(on.get()).as("it steps on from the match the user was on, alpha's, to beta's: not to the newest")
                    .isEqualTo(beta.get());
        } finally {
            SwingUtilities.invokeAndWait(() -> t.get().close());
        }
    }

    private static TerminalPanel panel(Component c) {
        if (c instanceof TerminalPanel p) {
            return p;
        }
        if (c instanceof Container k) {
            for (Component child : k.getComponents()) {
                TerminalPanel p = panel(child);
                if (p != null) {
                    return p;
                }
            }
        }
        return null;
    }

    /** Waits, up to eight seconds, for the screen to satisfy {@code done}, rechecking on each change of the buffer. */
    private static void awaitScreen(TerminalWidget t, Semaphore changed, Predicate<String> done) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (true) {
            AtomicReference<String> screen = new AtomicReference<>("");
            SwingUtilities.invokeAndWait(() -> screen.set(t.screenText()));
            if (done.test(screen.get())) {
                return;
            }
            long left = deadline - System.nanoTime();
            assertThat(left > 0 && changed.tryAcquire(left, TimeUnit.NANOSECONDS)).as("the screen: %s", screen.get()).isTrue();
        }
    }

    private static int count(String text, String word) {
        int n = 0;
        for (int i = text.indexOf(word); i >= 0; i = text.indexOf(word, i + 1)) {
            n++;
        }
        return n;
    }

    /// A terminal that never started a shell reports no running job.
    @Test
    void aTerminalWithoutAShellRunsNoJob() {
        assertThat(new TerminalWidget(Path.of(HOME)).hasRunningJob()).isFalse();
    }

    /// In an interactive shell, a job started with `&` is not a foreground job; a job run in front is, by its command.
    @Test
    void onlyAForegroundJobCountsAsRunning(@TempDir Path dir) throws Exception {
        Pty pty = Pty.start(List.of("/bin/sh", "-i"), dir, 80, 24);
        Thread drain = Thread.ofVirtual().start(() -> {
            try {
                pty.process().getInputStream().transferTo(OutputStream.nullOutputStream());
            } catch (IOException e) {
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
            assertThat(TerminalWidget.foregroundJob(shell, "/bin/sh")).contains("sleep 31");
        } finally {
            ProcessHandle.of(pty.process().pid()).ifPresent(h -> h.descendants().forEach(ProcessHandle::destroyForcibly));
            pty.process().destroyForcibly();
            drain.join(2000);
        }
    }

    /// With macOS keys, Select All is bound to ⌘A; elsewhere it has no key so Ctrl+A stays the shell's.
    @Test
    void commandASelectsTheTerminalBufferOnMacOs() {
        var keys = new TerminalWidget.Settings(() -> 13).getSelectAllActionPresentation().getKeyStrokes();
        if (Platform.macKeys()) {
            assertThat(keys).containsExactly(KeyStroke.getKeyStroke(KeyEvent.VK_A,
                    InputEvent.META_DOWN_MASK));
        } else {
            assertThat(keys).as("Ctrl+A stays the shell's start of line").isEmpty();
        }
    }

    /// The jobs of several shells come back by key for the shells that run one, a shell in front left out; a shell
    /// whose process id is unknown counts as running [TerminalWidget#UNCHECKED_JOB].
    @Test
    void severalShellsAnswerByKeyAndAnUnknownProcessIsUnchecked() {
        Map<String, TerminalWidget.Shell> shells = new LinkedHashMap<>();
        shells.put("ssh", new TerminalWidget.Shell(4242, "/bin/zsh"));
        shells.put("idle", new TerminalWidget.Shell(4343, "/bin/zsh"));
        shells.put("unknown", new TerminalWidget.Shell(-1, "/bin/zsh"));
        Map<String, String> jobs = TerminalWidget.foregroundJobs(shells, 2_000,
                args -> args.contains("4242") ? List.of("4242 4242 ssh") : List.of("4343 4343 -zsh"));
        assertThat(jobs).containsExactly(Map.entry("ssh", "ssh"), Map.entry("unknown", TerminalWidget.UNCHECKED_JOB));
    }

    /// Two checks of 600 ms each answer in well under their sum, since they run together; a check still running at
    /// the 1 s bound answers [TerminalWidget#UNCHECKED_JOB], and the call returns at the bound, not when `ps` would.
    @Test
    void theChecksRunTogetherAndAShellNotAnsweredByTheBoundIsUnchecked() {
        Map<String, TerminalWidget.Shell> shells = new LinkedHashMap<>();
        shells.put("a", new TerminalWidget.Shell(1, "/bin/zsh"));
        shells.put("b", new TerminalWidget.Shell(2, "/bin/zsh"));
        long start = System.nanoTime();
        Map<String, String> jobs = TerminalWidget.foregroundJobs(shells, 2_000, args -> {
            sleep(600);
            return List.of(args.getLast() + " " + args.getLast() + " ssh");
        });
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).as("together, not one after the other").isLessThan(1_100);
        assertThat(jobs).containsOnlyKeys("a", "b");

        start = System.nanoTime();
        jobs = TerminalWidget.foregroundJobs(Map.of("slow", new TerminalWidget.Shell(3, "/bin/zsh")), 1_000, args -> {
            sleep(5_000);
            return List.of("3 3 ssh");
        });
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).as("returns at the bound").isBetween(900L, 2_000L);
        assertThat(jobs).containsExactly(Map.entry("slow", TerminalWidget.UNCHECKED_JOB));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
