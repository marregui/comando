package dev.marregui.comando.tools;

import dev.marregui.comando.core.model.KitVersion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The kit's root and its brain as git checkouts: the kit's version and the newer releases its remote has, and the
 * backup of either, a commit of everything in it, saying what it adds, pushed to its remote. Each run is bounded, never asks for credentials,
 * and ends when this closes. Every method blocks: call it off the EDT and the reducer.
 */
public final class KitGit implements AutoCloseable {

    /** {@code ls-remote} and {@code push} talk to a remote: they may take this long. */
    static final long REMOTE_MILLIS = 120_000;
    /** A commit of the whole brain may take this long. */
    static final long COMMIT_MILLIS = 60_000;

    private final Git git;

    public KitGit() {
        this(new Git());
    }

    KitGit(Git git) {
        this.git = git;
    }

    /**
     * The kit's version, {@code git describe --tags --always} in {@code root}, and the releases newer than it on {@code
     * origin}; unknown when the root is no checkout. A remote that does not answer leaves the newer releases unknown, and
     * says why.
     */
    public KitVersion version(Path root) {
        Git.Run describe;
        try {
            describe = git.run(root, List.of("describe", "--tags", "--always"));
        } catch (IOException e) {
            return KitVersion.UNKNOWN;
        }
        if (describe.exit() != 0) {
            return KitVersion.UNKNOWN;
        }
        String described = text(describe);
        try {
            Git.Run tags = git.run(root, List.of("ls-remote", "--tags", "origin"), REMOTE_MILLIS);
            if (tags.exit() != 0) {
                return KitVersion.of(described, "").failedRemote(why("git ls-remote", tags, REMOTE_MILLIS));
            }
            return KitVersion.of(described, text(tags));
        } catch (IOException e) {
            return KitVersion.of(described, "").failedRemote(e.getMessage());
        }
    }

    /** The most files a backup's message lists, one a line; the rest are counted. */
    static final int LISTED = 200;
    /** The longest a backup's first line grows before its last areas are counted instead. */
    static final int SUBJECT = 120;

