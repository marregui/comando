package dev.marregui.comando.ui.dialogs.jobs;

import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.text.JTextComponent;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.model.KitJob;
import dev.marregui.comando.core.model.KitJobs;
import dev.marregui.comando.ui.kit.async.OnEdt;

import static org.assertj.core.api.Assertions.assertThat;

/// [JobsEditor] edits the kit's scheduled jobs (record 117), checking each as `kit.py` would before it can be saved.
/// - An unchanged list is valid and clean; a change makes it dirty, and undoing it clean again.
/// - The days, the time, the program and its arguments, the environment and the pause become the job handed to
///   `kit.py`.
/// - A broken rule blocks the save and says which field and why; what `kit.py` refused shows until the next edit.
/// - Adding gives a free name and asks for a program; removing takes the job out.
/// - The state line says how the scheduler holds the job; a new one is not the scheduler's yet.
/// - A job's menu runs it now or opens its log, only for a job the kit already has.
/// - The program and the log open from their rows when their files exist; an empty log shows kit.py's default.
/// - A service's Runs row says it is always running; each row's mark says how the job stands.
/// - Every field has a hint under it and an ⓘ on its label.
@ExtendWith(OnEdt.class)
class JobsEditorTest {

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    private final List<String> did = new ArrayList<>();

    private static KitJobs.Entry entry(final KitJob j, final boolean running) {
        return new KitJobs.Entry(j, true, true, true, running, "2026-10-26T07:30+01:00", 0, j.enabled() ? "2026-11-02T07:30+01:00" : "",
                "/k/.kit-jobs/" + j.name() + ".log");
    }

    private static final KitJob DRIFT = new KitJob("kb-drift", "KB drift report", List.of("MON"), "07:30",
            List.of("bin/kb-drift", "--days", "7"), Map.of("KB_DRIFT_TRIGGER", "{scheduler}"), "", true);
    private static final KitJob BRIEF = new KitJob("daily-brief", "", List.of("MON", "TUE", "WED", "THU", "FRI"), "10:15",
            List.of("bin/daily-brief"), Map.of(), "", false);

    private JobsEditor editor() {
        return editor(List.of(entry(DRIFT, false), entry(BRIEF, false)));
    }

    private JobsEditor editor(final List<KitJobs.Entry> entries) {
        return new JobsEditor(entries, Path.of("/k"), new JobsEditor.Actions() {
            @Override
            public Optional<Path> program(final Component owner, final Path bin) {
                return Optional.of(bin.resolve("curator"));
            }

            @Override
            public void run(final String name) {
                did.add("run " + name);
            }

            @Override
            public void open(final Path file) {
                did.add("open " + file);
            }
        });
    }

    private static void type(final JobsEditor e, final String key, final String text) {
        ((JTextComponent) e.field(key)).setText(text);
    }

    /// The list opens clean and valid, and hands back the jobs it was given.
    @Test
    void anUnchangedListIsCleanAndSavesAsItWas() {
        final JobsEditor e = editor();
        assertThat(e.dirty()).isFalse();
        assertThat(e.valid()).isTrue();
        assertThat(e.jobs()).contains(List.of(DRIFT, BRIEF));
        assertThat(((JTextComponent) e.field("arguments")).getText()).isEqualTo("--days 7");
        assertThat(((JTextComponent) e.field(KitJob.ENV_FIELD)).getText()).isEqualTo("KB_DRIFT_TRIGGER={scheduler}");
        e.day(1).doClick();
        assertThat(e.dirty()).as("a day more is a change").isTrue();
        e.day(1).doClick();
        type(e, KitJob.TIME_FIELD, "07:31");
        assertThat(e.dirty()).as("a minute later is a change").isTrue();
        type(e, KitJob.TIME_FIELD, "07:30");
        assertThat(e.dirty()).isFalse();
    }

    /// A service opened in the list is handed back as a service: the editor has no schedule to give it.
    @Test
    void aServiceStaysAService() {
        final KitJob service = new KitJob("cdb-serve", "", List.of(), "", List.of("tools/cdb-serve/cdb-serve", "run"),
                Map.of(), "", true, true);
        final JobsEditor e = new JobsEditor(List.of(entry(service, true)), Path.of("/k"), new JobsEditor.Actions() {
            @Override
            public Optional<Path> program(final Component owner, final Path bin) {
                return Optional.empty();
            }

            @Override
            public void run(final String name) {
                did.add("run " + name);
            }

            @Override
            public void open(final Path file) {
                did.add("open " + file);
            }
        });
        assertThat(e.valid()).isTrue();
        assertThat(e.jobs()).contains(List.of(service));
    }

