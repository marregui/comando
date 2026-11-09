package dev.marregui.comando.ui.panels;

import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.schema.SchemaRegistry;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.ui.kit.text.HitMarks;
import dev.marregui.comando.ui.kit.theme.Theme;

import static org.assertj.core.api.Assertions.assertThat;

/// How [EditorPanel] marks a search's hits in its text (record 141).
/// - every hit of the file is marked on its line, ASCII letters in either case, the current one in its own colour
/// - the current mark is the occurrence nearest where the snippet puts the match, the caret on it
/// - a hit on the file's name marks nothing and shows the top; a Markdown document shows its text, where the marks are
/// - the marks go when the text is edited or cleared
/// - the user's edit of the preview tab keeps it, asked once; the file loaded or reloaded from disk does not
class EditorPanelHitsTest {

    private final List<Intent> sent = new CopyOnWriteArrayList<>();

    private EditorPanel panel(final PanelState state, final Consumer<EditorPanel> setUp) throws Exception {
        final EditorPanel[] p = new EditorPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            p[0] = new EditorPanel(state, sent::add, new SchemaRegistry());
            setUp.accept(p[0]);
        });
        return p[0];
    }

    private EditorPanel withText(final String text) throws Exception {
        return panel(new PanelState(new PanelId("notes"), "notes", "", "", true), p -> {
            p.editor().load(text);
            p.file(Path.of("/s/notes.txt"), true);
        });
    }

    /// The hit as a search answers it: `snippet` the line without its indent, the match at `matchStart`.
    private static HitMarks.Hit hit(final int line, final String snippet, final int matchStart, final int length) {
        return new HitMarks.Hit(line, snippet.substring(matchStart, matchStart + length), matchStart,
                snippet.substring(0, matchStart));
    }

    private static List<List<Integer>> marks(final EditorPanel p) throws Exception {
        final List<List<Integer>> out = new CopyOnWriteArrayList<>();
        SwingUtilities.invokeAndWait(() -> p.hitMarks().forEach(m -> out.add(List.of(m[0], m[1]))));
        return out;
    }

    private static void show(final EditorPanel p, final HitMarks marks) throws Exception {
        SwingUtilities.invokeAndWait(() -> p.showHits(marks));
        SwingUtilities.invokeAndWait(() -> { });
    }

    /// Two hits of one file: each occurrence on their lines is marked, upper case as lower, and the current hit's own,
    /// the second on its line, is current with the caret on it, painted in the current colour.
    @Test
    void everyHitIsMarkedAndTheCurrentIsTheOneItsSnippetPointsAt() throws Exception {
        final EditorPanel p = withText("alpha\nfoo bar FOO\nbaz foo\n");

        show(p, new HitMarks(List.of(hit(3, "baz foo", 4, 3), hit(2, "foo bar FOO", 8, 3)), 1));

        assertThat(marks(p)).containsExactly(List.of(6, 9), List.of(14, 17), List.of(22, 25));
        assertThat(onEdt(p::currentHit)).isEqualTo(14);
        assertThat(onEdt(() -> p.editor().area().getCaretPosition())).isEqualTo(14);
        assertThat(onEdt(() -> colourAt(p, 14))).isSameAs(Theme.FIND_CURRENT);
        assertThat(onEdt(() -> colourAt(p, 6))).isSameAs(Theme.FIND_MATCH);
        assertThat(onEdt(() -> colourAt(p, 22))).isSameAs(Theme.FIND_MATCH);
    }

    /// The snippet drops the line's indent: the current mark is still the occurrence its text points at.
    @Test
    void anIndentedLineMarksTheOccurrenceTheSnippetPointsAt() throws Exception {
        final EditorPanel p = withText("        call(x); call(y);\n");

        show(p, new HitMarks(List.of(hit(1, "call(x); call(y);", 9, 4)), 0));

        assertThat(marks(p)).containsExactly(List.of(8, 12), List.of(17, 21));
        assertThat(onEdt(p::currentHit)).isEqualTo(17);
    }

    /// Without the text before the match, the occurrence nearest its column past the indent is current.
    @Test
    void theOccurrenceNearestTheColumnIsCurrent() throws Exception {
        final EditorPanel p = withText("  x = a; y = a; z = a;\n");

        show(p, new HitMarks(List.of(new HitMarks.Hit(1, "a", 12, "")), 0));

        assertThat(marks(p)).containsExactly(List.of(6, 7), List.of(13, 14), List.of(20, 21));
        assertThat(onEdt(p::currentHit)).isEqualTo(13);
    }

    /// ASCII letters match in either case, as the search finds them.
    @Test
    void lettersMatchInEitherCase() throws Exception {
        final EditorPanel p = withText("FOO foo fOo\n");

        show(p, new HitMarks(List.of(hit(1, "FOO foo fOo", 4, 3)), 0));

        assertThat(marks(p)).containsExactly(List.of(0, 3), List.of(4, 7), List.of(8, 11));
        assertThat(onEdt(p::currentHit)).isEqualTo(4);
    }

    /// The current hit comes to the middle of the view.
    @Test
    void theCurrentHitComesToTheMiddle() throws Exception {
        final StringBuilder lines = new StringBuilder();
        for (int i = 1; i <= 300; i++) {
            lines.append("line ").append(i).append('\n');
        }
        final EditorPanel p = withText(lines.toString());
        SwingUtilities.invokeAndWait(() -> {
            p.setSize(600, 300);
            layOut(p);
        });

        show(p, new HitMarks(List.of(hit(200, "line 200", 5, 3)), 0));

        final Rectangle seen = onEdt(() -> ((JViewport) SwingUtilities.getAncestorOfClass(JViewport.class,
                p.editor().area())).getViewRect());
        final int caretY = onEdt(() -> (int) p.editor().area().modelToView2D(p.currentHit()).getY());
        final int lineHeight = onEdt(() -> p.editor().area().getLineHeight());
        assertThat(caretY).as("caret " + caretY + " in " + seen)
                .isBetween(seen.y + seen.height / 2 - lineHeight * 2, seen.y + seen.height / 2 + lineHeight * 2);
    }

    /// A hit on the file's name marks nothing and puts the caret at the top; a line asked for alone gets the caret.
    @Test
    void aNameHitShowsTheTopAndALineGetsTheCaret() throws Exception {
        final EditorPanel p = withText("one\ntwo\nthree\n");
        SwingUtilities.invokeAndWait(() -> p.editor().area().setCaretPosition(9));

        show(p, new HitMarks(List.of(new HitMarks.Hit(0, "notes", -1, "")), 0));

        assertThat(marks(p)).isEmpty();
        assertThat(onEdt(p::currentHit)).isEqualTo(-1);
        assertThat(onEdt(() -> p.editor().area().getCaretPosition())).isZero();

        show(p, HitMarks.line(3));

        assertThat(onEdt(() -> p.editor().area().getCaretPosition())).isEqualTo(8);
    }

    /// A Markdown document opens rendered, but its hits are marked in its text, which it then shows.
    @Test
    void aMarkdownDocumentShowsItsTextWithTheHits() throws Exception {
        final EditorPanel p = panel(new PanelState(new PanelId("plan"), "plan.md", "", "", true), e -> {
            e.editor().load("# Plan\n\nthe budget stays\n");
            e.file(Path.of("/s/plan.md"), true);
        });
        assertThat(onEdt(p::previewing)).isTrue();

        show(p, new HitMarks(List.of(hit(3, "the budget stays", 4, 6)), 0));

        assertThat(onEdt(p::previewing)).isFalse();
        assertThat(marks(p)).containsExactly(List.of(12, 18));
    }

    /// Typing takes the marks off, as does clearing them.
    @Test
    void anEditTakesTheMarksOff() throws Exception {
        final EditorPanel p = withText("foo foo\n");
        final int before = onEdt(() -> painted(p));
        show(p, new HitMarks(List.of(hit(1, "foo foo", 0, 3)), 0));
        assertThat(onEdt(() -> painted(p))).isEqualTo(before + 2);

        SwingUtilities.invokeAndWait(() -> insert(p, 0, "x"));

        assertThat(marks(p)).isEmpty();
        assertThat(onEdt(p::currentHit)).isEqualTo(-1);
        assertThat(onEdt(() -> painted(p))).isEqualTo(before);

        show(p, new HitMarks(List.of(hit(1, "xfoo foo", 1, 3)), 0));
        SwingUtilities.invokeAndWait(p::clearHits);
        assertThat(marks(p)).isEmpty();
        assertThat(onEdt(() -> painted(p))).isEqualTo(before);
    }

    /// The preview tab's text loaded or reloaded from disk leaves it a preview; the user's edits keep it, asked once.
    @Test
    void theUsersEditKeepsThePreviewTabOnce() throws Exception {
        final PanelId id = new PanelId("preview");
        final EditorPanel p = panel(new PanelState(id, "notes.txt", "", "", true, false, true), e -> {
            e.editor().load("first\n");
            e.file(Path.of("/s/notes.txt"), true);
        });
        SwingUtilities.invokeAndWait(() -> p.reload("first\nsecond\n"));
        SwingUtilities.invokeAndWait(() -> { });
        assertThat(sent).noneMatch(Intent.PinPanel.class::isInstance);

        SwingUtilities.invokeAndWait(() -> insert(p, 0, "a"));
        SwingUtilities.invokeAndWait(() -> { });
        SwingUtilities.invokeAndWait(() -> insert(p, 0, "b"));
        SwingUtilities.invokeAndWait(() -> { });

        assertThat(sent).filteredOn(Intent.PinPanel.class::isInstance).containsExactly(new Intent.PinPanel(id));
    }

    /// A tab that stays is not asked to stay.
    @Test
    void anEditOfATabThatStaysAsksNothing() throws Exception {
        final EditorPanel p = withText("first\n");

        SwingUtilities.invokeAndWait(() -> insert(p, 0, "a"));
        SwingUtilities.invokeAndWait(() -> { });

        assertThat(sent).noneMatch(Intent.PinPanel.class::isInstance);
    }

    private static void insert(final EditorPanel p, final int at, final String text) {
        try {
            p.editor().area().getDocument().insertString(at, text, null);
        } catch (final BadLocationException e) {
            throw new IllegalStateException(e);
        }
    }

    /// The colour the highlight of `p`'s text that starts at `start` is painted in; null when none starts there.
    private static Object colourAt(final EditorPanel p, final int start) {
        for (final Highlighter.Highlight h : p.editor().area().getHighlighter().getHighlights()) {
            if (h.getStartOffset() == start && h.getPainter() instanceof DefaultHighlighter.DefaultHighlightPainter d) {
                return d.getColor();
            }
        }
        return null;
    }

    private static void layOut(final Component c) {
        c.doLayout();
        if (c instanceof Container k) {
            for (final Component child : k.getComponents()) {
                layOut(child);
            }
        }
    }

    /// How many highlights `p`'s text has, marks of hits and every other kind.
    private static int painted(final EditorPanel p) {
        return p.editor().area().getHighlighter().getHighlights().length;
    }

    /// What `read` returns, read on the EDT; its failure is thrown here.
    private static <T> T onEdt(final Callable<T> read) throws Exception {
        final AtomicReference<T> out = new AtomicReference<>();
        final AtomicReference<Exception> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                out.set(read.call());
            } catch (final Exception e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return out.get();
    }
}
