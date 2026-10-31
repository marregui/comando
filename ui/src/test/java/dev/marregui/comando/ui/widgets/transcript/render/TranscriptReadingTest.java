package dev.marregui.comando.ui.widgets.transcript.render;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.ApprovalKind;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.testkit.SyntheticSession;
import dev.marregui.comando.ui.kit.async.OnEdt;
import dev.marregui.comando.ui.kit.format.KeyText;
import dev.marregui.comando.ui.kit.theme.Theme;

import static org.assertj.core.api.Assertions.assertThat;

/// How [TranscriptCanvas] reads: what opens and closes by itself, what find and copy see, and the status of running
/// calls.
/// - consecutive hook notes fold into one closed line naming their events; a JSON reply reads as the context it adds
/// - notes open and close, errors open by default, and find opens what holds a match
/// - a card that opened while running stays open; a failed card opens; thinking opens while it streams
/// - clearing or changing the find closes what it opened, except the current match and the user's choices
/// - the Markdown export is a readable record, and the turn-end line is selectable
/// - an interrupted turn reads as text, a failed one in danger
/// - a running call shows its elapsed time and when an approval holds it up
@ExtendWith(OnEdt.class)
class TranscriptReadingTest {

    private static final String CONTEXT_JSON = "{\"hookSpecificOutput\":{\"hookEventName\":\"UserPromptSubmit\","
            + "\"additionalContext\":\"Effort protocol: invoke deep first.\\nSecond line of context.\"}}";

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final TranscriptCanvas canvas = new TranscriptCanvas(href -> { });
    private int next;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    TranscriptReadingTest() {
        canvas.clock(now::get);
    }

    private int add(final Block b) {
        final int at = next++;
        canvas.append(at, b);
        return at;
    }

    private void paint(final int width, final int height) {
        canvas.setSize(width, height);
        canvas.ensureLayout(width);
        canvas.setSize(width, canvas.contentHeight());
        final BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = img.createGraphics();
        canvas.paint(g);
        g.dispose();
    }

    private static Block.ToolCall running(final String id, final String name, final String input) {
        return new Block.ToolCall(id, name, input, "", Block.ToolStatus.RUNNING, "");
    }

    private static Approval approval(final String toolName, final String toolUseId) {
        return new Approval(1, "s", ApprovalKind.TOOL, toolName, "{}", "run it", 0L, toolUseId);
    }

    // ---- hook notes

    /// Three hook notes in a row become one closed group titled with their distinct events and count, a later hook
    /// after another block starts a new group, every block keeps its item, and the hook text is selectable only once
    /// opened.
    @Test
    void consecutiveHookNotesFoldIntoOneClosedLineNamingTheirEvents() {
        add(new Block.UserPrompt("go", "main", ""));
        add(new Block.Note(Block.NoteKind.HOOK, "UserPromptSubmit: " + CONTEXT_JSON, ""));
        add(new Block.Note(Block.NoteKind.HOOK, "SessionStart:startup: hook output", ""));
        add(new Block.Note(Block.NoteKind.HOOK, "UserPromptSubmit: watermark 61%", ""));
        add(new Block.AssistantText("m", 0, "Answer.", false, ""));
        add(new Block.Note(Block.NoteKind.HOOK, "SessionStart:compact: again", ""));
        paint(900, 600);

        final List<Item> top = canvas.topLevel();
        assertThat(top).hasSize(4);
        final Items.HookGroupItem group = (Items.HookGroupItem) top.get(1);
        assertThat(group.size()).isEqualTo(3);
        assertThat(group.isOpen()).isFalse();
        assertThat(group.title()).isEqualTo("hooks · UserPromptSubmit, SessionStart (3)");
        assertThat(((Items.HookGroupItem) top.get(3)).title()).as("a block between hooks starts a new group").isEqualTo("hook · SessionStart");
        assertThat(canvas.itemCount()).as("every block keeps its item, for replacements").isEqualTo(6);

        canvas.selectAll();
        assertThat(canvas.selectionText()).as("a closed group shows no hook text").doesNotContain("watermark");
        assertThat(group.toggle()).isTrue();
        canvas.layoutFully(900);
        canvas.selectAll();
        assertThat(canvas.selectionText()).contains("SessionStart:startup: hook output").contains("UserPromptSubmit: watermark 61%");
    }

