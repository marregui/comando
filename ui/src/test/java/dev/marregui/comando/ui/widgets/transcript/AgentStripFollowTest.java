package dev.marregui.comando.ui.widgets.transcript;

import dev.marregui.comando.core.state.BackgroundTask;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.ui.kit.action.ActionRegistry;
import dev.marregui.comando.ui.widget.WidgetContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The strip appearing keeps a following view at the end. Off the EDT, stepped with
 * invokeAndWait: the layout the strip asks for runs in the step that changes it (the RepaintManager's validation is
 * queued before the widget's invokeLater), the widget's scroll in the next.
 */
class AgentStripFollowTest {

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

    private static JScrollPane find(Component c) {
        if (c instanceof JScrollPane sp) {
            return sp;
        }
        if (c instanceof Container k) {
            for (Component child : k.getComponents()) {
                JScrollPane sp = find(child);
                if (sp != null) {
                    return sp;
                }
            }
        }
        return null;
    }

    @Test
    void aViewAtTheEndStaysAtTheEndWhenABackgroundSubagentsChipAppears() throws Exception {
        AtomicReference<TranscriptWidget> w = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            TranscriptWidget widget = new TranscriptWidget(new WidgetContext(intent -> { }, new ActionRegistry()));
            w.set(widget);
            Transcript t = new Transcript();
            for (int i = 0; i < 60; i++) {
                t.append(new Block.AssistantText("m" + i, 0, "Paragraph " + i + " of a long answer.", false, ""));
            }
            widget.render(new TranscriptView("s", t));
            widget.component().setSize(900, 400);
            layoutTree(widget.component());
            widget.canvas().refineAll();
            layoutTree(widget.component());
        });
        SwingUtilities.invokeAndWait(() -> { });
        SwingUtilities.invokeAndWait(() -> {
            JScrollBar bar = find(w.get().component()).getVerticalScrollBar();
            bar.setValue(bar.getMaximum());
        });
        SwingUtilities.invokeAndWait(() -> {
            w.get().setBackgroundTasks(List.of(new BackgroundTask("t1", "local_agent", "Watch the build", System.currentTimeMillis())));
            layoutTree(w.get().component());
        });
        SwingUtilities.invokeAndWait(() -> { });
        AtomicInteger hidden = new AtomicInteger(-1);
        SwingUtilities.invokeAndWait(() -> {
            assertThat(w.get().strip().isVisible()).isTrue();
            JScrollBar bar = find(w.get().component()).getVerticalScrollBar();
            hidden.set(bar.getMaximum() - (bar.getValue() + bar.getVisibleAmount()));
        });
        assertThat(hidden.get()).as("pixels of the transcript's end hidden below the view").isZero();
    }
}
