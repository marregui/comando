package dev.marregui.comando.ui.widgets.transcript.render;

import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.ui.kit.async.OnEdt;
import dev.marregui.comando.ui.kit.theme.Theme;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.swing.JScrollPane;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** A sub-agent card's live status, the running sub-agents, revealing a card and the link to its stored conversation. */
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

    private int add(Block b) {
        int at = next++;
        canvas.append(at, b);
        return at;
    }

    private static Block.ToolCall agent(String id, String description, String parent) {
        return new Block.ToolCall(id, "Agent", "{\"description\":\"" + description + "\",\"prompt\":\"Do it.\"}", "",
                Block.ToolStatus.RUNNING, parent);
    }

    private static Block.ToolCall tool(String id, String name, String input, String parent) {
        return new Block.ToolCall(id, name, input, "", Block.ToolStatus.RUNNING, parent);
    }

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

    @Test
    void theLatestToolsSummaryFollowsItsInputWhenItArrivesLater() {
        add(agent("a1", "survey", ""));
        Block.ToolCall read = tool("r1", "Read", "{}", "a1");
        int at = add(read);
        assertThat(canvas.lastTool("a1")).isEqualTo("Read");

        canvas.update(at, read.withInput("{\"file_path\":\"/w/a.txt\"}"));
        assertThat(canvas.lastTool("a1")).isEqualTo("Read /w/a.txt");
        assertThat(canvas.toolCount("a1")).as("an update is not another call").isEqualTo(1);
    }

    @Test
    void aLongLatestToolIsClipped() {
        add(agent("a1", "survey", ""));
        add(tool("b1", "Bash", "{\"command\":\"" + "x".repeat(200) + "\"}", "a1"));

        assertThat(canvas.lastTool("a1")).hasSize(Items.ToolItem.LAST_CHARS).startsWith("Bash xxx").endsWith("…");
    }

    @Test
    void aFinishedSubagentSaysHowLongItRanAndHowManyToolsItCalled() {
        Block.ToolCall a = agent("a1", "survey", "");
        int at = add(a);
        add(tool("b1", "Bash", "{\"command\":\"ls\"}", "a1"));
        now.addAndGet(20_000);
        canvas.update(at, a.withResult("Report.", false));

        assertThat(canvas.cardStatus("a1")).isEqualTo("done · 20 s · 1 tool");

        Block.ToolCall failed = agent("a2", "broken", "");
        int b = add(failed);
        canvas.update(b, failed.withResult("boom", true));
        assertThat(canvas.cardStatus("a2")).as("a failure keeps its own mark").isEmpty();

        Block.ToolCall bash = tool("t1", "Bash", "{\"command\":\"ls\"}", "");
        int c = add(bash);
        canvas.update(c, bash.withResult("ok", false));
        assertThat(canvas.cardStatus("t1")).as("other cards say nothing when done").isEmpty();
    }

    @Test
    void runningAgentsAreInStartOrderAndLeaveWhenTheyFinish() {
        Block.ToolCall first = agent("a1", "first", "");
        int at = add(first);
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
        JScrollPane scroll = new JScrollPane(canvas);
        scroll.setSize(600, 300);
        scroll.doLayout();
        scroll.getViewport().doLayout();
        canvas.ensureLayout(canvas.getWidth());
        // the user closed it
        Items.ToolItem card = (Items.ToolItem) canvas.itemAt(80);
        card.toggle();
        assertThat(canvas.isCardOpen("a1")).isFalse();

        assertThat(canvas.reveal("a1")).isTrue();

        assertThat(canvas.isCardOpen("a1")).isTrue();
        int top = canvas.topOf("a1");
        assertThat(top).isPositive();
        int y = scroll.getViewport().getViewPosition().y;
        assertThat(top).isBetween(y, y + scroll.getViewport().getHeight());
        assertThat(canvas.reveal("nope")).isFalse();
    }

    @Test
    void theHeaderLinkOfASubagentCardOpensItsTranscript() {
        int at = add(agent("a1", "survey", ""));
        canvas.setSize(700, 400);
        canvas.ensureLayout(700);
        Item card = canvas.itemAt(at);
        int linkX = -1;
        int linkY = 3 + 5 + 8;
        for (int px = 0; px < 400 && linkX < 0; px++) {
            if (card.headerLinkAt(px, linkY) != null) {
                linkX = px;
            }
        }
        assertThat(linkX).as("the header shows the link").isPositive();
        assertThat(card.headerLinkAt(linkX, linkY)).isEqualTo(TranscriptCanvas.SUBAGENT_SCHEME + "a1");
        assertThat(card.toggleAt(linkX, linkY)).as("elsewhere the header still toggles").isSameAs(card);

        int x = linkX + Theme.PAD * 2 + 1;
        int y = canvas.topOf("a1") + linkY;
        boolean open = canvas.isCardOpen("a1");
        canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, 0L, MouseEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1));
        canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, 0L, 0, x, y, 1, false, MouseEvent.BUTTON1));

        assertThat(links).containsExactly("subagent:a1");
        assertThat(canvas.isCardOpen("a1")).as("following the link does not toggle the card").isEqualTo(open);
    }
}
