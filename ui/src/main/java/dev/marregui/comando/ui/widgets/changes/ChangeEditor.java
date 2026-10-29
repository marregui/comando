package dev.marregui.comando.ui.widgets.changes;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.ChangeTexts;
import dev.marregui.comando.core.model.RepoStatus;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.ui.kit.action.ToolButton;
import dev.marregui.comando.ui.kit.async.Debouncer;
import dev.marregui.comando.ui.kit.async.Edt;
import dev.marregui.comando.ui.kit.async.UiErrands;
import dev.marregui.comando.ui.kit.desktop.Platform;
import dev.marregui.comando.ui.kit.format.KeyText;
import dev.marregui.comando.ui.kit.format.Labels;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.resource.ResourceActions;
import dev.marregui.comando.ui.kit.text.MarkdownHtml;
import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.kit.text.RenderedMarkdown;
import dev.marregui.comando.ui.kit.text.SplitDiff;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.vcs.RepoChanges;
import dev.marregui.comando.ui.widget.Unmountable;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.BoundedRangeModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * A changed file beside its {@code HEAD} version, the comparison window's body: the two texts side by side with their
 * differences, or, for Markdown, both rendered as a panel's preview renders them. The working tree's side may be
 * edited: the differences are worked out again as it changes, undo and redo go back and forth through every edit made
 * here (⌘Z, ⌘⇧Z), and the text is written every second while it has edits, and on leaving the file (another file,
 * closing the window, quitting comando); beside the side's title it says "edited" until written, then "saved". Roll back, in that side's menu, puts
 * {@code HEAD}'s text in its place, as one edit that undo takes back, saves it and takes the file out of the index; a
 * file {@code HEAD} does not have, or one git renamed, goes back through git, its version to the Trash. Remove moves
 * the file to the Trash, from the same menu. A read of the file while it has edits not yet written is left unshown: the edits win.
 * EDT only.
 */
public final class ChangeEditor implements Unmountable {

    private static final System.Logger LOG = System.getLogger(ChangeEditor.class.getName());

    /** Writes a file's text; completes with null once written, or with why it was not. */
    @FunctionalInterface
    public interface Writer {
        CompletableFuture<String> write(Path file, String text);
    }

    /** How long the line match may take before the texts are compared as a whole. */
    public static final long DIFF_MILLIS = 2_000;
    /** How long typing pauses before the differences are worked out again. */
    static final int RECOMPARE_MILLIS = 250;
    /** How often edits are written while they wait. */
    static final int AUTOSAVE_MILLIS = 1_000;
    private static final String SAVE_TIP = "Saved every second, and when this window or comando closes";

    private final IntentSink intents;
    private final Writer writer;
    private final ResourceActions.Questions questions;
    private Runnable gone = () -> {
    };
    private final SplitDiff diff = new SplitDiff();
    private final RenderedMarkdown headRendered = new RenderedMarkdown();
    private final RenderedMarkdown treeRendered = new RenderedMarkdown();
    private final JLabel title = new JLabel();
    private final JLabel count = new JLabel();
    private final JLabel note = new JLabel("", SwingConstants.CENTER);
    private final CardLayout cards = new CardLayout();
    private final JPanel center = new JPanel(cards);
    private final JPanel body = new JPanel(new BorderLayout());
    private final JButton previous;
    private final JButton next;
    private final JMenuItem rollBack;
    private final JMenuItem remove;
    private final JButton mode;
    private final Debouncer recompare;
    private final Timer autosave;
    /** Hears whether edits wait to be written: the window marks itself modified while they do. */
    private Consumer<Boolean> modified = m -> {
    };

    /** The texts on screen. */
    private ChangeTexts shown;
    private String root;
    private RepoStatus.FileChange change;
    /** The working tree's text as last read or written. */
    private String saved = "";
    /** Whether the file is in the working tree, so the empty text of a removed file is not written. */
    private boolean onDisk;
    private boolean editable;
    private boolean markdown;
    /** Edits typed since the file was read or written. */
    private boolean edited;
    /** The rendered sides shown rather than the texts; kept from one Markdown file to the next. */
    private boolean rendering;
    /** True while code sets the texts, so setting them is not taken for an edit. */
    private boolean loading;
    /** True while one rendered side follows the other's scroll. */
    private boolean following;
    /** Counts the comparisons asked for; only the newest is shown when its work ends. */
    private long asked;
    private String counted = "";