    /// A hook reply whose JSON only adds context shows that context after the event name; a reply that says more, or
    /// plain output, is shown whole.
    @Test
    void aHookReplyInJsonReadsAsTheContextItAdds() {
        final int at = add(new Block.Note(Block.NoteKind.HOOK, "UserPromptSubmit: " + CONTEXT_JSON, ""));
        final Items.NoteItem hook = (Items.NoteItem) canvas.itemAt(at);
        assertThat(hook.shown()).isEqualTo("UserPromptSubmit: Effort protocol: invoke deep first.\nSecond line of context.");
        // a reply that says more than the context is shown whole, so nothing is lost
        assertThat(Items.NoteItem.additionalContext("{\"hookSpecificOutput\":{\"additionalContext\":\"x\"},\"decision\":\"block\"}")).isNull();
        assertThat(Items.NoteItem.additionalContext("plain output")).isNull();
    }

    /// The Markdown export holds the hooks' full text, and find matches inside a closed group and opens it.
    @Test
    void findAndCopySeeTheFullHookText() {
        add(new Block.Note(Block.NoteKind.HOOK, "UserPromptSubmit: " + CONTEXT_JSON, ""));
        add(new Block.Note(Block.NoteKind.HOOK, "SessionStart:startup: hook output", ""));
        paint(900, 600);
        final Items.HookGroupItem group = (Items.HookGroupItem) canvas.topLevel().getFirst();

        assertThat(canvas.markdown()).contains("Second line of context.").contains("SessionStart:startup: hook output");
        assertThat(canvas.find("second line of context", true)).isEqualTo(1);
        assertThat(group.isOpen()).as("find opens the group holding the match").isTrue();
    }

    // ---- notes

    /// A long info note starts closed showing its first line, an error note starts open showing all of it, a one-line
    /// note cannot toggle, and the export always holds the full text.
    @Test
    void notesOpenAndCloseAndErrorsOpenByDefault() {
        final String tail = "Process exited [code=1]\n" + "stderr line\n".repeat(30) + "the cause is on the last line";
        final int info = add(new Block.Note(Block.NoteKind.INFO, tail, ""));
        final int error = add(new Block.Note(Block.NoteKind.ERROR, tail, ""));
        final int shortNote = add(new Block.Note(Block.NoteKind.INTERRUPT, "Interrupted", ""));
        paint(900, 600);

        final Items.NoteItem closed = (Items.NoteItem) canvas.itemAt(info);
        assertThat(closed.isOpen()).isFalse();
        assertThat(((Items.NoteItem) canvas.itemAt(error)).isOpen()).isTrue();
        assertThat(canvas.itemAt(shortNote).toggle()).as("a one-line note has nothing more to show").isFalse();

        canvas.selectAll();
        final String visible = canvas.selectionText();
        assertThat(visible).as("closed: the first line").contains("Process exited [code=1]");
        // the error note is open and shows its last line; the closed info note does not
        assertThat(visible.split("the cause is on the last line", -1)).hasSize(2);

        assertThat(closed.toggle()).isTrue();
        canvas.layoutFully(900);
        canvas.selectAll();
        assertThat(canvas.selectionText().split("the cause is on the last line", -1)).hasSize(3);
        assertThat(canvas.markdown()).contains("the cause is on the last line");
    }

    /// Find matches a closed note's third line and opens it.
    @Test
    void findOpensAClosedNoteHoldingTheText() {
        final int info = add(new Block.Note(Block.NoteKind.INFO, "first line\nsecond line\nneedle on the third", ""));
        paint(900, 600);
        assertThat(canvas.find("needle", true)).isEqualTo(1);
        assertThat(((Items.NoteItem) canvas.itemAt(info)).isOpen()).isTrue();
    }

    // ---- automatic opening

