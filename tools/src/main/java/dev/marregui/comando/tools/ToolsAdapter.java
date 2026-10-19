package dev.marregui.comando.tools;

import dev.marregui.comando.core.console.Console;
import dev.marregui.comando.core.console.ConsoleRegistry;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.ChangeAsk;
import dev.marregui.comando.core.model.ChangeTexts;
import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.RepoStatus;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.port.ToolPort;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.view.ViewSpec;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Runs the development tools. Each run is a child process on a virtual thread that copies its output
 * into the run's console and sends {@link Intent.CommandFinished} at the end; nothing blocks the caller.
 * Reading a folder also happens on a virtual thread and ends in a view, and so does moving a folder's entries to the
 * Trash, which ends in a notice and a read of the open folders. The git repositories of the open folders, and of every
 * folder while the Changes view is open, are read with the folders; what git ignores is left out of their trees.
 */
public final class ToolsAdapter implements ToolPort, AutoCloseable {

    /** Moves a file or a folder to the desktop's Trash; false when the desktop has none. */
    @FunctionalInterface
    public interface Trash {
        /** A desktop without a Trash, as in tests and on a server. */
        Trash NONE = file -> false;

        boolean moveToTrash(Path file) throws IOException;
    }

    private static final System.Logger LOG = System.getLogger(ToolsAdapter.class.getName());

