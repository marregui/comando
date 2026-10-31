package dev.marregui.comando.ui.widgets.transcript;

import java.awt.Component;
import java.awt.Container;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.state.BackgroundTask;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.ui.kit.action.ActionRegistry;
import dev.marregui.comando.ui.widget.WidgetContext;

import static org.assertj.core.api.Assertions.assertThat;

/// [TranscriptWidget] keeps a following view at the end when [AgentStrip] appears. Runs off the EDT, stepped with
/// `invokeAndWait`: the layout the strip asks for runs in the step that changes it, the widget's scroll in the next.
/// - a view at the end stays at the end when a background chip appears
class AgentStripFollowTest {

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    private static void layoutTree(final Component c) {
        if (c instanceof Container k) {
            k.doLayout();
            for (final Component child : k.getComponents()) {
                layoutTree(child);
            }
        }
    }

    private static JScrollPane find(final Component c) {
        if (c instanceof JScrollPane sp) {
            return sp;
        }
        if (c instanceof Container k) {
            for (final Component child : k.getComponents()) {
                final JScrollPane sp = find(child);
                if (sp != null) {
                    return sp;
                }
            }
        }
        return null;
    }

    /// After scrolling a long transcript to its end, a background task's chip appearing leaves 0 px of the end hidden.
    @Test
    void aViewAtTheEndStaysAtTheEndWhenABackgroundSubagentsChipAppears() throws Exception {
        final AtomicReference<TranscriptWidget> w = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            final TranscriptWidget widget = new TranscriptWidget(new WidgetContext(intent -> { }, new ActionRegistry()));
            w.set(widget);
            final Transcript t = new Transcript();
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
            final JScrollBar bar = find(w.get().component()).getVerticalScrollBar();
            bar.setValue(bar.getMaximum());
        });
        SwingUtilities.invokeAndWait(() -> {
            w.get().setBackgroundTasks(List.of(new BackgroundTask("t1", "local_agent", "Watch the build", System.currentTimeMillis())));
            layoutTree(w.get().component());
        });
        SwingUtilities.invokeAndWait(() -> { });
        final AtomicInteger hidden = new AtomicInteger(-1);
        SwingUtilities.invokeAndWait(() -> {
            assertThat(w.get().strip().isVisible()).isTrue();
            final JScrollBar bar = find(w.get().component()).getVerticalScrollBar();
            hidden.set(bar.getMaximum() - (bar.getValue() + bar.getVisibleAmount()));
        });
        assertThat(hidden.get()).as("pixels of the transcript's end hidden below the view").isZero();
    }
}