    /// A sub-agent card open while running stays open when it finishes; a closed tool card opens when it fails.
    @Test
    void aSubagentCardThatOpenedWhileRunningStaysOpenWhenItFinishes() {
        final Block.ToolCall agent = running("a1", "Agent", "{\"description\":\"survey\",\"prompt\":\"List the classes.\"}");
        final int at = add(agent);
        final Items.ToolItem card = (Items.ToolItem) canvas.itemAt(at);
        assertThat(card.isOpen()).isTrue();
        add(new Block.AssistantText("sub", 0, "Step seven of the survey.", false, "a1"));

        canvas.update(at, agent.withResult("The module has 12 classes.", false));
        assertThat(card.isOpen()).as("finishing never closes a card the user did not touch").isTrue();

        // a card that starts closed opens when it fails
        final Block.ToolCall bash = running("b1", "Bash", "{\"command\":\"false\"}");
        final int b = add(bash);
        assertThat(((Items.ToolItem) canvas.itemAt(b)).isOpen()).isFalse();
        canvas.update(b, bash.withResult("exit 1", true));
        assertThat(((Items.ToolItem) canvas.itemAt(b)).isOpen()).isTrue();
    }

    /// Streaming thinking is open and closes when it ends, unless the user toggled it meanwhile; thinking from history
    /// arrives closed.
    @Test
    void thinkingOpensWhileItStreamsAndClosesWhenItEnds() {
        final int at = add(new Block.Thinking("m", 0, "Considering", 10, true, ""));
        final Items.ThinkingItem thinking = (Items.ThinkingItem) canvas.itemAt(at);
        assertThat(thinking.isOpen()).isTrue();
        canvas.update(at, new Block.Thinking("m", 0, "Considering the request", 20, true, ""));
        assertThat(thinking.isOpen()).isTrue();
        canvas.update(at, new Block.Thinking("m", 0, "Considering the request.", 30, false, ""));
        assertThat(thinking.isOpen()).isFalse();

        // the user's choice wins: closed and reopened while streaming, it stays open at the end
        final int other = add(new Block.Thinking("m2", 0, "Weighing", 10, true, ""));
        final Items.ThinkingItem chosen = (Items.ThinkingItem) canvas.itemAt(other);
        chosen.toggle();
        chosen.toggle();
        canvas.update(other, new Block.Thinking("m2", 0, "Weighing it.", 20, false, ""));
        assertThat(chosen.isOpen()).isTrue();

        // thinking from history arrives finished and stays closed
        final int history = add(new Block.Thinking("m3", 0, "Old reasoning", 10, false, ""));
        assertThat(((Items.ThinkingItem) canvas.itemAt(history)).isOpen()).isFalse();
    }

    // ---- find

    /// Find opens every closed card holding a match; clearing it closes them again except the card of the current match
    /// and one the user toggled during the find.
    @Test
    void clearingTheFindClosesWhatItOpenedExceptTheCurrentMatchAndUserChoices() {
        final List<Integer> bashes = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            final Block.ToolCall c = running("c" + i, "Bash", "{\"command\":\"grep -r token" + i + "\"}");
            final int at = add(c);
            canvas.update(at, c.withResult("Lane.java:" + i + " matched", false));
            bashes.add(at);
        }
        paint(900, 600);
        for (final int at : bashes) {
            assertThat(((Items.ToolItem) canvas.itemAt(at)).isOpen()).isFalse();
        }

        assertThat(canvas.find("lane.java", true)).isEqualTo(4);
        assertThat(canvas.find("lane.java", true)).as("the second match is current").isEqualTo(4);
        for (final int at : bashes) {
            assertThat(((Items.ToolItem) canvas.itemAt(at)).isOpen()).isTrue();
        }
        final Items.ToolItem toggled = (Items.ToolItem) canvas.itemAt(bashes.get(3));
        toggled.toggle();
        toggled.toggle();

