package dev.marregui.comando.ui.shell;

import java.awt.Component;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.swing.JFrame;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.KitJob;
import dev.marregui.comando.core.model.KitJobs;
import dev.marregui.comando.core.model.KitRoot;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.ui.dialogs.jobs.JobsDialog;
import dev.marregui.comando.ui.dialogs.jobs.JobsEditor;
import dev.marregui.comando.ui.kit.async.Edt;
import dev.marregui.comando.ui.kit.async.ShownTimer;
import dev.marregui.comando.ui.kit.async.UiErrands;
import dev.marregui.comando.ui.kit.desktop.FileChoosers;
import dev.marregui.comando.ui.panels.SessionsSidebar;

/**
 * The kit's scheduled jobs in the window (record 117): read through {@link Setup#jobs} at start, after each change
 * comando makes, and while the Files tab shows whenever the manifest or the runner's state changed, shown on its Jobs
 * row; edited in the {@link JobsDialog}, whose Save hands the whole list to {@code kit.py}; run now, and brought to the
 * scheduler, from the row's and the window's menus. Every call of {@code kit.py} runs on an errand of the window. EDT
 * only, but for those errands.
 */
final class JobActions {

    /**
     * How often, while the Files tab shows, the manifest and the runner's state are looked at: a run starts or ends, the
     * next one moves on. Only a change is read through {@code kit.py}: a read is a {@code python3} and the scheduler's
     * own calls, which once a minute made 60 processes an hour on an idle window (soak run 8, 2026-11-03).
     */
    static final int LOOK_MILLIS = 10_000;
    /** The runner's state folder in the kit, {@code kit.py}'s {@code JOB_STATE}: a job's pid while it runs, its runs. */
    static final String STATE = ".kit-jobs";

    private final ComandoUi.Dependencies deps;
    private final Supplier<JFrame> frame;
    private final Consumer<Path> openFile;
    private SessionsSidebar sidebar;
    private ShownTimer timer;
    private KitJobs jobs = KitJobs.NONE;
    /** The state the last read started from, {@link #stateOf}; written and read on errands. */
    private volatile String seen = "";

    JobActions(final ComandoUi.Dependencies deps, final Supplier<JFrame> frame, final Consumer<Path> openFile) {
        this.deps = deps;
        this.frame = frame;
        this.openFile = openFile;
    }

    /** Shows the jobs in {@code bar}'s Files tab, once the window is built: read now, then again on a change it shows. */
    void attach(final SessionsSidebar bar) {
        sidebar = bar;
        bar.onJobs(this::configure, this::apply);
        if (deps.setup().root().kit().isEmpty()) {
            return;
        }
        timer = new ShownTimer(bar.filesComponent(), LOOK_MILLIS, this::look);
        timer.want(true);
        refresh();
    }

    /** Stops reading them again. */
    void stop() {
        if (timer != null) {
            timer.stop();
        }
    }

    KitJobs jobs() {
        return jobs;
    }

    /** Whether the root has a kit whose jobs can be configured. */
    boolean available() {
        return deps.setup().root().kit().isPresent();
    }

    /** Reads the jobs again off the EDT; the row follows. */
    void refresh() {
        UiErrands.start("kit-jobs", this::read);
    }

    /** Off the EDT, reads the jobs again when the manifest or the runner's state changed since the last read. */
    void look() {
        UiErrands.start("kit-jobs-look", this::readIfChanged);
    }

    /** {@link #look}'s errand; for tests. */
    void readIfChanged() {
        if (!stateOf(deps.setup().root().dir()).equals(seen)) {
            read();
        }
    }

    private void read() {
        // the state is taken before the read: a change while kit.py runs is read on the next look
        seen = stateOf(deps.setup().root().dir());
        final KitJobs now = deps.setup().jobs();
        Edt.later(() -> show(now));
    }

