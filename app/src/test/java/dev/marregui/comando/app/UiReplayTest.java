package dev.marregui.comando.app;

import dev.marregui.comando.core.console.ConsoleRegistry;
import dev.marregui.comando.core.effect.Effect;
import dev.marregui.comando.core.effect.EffectRouter;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.port.Clock;
import dev.marregui.comando.core.reduce.Reducer;
import dev.marregui.comando.core.result.ResultRegistry;
import dev.marregui.comando.core.schema.SchemaRegistry;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.LowerTab;
import dev.marregui.comando.core.state.ViewState;
import dev.marregui.comando.core.transcript.TranscriptRegistry;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.testkit.RecordingPorts;
import dev.marregui.comando.ui.PanelTexts;
import dev.marregui.comando.ui.shell.ComandoUi;
import dev.marregui.comando.ui.shell.Setup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/// Replays recorded intent logs against the real Swing shell, with every adapter replaced by a recording fake. Needs a
/// display: run with `./gradlew :app:uiTest` (on Linux under `xvfb-run`).
/// - the window shows what the replayed log's final state says
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class UiReplayTest {

    private static final class Texts implements PanelTexts {
        private final Map<String, String> map = new ConcurrentHashMap<>();

        @Override
        public String load(PanelId panel) {
            return map.getOrDefault(panel.value(), "");
        }

        @Override
        public void changed(PanelId panel, String text) {
            map.put(panel.value(), text);
        }

        @Override
        public void forget(PanelId panel) {
            map.remove(panel.value());
        }
    }

    /// After the replay the window title names the focused panel, the workspace tabs are the state's panels with the
    /// focused one selected, the lower tabs hold Session, Approvals, Terminal, Results and the lower views with the
    /// state's lower tab selected, and the side pane shows exactly the side views, wider than a fifth of the window.
    @Test
    void snapshotSessionRendersItsFinalState() throws Exception {
        IntentLog.Replay replay = IntentLog.read(Path.of(getClass().getResource("/replay/snapshot.jsonl").toURI()));
        RecordingPorts ports = new RecordingPorts();
        Deferred effects = new Deferred();
        try (StateLoop loop = new StateLoop(replay.initial(), new Reducer(Clock.SYSTEM), effects)) {
            ComandoUi ui = new ComandoUi(new ComandoUi.Dependencies(loop, loop::state, loop, new TranscriptRegistry(), new ResultRegistry(),
                    new ConsoleRegistry(), new SchemaRegistry(), new Texts(), List::of, null, Setup.NONE,
                    "replay", null, null, () -> { }));
            effects.target = new EffectRouter(ports, ports, ports, ui, ports, ports);
            try (ui) {
                loop.start();
                ui.start();
                for (Intent i : replay.intents()) {
                    loop.dispatch(i);
                }
                AppState end = settle(loop);
                Thread.sleep(500);
                SwingUtilities.invokeAndWait(() -> check(end));
                shoot(Path.of("build/reports/replay/snapshot.png"));
            }
        }
    }

    /** Waits until the state stops changing; the loop has no other idle signal. */
    private static AppState settle(StateLoop loop) throws InterruptedException {
        AppState s = loop.state();
        for (int i = 0; i < 50; i++) {
            Thread.sleep(100);
            AppState next = loop.state();
            if (next == s) {
                return s;
            }
            s = next;
        }
        return s;
    }

    private static void check(AppState s) {
        JFrame frame = frame();
        assertThat(frame.getTitle()).startsWith("comando — " + s.focusedPanel().title());
        JTabbedPane workspace = tabs(frame, "workspace");
        assertThat(titles(workspace)).containsExactlyElementsOf(s.panels().stream().map(p -> p.title()).toList());
        assertThat(workspace.getTitleAt(workspace.getSelectedIndex())).isEqualTo(s.focusedPanel().title());
        assertThat(workspace.isShowing()).isTrue();
        assertThat(workspace.getWidth()).as("editor width").isGreaterThan(frame.getWidth() / 5);

        JTabbedPane lower = tabs(frame, "lower");
        List<String> lowerViews = s.views().stream().filter(v -> v.placement() == Placement.LOWER).map(ViewState::title).toList();
        assertThat(titles(lower)).containsSubsequence("Session", "Approvals", "Terminal", "Results").containsAll(lowerViews);
        String approvals = s.approvals().isEmpty() ? "Approvals" : "Approvals (" + s.approvals().size() + ")";
        assertThat(titles(lower)).contains(approvals);
        String selected = lower.getTitleAt(lower.getSelectedIndex());
        switch (s.lowerTab().kind()) {
            case RESULTS -> assertThat(selected).isEqualTo("Results");
            case SESSION -> assertThat(selected).isEqualTo("Session");
            case APPROVALS -> assertThat(selected).startsWith("Approvals");
            case TERMINAL -> assertThat(selected).isEqualTo("Terminal");
            case VIEW -> assertThat(selected).isEqualTo(s.view(s.lowerTab().viewId()).orElseThrow().title());
        }
        List<String> sideViews = s.views().stream().filter(v -> v.placement() == Placement.SIDE).map(ViewState::title).toList();
        JTabbedPane side = tabs(frame, "side");
        assertThat(titles(side)).containsExactlyInAnyOrderElementsOf(sideViews);
        assertThat(side.isShowing()).isEqualTo(!sideViews.isEmpty());
        if (!sideViews.isEmpty()) {
            assertThat(side.getWidth()).as("side pane width").isGreaterThan(frame.getWidth() / 5);
        }
        assertThat(s.lowerTab()).isNotNull().isNotEqualTo(new LowerTab(LowerTab.Kind.VIEW, -1));
    }

    private static JFrame frame() {
        for (Frame f : Frame.getFrames()) {
            if (f instanceof JFrame jf && jf.isVisible()) {
                return jf;
            }
        }
        throw new AssertionError("no window");
    }

    private static JTabbedPane tabs(Container root, String name) {
        List<JTabbedPane> found = new ArrayList<>();
        collect(root, name, found);
        assertThat(found).as("tab pane " + name).hasSize(1);
        return found.getFirst();
    }

    private static void collect(Container c, String name, List<JTabbedPane> out) {
        for (Component child : c.getComponents()) {
            if (child instanceof JTabbedPane t && name.equals(t.getName())) {
                out.add(t);
            }
            if (child instanceof Container cc) {
                collect(cc, name, out);
            }
        }
    }

    private static List<String> titles(JTabbedPane t) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < t.getTabCount(); i++) {
            out.add(t.getTitleAt(i));
        }
        return out;
    }

    private static void shoot(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        SwingUtilities.invokeAndWait(() -> {
            JFrame f = frame();
            BufferedImage img = new BufferedImage(f.getRootPane().getWidth(), f.getRootPane().getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            f.getRootPane().paint(g);
            g.dispose();
            try {
                ImageIO.write(img, "png", file.toFile());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /** Effects go to the router once the shell exists, as in the app. */
    private static final class Deferred implements StateLoop.EffectHandler {
        volatile StateLoop.EffectHandler target;

        @Override
        public void handle(Effect effect) {
            if (target != null) {
                target.handle(effect);
            }
        }
    }
}
