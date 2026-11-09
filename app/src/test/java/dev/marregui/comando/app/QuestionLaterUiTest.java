package dev.marregui.comando.app;

import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import javax.swing.AbstractButton;
import javax.swing.JRadioButton;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.model.SessionSpec;

import static org.assertj.core.api.Assertions.assertThat;

/// A decision held with options, as a question with no answer in time leaves it (record 142), in the real window.
/// Opens the window, so this runs in `./gradlew :app:uiTest`.
/// - its card in the Later group shows one radio button per option, in order, and an Other answer line
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class QuestionLaterUiTest {

    @TempDir
    Path home;

    /// A decision `work` held with three options shows them as radio buttons on its card, none chosen, with an Other
    /// answer line beside them, as the question card it came from offers.
    @Test
    void aDecisionHeldWithOptionsShowsItsTogglesAndAnOtherAnswer() throws Exception {
        final AppConfig config = new AppConfig(home.resolve("data"), home.resolve("no-claude").toString(),
                home.resolve("projects"), false);
        try (final App app = new App(config, "question-later", () -> {
        })) {
            app.start();
            final StateLoop loop = app.loop();
            Awaitility.await("the window").atMost(Duration.ofSeconds(20))
                    .until(() -> loop.state().focused() != null && onEdt(() -> first(Frame.class, f -> true)) != null);
            loop.dispatch(new Intent.SaveSession("", SessionSpec.fresh("work", home.toString())));
            Awaitility.await("the session").atMost(Duration.ofSeconds(10))
                    .until(() -> loop.state().session("work").isPresent());
            loop.dispatch(new Intent.DecisionHeldBack("work", "Which theme?", "Asked: the theme of the new view",
                    List.of("Graphite", "Gold", "Both")));
            Awaitility.await("held").atMost(Duration.ofSeconds(10)).until(() -> !loop.state().later().isEmpty());
            final String name = "decision-" + loop.state().later().getFirst().id();
            Awaitility.await("its row").atMost(Duration.ofSeconds(10))
                    .until(() -> onEdt(() -> first(Component.class, c -> name.equals(c.getName()))) != null);
            final List<String> options = new ArrayList<>();
            final List<Boolean> chosen = new ArrayList<>();
            final List<JTextComponent> typed = new ArrayList<>();
            SwingUtilities.invokeAndWait(() -> {
                final Component row = first(Component.class, c -> name.equals(c.getName()));
                final List<AbstractButton> buttons = new ArrayList<>();
                collect(row, AbstractButton.class, b -> "option".equals(b.getName()), buttons);
                for (final AbstractButton b : buttons) {
                    assertThat(b).isInstanceOf(JRadioButton.class);
                    options.add(b.getText());
                    chosen.add(b.isSelected());
                }
                collect(row, JTextComponent.class, t -> "answer".equals(t.getName()), typed);
            });
            assertThat(options).containsExactly("Graphite", "Gold", "Both");
            assertThat(chosen).containsOnly(false);
            assertThat(typed).as("an Other answer beside options").hasSize(1);
        }
    }

    private static <T extends Component> T first(final Class<T> type, final Predicate<T> which) {
        final List<T> out = new ArrayList<>();
        for (final Frame f : Frame.getFrames()) {
            if (f.isVisible()) {
                collect(f, type, which, out);
            }
        }
        return out.isEmpty() ? null : out.getFirst();
    }

    private static <T extends Component> void collect(
            final Component c,
            final Class<T> type,
            final Predicate<T> which,
            final List<T> out
    ) {
        if (type.isInstance(c) && which.test(type.cast(c))) {
            out.add(type.cast(c));
        }
        if (c instanceof Container k) {
            for (final Component child : k.getComponents()) {
                collect(child, type, which, out);
            }
        }
    }

    private static <T> T onEdt(final EdtCall<T> call) throws Exception {
        final AtomicReference<T> out = new AtomicReference<>();
        final AtomicReference<Exception> failed = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                out.set(call.call());
            } catch (final Exception e) {
                failed.set(e);
            }
        });
        if (failed.get() != null) {
            throw failed.get();
        }
        return out.get();
    }

    @FunctionalInterface
    private interface EdtCall<T> {
        T call() throws Exception;
    }
}
