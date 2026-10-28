package dev.marregui.comando.app;

import dev.marregui.comando.claude.KitJobsCli;
import dev.marregui.comando.core.model.KitJob;
import dev.marregui.comando.core.model.KitJobs;
import dev.marregui.comando.core.model.KitRoot;
import dev.marregui.comando.tools.KitGit;
import dev.marregui.comando.ui.shell.Setup;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** The window's {@link Setup}: the kit root comando runs on, read through git, its jobs through its {@code install/kit.py}. */
final class RootSetup implements Setup {

    private final KitRoot root;
    private final KitGit git;
    private final KitJobsCli jobs;

    RootSetup(KitRoot root, KitGit git) {
        this.root = root;
        this.git = git;
        this.jobs = new KitJobsCli(root.dir());
    }

    @Override
    public KitRoot root() {
        return root;
    }

    @Override
    public Status status() {
        if (root.kit().isEmpty()) {
            return Status.NONE;
        }
        Optional<Path> brain = root.brain();
        return new Status(git.version(root.dir()), brain.isPresent() && git.canBackUp(brain.get()));
    }

    @Override
    public String backUpBrain() throws IOException {
        Optional<Path> brain = root.brain();
        if (brain.isEmpty()) {
            throw new IOException("the root " + root.dir() + " has no kit, so no brain");
        }
        return git.backUp(brain.get(), LocalDate.now());
    }

    @Override
    public KitJobs jobs() {
        return root.kit().isEmpty() ? KitJobs.NONE : jobs.read();
    }

    @Override
    public JobsDone saveJobs(List<KitJob> list) throws IOException {
        return done(jobs.save(list));
    }

    @Override
    public JobsDone runJob(String name) throws IOException {
        return done(jobs.run(name));
    }

    /** Exit 0 done, 1 done in part; anything else refused, in {@code kit.py}'s words. */
    private static JobsDone done(KitJobsCli.Result r) throws IOException {
        if (r.exit() > 1 || r.exit() < 0) {
            throw new IOException(r.text().isBlank() ? "kit.py exited " + r.exit() : r.text());
        }
        return new JobsDone(r.exit() == 0, r.text());
    }
}
