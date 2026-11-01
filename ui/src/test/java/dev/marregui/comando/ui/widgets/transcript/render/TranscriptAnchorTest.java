package dev.marregui.comando.ui.widgets.transcript.render;

import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JViewport;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.ui.kit.async.OnEdt;

import static org.assertj.core.api.Assertions.assertThat;

/// [TranscriptCanvas] keeps the block at the top of the view where it is while the reader is away from the end, and
/// lays out only a window around the view.
/// - blocks appended below leave the top block in place
/// - a block above the view that grows (a result, streamed text) leaves the top block in place
/// - estimated heights above the view becoming exact leave the top block in place, also when it starts exactly at the
///   view's top
/// - the idle pass lays out the window around the view only; items far from it let their layout go, keeping heights,
///   an answer its paragraphs too; the window follows a view that scrolls far
/// - an answer is parsed at its first layout only; a thought the next block replaced lets its layout go
@ExtendWith(OnEdt.class)
class TranscriptAnchorTest {

    private static final int W = 1000;
    private static final int H = 700;

    private final TranscriptCanvas canvas = new TranscriptCanvas(href -> { });
    private final JViewport viewport = new JViewport();
    private int next;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    /** {@code n} turns of a prompt and a 20-line answer, appended, laid out through the viewport. */
    private void load(final int n) {
        viewport.setView(canvas);
        viewport.setSize(W, H);
        for (int k = 0; k < n; k++) {
            canvas.append(next++, new Block.UserPrompt("prompt " + k, "", ""));
            canvas.append(next++, new Block.AssistantText("m" + k, 0, ("answer line " + k + "\n\n").repeat(20), false, ""));
        }
        viewport.doLayout();
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

    /** The top-level item at the top of the view. */
    private Item topItem() {
        final int y = viewport.getViewPosition().y;
        Item found = null;
        for (final Item item : canvas.topLevel()) {
            if (canvas.topOf(item) + item.height > y) {
                found = item;
                break;
            }
        }
        return found;
    }

    /** Where {@code item} sits in the view: its top less the view's. */
    private int onScreen(final Item item) {
        return canvas.topOf(item) - viewport.getViewPosition().y;
    }

    /** Reads mid-conversation: the view at {@code y}, painted and validated. */
    private void readAt(final int y) {
        viewport.setViewPosition(new Point(0, y));
        paintView();
        viewport.doLayout();
    }

    /// Blocks appended while the reader is mid-conversation leave the block at the top of the view where it was.
    @Test
    void blocksAppendedBelowLeaveTheTopBlockInPlace() {
        load(200);
        canvas.layoutFully(W);
        viewport.doLayout();
        readAt(canvas.contentHeight() / 2);
        final Item top = topItem();
        final int before = onScreen(top);
        for (int k = 0; k < 30; k++) {
            canvas.append(next++, new Block.AssistantText("late" + k, 0, "more\n\n".repeat(10), false, ""));
        }
        viewport.doLayout();
        paintView();
        assertThat(onScreen(top)).isEqualTo(before);
    }

    /// A block above the view that grows, as streamed text or a result arriving does, leaves the block at the top of
    /// the view where it was on screen.
    @Test
    void aBlockAboveTheViewThatGrowsLeavesTheTopBlockInPlace() {
        load(200);
        canvas.layoutFully(W);
        viewport.doLayout();
        readAt(canvas.contentHeight() / 2);
        final Item top = topItem();
        final int before = onScreen(top);
        canvas.update(3, new Block.AssistantText("m1", 0, ("answer line 1 grown\n\n").repeat(80), false, ""));
        viewport.doLayout();
        paintView();
        assertThat(onScreen(top)).as("the top block stays where it was on screen").isEqualTo(before);
    }

    /// Heights above the view that were estimates and become exact leave the block at the top of the view where it was.
    @Test
    void estimatedHeightsAboveTheViewBecomingExactLeaveTheTopBlockInPlace() {
        // headings, which the first guess takes for body lines: exact heights differ from the guesses
        viewport.setView(canvas);
        viewport.setSize(W, H);
        for (int k = 0; k < 300; k++) {
            canvas.append(next++, new Block.UserPrompt("prompt " + k, "", ""));
            canvas.append(next++, new Block.AssistantText("m" + k, 0, "# Heading\n".repeat(12), false, ""));
        }
        viewport.doLayout();
        readAt(canvas.contentHeight() / 2);
        assertThat(canvas.isEstimated()).isTrue();
        final Item top = topItem();
        final int before = onScreen(top);
        canvas.layoutFully(W);
        assertThat(canvas.isEstimated()).isFalse();
        viewport.doLayout();
        paintView();
        assertThat(onScreen(top)).as("the top block stays where it was on screen").isEqualTo(before);
    }

    /// A view whose top is exactly a block's top keeps that block there when the block above it, ending at the view's
    /// top, is laid out at its exact height by the paint or by the idle pass.
    @Test
    void aViewStartingExactlyAtABlockKeepsItWhenTheBlockAboveIsLaidOut() {
        viewport.setView(canvas);
        viewport.setSize(W, H);
        for (int k = 0; k < 300; k++) {
            canvas.append(next++, new Block.UserPrompt("prompt " + k, "", ""));
            canvas.append(next++, new Block.AssistantText("m" + k, 0, "# Heading\n".repeat(12), false, ""));
        }
        viewport.doLayout();
        final Item prompt = canvas.topLevel().get(300); // a prompt mid-conversation; the answer above it is a guess
        assertThat(canvas.topLevel().get(299).laidOut()).isFalse();
        viewport.setViewPosition(new Point(0, canvas.topOf(prompt)));
        paintView();
        viewport.doLayout();
        assertThat(onScreen(prompt)).as("after the paint").isZero();
        canvas.layoutFully(W);
        viewport.doLayout();
        canvas.refineAll();
        viewport.doLayout();
        assertThat(onScreen(prompt)).as("after the heights above became exact").isZero();
    }

    /// After the idle pass, only items near the view are laid out; once the view moves far away, the items it left let
    /// their layout go, and the content keeps its height.
    @Test
    void theIdlePassLaysOutAWindowAroundTheViewAndFarItemsLetTheirLayoutGo() {
        load(1000);
        readAt(Math.max(0, canvas.contentHeight() - H));
        canvas.refineAll();
        final List<Item> items = canvas.topLevel();
        final long laid = items.stream().filter(Item::laidOut).count();
        assertThat(laid).as("the window around the end, not the conversation").isPositive().isLessThan(items.size() / 4);

        canvas.layoutFully(W); // a find or select-all lays out everything
        viewport.doLayout();
        final int height = canvas.contentHeight();
        readAt(0);
        canvas.refineAll();
        final List<Item> far = new ArrayList<>(items.subList(items.size() - 50, items.size()));
        assertThat(far).as("the items at the end, far from the view at the top").noneMatch(Item::laidOut);
        assertThat(canvas.contentHeight()).as("their heights are kept").isEqualTo(height);
    }

    /// Answers appended and never laid out are not parsed; once the view at the end is painted, only answers in the
    /// window around it are.
    @Test
    void anAnswerIsParsedAtItsFirstLayoutOnly() {
        load(400);
        final List<Items.TextItem> answers = canvas.topLevel().stream().filter(Items.TextItem.class::isInstance).map(Items.TextItem.class::cast)
                .toList();
        assertThat(answers).as("appended, not yet laid out").noneMatch(Items.TextItem::parsed);
        readAt(Math.max(0, canvas.contentHeight() - H));
        canvas.refineAll();
        final long parsed = answers.stream().filter(Items.TextItem::parsed).count();
        assertThat(parsed).as("the window's answers").isPositive().isLessThan(answers.size() / 4);
    }

    /// An answer far from the view lets its paragraphs go with its layout: they are parsed again when it comes back.
    @Test
    void anAnswerFarFromTheViewLetsItsParagraphsGo() {
        load(400);
        canvas.layoutFully(W);
        viewport.doLayout();
        readAt(0);
        canvas.refineAll();
        final List<Item> items = canvas.topLevel();
        final List<Items.TextItem> far = items.subList(items.size() - 50, items.size()).stream().filter(Items.TextItem.class::isInstance)
                .map(Items.TextItem.class::cast).toList();
        assertThat(far).isNotEmpty().noneMatch(Items.TextItem::parsed);
    }

    /// After the idle pass ends at the end, a view that scrolls far has the idle pass run again around it.
    @Test
    void theWindowFollowsAViewThatScrollsFar() {
        load(400);
        readAt(Math.max(0, canvas.contentHeight() - H));
        canvas.refineAll();
        assertThat(canvas.refining()).as("the window at the end is done").isFalse();
        readAt(canvas.contentHeight() / 2);
        assertThat(canvas.refining()).as("the paint far from there schedules the idle pass").isTrue();
    }

    /// A thought shown live and laid out, which the next block then replaces, lets its layout go at once: it left the
    /// list the release pass walks.
    @Test
    void aThoughtTheNextBlockReplacedLetsItsLayoutGo() {
        viewport.setView(canvas);
        viewport.setSize(W, H);
        final List<Item> thoughts = new ArrayList<>();
        for (int k = 0; k < 10; k++) {
            canvas.append(next++, new Block.UserPrompt("prompt " + k, "", ""));
            final int at = next++;
            canvas.append(at, new Block.Thinking("t" + k, 0, ("a line of live reasoning " + k + "\n").repeat(40), 100, true, ""));
            viewport.doLayout();
            readAt(Math.max(0, canvas.contentHeight() - H)); // the live thought is on screen, laid out
            final Item thought = canvas.itemAt(at);
            assertThat(thought.laidOut()).isTrue();
            thoughts.add(thought);
            canvas.append(next++, new Block.AssistantText("m" + k, 0, ("answer line " + k + "\n\n").repeat(20), false, ""));
        }
        assertThat(thoughts).as("replaced, they left the list").noneMatch(canvas.topLevel()::contains);
        assertThat(thoughts).as("and hold no layout").noneMatch(Item::laidOut);
    }
}
