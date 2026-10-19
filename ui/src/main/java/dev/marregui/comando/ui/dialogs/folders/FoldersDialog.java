package dev.marregui.comando.ui.dialogs.folders;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.HomeDir;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.ui.kit.desktop.FileChoosers;
import dev.marregui.comando.ui.kit.resource.ResourceActions;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.vcs.RepoChanges;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Component;
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

/**
 * The Files tab's folders inside Claude Code's folder, or its repositories, in a window over comando's: the {@link FoldersEditor}, Cancel and Save.
 * Save writes {@code folders.json} once and is on only while every entry passes; Cancel, Esc and the window's close drop
 * the edits, asking first when there are any. EDT only.
 */
public final class FoldersDialog {

    private final JDialog dialog;
    private final FoldersEditor editor;
    private final JButton save = new JButton("Save");
    private final IntentSink intents;
    private final ResourceActions.Questions questions;
    private final String title;

    private FoldersDialog(Window owner, List<FolderRoot> folders, boolean repositories, RepoChanges repos, IntentSink intents) {
        this.intents = intents;
        this.questions = ResourceActions.Questions.MODAL;
        this.title = title(repositories);
        String claude = HomeDir.shorten(HomeDir.claudeDir().toString());
        editor = new FoldersEditor(folders, repositories, HomeDir.claudeDir(), repos, new FoldersEditor.Chooser() {
            @Override
            public Optional<Path> folder(Component parent, Path start) {
                return FileChoosers.directory(parent, repositories ? "Choose a git repository's top folder" : "Choose a folder inside " + claude, start);
            }

            @Override
            public Optional<Path> file(Component parent, Path start) {
                return FileChoosers.open(parent, "Choose a file in the folder", start);
            }
        });
        dialog = new JDialog(owner, title, Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                cancel();
            }
        });
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> cancel());
        save.putClientProperty("JButton.buttonType", "default");
        save.addActionListener(e -> save());
        editor.onChange(() -> save.setEnabled(editor.valid()));
        save.setEnabled(editor.valid());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING, Theme.GAP, 0));
        buttons.setBorder(BorderFactory.createEmptyBorder(0, Theme.PAD, Theme.PAD, Theme.PAD));
        buttons.add(cancel);
        buttons.add(save);
        JPanel content = new JPanel(new BorderLayout());
        content.add(editor.component(), BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        JComponent root = dialog.getRootPane();
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel");
        root.getActionMap().put("cancel", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                cancel();
            }
        });
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
    }

    /** The window's title: Repositories, or Claude Code's folder's folders ("~/.claude folders"). */
    public static String title(boolean repositories) {
        return repositories ? "Repositories" : HomeDir.shorten(HomeDir.claudeDir().toString()) + " folders";
    }

    /** Opens the window over {@code owner}, of the repositories or of the folders; returns when it closes. */
    public static void show(Window owner, List<FolderRoot> folders, boolean repositories, RepoChanges repos, IntentSink intents) {
        new FoldersDialog(owner, folders, repositories, repos, intents).dialog.setVisible(true);
    }

    private void save() {
        Optional<List<FolderRoot>> folders = editor.folders();
        if (folders.isEmpty()) {
            return;
        }
        if (editor.dirty()) {
            intents.dispatch(new Intent.SaveFolders(folders.get(), editor.base()));
        }
        dialog.dispose();
    }

    private void cancel() {
        if (editor.dirty()) {
            Object[] options = {"Discard", "Keep editing"};
            if (questions.ask(dialog, "Discard the changes to the " + (editor.repositories() ? "repositories?" : title + "?"), title, options) != 0) {
                return;
            }
        }
        dialog.dispose();
    }
}