    /** Whether {@code repo} is the top folder of a git repository that has a remote, so it can be backed up. */
    public boolean canBackUp(Path repo) {
        try {
            Git.Run top = git.run(repo, List.of("rev-parse", "--show-toplevel"));
            if (top.exit() != 0 || !Path.of(text(top)).toRealPath().equals(repo.toRealPath())) {
                return false;
            }
            Git.Run remotes = git.run(repo, List.of("remote"));
            return remotes.exit() == 0 && !text(remotes).isEmpty();
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Backs the brain up: {@link #backUp(Path, String, LocalDate)} as {@code Brain}. */
    public String backUp(Path brain, LocalDate today) throws IOException {
        return backUp(brain, "Brain", today);
    }

    /**
     * Backs {@code repo} up: {@code git add -A}, a commit when anything changed whose message says what it adds (a first
     * line {@code <what> backup <date>: } with the counts of each top folder, then one line a file), and {@code git
     * push}. Answers what was done, for the notice; throws, saying which step failed and git's message, when one fails.
     */
    public String backUp(Path repo, String what, LocalDate today) throws IOException {
        if (!canBackUp(repo)) {
            throw new IOException(repo + " is not a git repository with a remote");
        }
        step(repo, List.of("add", "-A"), COMMIT_MILLIS, "git add");
        Git.Run staged = git.run(repo, List.of("diff", "--cached", "--quiet"), COMMIT_MILLIS);
        boolean changed = staged.exit() == 1;
        if (staged.exit() != 0 && !changed) {
            throw new IOException(why("git diff --cached", staged, COMMIT_MILLIS));
        }
        String subject = "";
        if (changed) {
            Git.Run names = step(repo, List.of("-c", "core.quotePath=false", "diff", "--cached", "--name-status", "-M"), COMMIT_MILLIS,
                    "git diff --cached --name-status");
            List<Change> list = changes(text(names));
            subject = subject(what, today, list);
            step(repo, List.of("commit", "-m", subject, "-m", body(list)), COMMIT_MILLIS, "git commit");
        }
        String head = text(step(repo, List.of("rev-parse", "--short", "HEAD"), Git.TIMEOUT_MILLIS, "git rev-parse"));
        step(repo, List.of("push"), REMOTE_MILLIS, "git push");
        return changed ? "committed " + head + " \"" + subject + "\" and pushed it" : "nothing to commit; pushed " + head;
    }

    /** One file a backup commits: added, changed, removed or renamed (from {@code from}). */
    record Change(String verb, String path, String from) {
    }

    /** {@code git diff --name-status -M}'s lines as changes; a line git adds that is none of these is skipped. */
    static List<Change> changes(String nameStatus) {
        List<Change> out = new ArrayList<>();
        for (String line : nameStatus.lines().toList()) {
            String[] f = line.split("\t");
            if (f.length < 2 || f[0].isEmpty()) {
                continue;
            }
            switch (f[0].charAt(0)) {
                case 'A' -> out.add(new Change("added", f[1], ""));
                case 'D' -> out.add(new Change("removed", f[1], ""));
                case 'R' -> out.add(f.length > 2 ? new Change("renamed", f[2], f[1]) : new Change("changed", f[1], ""));
                case 'C' -> out.add(new Change("added", f.length > 2 ? f[2] : f[1], ""));
                default -> out.add(new Change("changed", f[1], ""));
            }
        }
        return out;
    }

    /**
     * The first line: {@code Brain backup 2026-10-28: MEMORY 2 changed; SCRATCHPAD 3 added, 1 changed}, each top folder (a
     * file at the top is its own) with its counts in the order added, changed, renamed, removed; the areas past {@link
     * #SUBJECT} characters are counted at its end.
     */
    static String subject(String what, LocalDate today, List<Change> list) {
        Map<String, Map<String, Integer>> areas = new LinkedHashMap<>();
        for (Change c : list) {
            String area = c.path().contains("/") ? c.path().substring(0, c.path().indexOf('/')) : c.path();
            areas.computeIfAbsent(area, a -> new LinkedHashMap<>()).merge(c.verb(), 1, Integer::sum);
        }
        StringBuilder out = new StringBuilder(what + " backup " + today + ":");
        int shown = 0;
        for (Map.Entry<String, Map<String, Integer>> a : areas.entrySet()) {
            List<String> counts = new ArrayList<>();
            for (String verb : List.of("added", "changed", "renamed", "removed")) {
                Integer n = a.getValue().get(verb);
                if (n != null) {
                    counts.add(n + " " + verb);
                }
            }
            String part = (shown == 0 ? " " : "; ") + a.getKey() + " " + String.join(", ", counts);
            int left = areas.size() - shown;
            if (shown > 0 && out.length() + part.length() > SUBJECT) {
                out.append("; ").append(left).append(left == 1 ? " more area" : " more areas");
                break;
            }
            out.append(part);
            shown++;
        }
        return out.toString();
    }

    /** One line a file, {@code added MEMORY/x.md} or {@code renamed a.md -> b.md}, the first {@link #LISTED}, then how many more. */
    static String body(List<Change> list) {
        List<String> lines = new ArrayList<>();
        for (Change c : list.subList(0, Math.min(LISTED, list.size()))) {
            lines.add(c.verb() + " " + (c.from().isEmpty() ? c.path() : c.from() + " -> " + c.path()));
        }
        if (list.size() > LISTED) {
            lines.add("and " + (list.size() - LISTED) + " more");
        }
        return String.join("\n", lines);
    }

    private Git.Run step(Path dir, List<String> args, long millis, String what) throws IOException {
        Git.Run r = git.run(dir, args, millis);
        if (r.exit() != 0) {
            throw new IOException(why(what, r, millis));
        }
        return r;
    }

    private static String why(String what, Git.Run r, long millis) {
        if (r.exit() < 0) {
            return what + " took longer than " + millis / 1000 + " s";
        }
        String err = r.err().strip();
        if (err.isEmpty()) {
            return what + " failed with exit code " + r.exit();
        }
        // git's own verdict line, else its first: a push begins with the remote's address
        String line = err.lines().filter(l -> l.startsWith("fatal:") || l.startsWith("error:")).findFirst()
                .orElse(err.lines().findFirst().orElse(err));
        return what + " failed: " + line.strip();
    }

    private static String text(Git.Run r) {
        return new String(r.out(), StandardCharsets.UTF_8).strip();
    }

    @Override
    public void close() {
        git.close();
    }
}