    public ChangeEditor(IntentSink intents, Writer writer, ResourceActions.Questions questions) {
        this.intents = intents;
        this.writer = writer;
        this.questions = questions;
        previous = arrow("↑", "Previous difference (⇧F7)", diff::previous);
        next = arrow("↓", "Next difference (F7)", diff::next);
        rollBack = menuItem("Roll back to HEAD", "rollback", this::rollBack);
        remove = menuItem("Move to the Trash…", "trash", this::remove);
        mode = ToolButton.of("preview", "", this::toggleRendered);
        recompare = new Debouncer(RECOMPARE_MILLIS, this::recompare);
        autosave = new Timer(AUTOSAVE_MILLIS, e -> {
            if (edited) {
                save();
            }
        });
        autosave.start();
        build();
        showMode();
        updateButtons();
    }

    public JComponent component() {
        return body;
    }

    /** What runs when the file shown goes: removed, or rolled back out of the working tree; the window closes. */
    public void onGone(Runnable gone) {
        this.gone = gone;
    }

    public String root() {
        return root;
    }

    public RepoStatus.FileChange change() {
        return change;
    }

    public ChangeTexts shown() {
        return shown;
    }

    public SplitDiff diff() {
        return diff;
    }

    /** The file shown, in the working tree; null before the first. */
    Path file() {
        return root == null ? null : Path.of(root).resolve(change.path());
    }

    /** Whether edits typed here wait to be written. */
    public boolean edited() {
        return edited;
    }

    public boolean rendering() {
        return rendering;
    }

    /** For tests: what the count beside the buttons says. */
    String countShown() {
        return count.getText();
    }

    /** For tests: whether the switch to the rendered sides is offered, and whether the menu's Roll back and Remove are on. */
    boolean offered(String action) {
        return switch (action) {
            case "rendered" -> mode.isVisible();
            case "rollback" -> rollBack.isEnabled();
            case "remove" -> remove.isEnabled();
            default -> throw new IllegalArgumentException(action);
        };
    }

    /** What is said beside the working tree's title: edited, saved, not saved, or nothing. */
    public String status() {
        return diff.rightStatus();
    }

    /** Hears whether edits wait to be written. */
    public void onModified(Consumer<Boolean> modified) {
        this.modified = modified;
    }

    /** Stops writing every second; the window is gone. */
    @Override
    public void unmount() {
        autosave.stop();
        recompare.stop();
    }

    /** For tests: the rendered sides, HEAD's then the working tree's. */
    List<RenderedMarkdown> sides() {
        return List.of(headRendered, treeRendered);
    }

    /**
     * Shows {@code texts}. {@code again}: a new read of the file shown, which keeps the place the user scrolled to and
     * is left unshown while edits wait to be written; else another file, whose edits are written first. The comparison
     * is worked out off the event thread, at most {@link #DIFF_MILLIS} for the line match.
     */
    public void show(ChangeTexts texts, boolean again) {
        if (again && edited) {
            return;
        }
        if (!again) {
            save();
        }
        RepoStatus.FileChange c = texts.change();
        boolean same = again && texts.note().isEmpty() && diff.left().getText().equals(texts.before())
                && diff.right().getText().equals(texts.after());
        shown = texts;
        root = texts.root();
        change = c;
        saved = texts.after();
        edited = false;
        if (!same) {
            say(Said.NOTHING);
        }
        onDisk = c.kind() != RepoStatus.Kind.DELETED;
        markdown = isMarkdown(c.path());
        editable = texts.note().isEmpty() && !Files.isSymbolicLink(file());
        title.setText(Plain.text(c.path() + "   " + RepoChanges.describe(c.kind()) + (c.from().isEmpty() ? "" : " from " + c.from())
                + "   · " + Labels.shortPath(texts.root())));
        title.setForeground(RepoChanges.color(c.kind()));
        if (!texts.note().isEmpty()) {
            note.setText(Plain.text(texts.note()));
            counted = "";
            cards.show(center, "note");
            showMode();
            updateButtons();
            return;
        }
        if (same) {
            // the texts on screen, as after a save or a change of kind: the caret, the place and the undo history stay
            showMode();
            updateButtons();
            return;
        }
        counted = "comparing…";
        updateButtons();
        long mine = ++asked;
        String before = texts.before();
        String after = texts.after();
        UiErrands.start("diff-compute", () -> {
            SplitDiff.Model m = SplitDiff.compute(before, after, DIFF_MILLIS);
            Edt.later(() -> {
                if (mine == asked) {
                    apply(c, m, again);
                }
            });
        });
    }

