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
import dev.marregui.comando.ui.kit.format.KeyText;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.widget.WidgetContext;
import dev.marregui.comando.ui.widgets.transcript.render.TranscriptCanvas;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.JViewport;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// [TranscriptWidget]: a session's transcript on its own ground, following the end, with the sub-agent strip.
/// - the transcript is drawn on the viewport's ground
/// - a transcript loaded while hidden, another transcript and a prompt sent from a panel all follow the end
/// - a waiting approval marks its tool call and survives a reload
/// - zoom stops at the font limits and turns back at once
/// - the strip names running sub-agents, then background ones, and hides when none run; a chip click reveals its card
/// - a sub-agent link asks for its transcript
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

    /// The canvas is not opaque, so a corner no block reaches shows [Theme#ground].
    @Test
    void theTranscriptIsDrawnOnItsOwnGround() {
        Transcript t = new Transcript();
        fill(t, 0, 10);
        widget.render(new TranscriptView("s", t));
        JViewport viewport = (JViewport) widget.canvas().getParent();
        viewport.setSize(400, 300);
        BufferedImage image = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            viewport.paint(g);
        } finally {
            g.dispose();
        }
        assertThat(widget.canvas().isOpaque()).as("the canvas paints no ground; the viewport's shows through").isFalse();
        assertThat(new Color(image.getRGB(399, 299))).as("a corner no block reaches").isEqualTo(Theme.ground());
    }

    /// A transcript rendered while not showing keeps following when more blocks arrive, although an unlaid-out scroll
    /// bar reads as at the top.
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

    /// After the user scrolled away from one transcript, rendering another starts following again.
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

    /// While scrolled away the latest button shows; a prompt replayed from history does not move the view, but a prompt
    /// sent from a panel follows again and hides the button.
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

    /// A waiting approval changes its tool call's status to "waiting for your approval", the mark comes back after
    /// another transcript is shown and this one rebuilt, and clearing the approvals restores "running".
    @Test
    void aWaitingApprovalMarksItsToolCallAndSurvivesAReload() {
        Transcript t = new Transcript();
        t.append(new Block.UserPrompt("clean", "main", ""));
        t.append(new Block.ToolCall("tu1", "Bash", "{\"command\":\"rm -rf build\"}", "", Block.ToolStatus.RUNNING, ""));
        widget.render(new TranscriptView("s", t));
        assertThat(widget.canvas().cardStatus("tu1")).isEqualTo("running");

        List<Approval> waiting = List.of(new Approval(7, "s", ApprovalKind.TOOL, "Bash", "{}", "rm -rf build", 0L, "tu1"));
        widget.setWaiting(waiting);
        assertThat(widget.canvas().cardStatus("tu1")).isEqualTo(KeyText.of("waiting for your approval · ⌃2"));

        // another session's transcript, then back: the canvas is rebuilt and the mark comes back with it
        widget.render(new TranscriptView("other", new Transcript()));
        widget.render(new TranscriptView("s", t));
        assertThat(widget.canvas().cardStatus("tu1")).isEqualTo(KeyText.of("waiting for your approval · ⌃2"));

        widget.setWaiting(List.of());
        assertThat(widget.canvas().cardStatus("tu1")).isEqualTo("running");
    }

    /// Zooming out stops where the text reaches 8 pt and one step in enlarges at once; zooming in stops at 40 pt; reset
    /// returns to 0.
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

    /// Chips list running calls in start order, clipped when long, then background sub-agents, never shell tasks;
    /// finished calls leave, and the strip hides when nothing runs.
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

    /// A background task whose id is a running card's gets no second chip.
    @Test
    void aBackgroundTaskWithARunningCardHasNoChipOfItsOwn() {
        Transcript t = new Transcript();
        t.append(agent("a1", "watch the build"));
        widget.render(new TranscriptView("s", t));
        widget.setBackgroundTasks(List.of(new BackgroundTask("a1", "local_agent", "something", 1L),
                new BackgroundTask("bg9", "local_agent", "watch the build", 1L)));

        assertThat(chipTexts(widget.strip())).containsExactly("watch the build");
    }

    /// A press on a running chip reveals its card by id and a double press opens the sub-agent's tab, whose first press
    /// still reveals; a background chip answers neither; a right press does nothing. The press is what counts: the
    /// strip rebuilds its chips as the sub-agents change, so a click whose release lands on a new chip would be lost.
    @Test
    void aRunningChipShowsItsCardOnAPressAndOpensItsTabOnADoublePress() {
        List<String> revealed = new ArrayList<>();
        List<String> opened = new ArrayList<>();
        AgentStrip strip = new AgentStrip(revealed::add, opened::add, () -> 10_000L);
        strip.show(List.of(new TranscriptCanvas.RunningAgent("a1", "survey", 4_000L, "")),
                List.of(new BackgroundTask("bg1", "local_agent", "watch", 0L)));
        assertThat(strip.chips()).extracting(JLabel::getText).containsExactly("survey · 6 s", "watch");

        for (JLabel chip : strip.chips()) {
            press(chip, 1, MouseEvent.BUTTON1);
        }
        assertThat(revealed).containsExactly("a1");
        assertThat(opened).isEmpty();

        for (JLabel chip : strip.chips()) {
            press(chip, 2, MouseEvent.BUTTON1);
        }
        assertThat(opened).containsExactly("a1");
        assertThat(revealed).as("the double press reveals no second time").containsExactly("a1");

        press(strip.chips().getFirst(), 1, MouseEvent.BUTTON3);
        assertThat(revealed).as("a right press").containsExactly("a1");
    }

    /// In the widget, a chip's press opens the card on the canvas, and its double press sends `OpenSubagent` for the
    /// session and the call, as the card's "transcript" link and the session card's sub-agents line do.
    @Test
    void theWidgetsChipOpensTheCardOnAPressAndTheSubagentsTabOnADoublePress() {
        RecordingSink sink = new RecordingSink();
        TranscriptWidget w = new TranscriptWidget(new WidgetContext(sink, new ActionRegistry()));
        Transcript t = new Transcript();
        t.append(agent("a1", "survey"));
        w.render(new TranscriptView("work", t));
        JLabel chip = w.strip().chips().getFirst();

        press(chip, 1, MouseEvent.BUTTON1);
        assertThat(w.canvas().isCardOpen("a1")).isTrue();
        assertThat(sink.of(Intent.OpenSubagent.class)).isEmpty();

        press(chip, 2, MouseEvent.BUTTON1);
        assertThat(sink.of(Intent.OpenSubagent.class)).containsExactly(new Intent.OpenSubagent("work", "a1"));
    }

    private static void press(JLabel chip, int clicks, int button) {
        int mask = button == MouseEvent.BUTTON1 ? MouseEvent.BUTTON1_DOWN_MASK : MouseEvent.BUTTON3_DOWN_MASK;
        chip.dispatchEvent(new MouseEvent(chip, MouseEvent.MOUSE_PRESSED, 0L, mask, 2, 2, clicks, false, button));
    }

    /// A link in [TranscriptCanvas#SUBAGENT_SCHEME] sends `OpenSubagent` with the session and the call id.
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