    /// A job kept awake stays awake through an edit: the editor has no field for it, and a save must not drop it.
    @Test
    void anAwakeJobStaysAwakeThroughAnEdit() {
        final KitJob dream = new KitJob("dream", "", KitJob.WEEKDAYS, "02:00", List.of("tools/dream/dream"), Map.of(),
                "", true, false, true);
        final JobsEditor e = editor(List.of(entry(dream, false)));
        type(e, KitJob.TIME_FIELD, "03:00");
        final KitJob edited = e.jobs().orElseThrow().getFirst();
        assertThat(edited.paddedTime()).isEqualTo("03:00");
        assertThat(edited.awake()).isTrue();
    }

    /// The day toggles, the time, the arguments, the environment and Enabled become the job; setting them back makes
    /// the list clean again, and an unpadded time is the same time.
    @Test
    void theFieldsBecomeTheJobHandedToKitPy() {
        final JobsEditor e = editor();
        e.day(3).doClick();
        type(e, KitJob.TIME_FIELD, "8:05");
        type(e, "arguments", "--days 8 'a b'");
        type(e, KitJob.ENV_FIELD, "KB_DRIFT_TRIGGER={scheduler}\nX=1");
        ((JCheckBox) e.field("enabled")).doClick();
        assertThat(e.dirty()).isTrue();
        final KitJob edited = e.jobs().orElseThrow().getFirst();
        assertThat(edited.when()).isEqualTo("MON,THU 08:05");
        assertThat(edited.command()).containsExactly("bin/kb-drift", "--days", "8", "a b");
        assertThat(edited.env()).isEqualTo(Map.of("KB_DRIFT_TRIGGER", "{scheduler}", "X", "1"));
        assertThat(edited.enabled()).isFalse();

        e.day(3).doClick();
        type(e, KitJob.TIME_FIELD, "7:30");
        type(e, "arguments", "--days 7");
        type(e, KitJob.ENV_FIELD, "KB_DRIFT_TRIGGER={scheduler}");
        ((JCheckBox) e.field("enabled")).doClick();
        assertThat(e.dirty()).as("back as it was; 7:30 is 07:30").isFalse();

        for (int d = 0; d < 7; d++) {
            if (!e.day(d).isSelected()) {
                e.day(d).doClick();
            }
        }
        assertThat(e.jobs().orElseThrow().getFirst().when()).isEqualTo("daily 07:30");
    }

    /// A taken name, no day, a bad time or no program block the save and say which field; what `kit.py` refused shows
    /// in the message line until the next edit.
    @Test
    void aBrokenRuleBlocksTheSaveAndSaysWhy() {
        final JobsEditor e = editor();
        type(e, KitJob.NAME_FIELD, "daily-brief");
        assertThat(e.valid()).isFalse();
        assertThat(e.jobs()).isEmpty();
        assertThat(e.messageShown()).isEqualTo("Name: another job has this name");
        type(e, KitJob.NAME_FIELD, "kb-drift");
        e.day(0).doClick();
        assertThat(e.messageShown()).isEqualTo("Runs: pick at least one day");
        e.day(0).doClick();
        type(e, KitJob.TIME_FIELD, "25:00");
        assertThat(e.messageShown()).isEqualTo("Time: HH:MM, from 00:00 to 23:59");
        type(e, KitJob.TIME_FIELD, "07:30");
        type(e, KitJob.COMMAND_FIELD, "");
        assertThat(e.messageShown()).startsWith("Program: choose the program");
        type(e, KitJob.COMMAND_FIELD, "bin/kb-drift");
        assertThat(e.valid()).isTrue();

        e.refused("job 'kb-drift': bin/kb-drift is not an executable file in /k");
        assertThat(e.messageShown()).isEqualTo("job 'kb-drift': bin/kb-drift is not an executable file in /k");
        type(e, KitJob.DESCRIPTION_FIELD, "KB drift");
        assertThat(e.messageShown()).as("an edit clears it").isEmpty();
    }

    /// Adding gives a free name on weekdays at 09:00 with no program, which blocks the save until one is picked inside
    /// the root; the state line says Save installs it. Removing takes the job out.
    @Test
    void addingAsksForAProgramAndRemovingTakesTheJobOut() {
        final JobsEditor e = editor();
        e.add();
        assertThat(e.list().getSelectedValue().toString()).isEqualTo("job");
        assertThat(e.stateShown()).isEqualTo("New: Save installs it · no run yet · after Save");
        assertThat(e.messageShown()).startsWith("Program:");
        assertThat(e.valid()).isFalse();
        type(e, KitJob.COMMAND_FIELD, "bin/curator");
        assertThat(e.jobs().orElseThrow()).extracting(KitJob::when).containsExactly("MON 07:30", "MON-FRI 10:15", "MON-FRI 09:00");
        e.add();
        assertThat(e.list().getSelectedValue().toString()).isEqualTo("job-2");
        e.remove();
        e.remove();
        assertThat(e.dirty()).isFalse();
    }

