package dev.marregui.comando.ui.dialogs.folders;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Window;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.ui.dialogs.FormRows;
import dev.marregui.comando.ui.kit.desktop.FileChoosers;
import dev.marregui.comando.ui.kit.resource.ResourceActions;
import dev.marregui.comando.ui.kit.vcs.RepoChanges;

/**
 * The folders of the folder tabs (Kit, Brain, Scratchpad), inside Claude Code's folder, in a window over comando's:
 * the {@link FoldersEditor}, Cancel and Save, and {@link #USE_KIT} when comando runs on a kit. Save writes
 * {@code folders.json} once, when something changed or an entry names an index file that does not exist yet in the
 * kit or the brain (Save creates it), and is on only then and while every entry passes; Cancel, Esc and the window's
 * close drop the edits, asking first when there are any. EDT only.
 */
public final class FoldersDialog {

    /** The folders' window's button that puts the kit's folders back. */
    public static final String USE_KIT = "Use the kit's folders";

    private final JDialog dialog;
    private final FoldersEditor editor;
    private final JButton save = new JButton("Save");
    private final IntentSink intents;
    private final ResourceActions.Questions questions;
    private final String title;

    private FoldersDialog(final Window owner, final List<FolderRoot> folders, final RepoChanges repos, final IntentSink intents,
                          final List<FolderRoot> kitFolders, final List<Path> roots, final List<Path> indexRoots) {
        this.intents = intents;
        this.questions = ResourceActions.Questions.MODAL;
        this.title = title();
        editor = new FoldersEditor(folders, roots, indexRoots, repos, new FoldersEditor.Chooser() {
            @Override
            public Optional<Path> folder(final Component parent, final Path start) {
                return FileChoosers.directory(parent, "Choose a folder", start);
            }

            @Override
            public Optional<Path> file(final Component parent, final Path start) {
                return FileChoosers.open(parent, "Choose a file in the folder", start);
            }
        });
        dialog = new JDialog(owner, title, Dialog.ModalityType.APPLICATION_MODAL);
        final JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> cancel());
        save.addActionListener(e -> save());
        editor.onChange(() -> save.setEnabled(saveOffered(editor)));
        save.setEnabled(saveOffered(editor));
        final JPanel south = FormRows.south(cancel, save);
        if (!kitFolders.isEmpty()) {
            // the kit's folders back at the top, as comando set them at the first start on the kit; the user's stay
            final JButton kit = new JButton(USE_KIT);
            kit.setToolTipText("Put the kit's folders the list lacks at its top; the others stay");
            kit.addActionListener(e -> editor.addFirst(kitFolders));
            south.add(kit, BorderLayout.WEST);
        }
        FormRows.frame(dialog, owner, editor.component(), south, this::cancel);
    }

    /**
     * The window's title: what it holds, the folder tabs' folders, which lie in Claude Code's folder, the kit or the
     * brain, each anywhere (Miguel's decision of 2026-11-01), so no one folder names them.
     */
    public static String title() {
        return "Sidebar folders";
    }

    /**
     * Opens the window over {@code owner}; returns when it closes. Offers {@link #USE_KIT} when the kit has folders. A
     * folder lies in one of {@code roots}: Claude Code's folder, and the kit's and the brain's, which may lie anywhere.
     * Save creates an index file named but missing only inside one of {@code indexRoots}: the kit's folder and the
     * brain, none without a kit.
     */
    public static void show(final Window owner, final List<FolderRoot> folders, final RepoChanges repos, final IntentSink intents,
                            final List<FolderRoot> kitFolders, final List<Path> roots, final List<Path> indexRoots) {
        new FoldersDialog(owner, folders, repos, intents, kitFolders, roots, indexRoots).dialog.setVisible(true);
    }

    /**
     * Save is on when the list is valid and saving changes something, as in the kit jobs window: the list differs from
     * what was saved, or an entry names an index file that does not exist yet, which Save creates.
     */
    static boolean saveOffered(final FoldersEditor editor) {
        return editor.valid() && changesSomething(editor);
    }

    private static boolean changesSomething(final FoldersEditor editor) {
        return editor.dirty() || editor.indexesToCreate();
    }

    private void save() {
        final Optional<List<FolderRoot>> folders = editor.folders();
        if (folders.isEmpty()) {
            return;
        }
        if (changesSomething(editor)) {
            intents.dispatch(new Intent.SaveFolders(folders.get(), editor.base()));
        }
        dialog.dispose();
    }

    private void cancel() {
        if (editor.dirty()) {
            final Object[] options = {"Discard", "Keep editing"};
            if (questions.ask(dialog, "Discard the changes to the " + title + "?", title, options) != 0) {
                return;
            }
        }
        dialog.dispose();
    }
}
