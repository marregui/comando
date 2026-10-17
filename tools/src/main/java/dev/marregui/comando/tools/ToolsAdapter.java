package dev.marregui.comando.tools;

import dev.marregui.comando.core.console.Console;
import dev.marregui.comando.core.console.ConsoleRegistry;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.port.ToolPort;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Runs the development tools. Each run is a child process on a virtual thread that copies its output
 * into the run's console and sends {@link Intent.CommandFinished} at the end; nothing blocks the caller.
 * Reading a recording or a folder also happens on a virtual thread and ends in a view, and so do the Files tab's
 * copies and deletions, which end in a notice and a read of the open folders.
 */
public final class ToolsAdapter implements ToolPort, AutoCloseable {

    /** Moves a file to the desktop's Trash; false when the desktop has none. */
    @FunctionalInterface
    public interface Trash {
        /** A desktop without a Trash, as in tests and on a server. */
        Trash NONE = file -> false;

        boolean moveToTrash(Path file) throws IOException;
    }

    private static final System.Logger LOG = System.getLogger(ToolsAdapter.class.getName());

    private final IntentSink sink;
    private final ConsoleRegistry consoles;
    /** Read at each run, so a change to the user's settings applies to the next run. */
    private final Supplier<ToolSettings> settings;
    private final Trash trash;
    private final Map<Long, Process> running = new ConcurrentHashMap<>();
    private final Set<Long> cancelled = ConcurrentHashMap.newKeySet();
    private final Set<Long> forgotten = ConcurrentHashMap.newKeySet();
    /** Runs from their start until {@link Intent.CommandFinished} is sent; their consoles must outlive a closed view. */
    private final Set<Long> active = ConcurrentHashMap.newKeySet();
    /** Set by {@link #close}: no process starts after it, so none outlives comando. */
    private volatile boolean closed;

    /** A stopped run's processes still alive this long after SIGTERM get SIGKILL. */
    static final long KILL_AFTER_MILLIS = 2_000;
    /** How long a run waits for its output after the process exited. */
    static final long DRAIN_MILLIS = 2_000;

    public ToolsAdapter(IntentSink sink, ConsoleRegistry consoles, Supplier<ToolSettings> settings) {
        this(sink, consoles, settings, Trash.NONE);
    }

    public ToolsAdapter(IntentSink sink, ConsoleRegistry consoles, Supplier<ToolSettings> settings, Trash trash) {
        this.sink = sink;
        this.consoles = consoles;
        this.settings = settings;
        this.trash = trash;
    }

    @Override
    public void runGradle(long runId, String workDir, String tasks) {
        active.add(runId);
        Thread.ofVirtual().name("gradle-" + runId).start(() -> guarded(runId, () -> {
            Console out = consoles.of(runId);
            ToolSettings tools = settings(out);
            Optional<Path> root = GradleBuild.root(Path.of(workDir));
            if (root.isEmpty()) {
                out.append("No gradlew at or above " + workDir + "\n");
                ended(runId);
                sink.dispatch(new Intent.CommandFinished(runId, 2, 0L, false, ViewSpec.TreeNode.NONE, Map.of()));
                return;
            }
            long start = System.currentTimeMillis();
            int exit = run(runId, GradleBuild.command(root.get(), tasks, tools.gradleSerial()), root.get(), out, tools);
            TestReports reports = exit == Integer.MIN_VALUE ? TestReports.NONE : TestReports.collect(root.get(), start);
            finish(runId, exit, start, reports);
        }));
    }

