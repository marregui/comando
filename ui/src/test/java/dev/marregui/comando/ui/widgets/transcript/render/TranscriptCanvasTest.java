package dev.marregui.comando.ui.widgets.transcript.render;

import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.testkit.SyntheticSession;
import dev.marregui.comando.ui.kit.async.OnEdt;
import dev.marregui.comando.ui.kit.theme.Theme;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OnEdt.class)
class TranscriptCanvasTest {

    private final List<String> opened = new ArrayList<>();
    private final TranscriptCanvas canvas = new TranscriptCanvas(opened::add);

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    private void load(List<SyntheticSession.Step> steps) {
        int n = 0;
        for (SyntheticSession.Step s : steps) {
            if (s.index() < 0) {
                canvas.append(n++, s.block());
            } else {
                canvas.update(s.index(), s.block());
            }
        }
    }

    private void paint(int width, int height) {
        canvas.setSize(width, height);
        canvas.ensureLayout(width);
        canvas.setSize(width, canvas.contentHeight());
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.translate(0, -(canvas.contentHeight() - height));
        g.setClip(0, canvas.contentHeight() - height, width, height);
        canvas.paint(g);
        g.dispose();
    }

    private static Block.Thinking thought(int index, String parent) {
        return new Block.Thinking("m", index, "", 812, false, parent);
    }

    private static Block.AssistantText text(String text) {
        return new Block.AssistantText("m", 0, text, false, "");
    }

    private static Block.ToolCall call(String id, String parent) {
        return new Block.ToolCall(id, "Bash", "{\"command\":\"ls\"}", "ok", Block.ToolStatus.DONE, parent);
    }

    @Test
    void thinkingShowsUntilTheNextItemInItsListTakesItsPlace() {
        canvas.append(0, new Block.UserPrompt("go", "p", ""));
        canvas.append(1, thought(0, ""));
        assertThat(canvas.topLevel().getLast()).as("while it is the latest, it shows").isInstanceOf(Items.ThinkingItem.class);
        canvas.append(2, thought(1, ""));
        canvas.append(3, call("t1", ""));
        assertThat(canvas.topLevel()).extracting(i -> i.getClass().getSimpleName()).containsExactly("PromptItem", "ToolItem");
        assertThat(canvas.itemCount()).as("the blocks are all still there").isEqualTo(4);

        canvas.append(4, new Block.ToolCall("a", "Agent", "{}", "", Block.ToolStatus.RUNNING, ""));
        canvas.append(5, thought(0, "a"));
        canvas.append(6, new Block.AssistantText("s", 0, "found it", false, "a"));
        assertThat(((Items.ToolItem) canvas.itemAt(4)).children()).as("inside a subagent's card too")
                .extracting(i -> i.getClass().getSimpleName()).containsExactly("TextItem");
        paint(800, 400);
    }

    @Test
    void theLastAnswerOfATurnIsItsConclusion() {
        canvas.append(0, new Block.UserPrompt("go", "p", ""));
        canvas.append(1, text("Looking at the store first."));
        canvas.append(2, call("t1", ""));
        canvas.append(3, text("Done: the store drops pending retries."));
        canvas.append(4, thought(0, ""));
        assertThat(((Items.TextItem) canvas.itemAt(3)).isConclusion()).as("not before the turn ends").isFalse();
        canvas.append(5, new Block.TurnEnd(0.1, 1_000, 2, false, ""));
        assertThat(((Items.TextItem) canvas.itemAt(1)).isConclusion()).isFalse();
        assertThat(((Items.TextItem) canvas.itemAt(3)).isConclusion()).isTrue();

        canvas.append(6, new Block.UserPrompt("again", "p", ""));
        canvas.append(7, text("Checking."));
        canvas.append(8, call("t2", ""));
        canvas.append(9, new Block.TurnEnd(0.1, 1_000, 2, false, ""));
        assertThat(((Items.TextItem) canvas.itemAt(7)).isConclusion()).as("a turn that ends on a tool call has none").isFalse();

        // a stored conversation's turn end, as its messages record it: the last answer of the file is a conclusion too
        canvas.append(10, new Block.UserPrompt("history", "", ""));
        canvas.append(11, text("An old answer."));
        canvas.append(12, Block.TurnEnd.stored());
        canvas.append(13, new Block.Note(Block.NoteKind.INFO, "Stored conversation loaded", ""));
        assertThat(((Items.TextItem) canvas.itemAt(11)).isConclusion()).isTrue();
        assertThat(((Items.TurnItem) canvas.itemAt(12)).summary()).as("a stored turn records no time").isEmpty();
        assertThat(((Items.TurnItem) canvas.itemAt(12)).markdown()).isEmpty();

        // a file from a version that stored no stop reasons has no turn ends: the next prompt closes the turn
        canvas.append(14, new Block.UserPrompt("older", "", ""));
        canvas.append(15, text("An older answer."));
        canvas.append(16, new Block.UserPrompt("next", "", ""));
        assertThat(((Items.TextItem) canvas.itemAt(15)).isConclusion()).isTrue();
        paint(800, 600);
    }

