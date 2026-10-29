package dev.marregui.comando.ui.dialogs.jobs;

import dev.marregui.comando.core.model.KitJob;
import dev.marregui.comando.core.model.KitJobs;
import dev.marregui.comando.ui.kit.resource.ResourceActions;
import dev.marregui.comando.ui.kit.theme.Theme;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

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

    JobsDialog(Window owner, KitJobs jobs, Path root, JobsEditor.Actions actions, Saver saver, ResourceActions.Questions questions) {
        this.saver = saver;
        this.questions = questions;
        editor = new JobsEditor(jobs.jobs(), root, actions);
        dialog = new JDialog(owner, TITLE, Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                cancel();
            }
        });
        cancel.addActionListener(e -> cancel());
        save.putClientProperty("JButton.buttonType", "default");
        save.setToolTipText("Write the jobs to comando-kit.json and bring the scheduler to them");
        save.addActionListener(e -> save());
        editor.onChange(this::enable);
        enable();
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING, Theme.GAP, 0));
        buttons.add(cancel);
        buttons.add(save);
        JPanel south = new JPanel(new BorderLayout());
        south.setBorder(BorderFactory.createEmptyBorder(0, Theme.PAD, Theme.PAD, Theme.PAD));
        south.add(buttons, BorderLayout.EAST);
        JPanel content = new JPanel(new BorderLayout());
        content.add(editor.component(), BorderLayout.CENTER);
        content.add(south, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        JComponent pane = dialog.getRootPane();
        pane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel");
        pane.getActionMap().put("cancel", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                cancel();
            }
        });
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
    }

    /** Opens the window over {@code owner}, {@code select} selected when named; returns when it closes. */
    public static void show(Window owner, KitJobs jobs, Path root, JobsEditor.Actions actions, Saver saver, String select) {
        JobsDialog d = new JobsDialog(owner, jobs, root, actions, saver, ResourceActions.Questions.MODAL);
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
        Optional<List<KitJob>> jobs = editor.jobs();
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
            Object[] options = {"Discard", "Keep editing"};
            if (questions.ask(dialog, "Discard the changes to the kit jobs?", TITLE, options) != 0) {
                return;
            }
        }
        dialog.dispose();
    }
}
