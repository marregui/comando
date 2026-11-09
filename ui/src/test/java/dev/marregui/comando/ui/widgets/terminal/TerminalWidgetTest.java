package dev.marregui.comando.ui.widgets.terminal;

import java.awt.Component;
import java.awt.Container;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

import com.jediterm.terminal.SubstringFinder;
import com.jediterm.terminal.ui.TerminalPanel;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.port.TerminalPort;
import dev.marregui.comando.ui.kit.desktop.Platform;
import dev.marregui.comando.ui.kit.format.KeyText;
import dev.marregui.comando.ui.widget.capability.Capabilities;

import static org.assertj.core.api.Assertions.assertThat;

/// [TerminalWidget]: a panel's shell, started on demand, with its exit bar, input gate, job check, zoom and find.
/// - before its shell starts the area says where and how; the exit bar names the directory of the restart
/// - text held for a shell that closes never reaches the next; text sent before the shell is ready arrives in order
/// - the job in front fails closed: unreadable `ps` output is an unchecked job; a shell in front is no job; an ended
///   shell's descendants are not read
/// - a job is named as typed, a script by its own name, clipped
/// - zoom stays in bounds and resets; find counts case-insensitively and searches again after new output
/// - only a foreground job counts as running
/// - on macOS ⌘A selects the terminal buffer
/// - closing ends every process the shell started, one that ignores SIGTERM after the grace
/// - an agent's typing never starts a shell; `^C` typed ends the job in front, not the shell; the last lines read back
class TerminalWidgetTest {

    private static final String HOME = System.getProperty("user.home");

    /// An unstarted terminal names its directory, with `~` for home, and the key that starts it; a new directory
    /// updates the text.
    @Test
    void beforeItsShellStartsTheAreaSaysWhereAndHow() {
        final TerminalWidget t = new TerminalWidget(Path.of(HOME, "lane2"));
        assertThat(t.isStarted()).isFalse();
        assertThat(t.emptyText()).isEqualTo(KeyText.of("Shell in ~/lane2 — click or press ⌃3 to start"));
        t.setDirectory(Path.of("/srv/x"));
        assertThat(t.emptyText()).isEqualTo(KeyText.of("Shell in /srv/x — click or press ⌃3 to start"));
    }

    /// After the shell exits, the restart button names the directory set since.
    @Test
    void theExitBarNamesTheDirectoryOfTheRestart() {
        final TerminalWidget t = new TerminalWidget(Path.of(HOME, "edge"));
        t.setDirectory(Path.of(HOME, "lane2"));
        t.showExited(1);
        assertThat(t.restartButtonText()).isEqualTo("Restart in ~/lane2");
    }

    /// The exit bar reads the directory source when it shows, so a rebinding no render reached is used for the restart.
    @Test
    void theExitBarAndRestartReadTheDirectorySourceWhenTheBarShows() {
        final TerminalWidget t = new TerminalWidget(Path.of(HOME, "edge"));
        final AtomicReference<Path> bound = new AtomicReference<>(Path.of(HOME, "edge"));
        t.setDirectorySource(bound::get);
        bound.set(Path.of(HOME, "lane2")); // the panel was rebound while the shell ran: no render reached the terminal
        t.showExited(0);
        assertThat(t.restartButtonText()).isEqualTo("Restart in ~/lane2");
        assertThat(t.directory()).isEqualTo(Path.of(HOME, "lane2"));
    }

