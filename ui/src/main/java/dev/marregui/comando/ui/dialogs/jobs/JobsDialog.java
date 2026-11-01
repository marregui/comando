package dev.marregui.comando.ui.dialogs.jobs;

import java.awt.Dialog;
import java.awt.Window;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JDialog;

import dev.marregui.comando.core.model.KitJob;
import dev.marregui.comando.core.model.KitJobs;
import dev.marregui.comando.ui.dialogs.FormRows;
import dev.marregui.comando.ui.kit.resource.ResourceActions;

/**
 * The kit's scheduled jobs in a window over comando's (record 117): the {@link JobsEditor}, Cancel and Save. Save hands
 * the whole list to {@code kit.py}, which writes {@code comando-kit.json}'s jobs and brings the scheduler to them; the
 * window waits for its answer, closes when it took the list, and stays open saying why when it refused it. Cancel, Esc
 * and the window's close drop the edits, asking first when there are any. EDT only.
 */
public final class JobsDialog {

    public static final String TITLE = "Kit jobs";

    /**
     * Hands the list to {@code kit.py} off the EDT, then runs {@code done}, or {@code refused} with why, on the EDT.
     */
    public interface Saver {
        void save(List<KitJob> jobs, Runnable done, Consumer<String> refused);
    }

    private final JDialog dialog;
    private final JobsEditor editor;
    private final JButton save = new JButton("Save");
    private final JButton cancel = new JButton("Cancel");
    private final Saver saver;
    private final ResourceActions.Questions questions;
    private boolean saving;

    JobsDialog(final Window owner, final KitJobs jobs, final Path root, final JobsEditor.Actions actions, final Saver saver, final ResourceActions.Questions questions) {
        this.saver = saver;
        this.questions = questions;
        editor = new JobsEditor(jobs.jobs(), root, actions);
        dialog = new JDialog(owner, TITLE, Dialog.ModalityType.APPLICATION_MODAL);
        cancel.addActionListener(e -> cancel());
        save.setToolTipText("Write the jobs to comando-kit.json and bring the scheduler to them");
        save.addActionListener(e -> save());
        editor.onChange(this::enable);
        enable();
        FormRows.frame(dialog, owner, editor.component(), FormRows.south(cancel, save), this::cancel);
    }

    /** Opens the window over {@code owner}, {@code select} selected when named; returns when it closes. */
    public static void show(final Window owner, final KitJobs jobs, final Path root, final JobsEditor.Actions actions, final Saver saver, final String select) {
        final JobsDialog d = new JobsDialog(owner, jobs, root, actions, saver, ResourceActions.Questions.MODAL);
        if (select != null) {
            d.editor.select(select);
        }
        d.dialog.setVisible(true);
    }

    JobsEditor editor() {
        return editor;
    }

    JDialog dialog() {
        return dialog;
    }

    JButton saveButton() {
        return save;
    }

    private void enable() {
        save.setEnabled(!saving && editor.valid() && editor.dirty());
        cancel.setEnabled(!saving);
    }

    void save() {
        final Optional<List<KitJob>> jobs = editor.jobs();
        if (jobs.isEmpty() || saving) {
            return;
        }
        if (!editor.dirty()) {
            dialog.dispose();
            return;
        }
        saving = true;
        save.setText("Saving…");
        enable();
        saver.save(jobs.get(), dialog::dispose, why -> {
            saving = false;
            save.setText("Save");
            editor.refused(why);
            enable();
        });
    }

    private void cancel() {
        if (saving) {
            return;
        }
        if (editor.dirty()) {
            final Object[] options = {"Discard", "Keep editing"};
            if (questions.ask(dialog, "Discard the changes to the kit jobs?", TITLE, options) != 0) {
                return;
            }
        }
        dialog.dispose();
    }
}
