package dev.marregui.comando.ui.widgets.transcript.render;

import java.awt.Cursor;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import javax.swing.JViewport;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.testkit.SyntheticSession;
import dev.marregui.comando.ui.kit.async.OnEdt;
import dev.marregui.comando.ui.kit.text.CaseFold;

import static org.assertj.core.api.Assertions.assertThat;

/// Find and copy in [TranscriptCanvas] read the blocks' text, as the boxes show it, and lay out nothing for it.
/// - the matches equal those counted over every box of a full layout: in folded cards, tool results, edits, thinking,
///   notes, hook groups, tables, code, Markdown marks and note links
/// - a find lays out the window it had and the item of the match it shows, nothing more
/// - a selection whose ends lie far apart copies the items between, laid out or not, as a full layout would; a click
///   after select-all ends it
/// - typing on or deleting a letter keeps the reader's match inside a sub-agent card whose earlier cards close or open
/// - the next match after blocks arrived counts their matches; ending a find keeps the top block in place
@ExtendWith(OnEdt.class)
class TranscriptFindTest {

    private static final int W = 1000;
    private static final int H = 700;

    private final TranscriptCanvas canvas = new TranscriptCanvas(href -> { });
    private final JViewport viewport = new JViewport();
    private int next;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    private void add(final Block b) {
        canvas.append(next++, b);
    }

    /** The synthetic session, then a block of each kind a find reads, ending on a thought that stays. */
    private void fixture() {
        final Transcript t = SyntheticSession.transcript();
        for (int i = 0; i < t.size(); i++) {
            add(t.get(i));
        }
        add(new Block.UserPrompt("find\tthe tab", "", ""));
        add(new Block.AssistantText("md", 0, "foo**bar** and `inline` here\n\n| a | b |\n|---|---|\n| cellword | x |\n\n"
                + "```java\nint codeword = 1;\n```\n\n---\n\n> a quoted line\n\nsee [[MyNote|the label]], [[Other#Head]] and https://example.org/x "
                + "or `[[kept]]` as written\n", false, ""));
        add(new Block.Note(Block.NoteKind.ERROR, "first line\nsecond notetext", ""));
        add(new Block.Note(Block.NoteKind.INFO, "info head\ninfo notetext tail", ""));
        add(new Block.Note(Block.NoteKind.HOOK, "SessionStart:startup: hookword one", ""));
        add(new Block.Note(Block.NoteKind.HOOK, "UserPromptSubmit: hookword two", ""));
        add(new Block.ToolCall("e1", "Edit", "{\"file_path\":\"/a.txt\",\"old_string\":\"x editword\",\"new_string\":\"y\"}", "ok",
                Block.ToolStatus.DONE, ""));
        add(new Block.ToolCall("b1", "Bash", "{\"command\":\"ls\"}", "line\nresultword\tthere", Block.ToolStatus.DONE, ""));
        add(new Block.TurnEnd(0, 1200, 1, false, ""));
        add(new Block.Thinking("th", 0, "thinking thoughtword here\nand more", 40, false, ""));
    }

    /** The old count: every item laid out, each top-level item's boxes in reading order, matched in their text. */
    private List<int[]> overALayoutOfEverything(final String text) {
        canvas.setSize(W, H);
        canvas.layoutFully(W);
        final String needle = CaseFold.fold(text);
        final List<int[]> out = new ArrayList<>();
        final List<Item> items = canvas.topLevel();
        for (int i = 0; i < items.size(); i++) {
            final List<Placed> boxes = new ArrayList<>();
            items.get(i).collect(0, 0, boxes);
            for (int k = 0; k < boxes.size(); k++) {
                final String hay = CaseFold.fold(boxes.get(k).box().text());
                for (int at = hay.indexOf(needle); at >= 0; at = hay.indexOf(needle, at + needle.length())) {
                    out.add(new int[]{i, k, at});
                }
            }
        }
        return out;
    }

