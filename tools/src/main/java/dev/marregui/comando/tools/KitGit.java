package dev.marregui.comando.tools;

import dev.marregui.comando.core.model.KitVersion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/**
 * The kit's root and its brain as git checkouts: the kit's version and the newer releases its remote has, and the
 * brain's backup, a commit of everything in it pushed to its remote. Each run is bounded, never asks for credentials,
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

    /** Whether {@code brain} is the top folder of a git repository that has a remote, so it can be backed up. */
    public boolean canBackUp(Path brain) {
        try {
            Git.Run top = git.run(brain, List.of("rev-parse", "--show-toplevel"));
            if (top.exit() != 0 || !Path.of(text(top)).toRealPath().equals(brain.toRealPath())) {
                return false;
            }
            Git.Run remotes = git.run(brain, List.of("remote"));
            return remotes.exit() == 0 && !text(remotes).isEmpty();
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Backs the brain up: {@code git add -A}, a commit {@code Brain backup <date>} when anything changed, and {@code git
     * push}. Answers what was done, for the notice; throws, saying which step failed and git's message, when one fails.
     */
    public String backUp(Path brain, LocalDate today) throws IOException {
        if (!canBackUp(brain)) {
            throw new IOException(brain + " is not a git repository with a remote");
        }
        step(brain, List.of("add", "-A"), COMMIT_MILLIS, "git add");
        Git.Run staged = git.run(brain, List.of("diff", "--cached", "--quiet"), COMMIT_MILLIS);
        boolean changed = staged.exit() == 1;
        if (staged.exit() != 0 && !changed) {
            throw new IOException(why("git diff --cached", staged, COMMIT_MILLIS));
        }
        if (changed) {
            step(brain, List.of("commit", "-m", message(today)), COMMIT_MILLIS, "git commit");
        }
        String head = text(step(brain, List.of("rev-parse", "--short", "HEAD"), Git.TIMEOUT_MILLIS, "git rev-parse"));
        step(brain, List.of("push"), REMOTE_MILLIS, "git push");
        return changed ? "committed " + head + " \"" + message(today) + "\" and pushed it"
                : "nothing to commit; pushed " + head;
    }

    /** The backup's commit message. */
    static String message(LocalDate today) {
        return "Brain backup " + today;
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
