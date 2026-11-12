package dev.marregui.comando.ui.widgets.changes;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.BoundedRangeModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollBar;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

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
import dev.marregui.comando.ui.kit.icon.IconMenus;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.resource.ResourceActions;
import dev.marregui.comando.ui.kit.text.HtmlPane;
import dev.marregui.comando.ui.kit.text.MarkdownHtml;
import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.kit.text.RenderedMarkdown;
import dev.marregui.comando.ui.kit.text.SplitDiff;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.vcs.RepoChanges;
import dev.marregui.comando.ui.widget.Unmountable;

/**
 * A changed file beside its {@code HEAD} version, the comparison window's body: the two texts side by side with their
 * differences, or, for Markdown, both rendered as a panel's preview renders them, level on the blocks both keep: a
 * block one side lacks leaves a gap there of its height, and the sides scroll together. The working tree's side may be
 * edited: the differences are worked out again as it changes, undo and redo go back and forth through every edit made
 * here (⌘Z, ⌘⇧Z), and the text is written every second while it has edits, and on leaving the file (another file,
 * closing the window, quitting comando); beside the side's title it says "edited" until written, then "saved". Edits
 * whose write failed as the window left their file are kept and written again every second until a write succeeds;
 * the file shown again meanwhile shows them, not yet written. Roll
 * back, in that side's menu, puts {@code HEAD}'s text in its place, as one edit that undo takes back, saves it and
 * takes the file out of the index; a file {@code HEAD} does not have, or one git renamed, goes back through git, its
 * version to the Trash. Remove moves the file to the Trash, from the same menu. A read of the file while it has edits
 * not yet written is left unshown: the edits win. EDT only.
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
    /**
     * How long a side's size must hold before the sides are laid level again. Unmeasured: longer than the 150 ms
     * {@link HtmlPane} waits to size its pictures again, so its redraw comes first.
     */
    static final int LEVEL_MILLIS = 200;
    /** How far apart, in pixels, the same block may sit on the two sides: a gap of a pixel is no block's height. */
    private static final int LEVEL_SLACK = 1;
    /**
     * How many times gaps are moved in one levelling: the first opens every gap, the next take up what a gap's height
     * missed. A page of 50 blocks, rows and items came level within three.
     */
    static final int LEVEL_PASSES = 3;
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
    private final Debouncer relevel;
    private final Timer autosave;
    /** Stops hearing the theme chosen; the window is gone. */
    private final Runnable stopHearingTheme;
    /** What follows the levelling under way: the view put back where a render wants it. */
    private Runnable afterLevel = () -> {
    };
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
    /** Whether the reader scrolled a rendered side, by wheel, bar or keys, since the sides were last rendered. */
    private boolean readerScrolled;
    /**
     * How far the reader's own scrolls moved {@code HEAD}'s and the working tree's rendered sides since they were last
     * rendered: moves made while the reader's input is dispatched, the other side's following included. The page's
     * own moves, the caret's scroll among them, are not in it.
     */
    private int headReaderMoved;
    private int treeReaderMoved;
    /**
     * Where a thumb the reader placed put each side since they were last rendered, the reader's movement counted from
     * there; -1 when none did.
     */
    private int headPlaced = -1;
    private int treePlaced = -1;
    /** Where each side's bar was last seen, so a move is measured from there. */
    private int headSeen;
    private int treeSeen;
    /** The gaps open on {@code HEAD}'s rendered side, in points, by the line of the block below each. */
    private final Map<Integer, Integer> headGaps = new HashMap<>();
    /** The gaps open on the working tree's rendered side. */
    private final Map<Integer, Integer> treeGaps = new HashMap<>();
    /** The heights of {@code HEAD}'s blocks as last levelled, by line: while they hold, the sides are level. */
    private Map<Integer, Integer> headHeights = Map.of();
    /** The heights of the working tree's blocks as last levelled. */
    private Map<Integer, Integer> treeHeights = Map.of();
    /** Where the blocks both sides keep sit on {@code HEAD}'s side, in order; empty until levelled. */
    private int[] headAnchors = {};
    /** Where those blocks sit on the working tree's side. */
    private int[] treeAnchors = {};
    /** Counts the comparisons asked for; only the newest is shown when its work ends. */
    private long asked;
    /** Edits of files no longer shown whose write failed, by file: written again every second until one succeeds. */
    private final Map<Path, Unsaved> unsaved = new LinkedHashMap<>();
    /** The files of {@link #unsaved} being written now, so a slow write is not started again on top of itself. */
    private final Set<Path> writing = new HashSet<>();

    /** The edits of a file no longer shown, not yet written. */
    private record Unsaved(String root, RepoStatus.FileChange change, String text) {
    }
    private String counted = "";

    public ChangeEditor(final IntentSink intents, final Writer writer, final ResourceActions.Questions questions) {
        this.intents = intents;
        this.writer = writer;
        this.questions = questions;
        previous = arrow("↑", KeyText.of("Previous difference (⇧F7)"), diff::previous);
        next = arrow("↓", "Next difference (F7)", diff::next);
        rollBack = IconMenus.item("Roll back to HEAD", "rollback", this::rollBack);
        remove = IconMenus.item("Move to the Trash…", "trash", this::remove);
        mode = ToolButton.of("preview", "", this::toggleRendered);
        recompare = new Debouncer(RECOMPARE_MILLIS, this::recompare);
        relevel = new Debouncer(LEVEL_MILLIS, this::level);
        autosave = new Timer(AUTOSAVE_MILLIS, e -> {
            if (edited) {
                save();
            }
            writeUnsaved();
        });
        autosave.start();
        build();
        showMode();
        updateButtons();
        stopHearingTheme = Theme.onTint(this::shadeAgain);
    }

    public JComponent component() {
        return body;
    }

    /** What runs when the file shown goes: removed, or rolled back out of the working tree; the window closes. */
    public void onGone(final Runnable gone) {
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

    /** For tests: what the header says of the file shown. */
    String titleShown() {
        return title.getText();
    }

    /** For tests: whether the switch to the rendered sides is offered, and whether Roll back and Remove are on. */
    boolean offered(final String action) {
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
    public void onModified(final Consumer<Boolean> modified) {
        this.modified = modified;
    }

    /** Stops writing every second; the window is gone. */
    @Override
    public void unmount() {
        autosave.stop();
        recompare.stop();
        relevel.stop();
        stopHearingTheme.run();
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
    public void show(final ChangeTexts texts, final boolean again) {
        if (again && edited) {
            return;
        }
        if (!again) {
            save();
        }
        final RepoStatus.FileChange c = texts.change();
        // edits of this file whose write failed while another file was shown come back as edits still to write
        final Unsaved kept = again ? null : unsaved.remove(Path.of(texts.root()).resolve(c.path()));
        final boolean same = again && texts.note().isEmpty() && diff.left().getText().equals(texts.before())
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
        // the window's title names the file
        title.setText(Plain.text(RepoChanges.describe(c.kind()) + (c.from().isEmpty() ? "" : " from " + c.from())
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
        final long mine = ++asked;
        final String before = texts.before();
        final String after = kept == null ? texts.after() : kept.text();
        UiErrands.start("diff-compute", () -> {
            final SplitDiff.Model m = SplitDiff.compute(before, after, DIFF_MILLIS);
            Edt.later(() -> {
                if (mine == asked) {
                    apply(c, m, again, kept);
                } else if (kept != null) {
                    unsaved.putIfAbsent(Path.of(kept.root()).resolve(kept.change().path()), kept);
                }
            });
        });
    }

    /** Shows a comparison worked out for {@code c}; {@code kept}, when not null, its edits not yet written. */
    private void apply(final RepoStatus.FileChange c, final SplitDiff.Model m, final boolean again,
                       final Unsaved kept) {
        if (!m.compared()) {
            if (kept != null) {
                // not editable here: the edits wait to be written as they did
                unsaved.putIfAbsent(Path.of(kept.root()).resolve(kept.change().path()), kept);
            }
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
        if (kept != null) {
            edited = true;
            say(Said.FAILED);
        }
        showMode();
        if (renderedShown()) {
            renderSides(again);
        }
        cards.show(center, renderedShown() ? "rendered" : "diff");
        updateButtons();
    }

    private void count(final SplitDiff.Model m) {
        final int n = m.chunks().size();
        counted = (n == 0 ? "no differences" : n == 1 ? "1 difference" : n + " differences") + (m.note().isEmpty() ? "" : " · " + m.note());
    }

    /** The differences of the text as edited, once typing pauses; dropped when more was typed meanwhile. */
    private void recompare() {
        if (shown == null || !shown.note().isEmpty()) {
            return;
        }
        final long mine = ++asked;
        final String before = diff.left().getText();
        final String after = diff.right().getText();
        UiErrands.start("diff-compute", () -> {
            final SplitDiff.Model m = SplitDiff.compute(before, after, DIFF_MILLIS);
            Edt.later(() -> {
                if (mine == asked) {
                    diff.remark(m);
                    count(m);
                    if (renderedShown()) {
                        renderSides(true);
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
        final String text = diff.right().getText();
        edited = false;
        updateButtons();
        if (text.equals(saved) || !onDisk && text.isEmpty()) {
            say(text.equals(saved) ? Said.SAVED : Said.NOTHING);
            return CompletableFuture.completedFuture(null);
        }
        final String r = root;
        final RepoStatus.FileChange c = change;
        return writer.write(file(), text).thenApply(reason -> {
            Edt.later(() -> saved(r, c, text, reason));
            return reason;
        });
    }

    private void saved(final String r, final RepoStatus.FileChange c, final String text, final String reason) {
        final boolean here = Objects.equals(root, r) && change != null && change.path().equals(c.path());
        if (reason != null) {
            intents.dispatch(Intent.Notify.logged(LOG, Notice.Level.ERROR, "Cannot save " + Path.of(c.path()).getFileName() + ": " + reason, null));
            if (here) {
                edited = true;
                updateButtons();
                say(Said.FAILED);
            } else {
                // the window shows another file now: the edits wait, and are written again every second
                unsaved.putIfAbsent(Path.of(r).resolve(c.path()), new Unsaved(r, c, text));
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

    /**
     * Writes the edits of the file shown and every edit of another file still waiting to be written; completes with
     * null once all are written, else with why the first was not. For quitting.
     */
    public CompletableFuture<String> saveAll() {
        final CompletableFuture<String> mine = save();
        final CompletableFuture<String> others = writeUnsaved();
        return mine.thenCombine(others, (a, b) -> a != null ? a : b);
    }

    /** Drops the edits not yet written, as when the file is rolled back or removed from elsewhere. */
    public void discard() {
        edited = false;
        updateButtons();
        say(Said.NOTHING);
    }

    /** Drops the edits waiting to be written of {@code paths} in {@code root}, changed elsewhere. */
    public void forget(final String root, final Collection<String> paths) {
        for (final String p : paths) {
            unsaved.remove(Path.of(root).resolve(p));
        }
    }

    /**
     * Writes again the edits of files no longer shown whose write failed; one that succeeds is done, one that fails
     * again waits for the next second, with a notice as each failed write of the file shown has.
     */
    private CompletableFuture<String> writeUnsaved() {
        final List<CompletableFuture<String>> writes = new ArrayList<>();
        for (final Map.Entry<Path, Unsaved> e : List.copyOf(unsaved.entrySet())) {
            final Path file = e.getKey();
            final Unsaved u = e.getValue();
            if (!writing.add(file)) {
                continue;
            }
            writes.add(writer.write(file, u.text()).thenApply(reason -> {
                Edt.later(() -> {
                    writing.remove(file);
                    if (reason == null) {
                        unsaved.remove(file, u);
                    } else {
                        intents.dispatch(Intent.Notify.logged(LOG, Notice.Level.ERROR,
                                "Cannot save " + file.getFileName() + ": " + reason, null));
                    }
                });
                return reason;
            }));
        }
        return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new))
                .thenApply(x -> writes.stream().map(CompletableFuture::join).filter(Objects::nonNull)
                        .findFirst().orElse(null));
    }

    /** What the working tree's side says of its edits. */
    private enum Said { NOTHING, EDITED, SAVED, FAILED }

    private void say(final Said said) {
        switch (said) {
            case NOTHING -> diff.rightStatus("", Theme.MUTED, SAVE_TIP);
            case EDITED -> diff.rightStatus("● edited", Theme.ATTENTION, SAVE_TIP);
            case SAVED -> diff.rightStatus("✓ saved", Theme.OK, SAVE_TIP);
            case FAILED -> diff.rightStatus("✗ not saved", Theme.DANGER, "Writing the file failed; the notice says why, and it is tried again");
        }
        modified.accept(said == Said.EDITED || said == Said.FAILED);
    }

    void undo() {
        final RSyntaxTextArea r = diff.right();
        if (editable && r.canUndo()) {
            r.undoLastAction();
        }
        updateButtons();
    }

    void redo() {
        final RSyntaxTextArea r = diff.right();
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
        final RepoStatus.Kind kind = change.kind();
        if (editable && (kind == RepoStatus.Kind.MODIFIED || kind == RepoStatus.Kind.DELETED)) {
            final RSyntaxTextArea r = diff.right();
            r.beginAtomicEdit();
            try {
                r.replaceRange(diff.left().getText(), 0, r.getDocument().getLength());
            } finally {
                r.endAtomicEdit();
            }
            if (renderedShown()) {
                renderSides(true);
            }
            save();
            intents.dispatch(new Intent.Unstage(root, List.of(change.path())));
            return;
        }
        final String name = Path.of(change.path()).getFileName().toString();
        final boolean leaves = kind != RepoStatus.Kind.MODIFIED && kind != RepoStatus.Kind.DELETED && kind != RepoStatus.Kind.CONFLICTED;
        final String question = kind == RepoStatus.Kind.UNTRACKED || kind == RepoStatus.Kind.ADDED
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
        final String name = Path.of(change.path()).getFileName().toString();
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
            renderSides(false);
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
     * Renders both sides, each shaded as its text is: the blocks whose lines the comparison found changed, and the
     * words inside them, red on {@code HEAD}'s side, green on the working tree's. A comparison not yet worked out for
     * the texts shown shades nothing and lies level on nothing: the sides scroll by the share of their length. The view
     * stays where it was when {@code keepPlace}, else starts from the top. Levelling follows in events of its own.
     */
    private void renderSides(final boolean keepPlace) {
        final Path folder = file().getParent();
        final String before = diff.left().getText();
        final String after = diff.right().getText();
        final SplitDiff.Model m = diff.model();
        final boolean current = comparisonShown();
        final JScrollBar headBar = headRendered.scroll().getVerticalScrollBar();
        final JScrollBar treeBar = treeRendered.scroll().getVerticalScrollBar();
        final int headAt = keepPlace ? headBar.getValue() : 0;
        final int treeAt = keepPlace ? treeBar.getValue() : 0;
        final MarkdownHtml.Anchors anchors = current ? MarkdownHtml.Anchors.gaps(Map.of()) : MarkdownHtml.Anchors.NONE;
        headGaps.clear();
        treeGaps.clear();
        headHeights = Map.of();
        treeHeights = Map.of();
        headAnchors = new int[0];
        treeAnchors = new int[0];
        headRendered.show(before, folder, current ? marks(m, false) : MarkdownHtml.Marks.NONE, anchors);
        treeRendered.show(after, folder, current ? marks(m, true) : MarkdownHtml.Marks.NONE, anchors);
        // a new page shows its top, and opening a gap keeps the view where it is: the view goes back once they are
        // open, unless the reader scrolled meanwhile, whose place wins
        readerScrolled = false;
        headReaderMoved = 0;
        treeReaderMoved = 0;
        headPlaced = -1;
        treePlaced = -1;
        afterLevel = () -> {
            if (!readerScrolled) {
                headBar.setValue(headAt);
                treeBar.setValue(treeAt);
            }
        };
        // levelling starts behind the events the render queued, the new page's first layout and paint among them: a
        // scroll queued while they ran comes before the first gap opens, which keeps the place it scrolled to. A place
        // kept is shown again before then, so a scroll made while the sides level starts from it
        Edt.later(() -> {
            if (keepPlace) {
                // a side the reader scrolled before the place is shown again, before or after the caret's scroll moved
                // it: the place, or where the reader placed the thumb, plus how far the reader moved it
                keep(headBar, (headPlaced >= 0 ? headPlaced : headAt) + headReaderMoved, treeBar,
                        (treePlaced >= 0 ? treePlaced : treeAt) + treeReaderMoved);
            }
            Edt.later(this::level);
        });
    }

    /** Puts each side's view at its place, neither following the other: until the sides level, no block lies level. */
    private void keep(final JScrollBar headBar, final int headAt, final JScrollBar treeBar, final int treeAt) {
        following = true;
        try {
            headBar.setValue(headAt);
            treeBar.setValue(treeAt);
        } finally {
            following = false;
        }
    }

    /** Whether the comparison worked out is of the texts shown. */
    private boolean comparisonShown() {
        final SplitDiff.Model m = diff.model();
        return m.compared() && m.before().equals(diff.left().getText()) && m.after().equals(diff.right().getText());
    }

    /**
     * Lays the rendered sides level: measures where the blocks both keep sit, and opens a gap above each that sits
     * higher than its match. A block one side lacks is what stands between, so the gap is that block's height. What a
     * gap's height missed is taken up by measuring again; a later change of width or a picture arriving, by coming back
     * here. A page with no size yet is levelled once it has one.
     */
    private void level() {
        level(LEVEL_PASSES);
    }

    /**
     * The passes of levelling, each in an event of its own: each draws a page again, and on a long page the passes in
     * one event would hold the EDT past its budget. {@code passes} are left.
     */
    private void level(final int passes) {
        if (levelled(passes)) {
            final Runnable then = afterLevel;
            afterLevel = () -> {
            };
            then.run();
        } else {
            Edt.later(() -> level(passes - 1));
        }
    }

    /** One pass of levelling, one of {@code passes} left; true when no other is needed. */
    private boolean levelled(final int passes) {
        if (!renderedShown() || !comparisonShown()) {
            return true;
        }
        final HtmlPane head = headRendered.pane();
        final HtmlPane tree = treeRendered.pane();
        final NavigableMap<Integer, Integer> a = head.anchorHeights();
        final NavigableMap<Integer, Integer> b = tree.anchorHeights();
        if (a.isEmpty() || b.isEmpty() || (a.equals(headHeights) && b.equals(treeHeights))) {
            return true;
        }
        final int end = MarkdownHtml.Anchors.END;
        final List<SplitDiff.Pair> pairs =
                new ArrayList<>(SplitDiff.pairs(diff.model().chunks(), a.headMap(end).keySet(), b.keySet()));
        pairs.add(new SplitDiff.Pair(end, end));
        final Map<Integer, Integer> headBefore = Map.copyOf(headGaps);
        final Map<Integer, Integer> treeBefore = Map.copyOf(treeGaps);
        if (passes > 0 && moveGaps(pairs, a, b)) {
            if (!headGaps.equals(headBefore)) {
                head.anchors(MarkdownHtml.Anchors.gaps(headGaps));
            }
            if (!treeGaps.equals(treeBefore)) {
                tree.anchors(MarkdownHtml.Anchors.gaps(treeGaps));
            }
            return false;
        }
        headHeights = a;
        treeHeights = b;
        headAnchors = pairs.stream().mapToInt(p -> a.get(p.left())).toArray();
        treeAnchors = pairs.stream().mapToInt(p -> b.get(p.right())).toArray();
        return true;
    }

    /**
     * Moves the gaps so each of {@code pairs} sits level, from the top of the page, given the heights {@code a} and
     * {@code b} measured with the gaps as they stand; false when each already is.
     */
    private boolean moveGaps(final List<SplitDiff.Pair> pairs, final Map<Integer, Integer> a,
                             final Map<Integer, Integer> b) {
        // how far the gaps moved so far have lowered HEAD's side against the working tree's
        int moved = 0;
        boolean any = false;
        for (final SplitDiff.Pair p : pairs) {
            final int apart = a.get(p.left()) - b.get(p.right()) + moved;
            if (Math.abs(apart) > LEVEL_SLACK) {
                if (apart > 0) {
                    moveGap(headGaps, p.left(), treeGaps, p.right(), apart);
                } else {
                    moveGap(treeGaps, p.right(), headGaps, p.left(), -apart);
                }
                moved -= apart;
                any = true;
            }
        }
        return any;
    }

    /**
     * Brings a block {@code by} points back to its match: out of the gap above the block on the {@code lower} side as
     * far as there is one, the rest into the gap above its match on the {@code higher} side.
     */
    private static void moveGap(final Map<Integer, Integer> lower, final int lowerLine,
                                final Map<Integer, Integer> higher, final int higherLine, final int by) {
        final int open = lower.getOrDefault(lowerLine, 0);
        final int taken = Math.min(open, by);
        if (open == taken) {
            lower.remove(lowerLine);
        } else {
            lower.put(lowerLine, open - taken);
        }
        if (by > taken) {
            higher.merge(higherLine, by - taken, Integer::sum);
        }
    }

    /** The theme chosen shades the comparison on screen: the texts' marks and the rendered sides are drawn again. */
    private void shadeAgain() {
        diff.remark(diff.model());
        headRendered.pane().followTheme();
        treeRendered.pane().followTheme();
    }

    /** The lines and words of one side that {@code m} found changed: the newer side's when {@code gained}. */
    static MarkdownHtml.Marks marks(final SplitDiff.Model m, final boolean gained) {
        final Set<Integer> lines = new HashSet<>();
        for (final SplitDiff.Chunk c : m.chunks()) {
            for (int i = gained ? c.rightStart() : c.leftStart(); i < (gained ? c.rightEnd() : c.leftEnd()); i++) {
                lines.add(i);
            }
        }
        return new MarkdownHtml.Marks(lines, gained ? m.rightWords() : m.leftWords(), gained);
    }

    private void showMode() {
        mode.setVisible(markdown && shown != null && shown.note().isEmpty());
        mode.setIcon(Icons.get(rendering ? "edit" : "preview"));
        final String tip = KeyText.of(rendering ? "Compare and edit the texts (⌘⇧M)" : "Show both sides rendered (⌘⇧M)");
        mode.setToolTipText(tip);
        mode.getAccessibleContext().setAccessibleName(rendering ? "Compare the texts" : "Show rendered");
    }

    private void updateButtons() {
        final boolean text = shown != null && shown.note().isEmpty() && !renderedShown();
        previous.setEnabled(text);
        next.setEnabled(text);
        rollBack.setEnabled(change != null);
        remove.setEnabled(change != null && change.kind() != RepoStatus.Kind.DELETED);
        count.setText(counted);
    }

    private static boolean isMarkdown(final String path) {
        final String p = path.toLowerCase(Locale.ROOT);
        return p.endsWith(".md") || p.endsWith(".markdown");
    }

    // ---- the parts

    private void build() {
        final JPanel top = new JPanel(new BorderLayout());
        top.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_S, Theme.SPACE_M, Theme.SPACE_S, Theme.SPACE_S));
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        top.add(title, BorderLayout.CENTER);
        final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        count.setForeground(Theme.MUTED_STRONG);
        count.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, Theme.SPACE_S));
        buttons.add(count);
        for (final JButton b : List.of(previous, next, mode)) {
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
            public void insertUpdate(final DocumentEvent e) {
                typed();
            }

            @Override
            public void removeUpdate(final DocumentEvent e) {
                typed();
            }

            @Override
            public void changedUpdate(final DocumentEvent e) {
            }
        });
        final JPopupMenu menu = diff.right().getPopupMenu();
        if (menu != null) {
            menu.addSeparator();
            menu.add(rollBack);
            menu.add(remove);
        }
        final int shortcut = Platform.menuShortcutMask();
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

    /** The two rendered sides, each titled, scrolled together on the blocks both keep. */
    private JComponent rendered() {
        final JPanel sides = new JPanel(new GridLayout(1, 2, 1, 0));
        sides.setBackground(Theme.CARD_BORDER);
        sides.add(titled("HEAD", headRendered.component()));
        sides.add(titled("Working tree", treeRendered.component()));
        final BoundedRangeModel a = headRendered.scroll().getVerticalScrollBar().getModel();
        final BoundedRangeModel b = treeRendered.scroll().getVerticalScrollBar().getModel();
        headSeen = a.getValue();
        treeSeen = b.getValue();
        a.addChangeListener(e -> {
            final int now = a.getValue();
            // a press that only sets the bar adjusting moves nothing
            if (now != headSeen && EventQueue.getCurrentEvent() instanceof InputEvent input) {
                if (HtmlPane.placesTheThumb(input)) {
                    headPlaced = now;
                    headReaderMoved = 0;
                } else {
                    headReaderMoved += now - headSeen;
                }
            }
            headSeen = now;
            follow(a, b);
        });
        b.addChangeListener(e -> {
            final int now = b.getValue();
            // a press that only sets the bar adjusting moves nothing
            if (now != treeSeen && EventQueue.getCurrentEvent() instanceof InputEvent input) {
                if (HtmlPane.placesTheThumb(input)) {
                    treePlaced = now;
                    treeReaderMoved = 0;
                } else {
                    treeReaderMoved += now - treeSeen;
                }
            }
            treeSeen = now;
            follow(b, a);
        });
        // the reader's own scrolls, told from the moves levelling makes: a scroll by wheel or trackpad, on the bar, or
        // by the keys that move the page up or down, which holds the keyboard; a copy or any other key moves nothing
        final MouseAdapter onBar = new MouseAdapter() {
            @Override
            public void mousePressed(final MouseEvent e) {
                readerScrolled = true;
            }
        };
        final KeyAdapter onKeys = new KeyAdapter() {
            @Override
            public void keyPressed(final KeyEvent e) {
                readerScrolled |= switch (e.getKeyCode()) {
                    case KeyEvent.VK_UP, KeyEvent.VK_DOWN, KeyEvent.VK_PAGE_UP, KeyEvent.VK_PAGE_DOWN, KeyEvent.VK_HOME,
                         KeyEvent.VK_END -> true;
                    default -> false;
                };
            }
        };
        for (final RenderedMarkdown side : List.of(headRendered, treeRendered)) {
            side.scroll().addMouseWheelListener(_ -> readerScrolled = true);
            side.scroll().getVerticalScrollBar().addMouseListener(onBar);
            side.pane().addKeyListener(onKeys);
        }
        // first shown, at another width, or grown by the pictures that arrived: the blocks have moved
        final ComponentAdapter resized = new ComponentAdapter() {
            @Override
            public void componentResized(final ComponentEvent e) {
                relevel.request();
            }
        };
        headRendered.pane().addComponentListener(resized);
        treeRendered.pane().addComponentListener(resized);
        return sides;
    }

    private void follow(final BoundedRangeModel from, final BoundedRangeModel to) {
        if (following) {
            return;
        }
        following = true;
        try {
            final boolean fromHead = from == headRendered.scroll().getVerticalScrollBar().getModel();
            final int value = from.getValue() - from.getMinimum();
            final int[] here = fromHead ? headAnchors : treeAnchors;
            final int[] there = fromHead ? treeAnchors : headAnchors;
            to.setValue(to.getMinimum() + across(here, there, value, span(from), span(to)));
        } finally {
            following = false;
        }
    }

    private static int span(final BoundedRangeModel m) {
        return m.getMaximum() - m.getExtent() - m.getMinimum();
    }

    /**
     * Where a scroll to {@code value} on one side falls on the other: at one of the blocks both keep, {@code here} on
     * this side and {@code there} on the other, at that block; between two, the same share of the way. Each end of the
     * scroll is one more such place, so with no block the position is the same share of the length. A block past what
     * either side can scroll to, or out of order, is passed over.
     */
    static int across(final int[] here, final int[] there, final int value, final int fromSpan, final int toSpan) {
        if (fromSpan <= 0) {
            return 0;
        }
        int x = 0;
        int y = 0;
        for (int i = 0; i <= here.length; i++) {
            final int nextX = i < here.length ? here[i] : fromSpan;
            final int nextY = i < here.length ? there[i] : toSpan;
            if (nextX > x && nextX <= fromSpan && nextY >= y && nextY <= toSpan) {
                if (value <= nextX) {
                    return y + (int) Math.round((value - x) * (double) (nextY - y) / (nextX - x));
                }
                x = nextX;
                y = nextY;
            }
        }
        return toSpan;
    }

    private static JButton arrow(final String text, final String tip, final Runnable r) {
        final JButton b = new JButton(text);
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.setToolTipText(tip);
        b.getAccessibleContext().setAccessibleName(tip);
        b.setFocusable(false);
        b.addActionListener(e -> r.run());
        return b;
    }

    private static JComponent titled(final String name, final JComponent c) {
        final JPanel p = new JPanel(new BorderLayout());
        final JLabel l = new JLabel(name);
        l.setForeground(Theme.MUTED_STRONG);
        l.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        p.add(l, BorderLayout.NORTH);
        p.add(c, BorderLayout.CENTER);
        return p;
    }

    /** Runs {@code r} on {@code key} while {@code c} has the keyboard; the Changes view's tree binds its keys so too. */
    static void bind(final JComponent c, final KeyStroke key, final String name, final Runnable r) {
        c.getInputMap(JComponent.WHEN_FOCUSED).put(key, name);
        c.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent e) {
                r.run();
            }
        });
    }
}
