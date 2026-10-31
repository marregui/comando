package dev.marregui.comando.ui.widgets.transcript.render;

import java.awt.Cursor;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JPanel;
import javax.swing.JViewport;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.testkit.SyntheticSession;
import dev.marregui.comando.ui.kit.async.OnEdt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/// [TranscriptCanvas] in a viewport while its idle layout pass runs after a session switch: the view stays put and
/// whole.
/// - a hover, a find bar left open or a first paint lays out only what is near the view
/// - a rebuilt session stays at its end whether painted or refined first; a scroll away before the next slice is kept
/// - a hover, select all, find, copy or a slice after an append keeps what the view shows
/// - the scroll range ends up covering the whole session
/// - find after a switch counts every match, in closed cards too, and shows the current one
@ExtendWith(OnEdt.class)
class TranscriptCanvasViewportTest {

    private static final int W = 1000;
    private static final int H = 700;

    private final TranscriptCanvas canvas = new TranscriptCanvas(href -> { });
    private final JViewport viewport = new JViewport();
    /** The transcript index of the next block appended. */
    private int next;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    /** {@code copies} sessions one after another, with distinct tool ids: about 73 top-level items per copy. */
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

    /**
     * As the widget does on a session switch: clear, append the history, lay out through the viewport, scroll to the
     * end.
     */
    private void rebuildAtEnd(final List<Block> blocks) {
        viewport.setView(canvas);
        viewport.setSize(W, H);
        canvas.clear();
        for (int i = 0; i < blocks.size(); i++) {
            canvas.append(i, blocks.get(i));
        }
        next = blocks.size();
        viewport.doLayout(); // ViewportLayout: the canvas's preferred size lays it out, then the view is sized
        viewport.setViewPosition(new Point(0, Math.max(0, canvas.contentHeight() - H)));
    }

    /** Paints what the viewport shows, as the repaint manager would. */
    private BufferedImage paintView() {
        final Rectangle r = viewport.getViewRect();
        final BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = img.createGraphics();
        g.translate(0, -r.y);
        g.setClip(0, r.y, W, H);
        canvas.paint(g);
        g.dispose();
        return img;
    }

    private boolean atEnd() {
        // the widget's rule: the bar is at the end when value + extent >= maximum - 40
        return viewport.getViewPosition().y + H >= canvas.contentHeight() - 40;
    }

    private void move(final int x, final int yInView) {
        final int y = viewport.getViewPosition().y + yInView;
        final MouseEvent e = new MouseEvent(canvas, MouseEvent.MOUSE_MOVED, 0L, 0, x, y, x, y, 0, false, MouseEvent.NOBUTTON);
        canvas.getMouseMotionListeners()[0].mouseMoved(e);
    }

