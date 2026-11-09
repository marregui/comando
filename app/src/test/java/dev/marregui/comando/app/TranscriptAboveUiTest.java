package dev.marregui.comando.app;

import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import javax.swing.JFrame;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.state.LowerTab;
import dev.marregui.comando.core.view.Layout;

import static org.assertj.core.api.Assertions.assertThat;

/// The transcript above the prompt (record 144), in the real window. Opens the window, so this runs in
/// `./gradlew :app:uiTest`.
/// - a session's panel in front puts the lower panel, the transcript's, above the panels; a file in front puts it
///   back below; the lower panel's height comes back the same after each flip
/// - an arrangement with the transcript below, as an agent sends it, puts it back below
/// - started again with a session's panel in front, the lower panel opens above at the height it had
/// - a session's panel never in front, brought forward while a file had hidden the lower panel, opens it above at the
///   height last shown
/// - turned off in `settings.json`, or with no setting (the default since the trial ended), a session's panel keeps
///   the lower panel below
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class TranscriptAboveUiTest {

    /// The lower panel's height the test sets while it sits above, in pixels.
    private static final int LOWER = 200;
    private static final String SESSION = "above";
    private static final Duration WAIT = Duration.ofSeconds(10);

    @TempDir
    Path home;

    /// The window's first panel bound to a session puts the lower panel above it; set to 200 px there, a file to read
    /// brought forward with its lower area asked for has it below; the session's panel brought back has it above,
    /// 200 px high again.
    @Test
    void aSessionsPanelHasTheTranscriptAboveAFileHasItBelowEachAtItsHeight() throws Exception {
        final Path notes = Files.writeString(home.resolve("notes.md"), "# Notes\n");
        try (final App app = start("transcript-above")) {
            final StateLoop loop = app.loop();
            final JSplitPane vertical = vertical();
            final Component lower = onEdt(vertical::getBottomComponent);
            final PanelId panel = bind(app);
            Awaitility.await("the lower panel above the session's").atMost(WAIT).until(() -> onEdt(()
                    -> vertical.getTopComponent() == lower && vertical.getHeight() > 400));
            onEdt(() -> {
                vertical.setDividerLocation(LOWER);
                return null;
            });

            loop.dispatch(new Intent.OpenFile("", notes.toString(), 0));
            Awaitility.await("the file's panel").atMost(WAIT).until(() -> loop.state().focusedPanel().title()
                    .equals("notes.md"));
            loop.dispatch(new Intent.SelectLowerTab(LowerTab.RESULTS));
            Awaitility.await("below for the file").atMost(WAIT).until(()
                    -> onEdt(() -> vertical.getBottomComponent() == lower));
            Awaitility.await("shown for the file").atMost(WAIT).until(() -> onEdt(lower::isVisible));

            loop.dispatch(new Intent.FocusPanel(panel));
            Awaitility.await("above again, at its height").atMost(WAIT).until(() -> onEdt(()
                    -> vertical.getTopComponent() == lower && vertical.getDividerLocation() == LOWER));
        }
    }

    /// With no setting written, the default since the trial ended, a session's panel keeps the lower panel below it.
    @Test
    void byDefaultASessionsPanelKeepsTheTranscriptBelow() throws Exception {
        try (final App app = new App(new AppConfig(home.resolve("data"), home.resolve("no-claude").toString(),
                home.resolve("projects"), false), "transcript-default", () -> {
        })) {
            app.start();
            Awaitility.await("the window").atMost(Duration.ofSeconds(20)).until(()
                    -> app.loop().state().focused() != null && onEdt(() -> frame() != null));
            final JSplitPane vertical = vertical();
            final Component lower = onEdt(vertical::getBottomComponent);
            bind(app);
            Awaitility.await("laid out").atMost(WAIT).until(() -> onEdt(vertical::getHeight) > 400);
            assertThat(onEdt(() -> vertical.getBottomComponent() == lower))
                    .as("the lower panel below the session's").isTrue();
        }
    }

    /// With the trial turned off before the start, the session's panel keeps the lower panel below it.
    @Test
    void turnedOffASessionsPanelKeepsTheTranscriptBelow() throws Exception {
        final Path data = Files.createDirectories(home.resolve("data"));
        Files.writeString(data.resolve("settings.json"), "{\"version\": 1, \"layout.transcriptAbove\": false}");
        try (final App app = start("transcript-below")) {
            final JSplitPane vertical = vertical();
            final Component lower = onEdt(vertical::getBottomComponent);
            bind(app);
            Awaitility.await("laid out").atMost(WAIT).until(() -> onEdt(vertical::getHeight) > 400);
            assertThat(onEdt(() -> vertical.getBottomComponent() == lower))
                    .as("the lower panel below the session's").isTrue();
        }
    }

    /// An arrangement that turns the trial off, as `ui_set_layout` with `transcript_above` false sends, puts the lower
    /// panel back below the session's panel, the keyboard still in the component that had it.
    @Test
    void anArrangementPutsTheTranscriptBelow() throws Exception {
        try (final App app = start("transcript-arranged")) {
            final JSplitPane vertical = vertical();
            final Component lower = onEdt(vertical::getBottomComponent);
            bind(app);
            Awaitility.await("above first").atMost(WAIT).until(() -> onEdt(() -> vertical.getTopComponent() == lower));
            // the keyboard in the session's prompt, under the lower panel
            final JTextComponent prompt = onEdt(() -> find(vertical.getBottomComponent(), JTextComponent.class,
                    JTextComponent::isShowing));
            Awaitility.await("the keyboard in the prompt").atMost(WAIT).until(() -> onEdt(() -> {
                prompt.requestFocusInWindow();
                return frame().getMostRecentFocusOwner() == prompt;
            }));

            app.loop().dispatch(new Intent.Arrange(new Layout(Layout.Toggle.KEEP, Layout.Toggle.KEEP, Double.NaN, null,
                    null, "", Layout.Toggle.HIDE)));
            Awaitility.await("below after the arrangement").atMost(WAIT).until(()
                    -> onEdt(() -> vertical.getBottomComponent() == lower));
            Awaitility.await("the keyboard where it was").atMost(WAIT).until(() -> onEdt(()
                    -> frame().getMostRecentFocusOwner() == prompt));
        }
    }

    /// A session's panel, the window's only one, with the lower panel above, 200 px high, at the quit: the next start
    /// lays the window out with the lower panel above, 200 px high again, not squeezed to nothing before the window
    /// had a height.
    @Test
    void startedAgainTheTranscriptIsAboveAtItsHeight() throws Exception {
        try (final App app = start("transcript-quit")) {
            final JSplitPane vertical = vertical();
            final Component lower = onEdt(vertical::getBottomComponent);
            final PanelId plain = app.loop().state().focused();
            bind(app);
            // the session's panel in front at the next start: the panel a start focuses is the first one
            app.loop().dispatch(new Intent.ClosePanel(plain));
            Awaitility.await("the session's panel alone").atMost(WAIT).until(()
                    -> app.loop().state().panels().size() == 1
                    && SESSION.equals(app.loop().state().focusedPanel().session()));
            Awaitility.await("above").atMost(WAIT).until(() -> onEdt(()
                    -> vertical.getTopComponent() == lower && vertical.getHeight() > 400));
            onEdt(() -> {
                vertical.setDividerLocation(LOWER);
                return null;
            });
        }
        try (final App again = start("transcript-again")) {
            final JSplitPane vertical = vertical();
            Awaitility.await("the session's panel in front").atMost(WAIT).until(()
                    -> SESSION.equals(again.loop().state().focusedPanel().session()));
            Awaitility.await("above again, at its height").atMost(WAIT).until(() -> onEdt(()
                    -> vertical.getHeight() > 400 && vertical.getDividerLocation() == LOWER));
        }
    }

    /// A file to read in front hides the lower panel; a second session's panel, never in front before, opened then
    /// shows it above at the height the lower panel last had, not at none.
    @Test
    void aNewSessionsPanelAfterAFileShowsTheTranscriptAtTheLastHeight() throws Exception {
        final Path notes = Files.writeString(home.resolve("notes.md"), "# Notes\n");
        try (final App app = start("transcript-new")) {
            final StateLoop loop = app.loop();
            final JSplitPane vertical = vertical();
            final Component lower = onEdt(vertical::getBottomComponent);
            final PanelId first = bind(app);
            Awaitility.await("above").atMost(WAIT).until(() -> onEdt(()
                    -> vertical.getTopComponent() == lower && vertical.getHeight() > 400));
            onEdt(() -> {
                vertical.setDividerLocation(LOWER);
                return null;
            });
            loop.dispatch(new Intent.OpenFile("", notes.toString(), 0));
            Awaitility.await("hidden for the file").atMost(WAIT).until(() -> !onEdt(lower::isVisible));

            loop.dispatch(new Intent.SaveSession("", SessionSpec.fresh("second", home.toString())));
            loop.dispatch(new Intent.OpenSession("second", first, new PanelId("second-panel")));
            Awaitility.await("the second session's panel in front").atMost(WAIT).until(() -> "second"
                    .equals(loop.state().focusedPanel().session()));
            Awaitility.await("above at the last height").atMost(WAIT).until(() -> onEdt(()
                    -> lower.isVisible() && vertical.getTopComponent() == lower
                    && vertical.getDividerLocation() == LOWER));
        }
    }

    /// The window's split between the panels and the lower panel: the vertical split not inside another split.
    private static JSplitPane vertical() throws Exception {
        return onEdt(() -> find(frame(), JSplitPane.class, p -> p.getOrientation() == JSplitPane.VERTICAL_SPLIT
                && !(p.getParent() instanceof JSplitPane)));
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

    /// Starts comando on the test's data root, with the transcript above turned on unless a test wrote its own
    /// `settings.json`: off is the default since the trial ended (record 144).
    private App start(final String name) throws Exception {
        final Path settings = Files.createDirectories(home.resolve("data")).resolve("settings.json");
        if (!Files.exists(settings)) {
            Files.writeString(settings, "{\"version\": 1, \"layout.transcriptAbove\": true}");
        }
        final App app = new App(new AppConfig(home.resolve("data"), home.resolve("no-claude").toString(),
                home.resolve("projects"), false), name, () -> {
        });
        try {
            app.start();
            Awaitility.await("the window").atMost(Duration.ofSeconds(20)).until(()
                    -> app.loop().state().focused() != null && onEdt(() -> frame() != null));
        } catch (final Exception | AssertionError e) {
            // the caller's try-with-resources holds the window only once it is returned
            app.close();
            throw e;
        }
        return app;
    }

    /// Binds the window's first panel to a fresh session, which opens the session's own panel in front; its id.
    private PanelId bind(final App app) {
        final StateLoop loop = app.loop();
        loop.dispatch(new Intent.SaveSession("", SessionSpec.fresh(SESSION, home.toString())));
        loop.dispatch(new Intent.BindSession(loop.state().focused(), SESSION));
        Awaitility.await("the session's panel in front").atMost(WAIT).until(() -> SESSION.equals(loop.state()
                .focusedPanel().session()));
        return loop.state().focused();
    }
}
