package dev.marregui.comando.ui.widgets.transcript;

import java.awt.Component;
import java.awt.Container;
import java.util.List;
import javax.swing.JLabel;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import dev.marregui.comando.core.state.BackgroundTask;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.ui.kit.action.ActionRegistry;
import dev.marregui.comando.ui.kit.async.OnEdt;
import dev.marregui.comando.ui.widget.WidgetContext;

import static org.assertj.core.api.Assertions.assertThat;

/// [AgentStrip] names every running sub-agent on one line; showing it keeps the transcript's end in view.
/// - chips that do not fit on the line are counted in a last chip
@ExtendWith(OnEdt.class)
class AgentStripTest {

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

    private static Block.ToolCall agent(final String id, final String description) {
        return new Block.ToolCall(id, "Agent", "{\"description\":\"" + description + "\",\"prompt\":\"Do it.\"}", "",
                Block.ToolStatus.RUNNING, "");
    }

    /// Four running calls and two background tasks make six chips; at 1 200 px three to five fit on one line and the
    /// rest are counted as `+N`; at 2 400 px all fit and the count is empty.
    @Test
    void chipsThatDoNotFitOnTheLineAreCountedInALastChip() {
        final TranscriptWidget widget = new TranscriptWidget(new WidgetContext(intent -> { }, new ActionRegistry()));
        final Transcript t = new Transcript();
        t.append(new Block.UserPrompt("review", "main", ""));
        t.append(agent("a1", "Check the adapter's tail"));
        t.append(agent("a2", "Check the UI side's strip"));
        t.append(agent("a3", "Verify the transcript gate"));
        t.append(agent("a4", "Probe the file parser inputs"));
        widget.render(new TranscriptView("s", t));
        final long started = System.currentTimeMillis() - 724_000L; // 12 min 04 s ago
        widget.setBackgroundTasks(List.of(
                new BackgroundTask("t1", "local_agent", "Watch the build and report", started),
                new BackgroundTask("t2", "local_agent", "Summarise the review findings", started)));

        final Component root = widget.component();
        root.setSize(1200, 600);
        layoutTree(root);

        final AgentStrip strip = widget.strip();
        assertThat(strip.chips()).hasSize(6);
        final List<JLabel> shown = strip.shownChips();
        for (final JLabel chip : shown) {
            assertThat(chip.getY() + chip.getHeight()).as("on the strip's one line").isLessThanOrEqualTo(strip.getHeight());
            assertThat(chip.getX() + chip.getWidth()).isLessThanOrEqualTo(strip.getWidth());
        }
        assertThat(shown.size()).as("most fit at 1 200 px").isBetween(3, 5);
        assertThat(strip.moreText()).isEqualTo("+" + (6 - shown.size()));

        root.setSize(2400, 600);
        layoutTree(root);
        assertThat(strip.shownChips()).as("all fit when there is room").hasSize(6);
        assertThat(strip.moreText()).isEmpty();
    }

}