    private static double differing(final BufferedImage a, final BufferedImage b) {
        long diff = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    diff++;
                }
            }
        }
        return diff / (double) (a.getWidth() * a.getHeight());
    }

    /// A pointer move over a rebuilt session of about 2 000 items, before the idle pass ends, lays out fewer than half
    /// of them.
    @Test
    void aHoverDuringTheIdlePassLaysOutOnlyWhatIsVisible() {
        rebuildAtEnd(session(27));
        paintView();
        final List<Item> items = canvas.topLevel();
        assertThat(canvas.isEstimated()).as("the idle pass has work").isTrue();
        move(500, H / 2);
        final long after = items.stream().filter(Item::laidOut).count();
        assertThat(after).as("a pointer move over the transcript lays out only what is near the view").isLessThan(items.size() / 2);
    }

    /// A hover while the view follows the end changes under 2% of the painted pixels and the view still shows the end.
    @Test
    void aHoverDuringTheIdlePassKeepsTheViewWhereItWas() {
        rebuildAtEnd(session(27));
        paintView();
        viewport.doLayout(); // the revalidation layoutVisible asked for
        viewport.setViewPosition(new Point(0, Math.max(0, canvas.contentHeight() - H))); // the widget follows the end
        final BufferedImage shown = paintView();
        viewport.doLayout();
        assertThat(canvas.isEstimated()).isTrue();
        final boolean endBefore = atEnd();
        move(500, H / 2);
        viewport.doLayout(); // the next validation
        final BufferedImage after = paintView();
        final double changed = differing(shown, after);
        assertThat(endBefore).isTrue();
        assertThat(changed).as("a hover does not move what the view shows").isLessThan(0.02);
        assertThat(atEnd()).as("the view that followed the end still shows the end after a hover").isTrue();
    }

    /// When the first paint, which grows the content well past the guessed end, runs before the idle pass, the view
    /// still shows the end once everything is laid out.
    @Test
    void aRebuiltSessionPaintedBeforeTheIdlePassStaysAtTheEnd() {
        // order 1: the repaint after the scroll to the end runs before the first slice of the idle pass; the last
        // answer is headings, which the first guess takes for body lines, so the visible pass grows the content well
        // past the end
        final List<Block> blocks = session(27);
        blocks.add(new Block.AssistantText("end", 0, "# Heading\n".repeat(30), false, ""));
        rebuildAtEnd(blocks);
        final int guessed = canvas.contentHeight();
        paintView();
        assertThat(canvas.contentHeight() - guessed).as("the visible pass grew the content past the end").isGreaterThan(40);
        viewport.doLayout();
        canvas.refineAll();
        viewport.doLayout();
        assertThat(atEnd()).as("a session shown after a switch shows its end once laid out").isTrue();
    }

    /// When the idle pass runs before the first paint, the view shows the end.
    @Test
    void aRebuiltSessionRefinedBeforeItIsPaintedStaysAtTheEnd() {
        // order 2: the first slice of the idle pass runs before the repaint
        rebuildAtEnd(session(27));
        canvas.refineAll();
        viewport.doLayout();
        paintView();
        assertThat(atEnd()).isTrue();
    }

    /// Select all during the idle pass lays out everything, sets the scroll range to the exact content and keeps the
    /// end in view.
    @Test
    void selectAllDuringTheIdlePassKeepsTheViewAndTheWholeSessionScrollable() {
        final JPanel parent = new JPanel(null);
        parent.add(viewport);
        parent.addNotify();
        rebuildAtEnd(session(27));
        viewport.validate();
        paintView();
        viewport.validate();
        viewport.setViewPosition(new Point(0, Math.max(0, canvas.contentHeight() - H)));
        assertThat(canvas.isEstimated()).isTrue();
        canvas.selectAll();
        assertThat(canvas.isEstimated()).as("select-all needs every position").isFalse();
        assertThat(canvas.getHeight()).as("the scroll range is the exact content").isEqualTo(canvas.contentHeight());
        assertThat(atEnd()).as("the view that showed the end still shows it").isTrue();
    }

    /// A switch to a large session while the find bar holds a query lays out fewer than half the items at the first
    /// paint.
    @Test
    void aSessionSwitchWithTheFindBarOpenLaysOutOnlyWhatIsVisible() {
        rebuildAtEnd(session(1));
        paintView();
        assertThat(canvas.find("cancelSignalTimeout", true)).isPositive();
        // the panel switches to another session while the find bar still holds its text
        rebuildAtEnd(session(27));
        paintView();
        final List<Item> items = canvas.topLevel();
        final long laid = items.stream().filter(Item::laidOut).count();
        assertThat(laid).as("the first paint after a switch lays out only what is near the view").isLessThan(items.size() / 2);
    }

    /// After a hover and the end of the idle pass, the scroll range covers the whole content, or a revalidation is
    /// pending.
    @Test
    void aHoverDuringTheIdlePassLeavesTheWholeSessionScrollable() {
        final JPanel parent = new JPanel(null);
        parent.add(viewport);
        parent.addNotify();
        rebuildAtEnd(session(27));
        viewport.validate();
        paintView();
        viewport.validate();
        assertThat(canvas.isValid()).isTrue();
        assertThat(canvas.isEstimated()).isTrue();
        move(500, H / 2);
        canvas.refineAll(); // the idle pass's next tick finds nothing left
        assertThat(!canvas.isValid() || canvas.getHeight() == canvas.contentHeight())
                .as("the scroll range covers the whole session, or a revalidation is pending").isTrue();
    }

    /// A backward find from the top during the idle pass scrolls the last match, near the end, into view with an exact
    /// scroll range.
    @Test
    void findDuringTheIdlePassShowsTheMatch() {
        final JPanel parent = new JPanel(null);
        parent.add(viewport);
        parent.addNotify();
        rebuildAtEnd(session(27));
        viewport.validate();
        paintView();
        viewport.validate();
        viewport.setViewPosition(new Point(0, 0)); // the user reads from the top while the idle pass runs
        // the last prompt's text: the backward search lands on the last match, near the end; no card opens for it
        assertThat(canvas.find("Summarise what changed", false)).isPositive();
        final List<Item> items = canvas.topLevel();
        int top = 10;
        int last = -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) instanceof Items.PromptItem p && p.text().startsWith("Summarise what changed")) {
                last = i;
            }
        }
        for (int i = 0; i < last; i++) {
            top += items.get(i).height;
        }
        final int y = viewport.getViewPosition().y;
        assertThat(top).as("the current match is in view").isBetween(y, y + H);
        assertThat(canvas.getHeight()).as("the scroll range is the exact content").isEqualTo(canvas.contentHeight());
    }

    /// A slice of the idle pass that runs after an append and before its layout does not throw.
    @Test
    void aBlockAppendedDuringTheIdlePassBeforeItsLayoutDoesNotBreakTheNextSlice() {
        // a live session: the idle pass runs after the switch, a new block arrives, and the timer's tick runs before
        // the repaint manager's validation lays the new block out
        rebuildAtEnd(session(27));
        paintView();
        viewport.doLayout();
        assertThat(canvas.isEstimated()).isTrue();
        canvas.append(next++, new Block.AssistantText("live", 0, "A new answer arrives.", true, ""));
        assertThatCode(canvas::refineAll).as("a slice of the idle pass with a block not yet positioned").doesNotThrowAnyException();
    }

    /// After switching to a session made of 27 copies, find counts 27 times the matches of one copy.
    @Test
    void findNextAfterASessionSwitchCountsEveryMatch() {
        rebuildAtEnd(session(1));
        paintView();
        final int one = canvas.find("Summarise what changed", true);
        // the panel switches session; the find bar keeps its text and the user presses ⌘G
        rebuildAtEnd(session(27));
        paintView();
        viewport.doLayout();
        final int n = canvas.find("Summarise what changed", true);
        assertThat(n).as("the count covers the whole session").isEqualTo(one * 27);
    }

    /// A user scroll away from the end between the first paint and the next slice is not taken back to the end.
    @Test
    void aScrollAwayFromTheEndBeforeTheNextSliceIsKept() {
        rebuildAtEnd(session(27));
        paintView(); // the visible pass grows the content while the view shows the end: the end is kept
        viewport.doLayout();
        viewport.setViewPosition(new Point(0, 50_000)); // the user scrolls up before the next slice
        paintView();
        canvas.refineAll();
        viewport.doLayout();
        assertThat(atEnd()).as("the view the user scrolled to is not taken back to the end").isFalse();
    }

    /// A find with no match right after an append, before validation, leaves a mid-session view where it was.
    @Test
    void findRightAfterAnAppendDuringTheIdlePassKeepsTheView() {
        rebuildAtEnd(session(27));
        viewport.setViewPosition(new Point(0, 100_000)); // the user reads mid-session; the end was never shown
        paintView();
        viewport.doLayout();
        final BufferedImage shown = paintView();
        assertThat(canvas.isEstimated()).isTrue();
        canvas.append(next++, new Block.AssistantText("live", 0, "A new answer arrives.", true, ""));
        assertThat(canvas.find("no such text anywhere", true)).isZero(); // before the validation the append asked for
        viewport.doLayout();
        final BufferedImage after = paintView();
        assertThat(viewport.getViewPosition().y).as("a find with no match leaves the view where the user read").isGreaterThan(50_000);
    }

    /// Copying a selection right after an append, before validation, returns text and changes under 5% of the view.
    @Test
    void copyRightAfterAnAppendDuringTheIdlePassKeepsTheView() {
        rebuildAtEnd(session(27));
        paintView();
        viewport.doLayout();
        // the user reads a little above the end: a view at the end follows new blocks, as the widget does, which is not
        // what copying may do
        viewport.setViewPosition(new Point(0, Math.max(0, canvas.contentHeight() - H - 300)));
        int y = -1;
        for (int dy = 20; dy < H - 20 && y < 0; dy += 7) { // a point over text, not a card header
            final int at = viewport.getViewPosition().y + dy;
            canvas.getMouseMotionListeners()[0].mouseMoved(new MouseEvent(canvas, MouseEvent.MOUSE_MOVED, 0L, 0, 300, at, 300, at, 0, false,
                    MouseEvent.NOBUTTON));
            if (canvas.getCursor().getType() == Cursor.TEXT_CURSOR) {
                y = at;
            }
        }
        assertThat(y).isPositive();
        final MouseEvent press = new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, 0L, InputEvent.BUTTON1_DOWN_MASK, 300, y, 300, y, 2, false,
                MouseEvent.BUTTON1);
        canvas.getMouseListeners()[0].mousePressed(press); // a double click selects a word
        final BufferedImage shown = paintView();
        viewport.doLayout();
        assertThat(canvas.isEstimated()).isTrue();
        canvas.append(next++, new Block.AssistantText("live", 0, "A new answer arrives.", true, ""));
        final String copied = canvas.selectionText(); // ⌘C before the validation the append asked for
        viewport.doLayout();
        final BufferedImage after = paintView();
        final double changed = differing(shown, after);
        assertThat(copied).isNotEmpty();
        assertThat(changed).as("copying does not move what the view shows").isLessThan(0.05);
    }

    /// A query found only in closed cards counts 27 times one copy's matches after the switch.
    @Test
    void findNextAfterASessionSwitchCountsMatchesInClosedCards() {
        rebuildAtEnd(session(1));
        paintView();
        final int one = canvas.find("cancelSignalTimeout", true); // only in Grep cards, closed by default
        rebuildAtEnd(session(27));
        paintView();
        viewport.doLayout();
        final int n = canvas.find("cancelSignalTimeout", true); // ⌘G with the text the bar kept
        assertThat(n).as("the count covers the matches in closed cards too").isEqualTo(one * 27);
    }

    /// An idle slice that runs after an append, before validation, changes under 5% of a mid-session view.
    @Test
    void aSliceAfterAnAppendWithTheViewMidSessionKeepsTheView() {
        rebuildAtEnd(session(27));
        viewport.setViewPosition(new Point(0, 100_000));
        final BufferedImage shown = paintView();
        viewport.doLayout();
        canvas.append(next++, new Block.AssistantText("live", 0, "A new answer arrives.", true, ""));
        canvas.refineAll(); // the tick runs before the validation
        viewport.doLayout();
        final BufferedImage after = paintView();
        final double changed = differing(shown, after);
        assertThat(changed).isLessThan(0.05);
    }
}