    private final IntentSink sink;
    private final ConsoleRegistry consoles;
    /** Read at each run, so a kit installed while comando runs serves the next run. */
    private final Supplier<ToolSettings> settings;
    private final Trash trash;
    private final Git git;
    /** One move to the Trash at a time, so two never edit one listing file together. */
    private final ReentrantLock trashing = new ReentrantLock();
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
        this(sink, consoles, settings, trash, new Git());
    }

    /** With the git it runs; tests name one that is missing. */
    ToolsAdapter(IntentSink sink, ConsoleRegistry consoles, Supplier<ToolSettings> settings, Trash trash, Git git) {
        this.sink = sink;
        this.consoles = consoles;
        this.settings = settings;
        this.trash = trash;
        this.git = git;
    }

    @Override
    public void runGradle(long runId, String workDir, String tasks) {
        active.add(runId);
        Thread.ofVirtual().name("gradle-" + runId).start(() -> guarded(runId, () -> {
            Console out = consoles.of(runId);
            ToolSettings tools = settings.get();
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
            // the repositories first, so the new folders' trees leave out what git ignores and their colours come with them
            Thread.ofVirtual().name("folder-read").start(() -> {
                readRepos(false, false);
                added.forEach(f -> readFolder(f, true));
            });
        }
    }

    /**
     * Keeps the Files tab's open folders in sync: every {@code every}, on a thread of its own, reads each watched folder
     * and sends its tree when something was added, removed, renamed or resized. Folders that are not open cost nothing.
     */
    public void pollFolders(Duration every) {
        folderPoll.scheduleWithFixedDelay(() -> {
            try {
                readRepos(false, polls++ % COLD_POLLS == 0);
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
            tree = FolderTree.tree(folder, Files::list, ignoredIn(folder));
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

    // ---- git repositories

    /** A status read, and when it was read. */
    private record RepoRead(Git.Read read, long at) {
    }

    /** A status read this recently serves a folder read instead of running git again. */
    static final long REPO_FRESH_MILLIS = 30_000;

    /** The latest read of each repository by its root. Guarded by {@link #repoReading}. */
    private final Map<Path, RepoRead> repoReads = new HashMap<>();
    /** The repositories as last sent, null before the first send. Guarded by {@link #repoReading}. */
    private List<RepoStatus> sentRepos;
    /** One status read at a time: the poll's, a newly watched folder's, a folder read for the filter. */
    private final ReentrantLock repoReading = new ReentrantLock();
    /** Every configured folder, whose repositories are read: the watched ones every poll, the others every {@link #COLD_POLLS}. */
    private volatile List<FolderRoot> repoFolders = List.of();
    /** Whether the Changes view is open: then every folder's repository is read every poll. */
    private volatile boolean changesShown;
    /** A repository of a closed folder, while the Changes view is closed, is read once in this many polls. */
    static final int COLD_POLLS = 6;
    /** Polls so far. Poll thread only. */
    private long polls;

    /** From now on the poll reads the repositories of {@code folders}, often while {@code changesShown}; all are read at once. */
    @Override
    public void watchRepos(List<FolderRoot> folders, boolean changesShown) {
        repoFolders = List.copyOf(folders);
        this.changesShown = changesShown;
        Thread.ofVirtual().name("repo-read").start(() -> readRepos(true, true));
    }

    /**
     * Reads the repositories of every configured folder and sends them when any changed since the last send, or always
     * when {@code force}. Those of the watched folders, and every one while the Changes view is open, are read now; the
     * others when {@code all}, or when never read, else they keep their last read.
     */
    private void readRepos(boolean force, boolean all) {
        repoReading.lock();
        try {
            if (closed) {
                return;
            }
            // the folders as they are now, under the lock: a read that waited for another never sends an older set after it
            List<Path> roots = new ArrayList<>();
            Set<FolderRoot> folders = new LinkedHashSet<>(repoFolders);
            folders.addAll(watchedFolders);
            for (FolderRoot f : folders) {
                for (Path r : Git.repos(Path.of(f.path()))) {
                    if (!roots.contains(r)) {
                        roots.add(r);
                    }
                }
            }
            Set<Path> hot = new HashSet<>();
            for (FolderRoot f : changesShown ? folders : new LinkedHashSet<>(watchedFolders)) {
                hot.addAll(Git.repos(Path.of(f.path())));
            }
            repoReads.keySet().retainAll(roots);
            List<RepoStatus> statuses = new ArrayList<>(roots.size());
            for (Path r : roots) {
                RepoRead read = repoReads.get(r);
                if (read == null || all || hot.contains(r)) {
                    read = new RepoRead(git.status(r), System.currentTimeMillis());
                    repoReads.put(r, read);
                }
                statuses.add(read.read().status());
            }
            if (force || !statuses.equals(sentRepos)) {
                sentRepos = List.copyOf(statuses);
                // under the lock, so sends reach the state loop in the order they were read
                sink.dispatch(new Intent.ReposLoaded(sentRepos));
            }
        } finally {
            repoReading.unlock();
        }
    }

    /**
     * The entries git ignores in the repositories {@code folder} belongs to, which its tree leaves out: from a read of
     * the last {@link #REPO_FRESH_MILLIS}, else from one made now. A folder git ignores as a whole (one inside a build
     * folder, say) leaves nothing out: the user asked for it.
     */
    private Predicate<Path> ignoredIn(FolderRoot folder) {
        Path top = Path.of(folder.path()).toAbsolutePath().normalize();
        List<Path> roots = Git.repos(top);
        if (roots.isEmpty()) {
            return p -> false;
        }
        Set<Path> ignored = new HashSet<>();
        repoReading.lock();
        try {
            for (Path r : roots) {
                RepoRead read = repoReads.get(r);
                if (read == null || System.currentTimeMillis() - read.at() > REPO_FRESH_MILLIS) {
                    if (closed) {
                        break;
                    }
                    read = new RepoRead(git.status(r), System.currentTimeMillis());
                    repoReads.put(r, read);
                }
                ignored.addAll(read.read().ignored());
            }
        } finally {
            repoReading.unlock();
        }
        for (Path p = top; p != null; p = p.getParent()) {
            if (ignored.contains(p)) {
                return x -> false;
            }
        }
        return ignored.isEmpty() ? p -> false : p -> ignored.contains(p.toAbsolutePath().normalize());
    }

    /**
     * Reads changes one at a time, in the order asked: the selection moving fast through the Changes view ends on the
     * file it ends on, never on one whose read finished late.
     */
    private final ExecutorService changeReads =
            Executors.newSingleThreadExecutor(r -> Thread.ofVirtual().name("change-read").unstarted(r));

    /** Reads {@code change}'s two versions off the caller's thread and sends them. */
    @Override
    public void readChange(String root, RepoStatus.FileChange change, ChangeAsk ask) {
        if (closed) {
            return;
        }
        try {
            changeReads.execute(() -> readChange(Path.of(root), change, ask));
        } catch (RejectedExecutionException e) {
            // closing: nothing is shown any more
        }
    }

    private void readChange(Path root, RepoStatus.FileChange change, ChangeAsk ask) {
        ChangeTexts texts;
        try {
            texts = git.texts(root, change);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot read a change [path=" + change.path() + ']', e);
            texts = ChangeTexts.noted(root.toString(), change, "cannot be read: " + e.getMessage());
        }
        sink.dispatch(new Intent.ChangeRead(texts, ask));
    }

    /**
     * Changes the working trees and indexes of repositories one at a time, in the order asked, then reads the
     * repositories again so the Files tab and the Changes view show the result.
     */
    private final ExecutorService repoWrites =
            Executors.newSingleThreadExecutor(r -> Thread.ofVirtual().name("repo-write").unstarted(r));

    @Override
    public void rollBack(String root, List<RepoStatus.FileChange> changes) {
        onRepository(() -> sink.dispatch(rollBack(Path.of(root).toAbsolutePath().normalize(), changes)));
    }

    @Override
    public void removeFiles(String root, List<String> paths) {
        onRepository(() -> sink.dispatch(remove(Path.of(root).toAbsolutePath().normalize(), paths)));
    }

    @Override
    public void unstage(String root, List<String> paths) {
        onRepository(() -> {
            List<String> args = new ArrayList<>(List.of("restore", "--staged", "--"));
            args.addAll(paths);
            try {
                git(Path.of(root), args);
            } catch (IOException e) {
                sink.dispatch(new Intent.Notify(Notice.Level.ERROR, "Cannot unstage " + String.join(", ", paths) + ": " + e.getMessage(), false));
            }
        });
    }

    private void onRepository(Runnable work) {
        if (closed) {
            return;
        }
        try {
            repoWrites.execute(() -> {
                try {
                    work.run();
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING, "changing a repository failed", e);
                    sink.dispatch(new Intent.Notify(Notice.Level.ERROR, "Git failed: " + e.getMessage(), false));
                } finally {
                    readRepos(true, true);
                }
            });
        } catch (RejectedExecutionException e) {
            // closing: nothing more is changed
        }
    }

    /**
     * Takes each change back to {@code HEAD}: a version that differs from it goes to the Trash first, and nothing
     * happens to a file whose version cannot go there; a file {@code HEAD} does not have goes to the Trash and out of
     * the index; a renamed file's old path comes back. Answers the notice saying what came of it.
     */
    Intent.Notify rollBack(Path root, List<RepoStatus.FileChange> changes) {
        List<String> failed = new ArrayList<>();
        int done = 0;
        int trashed = 0;
        String last = "";
        for (RepoStatus.FileChange c : changes) {
            Path file = inside(root, c.path());
            String name = Path.of(c.path()).getFileName().toString();
            if (file == null) {
                failed.add(c.path() + ": not in the repository");
                continue;
            }
            try {
                boolean there = Files.exists(file, LinkOption.NOFOLLOW_LINKS);
                switch (c.kind()) {
                    case UNTRACKED -> toTrash(file);
                    case ADDED, COPIED -> {
                        if (there) {
                            toTrash(file);
                        }
                        git(root, List.of("rm", "--cached", "-q", "--ignore-unmatch", "--", c.path()));
                    }
                    case RENAMED -> {
                        if (there) {
                            toTrash(file);
                        }
                        git(root, List.of("rm", "--cached", "-q", "--ignore-unmatch", "--", c.path()));
                        git(root, List.of("restore", "--source=HEAD", "--staged", "--worktree", "--", c.from()));
                    }
                    default -> {
                        if (there) {
                            toTrash(file);
                        }
                        git(root, List.of("restore", "--source=HEAD", "--staged", "--worktree", "--", c.path()));
                    }
                }
                trashed += there ? 1 : 0;
                done++;
                last = name;
            } catch (IOException e) {
                failed.add(name + ": " + e.getMessage());
            }
        }
        StringBuilder text = new StringBuilder(done == 0 ? "Nothing rolled back"
                : "Rolled back " + (done == 1 ? last : done + " files") + " to HEAD");
        if (trashed > 0) {
            text.append(trashed == 1 ? "; the version you had is in the Trash" : "; the versions you had are in the Trash");
        }
        failed.forEach(f -> text.append("; ").append(f));
        return new Intent.Notify(failed.isEmpty() ? Notice.Level.INFO : Notice.Level.WARN, text.toString(), false);
    }

    /** Moves each file to the Trash; one {@code HEAD} does not have also leaves the index. Answers what came of it. */
    Intent.Notify remove(Path root, List<String> paths) {
        List<String> failed = new ArrayList<>();
        int done = 0;
        String last = "";
        for (String p : paths) {
            Path file = inside(root, p);
            String name = Path.of(p).getFileName().toString();
            if (file == null || !Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                failed.add(name + (file == null ? ": not in the repository" : ": no such file"));
                continue;
            }
            try {
                toTrash(file);
                if (git.run(root, List.of("cat-file", "-e", "HEAD:" + p)).exit() != 0) {
                    git(root, List.of("rm", "--cached", "-q", "--ignore-unmatch", "--", p));
                }
                done++;
                last = name;
            } catch (IOException e) {
                failed.add(name + ": " + e.getMessage());
            }
        }
        StringBuilder text = new StringBuilder(done == 0 ? "Nothing moved to the Trash"
                : "Moved " + (done == 1 ? last : done + " files") + " to the Trash");
        failed.forEach(f -> text.append("; ").append(f));
        return new Intent.Notify(failed.isEmpty() ? Notice.Level.INFO : Notice.Level.WARN, text.toString(), false);
    }

    /** {@code relative} under {@code root}, or null when it is the root itself or leaves it. */
    private static Path inside(Path root, String relative) {
        Path p = root.resolve(relative).normalize();
        return p.startsWith(root) && !p.equals(root) ? p : null;
    }

    /** Moves {@code file} to the Trash; throws, leaving it in place, when the desktop has none. */
    private void toTrash(Path file) throws IOException {
        if (!trash.moveToTrash(file)) {
            throw new IOException("this desktop has no Trash; left as it is");
        }
    }

    /** Runs git in {@code root}; throws with git's own words when it fails or takes too long. */
    private void git(Path root, List<String> args) throws IOException {
        Git.Run r = git.run(root, args);
        if (r.exit() != 0) {
            throw new IOException(r.exit() < 0 ? "git took too long" : r.err().isBlank() ? "git exited with " + r.exit() : r.err().strip());
        }
    }

    /**
     * Moves {@code paths} to the Trash, off the caller's thread, then takes each moved entry's own lines out of the
     * nearest listing file above it ({@link FolderRoot#listedIn}), and reads the open folders again. Never deletes for
     * good: without a Trash nothing moves. An entry inside another one asked for goes with it.
     */
    @Override
    public void deleteFiles(FolderRoot folder, List<String> paths) {
        Path root = Path.of(folder.path()).toAbsolutePath().normalize();
        List<Path> asked = paths.stream().map(p -> Path.of(p).toAbsolutePath().normalize()).distinct().toList();
        // only an entry that goes itself takes the ones inside it along: the folder, refused, takes nothing
        List<Path> items = asked.stream().filter(p -> asked.stream()
                .noneMatch(o -> !o.equals(p) && p.startsWith(o) && o.startsWith(root) && !o.equals(root))).toList();
        Thread.ofVirtual().name("file-trash").start(() -> {
            trashing.lock();
            try {
                sink.dispatch(trash(folder, items));
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "moving to the Trash failed", e);
                sink.dispatch(new Intent.Notify(Notice.Level.ERROR, "Cannot move to the Trash: " + e, false));
            } finally {
                trashing.unlock();
            }
            // the tab shows the change now rather than at the next poll
            watchedFolders.forEach(f -> readFolder(f, true));
        });
    }

    /** Moves {@code items}, then edits their listing files; answers the notice that says what came of it. */
    private Intent.Notify trash(FolderRoot folder, List<Path> items) {
        Path root = Path.of(folder.path()).toAbsolutePath().normalize();
        List<String> failed = new ArrayList<>();
        // each listing file with the entries it loses, relative to its folder, a folder's with a trailing slash
        Map<Path, List<String>> listed = new LinkedHashMap<>();
        // each entry's listing, found before anything moves: a listing that goes in the same batch is not replaced by one above it
        Map<Path, Optional<Path>> listings = new HashMap<>();
        items.forEach(item -> listings.put(item, listing(folder, root, item)));
        int moved = 0;
        String last = "";
        for (int at = 0; at < items.size(); at++) {
            Path item = items.get(at);
            String name = item.getFileName() == null ? item.toString() : item.getFileName().toString();
            if (!folder.deletable() || !item.startsWith(root) || item.equals(root)) {
                failed.add(name + ": not an entry of folder " + folder.name() + " that goes to the Trash");
                continue;
            }
            if (!Files.exists(item, LinkOption.NOFOLLOW_LINKS)) {
                failed.add(name + ": no such file or folder");
                continue;
            }
            boolean dir = Files.isDirectory(item, LinkOption.NOFOLLOW_LINKS);
            try {
                if (!trash.moveToTrash(item)) {
                    int left = items.size() - at - 1;
                    failed.add(name + ": this desktop has no Trash" + (left == 0 ? "" : "; " + left + " more left in place"));
                    break;
                }
            } catch (IOException | RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "cannot move " + item + " to the Trash", e);
                failed.add(name + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                continue;
            }
            moved++;
            last = name;
            listings.get(item).ifPresent(l -> listed.computeIfAbsent(l, k -> new ArrayList<>())
                    .add(l.getParent().relativize(item).toString().replace('\\', '/') + (dir ? "/" : "")));
        }
        StringBuilder text = new StringBuilder(moved == 0 ? "Nothing moved to the Trash"
                : "Moved " + (moved == 1 ? last : moved + " entries") + " to the Trash");
        List<String> unlisted = new ArrayList<>();
        for (Map.Entry<Path, List<String>> e : listed.entrySet()) {
            String where = root.relativize(e.getKey()).toString();
            if (!Files.isRegularFile(e.getKey(), LinkOption.NOFOLLOW_LINKS)) {
                // the listing file went to the Trash too, after the entry it lists
                continue;
            }
            try {
                ListingLines.Result r = ListingLines.without(Files.readString(e.getKey(), StandardCharsets.UTF_8), e.getValue());
                if (r.removed() > 0) {
                    Path tmp = e.getKey().resolveSibling("." + e.getKey().getFileName() + ".comando-tmp");
                    Files.writeString(tmp, r.text(), StandardCharsets.UTF_8);
                    Files.move(tmp, e.getKey(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    text.append("; ").append(r.removed()).append(r.removed() == 1 ? " line" : " lines").append(" out of ").append(where);
                }
                if (!r.mentions().isEmpty()) {
                    text.append("; still named in ").append(where).append(r.mentions().size() == 1 ? " at line " : " at lines ")
                            .append(String.join(", ", r.mentions().stream().map(String::valueOf).toList()));
                }
            } catch (IOException | RuntimeException ex) {
                LOG.log(System.Logger.Level.WARNING, "cannot update " + e.getKey(), ex);
                unlisted.add(where + " not updated: " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
            }
        }
        if (!failed.isEmpty()) {
            text.append("; cannot move ").append(String.join("; ", failed));
        }
        unlisted.forEach(u -> text.append("; ").append(u));
        return new Intent.Notify(failed.isEmpty() && unlisted.isEmpty() ? Notice.Level.INFO : Notice.Level.ERROR, text.toString(), false);
    }

    /** The listing file nearest {@code item}: in its folder or one above it, up to the configured folder; empty when none. */
    private static Optional<Path> listing(FolderRoot folder, Path root, Path item) {
        if (folder.listedIn().isEmpty()) {
            return Optional.empty();
        }
        for (Path dir = item.getParent(); dir != null && dir.startsWith(root); dir = dir.getParent()) {
            Path candidate = dir.resolve(folder.listedIn());
            if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** Stops every run; called at exit. */
    @Override
    public void close() {
        closed = true;
        folderPoll.shutdownNow();
        changeReads.shutdownNow();
        repoWrites.shutdownNow();
        git.close();
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
