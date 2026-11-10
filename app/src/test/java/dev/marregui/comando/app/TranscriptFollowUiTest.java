package dev.marregui.comando.app;

import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.dispatch.Target;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.state.LowerTab;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.testkit.SyntheticSession;
import dev.marregui.comando.ui.kit.action.ActionRegistry;
import dev.marregui.comando.ui.widget.WidgetContext;
import dev.marregui.comando.ui.widgets.transcript.TranscriptView;
import dev.marregui.comando.ui.widgets.transcript.TranscriptWidget;
import dev.marregui.comando.ui.widgets.transcript.render.TranscriptCanvas;

/// A session's transcript on screen follows the end after the user sends a prompt from a panel, even when the user had
/// scrolled up to read: the prompt lands in view and the answer that streams under it stays in view, with no "Latest"
/// click. Opens windows, so this runs in `./gradlew :app:uiTest`.
/// - in the real window, a prompt run from the session's panel, the session's note right after it
/// - a prompt and the block after it delivered before the queued scroll ran, then a turn streaming one block at a time
/// - a prompt sent while the Session tab is hidden shows the end when the tab is shown again
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class TranscriptFollowUiTest {

    private static final String SESSION = "follow";
    private static final Duration WAIT = Duration.ofSeconds(10);

    private final AtomicReference<JFrame> frame = new AtomicReference<>();

    @TempDir
    Path home;

    @AfterEach
    void closeTheWindow() throws Exception {
        onEdt(() -> {
            if (frame.get() != null) {
                frame.get().dispose();
            }
            return null;
        });
    }

    /// In the real window, the Session tab scrolled up to the middle, a prompt run from the session's panel lands in
    /// view, though the session's note ("not sent", here) follows it at once: the transcript shows its end with the
    /// "Latest" button hidden, and stays there while a turn streams under it.
    @Test
    void inTheWindowAPromptRunFromThePanelShowsTheEnd() throws Exception {
        try (final App app = new App(new AppConfig(home.resolve("data"), home.resolve("no-claude").toString(),
                home.resolve("projects"), false), "follow", () -> {
        })) {
            app.start();
            final StateLoop loop = app.loop();
            Awaitility.await("the window").atMost(Duration.ofSeconds(20)).until(()
                    -> loop.state().focused() != null && onEdt(() -> window() != null));
            loop.dispatch(new Intent.SaveSession("", SessionSpec.fresh(SESSION, home.toString())));
            loop.dispatch(new Intent.BindSession(loop.state().focusedPanel().id(), SESSION));
            Awaitility.await("the session's panel in front").atMost(WAIT).until(() -> SESSION.equals(loop.state()
                    .focusedPanel().session()));
            final Transcript t = onEdt(() -> app.transcripts().of(SESSION));
            onEdt(() -> {
                final Transcript filled = longConversation();
                for (int i = 0; i < filled.size(); i++) {
                    t.append(filled.get(i));
                }
                t.changed();
                return null;
            });
            loop.dispatch(new Intent.SelectLowerTab(LowerTab.SESSION));
            final JScrollBar bar = await(JScrollBar.class, b -> b.getParent() instanceof JScrollPane p
                    && p.getViewport().getView() instanceof TranscriptCanvas
                    && b.getOrientation() == JScrollBar.VERTICAL && b.getMaximum() > 10 * b.getVisibleAmount());
            Awaitility.await("at the end").atMost(WAIT).until(() -> onEdt(() -> atEnd(bar)));
            onEdt(() -> {
                bar.setValue(bar.getMaximum() / 2);
                return null;
            });
            final JButton latest = await(JButton.class, b -> b.getText().contains("Latest"));

            loop.dispatch(new Intent.RunText(loop.state().focused(), "what next?", Target.CLAUDE));
            Awaitility.await("the prompt in view").atMost(WAIT).until(() -> onEdt(()
                    -> t.get(t.size() - 1) instanceof Block.Note && atEnd(bar) && !latest.isVisible()));
            for (final SyntheticSession.Step step : secondTurn(onEdt(t::size))) {
                onEdt(() -> {
                    apply(t, step);
                    t.changed();
                    return null;
                });
            }
            Awaitility.await("at the end, the way back hidden, once the rows are measured")
                    .during(Duration.ofSeconds(1)).atMost(WAIT)
                    .until(() -> onEdt(() -> atEnd(bar) && !latest.isVisible()));
        }
    }

    /// Scrolled up in a long transcript, the user sends a prompt, and before the scroll the prompt queued has run the
    /// block after it arrives and the view moves, as the canvas moves it to keep the block in view in its place (all
    /// in one event); then a turn streams one block at a time. The view ends at the bottom with the "Latest" button
    /// hidden, and stays so for a second.
    @Test
    void aPromptAndTheNextBlockInOneEventStayAtTheEndWhileTheAnswerStreams() throws Exception {
        final Transcript t = longConversation();
        final TranscriptWidget widget = onEdt(() -> {
            final TranscriptWidget w = new TranscriptWidget(new WidgetContext(_ -> { }, new ActionRegistry()));
            show(w.component(), "transcript follow");
            w.render(new TranscriptView(SESSION, t));
            return w;
        });
        final JScrollBar bar = barOf(widget);
        Awaitility.await("laid out at the end").atMost(WAIT).until(() -> onEdt(()
                -> bar.getMaximum() > 10 * bar.getVisibleAmount() && atEnd(bar)));
        scrollToTheMiddle(widget, bar);

        final List<SyntheticSession.Step> turn = secondTurn(t.size() + 1);
        onEdt(() -> {
            t.append(new Block.UserPrompt("what next?", "main", ""));
            widget.render(new TranscriptView(SESSION, t));
            apply(t, turn.getFirst());
            widget.render(new TranscriptView(SESSION, t));
            bar.setValue(bar.getValue() + 1);
            return null;
        });
        for (final SyntheticSession.Step step : turn.subList(1, turn.size())) {
            onEdt(() -> {
                apply(t, step);
                widget.render(new TranscriptView(SESSION, t));
                return null;
            });
        }
        Awaitility.await("at the end, the way back hidden, once the rows are measured").during(Duration.ofSeconds(1))
                .atMost(WAIT).until(() -> onEdt(() -> atEnd(bar) && !latest(widget).isVisible()));
    }

    /// The reader scrolled up in the Session tab, moved to the Results tab and sent a prompt from there; the answer
    /// streams while the transcript is hidden. Back on the Session tab, the view is at the end with the "Latest" button
    /// hidden, and stays so for a second once the canvas measured its rows.
    @Test
    void aPromptSentWhileTheTranscriptIsHiddenShowsTheEndWhenItIsShownAgain() throws Exception {
        final Transcript t = longConversation();
        final JTabbedPane tabs = onEdt(JTabbedPane::new);
        final TranscriptWidget widget = onEdt(() -> {
            final TranscriptWidget w = new TranscriptWidget(new WidgetContext(_ -> { }, new ActionRegistry()));
            tabs.addTab("Session", w.component());
            tabs.addTab("Results", new JPanel());
            show(tabs, "transcript follow hidden");
            w.render(new TranscriptView(SESSION, t));
            return w;
        });
        final JScrollBar bar = barOf(widget);
        Awaitility.await("laid out at the end").atMost(WAIT).until(() -> onEdt(()
                -> bar.getMaximum() > 10 * bar.getVisibleAmount() && atEnd(bar)));
        scrollToTheMiddle(widget, bar);
        onEdt(() -> {
            tabs.setSelectedIndex(1);
            t.append(new Block.UserPrompt("what next?", "main", ""));
            widget.render(new TranscriptView(SESSION, t));
            return null;
        });
        for (final SyntheticSession.Step step : secondTurn(t.size())) {
            onEdt(() -> {
                apply(t, step);
                widget.render(new TranscriptView(SESSION, t));
                return null;
            });
        }
        onEdt(() -> {
            tabs.setSelectedIndex(0);
            return null;
        });
        Awaitility.await("at the end, the way back hidden, once the rows are measured").during(Duration.ofSeconds(1))
                .atMost(WAIT).until(() -> onEdt(() -> atEnd(bar) && !latest(widget).isVisible()));
    }

    private void show(final Component content, final String title) {
        final JFrame f = new JFrame(title);
        f.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        f.add(content);
        f.setSize(700, 500);
        f.setVisible(true);
        frame.set(f);
    }

    /// Forty turns that never ended, so nothing folds: each a prompt, a long answer and a finished tool call with a
    /// long result, many screens high.
    private static Transcript longConversation() {
        final Transcript t = new Transcript();
        final String para = "The reader is somewhere in the middle of a long answer, reading what the tool returned. "
                .repeat(12);
        for (int turn = 0; turn < 40; turn++) {
            t.append(new Block.UserPrompt("question " + turn, "main", ""));
            t.append(new Block.AssistantText("m" + turn, 0, (para + "\n\n").repeat(4), false, ""));
            t.append(new Block.ToolCall("toolu_" + turn, "Bash", "{\"command\":\"ls\"}", "line of output\n".repeat(60),
                    Block.ToolStatus.DONE, ""));
        }
        return t;
    }

    /// The steps of the synthetic session's second turn after its prompt, replacements moved to the transcript's
    /// indexes: the turn's first block lands at {@code at}.
    private static List<SyntheticSession.Step> secondTurn(final int at) {
        final List<SyntheticSession.Step> out = new ArrayList<>();
        int appended = 0;
        int prompts = 0;
        int start = -1;
        for (final SyntheticSession.Step s : SyntheticSession.steps(24)) {
            if (s.index() < 0 && s.block() instanceof Block.UserPrompt p && p.parent().isEmpty()) {
                prompts++;
                if (prompts == 3) {
                    break;
                }
                start = appended + 1;
            } else if (prompts == 2) {
                out.add(new SyntheticSession.Step(s.index() < 0 ? -1 : s.index() - start + at, s.block()));
            }
            if (s.index() < 0) {
                appended++;
            }
        }
        return out;
    }

    private static void apply(final Transcript t, final SyntheticSession.Step step) {
        if (step.index() < 0) {
            t.append(step.block());
        } else {
            t.replace(step.index(), step.block());
        }
    }

    /// The reader scrolls up to the middle, to read a result; the way back to the end shows.
    private static void scrollToTheMiddle(final TranscriptWidget widget, final JScrollBar bar) throws Exception {
        onEdt(() -> {
            bar.setValue(bar.getMaximum() / 2);
            return null;
        });
        Awaitility.await("the way back shows").atMost(WAIT).until(() -> onEdt(() -> latest(widget).isVisible()));
    }

    /// The widget's own test of the end, read off its bar: within 40 px of the bottom.
    private static boolean atEnd(final JScrollBar bar) {
        return bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 40;
    }

    private static JScrollBar barOf(final TranscriptWidget widget) throws Exception {
        return onEdt(() -> find(widget.component(), JScrollPane.class, _ -> true).getVerticalScrollBar());
    }

    private static JButton latest(final TranscriptWidget widget) {
        return find(widget.component(), JButton.class, b -> b.getText().contains("Latest"));
    }

    private static <T extends Component> T await(final Class<T> type, final Predicate<T> which) {
        final AtomicReference<T> out = new AtomicReference<>();
        Awaitility.await(type.getSimpleName()).atMost(WAIT).until(() -> {
            out.set(onEdt(() -> find(window(), type, c -> c.isShowing() && which.test(c))));
            return out.get() != null;
        });
        return out.get();
    }

    private static JFrame window() {
        for (final Frame f : Frame.getFrames()) {
            if (f instanceof JFrame j && f.isDisplayable() && f.getTitle().startsWith("comando")) {
                return j;
            }
        }
        return null;
    }

    private static <T extends Component> T find(final Component c, final Class<T> type, final Predicate<T> which) {
        if (c == null) {
            return null;
        }
        if (type.isInstance(c) && which.test(type.cast(c))) {
            return type.cast(c);
        }
        if (c instanceof Container k) {
            for (final Component child : k.getComponents()) {
                final T found = find(child, type, which);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static <T> T onEdt(final Callable<T> call) throws Exception {
        final AtomicReference<T> out = new AtomicReference<>();
        final AtomicReference<Exception> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                out.set(call.call());
            } catch (final Exception e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return out.get();
    }
}
