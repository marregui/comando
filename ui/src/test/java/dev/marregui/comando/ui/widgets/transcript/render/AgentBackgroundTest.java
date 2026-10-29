package dev.marregui.comando.ui.widgets.transcript.render;

import dev.marregui.comando.core.state.BackgroundTask;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.ui.kit.async.OnEdt;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/// [TranscriptCanvas]'s status of a sub-agent card in the background, across rebuilds, quoted launches and many calls.
/// - switching sessions away and back does not end a background run's time
/// - a foreground report that quotes a launch line is done
/// - a running call's elapsed time survives a rebuild, also after many tool calls
@ExtendWith(OnEdt.class)
class AgentBackgroundTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final TranscriptCanvas canvas = new TranscriptCanvas(href -> { });

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    AgentBackgroundTest() {
        canvas.clock(now::get);
    }

    private static Block.ToolCall agent(String id) {
        return new Block.ToolCall(id, "Agent", "{\"description\":\"survey\",\"prompt\":\"Do it.\",\"run_in_background\":true}", "",
                Block.ToolStatus.RUNNING, "");
    }

    private static List<BackgroundTask> tasks(String... ids) {
        return Arrays.stream(ids).map(id -> new BackgroundTask(id, "local_agent", "task " + id, 1L)).toList();
    }

    /// A background launch whose task is briefly replaced by another session's tasks during a switch away and back
    /// keeps running from its launch, and reads "done · 5 min 00 s" when its task ends.
    @Test
    void aSessionSwitchBackDoesNotEndTheBackgroundAgentsTime() {
        // session B launches a background sub-agent, seen live
        Block.ToolCall a = agent("a1");
        canvas.append(0, a);
        Block.ToolCall launched = a.withResult("Async agent launched successfully.\nagentId: ag1 (internal)", false);
        canvas.update(0, launched);
        canvas.setBackgroundTasks(tasks("ag1"));
        now.addAndGet(60_000);

        // the user focuses a panel of session A: reset() applies the widget's tasks (B's), then LowerPanel A's
        canvas.clear();
        canvas.setBackgroundTasks(tasks("ag1"));
        canvas.setBackgroundTasks(tasks("x1"));
        now.addAndGet(60_000);

        // and back to B: reset() applies the tasks it holds (A's), the blocks are appended, then LowerPanel B's
        canvas.clear();
        canvas.setBackgroundTasks(tasks("x1"));
        canvas.append(0, launched);
        canvas.setBackgroundTasks(tasks("ag1"));
        assertThat(canvas.cardStatus("a1")).startsWith("running in background · 2 min");

        now.addAndGet(180_000);
        canvas.setBackgroundTasks(tasks());

        assertThat(canvas.cardStatus("a1")).as("it ran 5 minutes, from launch to the task's end").startsWith("done · 5 min 00 s");
    }

    /// A foreground call whose report text contains `agentId:` is done, not taken for a background launch.
    @Test
    void aForegroundReportThatMentionsALaunchedAgentIsDone() {
        Block.ToolCall a = new Block.ToolCall("a1", "Agent", "{\"description\":\"review\",\"prompt\":\"Do it.\"}", "",
                Block.ToolStatus.RUNNING, "");
        canvas.append(0, a);
        now.addAndGet(30_000);
        // a foreground sub-agent's own report, about the code it reviewed
        canvas.update(0, a.withResult("The adapter logs when an agent is launched; the line ends with agentId: abc123.", false));

        assertThat(canvas.cardStatus("a1")).as("the call returned its report: the sub-agent is done").startsWith("done");
    }

    /// A running call with 2 048 nested tool calls reads "running · 5 min" after five minutes, and still does after the
    /// canvas is rebuilt, as on a session switch or "Show earlier".
    @Test
    void aLongRunningSubagentsTimeSurvivesARebuildAfterManyToolCalls() {
        Block.ToolCall a = new Block.ToolCall("a1", "Agent", "{\"description\":\"survey\",\"prompt\":\"Do it.\"}", "",
                Block.ToolStatus.RUNNING, "");
        canvas.append(0, a);
        for (int i = 1; i <= 2048; i++) {
            canvas.append(i, new Block.ToolCall("r" + i, "Read", "{\"file_path\":\"/w/f" + i + "\"}", "", Block.ToolStatus.RUNNING, "a1"));
        }
        now.addAndGet(300_000);
        assertThat(canvas.cardStatus("a1")).startsWith("running · 5 min");

        canvas.clear();
        canvas.append(0, a);

        assertThat(canvas.cardStatus("a1")).as("the same call, five minutes in").startsWith("running · 5 min");
    }
}
