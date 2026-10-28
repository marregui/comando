package dev.marregui.comando.ui.shell;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.KitJob;
import dev.marregui.comando.core.model.KitJobs;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.ui.dialogs.jobs.JobsDialog;
import dev.marregui.comando.ui.dialogs.jobs.JobsEditor;
import dev.marregui.comando.ui.kit.async.Edt;
import dev.marregui.comando.ui.kit.async.ShownTimer;
import dev.marregui.comando.ui.kit.async.UiErrands;
import dev.marregui.comando.ui.kit.desktop.FileChoosers;
import dev.marregui.comando.ui.panels.SessionsSidebar;

import javax.swing.JFrame;
import java.awt.Component;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The kit's scheduled jobs in the window (record 117): read through {@link Setup#jobs} at start, after each change and
 * every minute while the Files tab shows, shown on its Jobs row; edited in the {@link JobsDialog}, whose Save hands the
 * whole list to {@code kit.py}; run now, and brought to the scheduler, from the row's and the window's menus. Every call
 * of {@code kit.py} runs off the EDT. EDT only, but for the reads' answers.
 */
final class JobActions {

    /** How often the jobs are read again while the Files tab shows: a run starts or ends, the next one moves on. */
    static final int REREAD_MILLIS = 60_000;

    private final ComandoUi.Dependencies deps;
    private final Supplier<JFrame> frame;
    private final Consumer<Path> openFile;
    private SessionsSidebar sidebar;
    private ShownTimer timer;
    private KitJobs jobs = KitJobs.NONE;

    JobActions(ComandoUi.Dependencies deps, Supplier<JFrame> frame, Consumer<Path> openFile) {
        this.deps = deps;
        this.frame = frame;
        this.openFile = openFile;
    }

    /** The sidebar whose Files tab shows the jobs, once the window is built; reads them once, then every minute it shows. */
    void attach(SessionsSidebar bar) {
        sidebar = bar;
        bar.onJobs(this::configure, this::apply);
        if (deps.setup().root().kit().isEmpty()) {
            return;
        }
        timer = new ShownTimer(bar.filesComponent(), REREAD_MILLIS, this::refresh);
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
        UiErrands.start("kit-jobs", () -> {
            KitJobs now = deps.setup().jobs();
            Edt.later(() -> show(now));
        });
    }

    private void show(KitJobs now) {
        jobs = now;
        if (sidebar != null) {
            sidebar.showJobs(now);
        }
    }

    /**
     * The window that edits the jobs, over comando's, on the jobs read now: a list read earlier may miss a later edit of
     * the manifest, which Save would then undo. A list that cannot be read opens no window, as Save would hand {@code
     * kit.py} the empty list and remove every job; the notice says why.
     */
    void configure() {
        if (!available()) {
            return;
        }
        UiErrands.start("kit-jobs", () -> {
            KitJobs now = deps.setup().jobs();
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

    private void open(KitJobs now) {
        Path root = deps.setup().root().dir();
        JobsDialog.show(frame.get(), now, root, new JobsEditor.Actions() {
            @Override
            public Optional<Path> program(Component owner, Path bin) {
                return FileChoosers.open(owner, "Choose the job's program", bin);
            }

            @Override
            public void run(String name) {
                JobActions.this.run(name);
            }

            @Override
            public void openLog(Path log) {
                openFile.accept(log);
            }
        }, this::save, null);
    }

    /** Hands the whole list to {@code kit.py} off the EDT; on the EDT, {@code done} or {@code refused} with its words. */
    private void save(List<KitJob> list, Runnable done, Consumer<String> refused) {
        UiErrands.start("kit-jobs-save", () -> {
            try {
                Setup.JobsDone r = deps.setup().saveJobs(list);
                Edt.later(() -> {
                    told(r, "The kit jobs are saved");
                    done.run();
                    refresh();
                });
            } catch (IOException e) {
                String why = e.getMessage() == null ? e.toString() : e.getMessage();
                Edt.later(() -> {
                    refused.accept(why.strip().lines().reduce((a, b) -> b).orElse(why));
                    refresh();
                });
            }
        });
    }

    /**
     * Brings the scheduler to the manifest: the jobs as the manifest holds them now, read again and handed to {@code
     * kit.py}; nothing when they cannot be read.
     */
    void apply() {
        UiErrands.start("kit-jobs-apply", () -> {
            try {
                KitJobs now = deps.setup().jobs();
                if (!now.read() || !now.error().isEmpty()) {
                    throw new IOException(now.error().isEmpty() ? "the jobs cannot be read" : now.error());
                }
                Setup.JobsDone r = deps.setup().saveJobs(now.definitions());
                Edt.later(() -> told(r, "The scheduler follows the kit jobs again"));
            } catch (IOException e) {
                notify(Notice.Level.ERROR, "The kit jobs were not applied: " + e.getMessage());
            }
            Edt.later(this::refresh);
        });
    }

    /** Starts job {@code name} now through the scheduler. */
    void run(String name) {
        UiErrands.start("kit-jobs-run", () -> {
            try {
                Setup.JobsDone r = deps.setup().runJob(name);
                Edt.later(() -> told(r, "The job " + name + " started; its output goes to its log"));
            } catch (IOException e) {
                notify(Notice.Level.ERROR, "The job " + name + " did not start: " + e.getMessage());
            }
            Edt.later(this::refresh);
        });
    }

    /** A notice of what {@code kit.py} did: {@code ok} when all of it, else its own words on what it left. */
    private void told(Setup.JobsDone r, String ok) {
        if (r.complete()) {
            notify(Notice.Level.INFO, ok);
        } else {
            String left = r.text().lines().filter(l -> l.contains("running") || l.contains("failed") || l.contains("not ")).findFirst()
                    .orElse(r.text().strip());
            notify(Notice.Level.WARN, "Kit jobs: " + left);
        }
    }

    private void notify(Notice.Level level, String text) {
        deps.intents().dispatch(new Intent.Notify(level, text, false, ""));
    }
}
