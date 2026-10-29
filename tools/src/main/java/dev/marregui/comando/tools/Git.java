package dev.marregui.comando.tools;

import dev.marregui.comando.core.loop.Processes;
import dev.marregui.comando.core.model.ChangeTexts;
import dev.marregui.comando.core.model.RepoStatus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * The git command line: which repositories a folder belongs to, what {@code git status} says of one, a changed file's
 * two versions, and the few writes the comparison window asks for (restore, unstage, remove from the index). Every run is bounded in time and in output, takes no optional lock (so it never holds
 * up the user's own git commands), never asks for credentials, and ends when this closes.
 */
final class Git implements AutoCloseable {


    /** A run still going after this is killed. */
    static final long TIMEOUT_MILLIS = 10_000;
    /** Output kept per run at most; a status past it is cut, a file past it not compared. */
    static final int MAX_OUTPUT = 32 * 1024 * 1024;
    /** A version larger than this is not compared. */
    static final int MAX_FILE_BYTES = 4 * 1024 * 1024;
    /** Repositories found directly inside a folder at most. */
    static final int MAX_REPOS = 20;
    /** The bytes looked at for a NUL, as git does, to tell a binary file. */
    private static final int BINARY_PROBE = 8_000;

    /** What one status read gives: the status, and the ignored entries the Files tab leaves out. */
    record Read(RepoStatus status, Set<Path> ignored) {
        Read {
            ignored = Set.copyOf(ignored);
        }
    }

    /** What a run gave: its exit code, its output, the start of its errors; exit -1 when it did not end in time. */
    record Run(int exit, byte[] out, String err, boolean cut) {
    }

    private final String git;
    private final Set<Process> live = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    Git() {
        this("git");
    }

    /** With the command to run; tests name a fake. */
    Git(String git) {
        this.git = git;
    }

    /**
     * The repositories {@code folder} belongs to: the one that holds it, else those directly inside it, by name. A
     * repository is a folder with a {@code .git} entry (a folder, or a file for a worktree or a submodule).
     */
    static List<Path> repos(Path folder) {
        Path start = folder.toAbsolutePath().normalize();
        for (Path p = start; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
                return List.of(p);
            }
        }
        if (!Files.isDirectory(start)) {
            return List.of();
        }
        try (Stream<Path> list = Files.list(start)) {
            return list.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) && Files.exists(p.resolve(".git"), LinkOption.NOFOLLOW_LINKS))
                    .sorted(Comparator.comparing(Path::toString)).limit(MAX_REPOS).toList();
        } catch (IOException | UncheckedIOException e) {
            return List.of();
        }
    }

    /** The repository at {@code root} as {@code git status} reads it; a failure is a status that says why. */
    Read status(Path root) {
        Run r;
        try {
            r = run(root, List.of("status", "--porcelain=v2", "-z", "--branch", "--ignored=matching", "--untracked-files=all"));
        } catch (IOException e) {
            return new Read(RepoStatus.failed(root.toString(), e.getMessage() == null ? "git cannot be run" : e.getMessage()), Set.of());
        }
        if (r.exit() != 0) {
            String why = r.exit() < 0 ? "git status took longer than " + TIMEOUT_MILLIS / 1000 + " s"
                    : r.err().isBlank() ? "git status failed with exit code " + r.exit() : r.err().strip();
            return new Read(RepoStatus.failed(root.toString(), why), Set.of());
        }
        return parse(root, r.out(), r.cut());
    }

    /**
     * {@code git status --porcelain=v2 -z --branch --ignored=matching} output as a status: the changes ordered by
     * path and cut at {@link RepoStatus#MAX_CHANGES}, and the ignored entries as absolute paths. {@code cut} says the
     * output itself was cut, so the changes are not all there.
     */
    static Read parse(Path root, byte[] out, boolean cut) {
        String text = new String(out, StandardCharsets.UTF_8);
        String[] records = text.split("\0", -1);
        String branch = "";
        String head = "";
        List<RepoStatus.FileChange> changes = new ArrayList<>();
        Set<Path> ignored = new HashSet<>();
        for (int i = 0; i < records.length; i++) {
            String rec = records[i];
            if (rec.isEmpty()) {
                continue;
            }
            switch (rec.charAt(0)) {
                case '#' -> {
                    if (rec.startsWith("# branch.head ")) {
                        branch = rec.substring("# branch.head ".length());
                    } else if (rec.startsWith("# branch.oid ")) {
                        String oid = rec.substring("# branch.oid ".length());
                        head = oid.equals("(initial)") ? "" : oid;
                    }
                }
                case '1' -> {
                    String[] f = rec.split(" ", 9);
                    if (f.length == 9) {
                        kind(f[1]).ifPresent(k -> changes.add(new RepoStatus.FileChange(f[8], "", k)));
                    }
                }
                case '2' -> {
                    String[] f = rec.split(" ", 10);
                    // the path it had follows as a record of its own
                    String from = i + 1 < records.length ? records[++i] : "";
                    if (f.length == 10) {
                        boolean copied = f[1].charAt(0) == 'C';
                        if (f[1].length() > 1 && f[1].charAt(1) == 'D') {
                            // renamed in the index, then deleted from the tree: against HEAD the old path is gone; a copy's source stays
                            if (!copied) {
                                changes.add(new RepoStatus.FileChange(from, "", RepoStatus.Kind.DELETED));
                            }
                        } else {
                            changes.add(new RepoStatus.FileChange(f[9], from, copied ? RepoStatus.Kind.COPIED : RepoStatus.Kind.RENAMED));
                        }
                    }
                }
                case 'u' -> {
                    String[] f = rec.split(" ", 11);
                    if (f.length == 11) {
                        changes.add(new RepoStatus.FileChange(f[10], "", RepoStatus.Kind.CONFLICTED));
                    }
                }
                case '?' -> {
                    if (rec.length() > 2) {
                        changes.add(new RepoStatus.FileChange(rec.substring(2), "", RepoStatus.Kind.UNTRACKED));
                    }
                }
                case '!' -> {
                    if (rec.length() > 2) {
                        String p = rec.substring(2);
                        ignored.add(root.resolve(p.endsWith("/") ? p.substring(0, p.length() - 1) : p));
                    }
                }
                default -> {
                }
            }
        }
        changes.sort(Comparator.comparing(RepoStatus.FileChange::path));
        boolean truncated = cut || changes.size() > RepoStatus.MAX_CHANGES;
        List<RepoStatus.FileChange> kept = changes.size() > RepoStatus.MAX_CHANGES ? changes.subList(0, RepoStatus.MAX_CHANGES) : changes;
        return new Read(new RepoStatus(root.toString(), branch, kept, truncated, "", head), ignored);
    }

    /**
     * How an ordinary entry's {@code XY} (index, then working tree) differs from {@code HEAD}, both counted; empty for
     * a file added to the index and deleted from the tree, which {@code HEAD} never had. A file only meant to be added
     * ({@code git add -N}, {@code .A}) is added.
     */
    static Optional<RepoStatus.Kind> kind(String xy) {
        char x = xy.charAt(0);
        char y = xy.length() > 1 ? xy.charAt(1) : '.';
        if (x == 'A' || x == '.' && y == 'A') {
            return y == 'D' ? Optional.empty() : Optional.of(RepoStatus.Kind.ADDED);
        }
        if (x == 'D' || y == 'D') {
            return Optional.of(RepoStatus.Kind.DELETED);
        }
        return Optional.of(RepoStatus.Kind.MODIFIED);
    }

    /**
     * {@code change}'s version in {@code HEAD} and its version in the working tree. A file {@code HEAD} did not have,
     * or the tree no longer has, is empty on that side; a binary file, a version past {@link #MAX_FILE_BYTES} or one
     * that cannot be read gives a note instead.
     */
    ChangeTexts texts(Path root, RepoStatus.FileChange change) {
        String rootName = root.toString();
        byte[] before = new byte[0];
        if (change.kind() != RepoStatus.Kind.ADDED && change.kind() != RepoStatus.Kind.UNTRACKED) {
            Run r;
            try {
                r = run(root, List.of("cat-file", "-p", "HEAD:" + change.headPath()));
            } catch (IOException e) {
                return ChangeTexts.noted(rootName, change, "git cannot be run: " + e.getMessage());
            }
            if (r.cut() || r.out().length > MAX_FILE_BYTES) {
                return ChangeTexts.noted(rootName, change, "larger than " + MAX_FILE_BYTES / (1024 * 1024) + " MB: not compared");
            }
            if (r.exit() < 0) {
                return ChangeTexts.noted(rootName, change, "git took longer than " + TIMEOUT_MILLIS / 1000 + " s to read the HEAD version");
            }
            if (r.exit() != 0 && !notInHead(r.err())) {
                return ChangeTexts.noted(rootName, change, submodule(root, change.headPath()) ? "a submodule: not compared"
                        : "the HEAD version cannot be read: " + r.err().strip());
            }
            // a file HEAD does not hold, as in a repository without commits: nothing before
            before = r.exit() == 0 ? r.out() : new byte[0];
        }
        byte[] after = new byte[0];
        if (change.kind() != RepoStatus.Kind.DELETED) {
            Path file = root.resolve(change.path());
            try {
                if (Files.isSymbolicLink(file)) {
                    // git stores a link as the path it points to, and so it is compared
                    after = Files.readSymbolicLink(file).toString().getBytes(StandardCharsets.UTF_8);
                } else if (Files.isDirectory(file)) {
                    return ChangeTexts.noted(rootName, change, "a folder, such as a submodule: not compared");
                } else if (Files.size(file) > MAX_FILE_BYTES) {
                    return ChangeTexts.noted(rootName, change, "larger than " + MAX_FILE_BYTES / (1024 * 1024) + " MB: not compared");
                } else {
                    after = Files.readAllBytes(file);
                }
            } catch (IOException e) {
                return ChangeTexts.noted(rootName, change, "cannot be read: " + e.getMessage());
            }
        }
        if (binary(before) || binary(after)) {
            return ChangeTexts.noted(rootName, change, "a binary file: not compared");
        }
        String b = new String(before, StandardCharsets.UTF_8);
        String a = new String(after, StandardCharsets.UTF_8);
        if (b.startsWith(LFS_POINTER)) {
            return ChangeTexts.noted(rootName, change, "stored in Git LFS: not compared");
        }
        if (a.indexOf('\r') >= 0 && b.indexOf('\r') < 0 && convertsLineEnds(root)) {
            // what git compares: the tree's line ends as they would be committed
            a = a.replace("\r\n", "\n");
        }
        return new ChangeTexts(rootName, change, b, a, "");
    }

    private static final String LFS_POINTER = "version https://git-lfs.github.com/spec/";

    /** Whether a failed read of a {@code HEAD} path says only that {@code HEAD}, or the path in it, is not there. */
    static boolean notInHead(String err) {
        return err.contains("does not exist in 'HEAD'") || err.contains("invalid object name 'HEAD'")
                || err.contains("exists on disk, but not in 'HEAD'");
    }

    /** Whether {@code path} is a submodule in {@code HEAD}: its entry there has mode 160000, a commit of another repository. */
    private boolean submodule(Path root, String path) {
        try {
            Run r = run(root, List.of("ls-tree", "HEAD", "--", path));
            return r.exit() == 0 && new String(r.out(), StandardCharsets.UTF_8).startsWith("160000 ");
        } catch (IOException e) {
            return false;
        }
    }

    /** Whether the repository commits the tree's CRLF line ends as LF ({@code core.autocrlf} true or input). */
    private boolean convertsLineEnds(Path root) {
        try {
            Run r = run(root, List.of("config", "--get", "core.autocrlf"));
            String v = new String(r.out(), StandardCharsets.UTF_8).strip();
            return r.exit() == 0 && (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("input"));
        } catch (IOException e) {
            return false;
        }
    }

    static boolean binary(byte[] b) {
        for (int i = 0; i < Math.min(b.length, BINARY_PROBE); i++) {
            if (b[i] == 0) {
                return true;
            }
        }
        return false;
    }

    /** Runs git in {@code dir}; its output is read on a thread of its own, so a stuck process is killed in time. */
    Run run(Path dir, List<String> args) throws IOException {
        return run(dir, args, TIMEOUT_MILLIS);
    }

    /** As {@link #run(Path, List)}, killed after {@code timeoutMillis}: a run that talks to a remote takes longer. */
    Run run(Path dir, List<String> args, long timeoutMillis) throws IOException {
        if (closed) {
            throw new IOException("closed");
        }
        List<String> cmd = new ArrayList<>(args.size() + 4);
        cmd.add(git);
        cmd.add("--no-optional-locks");
        cmd.add("-C");
        cmd.add(dir.toString());
        cmd.addAll(args);
        Process[] started = new Process[1];
        try {
            Processes.Result r = Processes.run(cmd, env -> {
                env.put("GIT_OPTIONAL_LOCKS", "0");
                env.put("GIT_TERMINAL_PROMPT", "0");
            }, null, null, Duration.ofMillis(timeoutMillis), MAX_OUTPUT, 4_096, p -> {
                started[0] = p;
                live.add(p);
            });
            return new Run(r.exitCode(), r.stdout(), r.stderr(), r.cut());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Run(-1, new byte[0], "interrupted", false);
        } finally {
            if (started[0] != null) {
                live.remove(started[0]);
            }
        }
    }

    /** Kills the runs still going; none starts after. */
    @Override
    public void close() {
        closed = true;
        for (Process p : live) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
        }
    }
}
