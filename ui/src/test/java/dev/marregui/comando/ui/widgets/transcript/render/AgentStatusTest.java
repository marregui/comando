package dev.marregui.comando.ui.widgets.transcript.render;

import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.JScrollPane;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.ui.kit.async.OnEdt;
import dev.marregui.comando.ui.kit.theme.Theme;

import static org.assertj.core.api.Assertions.assertThat;

/// [TranscriptCanvas]'s sub-agent cards: live status, the running list, revealing a card and the link to its
/// transcript.
/// - a card counts the tools its blocks call, nested ones too, and names the latest, clipped
/// - the latest tool's summary follows its input when it arrives later
/// - a finished card says how long it ran and how many tools it called; a failed one keeps its own mark
/// - running sub-agents are top-level, in start order, and leave when they finish
/// - reveal opens a card and scrolls it into view
/// - the header link opens the card's transcript on a double click without toggling it
@ExtendWith(OnEdt.class)
class AgentStatusTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final List<String> links = new ArrayList<>();
    private final TranscriptCanvas canvas = new TranscriptCanvas(links::add);
    private int next;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    AgentStatusTest() {
        canvas.clock(now::get);
    }

    private int add(final Block b) {
        final int at = next++;
        canvas.append(at, b);
        return at;
    }

    private static Block.ToolCall agent(final String id, final String description, final String parent) {
        return new Block.ToolCall(id, "Agent", "{\"description\":\"" + description + "\",\"prompt\":\"Do it.\"}", "",
                Block.ToolStatus.RUNNING, parent);
    }

    private static Block.ToolCall tool(final String id, final String name, final String input, final String parent) {
        return new Block.ToolCall(id, name, input, "", Block.ToolStatus.RUNNING, parent);
    }

    /// A running card counts its two tool calls, ignoring text blocks, and reads "running · 12 s · 2 tools" with the
    /// latest tool and its input.
    @Test
    void aSubagentCardCountsTheToolsItsBlocksCallAndNamesTheLatest() {
        add(agent("a1", "survey", ""));
        assertThat(canvas.cardStatus("a1")).isEqualTo("running");

        add(new Block.AssistantText("m1", 0, "Looking around.", false, "a1"));
        add(tool("b1", "Bash", "{\"command\":\"ls docs\"}", "a1"));
        add(tool("r1", "Read", "{\"file_path\":\"/w/docs/ui.md\"}", "a1"));
        now.addAndGet(12_000);

        assertThat(canvas.toolCount("a1")).isEqualTo(2);
        assertThat(canvas.lastTool("a1")).isEqualTo("Read /w/docs/ui.md");
        assertThat(canvas.cardStatus("a1")).isEqualTo("running · 12 s · 2 tools · Read /w/docs/ui.md");
    }

    /// A nested call and its tools count in the outer card as well, the outer card names the innermost latest tool, and
    /// only the outer call is a running top-level sub-agent.
    @Test
    void aNestedSubagentAndItsToolsCountInEveryCardAroundThem() {
        add(agent("a1", "outer", ""));
        add(tool("b1", "Bash", "{\"command\":\"ls\"}", "a1"));
        add(agent("a2", "inner", "a1"));
        add(tool("g1", "Grep", "{\"pattern\":\"needle\"}", "a2"));

        assertThat(canvas.toolCount("a2")).isEqualTo(1);
        assertThat(canvas.toolCount("a1")).as("Bash, the inner Agent call and its Grep").isEqualTo(3);
        assertThat(canvas.lastTool("a1")).isEqualTo("Grep needle");
        assertThat(canvas.runningAgents()).extracting(TranscriptCanvas.RunningAgent::toolUseId)
                .as("a nested sub-agent is not a top-level one").containsExactly("a1");
    }

    /// An update that adds a tool's input changes the latest tool's summary without counting another call.
    @Test
    void theLatestToolsSummaryFollowsItsInputWhenItArrivesLater() {
        add(agent("a1", "survey", ""));
        final Block.ToolCall read = tool("r1", "Read", "{}", "a1");
        final int at = add(read);
        assertThat(canvas.lastTool("a1")).isEqualTo("Read");

        canvas.update(at, read.withInput("{\"file_path\":\"/w/a.txt\"}"));
        assertThat(canvas.lastTool("a1")).isEqualTo("Read /w/a.txt");
        assertThat(canvas.toolCount("a1")).as("an update is not another call").isEqualTo(1);
    }

    /// A long command is clipped to `Items.ToolItem.LAST_CHARS` characters ending in `…`.
    @Test
    void aLongLatestToolIsClipped() {
        add(agent("a1", "survey", ""));
        add(tool("b1", "Bash", "{\"command\":\"" + "x".repeat(200) + "\"}", "a1"));

        assertThat(canvas.lastTool("a1")).hasSize(Items.ToolItem.LAST_CHARS).startsWith("Bash xxx").endsWith("…");
    }

    /// A finished card reads "done · 20 s · 1 tool"; a failed card and a finished non-sub-agent card have an empty
    /// status.
    @Test
    void aFinishedSubagentSaysHowLongItRanAndHowManyToolsItCalled() {
        final Block.ToolCall a = agent("a1", "survey", "");
        final int at = add(a);
        add(tool("b1", "Bash", "{\"command\":\"ls\"}", "a1"));
        now.addAndGet(20_000);
        canvas.update(at, a.withResult("Report.", false));

        assertThat(canvas.cardStatus("a1")).isEqualTo("done · 20 s · 1 tool");

        final Block.ToolCall failed = agent("a2", "broken", "");
        final int b = add(failed);
        canvas.update(b, failed.withResult("boom", true));
        assertThat(canvas.cardStatus("a2")).as("a failure keeps its own mark").isEmpty();

        final Block.ToolCall bash = tool("t1", "Bash", "{\"command\":\"ls\"}", "");
        final int c = add(bash);
        canvas.update(c, bash.withResult("ok", false));
        assertThat(canvas.cardStatus("t1")).as("other cards say nothing when done").isEmpty();
    }

    /// Running sub-agents are listed with their start times in start order, excluding other tools; a finished one
    /// leaves and clearing the canvas empties the list.
    @Test
    void runningAgentsAreInStartOrderAndLeaveWhenTheyFinish() {
        final Block.ToolCall first = agent("a1", "first", "");
        final int at = add(first);
        now.addAndGet(5_000);
        add(agent("a2", "second", ""));
        add(tool("t1", "Bash", "{\"command\":\"ls\"}", ""));

        assertThat(canvas.runningAgents()).containsExactly(
                new TranscriptCanvas.RunningAgent("a1", "first", 1_000_000L, ""),
                new TranscriptCanvas.RunningAgent("a2", "second", 1_005_000L, ""));

        canvas.update(at, first.withResult("done", false));
        assertThat(canvas.runningAgents()).extracting(TranscriptCanvas.RunningAgent::toolUseId).containsExactly("a2");

        canvas.clear();
        assertThat(canvas.runningAgents()).isEmpty();
    }

    /// Revealing a closed card far down opens it and scrolls its top into the viewport; an unknown id returns false.
    @Test
    void revealOpensTheCardAndScrollsItIntoView() {
        for (int i = 0; i < 80; i++) {
            add(new Block.AssistantText("m" + i, 0, "Paragraph " + i + " of the conversation before the agent.", false, ""));
        }
        add(agent("a1", "survey", ""));
        add(tool("b1", "Bash", "{\"command\":\"ls\"}", "a1"));
        for (int i = 0; i < 80; i++) {
            add(new Block.AssistantText("n" + i, 0, "Paragraph " + i + " after the agent.", false, ""));
        }
        final JScrollPane scroll = new JScrollPane(canvas);
        scroll.setSize(600, 300);
        scroll.doLayout();
        scroll.getViewport().doLayout();
        canvas.ensureLayout(canvas.getWidth());
        // the user closed it
        final Items.ToolItem card = (Items.ToolItem) canvas.itemAt(80);
        card.toggle();
        assertThat(canvas.isCardOpen("a1")).isFalse();

        assertThat(canvas.reveal("a1")).isTrue();

        assertThat(canvas.isCardOpen("a1")).isTrue();
        final int top = canvas.topOf("a1");
        assertThat(top).isPositive();
        final int y = scroll.getViewport().getViewPosition().y;
        assertThat(top).isBetween(y, y + scroll.getViewport().getHeight());
        assertThat(canvas.reveal("nope")).isFalse();
    }

    /// The card header shows a [TranscriptCanvas#SUBAGENT_SCHEME] link; a single click follows nothing, a double click
    /// follows it without toggling the card, and elsewhere the header still toggles.
    @Test
    void theHeaderLinkOfASubagentCardOpensItsTranscript() {
        final int at = add(agent("a1", "survey", ""));
        canvas.setSize(700, 400);
        canvas.ensureLayout(700);
        final Item card = canvas.itemAt(at);
        int linkX = -1;
        final int linkY = 3 + 5 + 8;
        for (int px = 0; px < 400 && linkX < 0; px++) {
            if (card.headerLinkAt(px, linkY) != null) {
                linkX = px;
            }
        }
        assertThat(linkX).as("the header shows the link").isPositive();
        assertThat(card.headerLinkAt(linkX, linkY)).isEqualTo(TranscriptCanvas.SUBAGENT_SCHEME + "a1");
        assertThat(card.toggleAt(linkX, linkY)).as("elsewhere the header still toggles").isSameAs(card);

        final int x = linkX + Theme.PAD * 2 + 1;
        final int y = canvas.topOf("a1") + linkY;
        final boolean open = canvas.isCardOpen("a1");
        canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, 0L, MouseEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1));
        canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, 0L, 0, x, y, 1, false, MouseEvent.BUTTON1));
        assertThat(links).as("a single click follows nothing (record 94)").isEmpty();
        canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, 0L, MouseEvent.BUTTON1_DOWN_MASK, x, y, 2, false, MouseEvent.BUTTON1));
        canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, 0L, 0, x, y, 2, false, MouseEvent.BUTTON1));

        assertThat(links).containsExactly("subagent:a1");
        assertThat(canvas.isCardOpen("a1")).as("following the link does not toggle the card").isEqualTo(open);
    }
}
