package dev.marregui.comando.ui.widgets.transcript;

import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.widgets.markdown.MarkdownWidget;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review lens L2, round 2 (temporary): a Markdown view replaced by another document. Runs off the EDT and steps the
 * EDT with invokeAndWait, so the widget's invokeLater runs between the steps as it does in the application.
 */
class ReviewL2MarkdownTest {

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    private static void layoutTree(Component c) {
        if (c instanceof Container k) {
            k.doLayout();
            for (Component child : k.getComponents()) {
                layoutTree(child);
            }
        }
    }

    private static String doc(String name) {
        StringBuilder sb = new StringBuilder("# " + name + "\n\n");
        for (int i = 0; i < 200; i++) {
            sb.append("Paragraph ").append(i).append(" of ").append(name).append(".\n\n");
        }
        return sb.toString();
    }

    @Test
    void anotherDocumentInTheSameViewStartsAtItsTop() throws Exception {
        AtomicReference<MarkdownWidget> w = new AtomicReference<>();
        AtomicInteger before = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> {
            MarkdownWidget widget = new MarkdownWidget();
            w.set(widget);
            widget.render(new ViewSpec.Markdown(doc("first")));
            JScrollPane scroll = (JScrollPane) widget.component();
            scroll.setSize(600, 300);
            layoutTree(scroll);
        });
        SwingUtilities.invokeAndWait(() -> { }); // the first render's caret scroll has run
        SwingUtilities.invokeAndWait(() -> {
            JScrollPane scroll = (JScrollPane) w.get().component();
            scroll.getViewport().setViewPosition(new Point(0, 2000)); // the reader is in the middle of the first
            before.set(scroll.getViewport().getViewPosition().y);
        });
        assertThat(before.get()).isEqualTo(2000);

        // a ShowView with the same title replaces an unpinned view of the same type: ViewTabs renders the same widget
        SwingUtilities.invokeAndWait(() -> {
            w.get().render(new ViewSpec.Markdown(doc("second, another document")));
            layoutTree(w.get().component());
        });
        SwingUtilities.invokeAndWait(() -> { }); // the widget's invokeLater has run

        AtomicInteger y = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> y.set(((JScrollPane) w.get().component()).getViewport().getViewPosition().y));
        assertThat(y.get()).as("another document is read from its start").isZero();
    }
}
