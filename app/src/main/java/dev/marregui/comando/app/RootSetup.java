package dev.marregui.comando.app;

import dev.marregui.comando.core.model.KitRoot;
import dev.marregui.comando.tools.KitGit;
import dev.marregui.comando.ui.shell.Setup;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;

/** The window's {@link Setup}: the kit root comando runs on, read through git. */
final class RootSetup implements Setup {

    private final KitRoot root;
    private final KitGit git;

    RootSetup(KitRoot root, KitGit git) {
        this.root = root;
        this.git = git;
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
}
