package dev.marregui.comando.ui.widgets.transcript;

import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.ApprovalKind;
import dev.marregui.comando.core.state.BackgroundTask;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.testkit.RecordingSink;
import dev.marregui.comando.testkit.SyntheticSession;
import dev.marregui.comando.ui.kit.action.ActionRegistry;
import dev.marregui.comando.ui.widget.WidgetContext;
import dev.marregui.comando.ui.widgets.transcript.render.TranscriptCanvas;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TranscriptWidgetTest {

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    private final TranscriptWidget widget = new TranscriptWidget(new WidgetContext(intent -> { }, new ActionRegistry()));

    private static void fill(Transcript t, int from, int to) {
        var steps = SyntheticSession.steps(24);
        for (int i = from; i < to && i < steps.size(); i++) {
            if (steps.get(i).index() < 0) {
                t.append(steps.get(i).block());
            }
        }
    }

    @Test
    void aTranscriptLoadedWhileHiddenKeepsFollowingTheEnd() {
        // a restored session: the transcript is loaded while the Session tab is hidden, then a new turn arrives
        Transcript t = new Transcript();
        fill(t, 0, 40);
        widget.render(new TranscriptView("s", t));
        assertThat(widget.component().isShowing()).isFalse();
        assertThat(widget.following()).isTrue();

        fill(t, 40, 80);
        widget.render(new TranscriptView("s", t));
        assertThat(widget.following()).as("an unlaid-out scroll bar reads 'at the top' and must not stop following").isTrue();
    }

    @Test
    void anotherTranscriptStartsFollowingAgain() {
        Transcript first = new Transcript();
        fill(first, 0, 40);
        widget.render(new TranscriptView("a", first));
        widget.scrolledAway();
        assertThat(widget.following()).isFalse();

        Transcript second = new Transcript();
        fill(second, 0, 10);
        widget.render(new TranscriptView("b", second));
        assertThat(widget.following()).isTrue();
    }

    @Test
    void aPromptSentFromAPanelFollowsAgainAndHidesTheLatestButton() {
        Transcript t = new Transcript();
        fill(t, 0, 40);
        widget.render(new TranscriptView("s", t));
        widget.scrolledAway();
        assertThat(widget.following()).isFalse();
        assertThat(widget.latestShown()).as("not following: the way back to the end shows").isTrue();

        // a prompt replayed from history does not move the view
        t.append(new Block.UserPrompt("from history", "", ""));
        widget.render(new TranscriptView("s", t));
        assertThat(widget.following()).isFalse();

        t.append(new Block.UserPrompt("go", "main", ""));
        widget.render(new TranscriptView("s", t));
        assertThat(widget.following()).isTrue();
        assertThat(widget.latestShown()).isFalse();
    }

    @Test
    void aWaitingApprovalMarksItsToolCallAndSurvivesAReload() {
        Transcript t = new Transcript();
        t.append(new Block.UserPrompt("clean", "main", ""));
        t.append(new Block.ToolCall("tu1", "Bash", "{\"command\":\"rm -rf build\"}", "", Block.ToolStatus.RUNNING, ""));
        widget.render(new TranscriptView("s", t));
        assertThat(widget.canvas().cardStatus("tu1")).isEqualTo("running");

        List<Approval> waiting = List.of(new Approval(7, "s", ApprovalKind.TOOL, "Bash", "{}", "rm -rf build", 0L, "tu1"));
        widget.setWaiting(waiting);
        assertThat(widget.canvas().cardStatus("tu1")).isEqualTo("waiting for your approval · ⌃2");

        // another session's transcript, then back: the canvas is rebuilt and the mark comes back with it
        widget.render(new TranscriptView("other", new Transcript()));
        widget.render(new TranscriptView("s", t));
        assertThat(widget.canvas().cardStatus("tu1")).isEqualTo("waiting for your approval · ⌃2");

        widget.setWaiting(List.of());
        assertThat(widget.canvas().cardStatus("tu1")).isEqualTo("running");
    }

    @Test
    void zoomStopsAtTheFontLimitsAndTurnsBackAtOnce() {
        for (int i = 0; i < 100; i++) {
            widget.zoom(-1);
        }
        int smallest = widget.canvas().zoom();
        assertThat(smallest).as("the steps stop where the text reaches 8 pt").isGreaterThan(-20);
        widget.zoom(1);
        assertThat(widget.canvas().zoom()).as("one step back enlarges again").isEqualTo(smallest + 1);
        for (int i = 0; i < 100; i++) {
            widget.zoom(1);
        }
        assertThat(widget.canvas().zoom()).as("the steps stop where the text reaches 40 pt").isLessThan(40);
        widget.resetZoom();
        assertThat(widget.canvas().zoom()).isZero();
    }

    // ---- sub-agents

    private static Block.ToolCall agent(String id, String description) {
        return new Block.ToolCall(id, "Agent", "{\"description\":\"" + description + "\",\"prompt\":\"Do it.\"}", "",
                Block.ToolStatus.RUNNING, "");
    }

    private static List<String> chipTexts(AgentStrip strip) {
        return strip.chips().stream().map(JLabel::getText).map(t -> t.replaceAll(" · \\d+ s$", "")).toList();
    }

    @Test
    void theStripNamesRunningSubagentsThenBackgroundOnesAndHidesWhenNoneRun() {
        Transcript t = new Transcript();
        t.append(new Block.UserPrompt("survey", "main", ""));
        Block.ToolCall first = agent("a1", "read the user guide");
        Block.ToolCall second = agent("a2", "a description far longer than a chip shows");
        t.append(first);
        t.append(second);
        widget.render(new TranscriptView("s", t));
        widget.setBackgroundTasks(List.of(
                new BackgroundTask("bg1", "local_agent", "watch the build", 0L),
                new BackgroundTask("sh1", "local_bash", "gradle test", 0L)));

        AgentStrip strip = widget.strip();
        assertThat(strip.isVisible()).isTrue();
        assertThat(chipTexts(strip)).as("running cards in start order, then background sub-agents; shell tasks stay out")
                .containsExactly("read the user guide", "a description far longer th…", "watch the build");

        t.replace(1, first.withResult("done", false));
        t.replace(2, second.withResult("done", false));
        widget.render(new TranscriptView("s", t));
        assertThat(chipTexts(strip)).containsExactly("watch the build");

        widget.setBackgroundTasks(List.of());
        assertThat(strip.isVisible()).isFalse();
        assertThat(strip.chips()).isEmpty();
    }

    @Test
    void aBackgroundTaskWithARunningCardHasNoChipOfItsOwn() {
        Transcript t = new Transcript();
        t.append(agent("a1", "watch the build"));
        widget.render(new TranscriptView("s", t));
        widget.setBackgroundTasks(List.of(new BackgroundTask("a1", "local_agent", "something", 1L),
                new BackgroundTask("bg9", "local_agent", "watch the build", 1L)));

        assertThat(chipTexts(widget.strip())).containsExactly("watch the build");
    }

    @Test
    void aRunningChipClickRevealsItsCardAndABackgroundChipHasNoClick() {
        List<String> revealed = new ArrayList<>();
        AgentStrip strip = new AgentStrip(revealed::add, () -> 10_000L);
        strip.show(List.of(new TranscriptCanvas.RunningAgent("a1", "survey", 4_000L, "")),
                List.of(new BackgroundTask("bg1", "local_agent", "watch", 0L)));
        assertThat(strip.chips()).extracting(JLabel::getText).containsExactly("survey · 6 s", "watch");

        for (JLabel chip : strip.chips()) {
            chip.dispatchEvent(new MouseEvent(chip, MouseEvent.MOUSE_CLICKED, 0L, 0, 2, 2, 1, false, MouseEvent.BUTTON1));
        }
        assertThat(revealed).containsExactly("a1");
    }

    @Test
    void theChipClickOfTheWidgetsStripOpensTheCard() {
        Transcript t = new Transcript();
        t.append(agent("a1", "survey"));
        widget.render(new TranscriptView("s", t));
        JLabel chip = widget.strip().chips().getFirst();

        chip.dispatchEvent(new MouseEvent(chip, MouseEvent.MOUSE_CLICKED, 0L, 0, 2, 2, 1, false, MouseEvent.BUTTON1));

        assertThat(widget.canvas().isCardOpen("a1")).isTrue();
    }

    @Test
    void aSubagentLinkAsksForItsTranscript() {
        RecordingSink sink = new RecordingSink();
        TranscriptWidget w = new TranscriptWidget(new WidgetContext(sink, new ActionRegistry()));
        Transcript t = new Transcript();
        t.append(agent("toolu_9", "survey"));
        w.render(new TranscriptView("work", t));

        w.openLink("subagent:toolu_9");

        assertThat(sink.of(Intent.OpenSubagent.class)).containsExactly(new Intent.OpenSubagent("work", "toolu_9"));
    }
}