    /// The state line says installed, the last run and the next for a job the scheduler holds, paused for a paused one,
    /// and that a renamed job is new to the scheduler.
    @Test
    void theStateLineSaysHowTheSchedulerHoldsTheJob() {
        final JobsEditor e = editor();
        assertThat(e.stateShown()).isEqualTo("Installed · Mon 10-26 07:30, exit 0 ✓ · Mon 11-02 07:30");
        e.select("daily-brief");
        assertThat(e.stateShown()).isEqualTo("Paused · Mon 10-26 07:30, exit 0 ✓ · none while paused");
        type(e, KitJob.NAME_FIELD, "brief");
        assertThat(e.stateShown()).isEqualTo("New: Save installs it · no run yet · after Save");
    }

    /// A job's menu runs the job the kit has by its saved name and opens its log; both are off for a job added here,
    /// Run now also for a paused one.
    @Test
    void aJobsMenuRunsItOrOpensItsLog() {
        final JobsEditor e = editor();
        final JPopupMenu menu = e.menuFor(0);
        ((JMenuItem) menu.getComponent(0)).doClick();
        ((JMenuItem) menu.getComponent(1)).doClick();
        assertThat(did).containsExactly("run kb-drift", "open " + Path.of("/k/.kit-jobs/kb-drift.log"));
        assertThat(e.menuFor(1).getComponent(0).isEnabled()).as("paused").isFalse();
        e.add();
        final JPopupMenu added = e.menuFor(2);
        assertThat(added.getComponent(0).isEnabled()).isFalse();
        assertThat(added.getComponent(1).isEnabled()).isFalse();
        ((JMenuItem) added.getComponent(2)).doClick();
        assertThat(e.list().getModel().getSize()).as("Remove takes it out").isEqualTo(2);
        assertThat(e.dirty()).isFalse();
    }

    /// A job whose description has outer spaces or whose variable ends in a space opens clean: what kit.py takes is
    /// kept as written.
    @Test
    void outerSpacesAreKeptAsWritten() {
        final KitJob spaced = new KitJob("s", " spaced ", List.of("MON"), "07:30", List.of("bin/s"), Map.of("A", "x "), "", true);
        final JobsEditor e = new JobsEditor(List.of(entry(spaced, false)), Path.of("/k"), new JobsEditor.Actions() {
            @Override
            public Optional<Path> program(final Component owner, final Path bin) {
                return Optional.empty();
            }

            @Override
            public void run(final String name) {
            }

            @Override
            public void open(final Path file) {
            }
        });
        assertThat(e.dirty()).isFalse();
        assertThat(e.jobs()).contains(List.of(spaced));
    }

    @TempDir
    Path kit;

    /// An editor on a real kit folder: `kb-drift`'s program and log exist there, `curator` failed its last run and
    /// `cdb-serve` is a service.
    private JobsEditor onKit() throws IOException {
        Files.createDirectories(kit.resolve("tools/kb-drift"));
        Files.writeString(kit.resolve("tools/kb-drift/kb-drift"), "#!/bin/sh\n");
        Files.createDirectories(kit.resolve(".kit-jobs"));
        Files.writeString(kit.resolve(".kit-jobs/kb-drift.log"), "ran\n");
        final KitJob drift = new KitJob("kb-drift", "", List.of("MON"), "07:30", List.of("tools/kb-drift/kb-drift"),
                Map.of(), "", true);
        final KitJob curator = new KitJob("curator", "", List.of("MON"), "07:45", List.of("tools/curator/curator"),
                Map.of(), "", true);
        final KitJob serve = new KitJob("cdb-serve", "", List.of(), "", List.of("tools/cdb-serve/cdb-serve"), Map.of(),
                "", true, true);
        final List<KitJobs.Entry> entries = List.of(
                new KitJobs.Entry(drift, true, true, true, false, "2026-11-16T07:30+01:00", 0, "2026-11-23T07:30+01:00",
                        kit.resolve(".kit-jobs/kb-drift.log").toString()),
                new KitJobs.Entry(curator, true, true, true, false, "2026-11-16T07:45+01:00", 1,
                        "2026-11-23T07:45+01:00", kit.resolve(".kit-jobs/curator.log").toString()),
                new KitJobs.Entry(serve, true, true, true, true, "2026-11-15T23:44+01:00", null, "",
                        kit.resolve(".kit-jobs/cdb-serve.log").toString()));
        return new JobsEditor(entries, kit, new JobsEditor.Actions() {
            @Override
            public Optional<Path> program(final Component owner, final Path tools) {
                return Optional.empty();
            }

            @Override
            public void run(final String name) {
                did.add("run " + name);
            }

            @Override
            public void open(final Path file) {
                did.add("open " + file);
            }
        });
    }