    @Test
    void aConclusionIsLaidOutInGold() {
        Style s = Style.of(0);
        assertThat(s.conclusion().text).isEqualTo(Theme.GOLD);
        assertThat(s.conclusion()).as("made once, so its layouts stay valid").isSameAs(s.conclusion());
        assertThat(s.conclusion().body).isEqualTo(s.body);
    }

    @Test
    void wholeSessionLaysOutAndPaints() {
        load(SyntheticSession.steps(24));
        paint(1000, 700);
        assertThat(canvas.contentHeight()).isGreaterThan(5000);
        // narrower means taller: the text wraps
        int wide = canvas.contentHeight();
        canvas.layoutFully(500);
        assertThat(canvas.contentHeight()).isGreaterThan(wide);
    }

    @Test
    void aWidthChangeLaysOutTheViewNowAndTheRestWhenIdle() {
        load(SyntheticSession.steps(10_000));
        canvas.setSize(1000, 700);
        canvas.layoutFully(1000);
        canvas.ensureLayout(600);
        assertThat(canvas.isEstimated()).isTrue();
        int guessed = canvas.contentHeight();
        canvas.refineAll();
        assertThat(canvas.isEstimated()).isFalse();
        int exact = canvas.contentHeight();
        canvas.layoutFully(600);
        assertThat(canvas.contentHeight()).isEqualTo(exact);
        // the guess is in the right range: within a factor of two of the exact height
        assertThat(guessed).isBetween(exact / 2, exact * 2);
    }

    @Test
    void aRebuiltViewLaysOutWhatIsVisibleNowAndTheRestWhenIdle() {
        // a panel switching sessions rebuilds the view from the session's history: no block has been laid out yet
        load(SyntheticSession.steps(10_000));
        canvas.setSize(1000, 700);
        canvas.ensureLayout(1000);
        List<Item> items = canvas.topLevel();
        long laid = items.stream().filter(Item::laidOut).count();
        assertThat(laid).as("only the blocks in view are laid out now").isPositive().isLessThan(items.size() / 2);
        assertThat(canvas.isEstimated()).isTrue();
        int guessed = canvas.contentHeight();

        paint(1000, 700);
        assertThat(items.getLast().laidOut()).as("the end, where the view follows, is laid out when painted").isTrue();

        canvas.refineAll();
        assertThat(canvas.isEstimated()).isFalse();
        assertThat(items).as("the idle pass lays out the rest").allMatch(Item::laidOut);
        int exact = canvas.contentHeight();
        TranscriptCanvas whole = new TranscriptCanvas(opened::add);
        int n = 0;
        for (SyntheticSession.Step st : SyntheticSession.steps(10_000)) {
            if (st.index() < 0) {
                whole.append(n++, st.block());
            } else {
                whole.update(st.index(), st.block());
            }
        }
        whole.layoutFully(1000);
        assertThat(exact).as("the same height as laying out everything at once").isEqualTo(whole.contentHeight());
        assertThat(guessed).as("the first guess is in the right range").isBetween(exact / 2, exact * 2);
    }

    @Test
    void subagentBlocksNestInTheirCall() {
        load(SyntheticSession.steps(10_000));
        canvas.ensureLayout(1000);
        // 8 turns of prompt, thinking, text, 5 calls, text, turn end, plus the subagent's call; its prompt and text nest
        assertThat(canvas.markdown()).contains("**Agent** · survey the module").doesNotContain("\n\n> List the classes.");
    }