    /// Text sent before the shell settles waits; a restart drops it, and the new shell's screen never shows it.
    @Test
    void textWaitingWhenTheShellClosesNeverReachesTheNextShell(@TempDir final Path dir) throws Exception {
        final AtomicReference<TerminalWidget> t = new AtomicReference<>();
        final AtomicReference<Integer> waitingBefore = new AtomicReference<>();
        final AtomicReference<Integer> waitingAfter = new AtomicReference<>();
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
            final AtomicReference<String> screen = new AtomicReference<>("");
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (System.nanoTime() - deadline < 0 && !screen.get().contains("READY")) {
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

    /// A login shell's `-zsh`, a full shell path, another shell started by `exec` and a shell under its install path
    /// are no job.
    @Test
    void aShellInFrontRunsNoJobWhateverItsNameLooksLike() {
        assertThat(TerminalWidget.foregroundJob(4242, "/bin/zsh", args -> List.of("4242 4242 -zsh"))).isEmpty();
        assertThat(TerminalWidget.foregroundJob(4242, "/bin/zsh", args -> List.of("4242 4242 /bin/zsh"))).isEmpty();
        assertThat(TerminalWidget.foregroundJob(4242, "/bin/zsh", args -> List.of("4242 4242 bash"))).as("exec bash").isEmpty();
        assertThat(TerminalWidget.foregroundJob(4242, "/opt/homebrew/bin/fish", args -> List.of("4242 4242 fish"))).isEmpty();
    }

    /// [TerminalWidget#jobName] drops an interpreter in front of a script, keeps a shell given an option, keeps a
    /// program's arguments, and clips at [TerminalWidget#JOB_CHARS] with `…`.
    @Test
    void aJobIsNamedAsTypedAScriptByItsOwnNameNotItsInterpreters() {
        assertThat(TerminalWidget.jobName("/bin/sh /usr/bin/man cat")).isEqualTo("man cat");
        assertThat(TerminalWidget.jobName("/usr/bin/python3 /opt/tools/serve.py --port 8080")).isEqualTo("serve.py --port 8080");
        assertThat(TerminalWidget.jobName("/bin/bash -c make")).as("an option to the shell keeps the shell").isEqualTo("bash -c make");
        assertThat(TerminalWidget.jobName("/usr/bin/less -s")).isEqualTo("less -s");
        assertThat(TerminalWidget.jobName("vim")).isEqualTo("vim");
        assertThat(TerminalWidget.jobName("java " + "-D".repeat(60))).hasSize(TerminalWidget.JOB_CHARS).endsWith("…");
    }

    /// The job in front is named from its process group's leader, a script such as `man` rather than `sh` or `less`;
    /// with the leader gone, another member of the group names it.
    @Test
    void theJobInFrontIsReadFromItsGroupsLeader() throws Exception {
        // the test's own process, given a child, stands for a shell with a job; ps is told what that job is
        final Process child = new ProcessBuilder("sleep", "30").start();
        try {
            final long self = ProcessHandle.current().pid();
            final List<List<String>> calls = new ArrayList<>();
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
        final long self = ProcessHandle.current().pid();
        final List<List<String>> calls = new ArrayList<>();
        assertThat(TerminalWidget.foregroundJob(self, "/bin/zsh", args -> {
            calls.add(args);
            return calls.size() == 1 ? List.of("77 76 zsh") : List.of();
        })).contains("a program");
    }

    /// A shell that ended between the read of its process and the check has its descendants read no more, so a
    /// process given its id since is not taken for its job (own rule 12): the shell's id now names a live process with
    /// a child (this test's JVM, which starts one), yet the job in front is "a program", and `ps` is not asked to list
    /// the members of a group, since the descendants are read through the ended shell's handle, not its id.
    @Test
    void anEndedShellsDescendantsAreNotRead() throws Exception {
        final Process gone = new ProcessBuilder("true").start();
        gone.waitFor();
        final ProcessHandle handle = gone.toHandle();
        final Process child = new ProcessBuilder("sleep", "30").start();
        try {
            final long reused = ProcessHandle.current().pid();
            assertThat(ProcessHandle.current().descendants()).as("the id's process has a child").isNotEmpty();
            final List<List<String>> calls = new ArrayList<>();
            assertThat(TerminalWidget.foregroundJob(reused, handle, "/bin/zsh", args -> {
                calls.add(args);
                return calls.size() == 1 ? List.of("77 76 zsh") : List.of("77 77 /usr/bin/less -s");
            })).contains("a program");
            assertThat(calls).as("the shell's own line only").hasSize(1);
        } finally {
            child.destroy();
            child.waitFor();
        }
    }

    /// Zoom adds points, stops at 8 pt, and reset returns to the base size.
    @Test
    void zoomChangesTheFontSizeWithinBoundsAndResets() {
        final TerminalWidget t = new TerminalWidget(Path.of(HOME));
        final float base = t.fontSize();
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
        final TerminalWidget t = new TerminalWidget(Path.of(HOME));
        assertThat(t.component().getClientProperty(Capabilities.OWNER)).isSameAs(t);
        assertThat(t.find("anything", true)).isZero(); // no shell yet: nothing to search
    }

    /// Two lines sent before a slow shell prints its first output appear after that output, in order.
    @Test
    void textSentBeforeTheShellIsReadyArrivesAfterItsFirstOutputInOrder(@TempDir final Path dir) throws Exception {
        final AtomicReference<TerminalWidget> t = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            t.set(new TerminalWidget(dir, List.of("/bin/sh", "-c", "sleep 0.5; echo READY; exec cat")));
            t.get().send("first\n");
            t.get().send("second\n");
        });
        try {
            final AtomicReference<String> screen = new AtomicReference<>("");
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (System.nanoTime() - deadline < 0 && count(screen.get(), "second") < 2) {
                Thread.sleep(50);
                SwingUtilities.invokeAndWait(() -> screen.set(t.get().screenText()));
            }
            final String text = screen.get();
            assertThat(count(text, "second")).as("echo and cat's copy of both lines: %s", text).isEqualTo(2);
            assertThat(text.indexOf("READY")).as("the shell's first output comes first: %s", text).isNotNegative()
                    .isLessThan(text.indexOf("first"));
            assertThat(text.indexOf("first")).isLessThan(text.indexOf("second"));
        } finally {
            SwingUtilities.invokeAndWait(() -> t.get().close());
        }
    }

    /// Find counts both matches of a lower-case word for an upper-case query, and zoom and clear find on a live
    /// terminal do not fail.
    @Test
    void findCountsMatchesInTheBufferIgnoringCaseAndZoomRefontsALiveTerminal(@TempDir final Path dir) throws Exception {
        final AtomicReference<TerminalWidget> t = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            t.set(new TerminalWidget(dir, List.of("/bin/sh", "-c", "echo alpha needle; echo needle beta; exec cat")));
            t.get().start();
        });
        try {
            final AtomicReference<String> screen = new AtomicReference<>("");
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (System.nanoTime() - deadline < 0 && !screen.get().contains("beta")) {
                Thread.sleep(50);
                SwingUtilities.invokeAndWait(() -> screen.set(t.get().screenText()));
            }
            final AtomicReference<Integer> found = new AtomicReference<>();
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
    void findNextSearchesAgainOnceTheOutputChanged(@TempDir final Path dir) throws Exception {
        final AtomicReference<TerminalWidget> t = new AtomicReference<>();
        final Semaphore changed = new Semaphore(0);
        SwingUtilities.invokeAndWait(() -> {
            t.set(new TerminalWidget(dir, List.of("/bin/sh", "-c", "echo alpha needle; echo needle beta; exec cat")));
            t.get().start();
            t.get().buffer().addModelListener(changed::release);
        });
        try {
            awaitScreen(t.get(), changed, s -> s.contains("beta"));
            final AtomicReference<Integer> found = new AtomicReference<>();
            final AtomicReference<Object> first = new AtomicReference<>();
            final AtomicReference<Object> next = new AtomicReference<>();
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
            final AtomicReference<Object> on = new AtomicReference<>();
            final AtomicReference<Object> beta = new AtomicReference<>();
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

    private static TerminalPanel panel(final Component c) {
        if (c instanceof TerminalPanel p) {
            return p;
        }
        if (c instanceof Container k) {
            for (final Component child : k.getComponents()) {
                final TerminalPanel p = panel(child);
                if (p != null) {
                    return p;
                }
            }
        }
        return null;
    }

    /** Waits, up to eight seconds, for the screen to satisfy {@code done}, rechecking on each change of the buffer. */
    private static void awaitScreen(final TerminalWidget t, final Semaphore changed, final Predicate<String> done) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (true) {
            final AtomicReference<String> screen = new AtomicReference<>("");
            SwingUtilities.invokeAndWait(() -> screen.set(t.screenText()));
            if (done.test(screen.get())) {
                return;
            }
            final long left = deadline - System.nanoTime();
            assertThat(left > 0 && changed.tryAcquire(left, TimeUnit.NANOSECONDS)).as("the screen: %s", screen.get()).isTrue();
        }
    }

    private static int count(final String text, final String word) {
        int n = 0;
        for (int i = text.indexOf(word); i >= 0; i = text.indexOf(word, i + 1)) {
            n++;
        }
        return n;
    }

    /// A shell that starts `nohup sleep 60` and a `sleep 61` that ignores SIGTERM and the hangup, then waits: after
    /// [TerminalWidget#close] neither grandchild's pid is alive within the grace and three seconds, though both outlive
    /// their parent and are then no longer its descendants.
    @Test
    void closingEndsEveryProcessTheShellStarted(@TempDir final Path dir) throws Exception {
        final AtomicReference<TerminalWidget> t = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            t.set(new TerminalWidget(dir, List.of("/bin/sh", "-c",
                    "nohup sleep 60 >/dev/null 2>&1 & echo PID=$!; (trap '' TERM HUP; exec sleep 61) & echo PID=$!; wait")));
            t.get().start();
        });
        final List<Long> pids = new ArrayList<>();
        try {
            final AtomicReference<String> screen = new AtomicReference<>("");
            Awaitility.await("both pids on the screen").atMost(Duration.ofSeconds(8)).until(() -> {
                SwingUtilities.invokeAndWait(() -> screen.set(t.get().screenText()));
                return count(screen.get(), "PID=") == 2;
            });
            final Matcher m = Pattern.compile("PID=(\\d+)").matcher(screen.get());
            while (m.find()) {
                pids.add(Long.parseLong(m.group(1)));
            }
            assertThat(pids).hasSize(2);
            SwingUtilities.invokeAndWait(() -> t.get().close());
            Awaitility.await("the shell's children ended").atMost(Duration.ofSeconds(5))
                    .until(() -> pids.stream().noneMatch(pid -> ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)));
        } finally {
            SwingUtilities.invokeAndWait(() -> t.get().close());
            for (final long pid : pids) {
                ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
            }
        }
    }

    /// A terminal that never started a shell reports no running job.
    @Test
    void aTerminalWithoutAShellRunsNoJob() {
        assertThat(new TerminalWidget(Path.of(HOME)).hasRunningJob()).isFalse();
    }

    /// In an interactive shell, a job started with `&` is not a foreground job; a job run in front is, by its command.
    @Test
    void onlyAForegroundJobCountsAsRunning(@TempDir final Path dir) throws Exception {
        final Pty pty = Pty.start(List.of("/bin/sh", "-i"), dir, 80, 24);
        final Thread drain = Thread.ofVirtual().start(() -> {
            try {
                pty.process().getInputStream().transferTo(OutputStream.nullOutputStream());
            } catch (final IOException e) {
                // the shell ended
            }
        });
        try {
            final long shell = pty.process().pid();
            final var in = pty.process().getOutputStream();
            in.write("sleep 30 &\n".getBytes());
            in.flush();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (ProcessHandle.of(shell).orElseThrow().descendants().findAny().isEmpty() && System.nanoTime() - deadline < 0) {
                Thread.sleep(20);
            }
            assertThat(ProcessHandle.of(shell).orElseThrow().descendants().findAny()).as("the background sleep started").isPresent();
            assertThat(TerminalWidget.foregroundJob(shell, "/bin/sh")).as("a background job").isEmpty();
            in.write("sleep 31\n".getBytes());
            in.flush();
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (TerminalWidget.foregroundJob(shell, "/bin/sh").isEmpty() && System.nanoTime() - deadline < 0) {
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
        final var keys = new TerminalWidget.Settings(() -> 13).getSelectAllActionPresentation().getKeyStrokes();
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
        final Map<String, TerminalWidget.Shell> shells = new LinkedHashMap<>();
        shells.put("ssh", new TerminalWidget.Shell(4242, "/bin/zsh"));
        shells.put("idle", new TerminalWidget.Shell(4343, "/bin/zsh"));
        shells.put("unknown", new TerminalWidget.Shell(-1, "/bin/zsh"));
        final Map<String, String> jobs = TerminalWidget.foregroundJobs(shells, 2_000,
                args -> args.contains("4242") ? List.of("4242 4242 ssh") : List.of("4343 4343 -zsh"));
        assertThat(jobs).containsExactly(Map.entry("ssh", "ssh"), Map.entry("unknown", TerminalWidget.UNCHECKED_JOB));
    }

    /// Two checks run together: each `ps` answers only once the other has started, which checks run one after the
    /// other never do within the bound. A check still running at the 1 s bound answers [TerminalWidget#UNCHECKED_JOB],
    /// and the call returns at the bound, not when `ps` would (8 s later).
    @Test
    void theChecksRunTogetherAndAShellNotAnsweredByTheBoundIsUnchecked() {
        final Map<String, TerminalWidget.Shell> shells = new LinkedHashMap<>();
        shells.put("a", new TerminalWidget.Shell(1, "/bin/zsh"));
        shells.put("b", new TerminalWidget.Shell(2, "/bin/zsh"));
        final CountDownLatch both = new CountDownLatch(2);
        Map<String, String> jobs = TerminalWidget.foregroundJobs(shells, 10_000, args -> {
            both.countDown();
            try {
                final String pid = args.getLast();
                return both.await(10, TimeUnit.SECONDS) ? List.of(pid + " " + pid + " ssh") : List.of();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            }
        });
        assertThat(jobs).as("together, not one after the other")
                .containsOnly(Map.entry("a", "ssh"), Map.entry("b", "ssh"));

        final long start = System.nanoTime();
        jobs = TerminalWidget.foregroundJobs(Map.of("slow", new TerminalWidget.Shell(3, "/bin/zsh")), 1_000, args -> {
            sleep(8_000);
            return List.of("3 3 ssh");
        });
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).as("returns at the bound")
                .isBetween(900L, 6_000L);
        assertThat(jobs).containsExactly(Map.entry("slow", TerminalWidget.UNCHECKED_JOB));
    }

    // ---- what an agent types and reads

    /// A terminal whose shell never started types nothing and starts nothing; it has no page, no line, no output.
    @Test
    void typingIntoAStoppedTerminalStartsNothing(@TempDir final Path dir) throws Exception {
        final AtomicReference<TerminalWidget> t = new AtomicReference<>();
        final AtomicReference<Boolean> typed = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            t.set(new TerminalWidget(dir, List.of("/bin/sh", "-i")));
            typed.set(t.get().type("echo never\r"));
        });
        assertThat(typed.get()).isFalse();
        assertThat(t.get().isStarted()).isFalse();
        assertThat(t.get().shell()).isEmpty();
        assertThat(t.get().page(0, 10, 1_000)).isEmpty();
        assertThat(t.get().page(-1, 10, 1_000)).isEmpty();
        assertThat(t.get().nextLine()).isZero();
        assertThat(t.get().alternateScreen()).isFalse();
        assertThat(t.get().lastOutputNanos()).isEmpty();
    }

    /// In a real interactive `/bin/sh`, `sleep 30` typed then `^C` typed ends the sleep within 2 s and leaves the
    /// shell running; the last lines read back hold the command, and no more of them than asked for.
    @Test
    void controlCTypedEndsTheJobInFrontAndLeavesTheShell(@TempDir final Path dir) throws Exception {
        final AtomicReference<TerminalWidget> t = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            t.set(new TerminalWidget(dir, List.of("/bin/sh", "-i")));
            t.get().start();
        });
        final AtomicReference<ProcessHandle> shell = new AtomicReference<>();
        try {
            Awaitility.await("the shell's first output").atMost(Duration.ofSeconds(8))
                    .until(() -> t.get().lastOutputNanos().isPresent());
            final AtomicReference<Boolean> typed = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> typed.set(t.get().type("echo PID=$$\r")));
            assertThat(typed.get()).isTrue();
            final Pattern pid = Pattern.compile("^PID=(\\d+)$", Pattern.MULTILINE);
            final AtomicReference<Matcher> echoed = new AtomicReference<>();
            Awaitility.await("the shell's pid on the screen").atMost(Duration.ofSeconds(8)).until(() -> {
                echoed.set(pid.matcher(t.get().page(0, 0, 64 * 1024).orElseThrow().text()));
                return echoed.get().find();
            });
            shell.set(ProcessHandle.of(Long.parseLong(echoed.get().group(1))).orElseThrow());
            SwingUtilities.invokeAndWait(() -> typed.set(t.get().type("sleep 30\r")));
            assertThat(typed.get()).isTrue();
            Awaitility.await("sleep runs in front").atMost(Duration.ofSeconds(8))
                    .until(() -> t.get().runningJob().filter(j -> j.contains("sleep")).isPresent());

            SwingUtilities.invokeAndWait(() -> typed.set(t.get().type("\u0003")));
            assertThat(typed.get()).isTrue();
            Awaitility.await("the sleep ended").atMost(Duration.ofSeconds(2))
                    .until(() -> shell.get().descendants().noneMatch(ProcessHandle::isAlive));
            assertThat(shell.get().isAlive()).as("the shell").isTrue();
            assertThat(t.get().isRunning()).isTrue();

            final TerminalPort.Page all = t.get().page(-1, 50, 64 * 1024).orElseThrow();
            assertThat(all.text()).contains("sleep 30");
            assertThat(all.alternate()).isFalse();
            final TerminalPort.Page last = t.get().page(-1, 2, 64 * 1024).orElseThrow();
            assertThat(last.text().split("\n")).hasSizeLessThanOrEqualTo(2);
            assertThat(last.first()).isGreaterThanOrEqualTo(all.first());
            assertThat(t.get().nextLine()).isEqualTo(last.next());
        } finally {
            if (shell.get() != null) {
                shell.get().descendants().forEach(ProcessHandle::destroyForcibly);
            }
            SwingUtilities.invokeAndWait(() -> t.get().close());
        }
    }

    private static void sleep(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
