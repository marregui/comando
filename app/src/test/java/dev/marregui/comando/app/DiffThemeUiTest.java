package dev.marregui.comando.app;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import javax.swing.AbstractButton;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.model.ChangeAsk;
import dev.marregui.comando.core.model.ChangeTexts;
import dev.marregui.comando.core.model.RepoStatus;
import dev.marregui.comando.ui.kit.theme.Ground;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.theme.Tint;

import static org.assertj.core.api.Assertions.assertThat;

/// The comparison window on the theme, in the real window. Opens the window, so this runs in `./gradlew :app:uiTest`.
/// - a theme chosen before its first open, as the saved one is at startup, is the one it opens on, texts and pages alike
/// - a theme chosen with it open reaches it too
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class DiffThemeUiTest {

    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final int MIDDLE = 128;

    @TempDir
    Path home;

    /// On a light theme chosen after startup the first comparison opens pale, its text and its rendered sides; a dark
    /// theme chosen with it open turns them all dark.
    @Test
    void theComparisonOpensOnTheThemeChosenAndFollowsTheNext() throws Exception {
        final App app = new App(new AppConfig(home.resolve("data"), home.resolve("no-claude").toString(),
                home.resolve("projects"), false), "diff-theme", () -> {
        });
        try {
            app.start();
            final StateLoop loop = app.loop();
            Awaitility.await("the window").atMost(Duration.ofSeconds(20)).until(() -> loop.state().focused() != null);
            choose(Ground.FOG, Tint.SLATE_INK);
            loop.dispatch(new Intent.ChangeRead(texts(), ChangeAsk.OPEN));

            final JDialog dialog = await(JDialog.class, d -> d.getTitle().contains("compared with HEAD"));
            final JTextArea right = await(JTextArea.class, a -> a.getText().contains("gained"));
            assertThat(onEdt(() -> bright(dialog.getContentPane().getBackground()))).as("its ground").isTrue();
            assertThat(onEdt(() -> bright(right.getBackground()))).as("its text's paper").isTrue();

            final AbstractButton rendered = await(AbstractButton.class,
                    b -> "Show rendered".equals(b.getAccessibleContext().getAccessibleName()));
            SwingUtilities.invokeAndWait(rendered::doClick);
            final JEditorPane page = await(JEditorPane.class, p -> p.getText().contains("gained"));
            assertThat(onEdt(() -> bright(page.getBackground()))).as("a rendered side's paper").isTrue();

            choose(Ground.GRAPHITE, Tint.TREASURE);
            Awaitility.await("dark with it open").atMost(WAIT).until(() -> onEdt(
                    () -> !bright(dialog.getContentPane().getBackground()) && !bright(page.getBackground())));
        } finally {
            choose(Ground.GRAPHITE, Tint.TREASURE);
            app.close();
        }
    }

    private static boolean bright(final Color c) {
        return (c.getRed() + c.getGreen() + c.getBlue()) / 3 > MIDDLE;
    }

    private static void choose(final Ground ground, final Tint tint) throws Exception {
        SwingUtilities.invokeAndWait(() -> Theme.choose(ground, tint));
    }

    private ChangeTexts texts() {
        final RepoStatus.FileChange change = new RepoStatus.FileChange("notes.md", "", RepoStatus.Kind.MODIFIED);
        return new ChangeTexts(home.toString(), change, "# Notes\n\nKept.\n", "# Notes\n\nKept.\n\ngained\n", "");
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