        canvas.clearFind();
        assertThat(((Items.ToolItem) canvas.itemAt(bashes.get(0))).isOpen()).isFalse();
        assertThat(((Items.ToolItem) canvas.itemAt(bashes.get(1))).isOpen()).as("holds the current match").isTrue();
        assertThat(((Items.ToolItem) canvas.itemAt(bashes.get(2))).isOpen()).isFalse();
        assertThat(toggled.isOpen()).as("the user toggled it during the find").isTrue();
    }

    /// A new query closes the cards the previous query opened that it does not match.
    @Test
    void aNewFindTextClosesWhatThePreviousOneOpened() {
        final Block.ToolCall a = running("a", "Bash", "{\"command\":\"echo alpha\"}");
        final Block.ToolCall b = running("b", "Bash", "{\"command\":\"echo beta\"}");
        final int ia = add(a);
        final int ib = add(b);
        paint(900, 600);
        canvas.find("echo", true);
        assertThat(((Items.ToolItem) canvas.itemAt(ia)).isOpen()).isTrue();
        canvas.find("beta", true);
        assertThat(((Items.ToolItem) canvas.itemAt(ia)).isOpen()).isFalse();
        assertThat(((Items.ToolItem) canvas.itemAt(ib)).isOpen()).isTrue();
    }

    // ---- copy

    /// The export quotes prompts, shows commands as code, clips long results with a count of the lines left out,
    /// renders edits as diffs and other inputs as JSON, ends turns with their time, and leaves out thinking.
    @Test
    void theMarkdownExportIsAReadableRecord() {
        add(new Block.UserPrompt("Run the tests", "main", ""));
        add(new Block.Thinking("m", 0, "private reasoning", 10, false, ""));
        final Block.ToolCall bash = running("b", "Bash", "{\"command\":\"./gradlew test\",\"description\":\"tests\"}");
        final int at = add(bash);
        canvas.update(at, bash.withResult("BUILD SUCCESSFUL\n" + "line\n".repeat(450), false));
        add(new Block.ToolCall("e", "Edit", "{\"file_path\":\"/a/B.java\",\"old_string\":\"int x = 1;\",\"new_string\":\"int x = 2;\"}",
                "ok", Block.ToolStatus.DONE, ""));
        add(new Block.ToolCall("g", "Grep", "{\"pattern\":\"cancel\",\"path\":\"core\"}", "3 files", Block.ToolStatus.DONE, ""));
        add(new Block.TurnEnd(0.12, 12_000, 1, false, ""));

        final String md = canvas.markdown();
        assertThat(md).contains("> Run the tests")
                .contains("**Bash** · ./gradlew test\n\n```bash\n./gradlew test\n```")
                .contains("```\nBUILD SUCCESSFUL\n")
                .contains("… 52 more lines\n```")
                .contains("**Edit** · /a/B.java\n\n```diff\n")
                .contains("-int x = 1;\n+int x = 2;")
                .contains("**Grep** · cancel  in core\n\n```json\n{\n  \"pattern\" : \"cancel\"")
                .contains("_12.0 s_")
                .doesNotContain("private reasoning");
    }

    /// Select all includes the turn end's time as the last text.
    @Test
    void theTurnEndLineIsSelectable() {
        add(new Block.AssistantText("m", 0, "Done.", false, ""));
        add(new Block.TurnEnd(0.12, 12_000, 1, false, ""));
        paint(900, 400);
        canvas.selectAll();
        assertThat(canvas.selectionText()).contains("Done.").endsWith("12.0 s");
    }

    /// [Items#fence] uses one more backtick than the longest run in the body, and three with a language for a plain
    /// body.
    @Test
    void fencesOutgrowBackticksInTheBody() {
        assertThat(Items.fence("", "a ``` b")).startsWith("````\n").endsWith("\n````");
        assertThat(Items.fence("bash", "ls")).isEqualTo("```bash\nls\n```");
    }

    // ---- turn ends

    /// An interrupted turn reads "interrupted" with its time in the transcript text colour; a failed turn is in danger.
    @Test
    void anInterruptedTurnReadsLikeAnyTextNotAsAFailure() {
        final int stopped = add(new Block.TurnEnd(0.12, 12_000, 1, true, "interrupted"));
        final int failed = add(new Block.TurnEnd(0.12, 12_000, 1, true, "API error"));
        final Style s = Style.of(0);
        final Items.TurnItem interrupted = (Items.TurnItem) canvas.itemAt(stopped);
        final Items.TurnItem failure = (Items.TurnItem) canvas.itemAt(failed);
        assertThat(interrupted.summary()).isEqualTo("interrupted · 12.0 s");
        assertThat(interrupted.color(s)).isEqualTo(Theme.TRANSCRIPT_TEXT);
        assertThat(failure.color(s)).isEqualTo(Theme.DANGER);
    }

    // ---- running calls

    /// An approval naming a call marks that call as waiting; one naming no call marks the last running call of its
    /// tool; clearing approvals restores "running".
    @Test
    void aCallHeldUpByAnApprovalSaysSo() {
        add(running("t1", "Bash", "{\"command\":\"rm -rf build\"}"));
        add(running("q1", "mcp__comando__db_run_sql", "{\"sql\":\"select 1\"}"));
        add(running("q2", "mcp__comando__db_run_sql", "{\"sql\":\"drop table t\"}"));
        assertThat(canvas.cardStatus("t1")).isEqualTo("running");

        canvas.setWaiting(List.of(approval("Bash", "t1")));
        assertThat(canvas.cardStatus("t1")).isEqualTo(KeyText.of("waiting for your approval · ⌃2"));
        assertThat(canvas.cardStatus("q2")).isEqualTo("running");

        // a statement card names no tool call: the last running call of that tool waits
        canvas.setWaiting(List.of(approval("db_run_sql", "")));
        assertThat(canvas.cardStatus("t1")).isEqualTo("running");
        assertThat(canvas.cardStatus("q1")).isEqualTo("running");
        assertThat(canvas.cardStatus("q2")).isEqualTo(KeyText.of("waiting for your approval · ⌃2"));

        canvas.setWaiting(List.of());
        assertThat(canvas.cardStatus("q2")).isEqualTo("running");
    }

    /// An approval received before its call marks the call when it arrives; once finished, it waits for nothing.
    @Test
    void anApprovalForACallThatArrivesLaterMarksItOnArrival() {
        canvas.setWaiting(List.of(approval("Bash", "late")));
        final Block.ToolCall late = running("late", "Bash", "{\"command\":\"ls\"}");
        final int at = add(late);
        assertThat(canvas.cardStatus("late")).isEqualTo(KeyText.of("waiting for your approval · ⌃2"));
        canvas.update(at, late.withResult("a\nb", false));
        assertThat(canvas.cardStatus("late")).as("a finished call waits for nothing").isEmpty();
    }

    /// A running call shows its elapsed seconds, then minutes and seconds, and does not tick while off screen; once
    /// finished it has no status and no running cards remain.
    @Test
    void aRunningCallShowsHowLongItHasRun() {
        final Block.ToolCall bash = running("b", "Bash", "{\"command\":\"sleep 60\"}");
        final int at = add(bash);
        assertThat(canvas.hasRunningCards()).isTrue();
        assertThat(canvas.ticking()).as("no repaint while the canvas is not on screen").isFalse();
        now.addAndGet(12_400);
        assertThat(canvas.cardStatus("b")).isEqualTo("running · 12 s");
        now.addAndGet(65_000);
        assertThat(canvas.cardStatus("b")).isEqualTo("running · 1 min 17 s");
        paint(900, 400);

        canvas.update(at, bash.withResult("", false));
        assertThat(canvas.cardStatus("b")).isEmpty();
        assertThat(canvas.hasRunningCards()).isFalse();
    }

    /// A synthetic session holding every item kind lays out and paints taller than 5 000 px.
    @Test
    void theSyntheticSessionStillPaintsWithEveryItemKind() {
        int n = 0;
        for (final SyntheticSession.Step s : SyntheticSession.steps(10_000)) {
            if (s.index() < 0) {
                canvas.append(n++, s.block());
            } else {
                canvas.update(s.index(), s.block());
            }
        }
        paint(1000, 700);
        assertThat(canvas.contentHeight()).isGreaterThan(5000);
    }
}
