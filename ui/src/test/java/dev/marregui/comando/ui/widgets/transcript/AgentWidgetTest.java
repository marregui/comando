package dev.marregui.comando.ui.widgets.transcript;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.core.transcript.TranscriptRegistry;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.action.ActionRegistry;
import dev.marregui.comando.ui.widget.WidgetContext;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/// [AgentWidget]: a sub-agent's tab, showing its transcript as it grows and whether it is followed.
/// - shows the sub-agent's transcript as it grows, and its state line
/// - stops listening once unmounted
/// - a session's sub-agent transcripts are forgotten with it
class AgentWidgetTest {

    /// The tab shows the state line it is given and each block appended to the sub-agent's own transcript, not the
    /// session's; once unmounted it ignores further blocks.
    @Test
    void showsTheSubagentsTranscriptAsItGrowsAndSaysWhetherItIsFollowed() throws Exception {
        TranscriptRegistry transcripts = new TranscriptRegistry();
        List<Intent> intents = new CopyOnWriteArrayList<>();
        AgentWidget[] w = new AgentWidget[1];
        SwingUtilities.invokeAndWait(() -> {
            w[0] = new AgentWidget(new WidgetContext(intents::add, new ActionRegistry()), transcripts);
            w[0].render(new ViewSpec.Agent("work", "toolu_1", "review", "Updating while the sub-agent runs."));
        });
        AgentWidget widget = w[0];
        JLabel state = (JLabel) find(widget.component(), "agent-state");
        assertThat(state.getText()).isEqualTo("Updating while the sub-agent runs.");
        assertThat(onEdt(() -> widget.transcript().canvas().itemCount())).as("nothing written yet").isZero();

        Transcript t = transcripts.of(TranscriptRegistry.subagentKey("work", "toolu_1"));
        t.append(new Block.AssistantText("m1", 0, "first step", false, ""));
        t.changed();
        Awaitility.await("the first block").atMost(Duration.ofSeconds(5)).until(() -> onEdt(() -> widget.transcript().canvas().itemCount()) == 1);
        t.append(new Block.ToolCall("c1", "Read", "{\"file_path\":\"/w/a.md\"}", "ok", Block.ToolStatus.DONE, ""));
        t.changed();
        Awaitility.await("followed as it grows").atMost(Duration.ofSeconds(5)).until(() -> onEdt(() -> widget.transcript().canvas().itemCount()) == 2);

        SwingUtilities.invokeAndWait(() -> widget.render(new ViewSpec.Agent("work", "toolu_1", "review", "The sub-agent ended.")));
        assertThat(state.getText()).isEqualTo("The sub-agent ended.");
        assertThat(transcripts.of("work").size()).as("the session's own transcript is another").isZero();

        SwingUtilities.invokeAndWait(widget::unmount);
        t.append(new Block.AssistantText("m2", 0, "after", false, ""));
        t.changed();
        Thread.sleep(200);
        assertThat(onEdt(() -> widget.transcript().canvas().itemCount())).as("closed: it listens no more").isEqualTo(2);
    }

    /// [TranscriptRegistry#forget] drops the session's sub-agent transcripts and keeps those of a session whose name
    /// only starts the same.
    @Test
    void aSessionsSubagentsGoWithIt() {
        TranscriptRegistry transcripts = new TranscriptRegistry();
        Transcript sub = transcripts.of(TranscriptRegistry.subagentKey("work", "toolu_1"));
        sub.append(new Block.AssistantText("m", 0, "x", false, ""));
        transcripts.of(TranscriptRegistry.subagentKey("workshop", "toolu_2")).append(new Block.AssistantText("m", 0, "y", false, ""));
        transcripts.forget("work");
        assertThat(transcripts.of(TranscriptRegistry.subagentKey("work", "toolu_1")).size()).isZero();
        assertThat(transcripts.of(TranscriptRegistry.subagentKey("workshop", "toolu_2")).size()).as("another session's, by a longer name").isEqualTo(1);
    }

    private static java.awt.Component find(java.awt.Component c, String name) {
        if (name.equals(c.getName())) {
            return c;
        }
        if (c instanceof java.awt.Container k) {
            for (java.awt.Component child : k.getComponents()) {
                java.awt.Component f = find(child, name);
                if (f != null) {
                    return f;
                }
            }
        }
        return null;
    }

    private static <T> T onEdt(java.util.concurrent.Callable<T> c) throws Exception {
        Object[] out = new Object[1];
        Exception[] err = new Exception[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                out[0] = c.call();
            } catch (Exception e) {
                err[0] = e;
            }
        });
        if (err[0] != null) {
            throw err[0];
        }
        @SuppressWarnings("unchecked") T t = (T) out[0];
        return t;
    }
}
