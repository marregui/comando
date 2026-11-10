package dev.marregui.comando.ui.widgets.transcript.render;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import dev.marregui.comando.core.state.BackgroundTask;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.ui.kit.async.OnEdt;
import dev.marregui.comando.ui.kit.theme.Theme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/// [TranscriptCanvas] reads report first: what a turn did before its report goes on one row when the turn ends.
/// - the steps, interim text included, leave the top level; the report and the turn's end stay; a click opens the row
///   and a second closes it, and the click is kept
/// - a live turn, a turn with no report and a turn of a report alone do not fold; prompts stay at the top level
/// - the row counts calls at any depth, says how many failed in the danger colour, and gives the turn's time, a stored
///   turn's from its span
/// - a turn with a sub-agent running stays open until it ends, also when the tasks reach a rebuilt transcript late
/// - a turn cut short does not fold; opening a row costs what the view shows
/// - a find reads inside a row and opens it until the find is cleared, also when the row forms under the find
/// - the reader's place and the selection survive the row forming
/// - going from prompt to prompt, the export and a card's reveal behave as they do unfolded
@ExtendWith(OnEdt.class)
class TranscriptFoldTest {

    private static final int W = 1000;
    private static final int H = 700;
    /**
     * Edits in a turn whose middle one slice of layout (8 ms) cannot reach. Set by hand: the row of 300 edits already
     * ran out of its first slice on an M2 Max (2026-11-09), so a row of 4 000 runs out on any runner.
     */
    private static final int PAST_A_SLICE = 4_000;
    /**
     * Calls in a turn, each followed by a long interim text, read at its middle. Set by hand: one 8 ms slice laid out
     * about one such text on an M2 Max (2026-11-10), so the 24 texts above the view took 24 slices over 8 frames; a
     * machine 20 times as fast still runs out of the first slice.
     */
    private static final int SLOW_CALLS = 50;
    /** Frames painted while a row forms before its view must have settled: that turn settled in 9 (2026-11-10). */
    private static final int MAX_FRAMES = 200;
    private static final String LAUNCHED = "Async agent launched successfully.\nagentId: a58 (internal ID)\n"
            + "The agent is working in the background.";

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private TranscriptCanvas canvas;
    private JViewport viewport;
    private int next;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    /** The canvas and its viewport, built on the EDT as Swing wants; the test methods run there too. */
    @BeforeEach
    void build() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            canvas = new TranscriptCanvas(_ -> { });
            viewport = new JViewport();
            canvas.clock(now::get);
        });
    }

    private static Block.ToolCall tool(final String id) {
        return new Block.ToolCall(id, "Bash", "{\"command\":\"ls\"}", "ok " + id, Block.ToolStatus.DONE, "");
    }

    private static Block.AssistantText text(final String text) {
        return new Block.AssistantText("m", 0, text, false, "");
    }

    private static Block.TurnEnd end(final long millis) {
        return new Block.TurnEnd(0.1, millis, 2, false, "");
    }

    private static Block.ToolCall backgroundAgent() {
        return new Block.ToolCall("a1", "Agent",
                "{\"description\":\"watch\",\"prompt\":\"Do it.\",\"run_in_background\":true}", "",
                Block.ToolStatus.RUNNING, "");
    }

    private static List<BackgroundTask> running() {
        return List.of(new BackgroundTask("a58", "local_agent", "watch", 0L));
    }

    /** A prompt, three calls with text between them, and a report, without the turn's end. */
    private static Block.ToolCall agent(final String id) {
        return new Block.ToolCall(id, "Agent",
                "{\"description\":\"watch\",\"prompt\":\"Do it.\",\"run_in_background\":true}", "",
                Block.ToolStatus.RUNNING, "");
    }

    private static Block.ToolCall launched(final String id, final String agentId) {
        return agent(id).withResult("Async agent launched successfully.\nagentId: " + agentId
                + " (internal ID)\nThe agent is working in the background.", false);
    }

    private static BackgroundTask task(final String agentId) {
        return new BackgroundTask(agentId, "local_agent", "watch", 0L);
    }

    private int add(final Block b) {
        final int at = next++;
        canvas.append(at, b);
        return at;
    }

    private void turn() {
        add(new Block.UserPrompt("go", "", ""));
        add(tool("t1"));
        add(text("Looking at the store."));
        add(tool("t2"));
        add(tool("t3"));
        add(text("Done: the store drops pending retries."));
    }

    /** A prompt, {@code n} calls and a report of many lines, without the turn's end, laid out in a viewport. */
    private void longTurn(final int n) {
        add(new Block.UserPrompt("go", "", ""));
        for (int k = 0; k < n; k++) {
            add(tool("t" + k));
        }
        add(text("a line of the report\n\n".repeat(120)));
        lay();
    }

    private void lay() {
        viewport.setView(canvas);
        viewport.setSize(W, H);
        canvas.layoutFully(W);
        viewport.doLayout();
    }

    private void paintView() {
        frame();
    }

    /** One frame of the view as Swing paints it: translated to where the view is when the paint starts. */
    private BufferedImage frame() {
        final Rectangle r = viewport.getViewRect();
        final BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = img.createGraphics();
        g.translate(0, -r.y);
        g.setClip(0, r.y, W, H);
        canvas.paint(g);
        g.dispose();
        return img;
    }

    private void readAt(final int y) {
        viewport.setViewPosition(new Point(0, y));
        paintView();
        viewport.doLayout();
    }

    /** The turn's row, which must be there. */
    private Items.StepsItem row() {
        for (final Item item : canvas.topLevel()) {
            if (item instanceof Items.StepsItem fold) {
                return fold;
            }
        }
        throw new AssertionError("no row at the top level");
    }

    private boolean folded() {
        return canvas.topLevel().stream().anyMatch(Items.StepsItem.class::isInstance);
    }

    private List<Class<?>> shape() {
        return canvas.topLevel().stream().<Class<?>>map(Object::getClass).toList();
    }

    private void click(final int x, final int y, final int clicks) {
        canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, 0L, InputEvent.BUTTON1_DOWN_MASK, x, y,
                clicks, false, MouseEvent.BUTTON1));
        canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, 0L, 0, x, y, clicks, false,
                MouseEvent.BUTTON1));
    }

    private void clickRow() {
        lay();
        click(40, canvas.topOf(row()) + 6, 1);
        lay();
    }

    /** Where a step of the row sits in the view. */
    private int stepOnScreen(final Item step) {
        return canvas.topOf(row()) + row().topOf(step) - viewport.getViewPosition().y;
    }

    private Item step(final int i) {
        return row().children().get(i);
    }

    private int pixels(final Color color) {
        canvas.setSize(W, H);
        canvas.ensureLayout(W);
        canvas.setSize(W, canvas.contentHeight());
        final BufferedImage img = new BufferedImage(W, canvas.contentHeight(), BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = img.createGraphics();
        g.setColor(Theme.ground());
        g.fillRect(0, 0, W, canvas.contentHeight());
        g.setClip(0, 0, W, canvas.contentHeight());
        canvas.paint(g);
        g.dispose();
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) | 0xFF000000) == color.getRGB()) {
                    n++;
                }
            }
        }
        return n;
    }

    /// Everything before the report, interim text included, goes on one closed row; the report stays framed and open,
    /// and every block keeps its index for the transcript to update it.
    @Test
    void aTurnThatEndedWithAReportFoldsEverythingBeforeItToOneRow() {
        turn();
        add(end(372_000));

        assertThat(shape()).containsExactly(Items.PromptItem.class, Items.StepsItem.class, Items.TextItem.class,
                Items.TurnItem.class);
        assertThat(row().title()).isEqualTo("3 tools · 6 min");
        assertThat(row().isOpen()).isFalse();
        assertThat(row().failed()).isZero();
        assertThat(((Items.TextItem) canvas.topLevel().get(2)).isReport()).as("the report stays").isTrue();
        assertThat(((Block.ToolCall) canvas.blockAt(3)).toolUseId()).as("blocks keep their index").isEqualTo("t2");
    }

    /// A closed row is as high as a line, whatever it holds: 44 calls take less room than one card.
    @Test
    void aClosedRowIsOneLineHighHoweverManyCallsItHolds() {
        longTurn(44);
        readAt(canvas.contentHeight() - H);
        add(end(60_000));
        lay();

        assertThat(row().height).isLessThan(30);
    }

    /// A click opens the row and shows its steps, and a second closes it, as sibling folds do; the click is the user's.
    @Test
    void aClickOpensTheRowAndASecondClosesIt() {
        turn();
        add(end(60_000));
        lay();
        final int closed = canvas.contentHeight();

        clickRow();

        assertThat(row().isOpen()).isTrue();
        assertThat(row().fold().userToggled()).isTrue();
        assertThat(canvas.contentHeight()).isGreaterThan(closed);

        clickRow();

        assertThat(row().isOpen()).isFalse();
        assertThat(canvas.contentHeight()).isEqualTo(closed);
    }

    /// A card inside an open row opens with a click, as it does at the top level.
    @Test
    void aCardInsideAnOpenRowOpensWithAClick() {
        turn();
        add(end(60_000));
        clickRow();

        click(40, canvas.topOf(row()) + row().topOf(step(0)) + 8, 1);

        assertThat(canvas.isCardOpen("t1")).isTrue();
    }

    /// A turn still going shows all it does: nothing folds before its end.
    @Test
    void aLiveTurnStaysOpen() {
        turn();

        assertThat(folded()).isFalse();
    }

    /// A turn that ended on a tool call has no report, and all it did stays in view.
    @Test
    void aTurnWithNoReportDoesNotFold() {
        add(new Block.UserPrompt("go", "", ""));
        add(text("Looking."));
        add(tool("t1"));
        add(end(1_000));

        assertThat(folded()).isFalse();
    }

    /// A report alone has nothing before it to fold, and a row of hook notes is no shorter folded.
    @Test
    void aTurnOfAReportAloneOrOfHooksAndAReportDoesNotFold() {
        add(new Block.UserPrompt("go", "", ""));
        add(text("Short answer."));
        add(end(1_000));
        add(new Block.UserPrompt("again", "", ""));
        add(new Block.Note(Block.NoteKind.HOOK, "UserPromptSubmit: a hook", ""));
        add(text("Another."));
        add(end(1_000));

        assertThat(folded()).isFalse();
    }

    /// Interim text with no call folds too, and the row says so; what came after the report stays below it.
    @Test
    void textWithNoCallFoldsAndWhatFollowsTheReportStaysBelowIt() {
        add(new Block.UserPrompt("go", "", ""));
        add(text("Thinking aloud."));
        add(text("The answer."));
        add(new Block.Note(Block.NoteKind.INFO, "a note", ""));
        add(end(40_000));

        assertThat(shape()).containsExactly(Items.PromptItem.class, Items.StepsItem.class, Items.TextItem.class,
                Items.NoteItem.class, Items.TurnItem.class);
        assertThat(row().title()).isEqualTo("no tools · 40 s");
    }

    /// A prompt sent during a turn stays at the top level: the row holds what came since it, and what came before it
    /// stays as it was.
    @Test
    void aPromptSentDuringATurnStaysAtTheTopLevel() {
        add(new Block.UserPrompt("go", "", ""));
        add(tool("t1"));
        add(new Block.UserPrompt("and also", "", ""));
        add(tool("t2"));
        add(text("Both done."));
        add(end(1_000));

        assertThat(shape()).containsExactly(Items.PromptItem.class, Items.ToolItem.class, Items.PromptItem.class,
                Items.StepsItem.class, Items.TextItem.class, Items.TurnItem.class);
        assertThat(row().title()).startsWith("1 tool · ");
    }

    /// The row counts the calls of a sub-agent inside it too.
    @Test
    void theRowCountsCallsAtAnyDepth() {
        add(new Block.UserPrompt("go", "", ""));
        add(new Block.ToolCall("a1", "Agent", "{\"description\":\"d\",\"prompt\":\"p\"}", "fin",
                Block.ToolStatus.DONE, ""));
        add(new Block.ToolCall("g1", "Grep", "{\"pattern\":\"q\"}", "x", Block.ToolStatus.DONE, "a1"));
        add(new Block.ToolCall("g2", "Grep", "{\"pattern\":\"q\"}", "x", Block.ToolStatus.ERROR, "a1"));
        add(text("Found it."));
        add(end(1_000));

        assertThat(row().title()).startsWith("3 tools");
        assertThat(row().failed()).isEqualTo(1);
    }

    /// A failed call does not open the row: it says so in the danger colour, and a result that fails later says so too.
    @Test
    void aFailedCallShowsOnTheClosedRowInTheDangerColour() {
        add(new Block.UserPrompt("go", "", ""));
        final Block.ToolCall good = tool("t1");
        final int at = add(good);
        add(text("Done."));
        add(end(1_000));
        assertThat(pixels(Theme.DANGER)).as("no failure, no danger").isZero();

        canvas.update(at, good.withResult("boom", true));

        assertThat(row().failed()).isEqualTo(1);
        assertThat(row().isOpen()).isFalse();
        assertThat(pixels(Theme.DANGER)).as("the failure on the row").isPositive();
    }

    /// A turn that ended with a sub-agent at work in the background shows that work until the sub-agent ends, then
    /// closes.
    @Test
    void aTurnWithARunningSubAgentStaysOpenUntilItEnds() {
        add(new Block.UserPrompt("go", "", ""));
        final Block.ToolCall a = backgroundAgent();
        final int at = add(a);
        canvas.update(at, a.withResult(LAUNCHED, false));
        canvas.setBackgroundTasks(running());
        add(text("It runs on."));
        add(end(1_000));

        assertThat(row().isOpen()).as("the sub-agent works").isTrue();

        canvas.setBackgroundTasks(List.of());

        assertThat(row().isOpen()).as("the sub-agent ended").isFalse();
    }

    /// A sub-agent's card with no result yet holds the row open as well, until its result arrives.
    @Test
    void aSubAgentWithNoResultHoldsTheRowOpenUntilItsResultArrives() {
        add(new Block.UserPrompt("go", "", ""));
        final Block.ToolCall a = new Block.ToolCall("a1", "Agent", "{\"description\":\"d\",\"prompt\":\"p\"}", "",
                Block.ToolStatus.RUNNING, "");
        final int at = add(a);
        add(text("Waiting for it."));
        add(end(1_000));
        assertThat(row().isOpen()).isTrue();

        canvas.update(at, a.withResult("fin", false));

        assertThat(row().isOpen()).isFalse();
    }

    /// A click on a row held open is the user's: it closes the row and the row stays closed; opened again, the end of
    /// the sub-agent does not close it.
    @Test
    void theUsersClickOutlivesTheSubAgentsEnd() {
        add(new Block.UserPrompt("go", "", ""));
        final Block.ToolCall a = backgroundAgent();
        final int at = add(a);
        canvas.update(at, a.withResult(LAUNCHED, false));
        canvas.setBackgroundTasks(running());
        add(text("It runs on."));
        add(end(1_000));

        clickRow();
        assertThat(row().isOpen()).isFalse();
        clickRow();
        assertThat(row().isOpen()).isTrue();

        canvas.setBackgroundTasks(List.of());

        assertThat(row().isOpen()).as("the click stands").isTrue();
    }

    /// A stored turn folds as a live one does and reads its time from its span; the turn's own line stays as it was:
    /// not
    /// timed, with no summary.
    @Test
    void aStoredTurnFoldsWithTheTimeOfItsSpan() {
        turn();
        final Block.TurnEnd stored = Block.TurnEnd.stored(125_000);
        add(stored);

        assertThat(row().title()).isEqualTo("3 tools · 2 min");
        assertThat(stored.timed()).isFalse();
        assertThat(((Items.TurnItem) canvas.topLevel().getLast()).summary()).isEmpty();
    }

    /// A stored turn with no span gives no time, not a wrong one.
    @Test
    void aStoredTurnWithNoSpanSaysNoTime() {
        turn();
        add(Block.TurnEnd.stored());

        assertThat(row().title()).isEqualTo("3 tools");
    }

    /// A find reads inside a closed row and opens it and the card it matched in; a find that moves on closes both.
    @Test
    void aFindOpensTheRowUntilItMovesOn() {
        turn();
        add(end(1_000));
        lay();

        assertThat(canvas.find("ok t2", true)).isEqualTo(1);

        assertThat(row().isOpen()).isTrue();
        assertThat(row().fold().revealed()).isTrue();
        assertThat(canvas.isCardOpen("t2")).isTrue();

        assertThat(canvas.find("nothing says this", true)).isZero();

        assertThat(row().isOpen()).isFalse();
        assertThat(canvas.isCardOpen("t2")).isFalse();
    }

    /// Clearing a find leaves the current match where the reader sees it: its row stays open as if never found, the
    /// same as any fold the find opened, and is no longer the find's to close.
    @Test
    void clearingAFindKeepsTheRowOfTheCurrentMatchOpen() {
        turn();
        add(end(1_000));
        lay();
        assertThat(canvas.find("ok t2", true)).isEqualTo(1);

        canvas.clearFind();

        assertThat(row().isOpen()).isTrue();
        assertThat(row().fold().revealed()).isFalse();
    }

    /// Interim text matches like any other, and a word of the row itself opens nothing.
    @Test
    void aFindReadsInterimTextAndNotTheRow() {
        turn();
        add(end(1_000));
        lay();

        assertThat(canvas.find("3 tools", true)).isZero();
        assertThat(row().isOpen()).isFalse();
        assertThat(canvas.find("Looking at the store", true)).isEqualTo(1);
        assertThat(row().isOpen()).isTrue();
    }

    /// A row that forms under a find with a match in it opens, so the match stays counted and in view.
    @Test
    void aRowThatFormsUnderAFindOpensForItsMatch() {
        turn();
        lay();
        assertThat(canvas.find("Looking at the store", true)).isEqualTo(1);

        add(end(1_000));

        assertThat(row().isOpen()).isTrue();
        assertThat(canvas.findMatches()).hasSize(1);

        assertThat(canvas.find("nothing says this", true)).isZero();

        assertThat(row().isOpen()).isFalse();
    }

    /// A row the user opened stays open when a find through it is cleared.
    @Test
    void aFindLeavesARowTheUserOpenedOpen() {
        turn();
        add(end(1_000));
        clickRow();

        assertThat(canvas.find("Looking at the store", true)).isEqualTo(1);
        canvas.clearFind();

        assertThat(row().isOpen()).isTrue();
    }

    /// Going from prompt to prompt lands on the same prompts with every row closed or open.
    @Test
    void goingFromPromptToPromptIsUnchanged() {
        turn();
        add(end(1_000));
        add(new Block.UserPrompt("next", "", ""));
        add(tool("u1"));
        add(text("And done."));
        add(end(1_000));
        lay();

        final Item second = canvas.topLevel().get(4);
        assertThat(second).isInstanceOf(Items.PromptItem.class);
        assertThat(canvas.promptTop(canvas.contentHeight(), false)).isEqualTo(canvas.topOf(second));
        assertThat(canvas.promptTop(canvas.topOf(second), false)).isEqualTo(canvas.topOf(canvas.topLevel().getFirst()));
        assertThat(canvas.promptTop(canvas.topOf(canvas.topLevel().getFirst()), true)).isEqualTo(canvas.topOf(second));
    }

    /// The export does not depend on what is folded: it is what the turn reads unfolded, followed by the turn's footer.
    @Test
    void theExportIsTheSameAsUnfolded() {
        turn();
        final String unfolded = canvas.markdown();
        final Block.TurnEnd end = end(1_000);

        add(end);

        assertThat(folded()).isTrue();
        assertThat(canvas.markdown()).isEqualTo(unfolded + new Items.TurnItem(end).markdown() + "\n\n");
    }

    /// A reader on the report, away from the end, keeps the same line in view while the steps above it become a row.
    @Test
    void aReaderOnTheReportKeepsTheirPlaceWhenTheStepsAboveFold() {
        longTurn(30);
        final Item report = canvas.topLevel().getLast();
        readAt(canvas.topOf(report) + 200);
        final int before = canvas.topOf(report) - viewport.getViewPosition().y;

        add(end(1_000));
        viewport.doLayout();
        paintView();

        assertThat(row().isOpen()).as("the reader is not on the steps").isFalse();
        assertThat(canvas.topOf(report) - viewport.getViewPosition().y).isEqualTo(before);
    }

    /// A reader among the steps, away from the end, keeps them: the row forms open and what they read does not move.
    @Test
    void aReaderOnTheStepsKeepsThemOpenAndInPlace() {
        longTurn(30);
        final Item card = canvas.topLevel().get(15);
        readAt(canvas.topOf(card) - 40);
        final int before = canvas.topOf(card) - viewport.getViewPosition().y;

        add(end(1_000));
        viewport.doLayout();
        paintView();

        assertThat(row().isOpen()).as("the reader is on the steps").isTrue();
        assertThat(stepOnScreen(card)).isEqualTo(before);
    }

    /// A transcript at its end stays there: the steps fold whatever of them it showed.
    @Test
    void aTranscriptAtItsEndFoldsTheSteps() {
        longTurn(30);
        readAt(canvas.contentHeight() - H);

        add(end(1_000));
        lay();

        assertThat(row().isOpen()).isFalse();
    }

    /// A selection among the steps keeps them shown and selected: the row forms open.
    @Test
    void aSelectionAmongTheStepsKeepsThemOpen() {
        turn();
        lay();
        canvas.selectAll();
        final String selected = canvas.selectionText();
        assertThat(selected).contains("Looking at the store");

        add(end(1_000));
        lay();

        assertThat(row().isOpen()).isTrue();
        assertThat(canvas.selectionText()).startsWith(selected);
    }

    /// With a selection on the report alone the steps fold, and the selection stays.
    @Test
    void aSelectionOnTheReportDoesNotHoldTheSteps() {
        longTurn(3);
        final Item report = canvas.topLevel().getLast();
        readAt(canvas.contentHeight() - H);
        click(60, canvas.topOf(report) + 30, 2);
        final String selected = canvas.selectionText();
        assertThat(selected).isNotEmpty();

        add(end(1_000));
        lay();

        assertThat(row().isOpen()).isFalse();
        assertThat(canvas.selectionText()).isEqualTo(selected);
    }

    /// Going to a card inside a closed row opens the row and scrolls to the card, and it stays open after.
    @Test
    void revealingACardOpensItsRowAndGoesToIt() {
        longTurn(40);
        readAt(canvas.contentHeight() - H);
        add(end(1_000));
        lay();
        readAt(canvas.contentHeight() - H);
        assertThat(row().isOpen()).isFalse();

        assertThat(canvas.reveal("t20")).isTrue();
        viewport.doLayout();
        paintView();

        assertThat(row().isOpen()).isTrue();
        assertThat(row().fold().userToggled()).as("a choice, kept").isTrue();
        assertThat(stepOnScreen(step(20))).isBetween(0, H);
    }

    /// A rebuild folds anew: the turn's row closes again and no place of the transcript before it holds a row open.
    @Test
    void aRebuildFoldsAnewFromAViewThatWasOnTheSteps() {
        longTurn(30);
        readAt(canvas.topOf(canvas.topLevel().get(15)));

        canvas.clear();
        next = 0;
        add(new Block.UserPrompt("go", "", ""));
        add(tool("t0"));
        add(text("Done."));
        add(end(1_000));

        assertThat(row().isOpen()).isFalse();
    }

    /// The report keeps its place as the last one: the widget's way to it finds it below the row.
    @Test
    void theReportTopIsBelowTheRow() {
        turn();
        add(end(1_000));
        lay();

        assertThat(canvas.reportTop()).isEqualTo(canvas.topOf(canvas.topLevel().get(2)));
        assertThat(canvas.reportTop()).isGreaterThan(canvas.topOf(row()));
    }

    /// Two rows held open close in one update while the selection lies in a report: the first has let its steps go
    /// when the second asks where the selection is, and nothing reads them.
    @Test
    void heldRowsThatCloseTogetherUnderASelectionLetTheirStepsGo() {
        add(new Block.UserPrompt("go", "", ""));
        final int a1 = add(agent("a1"));
        add(text("Interim one."));
        add(text("Report one."));
        add(end(1_000));
        add(new Block.UserPrompt("go again", "", ""));
        final int a2 = add(agent("a2"));
        add(text("Interim two."));
        final int last = add(text("Report two."));
        add(end(1_000));
        canvas.update(a1, launched("a1", "a58"));
        canvas.update(a2, launched("a2", "b77"));
        canvas.setBackgroundTasks(List.of(task("a58"), task("b77")));
        lay();
        final List<Item> items = canvas.topLevel();
        final Item report = items.stream().filter(i -> i.block == canvas.blockAt(last)).findFirst().orElseThrow();
        click(60, canvas.topOf(report) + 12, 2);

        canvas.setBackgroundTasks(List.of());

        assertThat(canvas.topLevel()).allSatisfy(r -> assertThat(r instanceof Items.StepsItem row
                && row.isOpen()).isFalse());
    }

    /// A row of many steps opens at the cost of what the view shows; the idle pass lays out what lies around the view,
    /// as the view moves, and an exact layout all of it.
    @Test
    void openingARowLaysOutWhatTheViewShows() {
        longTurn(2_000);
        readAt(canvas.contentHeight());
        add(end(1_000));
        lay();
        readAt(0);
        assertThat(row().isOpen()).isFalse();

        click(40, canvas.topOf(row()) + 6, 1);
        canvas.ensureLayout(W);

        assertThat(row().isOpen()).isTrue();
        final List<Item> steps = row().children();
        final long laid = steps.stream().filter(Item::laidOut).count();
        assertThat(steps.getFirst().laidOut()).isTrue();
        assertThat(laid).as("what the view shows, not the turn").isLessThan(200);

        canvas.refineAll();
        final long near = steps.stream().filter(Item::laidOut).count();
        assertThat(near).as("the idle pass, around the view").isGreaterThan(laid);

        readAt(canvas.contentHeight() / 2);
        canvas.refineAll();
        assertThat(steps.stream().filter(Item::laidOut).count()).as("the view moved down the row").isGreaterThan(near);

        canvas.layoutFully(W);
        assertThat(steps).allMatch(Item::laidOut);
    }

    /// A rebuilt transcript reads its blocks before the session's tasks: the row closes on a card that has not been
    /// told, and opens when it is, held as the sub-agent's row is.
    @Test
    void aRowOpensWhenTheTasksOfARebuiltTranscriptSayItsSubAgentWorks() {
        canvas.setBackgroundTasks(List.of());
        add(new Block.UserPrompt("go", "", ""));
        add(launched("a1", "a58"));
        add(text("It runs on."));
        add(text("The report."));
        add(end(1_000));
        assertThat(row().isOpen()).as("no task is known").isFalse();

        canvas.setBackgroundTasks(running());

        assertThat(row().isOpen()).isTrue();
        canvas.setBackgroundTasks(List.of());
        assertThat(row().isOpen()).as("the sub-agent ended").isFalse();
    }

    /// A tool's name is on no text a find reads: no match, and no row opened for it.
    @Test
    void aFindOfAToolsNameOpensNoRow() {
        turn();
        add(end(1_000));
        lay();

        assertThat(canvas.find("Bash", true)).isZero();

        assertThat(row().isOpen()).isFalse();
    }

    /// A failed or interrupted turn stopped somewhere: its steps stay in the open, as the record of how far it got.
    @Test
    void aTurnCutShortDoesNotFold() {
        turn();
        add(new Block.TurnEnd(0.1, 1_000, 2, true, "interrupted"));
        assertThat(folded()).as("interrupted").isFalse();

        turn();
        add(new Block.TurnEnd(0.1, 1_000, 2, true, "API Error: 529 overloaded"));
        assertThat(folded()).as("failed").isFalse();
    }

    /// A last turn that ended with no report has none to go to: an earlier turn's is not what it came to.
    @Test
    void aLastTurnWithNoReportHasNoReportTop() {
        turn();
        add(end(1_000));
        add(new Block.UserPrompt("again", "", ""));
        add(tool("t9"));
        add(new Block.TurnEnd(0.1, 1_000, 2, true, "API Error: 529 overloaded"));
        lay();

        assertThat(canvas.reportTop()).isEqualTo(-1);
    }

    /// A row forms open under a reader over heights only estimated, the window just changed: the step at the view's top
    /// stays on screen where it was.
    @Test
    void aStepInViewDoesNotMoveWhenItsRowFormsOverEstimates() {
        stepInViewStaysAsItsRowForms(300);
    }

    /// A turn too long for one slice of layout to reach the step in view: the slices after it make the steps above
    /// exact, and the step stays where it was all along.
    @Test
    void aStepInViewDoesNotMoveWhileTheSlicesAboveItCatchUp() {
        stepInViewStaysAsItsRowForms(PAST_A_SLICE);
    }

    /** A turn of {@code calls} edits read at its middle after a window change; its row forms when it ends. */
    private void stepInViewStaysAsItsRowForms(final int calls) {
        add(new Block.UserPrompt("go", "", ""));
        for (int k = 0; k < calls; k++) {
            add(new Block.ToolCall("e" + k, "Edit",
                    "{\"file_path\":\"/w/F.java\",\"old_string\":\"" + "a line\\n".repeat(20)
                    + "\",\"new_string\":\"b\"}", "ok", Block.ToolStatus.DONE, ""));
        }
        add(text("The report."));
        lay();
        final Item step = canvas.topLevel().get(calls / 2);
        readAt(canvas.topOf(step));
        canvas.ensureLayout(W - 200);
        final int before = canvas.topOf(step) - viewport.getViewPosition().y;

        add(end(1_000));
        canvas.ensureLayout(W - 200);
        viewport.doLayout();

        assertThat(row().isOpen()).isTrue();
        assertThat(canvas.topOf(row()) + row().topOf(step) - viewport.getViewPosition().y)
                .isCloseTo(before, within(1));
        readAt(viewport.getViewPosition().y);
        canvas.refineAll();
        assertThat(canvas.topOf(row()) + row().topOf(step) - viewport.getViewPosition().y)
                .as("after the idle pass").isCloseTo(before, within(1));
    }

    /// A row forms open under a reader in a transcript never laid out whole, so one slice of layout reaches only the
    /// first of the steps above the view: every frame painted while the row forms, the frames in which the view moves to
    /// keep the step in view in place included, draws that step where it was.
    @Test
    void everyFrameDrawsTheStepInViewInItsPlaceWhileItsRowForms() {
        add(new Block.UserPrompt("go", "", ""));
        for (int k = 0; k < SLOW_CALLS; k++) {
            add(new Block.ToolCall("e" + k, "Edit", "{\"file_path\":\"/w/F" + k + ".java\",\"old_string\":\"a\","
                    + "\"new_string\":\"b\"}", "ok", Block.ToolStatus.DONE, ""));
            add(text(("Interim text " + k + " wraps" + " over the width of the view".repeat(30) + ".\n\n").repeat(8)));
        }
        add(text("The report."));
        viewport.setView(canvas);
        viewport.setSize(W, H);
        canvas.ensureLayout(W);
        viewport.doLayout();
        final Item step = canvas.topLevel().get(SLOW_CALLS);
        readAt(canvas.topOf(step));
        final int before = canvas.topOf(step) - viewport.getViewPosition().y;

        add(end(1_000));
        canvas.ensureLayout(W);
        viewport.doLayout();
        final List<Integer> bands = new ArrayList<>();
        final List<Integer> moves = new ArrayList<>();
        final int from = Math.max(0, before);
        final int to = Math.min(H, before + step.height);
        for (int f = 0; f < MAX_FRAMES && (f == 0 || moves.getLast() != 0); f++) {
            final int y = viewport.getViewPosition().y;
            bands.add(band(frame(), from, to));
            viewport.doLayout();
            moves.add(viewport.getViewPosition().y - y);
        }
        assertThat(moves).as("the view settles, frame by frame").endsWith(0);
        assertThat(moves).as("a frame in which the view moved").hasSizeGreaterThan(1);
        canvas.refineAll();
        assertThat(canvas.topOf(row()) + row().topOf(step) - viewport.getViewPosition().y)
                .as("after the idle pass").isCloseTo(before, within(1));
        final BufferedImage settled = frame();
        assertThat(painted(settled, from, to)).as("the step in view is painted").isTrue();

        final int expected = band(settled, from, to);
        final List<String> displaced = new ArrayList<>();
        for (int f = 0; f < bands.size(); f++) {
            if (bands.get(f) != expected) {
                displaced.add("frame " + f + ", the view moved " + moves.get(f) + " px");
            }
        }
        assertThat(displaced).as("frames that drew the step in view away from its place").isEmpty();
    }

    /** The rows {@code from} to {@code to} of {@code img}, pixel for pixel, as one hash: frames compare by it. */
    private static int band(final BufferedImage img, final int from, final int to) {
        return Arrays.hashCode(img.getRGB(0, from, W, to - from, null, 0, W));
    }

    /** True when a pixel in rows {@code from} to {@code to} of {@code img} is painted. */
    private static boolean painted(final BufferedImage img, final int from, final int to) {
        for (final int argb : img.getRGB(0, from, W, to - from, null, 0, W)) {
            if (argb != 0) {
                return true;
            }
        }
        return false;
    }

    /// A selection from the prompt to the report across an open row whose steps below the view are not laid out yet
    /// copies every step, as it does once they are.
    @Test
    void aCopyAcrossARowStillLayingOutTakesEveryStep() {
        add(new Block.UserPrompt("gather the facts", "", ""));
        add(text("Starting with the store."));
        for (int k = 0; k < 600; k++) {
            add(tool("t" + k));
            if (k % 50 == 49) {
                add(text("note " + k));
            }
        }
        add(text("the report"));
        readAt(canvas.contentHeight());
        add(end(1_000));
        lay();
        final Item report = canvas.topLevel().get(canvas.topLevel().indexOf(row()) + 1);
        readAt(canvas.topOf(report) - 10);
        click(60, canvas.topOf(report) + 12, 2);
        assertThat(canvas.selectionText()).as("the anchor").isNotEmpty();
        readAt(0);
        click(40, canvas.topOf(row()) + 6, 1);
        canvas.ensureLayout(W);
        final Item first = row().children().getFirst();
        final int y = canvas.topOf(row()) + row().topOf(first) + 12;
        canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, 0L,
                InputEvent.BUTTON1_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK, 60, y, 1, false, MouseEvent.BUTTON1));
        canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, 0L, InputEvent.SHIFT_DOWN_MASK, 60, y, 1,
                false, MouseEvent.BUTTON1));
        assertThat(row().children().stream().filter(Item::laidOut).count()).as("still laying out").isLessThan(612);

        final String partly = canvas.selectionText();
        canvas.layoutFully(W);

        assertThat(partly).contains("note 549").isEqualTo(canvas.selectionText());
    }

    /// A word only in a card's description, which the card does not show, is no match and opens nothing.
    @Test
    void aWordTheCardDoesNotShowOpensNoRow() {
        add(new Block.UserPrompt("go", "", ""));
        add(new Block.ToolCall("t1", "Bash", "{\"command\":\"ls\",\"description\":\"zebra\"}", "ok",
                Block.ToolStatus.DONE, ""));
        add(text("The report."));
        add(end(1_000));
        lay();

        assertThat(canvas.find("zebra", true)).isZero();

        assertThat(row().isOpen()).isFalse();
    }

    /// A row a find opened holds for a sub-agent that starts under it: the next find does not close it.
    @Test
    void aRowAFindOpenedHoldsForASubAgentThatStarts() {
        canvas.setBackgroundTasks(List.of());
        add(new Block.UserPrompt("go", "", ""));
        add(launched("a1", "a58"));
        add(text("zebra on the way."));
        add(text("The report."));
        add(end(1_000));
        lay();
        assertThat(canvas.find("zebra", true)).isEqualTo(1);
        canvas.setBackgroundTasks(running());

        canvas.find("nothing says this", true);

        assertThat(row().isOpen()).isTrue();
    }
}
