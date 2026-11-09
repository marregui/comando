package dev.marregui.comando.app;

import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Frame;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import javax.swing.JFrame;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.state.LowerTab;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.ui.kit.action.ActionRegistry;
import dev.marregui.comando.ui.widget.WidgetContext;
import dev.marregui.comando.ui.widgets.transcript.TranscriptView;
import dev.marregui.comando.ui.widgets.transcript.TranscriptWidget;
import dev.marregui.comando.ui.widgets.transcript.render.TranscriptCanvas;

import static org.assertj.core.api.Assertions.assertThat;

/// A session's transcript read report first, in the real window. Opens the window, so this runs in
/// `./gradlew :app:uiTest`.
/// - a turn of tools and a report folds to one row in the Session tab
/// - a click on the row opens it and a second closes it
/// - a find reveals a match inside the closed row, and a find that moves on closes it again
/// - asking for the report scrolls it to the top of the view and stops following the end
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class ReportFirstUiTest {

    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final String SESSION = "folded";
    private static final int TOOLS = 12;
    /** The least a card adds to the transcript, in pixels; the row a closed turn leaves is less than two of them. */
    private static final int CARD_MIN_HEIGHT = 16;

    @TempDir
    Path home;

    /// A turn of twelve calls and a report reads as one row above the report; a click on the row opens the calls and a
    /// second closes them; a find for the result of one call opens the row and that card, and a find for something else
    /// closes both.
    @Test
    void aTurnOfToolsAndAReportFoldsToOneRowAClickOpensItAFindRevealsInsideIt() throws Exception {
        try (final App app = new App(new AppConfig(home.resolve("data"), home.resolve("no-claude").toString(),
                home.resolve("projects"), false),
                "report-first", () -> {
        })) {
            app.start();
            final StateLoop loop = app.loop();
            Awaitility.await("the window").atMost(Duration.ofSeconds(20)).until(()
                    -> loop.state().focused() != null && onEdt(() -> frame() != null));
            loop.dispatch(new Intent.SaveSession("", SessionSpec.fresh(SESSION, home.toString())));
            loop.dispatch(new Intent.BindSession(loop.state().focusedPanel().id(), SESSION));
            Awaitility.await("the session").atMost(WAIT).until(() -> loop.state().session(SESSION).isPresent());
            onEdt(() -> {
                final Transcript t = app.transcripts().of(SESSION);
                turn(t, "The store drops pending retries.");
                t.changed();
                return null;
            });
            loop.dispatch(new Intent.SelectLowerTab(LowerTab.SESSION));
            final TranscriptCanvas canvas = await(TranscriptCanvas.class,
                    c -> c.contentHeight() > 0 && c.reportTop() >= 0);

            final int row = onEdt(() -> rowOf(canvas));
            final int closed = onEdt(canvas::reportTop);
            assertThat(closed - row).as("one row between the prompt and the report").isLessThan(CARD_MIN_HEIGHT * 2);

            onEdt(() -> click(canvas, row));
            final int open = onEdt(canvas::reportTop);
            assertThat(open - closed).as("the click shows the calls").isGreaterThanOrEqualTo(TOOLS * CARD_MIN_HEIGHT);

            onEdt(() -> click(canvas, row));
            assertThat(onEdt(canvas::reportTop)).as("a second click closes the row").isEqualTo(closed);

            assertThat(onEdt(() -> canvas.find("result of call 7", true))).as("the match inside the row").isEqualTo(1);
            assertThat(onEdt(() -> canvas.isCardOpen("t7"))).as("its card shows").isTrue();
            assertThat(onEdt(canvas::reportTop)).as("the row opened for it").isGreaterThan(closed);

            assertThat(onEdt(() -> canvas.find("nothing says this", true))).as("a find that moves on").isZero();
            assertThat(onEdt(() -> canvas.isCardOpen("t7"))).as("the card closes with it").isFalse();
            assertThat(onEdt(canvas::reportTop)).as("and so does the row").isEqualTo(closed);
        }
    }

    /// Asked for the report, a following transcript on screen stops following and puts the report's first line at the
    /// top
    /// of the view, with the end of the report below it.
    @Test
    void askingForTheReportScrollsItToTheTopOfTheView() throws Exception {
        final TranscriptWidget widget = onEdt(() -> new TranscriptWidget(new WidgetContext(_ -> { },
                new ActionRegistry())));
        final JFrame window = onEdt(() -> {
            final JFrame f = new JFrame("report");
            f.add(widget.component());
            f.setSize(800, 500);
            f.setVisible(true);
            final Transcript t = new Transcript();
            t.append(new Block.UserPrompt("before", "", ""));
            t.append(new Block.AssistantText("m", 0, "an earlier answer\n\n".repeat(60), false, ""));
            t.append(new Block.TurnEnd(0.1, 1_000, 2, false, ""));
            turn(t, "a line of the report\n\n".repeat(120));
            widget.render(new TranscriptView("s", t));
            return f;
        });
        try {
            final JViewport viewport = onEdt(() -> (JViewport) widget.canvas().getParent());
            Awaitility.await("the transcript at its end").atMost(WAIT).until(() -> onEdt(()
                    -> viewport.getViewPosition().y > 0));

            onEdt(() -> {
                widget.showReport();
                return null;
            });

            Awaitility.await("the report at the top").atMost(WAIT)
                    .until(() -> onEdt(() -> viewport.getViewPosition().y == widget.canvas().reportTop()));
        } finally {
            onEdt(() -> {
                window.dispose();
                return null;
            });
        }
    }

    /** A prompt, {@link #TOOLS} calls and {@code report}, ended. */
    private static void turn(final Transcript t, final String report) {
        t.append(new Block.UserPrompt("go", "p", ""));
        for (int i = 0; i < TOOLS; i++) {
            t.append(new Block.ToolCall("t" + i, "Bash", "{\"command\":\"ls\"}", "result of call " + i,
                    Block.ToolStatus.DONE, ""));
        }
        t.append(new Block.AssistantText("m", 0, report, false, ""));
        t.append(new Block.TurnEnd(0.1, 372_000, 2, false, ""));
    }

    /** The first line above the report the pointer can toggle: the turn's row. */
    private static int rowOf(final TranscriptCanvas canvas) {
        final int report = canvas.reportTop();
        for (int y = canvas.promptTop(report, false); y < report; y++) {
            canvas.getMouseMotionListeners()[0].mouseMoved(new MouseEvent(canvas, MouseEvent.MOUSE_MOVED, 0L, 0, 40,
                    y, 0, false, MouseEvent.NOBUTTON));
            if (canvas.getCursor().getType() == Cursor.HAND_CURSOR) {
                return y;
            }
        }
        throw new AssertionError("no row above the report");
    }

    private static Void click(final TranscriptCanvas canvas, final int y) {
        canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, 0L, InputEvent.BUTTON1_DOWN_MASK, 40, y,
                1, false, MouseEvent.BUTTON1));
        canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, 0L, 0, 40, y, 1, false,
                MouseEvent.BUTTON1));
        return null;
    }

    private static <T extends Component> T await(final Class<T> type, final Predicate<T> which) {
        final AtomicReference<T> out = new AtomicReference<>();
        Awaitility.await(type.getSimpleName()).atMost(WAIT).until(() -> {
            out.set(onEdt(() -> find(frame(), type, c -> c.isShowing() && which.test(c))));
            return out.get() != null;
        });
        return out.get();
    }

    private static JFrame frame() {
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

    private static <T> T onEdt(final EdtCall<T> call) throws Exception {
        final AtomicReference<T> out = new AtomicReference<>();
        final AtomicReference<Exception> err = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                out.set(call.call());
            } catch (final Exception e) {
                err.set(e);
            }
        });
        if (err.get() != null) {
            throw err.get();
        }
        return out.get();
    }

    private interface EdtCall<T> {
        T call() throws Exception;
    }
}
