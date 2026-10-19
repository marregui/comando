package dev.marregui.comando.ui.shell;

import dev.marregui.comando.core.model.ChangeAsk;
import dev.marregui.comando.core.model.ChangeTexts;
import dev.marregui.comando.core.model.RepoStatus;
import dev.marregui.comando.ui.kit.async.Edt;
import dev.marregui.comando.ui.kit.format.Labels;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.kit.text.SplitDiff;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.vcs.RepoChanges;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.util.Objects;

/**
 * A changed file compared with its {@code HEAD} version, side by side, in a window of its own that floats over
 * comando's: one window, reused for every file compared, so moving through the Changes view moves it along. It takes
 * the keyboard when a file is opened, not when it follows the selection. While it shows, the file is read again every
 * few seconds, so edits made meanwhile (by a session, say) show where the user scrolled to. F7 and ⇧F7 (or its
 * buttons) go to the next and the previous difference; Esc closes it.
 */
final class DiffWindow {

    /** How often the file shown is read again. */
    static final int REREAD_MILLIS = 3_000;
    /** How long the line match may take before the texts are compared as a whole. */
    static final long DIFF_MILLIS = 2_000;

    private final Window owner;
    private final Timer reread;
    /** Switches every editor, this window's included, to the other theme. */
    private final Runnable toggleTheme;
    private final JButton theme = new JButton(Icons.get("theme"));
    private JDialog dialog;
    /** The texts on screen, so a read that changed nothing changes nothing. */
    private ChangeTexts shown;
    /** Counts the comparisons asked for; only the newest is shown when its work ends. */
    private long asked;
    private final SplitDiff diff = new SplitDiff();
    private final JLabel title = new JLabel();
    private final JLabel count = new JLabel();
    private final JLabel note = new JLabel("", SwingConstants.CENTER);
    private final CardLayout cards = new CardLayout();
    private final JPanel center = new JPanel(cards);
    /** The repository and the path shown, or null before the first. */
    private String root;
    private RepoStatus.FileChange change;

