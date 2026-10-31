package dev.marregui.comando.ui.shell;

import java.io.IOException;
import java.util.List;

import dev.marregui.comando.core.model.KitJob;
import dev.marregui.comando.core.model.KitJobs;
import dev.marregui.comando.core.model.KitRoot;
import dev.marregui.comando.core.model.KitVersion;

/**
 * The kit comando runs its sessions on, as the window reaches it: the root and its {@code comando-kit.json}, the kit's
 * version and newer releases, the brain's and the kit's backups, and the kit's scheduled jobs through its
 * {@code install/kit.py} (record 117). {@link #status}, {@link #backUpBrain}, {@link #backUpKit} and the jobs' calls
 * block (git, a remote, Python): call them off the EDT. Implemented in {@code app}.
 */
public interface Setup {

    /** The root comando starts its sessions on. */
    KitRoot root();

    /**
     * The kit's version, its newer releases, and whether the brain and the kit can be backed up; none without a kit.
     */
    Status status();

    /** Commits everything in the brain and pushes it; what was done, for the notice. */
    String backUpBrain() throws IOException;

    /** Commits everything in the kit's root and pushes it; what was done, for the notice. */
    default String backUpKit() throws IOException {
        throw new IOException("no kit in this window");
    }

    /** The kit's scheduled jobs as {@code kit.py} reports them; {@link KitJobs#NONE} without a kit. */
    default KitJobs jobs() {
        return KitJobs.NONE;
    }

    /**
     * Writes {@code jobs}, the whole list, to the manifest and brings the scheduler to it; throws with {@code kit.py}'s
     * words when it refused the list, which then changed nothing.
     */
    default JobsDone saveJobs(final List<KitJob> jobs) throws IOException {
        throw new IOException("no kit in this window");
    }

    /** Starts job {@code name} now through the scheduler; throws with the reason when it cannot. */
    default JobsDone runJob(final String name) throws IOException {
        throw new IOException("no kit in this window");
    }

    /**
     * What a change to the jobs did.
     *
     * @param complete false when {@code kit.py} left part of it undone, such as a running job not reloaded
     * @param text     what {@code kit.py} said
     */
    record JobsDone(boolean complete, String text) {
    }

    /**
     * The kit as git sees it now.
     *
     * @param version      the kit's version and the newer releases of its remote
     * @param canBackUp    whether the brain is a git repository of its own with a remote
     * @param canBackUpKit whether the kit's root is a git repository with a remote
     */
    record Status(KitVersion version, boolean canBackUp, boolean canBackUpKit) {
        public static final Status NONE = new Status(KitVersion.UNKNOWN, false, false);

        /** A status without the kit's backup. */
        public Status(final KitVersion version, final boolean canBackUp) {
            this(version, canBackUp, false);
        }
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
