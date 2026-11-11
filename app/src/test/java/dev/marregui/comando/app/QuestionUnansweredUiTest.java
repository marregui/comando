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
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.ApprovalKind;
import dev.marregui.comando.core.model.SessionSpec;

import static org.assertj.core.api.Assertions.assertThat;

/// A question card nobody answers before the approval timeout, in the real window. Opens the window, so this runs in
/// `./gradlew :app:uiTest`.
/// - while it waits, its card is in the Approvals tab and the tab's title counts it
/// - withdrawn at the deadline, as the MCP server does, its card goes, nothing of it is kept and the title has no
///   later count
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class QuestionUnansweredUiTest {

    private static final String QUESTIONS = "{\"questions\":[{\"question\":\"Which theme?\",\"header\":\"Theme\","
            + "\"options\":[{\"label\":\"Graphite\"},{\"label\":\"Gold\"}],\"multiSelect\":false}]}";

    @TempDir
    Path home;

    /// A question of `work` shows its card and `Approvals (1)`; withdrawn with no answer, the card leaves, the inbox
    /// holds nothing and the tab reads `Approvals`.
    @Test
    void anUnansweredQuestionLeavesNothingBehindAtItsDeadline() throws Exception {
        final AppConfig config = new AppConfig(home.resolve("data"), home.resolve("no-claude").toString(),
                home.resolve("projects"), false);
        try (final App app = new App(config, "question-unanswered", () -> {
        })) {
            app.start();
            final StateLoop loop = app.loop();
            Awaitility.await("the window").atMost(Duration.ofSeconds(20))
                    .until(() -> loop.state().focused() != null && onEdt(() -> first(Frame.class, f -> true)) != null);
            loop.dispatch(new Intent.SaveSession("", SessionSpec.fresh("work", home.toString())));
            Awaitility.await("the session").atMost(Duration.ofSeconds(10))
                    .until(() -> loop.state().session("work").isPresent());
            loop.dispatch(new Intent.ApprovalRequested(new Approval(41, "work", ApprovalKind.QUESTION,
                    "AskUserQuestion", QUESTIONS, "Which theme?", System.currentTimeMillis())));
            Awaitility.await("its card").atMost(Duration.ofSeconds(10))
                    .until(() -> onEdt(() -> first(Component.class, c -> "approval-41".equals(c.getName()))) != null);
            assertThat(onEdt(QuestionUnansweredUiTest::approvalsTitle)).isEqualTo("Approvals (1)");

            loop.dispatch(new Intent.ApprovalWithdrawn(41, "no answer within 24 h"));
            Awaitility.await("its card gone").atMost(Duration.ofSeconds(10))
                    .until(() -> onEdt(() -> first(Component.class, c -> "approval-41".equals(c.getName()))) == null);
            assertThat(loop.state().approvals()).as("nothing kept").isEmpty();
            assertThat(onEdt(QuestionUnansweredUiTest::approvalsTitle)).as("no later count").isEqualTo("Approvals");
            final Predicate<Component> anyCard = c -> c.getName() != null && c.getName().startsWith("approval-");
            assertThat(onEdt(() -> first(Component.class, anyCard))).as("no card in its place").isNull();
        }
    }

    /// The Approvals tab's title in the lower panel's tabs.
    private static String approvalsTitle() {
        final List<JTabbedPane> panes = new ArrayList<>();
        for (final Frame f : Frame.getFrames()) {
            if (f.isVisible()) {
                collect(f, JTabbedPane.class, t -> true, panes);
            }
        }
        for (final JTabbedPane t : panes) {
            for (int i = 0; i < t.getTabCount(); i++) {
                if (t.getTitleAt(i).startsWith("Approvals")) {
                    return t.getTitleAt(i);
                }
            }
        }
        return null;
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