    /**
     * {@code reread} asks for the file shown again; it runs every few seconds while the window shows. {@code
     * toggleTheme} switches every editor's theme, as a panel's theme button does.
     */
    DiffWindow(Window owner, Runnable reread, Runnable toggleTheme) {
        this.owner = owner;
        this.toggleTheme = toggleTheme;
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
    boolean shows(String root, String path) {
        return isShowing() && Objects.equals(this.root, root) && change != null && change.path().equals(path);
    }

    String root() {
        return root;
    }

    RepoStatus.FileChange change() {
        return change;
    }

    SplitDiff diff() {
        return diff;
    }

    /**
     * Shows {@code texts} as {@code ask} says: an opened file comes forward and takes the keyboard; a file the selection
     * moved to shows in place; a file read again shows only while the window still shows it, so a read asked for
     * before the selection moved never takes the window back. The comparison is worked out off the event thread, at
     * most {@link #DIFF_MILLIS} for the line match; the newest asked for is the one shown.
     */
    void show(ChangeTexts texts, ChangeAsk ask) {
        RepoStatus.FileChange c = texts.change();
        if (ask == ChangeAsk.REFRESH && !shows(texts.root(), c.path())) {
            return;
        }
        if (isShowing() && texts.equals(shown)) {
            if (ask == ChangeAsk.OPEN) {
                dialog.toFront();
            }
            return;
        }
        // new versions of the file shown keep the place the user scrolled to
        boolean again = shows(texts.root(), c.path());
        shown = texts;
        root = texts.root();
        change = c;
        long mine = ++asked;
        title.setText(Plain.text(c.path() + "   " + RepoChanges.describe(c.kind()) + (c.from().isEmpty() ? "" : " from " + c.from())
                + "   · " + Labels.shortPath(texts.root())));
        title.setForeground(RepoChanges.color(c.kind()));
        if (texts.note().isEmpty()) {
            count.setText("comparing…");
            String before = texts.before();
            String after = texts.after();
            Thread.ofVirtual().name("diff-compute").start(() -> {
                SplitDiff.Model m = SplitDiff.compute(before, after, DIFF_MILLIS);
                Edt.later(() -> {
                    if (mine == asked) {
                        apply(c, m, again);
                    }
                });
            });
        } else {
            note.setText(Plain.text(texts.note()));
            count.setText("");
            cards.show(center, "note");
        }
        JDialog d = dialog();
        d.setTitle(c.path() + " — compared with HEAD");
        if (!d.isVisible()) {
            place(d);
            d.setVisible(true);
            reread.restart();
        }
        if (ask == ChangeAsk.OPEN) {
            d.toFront();
            diff.right().requestFocusInWindow();
        }
    }

    /** Shows a comparison worked out for {@code c}. */
    private void apply(RepoStatus.FileChange c, SplitDiff.Model m, boolean again) {
        if (!m.compared()) {
            note.setText(Plain.text(m.note()));
            count.setText("");
            cards.show(center, "note");
            return;
        }
        diff.show(c.path(), "HEAD" + (c.from().isEmpty() ? "" : " · " + c.from()), "Working tree", m, again);
        int n = m.chunks().size();
        count.setText((n == 0 ? "no differences" : n == 1 ? "1 difference" : n + " differences") + (m.note().isEmpty() ? "" : " · " + m.note()));
        cards.show(center, "diff");
    }

    /** The light editor theme or the dark one, as every panel's editors take it. */
    void light(boolean on) {
        diff.light(on);
        showTheme();
    }

    private void showTheme() {
        boolean light = diff.isLight();
        // worded as a panel's theme button: the switch is the same, and so is its reach
        theme.setToolTipText(light ? "Switch every editor and this diff to dark" : "Switch every editor and this diff to light");
        theme.getAccessibleContext().setAccessibleName(light ? "Dark editors" : "Light editors");
    }

    void close() {
        if (dialog != null) {
            dialog.setVisible(false);
        }
    }

    void dispose() {
        reread.stop();
        if (dialog != null) {
            dialog.dispose();
            dialog = null;
        }
    }

    private JDialog dialog() {
        if (dialog != null) {
            return dialog;
        }
        JDialog d = new JDialog(owner, "", Dialog.ModalityType.MODELESS);
        d.setDefaultCloseOperation(JDialog.HIDE_ON_CLOSE);
        d.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentHidden(ComponentEvent e) {
                reread.stop();
            }
        });
        JPanel top = new JPanel(new BorderLayout());
        top.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 6));
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        top.add(title, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        count.setForeground(Theme.MUTED_STRONG);
        buttons.add(count);
        buttons.add(arrow("↑", "Previous difference (⇧F7)", diff::previous));
        buttons.add(arrow("↓", "Next difference (F7)", diff::next));
        theme.putClientProperty("JButton.buttonType", "toolBarButton");
        theme.setFocusable(false);
        theme.addActionListener(e -> toggleTheme.run());
        showTheme();
        buttons.add(theme);
        top.add(buttons, BorderLayout.EAST);
        note.setForeground(Theme.MUTED_STRONG);
        center.add(diff.component(), "diff");
        center.add(note, "note");
        JPanel body = new JPanel(new BorderLayout());
        body.add(top, BorderLayout.NORTH);
        body.add(center, BorderLayout.CENTER);
        d.setContentPane(body);
        JComponent root = d.getRootPane();
        bind(root, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close", this::close);
        bind(root, KeyStroke.getKeyStroke(KeyEvent.VK_W, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "close", this::close);
        bind(root, KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0), "next", diff::next);
        bind(root, KeyStroke.getKeyStroke(KeyEvent.VK_F7, KeyEvent.SHIFT_DOWN_MASK), "previous", diff::previous);
        dialog = d;
        return d;
    }

    private static JButton arrow(String text, String tip, Runnable r) {
        JButton b = new JButton(text);
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.setToolTipText(tip);
        b.setFocusable(false);
        b.addActionListener(e -> r.run());
        return b;
    }

    private static void bind(JComponent c, KeyStroke key, String name, Runnable r) {
        c.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(key, name);
        c.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        });
    }

    /** The first time: most of comando's window, centred on it. Later the user's own size and place stay. */
    private void place(JDialog d) {
        if (d.getWidth() > 0) {
            return;
        }
        Rectangle o = owner.getBounds();
        int w = Math.max(800, (int) (o.width * 0.9));
        int h = Math.max(500, (int) (o.height * 0.85));
        d.setBounds(o.x + (o.width - w) / 2, o.y + (o.height - h) / 2, w, h);
    }
}
