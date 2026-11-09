package dev.marregui.comando.app;

import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import javax.swing.AbstractButton;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.model.Decision;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.state.LowerTab;

import static org.assertj.core.api.Assertions.assertThat;

/// The decisions automatic checkpoints held back (record 131), in the real window. Opens the window, so this runs in
/// `./gradlew :app:uiTest`.
/// - a decision held back joins the Approvals tab's Later group and the tab's title, and brings nothing forward
/// - it is kept in `decisions.json` and read back by the next start
/// - Dismiss in the Later group removes it, from the window and the file
/// - decisions of two sessions answered on their cards, one by an option and one by a typed line, go in one Send
///   answers, in the group's order, and leave the group
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class LaterDecisionsUiTest {

    @TempDir
    Path home;

    /// One decision held back for `work` shows as a row in the Later group with "Approvals (1 later)" as the tab's
    /// title while the Session tab stays in front; a second start of comando on the same data root lists it again; its
    /// Dismiss empties the group and the file.
    @Test
    void aDecisionHeldBackWaitsInTheLaterGroupAcrossARestartUntilDismissed() throws Exception {
        final Path data = home.resolve("data");
        final AppConfig config = new AppConfig(data, home.resolve("no-claude").toString(), home.resolve("projects"), false);
        try (final App app = new App(config, "later", () -> {
        })) {
            app.start();
            final StateLoop loop = app.loop();
            awaitWindow(loop);
            loop.dispatch(new Intent.SaveSession("", SessionSpec.fresh("work", home.toString())));
            Awaitility.await("the session").atMost(Duration.ofSeconds(10)).until(() -> loop.state().session("work").isPresent());
            loop.dispatch(new Intent.DecisionHeldBack("work", "Merge the two feedback entries?", "both say SVG only"));
            Awaitility.await("held").atMost(Duration.ofSeconds(10)).until(() -> !loop.state().later().isEmpty());
            // the id comes from the state's counter, never given twice (record 131)
            final String row = "decision-" + loop.state().later().getFirst().id();
            Awaitility.await("the Later row").atMost(Duration.ofSeconds(10))
                    .until(() -> onEdt(() -> first(Component.class, c -> row.equals(c.getName()))) != null);
            assertThat(onEdt(LaterDecisionsUiTest::approvalsTitle)).isEqualTo("Approvals (1 later)");
            assertThat(loop.state().lowerTab()).as("nothing comes forward").isEqualTo(LowerTab.SESSION);
            Awaitility.await("decisions.json").atMost(Duration.ofSeconds(10))
                    .until(() -> Files.exists(data.resolve("decisions.json"))
                            && Files.readString(data.resolve("decisions.json")).contains("Merge the two feedback entries?"));
        }

        try (final App app = new App(config, "later", () -> {
        })) {
            app.start();
            final StateLoop loop = app.loop();
            awaitWindow(loop);
            Awaitility.await("read back at start").atMost(Duration.ofSeconds(10))
                    .until(() -> loop.state().later().stream().map(Decision::title).toList().equals(List.of("Merge the two feedback entries?")));
            final String name = "decision-" + loop.state().later().getFirst().id();
            Awaitility.await("its row").atMost(Duration.ofSeconds(10))
                    .until(() -> onEdt(() -> first(Component.class, c -> name.equals(c.getName()))) != null);
            SwingUtilities.invokeAndWait(() -> {
                final Component row = first(Component.class, c -> name.equals(c.getName()));
                final List<AbstractButton> dismiss = new ArrayList<>();
                collect(row, AbstractButton.class, b -> "dismiss".equals(b.getName()), dismiss);
                dismiss.getFirst().doClick();
            });
            Awaitility.await("dismissed").atMost(Duration.ofSeconds(10)).until(() -> loop.state().later().isEmpty());
            Awaitility.await("the title").atMost(Duration.ofSeconds(10))
                    .until(() -> "Approvals".equals(onEdt(LaterDecisionsUiTest::approvalsTitle)));
            Awaitility.await("the file emptied").atMost(Duration.ofSeconds(10))
                    .until(() -> !Files.readString(data.resolve("decisions.json")).contains("Merge"));
        }
    }

    /// Two decisions of two sessions, `work`'s with options and held first, `docs`'s answered by a typed line: an
    /// option chosen and a line typed, Send answers dispatches one `DecisionsAnswered` holding both, `work`'s first,
    /// and the reducer takes both out of the Later group.
    @Test
    void decisionsOfTwoSessionsAreAnsweredInOneSend() throws Exception {
        final AppConfig config = new AppConfig(home.resolve("data"), home.resolve("no-claude").toString(),
                home.resolve("projects"), false);
        try (final App app = new App(config, "later", () -> {
        })) {
            app.start();
            final StateLoop loop = app.loop();
            awaitWindow(loop);
            final List<Intent.DecisionsAnswered> seen = new CopyOnWriteArrayList<>();
            loop.addObserver(i -> {
                if (i instanceof Intent.DecisionsAnswered a) {
                    seen.add(a);
                }
            });
            loop.dispatch(new Intent.SaveSession("", SessionSpec.fresh("work", home.toString())));
            loop.dispatch(new Intent.SaveSession("", SessionSpec.fresh("docs", home.toString())));
            Awaitility.await("the sessions").atMost(Duration.ofSeconds(10))
                    .until(() -> loop.state().session("work").isPresent() && loop.state().session("docs").isPresent());
            loop.dispatch(new Intent.DecisionHeldBack("work", "Merge the two feedback entries?", "",
                    List.of("Merge", "Keep both")));
            Awaitility.await("work's").atMost(Duration.ofSeconds(10)).until(() -> loop.state().later().size() == 1);
            loop.dispatch(new Intent.DecisionHeldBack("docs", "Name the release?", "the next one"));
            Awaitility.await("docs's").atMost(Duration.ofSeconds(10)).until(() -> loop.state().later().size() == 2);
            final long work = loop.state().later().getFirst().id();
            final long docs = loop.state().later().getLast().id();
            final String docsRow = "decision-" + docs;
            Awaitility.await("both rows").atMost(Duration.ofSeconds(10))
                    .until(() -> onEdt(() -> first(Component.class, c -> docsRow.equals(c.getName()))) != null);
            SwingUtilities.invokeAndWait(() -> {
                final Component workRow = first(Component.class, c -> ("decision-" + work).equals(c.getName()));
                final List<AbstractButton> options = new ArrayList<>();
                collect(workRow, AbstractButton.class, b -> "option".equals(b.getName()), options);
                options.getLast().doClick();
                final Component docsCard = first(Component.class, c -> docsRow.equals(c.getName()));
                final List<JTextField> typed = new ArrayList<>();
                collect(docsCard, JTextField.class, f -> "answer".equals(f.getName()), typed);
                typed.getFirst().setText("2026.16");
                first(AbstractButton.class, b -> "send-answers".equals(b.getName())).doClick();
            });
            Awaitility.await("sent").atMost(Duration.ofSeconds(10)).until(() -> !seen.isEmpty());
            final Map<Long, String> expected = new LinkedHashMap<>();
            expected.put(work, "Keep both");
            expected.put(docs, "2026.16");
            assertThat(seen).hasSize(1);
            assertThat(seen.getFirst().answers()).containsExactlyEntriesOf(expected);
            Awaitility.await("answered, out of the group").atMost(Duration.ofSeconds(10))
                    .until(() -> loop.state().later().isEmpty());
        }
    }

    private static void awaitWindow(final StateLoop loop) {
        Awaitility.await("the window").atMost(Duration.ofSeconds(20))
                .until(() -> loop.state().focused() != null && onEdt(LaterDecisionsUiTest::approvalsTitle) != null);
    }

    /** The lower panel's Approvals tab's title, or null before the window shows it. */
    private static String approvalsTitle() {
        final JTabbedPane tabs = first(JTabbedPane.class, t -> {
            for (int i = 0; i < t.getTabCount(); i++) {
                if (t.getTitleAt(i).startsWith("Approvals")) {
                    return true;
                }
            }
            return false;
        });
        if (tabs == null) {
            return null;
        }
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (tabs.getTitleAt(i).startsWith("Approvals")) {
                return tabs.getTitleAt(i);
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

    private static <T extends Component> void collect(final Component c, final Class<T> type, final Predicate<T> which, final List<T> out) {
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