    /**
     * What the jobs' state is read from, as one string that changes when it does: the manifest's and each runner file's
     * time and size, the logs left out (a running job writes its log all the time; its pid and its runs tell).
     */
    static String stateOf(final Path root) {
        final StringBuilder out = new StringBuilder();
        stamp(out, root.resolve(KitRoot.FILE));
        final Path dir = root.resolve(STATE);
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(f -> !f.getFileName().toString().endsWith(".log")).sorted().forEach(f -> stamp(out, f));
            } catch (final IOException e) {
                out.append("unlisted ").append(e.getMessage());
            }
        }
        return out.toString();
    }

    private static void stamp(final StringBuilder out, final Path file) {
        out.append(file.getFileName()).append(' ');
        try {
            final BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class);
            out.append(a.lastModifiedTime().toMillis()).append(' ').append(a.size());
        } catch (final IOException e) {
            out.append('-');
        }
        out.append('\n');
    }

    private void show(final KitJobs now) {
        jobs = now;
        if (sidebar != null) {
            sidebar.showJobs(now);
        }
    }

    /**
     * The window that edits the jobs, over comando's, on the jobs read now: a list read earlier may miss a later edit
     * of the manifest, which Save would then undo. A list that cannot be read opens no window, as Save would hand
     * {@code kit.py} the empty list and remove every job; the notice says why.
     */
    void configure() {
        if (!available()) {
            return;
        }
        UiErrands.start("kit-jobs", () -> {
            final KitJobs now = deps.setup().jobs();
            Edt.later(() -> {
                show(now);
                if (!now.read() || !now.error().isEmpty()) {
                    notify(Notice.Level.ERROR, "Kit jobs cannot be read" + (now.error().isEmpty() ? "" : ": " + now.error())
                            + "; fix comando-kit.json, then open them again");
                    return;
                }
                open(now);
            });
        });
    }

    private void open(final KitJobs now) {
        final Path root = deps.setup().root().dir();
        JobsDialog.show(frame.get(), now, root, new JobsEditor.Actions() {
            @Override
            public Optional<Path> program(final Component owner, final Path bin) {
                return FileChoosers.open(owner, "Choose the job's program", bin);
            }

            @Override
            public void run(final String name) {
                JobActions.this.run(name);
            }

            @Override
            public void openLog(final Path log) {
                openFile.accept(log);
            }
        }, this::save, null);
    }

    /**
     * Hands the whole list to {@code kit.py} off the EDT, then runs {@code done}, or {@code refused} with why, on it.
     */
    private void save(final List<KitJob> list, final Runnable done, final Consumer<String> refused) {
        UiErrands.start("kit-jobs-save", () -> {
            try {
                final Setup.JobsDone r = deps.setup().saveJobs(list);
                Edt.later(() -> {
                    told(r, "The kit jobs are saved");
                    done.run();
                    refresh();
                });
            } catch (final IOException e) {
                final String why = e.getMessage() == null ? e.toString() : e.getMessage();
                Edt.later(() -> {
                    refused.accept(why.strip().lines().reduce((a, b) -> b).orElse(why));
                    refresh();
                });
            }
        });
    }

    /**
     * Brings the scheduler to the manifest: the jobs as the manifest holds them now, read again and handed to
     * {@code kit.py}; nothing when they cannot be read.
     */
    void apply() {
        UiErrands.start("kit-jobs-apply", () -> {
            try {
                final KitJobs now = deps.setup().jobs();
                if (!now.read() || !now.error().isEmpty()) {
                    throw new IOException(now.error().isEmpty() ? "the jobs cannot be read" : now.error());
                }
                final Setup.JobsDone r = deps.setup().saveJobs(now.definitions());
                Edt.later(() -> told(r, "The scheduler follows the kit jobs again"));
            } catch (final IOException e) {
                notify(Notice.Level.ERROR, "The kit jobs were not applied: " + e.getMessage());
            }
            Edt.later(this::refresh);
        });
    }

    /** Starts job {@code name} now through the scheduler. */
    void run(final String name) {
        UiErrands.start("kit-jobs-run", () -> {
            try {
                final Setup.JobsDone r = deps.setup().runJob(name);
                Edt.later(() -> told(r, "The job " + name + " started; its output goes to its log"));
            } catch (final IOException e) {
                notify(Notice.Level.ERROR, "The job " + name + " did not start: " + e.getMessage());
            }
            Edt.later(this::refresh);
        });
    }

    /** A notice of what {@code kit.py} did: {@code ok} when all of it, else its own words on what it left. */
    private void told(final Setup.JobsDone r, final String ok) {
        if (r.complete()) {
            notify(Notice.Level.INFO, ok);
        } else {
            final String left = r.text().lines().filter(l -> l.contains("running") || l.contains("failed") || l.contains("not ")).findFirst()
                    .orElse(r.text().strip());
            notify(Notice.Level.WARN, "Kit jobs: " + left);
        }
    }

    private void notify(final Notice.Level level, final String text) {
        deps.intents().dispatch(new Intent.Notify(level, text, false, ""));
    }
}
