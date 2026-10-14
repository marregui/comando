package dev.marregui.comando.ui.widgets.editor;

import dev.marregui.comando.ui.PromptHistory;
import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.Utilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PromptBarTest {

    /** A history store in memory. */
    private static final class Memory implements PromptHistory {
        final Map<String, List<String>> kept = new HashMap<>();

        @Override
        public List<String> load(String session) {
            return List.copyOf(kept.getOrDefault(session, List.of()));
        }

        @Override
        public void add(String session, String prompt) {
            kept.computeIfAbsent(session, k -> new ArrayList<>()).add(prompt);
        }
    }

    private static void press(PromptBar bar, int code, int modifiers) {
        KeyStroke k = KeyStroke.getKeyStroke(code, modifiers);
        Object name = bar.field().getInputMap(JComponent.WHEN_FOCUSED).get(k);
        bar.field().getActionMap().get(name).actionPerformed(new ActionEvent(bar.field(), ActionEvent.ACTION_PERFORMED, ""));
    }

    @Test
    void aRefusedPromptStaysInTheField() {
        onEdt(() -> {
            PromptBar bar = new PromptBar(text -> false, new Memory());
            bar.setTarget("", false);
            bar.field().setText("explain this");
            press(bar, KeyEvent.VK_ENTER, 0);
            assertThat(bar.text()).isEqualTo("explain this");
            // FlatLaf draws the outline of the component that owns the border, the scroll pane
            assertThat(bar.scroll().getClientProperty("JComponent.outline")).isEqualTo("error");
            bar.field().replaceSelection("!");
            assertThat(bar.scroll().getClientProperty("JComponent.outline")).as("an edit clears it").isNull();
        });
    }

    /** The empty bar shows its hint, painted by the bar since FlatLaf paints none in a text area. */
    @Test
    void theEmptyBarShowsItsHint() {
        onEdt(() -> {
            PromptBar bar = new PromptBar(text -> true, new Memory());
            bar.setTarget("", false);
            assertThat(bar.placeholder()).isEqualTo("Bind a session to talk to Claude");
            bar.setTarget("tutor", true);
            assertThat(bar.placeholder()).isEqualTo("Message tutor — a turn is running; ⏎ queues");
            bar.setTarget("tutor", false);
            assertThat(bar.placeholder()).isEqualTo("Message tutor — ⏎ sends, ⇧⏎ new line, ↑ history");
            layout(bar, 600, 60);
            int empty = ink(bar.field());
            bar.field().setText("x");
            int typed = ink(bar.field());
            assertThat(empty).as("the hint is painted").isGreaterThan(0).isGreaterThan(typed);
        });
    }

    /** A long prompt without newlines grows the bar by its wrapped rows, and a narrower bar re-measures. */
    @Test
    void theBarGrowsByWrappedRows() {
        PromptBar[] holder = new PromptBar[1];
        int[] wide = new int[1];
        onEdt(() -> {
            PromptBar bar = new PromptBar(text -> true, new Memory());
            holder[0] = bar;
            layout(bar, 600, 200);
            bar.field().setText("one line");
            assertThat(bar.rows()).isEqualTo(1);
            bar.field().setText(LONG);
            assertThat(bar.rows()).isGreaterThan(1).isLessThanOrEqualTo(PromptBar.MAX_ROWS);
            assertThat(bar.rows()).isEqualTo(wrappedRows(bar));
            wide[0] = bar.rows();
            layout(bar, 300, 200);
        });
        onEdt(() -> {
            PromptBar bar = holder[0];
            assertThat(bar.rows()).as("the resize re-measured").isGreaterThan(wide[0]);
        });
    }

    /** ↑ in a wrapped prompt moves a row up; only on the first row does it walk the history. */
    @Test
    void upMovesAWrappedRowBeforeItWalksTheHistory() {
        onEdt(() -> {
            Memory memory = new Memory();
            memory.add("work", "previous");
            PromptBar bar = new PromptBar(text -> true, memory);
            bar.setTarget("work", false);
            layout(bar, 600, 200);
            bar.field().setText(LONG);
            layout(bar, 600, 200);
            int rows = wrappedRows(bar);
            assertThat(rows).isGreaterThan(1);
            ink(bar.field()); // A text area moves its caret by rows only once it has been painted, as on screen
            bar.field().setCaretPosition(LONG.length());
            for (int i = 1; i < rows; i++) {
                int before = bar.field().getCaretPosition();
                press(bar, KeyEvent.VK_UP, 0);
                assertThat(bar.text()).as("row %d keeps the prompt", i).isEqualTo(LONG);
                assertThat(bar.field().getCaretPosition()).isLessThan(before);
            }
            press(bar, KeyEvent.VK_UP, 0);
            assertThat(bar.text()).isEqualTo("previous");
            press(bar, KeyEvent.VK_DOWN, 0);
            assertThat(bar.text()).isEqualTo(LONG);
        });
    }

    /** Panels bound to one session share a prompt as soon as it is sent. */
    @Test
    void panelsBoundToOneSessionShareTheirPromptsAtOnce() {
        onEdt(() -> {
            Memory memory = new Memory();
            PromptBar a = new PromptBar(text -> true, memory);
            PromptBar b = new PromptBar(text -> true, memory);
            a.setTarget("tutor", false);
            b.setTarget("tutor", false);
            a.field().setText("go");
            a.sendNow();
            press(b, KeyEvent.VK_UP, 0);
            assertThat(b.text()).isEqualTo("go");
            press(a, KeyEvent.VK_UP, 0);
            assertThat(a.text()).isEqualTo("go");
        });
    }

    private static final String LONG = "Look up the columns of the trades table with db_describe, then put a SQL query that returns the "
            + "hourly average price per symbol into my editor with ui_insert, and explain each clause briefly.";

    private static void layout(Component c, int width, int height) {
        c.setSize(width, height);
        layoutAll(c);
    }

    private static void layoutAll(Component c) {
        c.doLayout();
        if (c instanceof Container k) {
            for (Component x : k.getComponents()) {
                layoutAll(x);
            }
        }
    }

    /** The rows the field's text takes at its width, counted from the caret positions of its row starts. */
    private static int wrappedRows(PromptBar bar) {
        JTextArea f = bar.field();
        int rows = 0;
        try {
            for (int at = 0; at <= f.getDocument().getLength(); ) {
                rows++;
                int end = Utilities.getRowEnd(f, at);
                if (end < 0 || end >= f.getDocument().getLength()) {
                    break;
                }
                at = end + 1;
            }
        } catch (BadLocationException e) {
            throw new IllegalStateException(e);
        }
        return rows;
    }

    private static int ink(JComponent c) {
        int w = Math.max(1, c.getWidth());
        int h = Math.max(1, c.getHeight());
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        c.paint(g);
        g.dispose();
        int bg = img.getRGB(w - 2, h - 2);
        int n = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (img.getRGB(x, y) != bg) {
                    n++;
                }
            }
        }
        return n;
    }

    @Test
    void enterSendsShiftEnterAddsALineAndTheFieldGrows() {
        onEdt(() -> {
            List<String> sent = new ArrayList<>();
            PromptBar bar = new PromptBar(text -> sent.add(text), new Memory());
            bar.setTarget("work", false);
            bar.field().setText("first line");
            bar.field().setCaretPosition(bar.field().getText().length());
            press(bar, KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK);
            bar.field().replaceSelection("second line");
            assertThat(bar.rows()).isEqualTo(2);
            press(bar, KeyEvent.VK_ENTER, 0);
            assertThat(sent).containsExactly("first line\nsecond line");
            assertThat(bar.text()).isEmpty();
        });
    }

    @Test
    void historyIsKeptPerSessionAndWalkingItKeepsTheDraft() {
        onEdt(() -> {
            Memory memory = new Memory();
            PromptBar bar = new PromptBar(text -> true, memory);
            bar.setTarget("work", false);
            bar.field().setText("one");
            bar.sendNow();
            bar.field().setText("two");
            bar.sendNow();
            assertThat(memory.kept.get("work")).containsExactly("one", "two");

            PromptBar other = new PromptBar(text -> true, memory);
            other.setTarget("work", false);
            other.field().setText("draft");
            press(other, KeyEvent.VK_UP, 0);
            assertThat(other.text()).isEqualTo("two");
            press(other, KeyEvent.VK_UP, 0);
            assertThat(other.text()).isEqualTo("one");
            press(other, KeyEvent.VK_DOWN, 0);
            press(other, KeyEvent.VK_DOWN, 0);
            assertThat(other.text()).isEqualTo("draft");
        });
    }

    private static void onEdt(Runnable r) {
        try {
            SwingUtilities.invokeAndWait(r);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException x) {
                throw x;
            }
            if (e.getCause() instanceof Error x) {
                throw x;
            }
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
