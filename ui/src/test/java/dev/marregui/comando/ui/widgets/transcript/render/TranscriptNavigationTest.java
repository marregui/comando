package dev.marregui.comando.ui.widgets.transcript.render;

import java.awt.Component;
import java.awt.Container;
import java.awt.event.ActionEvent;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import javax.swing.AbstractButton;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.ui.kit.action.ActionRegistry;
import dev.marregui.comando.ui.widget.WidgetContext;
import dev.marregui.comando.ui.widgets.transcript.TranscriptView;
import dev.marregui.comando.ui.widgets.transcript.TranscriptWidget;
import dev.marregui.comando.ui.widgets.transcript.TranscriptWidget.Move;

import static org.assertj.core.api.Assertions.assertThat;

/// [TranscriptWidget] holds the whole conversation and moves through it by key.
/// - every block of a conversation longer than any window is an item, with no button for earlier blocks
/// - Home and End reach the first and the latest block; End follows again
/// - ⌥↑ and ⌥↓ land on the previous and next prompt; PgUp and PgDn move by a page
/// - a view at the end follows new blocks
///
/// Each step runs on the EDT, and what it left for later runs before the next; a key's step ends with the idle pass
/// settled around where it landed.
class TranscriptNavigationTest {

    private static final int W = 900;
    private static final int H = 600;
    private static final int PROMPTS = 1300;