    /** Shows a comparison worked out for {@code c}. */
    private void apply(RepoStatus.FileChange c, SplitDiff.Model m, boolean again) {
        if (!m.compared()) {
            note.setText(Plain.text(m.note()));
            counted = "";
            editable = false;
            cards.show(center, "note");
            showMode();
            updateButtons();
            return;
        }
        loading = true;
        try {
            diff.show(c.path(), "HEAD" + (c.from().isEmpty() ? "" : " · " + c.from()), "Working tree", m, again);
            diff.editable(editable);
            diff.right().discardAllEdits();
        } finally {
            loading = false;
        }
        count(m);
        showMode();
        if (renderedShown()) {
            renderSides();
        }
        cards.show(center, renderedShown() ? "rendered" : "diff");
        updateButtons();
    }

    private void count(SplitDiff.Model m) {
        int n = m.chunks().size();
        counted = (n == 0 ? "no differences" : n == 1 ? "1 difference" : n + " differences") + (m.note().isEmpty() ? "" : " · " + m.note());
    }

    /** The differences of the text as edited, once typing pauses; dropped when more was typed meanwhile. */
    private void recompare() {
        if (shown == null || !shown.note().isEmpty()) {
            return;
        }
        long mine = ++asked;
        String before = diff.left().getText();
        String after = diff.right().getText();
        UiErrands.start("diff-compute", () -> {
            SplitDiff.Model m = SplitDiff.compute(before, after, DIFF_MILLIS);
            Edt.later(() -> {
                if (mine == asked) {
                    diff.remark(m);
                    count(m);
                    if (renderedShown()) {
                        renderSides();
                    }
                    updateButtons();
                }
            });
        });
    }

    /**
     * Writes the text as edited, when it was edited; completes with null once written (or when there was nothing to
     * write), else with why not, which a notice says too. A file removed from the working tree stays removed while its
     * text is empty.
     */
    public CompletableFuture<String> save() {
        if (!editable || !edited || change == null) {
            return CompletableFuture.completedFuture(null);
        }
        String text = diff.right().getText();
        edited = false;
        updateButtons();
        if (text.equals(saved) || !onDisk && text.isEmpty()) {
            say(text.equals(saved) ? Said.SAVED : Said.NOTHING);
            return CompletableFuture.completedFuture(null);
        }
        String r = root;
        RepoStatus.FileChange c = change;
        return writer.write(file(), text).thenApply(reason -> {
            Edt.later(() -> saved(r, c, text, reason));
            return reason;
        });
    }

    private void saved(String r, RepoStatus.FileChange c, String text, String reason) {
        boolean here = Objects.equals(root, r) && change != null && change.path().equals(c.path());
        if (reason != null) {
            intents.dispatch(Intent.Notify.logged(LOG, Notice.Level.ERROR, "Cannot save " + Path.of(c.path()).getFileName() + ": " + reason, null));
            if (here) {
                edited = true;
                updateButtons();
                say(Said.FAILED);
            }
            return;
        }
        if (here) {
            saved = text;
            onDisk = true;
            shown = new ChangeTexts(r, change, shown.before(), text, "");
            if (!edited) {
                say(Said.SAVED);
            }
        }
    }

    /** Drops the edits not yet written, as when the file is rolled back or removed from elsewhere. */
    public void discard() {
        edited = false;
        updateButtons();
        say(Said.NOTHING);
    }

    /** What the working tree's side says of its edits. */
    private enum Said { NOTHING, EDITED, SAVED, FAILED }

    private void say(Said said) {
        switch (said) {
            case NOTHING -> diff.rightStatus("", Theme.MUTED, SAVE_TIP);
            case EDITED -> diff.rightStatus("● edited", Theme.ATTENTION, SAVE_TIP);
            case SAVED -> diff.rightStatus("✓ saved", Theme.OK, SAVE_TIP);
            case FAILED -> diff.rightStatus("✗ not saved", Theme.DANGER, "Writing the file failed; the notice says why, and it is tried again");
        }
        modified.accept(said == Said.EDITED || said == Said.FAILED);
    }

    void undo() {
        RSyntaxTextArea r = diff.right();
        if (editable && r.canUndo()) {
            r.undoLastAction();
        }
        updateButtons();
    }

    void redo() {
        RSyntaxTextArea r = diff.right();
        if (editable && r.canRedo()) {
            r.redoLastAction();
        }
        updateButtons();
    }

