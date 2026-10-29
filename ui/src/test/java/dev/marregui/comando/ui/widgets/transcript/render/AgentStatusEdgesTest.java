package dev.marregui.comando.ui.widgets.transcript.render;

import dev.marregui.comando.core.state.BackgroundTask;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.ui.kit.async.OnEdt;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/// [TranscriptCanvas]'s sub-agent status tells the truth: launched into the background, read from a stored
/// conversation, clipped.
/// - a background launch is not done while it works, and runs while the session lists it
/// - a launch seen before the session lists it runs for a grace, then ends; one the session already let go reads done
/// - a card from a stored conversation claims no tool count and a stored launch reads done
/// - the latest tool's label never splits a surrogate pair
@ExtendWith(OnEdt.class)
class AgentStatusEdgesTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final TranscriptCanvas canvas = new TranscriptCanvas(href -> { });
    private int next;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    AgentStatusEdgesTest() {
        canvas.clock(now::get);
    }

    private int add(Block b) {
        int at = next++;
        canvas.append(at, b);
        return at;
    }

    /// A background call that returns its "async launched" result at once is not reported done.
    @Test
    void aSubagentLaunchedInTheBackgroundIsNotReportedDoneWhileItWorks() {
        // the Agent call returns at once with an "async launched" result; the sub-agent goes on working
        Block.ToolCall a = new Block.ToolCall("a1", "Agent",
                "{\"description\":\"survey\",\"prompt\":\"Do it.\",\"run_in_background\":true}", "", Block.ToolStatus.RUNNING, "");
        int at = add(a);
        now.addAndGet(300);
        canvas.update(at, a.withResult("Async agent launched successfully.\nagentId: x1 (internal ID)", false));

        assertThat(canvas.cardStatus("a1")).as("a background sub-agent that has just started").doesNotStartWith("done");
    }

    /// A card that arrives done with no child blocks does not claim "0 tools".
    @Test
    void aSubagentCardReadFromAStoredConversationDoesNotClaimItCalledNoTools() {
        // a stored conversation keeps a sub-agent's blocks in a file of its own: the card arrives done, with no
        // children
        add(new Block.ToolCall("a1", "Agent", "{\"description\":\"survey\",\"prompt\":\"Do it.\"}", "Report.",
                Block.ToolStatus.DONE, ""));

        assertThat(canvas.cardStatus("a1")).as("the count is unknown, not zero").doesNotContain("0 tools");
    }

    /// A command that puts an emoji's high surrogate at the clip boundary leaves no lone surrogate in the label.
    @Test
    void theLatestToolsLabelNeverSplitsASurrogatePair() {
        add(new Block.ToolCall("a1", "Agent", "{\"description\":\"survey\",\"prompt\":\"Do it.\"}", "",
                Block.ToolStatus.RUNNING, ""));
        // "Bash " (5) + 53 characters puts an emoji's high surrogate at index 58, the last one a 60-character clip
        // keeps
        String command = "x".repeat(53) + "😀" + "y".repeat(20);
        add(new Block.ToolCall("b1", "Bash", "{\"command\":\"" + command + "\"}", "", Block.ToolStatus.RUNNING, "a1"));

        String last = canvas.lastTool("a1");
        // a lone surrogate comes back from codePoints() as a code point in the surrogate range
        assertThat(last.codePoints().filter(cp -> cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE).count())
                .as("lone surrogates in " + last).isZero();
    }

    private static final String LAUNCHED = "Async agent launched successfully.\nagentId: a58 (internal ID)\nThe agent is working in the background.";

    private static Block.ToolCall backgroundAgent() {
        return new Block.ToolCall("a1", "Agent", "{\"description\":\"watch\",\"prompt\":\"Do it.\",\"run_in_background\":true}", "",
                Block.ToolStatus.RUNNING, "");
    }

    /// A launched call listed by the session reads "running in background" with its time and the tools its later blocks
    /// call, and is a running sub-agent by its agent id; once the session drops it, it reads done with its total time.
    @Test
    void aBackgroundSubagentRunsWhileTheSessionListsItAndCountsTheToolsItsBlocksCall() {
        Block.ToolCall a = backgroundAgent();
        int at = add(a);
        canvas.update(at, a.withResult(LAUNCHED, false));
        canvas.setBackgroundTasks(List.of(new BackgroundTask("a58", "local_agent", "watch", 0L)));
        // its blocks keep arriving in the stream after its call returned
        add(new Block.ToolCall("b1", "Bash", "{\"command\":\"ls\"}", "ok", Block.ToolStatus.DONE, "a1"));
        now.addAndGet(65_000);

        assertThat(canvas.cardStatus("a1")).isEqualTo("running in background · 1 min 05 s · 1 tool · Bash ls");
        assertThat(canvas.runningAgents()).extracting(TranscriptCanvas.RunningAgent::agentId).containsExactly("a58");

        now.addAndGet(5_000);
        canvas.setBackgroundTasks(List.of());

        assertThat(canvas.cardStatus("a1")).isEqualTo("done · 1 min 10 s · 1 tool");
        assertThat(canvas.runningAgents()).isEmpty();
    }

    /// A launch reads running in background until [TranscriptCanvas#LAUNCH_GRACE_MILLIS] passes without the session
    /// listing it, then reads done.
    @Test
    void aLaunchSeenBeforeTheSessionListsItRunsForAGraceThenEnds() {
        Block.ToolCall a = backgroundAgent();
        int at = add(a);
        canvas.update(at, a.withResult(LAUNCHED, false));
        assertThat(canvas.cardStatus("a1")).startsWith("running in background");

        now.addAndGet(TranscriptCanvas.LAUNCH_GRACE_MILLIS + 1);
        canvas.setBackgroundTasks(List.of(new BackgroundTask("other", "local_agent", "another", 0L)));
        assertThat(canvas.cardStatus("a1")).as("never listed within the grace").startsWith("done");
    }

    /// A launch whose sub-agent the session already listed and dropped reads done at once.
    @Test
    void aBackgroundSubagentTheSessionAlreadyLetGoReadsDone() {
        canvas.setBackgroundTasks(List.of(new BackgroundTask("a58", "local_agent", "watch", 0L)));
        canvas.setBackgroundTasks(List.of());
        Block.ToolCall a = backgroundAgent();
        int at = add(a);
        canvas.update(at, a.withResult(LAUNCHED, false));

        assertThat(canvas.cardStatus("a1")).startsWith("done");
    }

    /// A stored call whose result is a launch line reads "done" and is not running.
    @Test
    void aBackgroundLaunchReadFromAStoredConversationReadsDone() {
        add(new Block.ToolCall("a1", "Agent", "{\"description\":\"watch\",\"prompt\":\"Do it.\"}", LAUNCHED, Block.ToolStatus.DONE, ""));

        assertThat(canvas.cardStatus("a1")).isEqualTo("done");
        assertThat(canvas.runningAgents()).isEmpty();
    }
}