    @Override
    public void runJfrq(long runId, String recording, String args) {
        active.add(runId);
        Thread.ofVirtual().name("jfrq-" + runId).start(() -> guarded(runId, () -> {
            Console out = consoles.of(runId);
            ToolSettings tools = settings(out);
            if (tools.jfrq().map(j -> !Files.isExecutable(j)).orElse(true)) {
                out.append(tools.jfrq().map(j -> "jfrq not found at " + j).orElse("jfrq not found on the PATH")
                        + "; set its path as \"jfrq\" in tools.json (Panel ▸ Tools ▸ Tool settings)\n");
                ended(runId);
                sink.dispatch(new Intent.CommandFinished(runId, 2, 0L, false, ViewSpec.TreeNode.NONE, Map.of()));
                return;
            }
            List<String> words = new ArrayList<>(Args.split(args));
            String jfrq = tools.jfrq().orElseThrow().toString();
            List<String> cmd = new ArrayList<>(List.of(jfrq, words.isEmpty() ? "info" : words.removeFirst(), recording));
            cmd.addAll(words);
            long start = System.currentTimeMillis();
            Path dir = Path.of(recording).toAbsolutePath().getParent();
            int exit = run(runId, cmd, dir != null && Files.isDirectory(dir) ? dir : Path.of(System.getProperty("user.home")), out, tools);
            finish(runId, exit, start, TestReports.NONE);
        }));
    }