    /**
     * A changed or removed file whose text is shown takes {@code HEAD}'s text as one edit, which undo takes back, is
     * saved and leaves the index. Any other goes back through git, after a question: its version to the Trash, and a
     * file {@code HEAD} does not have, or a renamed one, leaves the window.
     */
    void rollBack() {
        if (change == null) {
            return;
        }
        RepoStatus.Kind kind = change.kind();
        if (editable && (kind == RepoStatus.Kind.MODIFIED || kind == RepoStatus.Kind.DELETED)) {
            RSyntaxTextArea r = diff.right();
            r.beginAtomicEdit();
            try {
                r.replaceRange(diff.left().getText(), 0, r.getDocument().getLength());
            } finally {
                r.endAtomicEdit();
            }
            if (renderedShown()) {
                renderSides();
            }
            save();
            intents.dispatch(new Intent.Unstage(root, List.of(change.path())));
            return;
        }
        String name = Path.of(change.path()).getFileName().toString();
        boolean leaves = kind != RepoStatus.Kind.MODIFIED && kind != RepoStatus.Kind.DELETED && kind != RepoStatus.Kind.CONFLICTED;
        String question = kind == RepoStatus.Kind.UNTRACKED || kind == RepoStatus.Kind.ADDED
                ? name + " is not in HEAD: rolling it back moves it to the Trash."
                : "Roll back " + name + " to HEAD? The version you have goes to the Trash.";
        if (questions.ask(body, question, "Roll back", new Object[] {"Roll back", "Cancel"}) != 0) {
            return;
        }
        edited = false;
        intents.dispatch(new Intent.RollBack(root, List.of(change)));
        if (leaves) {
            gone.run();
        }
    }

    /** Moves the file to the Trash, after a question; the window closes. */
    void remove() {
        if (change == null || change.kind() == RepoStatus.Kind.DELETED) {
            return;
        }
        String name = Path.of(change.path()).getFileName().toString();
        if (questions.ask(body, "Move " + name + " to the Trash? It can be put back from there.", "Remove",
                new Object[] {"Move to Trash", "Cancel"}) != 0) {
            return;
        }
        edited = false;
        intents.dispatch(new Intent.RemoveFiles(root, List.of(change.path())));
        gone.run();
    }

    /** Switches a Markdown file between its texts compared and both sides rendered. */
    public void toggleRendered() {
        if (!markdown || shown == null || !shown.note().isEmpty()) {
            return;
        }
        rendering = !rendering;
        if (rendering) {
            renderSides();
        }
        cards.show(center, rendering ? "rendered" : "diff");
        showMode();
        updateButtons();
        if (!rendering) {
            diff.right().requestFocusInWindow();
        }
    }

    /** Whether the rendered sides show: chosen, and the file is Markdown. */
    private boolean renderedShown() {
        return rendering && markdown;
    }

    /**
     * Renders both sides, each shaded as its text is: the blocks whose lines the comparison found changed, and the words
     * inside them, red on {@code HEAD}'s side, green on the working tree's. A comparison not yet worked out for the
     * texts shown shades nothing.
     */
    private void renderSides() {
        Path folder = file().getParent();
        String before = diff.left().getText();
        String after = diff.right().getText();
        SplitDiff.Model m = diff.model();
        boolean current = m.compared() && m.before().equals(before) && m.after().equals(after);
        headRendered.show(before, folder, current ? marks(m, false) : MarkdownHtml.Marks.NONE);
        treeRendered.show(after, folder, current ? marks(m, true) : MarkdownHtml.Marks.NONE);
    }

    /** The lines and words of one side that {@code m} found changed: the newer side's when {@code gained}. */
    static MarkdownHtml.Marks marks(SplitDiff.Model m, boolean gained) {
        Set<Integer> lines = new HashSet<>();
        for (SplitDiff.Chunk c : m.chunks()) {
            for (int i = gained ? c.rightStart() : c.leftStart(); i < (gained ? c.rightEnd() : c.leftEnd()); i++) {
                lines.add(i);
            }
        }
        return new MarkdownHtml.Marks(lines, gained ? m.rightWords() : m.leftWords(), gained);
    }

    private void showMode() {
        mode.setVisible(markdown && shown != null && shown.note().isEmpty());
        mode.setIcon(Icons.get(rendering ? "edit" : "preview"));
        String tip = KeyText.of(rendering ? "Compare and edit the texts (⌘⇧M)" : "Show both sides rendered (⌘⇧M)");
        mode.setToolTipText(tip);
        mode.getAccessibleContext().setAccessibleName(rendering ? "Compare the texts" : "Show rendered");
    }

