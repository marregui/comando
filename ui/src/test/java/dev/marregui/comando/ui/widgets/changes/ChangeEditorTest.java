package dev.marregui.comando.ui.widgets.changes;

import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Document;
import javax.swing.text.Highlighter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.ChangeTexts;
import dev.marregui.comando.core.model.RepoStatus;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.ui.kit.format.Labels;
import dev.marregui.comando.ui.kit.text.RenderedMarkdown;
import dev.marregui.comando.ui.kit.theme.Ground;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.theme.Tint;
import dev.marregui.comando.ui.kit.vcs.RepoChanges;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;

/// [ChangeEditor]: one changed file compared with HEAD, its working-tree side editable, saved, rolled back or removed.
/// - an edit is compared again, saved within a second, and undone and redone
/// - a read while edited is not shown; a read of the saved text keeps the undo history
/// - rolling back a changed file is one undoable edit; a file HEAD lacks goes to the Trash after a question
/// - removing asks, then moves the file to the Trash; a removed file stays removed while its text is empty
/// - Markdown offers both sides rendered; a file not compared is not editable
/// - a failed write says so and is tried again
/// - the rendered sides stay level on what did not change, a block one side lacks leaving a gap there, and scroll
///   together on those blocks
/// - the header says what the window's title lacks; a theme chosen shades the comparison on screen again
class ChangeEditorTest {

    @TempDir
    Path root;

    private final List<Intent> sent = new CopyOnWriteArrayList<>();
    private final List<Map.Entry<Path, String>> written = new CopyOnWriteArrayList<>();
    private final AtomicInteger answer = new AtomicInteger(1);
    private final List<String> asked = new CopyOnWriteArrayList<>();
    private final AtomicInteger gone = new AtomicInteger();
    /** Why a write fails; null writes. */
    private final AtomicReference<String> failing = new AtomicReference<>();

    /// How far apart the same block may sit on the two sides, in pixels.
    private static final int LEVEL = 2;

    private ChangeEditor editor() throws Exception {
        return on(null, x -> {
            final ChangeEditor e = new ChangeEditor(sent::add, (file, text) -> {
                written.add(Map.entry(file, text));
                return CompletableFuture.completedFuture(failing.get());
            }, (parent, text, title, options) -> {
                asked.add(text);
                return answer.get();
            });
            e.onGone(gone::incrementAndGet);
            return e;
        });
    }

