package dev.marregui.comando.app;

import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.state.BackgroundTask;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.ui.widgets.transcript.render.TranscriptCanvas;

import static org.assertj.core.api.Assertions.assertThat;

/// The attention list in the real window. Opens the window, so this runs in `./gradlew :app:uiTest`.
/// - a turn that ends on an unfocused session puts it under To read in the Sessions tab and in the work list
/// - a click on its line brings its panel forward and the mark goes, from the row and from the count
/// - that click lands on the report
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class AttentionUiTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    @TempDir
    Path home;

    /// `work` ends a turn behind another panel: its row reads To read and the count says 1 to read. A click on its work
    /// line focuses its panel; the session is no longer marked, its row is under Idle and the count has no To read.
    @Test
    void aTurnEndedOutOfSightIsToReadUntilItsLineIsClicked() throws Exception {
        final App app = new App(new AppConfig(home.resolve("data"), home.resolve("no-claude").toString(),
                home.resolve("projects"), false), "attention", () -> {
        });
        try {
            app.start();
            final StateLoop loop = app.loop();
            Awaitility.await("the window").atMost(Duration.ofSeconds(20)).until(() -> loop.state().focused() != null);
            final PanelId first = loop.state().focused();
            loop.dispatch(new Intent.SaveSession("", SessionSpec.fresh("work", home.toString())));
            Awaitility.await("its panel").atMost(WAIT).until(() -> loop.state().panels().stream()
                    .anyMatch(p -> p.session().equals("work")));
            final PanelId bound = loop.state().panels().stream().filter(p -> p.session().equals("work")).findFirst()
                    .orElseThrow().id();
            loop.dispatch(new Intent.FocusPanel(first));
            loop.dispatch(new Intent.SessionTurnRunning("work"));
            Awaitility.await("behind another panel").atMost(WAIT).until(() -> first.equals(loop.state().focused())
                    && loop.state().session("work").orElseThrow().isBusy());
            loop.dispatch(new Intent.SessionTurnEnded("work", 0, 0.1, 10_000L, 200_000L, false, ""));

            final JList<?> sessions = await(JList.class,
                    l -> "Sessions".equals(l.getAccessibleContext().getAccessibleName()));
            Awaitility.await("under To read").atMost(WAIT).until(() -> "To read".equals(group(sessions)));
            final JLabel count = await(JLabel.class, l -> "Work".equals(l.getAccessibleContext().getAccessibleName())
                    && l.getText().contains("1 to read"));
            click(count, onEdt(() -> new Rectangle(0, 0, count.getWidth(), count.getHeight())));

            @SuppressWarnings("unchecked")
            final JList<Object> list = await(JList.class,
                    l -> "Work".equals(l.getAccessibleContext().getAccessibleName()));
            assertThat(onEdt(() -> list.getModel().getSize())).isEqualTo(1);
            assertThat(onEdt(() -> list.getModel().getElementAt(0).toString())).contains("work · to read");
            click(list, onEdt(() -> list.getCellBounds(0, 0)));

            Awaitility.await("its panel in front").atMost(WAIT).until(() -> bound.equals(loop.state().focused()));
            Awaitility.await("the mark gone").atMost(WAIT)
                    .until(() -> !loop.state().session("work").orElseThrow().spec().unread());
            Awaitility.await("out of To read").atMost(WAIT).until(() -> "Idle".equals(group(sessions)));
            Awaitility.await("out of the count").atMost(WAIT).until(() -> onEdt(() -> !count.getText()
                    .contains("to read")));
        } finally {
            app.close();
        }
    }

    /// A click on the line of a finished session's report brings its panel forward with the report's first line at the
    /// top of the view: a session's long answer is read from its start, not from where the transcript last ended.
    @Test
    void aClickOnItsLineLandsOnTheReport() throws Exception {
        clickLandsOnTheReport(false);
    }

    /// The same with a sub-agent's chip on the transcript in front: the chip goes as the panel changes, and the scroll
    /// that keeps a followed transcript at its end must not take the view off the report.
    @Test
    void aClickOnItsLineLandsOnTheReportPastASubAgentsChip() throws Exception {
        clickLandsOnTheReport(true);
    }

    private void clickLandsOnTheReport(final boolean chip) throws Exception {
        final App app = new App(new AppConfig(home.resolve("data"), home.resolve("no-claude").toString(),
                home.resolve("projects"), false), "attention", () -> {
        });
        try {
            app.start();
            final StateLoop loop = app.loop();
            Awaitility.await("the window").atMost(Duration.ofSeconds(20)).until(() -> loop.state().focused() != null);
            final PanelId first = loop.state().focused();
            loop.dispatch(new Intent.SaveSession("", SessionSpec.fresh("work", home.toString())));
            Awaitility.await("its panel").atMost(WAIT).until(() -> loop.state().panels().stream()
                    .anyMatch(p -> p.session().equals("work")));
            onEdt(() -> {
                final Transcript t = app.transcripts().of("work");
                t.append(new Block.UserPrompt("before", "", ""));
                t.append(new Block.AssistantText("m1", 0, "an earlier answer\n\n".repeat(80), false, ""));
                t.append(new Block.TurnEnd(0.1, 1_000, 2, false, ""));
                t.append(new Block.UserPrompt("now", "", ""));
                t.append(new Block.AssistantText("m2", 0, "a line of the report\n\n".repeat(120), false, ""));
                t.append(new Block.TurnEnd(0.1, 1_000, 2, false, ""));
                t.changed();
                return null;
            });
            PanelId front = first;
            if (chip) {
                loop.dispatch(new Intent.SaveSession("", SessionSpec.fresh("reader", home.toString())));
                Awaitility.await("the reader's panel").atMost(WAIT).until(() -> loop.state().panels().stream()
                        .anyMatch(p -> p.session().equals("reader")));
                front = loop.state().panels().stream().filter(p -> p.session().equals("reader")).findFirst()
                        .orElseThrow().id();
                loop.dispatch(new Intent.SessionTasksChanged("reader",
                        List.of(new BackgroundTask("t1", "local_agent", "a run", 0L))));
            }
            final PanelId shown = front;
            loop.dispatch(new Intent.FocusPanel(shown));
            loop.dispatch(new Intent.SessionTurnRunning("work"));
            Awaitility.await("behind another panel").atMost(WAIT).until(() -> shown.equals(loop.state().focused())
                    && loop.state().session("work").orElseThrow().isBusy());
            loop.dispatch(new Intent.SessionTurnEnded("work", 0, 0.1, 10_000L, 200_000L, false, ""));

            final JLabel count = await(JLabel.class, l -> "Work".equals(l.getAccessibleContext().getAccessibleName())
                    && l.getText().contains("1 to read"));
            click(count, onEdt(() -> new Rectangle(0, 0, count.getWidth(), count.getHeight())));
            final JList<?> list = await(JList.class, l -> "Work".equals(l.getAccessibleContext().getAccessibleName()));
            click(list, onEdt(() -> list.getCellBounds(0, 0)));

            final TranscriptCanvas canvas = await(TranscriptCanvas.class, c -> c.reportTop() > 0);
            Awaitility.await("the report at the top of the view").atMost(WAIT).until(() -> onEdt(
                    () -> canvas.getParent() instanceof JViewport v && v.getViewPosition().y == canvas.reportTop()));
        } finally {
            app.close();
        }
    }

    /** The group `work`'s row says it is in, as the list tells assistive technology. */
    private static String group(final JList<?> sessions) throws Exception {
        return onEdt(() -> {
            for (int i = 0; i < sessions.getModel().getSize(); i++) {
                if (sessions.getModel().getElementAt(i) instanceof SessionState s && s.name().equals("work")) {
                    return description(sessions, i);
                }
            }
            return "";
        });
    }

    private static <T> String description(final JList<T> list, final int index) {
        final Component row = list.getCellRenderer().getListCellRendererComponent(list,
                list.getModel().getElementAt(index), index, false, false);
        return row.getAccessibleContext().getAccessibleDescription();
    }

    private static void click(final Component c, final Rectangle at) throws Exception {
        SwingUtilities.invokeAndWait(() -> c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_CLICKED, 0L, 0, at.x + 2,
                at.y + 2, 1, false, MouseEvent.BUTTON1)));
    }

    private static <T extends Component> T await(final Class<T> type, final Predicate<T> which) {
        final AtomicReference<T> out = new AtomicReference<>();
        Awaitility.await(type.getSimpleName()).atMost(WAIT).until(() -> {
            out.set(onEdt(() -> {
                for (final Window w : Window.getWindows()) {
                    final T found = find(w, type, c -> c.isShowing() && which.test(c));
                    if (found != null) {
                        return found;
                    }
                }
                return null;
            }));
            return out.get() != null;
        });
        return out.get();
    }

    private static <T extends Component> T find(final Component c, final Class<T> type, final Predicate<T> which) {
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
