package dev.marregui.comando.app;

import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import javax.swing.JTextArea;
import javax.swing.JViewport;
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

import static org.assertj.core.api.Assertions.assertThat;

/// The keys of the comparison window, the window a changed file of the Changes view opens in, in the real window.
/// Opens the window, so this runs in `./gradlew :app:uiTest`.
/// - F7 goes to the next difference and ⇧F7 to the one before, as the tooltips of its arrows say
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class CompareKeysUiTest {

    private static final Duration WAIT = Duration.ofSeconds(10);
    /** Lines of the file compared: long enough that its two differences are far apart in the window. */
    private static final int LINES = 400;

    @TempDir
    Path home;

    /// A file changed at two places far apart opens at the first: F7 scrolls the window further down to the second, ⇧F7
    /// back up to the first.
    @Test
    void f7AndShiftF7WalkTheDifferencesOfAComparison() throws Exception {
        try (final App app = new App(new AppConfig(home.resolve("data"), home.resolve("no-claude").toString(),
                home.resolve("projects"), false), "compare-keys", () -> {
        })) {
            app.start();
            final StateLoop loop = app.loop();
            Awaitility.await("the window").atMost(Duration.ofSeconds(20)).until(() -> loop.state().focused() != null);
            loop.dispatch(new Intent.ChangeRead(texts(), ChangeAsk.OPEN));
            final JTextArea right = await(JTextArea.class, a -> a.getText().contains("changed at 300"));
            final JViewport view = onEdt(() -> (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, right));
            // a comparison opens scrolled to its first difference
            Awaitility.await("at the first difference").atMost(WAIT).until(() -> onEdt(() -> top(view) > 0));
            final int first = onEdt(() -> top(view));

            press(right, 0);
            Awaitility.await("at the second, further down").atMost(WAIT).until(() -> onEdt(() -> top(view) > first));
            press(right, KeyEvent.SHIFT_DOWN_MASK);
            Awaitility.await("back at the first").atMost(WAIT).until(() -> onEdt(() -> top(view) == first));
            assertThat(onEdt(() -> top(view))).as("⇧F7 lands where F7 first did").isEqualTo(first);
        }
    }

    private ChangeTexts texts() {
        final StringBuilder head = new StringBuilder();
        final StringBuilder tree = new StringBuilder();
        for (int i = 1; i <= LINES; i++) {
            head.append("line ").append(i).append('\n');
            tree.append(i == 100 || i == 300 ? "changed at " + i : "line " + i).append('\n');
        }
        final RepoStatus.FileChange change = new RepoStatus.FileChange("long.txt", "", RepoStatus.Kind.MODIFIED);
        return new ChangeTexts(home.toString(), change, head.toString(), tree.toString(), "");
    }

    private static int top(final JViewport view) {
        return view.getViewPosition().y;
    }

    private static void press(final Component target, final int modifiers) throws Exception {
        onEdt(() -> {
            final long now = System.currentTimeMillis();
            target.dispatchEvent(new KeyEvent(target, KeyEvent.KEY_PRESSED, now, modifiers, KeyEvent.VK_F7,
                    KeyEvent.CHAR_UNDEFINED));
            target.dispatchEvent(new KeyEvent(target, KeyEvent.KEY_RELEASED, now, modifiers, KeyEvent.VK_F7,
                    KeyEvent.CHAR_UNDEFINED));
            return null;
        });
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
        if (c instanceof final Container k) {
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