    private static <T> T on(final ChangeEditor e, final Function<ChangeEditor, T> f) throws Exception {
        final AtomicReference<T> out = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> out.set(f.apply(e)));
        return out.get();
    }

    private static boolean is(final ChangeEditor e, final Predicate<ChangeEditor> p) throws Exception {
        return on(e, p::test);
    }

    private static String str(final ChangeEditor e, final Function<ChangeEditor, String> f) throws Exception {
        return on(e, f);
    }

    private static void run(final ChangeEditor e, final Consumer<ChangeEditor> f) throws Exception {
        SwingUtilities.invokeAndWait(() -> f.accept(e));
    }

    private ChangeTexts texts(final String path, final RepoStatus.Kind kind, final String before, final String after) {
        return new ChangeTexts(root.toString(), new RepoStatus.FileChange(path, "", kind), before, after, "");
    }

    /** Shows {@code t} and waits until its comparison is on screen. */
    private static void shown(final ChangeEditor e, final ChangeTexts t) throws Exception {
        run(e, x -> x.show(t, false));
        await().atMost(Duration.ofSeconds(10)).until(() -> on(e, x -> !x.countShown().startsWith("comparing")));
    }

    private static String right(final ChangeEditor e) throws Exception {
        return on(e, x -> x.diff().right().getText());
    }

    /// An edit marks the file edited and is compared again once typing pauses; saving writes the text and marks it
    /// saved, and undo and redo move between the two texts.
    @Test
    void anEditIsComparedAgainSavedAndUndoneAndRedone() throws Exception {
        final ChangeEditor e = editor();
        shown(e, texts("A.java", RepoStatus.Kind.MODIFIED, "a\nb\nc\n", "a\nB\nc\n"));
        assertThat(str(e, ChangeEditor::countShown)).isEqualTo("1 difference");
        assertThat(is(e, x -> x.diff().right().isEditable())).isTrue();

        run(e, x -> x.diff().right().insert("x", 0));
        assertThat(str(e, ChangeEditor::status)).isEqualTo("● edited");
        assertThat(is(e, x -> x.diff().right().canUndo())).isTrue();
        assertThat(str(e, ChangeEditor::countShown)).isEqualTo("1 difference");
        // compared again once typing pauses: the first line differs now
        await().atMost(Duration.ofSeconds(10)).until(() -> on(e, (ChangeEditor x) -> x.diff().chunks().getFirst().leftStart() == 0));

        run(e, ChangeEditor::save);
        assertThat(written).containsExactly(Map.entry(root.resolve("A.java"), "xa\nB\nc\n"));
        assertThat(str(e, ChangeEditor::status)).isEqualTo("✓ saved");

        run(e, ChangeEditor::undo);
        assertThat(right(e)).isEqualTo("a\nB\nc\n");
        assertThat(is(e, x -> x.diff().right().canRedo())).isTrue();
        run(e, ChangeEditor::redo);
        assertThat(right(e)).isEqualTo("xa\nB\nc\n");
    }

    /// A fresh read arriving while the side has unsaved edits leaves the edits on screen; after saving, a read of the
    /// saved text keeps the undo history.
    @Test
    void aReadWhileEditedIsLeftUnshownAndAReadOfTheSavedTextKeepsTheUndoHistory() throws Exception {
        final ChangeEditor e = editor();
        final ChangeTexts t = texts("A.java", RepoStatus.Kind.MODIFIED, "a\n", "b\n");
        shown(e, t);
        run(e, x -> x.diff().right().insert("x", 0));
        run(e, x -> x.show(texts("A.java", RepoStatus.Kind.MODIFIED, "a\n", "from a session\n"), true));
        assertThat(right(e)).as("the edits win").isEqualTo("xb\n");

        run(e, ChangeEditor::save);
        run(e, x -> x.show(texts("A.java", RepoStatus.Kind.MODIFIED, "a\n", "xb\n"), true));
        assertThat(right(e)).isEqualTo("xb\n");
        assertThat(is(e, x -> x.diff().right().canUndo())).as("the read of what was saved keeps the history").isTrue();
    }

    /// Rolling back a modified file writes HEAD's text without asking, sends `Unstage`, and a single undo brings back
    /// the working-tree text.
    @Test
    void rollingBackAChangedFileTakesHeadsTextAsOneEditThatUndoTakesBack() throws Exception {
        final ChangeEditor e = editor();
        shown(e, texts("docs/A.java", RepoStatus.Kind.MODIFIED, "one\ntwo\n", "one\n2\nthree\n"));
        run(e, ChangeEditor::rollBack);
        assertThat(right(e)).isEqualTo("one\ntwo\n");
        assertThat(written).containsExactly(Map.entry(root.resolve("docs/A.java"), "one\ntwo\n"));
        assertThat(sent).containsExactly(new Intent.Unstage(root.toString(), List.of("docs/A.java")));
        assertThat(asked).as("undo is the way back: nothing asked").isEmpty();

        run(e, ChangeEditor::undo);
        assertThat(right(e)).as("one undo").isEqualTo("one\n2\nthree\n");
    }

    /// Rolling back an untracked file asks first, sends nothing when cancelled, and when confirmed sends `RollBack` and
    /// leaves the window.
    @Test
    void aFileHeadDoesNotHaveRollsBackThroughGitAfterAQuestionAndLeavesTheWindow() throws Exception {
        final ChangeEditor e = editor();
        final RepoStatus.FileChange c = new RepoStatus.FileChange("notes/n.md", "", RepoStatus.Kind.UNTRACKED);
        shown(e, new ChangeTexts(root.toString(), c, "", "# n\n", ""));
        run(e, ChangeEditor::rollBack);
        assertThat(sent).as("cancelled").isEmpty();
        answer.set(0);
        run(e, ChangeEditor::rollBack);
        assertThat(asked.getLast()).isEqualTo("n.md is not in HEAD: rolling it back moves it to the Trash.");
        assertThat(sent).containsExactly(new Intent.RollBack(root.toString(), List.of(c)));
        assertThat(gone).hasValue(1);
    }

    /// Remove asks, sends `RemoveFiles` and leaves the window; a deleted file does not offer remove.
    @Test
    void removingAsksThenSendsTheFileToTheTrashAndLeavesTheWindow() throws Exception {
        final ChangeEditor e = editor();
        shown(e, texts("A.java", RepoStatus.Kind.MODIFIED, "a\n", "b\n"));
        answer.set(0);
        run(e, ChangeEditor::remove);
        assertThat(asked).containsExactly("Move A.java to the Trash? It can be put back from there.");
        assertThat(sent).containsExactly(new Intent.RemoveFiles(root.toString(), List.of("A.java")));
        assertThat(gone).hasValue(1);

        shown(e, texts("gone.txt", RepoStatus.Kind.DELETED, "bye\n", ""));
        assertThat(is(e, x -> x.offered("remove"))).as("a removed file is not removed again").isFalse();
    }

    /// A deleted file whose side is edited back to empty writes nothing on save; rolling it back writes HEAD's text and
    /// sends `Unstage`.
    @Test
    void aRemovedFileStaysRemovedWhileItsTextIsEmpty() throws Exception {
        final ChangeEditor e = editor();
        shown(e, texts("gone.txt", RepoStatus.Kind.DELETED, "bye\n", ""));
        run(e, x -> x.diff().right().insert("y", 0));
        run(e, x -> x.diff().right().setText(""));
        run(e, ChangeEditor::save);
        assertThat(written).isEmpty();
        run(e, ChangeEditor::rollBack);
        assertThat(written).as("rolled back: written again as HEAD has it").containsExactly(Map.entry(root.resolve("gone.txt"), "bye\n"));
        assertThat(sent).containsExactly(new Intent.Unstage(root.toString(), List.of("gone.txt")));
    }

    /// Only Markdown offers the rendered view, whose sides shade the removed text red and the added green; another file
    /// shown next is edited as text.
    @Test
    void markdownOffersBothSidesRenderedAndOtherTextNot() throws Exception {
        final ChangeEditor e = editor();
        shown(e, texts("A.java", RepoStatus.Kind.MODIFIED, "a\n", "b\n"));
        assertThat(is(e, x -> x.offered("rendered"))).isFalse();
        run(e, ChangeEditor::toggleRendered);
        assertThat(is(e, ChangeEditor::rendering)).isFalse();

        shown(e, texts("README.md", RepoStatus.Kind.MODIFIED, "# Old\n", "# New\n"));
        assertThat(is(e, x -> x.offered("rendered"))).isTrue();
        run(e, ChangeEditor::toggleRendered);
        assertThat(is(e, ChangeEditor::rendering)).isTrue();
        assertThat(str(e, x -> x.sides().get(0).pane().getText())).contains("Old");
        assertThat(str(e, x -> x.sides().get(1).pane().getText())).contains("New");
        assertThat(str(e, x -> x.sides().get(0).pane().getText())).as("HEAD's changed heading shaded red")
                .containsIgnoringCase(Theme.hex(Theme.content().removed));
        assertThat(str(e, x -> x.sides().get(1).pane().getText())).as("the working tree's shaded green")
                .containsIgnoringCase(Theme.hex(Theme.content().added));

        shown(e, texts("B.java", RepoStatus.Kind.MODIFIED, "a\n", "b\n"));
        run(e, x -> x.diff().right().insert("x", 0));
        assertThat(str(e, ChangeEditor::status)).as("the choice holds for Markdown only: other text is edited as ever").isEqualTo("● edited");
    }

    /// A binary file shown as a note is not editable and offers no rendered view, but offers remove.
    @Test
    void aFileNotComparedIsNotEdited() throws Exception {
        final ChangeEditor e = editor();
        final RepoStatus.FileChange c = new RepoStatus.FileChange("a.png", "", RepoStatus.Kind.MODIFIED);
        run(e, x -> x.show(ChangeTexts.noted(root.toString(), c, "a binary file: not compared"), false));
        assertThat(is(e, x -> x.diff().right().isEditable())).isFalse();
        assertThat(is(e, x -> x.offered("rendered"))).isFalse();
        assertThat(is(e, x -> x.offered("remove"))).isTrue();
    }

    /// The same paragraphs after two only the working tree has sit at one height on both sides: at the top, the gap on
    /// HEAD's side holding the place of the blocks it lacks, and after scrolling either side.
    @Test
    void blocksBothSidesKeepSitLevelAfterABlockOneSideAdded() throws Exception {
        final String tail = paragraphs(40);
        final ChangeEditor e = rendered("# Title\n\nFirst.\n\n" + tail,
                "# Title\n\nFirst.\n\nOnly here.\n\nAnd only here, a second block.\n\n" + tail);
        assertLevel(e, "Paragraph 0.");
        assertLevel(e, "Paragraph 39.");
        scroll(e, 0, 300);
        assertLevel(e, "Paragraph 20.");
        scroll(e, 1, 150);
        assertLevel(e, "Paragraph 10.");
    }

    /// The working tree lost a block: the gap is on its side now, and the blocks after it level all the same.
    @Test
    void aBlockRemovedLeavesItsGapOnTheOtherSide() throws Exception {
        final String tail = paragraphs(30);
        final ChangeEditor e = rendered("Start.\n\n## Gone\n\nGone too.\n\n" + tail, "Start.\n\n" + tail);
        assertLevel(e, "Paragraph 0.");
        scroll(e, 1, 200);
        assertLevel(e, "Paragraph 15.");
    }

    /// A row added inside a table leaves a gap in the table on the other side, and the rows below it level.
    @Test
    void aRowOneSideAddedLeavesAGapAmongTheRows() throws Exception {
        final String head = "| Name | Says |\n| --- | --- |\n";
        final String rows =
                IntStream.range(0, 20).mapToObj(i -> "| row" + i + " | text |\n").collect(Collectors.joining());
        final ChangeEditor e = rendered("Above.\n\n" + head + "| one | text |\n" + rows + "\nBelow.\n",
                "Above.\n\n" + head + "| one | text |\n| added | text |\n" + rows + "\nBelow.\n");
        assertLevel(e, "row0");
        assertLevel(e, "row19");
        assertLevel(e, "Below.");
    }

    /// An item added to a list leaves a gap among the items on the other side.
    @Test
    void anItemOneSideAddedLeavesAGapAmongTheItems() throws Exception {
        final String items = IntStream.range(0, 12).mapToObj(i -> "- item" + i + "\n").collect(Collectors.joining());
        final ChangeEditor e =
                rendered("- first\n" + items + "\nBelow.\n", "- first\n- added\n" + items + "\nBelow.\n");
        assertLevel(e, "item0");
        assertLevel(e, "Below.");
    }

    /// A block at the very end only one side has: the sides end level, with the same length to scroll.
    @Test
    void aBlockAddedAtTheEndLeavesTheSidesEqualInLength() throws Exception {
        final String common = paragraphs(5);
        final ChangeEditor e = rendered(common, common + "A closing block.\n\nAnd another.\n");
        final int head = on(e, x -> height(x.sides().get(0)));
        final int tree = on(e, x -> height(x.sides().get(1)));
        assertThat(head).isCloseTo(tree, within(LEVEL));
    }

    /// A file read again while its sides show keeps the reader's place, as its texts do.
    @Test
    void sidesReadAgainKeepTheirPlace() throws Exception {
        final String tail = paragraphs(30);
        final ChangeEditor e = rendered("One.\n\n" + tail, "One.\n\nAdded.\n\n" + tail);
        assertLevel(e, "Paragraph 0.");
        scroll(e, 0, 200);
        final String after = "One.\n\nAdded.\n\n" + tail + "Tail.\n";
        run(e, x -> x.show(new ChangeTexts(x.root(), x.change(), x.shown().before(), after, ""), true));
        await().atMost(Duration.ofSeconds(10)).until(() -> on(e, x -> x.diff().right().getText().equals(after)));
        assertLevel(e, "Paragraph 20.");
        final int place = on(e, x -> x.sides().get(0).scroll().getVerticalScrollBar().getValue());
        assertThat(place).isCloseTo(200, within(40));
    }

    /// Sides rendered again start from the top, though the gaps opened after the render keep the view where it was.
    @Test
    void sidesRenderedAgainStartFromTheTop() throws Exception {
        final String tail = paragraphs(30);
        final ChangeEditor e = rendered("One.\n\n" + tail, "One.\n\nAdded.\n\n" + tail);
        assertLevel(e, "Paragraph 0.");
        scroll(e, 0, 200);
        run(e, ChangeEditor::toggleRendered);
        run(e, ChangeEditor::toggleRendered);
        run(e, x -> layOut(x.component()));
        run(e, x -> { });
        final int head = on(e, x -> x.sides().get(0).scroll().getVerticalScrollBar().getValue());
        final int tree = on(e, x -> x.sides().get(1).scroll().getVerticalScrollBar().getValue());
        assertThat(head).isZero();
        assertThat(tree).isZero();
    }

    /// A scroll lands on the other side where the blocks both keep say: at a block, on that block; between two, the
    /// same share of the way; past the last, up to the other side's end.
    @Test
    void aScrollFallsBetweenTheBlocksBothKeep() {
        final int[] here = {100, 200};
        final int[] there = {100, 300};
        assertThat(ChangeEditor.across(here, there, 100, 400, 400)).isEqualTo(100);
        assertThat(ChangeEditor.across(here, there, 150, 400, 400)).isEqualTo(200);
        assertThat(ChangeEditor.across(here, there, 200, 400, 400)).isEqualTo(300);
        assertThat(ChangeEditor.across(here, there, 300, 400, 400)).isEqualTo(350);
        assertThat(ChangeEditor.across(here, there, 400, 400, 400)).isEqualTo(400);
    }

    /// With no block to go by the scroll is the same share of the length; a side with nothing to scroll stays at its
    /// top; a block past what either side can scroll to is passed over.
    @Test
    void aScrollWithNoBlockToGoByIsAShareOfTheLength() {
        final int[] none = {};
        assertThat(ChangeEditor.across(none, none, 50, 100, 300)).isEqualTo(150);
        assertThat(ChangeEditor.across(none, none, 0, 0, 300)).isZero();
        assertThat(ChangeEditor.across(new int[] {500}, new int[] {20}, 50, 100, 300)).isEqualTo(150);
    }

    /// Sides rendered before they had a size, as in a window not yet laid out, level once they have one.
    @Test
    void sidesRenderedBeforeTheyHadASizeLevelOnceTheyHaveOne() throws Exception {
        final String tail = paragraphs(20);
        final ChangeEditor e = editor();
        shown(e, texts("README.md", RepoStatus.Kind.MODIFIED, "One.\n\n" + tail, "One.\n\nAdded.\n\n" + tail));
        run(e, ChangeEditor::toggleRendered);
        sized(e);
        assertLevel(e, "Paragraph 10.");
    }

    /// The header leaves the path to the window's title, and keeps the file's state, what it was renamed from and its
    /// root.
    @Test
    void theHeaderSaysWhatTheWindowsTitleLacks() throws Exception {
        final ChangeEditor e = editor();
        shown(e, texts("docs/README.md", RepoStatus.Kind.MODIFIED, "a\n", "b\n"));
        assertThat(str(e, ChangeEditor::titleShown)).doesNotContain("README.md")
                .contains(RepoChanges.describe(RepoStatus.Kind.MODIFIED)).contains(Labels.shortPath(root.toString()));

        final RepoStatus.FileChange moved = new RepoStatus.FileChange("new.md", "old.md", RepoStatus.Kind.RENAMED);
        shown(e, new ChangeTexts(root.toString(), moved, "a\n", "b\n", ""));
        assertThat(str(e, ChangeEditor::titleShown)).doesNotContain("new.md").contains("from old.md");
    }

    /// With the comparison on screen, a theme chosen shades both the texts and the rendered sides in its colours; once
    /// unmounted, it no longer hears the theme.
    @Test
    void aThemeChosenShadesTheComparisonShownAgain() throws Exception {
        SwingUtilities.invokeAndWait(() -> Theme.choose(Ground.GRAPHITE, Tint.TREASURE));
        try {
            final ChangeEditor e = editor();
            shown(e, texts("README.md", RepoStatus.Kind.MODIFIED, "one old word\n", "one new word\n"));
            run(e, ChangeEditor::toggleRendered);
            final String darkLine = Theme.hex(Theme.content().removed);
            SwingUtilities.invokeAndWait(() -> Theme.choose(Ground.FOG, Tint.SLATE_INK));
            final String lightLine = Theme.hex(Theme.content().removed);
            assertThat(lightLine).as("the two themes shade differently").isNotEqualToIgnoringCase(darkLine);
            assertThat(str(e, x -> x.sides().get(0).pane().getText())).as("the rendered side, in the theme chosen")
                    .containsIgnoringCase(lightLine).doesNotContainIgnoringCase(darkLine);
            assertThat(on(e, ChangeEditorTest::wordShades)).as("the text's words, in the theme chosen")
                    .contains(Theme.content().removedWord.getRGB());

            run(e, ChangeEditor::unmount);
            SwingUtilities.invokeAndWait(() -> Theme.choose(Ground.GRAPHITE, Tint.TREASURE));
            assertThat(str(e, x -> x.sides().get(0).pane().getText())).as("unmounted: left as it was")
                    .containsIgnoringCase(lightLine);
        } finally {
            SwingUtilities.invokeAndWait(() -> Theme.choose(Ground.GRAPHITE, Tint.TREASURE));
        }
    }

    /// A file shown on sides of 1000 by 400 pixels, its two sides rendered.
    private ChangeEditor rendered(final String before, final String after) throws Exception {
        final ChangeEditor e = editor();
        shown(e, texts("README.md", RepoStatus.Kind.MODIFIED, before, after));
        sized(e);
        run(e, ChangeEditor::toggleRendered);
        run(e, x -> layOut(x.component()));
        return e;
    }

    private static void sized(final ChangeEditor e) throws Exception {
        run(e, x -> {
            x.component().setSize(1000, 400);
            layOut(x.component());
        });
    }

    private static void layOut(final Container c) {
        c.doLayout();
        for (final Component child : c.getComponents()) {
            if (child instanceof Container inner) {
                layOut(inner);
            }
        }
    }

    private static String paragraphs(final int count) {
        return IntStream.range(0, count).mapToObj(i -> "Paragraph " + i + ".\n\n").collect(Collectors.joining());
    }

    /// Scrolls side {@code side}, 0 for HEAD's, to {@code y}, and waits for what was queued behind the scroll.
    private static void scroll(final ChangeEditor e, final int side, final int y) throws Exception {
        run(e, x -> layOut(x.component()));
        run(e, x -> x.sides().get(side).scroll().getVerticalScrollBar().setValue(y));
        run(e, x -> { });
    }

    /// Asserts {@code text} sits at the same height on the screen on both sides, in at most 3 seconds.
    private static void assertLevel(final ChangeEditor e, final String text) {
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            run(e, x -> layOut(x.component()));
            final int head = on(e, x -> onScreen(x.sides().get(0), text));
            final int tree = on(e, x -> onScreen(x.sides().get(1), text));
            assertThat(head).as(text).isCloseTo(tree, within(LEVEL));
        });
    }

    /// The height of {@code text}'s first appearance, from the top of the view.
    private static int onScreen(final RenderedMarkdown side, final String text) {
        try {
            final Document d = side.pane().getDocument();
            final int at = d.getText(0, d.getLength()).indexOf(text);
            assertThat(at).as(text + " on the page").isNotNegative();
            return (int) side.pane().modelToView2D(at).getY() - side.scroll().getViewport().getViewPosition().y;
        } catch (final BadLocationException ex) {
            throw new AssertionError(ex);
        }
    }

    private static int height(final RenderedMarkdown side) {
        return side.pane().getPreferredSize().height;
    }

    /// The colours of the words shaded on HEAD's text.
    private static List<Integer> wordShades(final ChangeEditor e) {
        return Arrays.stream(e.diff().left().getHighlighter().getHighlights()).map(Highlighter.Highlight::getPainter)
                .filter(DefaultHighlighter.DefaultHighlightPainter.class::isInstance)
                .map(p -> ((DefaultHighlighter.DefaultHighlightPainter) p).getColor().getRGB()).toList();
    }

    /// An edit is written without asking within a second, the status goes from edited to saved, and the modified
    /// callback reports true then false.
    @Test
    void editsAreWrittenWithinASecondAndTheWorkingTreesSideSaysSo() throws Exception {
        final ChangeEditor e = editor();
        shown(e, texts("A.java", RepoStatus.Kind.MODIFIED, "a\n", "b\n"));
        final List<Boolean> modified = new CopyOnWriteArrayList<>();
        run(e, x -> x.onModified(modified::add));
        run(e, x -> x.diff().right().insert("x", 0));
        assertThat(str(e, ChangeEditor::status)).isEqualTo("● edited");
        await().atMost(Duration.ofSeconds(3)).until(() -> written.size() == 1);
        assertThat(written.getFirst().getValue()).as("written without asking").isEqualTo("xb\n");
        await().atMost(Duration.ofSeconds(3)).until(() -> str(e, ChangeEditor::status).equals("✓ saved"));
        assertThat(modified).as("modified while edited, not once written").containsSubsequence(true, false);
        assertThat(is(e, ChangeEditor::edited)).isFalse();
    }

    /// A failed write shows "not saved", posts an error notice whose subject opens the log, keeps the file edited and
    /// retries until the write succeeds.
    @Test
    void aWriteThatFailsSaysSoAndIsTriedAgain() throws Exception {
        final ChangeEditor e = editor();
        shown(e, texts("A.java", RepoStatus.Kind.MODIFIED, "a\n", "b\n"));
        failing.set("disk full");
        run(e, x -> x.diff().right().insert("x", 0));
        run(e, ChangeEditor::save);
        assertThat(str(e, ChangeEditor::status)).isEqualTo("✗ not saved");
        assertThat(sent).singleElement().isInstanceOfSatisfying(Intent.Notify.class, n -> {
            assertThat(n.level()).isEqualTo(Notice.Level.ERROR);
            assertThat(n.text()).isEqualTo("Cannot save A.java: disk full");
            assertThat(n.subject()).as("its click opens the log at the entry").startsWith("log:");
        });
        assertThat(is(e, ChangeEditor::edited)).as("tried again at the next second").isTrue();
        failing.set(null);
        await().atMost(Duration.ofSeconds(3)).until(() -> str(e, ChangeEditor::status).equals("✓ saved"));
    }

    /// The window moves to another file while the edits of the one it showed cannot be written: the write is tried
    /// again every second until it succeeds, and the file shown again meanwhile shows the edits, still to be written.
    @Test
    void anEditWhoseWriteFailsAsTheWindowMovesOnIsTriedAgainAndShownOnComingBack() throws Exception {
        final ChangeEditor e = editor();
        shown(e, texts("A.java", RepoStatus.Kind.MODIFIED, "a\n", "b\n"));
        run(e, x -> x.diff().right().insert("x", 0));
        failing.set("disk full");
        shown(e, texts("B.java", RepoStatus.Kind.MODIFIED, "c\n", "d\n"));
        assertThat(written).extracting(w -> w.getKey().getFileName().toString()).containsExactly("A.java");
        await().atMost(Duration.ofSeconds(5)).until(() -> writesOf("A.java") >= 2);
        shown(e, texts("A.java", RepoStatus.Kind.MODIFIED, "a\n", "b\n"));
        assertThat(right(e)).as("A's edits, not its text on disk").isEqualTo("xb\n");
        assertThat(is(e, ChangeEditor::edited)).isTrue();
        assertThat(str(e, ChangeEditor::status)).isEqualTo("✗ not saved");
        failing.set(null);
        await().atMost(Duration.ofSeconds(3)).until(() -> str(e, ChangeEditor::status).equals("✓ saved"));
        assertThat(written.getLast()).isEqualTo(Map.entry(root.resolve("A.java"), "xb\n"));
        final long writes = written.size();
        Thread.sleep(ChangeEditor.AUTOSAVE_MILLIS * 2L);
        assertThat(written).as("nothing left to write once written").hasSize((int) writes);
        run(e, ChangeEditor::unmount);
    }

    private long writesOf(final String name) {
        return written.stream().filter(w -> w.getKey().getFileName().toString().equals(name)).count();
    }
}
