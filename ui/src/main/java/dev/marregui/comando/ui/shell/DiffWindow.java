package dev.marregui.comando.ui.shell;

import java.awt.Component;
import java.awt.Dialog;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import dev.marregui.comando.core.model.ChangeAsk;
import dev.marregui.comando.core.model.ChangeTexts;
import dev.marregui.comando.core.model.RepoStatus;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.ui.kit.desktop.Platform;
import dev.marregui.comando.ui.kit.resource.ResourceActions;
import dev.marregui.comando.ui.kit.text.SplitDiff;
import dev.marregui.comando.ui.widgets.changes.ChangeEditor;

/**
 * A changed file compared with its {@code HEAD} version, side by side ({@link ChangeEditor}), in a window of its own
 * that floats over comando's: one window, reused for every file compared, so moving through the Changes view moves it
 * along. It takes the keyboard when a file is opened, not when it follows the selection. While it shows, the file is
 * read again every {@link #REREAD_MILLIS}, so edits made meanwhile (by a session, say) show where the user scrolled
 * to. Its edits are written every second, when it closes, when it moves to another file and when comando quits. F7
 * and ⇧F7 go to the next and the previous difference, ⌘⇧M switches a Markdown file to its rendered sides, Esc and ⌘W
 * close it.
 */
final class DiffWindow {

    /** How often the file shown is read again. */
    static final int REREAD_MILLIS = 3_000;
    /** How long quitting waits for the window's edits to be written. */
    static final Duration SAVE_AT_QUIT = Duration.ofSeconds(2);

    private static final System.Logger LOG = System.getLogger(DiffWindow.class.getName());

    private final Window owner;
    private final Timer reread;
    private final ChangeEditor editor;
    private JDialog dialog;

    /**
     * {@code reread} asks for the file shown again, every {@link #REREAD_MILLIS} while the window shows;
     * {@code writer} writes the file edited.
     */
    DiffWindow(final Window owner, final Runnable reread, final IntentSink intents, final ChangeEditor.Writer writer, final ResourceActions.Questions questions) {
        this.owner = owner;
        this.editor = new ChangeEditor(intents, writer, questions);
        editor.onGone(this::close);
        // macOS draws the window's close button with a dot while its document has unsaved changes
        editor.onModified(m -> {
            if (dialog != null) {
                dialog.getRootPane().putClientProperty("Window.documentModified", m);
            }
        });
        this.reread = new Timer(REREAD_MILLIS, e -> {
            if (isShowing()) {
                reread.run();
            }
        });
    }

    /** Whether a comparison is on screen. */
    boolean isShowing() {
        return dialog != null && dialog.isVisible();
    }

    /** Whether the comparison on screen is of {@code path} in {@code root}. */
    boolean shows(final String root, final String path) {
        return isShowing() && Objects.equals(editor.root(), root) && editor.change() != null && editor.change().path().equals(path);
    }

    String root() {
        return editor.root();
    }

    RepoStatus.FileChange change() {
        return editor.change();
    }

    SplitDiff diff() {
        return editor.diff();
    }

    ChangeEditor editor() {
        return editor;
    }

    /** Whether {@code c} is in this window. */
    boolean holds(final Component c) {
        return dialog != null && SwingUtilities.getWindowAncestor(c) == dialog;
    }

    /**
     * Shows {@code texts} as {@code ask} says: an opened file comes forward and takes the keyboard; a file the
     * selection moved to shows in place, after the one shown is saved; a file read again shows only while the window
     * still shows it, so a read asked for before the selection moved never takes the window back.
     */
    void show(final ChangeTexts texts, final ChangeAsk ask) {
        final RepoStatus.FileChange c = texts.change();
        if (ask == ChangeAsk.REFRESH && !shows(texts.root(), c.path())) {
            return;
        }
        if (isShowing() && texts.equals(editor.shown())) {
            if (ask == ChangeAsk.OPEN) {
                dialog.toFront();
            }
            return;
        }
        editor.show(texts, shows(texts.root(), c.path()));
        final JDialog d = dialog();
        d.setTitle(c.path() + " — compared with HEAD");
        if (!d.isVisible()) {
            place(d);
            d.setVisible(true);
            reread.restart();
        }
        if (ask == ChangeAsk.OPEN) {
            d.toFront();
            editor.diff().right().requestFocusInWindow();
        }
    }

    /** Hides the window; its edits are written as it hides. */
    void close() {
        if (dialog != null) {
            dialog.setVisible(false);
        }
    }

    /** Drops the window's edits and closes it when it shows one of {@code paths} in {@code root}, changed elsewhere. */
    void drop(final String root, final Collection<String> paths) {
        if (editor.change() != null && Objects.equals(editor.root(), root) && paths.contains(editor.change().path())) {
            editor.discard();
            close();
        }
    }

    /** Writes the window's edits, waiting at most {@link #SAVE_AT_QUIT}; for quitting. */
    void saveNow() {
        try {
            final String reason = editor.save().get(SAVE_AT_QUIT.toMillis(), TimeUnit.MILLISECONDS);
            if (reason != null) {
                LOG.log(System.Logger.Level.WARNING, "cannot save the comparison window's edits [reason=" + reason + ']');
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot save the comparison window's edits in time", e);
        }
    }

    void dispose() {
        reread.stop();
        editor.unmount();
        if (dialog != null) {
            dialog.dispose();
            dialog = null;
        }
    }

    private JDialog dialog() {
        if (dialog != null) {
            return dialog;
        }
        final JDialog d = new JDialog(owner, "", Dialog.ModalityType.MODELESS);
        d.setDefaultCloseOperation(JDialog.HIDE_ON_CLOSE);
        d.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentHidden(final ComponentEvent e) {
                reread.stop();
                editor.save();
            }
        });
        d.setContentPane(editor.component());
        final JComponent root = d.getRootPane();
        final int shortcut = Platform.menuShortcutMask();
        bind(root, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close", this::close);
        bind(root, KeyStroke.getKeyStroke(KeyEvent.VK_W, shortcut), "close", this::close);
        bind(root, KeyStroke.getKeyStroke(KeyEvent.VK_S, shortcut), "save", editor::save);
        bind(root, KeyStroke.getKeyStroke(KeyEvent.VK_M, shortcut | KeyEvent.SHIFT_DOWN_MASK), "rendered", editor::toggleRendered);
        bind(root, KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0), "next", editor.diff()::next);
        bind(root, KeyStroke.getKeyStroke(KeyEvent.VK_F7, KeyEvent.SHIFT_DOWN_MASK), "previous", editor.diff()::previous);
        dialog = d;
        return d;
    }

    private static void bind(final JComponent c, final KeyStroke key, final String name, final Runnable r) {
        c.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(key, name);
        c.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent e) {
                r.run();
            }
        });
    }

    /** The first time: most of comando's window, centred on it. Later the user's own size and place stay. */
    private void place(final JDialog d) {
        if (d.getWidth() > 0) {
            return;
        }
        final Rectangle o = owner.getBounds();
        final int w = Math.max(800, (int) (o.width * 0.9));
        final int h = Math.max(500, (int) (o.height * 0.85));
        d.setBounds(o.x + (o.width - w) / 2, o.y + (o.height - h) / 2, w, h);
    }
}
