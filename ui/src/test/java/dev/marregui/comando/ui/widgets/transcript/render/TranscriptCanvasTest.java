package dev.marregui.comando.ui.widgets.transcript.render;

import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.testkit.SyntheticSession;
import dev.marregui.comando.ui.kit.async.OnEdt;
import dev.marregui.comando.ui.kit.theme.Tint;
import dev.marregui.comando.ui.kit.theme.Theme;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
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

    @AfterEach
    void defaultTint() {
        Theme.tint(Tint.TREASURE);
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

    private BufferedImage paint(int width, int height) {
        canvas.setSize(width, height);
        canvas.ensureLayout(width);
        canvas.setSize(width, canvas.contentHeight());
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        // the canvas paints no ground of its own: the viewport under it does, and glyphs blend over it
        g.setColor(Theme.ground());
        g.fillRect(0, 0, width, height);
        g.translate(0, -(canvas.contentHeight() - height));
        g.setClip(0, canvas.contentHeight() - height, width, height);
        canvas.paint(g);
        g.dispose();
        return img;
    }

    /** How many pixels of {@code img} are {@code color} itself: the cores of glyphs drawn in it. */
    private static int pixels(BufferedImage img, Color color) {
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) | 0xFF000000) == color.getRGB() && (img.getRGB(x, y) >>> 24) == 0xFF) {
                    n++;
                }
            }
        }
        return n;
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
    void aStoredTurnEndRecordsNoTime() {
        // a stored conversation's turn end, as its messages record it
        canvas.append(0, new Block.UserPrompt("history", "", ""));
        canvas.append(1, text("An old answer."));
        canvas.append(2, Block.TurnEnd.stored());
        canvas.append(3, new Block.Note(Block.NoteKind.INFO, "Stored conversation loaded", ""));
        assertThat(((Items.TurnItem) canvas.itemAt(2)).summary()).as("a stored turn records no time").isEmpty();
        assertThat(((Items.TurnItem) canvas.itemAt(2)).markdown()).isEmpty();
        paint(800, 300);
    }

    @Test
    void everyTextOutsideABubbleOrACardReadsInOneColour() {
        Style s = Style.of(0);
        assertThat(s.outside().text).isEqualTo(Theme.TRANSCRIPT_TEXT);
        assertThat(s.outside().muted).as("quotes too").isEqualTo(Theme.TRANSCRIPT_TEXT);
        assertThat(s.outside()).as("made once, so its layouts stay valid").isSameAs(s.outside());
        assertThat(s.outside().body).isEqualTo(s.body);

        canvas.append(0, new Block.UserPrompt("go", "p", ""));
        canvas.append(1, call("t1", ""));
        assertThat(pixels(paint(800, 300), Theme.TRANSCRIPT_TEXT)).as("a bubble and a card keep their colours").isZero();

        canvas.append(2, text("Done: the store drops pending retries."));
        canvas.append(3, new Block.TurnEnd(0.1, 1_000, 2, false, ""));
        int answer = pixels(paint(800, 300), Theme.TRANSCRIPT_TEXT);
        assertThat(answer).as("the turn's last answer and its time").isPositive();
        assertThat(((Items.TextItem) canvas.itemAt(2)).isReport()).as("the turn's last answer is its report").isTrue();

        canvas.append(4, new Block.Note(Block.NoteKind.INFO, "Stored conversation loaded", ""));
        assertThat(pixels(paint(800, 300), Theme.TRANSCRIPT_TEXT)).as("a note").isGreaterThan(answer);
    }

    @Test
    void anAnswersHeadingsMarksAndTableHeadsAreTintedElsewhereTheTextsColour() {
        Style s = Style.of(0);
        Tint tint = Theme.tint();
        assertThat(s.outside().heading).isEqualTo(tint.body);
        assertThat(s.outside().mark).isEqualTo(tint.body);
        assertThat(s.outside().tableHead).isEqualTo(tint.highlight);
        assertThat(s.outside().strong).as("bold brighter than the text").isNotEqualTo(s.outside().text);
        assertThat(s.heading).as("a card's text keeps one colour").isEqualTo(s.text);
        assertThat(s.mark).isEqualTo(s.muted);
        assertThat(s.tableHead).isEqualTo(s.text);
        assertThat(s.strong).isEqualTo(s.text);
        assertThat(s.codeText).isNull();
        assertThat(s.findMatch).as("a match is data, no tint's").isEqualTo(Theme.FIND_MATCH);
        assertThat(Style.of(0).headings[0].getSize2D()).as("h1 at the shared scale")
                .isEqualTo(s.body.getSize2D() * Theme.HEADING_SCALE[0]);
    }

    @Test
    void theTextThatEndsATurnIsItsReport() {
        canvas.append(0, new Block.UserPrompt("go", "p", ""));
        canvas.append(1, text("Looking."));
        canvas.append(2, call("t1", ""));
        canvas.append(3, text("# Result\n\nThe store drops pending retries."));
        canvas.append(4, new Block.Note(Block.NoteKind.INFO, "a hook said so", ""));
        canvas.append(5, new Block.TurnEnd(0.1, 1_000, 2, false, ""));
        canvas.append(6, new Block.UserPrompt("again", "p", ""));
        canvas.append(7, text("Trying."));
        canvas.append(8, call("t2", ""));
        canvas.append(9, new Block.TurnEnd(0.1, 1_000, 2, true, "interrupted"));
        paint(800, 600);
        assertThat(((Items.TextItem) canvas.itemAt(1)).isReport()).as("a text a tool call followed").isFalse();
        assertThat(((Items.TextItem) canvas.itemAt(3)).isReport()).as("notes after it do not count").isTrue();
        assertThat(((Items.TextItem) canvas.itemAt(7)).isReport()).as("a turn that ended on a tool call has none").isFalse();
    }

    @Test
    void choosingAnotherTintRepaintsTheReportInItOnTheTranscriptsGround() {
        canvas.append(0, new Block.UserPrompt("go", "p", ""));
        canvas.append(1, text("# Result\n\nDone."));
        canvas.append(2, new Block.TurnEnd(0.1, 1_000, 2, false, ""));
        assertThat(pixels(paint(800, 300), Tint.TREASURE.highlight)).as("the bar's top").isPositive();
        assertThat(pixels(paint(800, 300), Tint.CHAMPAGNE.highlight)).isZero();
        assertThat(pixels(paint(800, 300), Tint.over(Theme.ground(), Tint.TREASURE.highlight, 0.03f)))
                .as("no ground lifted towards the tint").isZero();

        Theme.tint(Tint.ELECTRIC_BLUE);
        canvas.restyle();
        assertThat(pixels(paint(800, 300), Tint.ELECTRIC_BLUE.highlight)).isPositive();
        assertThat(pixels(paint(800, 300), Tint.TREASURE.highlight)).isZero();
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
    @Test
    void aLinkInTextIsFollowedAtADoubleClickNotASingleOne() {
        canvas.append(0, new Block.AssistantText("m", 0, "Open [[project-current-lane]] now", false, ""));
        paint(800, 200);
        canvas.setSize(800, canvas.contentHeight());
        int[] at = null;
        for (int y = 2; y < canvas.contentHeight() && at == null; y += 2) {
            for (int x = 2; x < 800 && at == null; x += 3) {
                canvas.getMouseMotionListeners()[0].mouseMoved(new MouseEvent(canvas, MouseEvent.MOUSE_MOVED, 0L, 0, x, y, 0, false, MouseEvent.NOBUTTON));
                if (canvas.getCursor().getType() == Cursor.HAND_CURSOR) {
                    at = new int[]{x + 4, y};
                }
            }
        }
        assertThat(at).as("the label shows as a link").isNotNull();
        for (int clicks = 1; clicks <= 2; clicks++) {
            canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, 0L, InputEvent.BUTTON1_DOWN_MASK, at[0], at[1], clicks, false, MouseEvent.BUTTON1));
            canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, 0L, 0, at[0], at[1], clicks, false, MouseEvent.BUTTON1));
            if (clicks == 1) {
                assertThat(opened).as("a single click follows nothing").isEmpty();
            }
        }
        assertThat(opened).containsExactly("comando-note:project-current-lane");
        assertThat(canvas.selectionText()).as("the double click selects no word").isEmpty();
    }

    @Test
    void aToolsResultShowsItsLinks() {
        Rich r = new Rich(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12), Color.GRAY)
                .append("wrote /tmp/x.md\nsee https://example.org/y, [[notes]] and [guide](docs/guide.md)").linked(Color.BLUE);
        String text = r.text();
        assertThat(r.linkAt(text.indexOf("example.org"))).isEqualTo("https://example.org/y");
        assertThat(r.linkAt(text.indexOf("notes"))).isEqualTo("comando-note:notes");
        assertThat(r.linkAt(text.indexOf("guide]"))).isEqualTo("docs/guide.md");
        assertThat(r.linkAt(text.indexOf("wrote"))).isNull();
        assertThat(text).as("the text stays as written").contains("[[notes]]");
    }
}