    /// For each word, in a folded card, a tool result, an edit, a thought, a note, a hook group, a table cell, code, or
    /// across Markdown marks, the matches a find counts from the blocks are those a full layout's boxes hold, item,
    /// box and offset alike.
    @Test
    void theMatchesEqualThoseOverEveryBoxOfAFullLayout() {
        fixture();
        canvas.setSize(W, H);
        canvas.ensureLayout(W);
        for (final String word : List.of("cancelSignalTimeout", "timeout", "foobar", "cellword", "codeword", "notetext", "hookword",
                "editword", "resultword", "thoughtword", "the tab", "quoted", "the label", "Other › Head", "[[kept]]", "example.org", "e")) {
            final int n = canvas.find(word, true);
            final List<int[]> found = canvas.findMatches();
            final List<int[]> expected = overALayoutOfEverything(word);
            assertThat(n).as(word).isPositive().isEqualTo(expected.size());
            assertThat(found).as(word).usingElementComparator(Arrays::compare).containsExactlyElementsOf(expected);
            canvas.clearFind();
        }
    }

    /** {@code copies} synthetic sessions one after another, with distinct tool ids. */
    private static List<Block> session(final int copies) {
        final Transcript t = SyntheticSession.transcript();
        final List<Block> out = new ArrayList<>();
        for (int k = 0; k < copies; k++) {
            final String p = "c" + k + "_";
            for (int i = 0; i < t.size(); i++) {
                final Block b = t.get(i);
                out.add(switch (b) {
                    case Block.ToolCall c -> new Block.ToolCall(p + c.toolUseId(), c.name(), c.inputJson(), c.resultText(), c.status(),
                            c.parent().isEmpty() ? "" : p + c.parent());
                    case Block.UserPrompt u -> new Block.UserPrompt(u.text(), u.origin(), u.parent().isEmpty() ? "" : p + u.parent());
                    case Block.AssistantText a -> new Block.AssistantText(a.messageId(), a.index(), a.text(), a.streaming(),
                            a.parent().isEmpty() ? "" : p + a.parent());
                    default -> b;
                });
            }
        }
        return out;
    }

    private void paintView() {
        final Rectangle r = viewport.getViewRect();
        final BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = img.createGraphics();
        g.translate(0, -r.y);
        g.setClip(0, r.y, W, H);
        canvas.paint(g);
        g.dispose();
    }

    /** A long session shown at its end, painted, its idle pass done. */
    private void atTheEndOfALongSession() {
        viewport.setView(canvas);
        viewport.setSize(W, H);
        final List<Block> blocks = session(27);
        for (final Block b : blocks) {
            add(b);
        }
        viewport.doLayout();
        viewport.setViewPosition(new Point(0, Math.max(0, canvas.contentHeight() - H)));
        paintView();
        viewport.doLayout();
        canvas.refineAll();
        viewport.doLayout();
    }

    private Set<Item> laidOut() {
        final Set<Item> out = Collections.newSetFromMap(new IdentityHashMap<>());
        canvas.topLevel().stream().filter(Item::laidOut).forEach(out::add);
        return out;
    }

    /// A find whose first match lies near the top of a session shown at its end lays out the item of that match and
    /// nothing outside the window it had; it still counts every match.
    @Test
    void aFindLaysOutTheWindowItHadAndTheMatchsItemOnly() {
        final TranscriptCanvas one = new TranscriptCanvas(href -> { });
        final List<Block> copy = session(1);
        for (int i = 0; i < copy.size(); i++) {
            one.append(i, copy.get(i));
        }
        one.setSize(W, H);
        final int perCopy = one.find("Summarise what changed", true);
        atTheEndOfALongSession();
        final Set<Item> before = laidOut();
        assertThat(before.size()).isLessThan(canvas.topLevel().size() / 4);

        final int n = canvas.find("Summarise what changed", true);

        final Set<Item> after = laidOut();
        after.removeAll(before);
        assertThat(n).as("every match of the 27 copies").isPositive().isEqualTo(27 * perCopy);
        assertThat(after).as("only the first match's item is laid out beyond the window").hasSize(1);
        final Item shown = after.iterator().next();
        assertThat(canvas.topLevel().indexOf(shown)).as("the first match lies in the first copy").isLessThan(canvas.topLevel().size() / 27 + 1);
    }