    /** The settings for one run; what was wrong with the user's goes to the run's console first. */
    private ToolSettings settings(Console out) {
        ToolSettings tools = settings.get();
        if (!tools.problem().isEmpty()) {
            out.append(tools.problem() + "\n");
        }
        return tools;
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
    private int run(long runId, List<String> cmd, Path dir, Console out, ToolSettings tools) {
        out.append("$ " + String.join(" ", cmd) + "\n");
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
        pb.environment().putIfAbsent("JAVA_HOME", tools.javaHome());
        if (tools.jfrq().map(j -> cmd.getFirst().equals(j.toString())).orElse(false)) {
            pb.environment().put("JAVA_HOME", tools.javaHome()); // jfrq's launcher needs JDK 25, which runs comando
        }
        if (closed) {
            out.append("Not started: comando is closing\n");
            return Integer.MIN_VALUE;
        }
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            out.append("Cannot start: " + e.getMessage() + "\n");
            return Integer.MIN_VALUE;
        }
        running.put(runId, p);
        if (cancelled.contains(runId) || closed) {
            cancelRun(runId); // the stop, or comando's close, came while the process was starting
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

    /** A folder's tree as last sent, and the ticket of the read that produced it. */
    private record Sent(long ticket, ViewSpec.TreeNode tree) {
    }

    /** Orders reads of the same folder: a read finishing after a newer one has sent never sends. */
    private final AtomicLong readTickets = new AtomicLong();
    /**
     * The last tree sent per folder, keyed by its settings, not its name: a read with settings since edited is ordered
     * apart from the reads with the new ones. Guarded by itself; a send happens under that lock so sends keep order.
     */
    private final Map<FolderRoot, Sent> sentFolders = new HashMap<>();
    /** The folders the Files tab has open: the poll reads these and no others. */
    private volatile List<FolderRoot> watchedFolders = List.of();

    private final ScheduledExecutorService folderPoll =
            Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().name("folder-poll").daemon(true).unstarted(r));

    /** Reads {@code folders} now, off the caller's thread, and sends each tree even when it did not change. */
    @Override
    public void readFolders(List<FolderRoot> folders) {
        List<FolderRoot> copy = List.copyOf(folders);
        Thread.ofVirtual().name("folder-read").start(() -> copy.forEach(f -> readFolder(f, true)));
    }

    /** From now on the poll reads {@code folders} only; a folder newly watched is read at once. */
    @Override
    public void watchFolders(List<FolderRoot> folders) {
        List<FolderRoot> before = watchedFolders;
        watchedFolders = List.copyOf(folders);
        synchronized (sentFolders) {
            // a folder no longer watched sends at its next read whatever it holds: what was sent of it is not needed
            sentFolders.keySet().retainAll(watchedFolders);
        }
        List<FolderRoot> added = folders.stream().filter(f -> !before.contains(f)).toList();
        if (!added.isEmpty()) {
            readFolders(added);
        }
    }

    /**
     * Keeps the Files tab's open folders in sync: every {@code every}, on a thread of its own, reads each watched folder
     * and sends its tree when something was added, removed, renamed or resized. Folders that are not open cost nothing.
     */
    public void pollFolders(Duration every) {
        folderPoll.scheduleWithFixedDelay(() -> {
            try {
                watchedFolders.forEach(f -> readFolder(f, false));
            } catch (RuntimeException e) {
                // a task that throws is never run again: log and keep polling
                LOG.log(System.Logger.Level.WARNING, "folder poll failed: " + e, e);
            }
        }, every.toMillis(), every.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Reads {@code folder} and sends its tree when it changed since the last send, or always when {@code force}. A
     * folder that cannot be read becomes a tree that says so, logged once per change rather than at every read. The
     * read takes a ticket first: when a newer read of the same folder has already sent, this one sends nothing; nor
     * does a poll's read of a folder closed or edited since the poll began.
     */
    private void readFolder(FolderRoot folder, boolean force) {
        long ticket = readTickets.incrementAndGet();
        ViewSpec.TreeNode tree;
        try {
            tree = FolderTree.tree(folder);
        } catch (IOException | RuntimeException e) {
            tree = FolderTree.unreadable(folder, e);
        }
        synchronized (sentFolders) {
            Sent before = sentFolders.get(folder);
            if (before != null && before.ticket() > ticket) {
                return;
            }
            if (!force && !watchedFolders.contains(folder)) {
                // a poll that began before the folder was closed or edited: what it read is no longer asked for
                return;
            }
            sentFolders.put(folder, new Sent(ticket, tree));
            if (!force && before != null && before.tree().equals(tree)) {
                return;
            }
            if (FolderTree.isUnreadable(tree) && (before == null || !before.tree().equals(tree))) {
                LOG.log(System.Logger.Level.WARNING, "cannot read the folder " + folder.name() + " at " + folder.path() + ": "
                        + tree.children().getFirst().label());
            }
            // under the lock, so two sends of one folder reach the state loop in the order their reads were ticketed
            sink.dispatch(new Intent.FolderLoaded(folder, tree));
        }
    }

    /** Copies one file, never over another: a copy onto an existing name is refused, as is a source that is not a file. */
    @Override
    public void copyFile(String from, String to) {
        Path source = Path.of(from);
        Path target = Path.of(to);
        Thread.ofVirtual().name("file-copy").start(() -> fileOp("Cannot copy " + source.getFileName(), () -> {
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("not a file");
            }
            Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
            return "Copied " + source.getFileName() + " to " + target.getFileName();
        }));
    }

    /**
     * Deletes one file, never a folder: to the Trash when asked, and then never for good when the desktop has none, so
     * the user's answer to "Move to the Trash?" is never taken as consent to delete.
     */
    @Override
    public void deleteFile(String file, boolean toTrash) {
        Path path = Path.of(file);
        Thread.ofVirtual().name("file-delete").start(() -> fileOp("Cannot delete " + path.getFileName(), () -> {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("not a file");
            }
            if (!toTrash) {
                Files.delete(path);
                return "Deleted " + path.getFileName();
            }
            if (!trash.moveToTrash(path)) {
                throw new IOException("this desktop has no Trash");
            }
            return "Moved " + path.getFileName() + " to the Trash";
        }));
    }

    /** One copy or deletion, on the calling thread: what it did as a notice, or why it failed; then the open folders read again. */
    private void fileOp(String failure, FileOp op) {
        try {
            sink.dispatch(new Intent.Notify(Notice.Level.INFO, op.run(), false));
        } catch (FileAlreadyExistsException e) {
            sink.dispatch(new Intent.Notify(Notice.Level.ERROR, failure + ": " + Path.of(e.getFile()).getFileName() + " exists", false));
        } catch (NoSuchFileException e) {
            sink.dispatch(new Intent.Notify(Notice.Level.ERROR, failure + ": no such file or folder", false));
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, failure, e);
            sink.dispatch(new Intent.Notify(Notice.Level.ERROR, failure + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), false));
        }
        // the tab shows the change now rather than at the next poll
        watchedFolders.forEach(f -> readFolder(f, true));
    }

    @FunctionalInterface
    private interface FileOp {
        /** Does the work; answers what was done. */
        String run() throws IOException;
    }

    /** Stops every run; called at exit. */
    @Override
    public void close() {
        closed = true;
        folderPoll.shutdownNow();
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
