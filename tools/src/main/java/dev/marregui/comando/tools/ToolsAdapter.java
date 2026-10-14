package dev.marregui.comando.tools;

import dev.marregui.comando.core.console.Console;
import dev.marregui.comando.core.console.ConsoleRegistry;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.port.ToolPort;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs the development tools. Each run is a child process on a virtual thread that copies its output
 * into the run's console and sends {@link Intent.CommandFinished} at the end; nothing blocks the caller.
 * Reading a recording or the scratchpad also happens on a virtual thread and ends in a view.
 */
public final class ToolsAdapter implements ToolPort, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(ToolsAdapter.class.getName());

    private final IntentSink sink;
    private final ConsoleRegistry consoles;
    private final ToolSettings settings;
    private final Map<Long, Process> running = new ConcurrentHashMap<>();
    private final Set<Long> cancelled = ConcurrentHashMap.newKeySet();
    private final Set<Long> forgotten = ConcurrentHashMap.newKeySet();
    /** Runs from their start until {@link Intent.CommandFinished} is sent; their consoles must outlive a closed view. */
    private final Set<Long> active = ConcurrentHashMap.newKeySet();

    /** A stopped run's processes still alive this long after SIGTERM get SIGKILL. */
    static final long KILL_AFTER_MILLIS = 2_000;
    /** How long a run waits for its output after the process exited. */
    static final long DRAIN_MILLIS = 2_000;

    public ToolsAdapter(IntentSink sink, ConsoleRegistry consoles, ToolSettings settings) {
        this.sink = sink;
        this.consoles = consoles;
        this.settings = settings;
    }

    @Override
    public void runGradle(long runId, String workDir, String tasks) {
        active.add(runId);
        Thread.ofVirtual().name("gradle-" + runId).start(() -> guarded(runId, () -> {
            Console out = consoles.of(runId);
            Optional<Path> root = GradleBuild.root(Path.of(workDir));
            if (root.isEmpty()) {
                out.append("No gradlew at or above " + workDir + "\n");
                ended(runId);
                sink.dispatch(new Intent.CommandFinished(runId, 2, 0L, false, ViewSpec.TreeNode.NONE, Map.of()));
                return;
            }
            long start = System.currentTimeMillis();
            int exit = run(runId, GradleBuild.command(root.get(), tasks, settings.gradleSerial()), root.get(), out);
            TestReports reports = exit == Integer.MIN_VALUE ? TestReports.NONE : TestReports.collect(root.get(), start);
            finish(runId, exit, start, reports);
        }));
    }

    @Override
    public void runJfrq(long runId, String recording, String args) {
        active.add(runId);
        Thread.ofVirtual().name("jfrq-" + runId).start(() -> guarded(runId, () -> {
            Console out = consoles.of(runId);
            if (!Files.isExecutable(settings.jfrq())) {
                out.append("jfrq not found at " + settings.jfrq() + "; build it with ./gradlew :cli:installDist in ~/workspace/jfr, "
                        + "or set COMANDO_JFRQ\n");
                ended(runId);
                sink.dispatch(new Intent.CommandFinished(runId, 2, 0L, false, ViewSpec.TreeNode.NONE, Map.of()));
                return;
            }
            List<String> words = new ArrayList<>(Args.split(args));
            List<String> cmd = new ArrayList<>(List.of(settings.jfrq().toString(), words.isEmpty() ? "info" : words.removeFirst(), recording));
            cmd.addAll(words);
            long start = System.currentTimeMillis();
            Path dir = Path.of(recording).toAbsolutePath().getParent();
            int exit = run(runId, cmd, dir != null && Files.isDirectory(dir) ? dir : Path.of(System.getProperty("user.home")), out);
            finish(runId, exit, start, TestReports.NONE);
        }));
    }

    /** Runs a run's body so that it always ends with {@link Intent.CommandFinished}, even when the body fails. */
    private void guarded(long runId, Runnable body) {
        try {
            body.run();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "tool run failed [run=" + runId + ']', e);
            consoles.of(runId).append("\n" + e.getMessage() + "\n");
            cancelled.remove(runId);
            ended(runId);
            sink.dispatch(new Intent.CommandFinished(runId, -1, 0L, false, ViewSpec.TreeNode.NONE, Map.of()));
        }
    }

    /**
     * Runs {@code cmd}, copying its output; returns the exit code, {@link Integer#MIN_VALUE} when it did not
     * start. Output is copied on a thread of its own. A child that outlives the process (forked while it was
     * being stopped, or re-parented) can hold the output pipe open; the run then stops waiting for output
     * {@link #DRAIN_MILLIS} after the process exits, instead of waiting for that child.
     */
    private int run(long runId, List<String> cmd, Path dir, Console out) {
        out.append("$ " + String.join(" ", cmd) + "\n");
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
        pb.environment().putIfAbsent("JAVA_HOME", settings.javaHome());
        if (cmd.getFirst().equals(settings.jfrq().toString())) {
            pb.environment().put("JAVA_HOME", settings.javaHome()); // jfrq's launcher needs JDK 25, which runs comando
        }
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            out.append("Cannot start: " + e.getMessage() + "\n");
            return Integer.MIN_VALUE;
        }
        running.put(runId, p);
        if (cancelled.contains(runId)) {
            cancelRun(runId); // the stop came while the process was starting
        }
        Output output = new Output(out);
        Thread reader = Thread.ofVirtual().name("tool-output-" + runId).start(() -> output.copy(p));
        try {
            p.getOutputStream().close();
            int exit = p.waitFor();
            if (!reader.join(Duration.ofMillis(DRAIN_MILLIS))) {
                output.close("\nA child process still holds the output open; stopped reading it.\n");
            } else {
                output.close(null);
            }
            return exit;
        } catch (IOException e) {
            output.close("\nOutput ended: " + e.getMessage() + "\n");
            return waitQuietly(p);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            terminate(p, true);
            output.close(null);
            return -1;
        } finally {
            running.remove(runId);
        }
    }

    /**
     * The copy of one run's output into its console. The reader appends only while the output is open,
     * under the lock, so after {@link #close} nothing more reaches the console and the closing line is last.
     */
    private static final class Output {
        private final Console out;
        private boolean open = true; // guarded by this

        Output(Console out) {
            this.out = out;
        }

        void copy(Process p) {
            try (Reader r = new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)) {
                char[] buf = new char[8192];
                for (int n; (n = r.read(buf)) >= 0; ) {
                    synchronized (this) {
                        if (!open) {
                            return;
                        }
                        out.append(new String(buf, 0, n));
                    }
                }
            } catch (IOException e) {
                synchronized (this) {
                    if (open) {
                        out.append("\nOutput ended: " + e.getMessage() + "\n");
                    }
                }
            }
        }

        synchronized void close(String last) {
            if (open && last != null) {
                out.append(last);
            }
            open = false;
        }
    }

    private static int waitQuietly(Process p) {
        try {
            return p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private void finish(long runId, int exit, long start, TestReports reports) {
        boolean wasCancelled = cancelled.remove(runId);
        long millis = System.currentTimeMillis() - start;
        Console console = consoles.of(runId);
        console.seal();
        console.append(wasCancelled ? "\nStopped.\n" : "\nExit code " + exit + " after " + millis / 1000.0 + " s\n");
        ended(runId);
        sink.dispatch(new Intent.CommandFinished(runId, exit, millis, wasCancelled, reports.tree(), reports.failures()));
    }

    private void ended(long runId) {
        active.remove(runId);
        if (forgotten.remove(runId)) {
            consoles.forget(runId);
        }
    }

    @Override
    public void cancelRun(long runId) {
        cancelled.add(runId); // also before the process exists: the run stops it as soon as it starts
        Process p = running.get(runId);
        if (p == null) {
            return;
        }
        List<ProcessHandle> tree = terminate(p, false);
        Thread.ofVirtual().name("tool-kill-" + runId).start(() -> {
            try {
                Thread.sleep(KILL_AFTER_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            forceRemaining(p, tree);
        });
    }

    /**
     * Sends SIGTERM (or SIGKILL with {@code force}) to the process and its descendants; returns the
     * descendants it found, so a later pass can reach those the process's exit re-parented.
     */
    private static List<ProcessHandle> terminate(Process p, boolean force) {
        List<ProcessHandle> tree = p.toHandle().descendants().toList();
        for (ProcessHandle h : tree) {
            if (force) {
                h.destroyForcibly();
            } else {
                h.destroy();
            }
        }
        if (force) {
            p.destroyForcibly();
        } else {
            p.destroy();
        }
        return tree;
    }

    /** SIGKILL to whatever of the run's process tree is still alive: a child that ignores SIGTERM, or one forked late. */
    private static void forceRemaining(Process p, List<ProcessHandle> tree) {
        for (ProcessHandle h : tree) {
            if (h.isAlive()) {
                h.destroyForcibly();
            }
        }
        if (p.isAlive()) {
            terminate(p, true);
        }
    }

    @Override
    public void forgetRun(long runId) {
        forgotten.add(runId);
        if (!active.contains(runId)) {
            forgotten.remove(runId);
            consoles.forget(runId);
        }
        // else the run's end releases the console (finish), so a view closed mid-run leaves nothing behind
    }

    @Override
    public void openRecording(String path) {
        Thread.ofVirtual().name("recording").start(() -> {
            Path file = Path.of(path);
            String name = file.getFileName().toString();
            try {
                Recordings.Summary s = Recordings.read(file);
                sink.dispatch(new Intent.ShowView("", "jfr · " + name, s.events(), Placement.LOWER));
                if (s.cpu() != null) {
                    sink.dispatch(new Intent.ShowView("", "cpu · " + name, s.cpu(), Placement.LOWER));
                }
                if (s.heap() != null) {
                    sink.dispatch(new Intent.ShowView("", "heap · " + name, s.heap(), Placement.LOWER));
                }
                sink.dispatch(new Intent.Notify(Notice.Level.INFO, name + ": " + s.events().rows().size() + " event types", false));
            } catch (IOException | RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "cannot read " + path + ": " + e.getMessage());
                sink.dispatch(new Intent.Notify(Notice.Level.ERROR, "Cannot read " + name + ": " + e.getMessage(), false));
            }
        });
    }

    /** The scratchpad as last sent, so a read that finds it unchanged sends nothing. */
    private final AtomicReference<ViewSpec.TreeNode> scratchpad = new AtomicReference<>();
    /** One read at a time: an older read finishing last would send a stale tree, and a slow disk would pile reads up. */
    private final AtomicBoolean readingScratchpad = new AtomicBoolean();

    /** Reads the scratchpad for the sidebar now, off the caller's thread; see {@link #readScratchpad}. */
    @Override
    public void browseScratchpad() {
        Thread.ofVirtual().name("scratchpad").start(this::readScratchpad);
    }

    private final ScheduledExecutorService scratchpadWatch =
            Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().name("scratchpad-watch").daemon(true).unstarted(r));

    /**
     * Keeps the sidebar's scratchpad in sync whether it shows or not: reads the whole folder every {@code every},
     * on a thread of its own, and sends the tree when something was added, removed, renamed or resized.
     */
    public void watchScratchpad(Duration every) {
        scratchpadWatch.scheduleWithFixedDelay(this::readScratchpad, 0, every.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Reads the scratchpad and sends it only when it changed since the last read. A folder that cannot be read
     * becomes a tree that says so, not a notice at every read. A read while another runs is dropped: that one
     * returns what is on disk, and an older read finishing last would send a stale tree.
     */
    private void readScratchpad() {
        if (!readingScratchpad.compareAndSet(false, true)) {
            return;
        }
        try {
            ViewSpec.TreeNode root;
            try {
                root = Scratchpad.tree(settings.scratchpad());
            } catch (IOException | RuntimeException e) {
                root = new ViewSpec.TreeNode(settings.scratchpad().toString(), "cannot be read: " + e.getMessage(), List.of());
            }
            ViewSpec.TreeNode before = scratchpad.getAndSet(root);
            if (!root.equals(before)) {
                if (root.detail().startsWith("cannot be read")) {
                    LOG.log(System.Logger.Level.WARNING, "cannot read the scratchpad at " + settings.scratchpad() + ": " + root.detail());
                }
                sink.dispatch(new Intent.ScratchpadLoaded(root));
            }
        } finally {
            readingScratchpad.set(false);
        }
    }

    /** Stops every run; called at exit. */
    @Override
    public void close() {
        scratchpadWatch.shutdownNow();
        Map<Process, List<ProcessHandle>> trees = new HashMap<>();
        for (Process p : List.copyOf(running.values())) {
            trees.put(p, terminate(p, false));
        }
        long deadline = System.nanoTime() + KILL_AFTER_MILLIS * 1_000_000L;
        for (Map.Entry<Process, List<ProcessHandle>> e : trees.entrySet()) {
            try {
                e.getKey().waitFor(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            forceRemaining(e.getKey(), e.getValue());
        }
    }
}