    @Test
    void theMarkdownOfASubagentCallQuotesItsSteps() {
        load(SyntheticSession.steps(10_000));
        canvas.ensureLayout(1000);
        String md = canvas.markdown();
        int call = md.indexOf("**Agent** · survey the module");
        assertThat(call).isNotNegative();
        String card = md.substring(call);
        assertThat(card).as("the subagent's prompt and answer, quoted under the call")
                .contains("> > List the classes.")
                .contains("> The module has **12 classes**; the entry point is `core/Reducer.java:42`.");
        assertThat(card.indexOf("> > List the classes.")).as("after the call's result")
                .isGreaterThan(card.indexOf("The module has 12 classes."));
    }

    @Test
    void zoomKeepsTheTextBetween8And40Points() {
        assertThat(Style.of(-100).body.getSize2D()).isEqualTo(8f);
        assertThat(Style.of(-100).mono.getSize2D()).isEqualTo(8f);
        assertThat(Style.of(100).body.getSize2D()).isEqualTo(40f);
        assertThat(Style.of(100).mono.getSize2D()).isEqualTo(40f);
    }

    @Test
    void findOpensClosedCardsAndCountsMatches() {
        load(SyntheticSession.steps(10_000));
        paint(1000, 700);
        // tool results are closed by default; the find opens the cards that hold the text
        int matches = canvas.find("cancelSignalTimeout", true);
        assertThat(matches).isGreaterThanOrEqualTo(8);
        canvas.clearFind();
        assertThat(canvas.find("no such text anywhere", true)).isZero();
    }

    @Test
    void typingOnInTheFindBarKeepsTheMatchUnderTheReader() {
        load(SyntheticSession.steps(10_000));
        paint(1000, 700);
        int matches = canvas.find("cancelSignal", true);
        assertThat(matches).isGreaterThanOrEqualTo(4);
        canvas.find("cancelSignal", true);
        canvas.find("cancelSignal", true);
        int before = canvas.currentMatchY();
        canvas.rewindFind();
        canvas.find("cancelSignalT", true);
        assertThat(canvas.currentMatchY()).as("at or after the match the reader was on, not the first one")
                .isGreaterThanOrEqualTo(before).isPositive();
        canvas.clearFind();
        canvas.find("cancelSignal", true);
        int first = canvas.currentMatchY();
        assertThat(before).as("the third match is below the first").isGreaterThan(first);
    }

    @Test
    void selectAllCopiesTextOfEveryBox() {
        load(SyntheticSession.steps(10_000));
        paint(1000, 700);
        canvas.selectAll();
        String text = canvas.selectionText();
        assertThat(text).contains("Why does the cancel path take eleven seconds?").contains("## Finding 1".substring(3));
        canvas.clearSelection();
        assertThat(canvas.selectionText()).isEmpty();
    }

    @Test
    void streamingKeepsLayoutsOfFinishedParagraphs() {
        canvas.append(0, new Block.AssistantText("m", 0, "First paragraph.\n\nSecond", true, ""));
        canvas.ensureLayout(800);
        int h1 = canvas.contentHeight();
        for (int i = 0; i < 200; i++) {
            canvas.update(0, new Block.AssistantText("m", 0, "First paragraph.\n\nSecond" + " word".repeat(i), true, ""));
            canvas.ensureLayout(800);
        }
        assertThat(canvas.contentHeight()).isGreaterThan(h1);
    }

    @Test
    void zoomRelaysOutEverything() {
        load(SyntheticSession.steps(10_000));
        canvas.ensureLayout(1000);
        int before = canvas.contentHeight();
        canvas.setZoom(4);
        canvas.layoutFully(1000);
        assertThat(canvas.contentHeight()).isGreaterThan(before);
    }

    @Test
    void tabsExpandToFourColumnStops() {
        assertThat(Rich.detab("1\tab\n\tx\tyz")).isEqualTo("1   ab\n    x   yz");
        assertThat(Rich.detab("no tabs")).isEqualTo("no tabs");
    }
}
