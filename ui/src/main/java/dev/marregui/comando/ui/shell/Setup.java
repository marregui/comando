package dev.marregui.comando.ui.shell;

import dev.marregui.comando.core.model.KitRoot;
import dev.marregui.comando.core.model.KitVersion;

import java.io.IOException;

/**
 * The kit comando runs its sessions on, as the window reaches it: the root and its {@code comando-kit.json}, the kit's version
 * and newer releases, and the brain's backup. {@link #status} and {@link #backUpBrain} block (git, a remote): call them
 * off the EDT.
 */
public interface Setup {

    /** The root comando starts its sessions on. */
    KitRoot root();

    /** The kit's version, its newer releases, and whether the brain can be backed up; nothing without a kit. */
    Status status();

    /** Commits everything in the brain and pushes it; what was done, for the notice. */
    String backUpBrain() throws IOException;

    /**
     * The kit as git sees it now.
     *
     * @param version   the kit's version and the newer releases of its remote
     * @param canBackUp whether the brain is a git repository of its own with a remote
     */
    record Status(KitVersion version, boolean canBackUp) {
        public static final Status NONE = new Status(KitVersion.UNKNOWN, false);
    }

    /** For a window without a kit (the replay tests): the root comando would use, no version, no backup. */
    Setup NONE = new Setup() {
        @Override
        public KitRoot root() {
            return KitRoot.current();
        }

        @Override
        public Status status() {
            return Status.NONE;
        }

        @Override
        public String backUpBrain() throws IOException {
            throw new IOException("no kit in this window");
        }
    };
}