    /// A word selected near the top, then a shift-click at the end, after the items between let their layout go,
    /// copies the same text as the same selection over a layout of everything.
    @Test
    void aSelectionWhoseEndsLieFarApartCopiesTheItemsBetweenAsAFullLayoutWould() {
        viewport.setView(canvas);
        viewport.setSize(W, H);
        for (final Block b : session(8)) {
            add(b);
        }
        viewport.doLayout();
        viewport.setViewPosition(new Point(0, 0));
        paintView();
        viewport.doLayout();
        press(textPointInView(), 2, false); // a double click selects a word near the top
        viewport.setViewPosition(new Point(0, Math.max(0, canvas.contentHeight() - H)));
        paintView();
        viewport.doLayout();
        canvas.refineAll(); // the window slides to the end; what lies between lets its layout go
        viewport.doLayout();
        press(textPointInView(), 1, true); // a shift-click near the end
        assertThat(canvas.topLevel().stream().filter(i -> !i.laidOut()).count()).as("items between not laid out").isPositive();

        final String copied = canvas.selectionText();
        canvas.layoutFully(W);
        viewport.doLayout();
        assertThat(copied).isNotEmpty().isEqualTo(canvas.selectionText());
    }

    /** A point over text in the view, in canvas coordinates. */
    private Point textPointInView() {
        for (int dy = 20; dy < H - 20; dy += 7) {
            final int at = viewport.getViewPosition().y + dy;
            canvas.getMouseMotionListeners()[0].mouseMoved(new MouseEvent(canvas, MouseEvent.MOUSE_MOVED, 0L, 0, 300, at, 300, at, 0, false,
                    MouseEvent.NOBUTTON));
            if (canvas.getCursor().getType() == Cursor.TEXT_CURSOR) {
                return new Point(300, at);
            }
        }
        throw new AssertionError("no text in the view");
    }