    private final TranscriptWidget widget = new TranscriptWidget(new WidgetContext(intent -> { }, new ActionRegistry()));
    private final Transcript transcript = new Transcript();

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    /** Runs {@code r} on the EDT and waits, so what it posted with invokeLater runs before the next step. */
    private static void edt(final Runnable r) {
        try {
            SwingUtilities.invokeAndWait(r);
            SwingUtilities.invokeAndWait(() -> { });
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (final InvocationTargetException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    /** {@code n} turns: a prompt, then an answer some 40 lines long, so prompts lie more than a page apart. */
    private void turns(final int from, final int n) {
        for (int k = from; k < from + n; k++) {
            transcript.append(new Block.UserPrompt("prompt " + k, "", ""));
            transcript.append(new Block.AssistantText("m" + k, 0, ("line of answer " + k + "\n\n").repeat(40), false, ""));
        }
    }

    /** Renders the transcript into a widget sized as on screen, its panes laid out as a window would. */
    private void show() {
        edt(() -> {
            widget.render(new TranscriptView("s", transcript));
            widget.component().setSize(W, H);
            layOut(widget.component());
            layOut(widget.component());
        });
    }

    private static void layOut(final Component c) {
        if (c instanceof Container k) {
            k.doLayout();
            for (final Component child : k.getComponents()) {
                layOut(child);
            }
        }
    }

    private JScrollBar bar() {
        return ((JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, widget.canvas())).getVerticalScrollBar();
    }

    /** Presses {@code m}'s key as the keyboard in the transcript would: the nearest binding of the key above the canvas. */
    private void press(final Move m) {
        edt(() -> {
            for (Container c = widget.canvas(); c != null; c = c.getParent()) {
                final Object name = c instanceof JComponent jc ? jc.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(m.key) : null;
                if (name != null) {
                    final JComponent jc = (JComponent) c;
                    assertThat(name).as("the transcript's own move, not another binding of the key").isEqualTo(m.id);
                    final Action a = jc.getActionMap().get(name);
                    a.actionPerformed(new ActionEvent(jc, ActionEvent.ACTION_PERFORMED, ""));
                    layOut(widget.component());
                    // the idle pass settles the window around where the key landed, as its timer would: the block it
                    // landed on stays at the top while heights around it become exact
                    widget.canvas().refineAll();
                    layOut(widget.component());
                    return;
                }
            }
            throw new AssertionError("no binding for " + m.key);
        });
    }

    /** The top of the item of the block at transcript index {@code index}, as laid out now. */
    private int topOfBlock(final int index) {
        final int[] top = new int[1];
        edt(() -> top[0] = widget.canvas().topOf(widget.canvas().itemAt(index)));
        return top[0];
    }

    private int value() {
        final int[] v = new int[1];
        edt(() -> v[0] = bar().getValue());
        return v[0];
    }

    private boolean atEnd() {
        final boolean[] at = new boolean[1];
        edt(() -> {
            final JScrollBar b = bar();
            at[0] = b.getValue() + b.getVisibleAmount() >= b.getMaximum() - 40;
        });
        return at[0];
    }

    /// A conversation of 2 600 blocks, more than the 2 000 a window used to hold, has an item for every block, and no
    /// button offers earlier ones.
    @Test
    void theWholeConversationIsReachableWithNoButtonForEarlierBlocks() {
        turns(0, PROMPTS);
        show();
        final int[] items = new int[1];
        final List<String> buttons = new ArrayList<>();
        edt(() -> {
            items[0] = widget.canvas().itemCount();
            collectButtons(widget.component(), buttons);
        });
        assertThat(items[0]).as("every block is an item").isEqualTo(transcript.size());
        assertThat(buttons).as("no button for earlier blocks").noneMatch(t -> t.contains("earlier"));
        press(Move.FIRST);
        assertThat(value()).as("Home reaches the first block").isZero();
        assertThat(topOfBlock(0)).as("the first block is at the top of the view").isLessThanOrEqualTo(value() + H);
    }

    private static void collectButtons(final Component c, final List<String> out) {
        if (c instanceof AbstractButton b && b.getText() != null) {
            out.add(b.getText());
        }
        if (c instanceof Container k) {
            for (final Component child : k.getComponents()) {
                collectButtons(child, out);
            }
        }
    }

    /// From the end, ⌥↑ lands on the last prompt and again on the one before; ⌥↓ goes back to the last; Home goes to
    /// the top and stops following; ⌥↓ from there lands on the first prompt, then the second; End follows again.
    @Test
    void eachNavigationKeyLandsOnItsBlock() {
        turns(0, 40);
        show();
        press(Move.LAST);
        assertThat(atEnd()).isTrue();
        final int last = 2 * 39;
        final int beforeLast = 2 * 38;

        press(Move.PREVIOUS_PROMPT);
        assertThat(value()).as("⌥↑ from the end: the last prompt at the top of the view").isEqualTo(topOfBlock(last));
        press(Move.PREVIOUS_PROMPT);
        assertThat(value()).as("⌥↑ again: the prompt before it").isEqualTo(topOfBlock(beforeLast));
        press(Move.NEXT_PROMPT);
        assertThat(value()).as("⌥↓: the last prompt again").isEqualTo(topOfBlock(last));

        press(Move.FIRST);
        assertThat(value()).as("Home").isZero();
        assertThat(atEnd()).as("the view left the end").isFalse();
        press(Move.NEXT_PROMPT);
        assertThat(value()).as("⌥↓ from the top: the first prompt").isEqualTo(topOfBlock(0));
        press(Move.NEXT_PROMPT);
        assertThat(value()).as("⌥↓ again: the second prompt").isEqualTo(topOfBlock(2));

        press(Move.LAST);
        assertThat(atEnd()).as("End: the latest block, followed").isTrue();
    }

    /// PgDn moves the view down by the canvas's block increment, a view less two lines, and PgUp back up by as much.
    @Test
    void pageDownAndPageUpMoveByAPage() {
        turns(0, 40);
        show();
        press(Move.FIRST);
        final int page = H - 48;
        press(Move.PAGE_DOWN);
        final int down = value();
        assertThat(down).as("PgDn").isBetween(page - 30, page);
        press(Move.PAGE_DOWN);
        assertThat(value()).as("PgDn again").isEqualTo(2 * down);
        press(Move.PAGE_UP);
        assertThat(value()).as("PgUp").isEqualTo(down);
    }

    /// A view at the end follows the blocks that arrive.
    @Test
    void aViewAtTheEndFollowsNewBlocks() {
        turns(0, 40);
        show();
        press(Move.LAST);
        turns(40, 10);
        edt(() -> {
            widget.render(new TranscriptView("s", transcript));
            layOut(widget.component());
        });
        edt(() -> layOut(widget.component()));
        assertThat(atEnd()).as("the view shows the new end").isTrue();
        assertThat(value()).as("and has moved to it").isGreaterThan(topOfBlock(2 * 40) - H);
    }
}