    /// The program's and the log's open buttons open the files their fields name, resolved in the kit, and are off
    /// when nothing is named; an empty Log field shows, in grey, the default kit.py reported.
    @Test
    void theProgramAndTheLogOpenFromTheirRows() throws IOException {
        final JobsEditor e = onKit();
        assertThat(e.openProgramButton().isEnabled()).isTrue();
        assertThat(e.openLogButton().isEnabled()).isTrue();
        e.openProgramButton().doClick();
        e.openLogButton().doClick();
        assertThat(did).containsExactly("open " + kit.resolve("tools/kb-drift/kb-drift"),
                "open " + kit.resolve(".kit-jobs/kb-drift.log"));
        assertThat(e.field(KitJob.LOG_FIELD).getClientProperty("JTextField.placeholderText"))
                .isEqualTo(".kit-jobs/kb-drift.log");

        type(e, KitJob.COMMAND_FIELD, "");
        assertThat(e.openProgramButton().isEnabled()).as("no program named").isFalse();
        type(e, KitJob.LOG_FIELD, "logs/drift.log");
        did.clear();
        e.openLogButton().doClick();
        assertThat(did).as("a typed log opens in the kit").containsExactly("open " + kit.resolve("logs/drift.log"));
        e.add();
        assertThat(e.openLogButton().isEnabled()).as("a new job has no log yet").isFalse();
    }

    /// A service's Runs row says it is always running, a timed job's shows its days; the status above the fields
    /// names a failed last run with its exit code.
    @Test
    void aServiceSaysAlwaysRunningAndAFailedRunShowsItsExit() throws IOException {
        final JobsEditor e = onKit();
        assertThat(e.runsShown()).isEqualTo("days");
        e.select("cdb-serve");
        assertThat(e.runsShown()).isEqualTo("always");
        assertThat(e.stateShown()).isEqualTo("Installed, running · Sun 11-15 23:44 · always on");
        e.select("curator");
        assertThat(e.stateShown()).isEqualTo("Installed · Mon 11-16 07:45, exit 1 ✗ · Mon 11-23 07:45");
    }

    /// Each row's tooltip says how the job stands and that a right click has its actions: a good last run, a failed
    /// one with its exit, a running service.
    @Test
    void eachRowSaysHowItsJobStands() throws IOException {
        final JobsEditor e = onKit();
        assertThat(rowTip(e, 0)).isEqualTo("Last run ended well, Mon 11-16 07:30; right-click for actions");
        assertThat(rowTip(e, 1)).isEqualTo("Last run failed with exit 1, Mon 11-16 07:45; right-click for actions");
        assertThat(rowTip(e, 2)).isEqualTo("Running now; right-click for actions");
    }

    /// Every field's label carries an ⓘ, and every field but Enabled a hint line under it.
    @Test
    void everyFieldHasAHintAndAnExplanation() throws IOException {
        final JobsEditor e = onKit();
        final List<String> names = new ArrayList<>();
        collect(e.component(), names);
        for (final String key : List.of(KitJob.NAME_FIELD, KitJob.DESCRIPTION_FIELD, KitJob.DAYS_FIELD,
                KitJob.COMMAND_FIELD, "arguments", KitJob.ENV_FIELD, KitJob.LOG_FIELD)) {
            assertThat(names).contains("hint:" + key, "about:" + key);
        }
        assertThat(names).contains("about:enabled", "context", "context-actions");
    }

    private static String rowTip(final JobsEditor e, final int i) {
        final JList<JobsEditor.Row> list = e.list();
        final Component c = list.getCellRenderer().getListCellRendererComponent(list,
                list.getModel().getElementAt(i), i, false, false);
        return ((JComponent) c).getToolTipText();
    }

    private static void collect(final Component c, final List<String> names) {
        if (c.getName() != null) {
            names.add(c.getName());
        }
        if (c instanceof Container k) {
            for (final Component child : k.getComponents()) {
                collect(child, names);
            }
        }
    }
}