    private void press(final Point p, final int clicks, final boolean shift) {
        final int mods = InputEvent.BUTTON1_DOWN_MASK | (shift ? InputEvent.SHIFT_DOWN_MASK : 0);
        canvas.getMouseListeners()[0].mousePressed(new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, 0L, mods, p.x, p.y, p.x, p.y, clicks,
                false, MouseEvent.BUTTON1));
    }

    /** A sub-agent card holding two Grep cards, closed, then a prompt; the transcript laid out. */
    private void subagentWithTwoGreps() {
        add(new Block.UserPrompt("start", "", ""));
        add(new Block.ToolCall("a1", "Agent", "{\"description\":\"d\",\"prompt\":\"p\"}", "fin", Block.ToolStatus.DONE, ""));
        add(new Block.ToolCall("g1", "Grep", "{\"pattern\":\"q\"}", "abX one", Block.ToolStatus.DONE, "a1"));
        add(new Block.ToolCall("g2", "Grep", "{\"pattern\":\"q\"}", "abY two", Block.ToolStatus.DONE, "a1"));
        add(new Block.UserPrompt("abY later", "", ""));
        canvas.setSize(900, 600);
        canvas.ensureLayout(900);
    }

    private int cardBottom() {
        return canvas.topOf("a1") + canvas.itemAt(1).height;
    }

    /// The reader is on the match in the second Grep card of a sub-agent card; typing a letter the first card no longer
    /// holds closes that card, and the find stays on the same match instead of jumping to the prompt below.
    @Test
    void typingOnKeepsTheMatchInASubAgentCardWhenAnEarlierNestedCardCloses() {
        subagentWithTwoGreps();
        assertThat(canvas.find("ab", true)).isEqualTo(3); // g1's result, g2's result, the later prompt
        canvas.find("ab", true); // ⌘G: on g2's
        assertThat(canvas.currentMatchY()).as("on g2's match, in the card").isBetween(canvas.topOf("a1"), cardBottom());

        canvas.rewindFind(); // the find bar does this as the text changes
        assertThat(canvas.find("abY", true)).isEqualTo(2);
        assertThat(canvas.currentMatchY()).as("still g2's match, in the card").isBetween(canvas.topOf("a1"), cardBottom());
    }

    /// The reader is on g2's match of "abY"; deleting the last letter opens g1 again, before g2 in the card, and the
    /// find stays on g2's match instead of moving back to g1's.
    @Test
    void deletingALetterKeepsTheMatchInASubAgentCardWhenAnEarlierNestedCardOpens() {
        subagentWithTwoGreps();
        assertThat(canvas.find("abY", true)).isEqualTo(2); // g2's result first
        final int onG2 = canvas.currentMatchY();
        canvas.rewindFind();
        assertThat(canvas.find("ab", true)).isEqualTo(3);
        assertThat(canvas.currentMatchY()).as("g2's match, below g1's").isGreaterThan(onG2).isLessThan(cardBottom());
    }

    /// The same text again after blocks holding it arrived counts their matches too.
    @Test
    void findNextAfterBlocksArriveCountsTheirMatches() {
        fixture();
        canvas.setSize(W, H);
        canvas.ensureLayout(W);
        final int before = canvas.find("hookword", true);
        add(new Block.AssistantText("late", 0, "a late hookword arrives", false, ""));
        assertThat(canvas.find("hookword", true)).isEqualTo(before + 1);
    }

    /// Select all, then a click on text: the click starts a new selection, so copying gives what it selected, not
    /// everything.
    @Test
    void aClickAfterSelectAllEndsIt() {
        viewport.setView(canvas);
        viewport.setSize(W, H);
        for (final Block b : session(2)) {
            add(b);
        }
        viewport.doLayout();
        paintView();
        viewport.doLayout();
        canvas.selectAll();
        assertThat(canvas.selectionText()).isNotEmpty();
        press(textPointInView(), 1, false);
        assertThat(canvas.selectionText()).as("a click selects nothing yet").isEmpty();
    }

    /// Esc on a find whose match lies far above the view, after a zoom made that card's height a guess again, lays the
    /// card out at its exact height and leaves the block at the top of the view where it was.
    @Test
    void endingAFindWhoseMatchLiesFarAboveLeavesTheTopBlockInPlace() {
        viewport.setView(canvas);
        viewport.setSize(W, H);
        for (int k = 0; k < 300; k++) {
            add(new Block.UserPrompt("prompt " + k, "", ""));
            add(new Block.AssistantText("m" + k, 0, ("answer line " + k + "\n\n").repeat(20), false, ""));
            if (k == 3) {
                add(new Block.ToolCall("g1", "Grep", "{\"pattern\":\"x\",\"path\":\"/tmp\"}", "a\nneedleword here\n" + "more result line\n".repeat(30),
                        Block.ToolStatus.DONE, ""));
            }
        }
        viewport.doLayout();
        assertThat(canvas.find("needleword", true)).isEqualTo(1);
        viewport.doLayout();
        paintView();
        readAt(canvas.contentHeight() / 2);
        canvas.setZoom(1);
        readAt(viewport.getViewPosition().y);
        canvas.refineAll();
        viewport.doLayout();
        paintView();
        final Item top = topItem();
        final int before = canvas.topOf(top) - viewport.getViewPosition().y;
        canvas.clearFind();
        viewport.doLayout();
        paintView();
        assertThat(canvas.topOf(top) - viewport.getViewPosition().y).isEqualTo(before);
    }

    private void readAt(final int y) {
        viewport.setViewPosition(new Point(0, y));
        paintView();
        viewport.doLayout();
        canvas.refineAll();
        viewport.doLayout();
        paintView();
    }

    private Item topItem() {
        final int y = viewport.getViewPosition().y;
        return canvas.topLevel().stream().filter(i -> canvas.topOf(i) + i.height > y).findFirst().orElseThrow();
    }
}