    private void updateButtons() {
        boolean text = shown != null && shown.note().isEmpty() && !renderedShown();
        previous.setEnabled(text);
        next.setEnabled(text);
        rollBack.setEnabled(change != null);
        remove.setEnabled(change != null && change.kind() != RepoStatus.Kind.DELETED);
        count.setText(counted);
    }

    private static boolean isMarkdown(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        return p.endsWith(".md") || p.endsWith(".markdown");
    }

    // ---- the parts

    private void build() {
        JPanel top = new JPanel(new BorderLayout());
        top.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_S, Theme.SPACE_M, Theme.SPACE_S, Theme.SPACE_S));
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        top.add(title, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        count.setForeground(Theme.MUTED_STRONG);
        count.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, Theme.SPACE_S));
        buttons.add(count);
        for (JButton b : List.of(previous, next, mode)) {
            buttons.add(b);
        }
        top.add(buttons, BorderLayout.EAST);
        note.setForeground(Theme.MUTED_STRONG);
        center.add(diff.component(), "diff");
        center.add(rendered(), "rendered");
        center.add(note, "note");
        body.add(top, BorderLayout.NORTH);
        body.add(center, BorderLayout.CENTER);

        diff.right().getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                typed();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                typed();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
            }
        });
        JPopupMenu menu = diff.right().getPopupMenu();
        if (menu != null) {
            menu.addSeparator();
            menu.add(rollBack);
            menu.add(remove);
        }
        int shortcut = Platform.menuShortcutMask();
        bind(diff.right(), KeyStroke.getKeyStroke(KeyEvent.VK_Z, shortcut), "comando-undo", this::undo);
        bind(diff.right(), KeyStroke.getKeyStroke(KeyEvent.VK_Z, shortcut | KeyEvent.SHIFT_DOWN_MASK), "comando-redo", this::redo);
        bind(diff.right(), KeyStroke.getKeyStroke(KeyEvent.VK_Y, shortcut), "comando-redo", this::redo);
    }

    private void typed() {
        if (loading) {
            return;
        }
        edited = true;
        recompare.request();
        updateButtons();
        say(Said.EDITED);
        // the undo manager hears of the edit after the document's listeners: Undo is offered once it has
        Edt.later(this::updateButtons);
    }

    /** The two rendered sides, each titled, scrolled together by the share of their length. */
    private JComponent rendered() {
        JPanel sides = new JPanel(new GridLayout(1, 2, 1, 0));
        sides.setBackground(Theme.CARD_BORDER);
        sides.add(titled("HEAD", headRendered.component()));
        sides.add(titled("Working tree", treeRendered.component()));
        BoundedRangeModel a = headRendered.scroll().getVerticalScrollBar().getModel();
        BoundedRangeModel b = treeRendered.scroll().getVerticalScrollBar().getModel();
        a.addChangeListener(e -> follow(a, b));
        b.addChangeListener(e -> follow(b, a));
        return sides;
    }

    private void follow(BoundedRangeModel from, BoundedRangeModel to) {
        if (following) {
            return;
        }
        following = true;
        try {
            int span = from.getMaximum() - from.getExtent() - from.getMinimum();
            double share = span <= 0 ? 0 : (from.getValue() - from.getMinimum()) / (double) span;
            to.setValue(to.getMinimum() + (int) Math.round(share * (to.getMaximum() - to.getExtent() - to.getMinimum())));
        } finally {
            following = false;
        }
    }

    private static JMenuItem menuItem(String text, String icon, Runnable r) {
        JMenuItem i = new JMenuItem(text, Icons.get(icon));
        i.addActionListener(e -> r.run());
        return i;
    }

    private static JButton arrow(String text, String tip, Runnable r) {
        JButton b = new JButton(text);
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.setToolTipText(tip);
        b.getAccessibleContext().setAccessibleName(tip);
        b.setFocusable(false);
        b.addActionListener(e -> r.run());
        return b;
    }

    private static JComponent titled(String name, JComponent c) {
        JPanel p = new JPanel(new BorderLayout());
        JLabel l = new JLabel(name);
        l.setForeground(Theme.MUTED_STRONG);
        l.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        p.add(l, BorderLayout.NORTH);
        p.add(c, BorderLayout.CENTER);
        return p;
    }

    private static void bind(JComponent c, KeyStroke key, String name, Runnable r) {
        c.getInputMap(JComponent.WHEN_FOCUSED).put(key, name);
        c.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        });
    }
}
