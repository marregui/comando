package dev.marregui.comando.ui.dialogs.jobs;

import dev.marregui.comando.core.model.KitJob;
import dev.marregui.comando.core.model.KitJobs;
import dev.marregui.comando.ui.kit.async.OnEdt;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.swing.JCheckBox;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/// [JobsEditor] edits the kit's scheduled jobs (record 117), checking each as `kit.py` would before it can be saved.
/// - an unchanged list is valid and clean; a change makes it dirty, and undoing it clean again
/// - the days, the time, the program and its arguments, the environment and the pause become the job handed to `kit.py`
/// - a broken rule blocks the save and says which field and why; what `kit.py` refused shows until the next edit
/// - adding gives a free name and asks for a program; removing takes the job out
/// - the state line says how the scheduler holds the job; a new one is not the scheduler's yet
/// - a job's menu runs it now or opens its log, only for a job the kit already has
@ExtendWith(OnEdt.class)
class JobsEditorTest {

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    private final List<String> did = new ArrayList<>();

    private static KitJobs.Entry entry(KitJob j, boolean running) {
        return new KitJobs.Entry(j, true, true, true, running, "2026-10-26T07:30+01:00", 0, j.enabled() ? "2026-11-02T07:30+01:00" : "",
                "/k/.kit-jobs/" + j.name() + ".log");
    }

    private static final KitJob DRIFT = new KitJob("kb-drift", "KB drift report", List.of("MON"), "07:30",
            List.of("bin/kb-drift", "--days", "7"), Map.of("KB_DRIFT_TRIGGER", "{scheduler}"), "", true);
    private static final KitJob BRIEF = new KitJob("daily-brief", "", List.of("MON", "TUE", "WED", "THU", "FRI"), "10:15",
            List.of("bin/daily-brief"), Map.of(), "", false);

    private JobsEditor editor() {
        return new JobsEditor(List.of(entry(DRIFT, false), entry(BRIEF, false)), Path.of("/k"), new JobsEditor.Actions() {
            @Override
            public Optional<Path> program(Component owner, Path bin) {
                return Optional.of(bin.resolve("curator"));
            }

            @Override
            public void run(String name) {
                did.add("run " + name);
            }

            @Override
            public void openLog(Path log) {
                did.add("log " + log);
            }
        });
    }

    private static void type(JobsEditor e, String key, String text) {
        ((JTextComponent) e.field(key)).setText(text);
    }

    /// The list opens clean and valid, and hands back the jobs it was given.
    @Test
    void anUnchangedListIsCleanAndSavesAsItWas() {
        JobsEditor e = editor();
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

    /// The day toggles, the time, the arguments, the environment and Enabled become the job; setting them back makes
    /// the list clean again, and an unpadded time is the same time.
    @Test
    void theFieldsBecomeTheJobHandedToKitPy() {
        JobsEditor e = editor();
        e.day(3).doClick();
        type(e, KitJob.TIME_FIELD, "8:05");
        type(e, "arguments", "--days 8 'a b'");
        type(e, KitJob.ENV_FIELD, "KB_DRIFT_TRIGGER={scheduler}\nX=1");
        ((JCheckBox) e.field("enabled")).doClick();
        assertThat(e.dirty()).isTrue();
        KitJob edited = e.jobs().orElseThrow().getFirst();
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
        JobsEditor e = editor();
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
        JobsEditor e = editor();
        e.add();
        assertThat(e.list().getSelectedValue().toString()).isEqualTo("job");
        assertThat(e.stateShown()).isEqualTo("new: Save installs it");
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
        JobsEditor e = editor();
        assertThat(e.stateShown()).isEqualTo("installed · last Mon 10-26 07:30 exit 0 · next Mon 11-02 07:30");
        e.select("daily-brief");
        assertThat(e.stateShown()).startsWith("paused · last");
        type(e, KitJob.NAME_FIELD, "brief");
        assertThat(e.stateShown()).isEqualTo("new: Save installs it");
    }

    /// A job's menu runs the job the kit has by its saved name and opens its log; both are off for a job added here,
    /// Run now also for a paused one.
    @Test
    void aJobsMenuRunsItOrOpensItsLog() {
        JobsEditor e = editor();
        JPopupMenu menu = e.menuFor(0);
        ((JMenuItem) menu.getComponent(0)).doClick();
        ((JMenuItem) menu.getComponent(1)).doClick();
        assertThat(did).containsExactly("run kb-drift", "log " + Path.of("/k/.kit-jobs/kb-drift.log"));
        assertThat(e.menuFor(1).getComponent(0).isEnabled()).as("paused").isFalse();
        e.add();
        JPopupMenu added = e.menuFor(2);
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
        KitJob spaced = new KitJob("s", " spaced ", List.of("MON"), "07:30", List.of("bin/s"), Map.of("A", "x "), "", true);
        JobsEditor e = new JobsEditor(List.of(entry(spaced, false)), Path.of("/k"), new JobsEditor.Actions() {
            @Override
            public Optional<Path> program(Component owner, Path bin) {
                return Optional.empty();
            }

            @Override
            public void run(String name) {
            }

            @Override
            public void openLog(Path log) {
            }
        });
        assertThat(e.dirty()).isFalse();
        assertThat(e.jobs()).contains(List.of(spaced));
    }
}
