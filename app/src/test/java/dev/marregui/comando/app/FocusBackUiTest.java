package dev.marregui.comando.app;

import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.SwingUtilities;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.state.LowerTab;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.ui.panels.EditorPanel;
import dev.marregui.comando.ui.widgets.transcript.render.TranscriptCanvas;

import static org.assertj.core.api.Assertions.assertThat;

/// Closing what a double click opened gives the keyboard back to what opened it, in the real window. Opens the window,
/// so this runs in `./gradlew :app:uiTest`; the window is off screen and never focused, so the keyboard is read as the
/// window's most recent focus owner.
/// - A opens B, B opens C: closing C brings B forward with the keyboard where it was, closing B brings A back
/// - a file opened from the sidebar's session list gives the keyboard back there
/// - a file already open goes back to the panel that opened it last; a keyboard left behind counts as the front panel
/// - a file opened from the Session tab goes back to that session's panel with its Session tab in front
/// - closing returns to where the user came from last, and a close in the background moves nothing
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class FocusBackUiTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    @TempDir
    Path home;

    /// Closing the third panel of a chain of openings puts the keyboard back at its place in the second, closing the
    /// second brings the first back, and a file opened with the keyboard in the sidebar's session list returns it to
    /// that list.
    @Test
    void closingWhatWasOpenedGivesTheKeyboardBackToWhatOpenedItAndOpeningsChain() throws Exception {
        final Path work = Files.createDirectories(home.resolve("work"));
        for (final String n : new String[]{"a.txt", "b.txt", "c.txt", "d.txt"}) {
            Files.writeString(work.resolve(n), n + "\n");
        }
        final App app = new App(new AppConfig(home.resolve("data"), home.resolve("no-claude").toString(), home.resolve("projects"), false),
                "focus", () -> {
        });
        try {
            app.start();
            final StateLoop loop = app.loop();
            Awaitility.await("the window").atMost(Duration.ofSeconds(20)).until(() -> loop.state().focused() != null && frame() != null);

            final PanelId a = open(loop, work.resolve("a.txt"));
            keyboardTo(editorOf(a));
            final PanelId b = open(loop, work.resolve("b.txt"));
            keyboardTo(editorOf(b)); // the double click in B that opens C puts the keyboard in B first
            final PanelId c = open(loop, work.resolve("c.txt"));
            keyboardTo(editorOf(c));

            loop.dispatch(new Intent.ClosePanel(c));
            Awaitility.await("B in front, the keyboard in B").atMost(WAIT)
                    .until(() -> loop.state().focused().equals(b) && keyboardIn(b));
            loop.dispatch(new Intent.ClosePanel(b));
            Awaitility.await("A in front, the keyboard in A").atMost(WAIT)
                    .until(() -> loop.state().focused().equals(a) && keyboardIn(a));

            // the keyboard in the sidebar's session list, outside every panel: closing the file gives it back there
            final Component list = onEdt(() -> find(frame(), JList.class, l -> "Sessions".equals(l.getAccessibleContext().getAccessibleName())));
            assertThat(list).isNotNull();
            keyboardTo(list);
            final PanelId d = open(loop, work.resolve("d.txt"));
            keyboardTo(editorOf(d));
            loop.dispatch(new Intent.ClosePanel(d));
            Awaitility.await("the keyboard back in the list").atMost(WAIT).until(() -> onEdt(() -> frame().getMostRecentFocusOwner()) == list);
        } finally {
            app.close();
        }
    }

    /// A file opened again from a second panel comes forward and, closed, returns to that second panel; with the review
    /// panel in front and the keyboard left behind in main, the opening counts as the review panel's.
    @Test
    void aFileAlreadyOpenFromAnotherPanelGoesBackToTheOneThatOpenedItLastAndAKeyboardLeftBehindCountsAsTheFront() throws Exception {
        final Path work = Files.createDirectories(home.resolve("work"));
        for (final String n : new String[]{"main.txt", "review.txt", "report.txt", "other.txt"}) {
            Files.writeString(work.resolve(n), n + "\n");
        }
        final App app = new App(new AppConfig(home.resolve("data"), home.resolve("no-claude").toString(), home.resolve("projects"), false),
                "focus", () -> {
        });
        try {
            app.start();
            final StateLoop loop = app.loop();
            Awaitility.await("the window").atMost(Duration.ofSeconds(20)).until(() -> loop.state().focused() != null && frame() != null);
            final PanelId main = open(loop, work.resolve("main.txt"));
            keyboardTo(editorOf(main));
            final PanelId report = open(loop, work.resolve("report.txt")); // first opened from main
            final PanelId review = open(loop, work.resolve("review.txt"));
            keyboardTo(editorOf(review));

            // opened again from the review panel: it is already open, and comes forward
            loop.dispatch(new Intent.OpenFile("", work.resolve("report.txt").toString(), 0));
            Awaitility.await("the report in front").atMost(WAIT).until(() -> loop.state().focused().equals(report));
            loop.dispatch(new Intent.ClosePanel(report));
            Awaitility.await("back to the review panel, not main").atMost(WAIT)
                    .until(() -> loop.state().focused().equals(review) && keyboardIn(review));

            // the review panel in front, the keyboard left behind in main: the opening counts as the review panel's
            keyboardTo(editorOf(review));
            loop.dispatch(new Intent.FocusPanel(main));
            Awaitility.await("main in front").atMost(WAIT).until(() -> loop.state().focused().equals(main));
            loop.dispatch(new Intent.FocusPanel(review));
            Awaitility.await("review in front").atMost(WAIT).until(() -> loop.state().focused().equals(review));
            final PanelId other = open(loop, work.resolve("other.txt"));
            loop.dispatch(new Intent.ClosePanel(other));
            Awaitility.await("back to the review panel").atMost(WAIT).until(() -> loop.state().focused().equals(review));
        } finally {
            app.close();
        }
    }

    /// A document double-clicked in a session's Session tab, closed, brings that session's own panel forward with the
    /// Session tab selected in the lower panel.
    @Test
    void aFileOpenedFromTheSessionTabGoesBackToThatSessionsPanelWithItsSessionTabInFront() throws Exception {
        final Path work = Files.createDirectories(home.resolve("work"));
        Files.writeString(work.resolve("report.md"), "# Report\n");
        final App app = new App(new AppConfig(home.resolve("data"), home.resolve("no-claude").toString(), home.resolve("projects"), false),
                "focus", () -> {
        });
        try {
            app.start();
            final StateLoop loop = app.loop();
            Awaitility.await("the window").atMost(Duration.ofSeconds(20)).until(() -> loop.state().focused() != null && frame() != null);
            // a session comes with its own panel (record 88): reviews, then main at its right
            loop.dispatch(new Intent.SaveSession("", SessionSpec.fresh("reviews", work.toString())));
            loop.dispatch(new Intent.SaveSession("", SessionSpec.fresh("main", work.toString())));
            Awaitility.await("both panels").atMost(WAIT).until(() -> panelOf(loop, "reviews") != null && panelOf(loop, "main") != null);
            final PanelId reviews = panelOf(loop, "reviews");
            app.transcripts().of("reviews").append(new Block.AssistantText("m1", 0, "See report.md for the findings.", false, ""));
            app.transcripts().of("reviews").changed();

            loop.dispatch(new Intent.FocusPanel(reviews));
            loop.dispatch(new Intent.SelectLowerTab(LowerTab.SESSION));
            Awaitility.await("reviews in front").atMost(WAIT).until(() -> loop.state().focused().equals(reviews));
            final Component canvas = await(TranscriptCanvas.class);
            keyboardTo(canvas);

            // the double click on the document in the Session tab
            loop.dispatch(new Intent.OpenFile("reviews", "report.md", 0));
            Awaitility.await("the report in front").atMost(WAIT).until(() -> loop.state().focusedPanel().title().equals("report.md"));
            final PanelId report = loop.state().focused();
            loop.dispatch(new Intent.ClosePanel(report));

            Awaitility.await("the reviews panel in front, its Session tab with the keyboard").atMost(WAIT)
                    .until(() -> loop.state().focused().equals(reviews) && loop.state().lowerTab().equals(LowerTab.SESSION)
                            && onEdt(() -> frame().getMostRecentFocusOwner()) == canvas);
        } finally {
            app.close();
        }
    }

    /// Closing returns to the panel the user came from last, and closing a file opened from main while the user is in
    /// another panel changes neither the front panel nor the keyboard.
    @Test
    void closingReturnsToWhereTheUserCameFromLastAndABackgroundCloseMovesNothing() throws Exception {
        final Path work = Files.createDirectories(home.resolve("work"));
        for (final String n : new String[]{"review.txt", "main.txt", "f.txt", "g.txt"}) {
            Files.writeString(work.resolve(n), n + "\n");
        }
        final App app = new App(new AppConfig(home.resolve("data"), home.resolve("no-claude").toString(), home.resolve("projects"), false),
                "focus", () -> {
        });
        try {
            app.start();
            final StateLoop loop = app.loop();
            Awaitility.await("the window").atMost(Duration.ofSeconds(20)).until(() -> loop.state().focused() != null && frame() != null);
            final PanelId review = open(loop, work.resolve("review.txt"));
            final PanelId main = open(loop, work.resolve("main.txt"));
            loop.dispatch(new Intent.FocusPanel(review));
            Awaitility.await("review in front").atMost(WAIT).until(() -> loop.state().focused().equals(review));
            keyboardTo(editorOf(review));

            final PanelId f = open(loop, work.resolve("f.txt")); // opened from review
            loop.dispatch(new Intent.FocusPanel(main)); // a click on main
            Awaitility.await("main in front").atMost(WAIT).until(() -> loop.state().focused().equals(main));
            keyboardTo(editorOf(main));
            loop.dispatch(new Intent.FocusPanel(f)); // and back to F
            Awaitility.await("F in front").atMost(WAIT).until(() -> loop.state().focused().equals(f));
            keyboardTo(editorOf(f));
            loop.dispatch(new Intent.ClosePanel(f));
            Awaitility.await("back to main, where the user came from").atMost(WAIT)
                    .until(() -> loop.state().focused().equals(main) && keyboardIn(main));

            // G opened from main, then the user goes to review; closing G behind them moves nothing
            final PanelId g = open(loop, work.resolve("g.txt"));
            loop.dispatch(new Intent.FocusPanel(review));
            Awaitility.await("review in front").atMost(WAIT).until(() -> loop.state().focused().equals(review));
            keyboardTo(editorOf(review));
            loop.dispatch(new Intent.ClosePanel(g));
            Awaitility.await("G gone").atMost(WAIT).until(() -> loop.state().panel(g).isEmpty());
            Awaitility.await("still at review").during(Duration.ofMillis(800)).atMost(Duration.ofSeconds(3))
                    .until(() -> loop.state().focused().equals(review) && keyboardIn(review));
        } finally {
            app.close();
        }
    }

    private static PanelId panelOf(final StateLoop loop, final String session) {
        return loop.state().panels().stream().filter(p -> p.session().equals(session)).map(PanelState::id).findFirst().orElse(null);
    }

    private static <T extends Component> T await(final Class<T> type) {
        final AtomicReference<T> out = new AtomicReference<>();
        Awaitility.await(type.getSimpleName()).atMost(WAIT).until(() -> {
            out.set(onEdt(() -> find(frame(), type, c -> true)));
            return out.get() != null;
        });
        return out.get();
    }

    /** Opens {@code file} as a file link without a line opens it, and waits for its panel in front. */
    private static PanelId open(final StateLoop loop, final Path file) {
        loop.dispatch(new Intent.OpenFile("", file.toString(), 0));
        final String title = file.getFileName().toString();
        Awaitility.await(title + " in front").atMost(WAIT).until(() -> loop.state().focusedPanel().title().equals(title));
        return loop.state().panels().stream().filter(p -> p.title().equals(title)).map(PanelState::id).findFirst().orElseThrow();
    }

    private static Component editorOf(final PanelId id) throws Exception {
        final AtomicReference<Component> out = new AtomicReference<>();
        Awaitility.await("panel " + id.value() + " shows").atMost(WAIT).until(() -> {
            out.set(onEdt(() -> {
                final EditorPanel p = panelOf(frame(), id);
                return p == null || !p.isShowing() ? null : p.editor().area();
            }));
            return out.get() != null;
        });
        return out.get();
    }

    private static void keyboardTo(final Component c) throws Exception {
        Awaitility.await("the keyboard taken").atMost(WAIT).until(() -> onEdt(() -> {
            c.requestFocusInWindow();
            return frame().getMostRecentFocusOwner() == c;
        }));
    }

    private static boolean keyboardIn(final PanelId id) throws Exception {
        return onEdt(() -> {
            final Component owner = frame().getMostRecentFocusOwner();
            final EditorPanel p = owner == null ? null : (EditorPanel) SwingUtilities.getAncestorOfClass(EditorPanel.class, owner);
            return p != null && p.panel().id().equals(id) && owner == p.editor().area();
        });
    }

    private static JFrame frame() {
        for (final Frame f : Frame.getFrames()) {
            if (f instanceof JFrame j && f.isDisplayable() && f.getTitle().startsWith("comando")) {
                return j;
            }
        }
        return null;
    }

    private static EditorPanel panelOf(final Component c, final PanelId id) {
        if (c instanceof EditorPanel p && p.panel().id().equals(id)) {
            return p;
        }
        if (c instanceof Container k) {
            for (final Component child : k.getComponents()) {
                final EditorPanel found = panelOf(child, id);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static <T extends Component> T find(final Component c, final Class<T> type, final Predicate<T> which) {
        if (type.isInstance(c) && c.isShowing() && which.test(type.cast(c))) {
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

    private interface EdtCall<T> {
        T call() throws Exception;
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
}
