package dev.marregui.comando.ui.widgets.changes;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.ChangeTexts;
import dev.marregui.comando.core.model.RepoStatus;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.ui.kit.theme.Theme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// [ChangeEditor]: one changed file compared with HEAD, its working-tree side editable, saved, rolled back or removed.
/// - an edit is compared again, saved within a second, and undone and redone
/// - a read while edited is not shown; a read of the saved text keeps the undo history
/// - rolling back a changed file is one undoable edit; a file HEAD lacks goes to the Trash after a question
/// - removing asks, then moves the file to the Trash; a removed file stays removed while its text is empty
/// - Markdown offers both sides rendered; a file not compared is not editable
/// - a failed write says so and is tried again
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
}
