package dev.marregui.comando.ui.widgets.transcript.render;

import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.ApprovalKind;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.testkit.SyntheticSession;
import dev.marregui.comando.ui.kit.theme.Theme;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** How the transcript reads: what opens and closes by itself, what find and copy see, and the status of running calls. */
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

    private int add(Block b) {
        int at = next++;
        canvas.append(at, b);
        return at;
    }

    private void paint(int width, int height) {
        canvas.setSize(width, height);
        canvas.ensureLayout(width);
        canvas.setSize(width, canvas.contentHeight());
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        canvas.paint(g);
        g.dispose();
    }

    private static Block.ToolCall running(String id, String name, String input) {
        return new Block.ToolCall(id, name, input, "", Block.ToolStatus.RUNNING, "");
    }

    private static Approval approval(String toolName, String toolUseId) {
        return new Approval(1, "s", ApprovalKind.TOOL, toolName, "{}", "run it", 0L, toolUseId);
    }

    // ---- hook notes

    @Test
    void consecutiveHookNotesFoldIntoOneClosedLineNamingTheirEvents() {
        add(new Block.UserPrompt("go", "main", ""));
        add(new Block.Note(Block.NoteKind.HOOK, "UserPromptSubmit: " + CONTEXT_JSON, ""));
        add(new Block.Note(Block.NoteKind.HOOK, "SessionStart:startup: hook output", ""));
        add(new Block.Note(Block.NoteKind.HOOK, "UserPromptSubmit: watermark 61%", ""));
        add(new Block.AssistantText("m", 0, "Answer.", false, ""));
        add(new Block.Note(Block.NoteKind.HOOK, "SessionStart:compact: again", ""));
        paint(900, 600);

        List<Item> top = canvas.topLevel();
        assertThat(top).hasSize(4);
        Items.HookGroupItem group = (Items.HookGroupItem) top.get(1);
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

    @Test
    void aHookReplyInJsonReadsAsTheContextItAdds() {
        int at = add(new Block.Note(Block.NoteKind.HOOK, "UserPromptSubmit: " + CONTEXT_JSON, ""));
        Items.NoteItem hook = (Items.NoteItem) canvas.itemAt(at);
        assertThat(hook.shown()).isEqualTo("UserPromptSubmit: Effort protocol: invoke deep first.\nSecond line of context.");
        // a reply that says more than the context is shown whole, so nothing is lost
        assertThat(Items.NoteItem.additionalContext("{\"hookSpecificOutput\":{\"additionalContext\":\"x\"},\"decision\":\"block\"}")).isNull();
        assertThat(Items.NoteItem.additionalContext("plain output")).isNull();
    }

    @Test
    void findAndCopySeeTheFullHookText() {
        add(new Block.Note(Block.NoteKind.HOOK, "UserPromptSubmit: " + CONTEXT_JSON, ""));
        add(new Block.Note(Block.NoteKind.HOOK, "SessionStart:startup: hook output", ""));
        paint(900, 600);
        Items.HookGroupItem group = (Items.HookGroupItem) canvas.topLevel().getFirst();

        assertThat(canvas.markdown()).contains("Second line of context.").contains("SessionStart:startup: hook output");
        assertThat(canvas.find("second line of context", true)).isEqualTo(1);
        assertThat(group.isOpen()).as("find opens the group holding the match").isTrue();
    }

    // ---- notes

    @Test
    void notesOpenAndCloseAndErrorsOpenByDefault() {
        String tail = "Process exited [code=1]\n" + "stderr line\n".repeat(30) + "the cause is on the last line";
        int info = add(new Block.Note(Block.NoteKind.INFO, tail, ""));
        int error = add(new Block.Note(Block.NoteKind.ERROR, tail, ""));
        int shortNote = add(new Block.Note(Block.NoteKind.INTERRUPT, "Interrupted", ""));
        paint(900, 600);

        Items.NoteItem closed = (Items.NoteItem) canvas.itemAt(info);
        assertThat(closed.isOpen()).isFalse();
        assertThat(((Items.NoteItem) canvas.itemAt(error)).isOpen()).isTrue();
        assertThat(canvas.itemAt(shortNote).toggle()).as("a one-line note has nothing more to show").isFalse();

        canvas.selectAll();
        String visible = canvas.selectionText();
        assertThat(visible).as("closed: the first line").contains("Process exited [code=1]");
        // the error note is open and shows its last line; the closed info note does not
        assertThat(visible.split("the cause is on the last line", -1)).hasSize(2);

        assertThat(closed.toggle()).isTrue();
        canvas.layoutFully(900);
        canvas.selectAll();
        assertThat(canvas.selectionText().split("the cause is on the last line", -1)).hasSize(3);
        assertThat(canvas.markdown()).contains("the cause is on the last line");
    }

    @Test
    void findOpensAClosedNoteHoldingTheText() {
        int info = add(new Block.Note(Block.NoteKind.INFO, "first line\nsecond line\nneedle on the third", ""));
        paint(900, 600);
        assertThat(canvas.find("needle", true)).isEqualTo(1);
        assertThat(((Items.NoteItem) canvas.itemAt(info)).isOpen()).isTrue();
    }

    // ---- automatic opening

    @Test
    void aSubagentCardThatOpenedWhileRunningStaysOpenWhenItFinishes() {
        Block.ToolCall agent = running("a1", "Agent", "{\"description\":\"survey\",\"prompt\":\"List the classes.\"}");
        int at = add(agent);
        Items.ToolItem card = (Items.ToolItem) canvas.itemAt(at);
        assertThat(card.isOpen()).isTrue();
        add(new Block.AssistantText("sub", 0, "Step seven of the survey.", false, "a1"));

        canvas.update(at, agent.withResult("The module has 12 classes.", false));
        assertThat(card.isOpen()).as("finishing never closes a card the user did not touch").isTrue();

        // a card that starts closed opens when it fails
        Block.ToolCall bash = running("b1", "Bash", "{\"command\":\"false\"}");
        int b = add(bash);
        assertThat(((Items.ToolItem) canvas.itemAt(b)).isOpen()).isFalse();
        canvas.update(b, bash.withResult("exit 1", true));
        assertThat(((Items.ToolItem) canvas.itemAt(b)).isOpen()).isTrue();
    }

    @Test
    void thinkingOpensWhileItStreamsAndClosesWhenItEnds() {
        int at = add(new Block.Thinking("m", 0, "Considering", 10, true, ""));
        Items.ThinkingItem thinking = (Items.ThinkingItem) canvas.itemAt(at);
        assertThat(thinking.isOpen()).isTrue();
        canvas.update(at, new Block.Thinking("m", 0, "Considering the request", 20, true, ""));
        assertThat(thinking.isOpen()).isTrue();
        canvas.update(at, new Block.Thinking("m", 0, "Considering the request.", 30, false, ""));
        assertThat(thinking.isOpen()).isFalse();

        // the user's choice wins: closed and reopened while streaming, it stays open at the end
        int other = add(new Block.Thinking("m2", 0, "Weighing", 10, true, ""));
        Items.ThinkingItem chosen = (Items.ThinkingItem) canvas.itemAt(other);
        chosen.toggle();
        chosen.toggle();
        canvas.update(other, new Block.Thinking("m2", 0, "Weighing it.", 20, false, ""));
        assertThat(chosen.isOpen()).isTrue();

        // thinking from history arrives finished and stays closed
        int history = add(new Block.Thinking("m3", 0, "Old reasoning", 10, false, ""));
        assertThat(((Items.ThinkingItem) canvas.itemAt(history)).isOpen()).isFalse();
    }

    // ---- find

    @Test
    void clearingTheFindClosesWhatItOpenedExceptTheCurrentMatchAndUserChoices() {
        List<Integer> bashes = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Block.ToolCall c = running("c" + i, "Bash", "{\"command\":\"grep -r token" + i + "\"}");
            int at = add(c);
            canvas.update(at, c.withResult("Lane.java:" + i + " matched", false));
            bashes.add(at);
        }
        paint(900, 600);
        for (int at : bashes) {
            assertThat(((Items.ToolItem) canvas.itemAt(at)).isOpen()).isFalse();
        }

        assertThat(canvas.find("lane.java", true)).isEqualTo(4);
        assertThat(canvas.find("lane.java", true)).as("the second match is current").isEqualTo(4);
        for (int at : bashes) {
            assertThat(((Items.ToolItem) canvas.itemAt(at)).isOpen()).isTrue();
        }
        Items.ToolItem toggled = (Items.ToolItem) canvas.itemAt(bashes.get(3));
        toggled.toggle();
        toggled.toggle();

        canvas.clearFind();
        assertThat(((Items.ToolItem) canvas.itemAt(bashes.get(0))).isOpen()).isFalse();
        assertThat(((Items.ToolItem) canvas.itemAt(bashes.get(1))).isOpen()).as("holds the current match").isTrue();
        assertThat(((Items.ToolItem) canvas.itemAt(bashes.get(2))).isOpen()).isFalse();
        assertThat(toggled.isOpen()).as("the user toggled it during the find").isTrue();
    }

    @Test
    void aNewFindTextClosesWhatThePreviousOneOpened() {
        Block.ToolCall a = running("a", "Bash", "{\"command\":\"echo alpha\"}");
        Block.ToolCall b = running("b", "Bash", "{\"command\":\"echo beta\"}");
        int ia = add(a);
        int ib = add(b);
        paint(900, 600);
        canvas.find("echo", true);
        assertThat(((Items.ToolItem) canvas.itemAt(ia)).isOpen()).isTrue();
        canvas.find("beta", true);
        assertThat(((Items.ToolItem) canvas.itemAt(ia)).isOpen()).isFalse();
        assertThat(((Items.ToolItem) canvas.itemAt(ib)).isOpen()).isTrue();
    }

    // ---- copy

    @Test
    void theMarkdownExportIsAReadableRecord() {
        add(new Block.UserPrompt("Run the tests", "main", ""));
        add(new Block.Thinking("m", 0, "private reasoning", 10, false, ""));
        Block.ToolCall bash = running("b", "Bash", "{\"command\":\"./gradlew test\",\"description\":\"tests\"}");
        int at = add(bash);
        canvas.update(at, bash.withResult("BUILD SUCCESSFUL\n" + "line\n".repeat(450), false));
        add(new Block.ToolCall("e", "Edit", "{\"file_path\":\"/a/B.java\",\"old_string\":\"int x = 1;\",\"new_string\":\"int x = 2;\"}",
                "ok", Block.ToolStatus.DONE, ""));
        add(new Block.ToolCall("g", "Grep", "{\"pattern\":\"cancel\",\"path\":\"core\"}", "3 files", Block.ToolStatus.DONE, ""));
        add(new Block.TurnEnd(0.12, 12_000, 1, false, ""));

        String md = canvas.markdown();
        assertThat(md).contains("> Run the tests")
                .contains("**Bash** · ./gradlew test\n\n```bash\n./gradlew test\n```")
                .contains("```\nBUILD SUCCESSFUL\n")
                .contains("… 52 more lines\n```")
                .contains("**Edit** · /a/B.java\n\n```diff\n")
                .contains("-int x = 1;\n+int x = 2;")
                .contains("**Grep** · cancel  in core\n\n```json\n{\n  \"pattern\" : \"cancel\"")
                .contains("_12.0 s · $0.120 total_")
                .doesNotContain("private reasoning");
    }

    @Test
    void theTurnEndLineIsSelectable() {
        add(new Block.AssistantText("m", 0, "Done.", false, ""));
        add(new Block.TurnEnd(0.12, 12_000, 1, false, ""));
        paint(900, 400);
        canvas.selectAll();
        assertThat(canvas.selectionText()).contains("Done.").endsWith("12.0 s · $0.120 total");
    }

    @Test
    void fencesOutgrowBackticksInTheBody() {
        assertThat(Items.fence("", "a ``` b")).startsWith("````\n").endsWith("\n````");
        assertThat(Items.fence("bash", "ls")).isEqualTo("```bash\nls\n```");
    }

    // ---- turn ends

    @Test
    void anInterruptedTurnReadsMutedNotAsAFailure() {
        int stopped = add(new Block.TurnEnd(0.12, 12_000, 1, true, "interrupted"));
        int failed = add(new Block.TurnEnd(0.12, 12_000, 1, true, "API error"));
        Style s = Style.of(0);
        Items.TurnItem interrupted = (Items.TurnItem) canvas.itemAt(stopped);
        Items.TurnItem failure = (Items.TurnItem) canvas.itemAt(failed);
        assertThat(interrupted.summary()).isEqualTo("interrupted · 12.0 s · $0.120 total");
        assertThat(interrupted.color(s)).isEqualTo(Theme.MUTED);
        assertThat(failure.color(s)).isEqualTo(Theme.DANGER);
    }

    // ---- running calls

    @Test
    void aCallHeldUpByAnApprovalSaysSo() {
        add(running("t1", "Bash", "{\"command\":\"rm -rf build\"}"));
        add(running("q1", "mcp__comando__db_run_sql", "{\"sql\":\"select 1\"}"));
        add(running("q2", "mcp__comando__db_run_sql", "{\"sql\":\"drop table t\"}"));
        assertThat(canvas.cardStatus("t1")).isEqualTo("running");

        canvas.setWaiting(List.of(approval("Bash", "t1")));
        assertThat(canvas.cardStatus("t1")).isEqualTo("waiting for your approval · ⌃3");
        assertThat(canvas.cardStatus("q2")).isEqualTo("running");

        // a statement card names no tool call: the last running call of that tool waits
        canvas.setWaiting(List.of(approval("db_run_sql", "")));
        assertThat(canvas.cardStatus("t1")).isEqualTo("running");
        assertThat(canvas.cardStatus("q1")).isEqualTo("running");
        assertThat(canvas.cardStatus("q2")).isEqualTo("waiting for your approval · ⌃3");

        canvas.setWaiting(List.of());
        assertThat(canvas.cardStatus("q2")).isEqualTo("running");
    }

    @Test
    void anApprovalForACallThatArrivesLaterMarksItOnArrival() {
        canvas.setWaiting(List.of(approval("Bash", "late")));
        Block.ToolCall late = running("late", "Bash", "{\"command\":\"ls\"}");
        int at = add(late);
        assertThat(canvas.cardStatus("late")).isEqualTo("waiting for your approval · ⌃3");
        canvas.update(at, late.withResult("a\nb", false));
        assertThat(canvas.cardStatus("late")).as("a finished call waits for nothing").isEmpty();
    }

    @Test
    void aRunningCallShowsHowLongItHasRun() {
        Block.ToolCall bash = running("b", "Bash", "{\"command\":\"sleep 60\"}");
        int at = add(bash);
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

    @Test
    void theSyntheticSessionStillPaintsWithEveryItemKind() {
        int n = 0;
        for (SyntheticSession.Step s : SyntheticSession.steps(10_000)) {
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
