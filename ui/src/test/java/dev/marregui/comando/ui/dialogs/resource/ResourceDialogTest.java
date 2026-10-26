package dev.marregui.comando.ui.dialogs.resource;

import com.formdev.flatlaf.FlatDarkLaf;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.resource.ConnectionKind;
import dev.marregui.comando.core.resource.SessionKind;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.ConnectionState;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.testkit.RecordingSink;
import dev.marregui.comando.testkit.States;
import dev.marregui.comando.ui.kit.desktop.Platform;
import dev.marregui.comando.ui.kit.theme.Theme;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.swing.AbstractButton;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JRootPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.LookAndFeel;
import javax.swing.UIManager;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceDialogTest {

    private static LookAndFeel previous;

    @BeforeAll
    static void flatLaf() throws Exception {
        previous = UIManager.getLookAndFeel();
        UIManager.setLookAndFeel(new FlatDarkLaf()); // the application's look: its sizes decide what fits
    }

    @AfterAll
    static void restore() throws Exception {
        UIManager.setLookAndFeel(previous);
    }

    @Test
    void aStartedSessionsNewIdReachesTheFormAndTheUsersEditStays() {
        Map<String, String> filled = Map.of("name", "edge", "effort", "", "sessionId", "A", "startMode", "FORK");
        Map<String, String> form = Map.of("name", "edge", "effort", "high", "sessionId", "A", "startMode", "FORK");
        Map<String, String> stored = Map.of("name", "edge", "effort", "", "sessionId", "B", "startMode", "RESUME");

        assertThat(ResourceDialog.merge(form, filled, stored))
                .containsEntry("effort", "high")
                .containsEntry("sessionId", "B")
                .containsEntry("startMode", "RESUME");
    }

    @Test
    void aFieldTheUserChangedWinsOverTheStoredChange() {
        Map<String, String> filled = Map.of("sessionId", "A");
        assertThat(ResourceDialog.merge(Map.of("sessionId", "C"), filled, Map.of("sessionId", "B"))).containsEntry("sessionId", "C");
    }

    // ---- layout

    @Test
    void everyButtonIsVisibleAtTheDefaultSize() {
        ResourceDialog<SessionSpec> sessions = sessions(new RecordingSink());
        sessions.prepare(States.twoPanels(), "work");
        assertEveryButtonVisible(sessions.content(), "Start", "Open its panel", "Save", "…");
        assertThat(all(sessions.content(), AbstractButton.class, b -> List.of("Add", "Clone", "Remove", "Import…").contains(b.getText())))
                .as("the sidebar adds, clones, removes and imports; the dialog edits one resource").isEmpty();
        assertThat(all(sessions.content(), JList.class, l -> true)).as("no list of resources").isEmpty();

        AppState imported = AppState.initial(List.of(new PanelState(States.P1, "one", "", "imp")), List.of(),
                List.of(SessionState.closed(SessionSpec.imported("imp", "/tmp", "c0ffee"))));
        ResourceDialog<SessionSpec> advanced = sessions(new RecordingSink());
        advanced.prepare(imported, "imp");
        assertEveryButtonVisible(advanced.content(), "Start", "Open its panel", "Save");

        ResourceDialog<ConnectionSpec> connections = connections(new RecordingSink());
        connections.prepare(States.twoPanels(), "local");
        assertEveryButtonVisible(connections.content(), "Test", "Connect", "Assign to panel", "Save");
    }

    // ---- keys

    @Test
    void enterSavesAndCommandEnterSavesAndAssigns() {
        ResourceDialog<SessionSpec> d = sessions(new RecordingSink());
        JRootPane root = new JRootPane();
        d.install(root);
        assertThat(root.getDefaultButton().getText()).isEqualTo("Save");
        assertThat(registered(root)).as("the look's own ⌃⏎ presses the default button; comando adds Esc and ⌘⏎")
                .containsExactlyInAnyOrder(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                        KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, Platform.menuShortcutMask()));
    }

    @Test
    void escapeWithUnsavedChangesAsksInTheDialog() {
        RecordingSink sink = new RecordingSink();
        ResourceDialog<SessionSpec> d = sessions(sink);
        JRootPane root = new JRootPane();
        d.install(root);
        d.prepare(States.twoPanels(), "work");
        d.escape();
        assertThat(confirmRow(d).isVisible()).isFalse();

        text(d, "name").setText("work 2");
        d.escape();
        assertThat(confirmRow(d).isVisible()).isTrue();
        assertThat(label(confirmRow(d)).getText()).isEqualTo("Discard changes to work?");
        assertThat(root.getDefaultButton().getText()).isEqualTo("Keep editing");

        click(d, "Keep editing", confirmRow(d));
        assertThat(confirmRow(d).isVisible()).isFalse();
        assertThat(text(d, "name").getText()).isEqualTo("work 2");
        assertThat(root.getDefaultButton().getText()).isEqualTo("Save");

        d.escape();
        click(d, "Discard", confirmRow(d));
        assertThat(text(d, "name").getText()).isEqualTo("work");
        assertThat(d.isDirty()).isFalse();
        assertThat(sink.intents()).isEmpty();
    }

    // ---- unsaved changes on every path that replaces the form

    private static AppState twoSessions() {
        return AppState.initial(List.of(new PanelState(States.P1, "one", "", "edge")), List.of(),
                List.of(SessionState.closed(SessionSpec.fresh("edge", "/tmp")), SessionState.closed(SessionSpec.fresh("edge2", "/var"))));
    }

    @Test
    void openingOnAnotherResourceANewOneOrACopyAsksFirstWhenTheFormHasUnsavedChanges() {
        RecordingSink sink = new RecordingSink();
        ResourceDialog<SessionSpec> d = sessions(sink);
        JRootPane root = new JRootPane();
        d.install(root);
        AppState s = twoSessions();
        d.prepare(s, "edge");
        combo(d, "model").setSelectedItem("opus");
        text(d, "budgetUsd").setText("2");

        d.prepare(s, "edge2");
        assertThat(label(confirmRow(d)).getText()).isEqualTo("Discard changes to edge?");
        assertThat(text(d, "name").getText()).isEqualTo("edge");
        click(d, "Keep editing", confirmRow(d));
        assertThat(text(d, "budgetUsd").getText()).isEqualTo("2");
        assertThat(combo(d, "model").getSelectedItem()).isEqualTo("opus");
        assertThat(d.isDirty()).isTrue();

        d.prepareNew(s);
        d.escape();
        assertThat(text(d, "name").getText()).as("Esc keeps editing too").isEqualTo("edge");
        assertThat(d.isDirty()).isTrue();

        d.prepareClone(s, "edge2");
        click(d, "Discard", confirmRow(d));
        assertThat(text(d, "name").getText()).isEqualTo("edge2 copy");
        assertThat(text(d, "workDir").getText()).isEqualTo("/var");
        assertThat(d.isDirty()).isFalse();

        text(d, "name").setText("mine");
        d.prepare(s, "edge2");
        assertThat(label(confirmRow(d)).getText()).isEqualTo("Discard changes to the new session?");
        click(d, "Discard", confirmRow(d));
        assertThat(text(d, "name").getText()).isEqualTo("edge2");
        assertThat(sink.intents()).isEmpty();
    }

    @Test
    void theCloseButtonAsksLikeEscSoAReopenedDialogHoldsNoUnsavedEdit() {
        RecordingSink sink = new RecordingSink();
        ResourceDialog<SessionSpec> d = sessions(sink);
        JRootPane root = new JRootPane();
        d.install(root);
        AppState s = twoSessions();
        d.prepare(s, "edge");
        text(d, "name").setText("mine");

        d.close();
        assertThat(label(confirmRow(d)).getText()).isEqualTo("Discard changes to edge?");
        click(d, "Discard", confirmRow(d));
        d.prepare(s, "edge");
        assertThat(text(d, "name").getText()).as("the stored name, not the edit").isEqualTo("edge");
        assertThat(d.isDirty()).isFalse();
        assertThat(sink.intents()).isEmpty();

        // a question open when the close button is clicked is answered with its safe button first
        text(d, "name").setText("mine");
        d.prepare(s, "edge2");
        d.close();
        assertThat(label(confirmRow(d)).getText()).isEqualTo("Discard changes to edge?");
        assertThat(sink.intents()).isEmpty();
    }

    @Test
    void newAndCloneOpenTheirOwnFormNotTheSelectedResource() {
        RecordingSink sink = new RecordingSink();
        ResourceDialog<SessionSpec> d = sessions(sink);
        AppState s = twoSessions();
        d.prepare(s, "edge");

        assertThat(d.title()).isEqualTo("Session edge");

        d.prepareNew(s);
        assertThat(text(d, "name").getText()).isEqualTo("new session");
        assertThat(d.title()).isEqualTo("New session");

        d.prepareClone(s, "edge2");
        assertThat(text(d, "name").getText()).isEqualTo("edge2 copy");
        assertThat(text(d, "workDir").getText()).isEqualTo("/var");
        assertThat(d.title()).as("a copy is new until saved").isEqualTo("New session");

        d.prepare(s, "");
        assertThat(text(d, "name").getText()).as("no name: a new one").isEqualTo("new session");
        assertThat(sink.intents()).isEmpty();
    }

    @Test
    void openingOnTheResourceBeingEditedKeepsTheEdits() {
        ResourceDialog<SessionSpec> d = sessions(new RecordingSink());
        AppState s = twoSessions();
        d.prepare(s, "edge");
        text(d, "budgetUsd").setText("2");
        d.prepare(s, "edge");
        assertThat(confirmRow(d).isVisible()).isFalse();
        assertThat(text(d, "budgetUsd").getText()).isEqualTo("2");
    }

    // ---- save, assign, open

    @Test
    void assignOnANewFormSavesItFirstAndSaysSo() {
        RecordingSink sink = new RecordingSink();
        ResourceDialog<SessionSpec> d = sessions(sink);
        d.prepareNew(States.twoPanels());
        text(d, "name").setText("fresh");
        click(d, "Open its panel");

        assertThat(sink.intents()).hasSize(2);
        assertThat(sink.intents().get(0)).isInstanceOfSatisfying(Intent.SaveSession.class, i -> {
            assertThat(i.previousName()).isEmpty();
            assertThat(i.spec().name()).isEqualTo("fresh");
        });
        assertThat(sink.intents().get(1)).isEqualTo(new Intent.BindSession(States.P1, "fresh"));
        assertThat(message(d).getText()).isEqualTo("Saved and opened fresh in its own panel");
    }

    @Test
    void assignOnAnInvalidFormSaysWhyAndDoesNothing() {
        RecordingSink sink = new RecordingSink();
        ResourceDialog<SessionSpec> d = sessions(sink);
        d.prepare(States.twoPanels(), "work");
        text(d, "budgetUsd").setText("abc");
        click(d, "Open its panel");
        assertThat(sink.intents()).isEmpty();
        assertThat(message(d).getText()).isEqualTo("budget is not a number: abc");
        assertThat(message(d).getForeground()).isEqualTo(Theme.DANGER);
    }

    @Test
    void assignOnAnUnchangedFormOnlyBinds() {
        RecordingSink sink = new RecordingSink();
        ResourceDialog<SessionSpec> d = sessions(sink);
        d.prepare(States.twoPanels(), "work");
        click(d, "Open its panel");
        assertThat(sink.intents()).containsExactly(new Intent.BindSession(States.P1, "work"));
        assertThat(message(d).getText()).isEqualTo("Opened work in its own panel");
    }

    @Test
    void testReadsTheUnsavedForm() {
        RecordingSink sink = new RecordingSink();
        ResourceDialog<ConnectionSpec> d = connections(sink);
        d.prepare(States.twoPanels(), "local");
        text(d, "host").setText("10.0.0.9");
        click(d, "Test");
        assertThat(sink.of(Intent.TestConnection.class)).singleElement().satisfies(i -> assertThat(i.spec().host()).isEqualTo("10.0.0.9"));
        assertThat(sink.of(Intent.SaveConnection.class)).isEmpty();
    }

    @Test
    void startWithUnsavedChangesSavesFirst() {
        RecordingSink sink = new RecordingSink();
        ResourceDialog<SessionSpec> d = sessions(sink);
        d.prepare(States.twoPanels(), "work");
        combo(d, "model").setSelectedItem("opus");
        click(d, "Start");
        assertThat(sink.intents()).hasSize(2);
        assertThat(sink.intents().get(0)).isInstanceOfSatisfying(Intent.SaveSession.class, i -> assertThat(i.spec().model()).isEqualTo("opus"));
        assertThat(sink.intents().get(1)).isEqualTo(new Intent.StartSession("work"));
        assertThat(message(d).getText()).isEqualTo("Saved and starting");
    }

    // ---- outcome of Test, Connect, Start

    @Test
    void theMessageShowsTheLatestNoticeAboutTheResourceAfterTheClick() {
        RecordingSink sink = new RecordingSink();
        ResourceDialog<ConnectionSpec> d = connections(sink);
        AppState s = States.twoPanels();
        d.prepare(s, "local");
        click(d, "Connect");
        long now = System.currentTimeMillis();
        String local = Notice.connection("local");

        Notice stale = new Notice(Notice.Level.ERROR, "Connection local failed: earlier", now - 600_000, local);
        d.render(s.withNotices(stale, List.of(stale)));
        assertThat(message(d).getText()).isEqualTo("Connecting local");

        Notice failed = new Notice(Notice.Level.ERROR, "Connection local failed: password authentication failed", now + 1_000, local);
        Notice other = new Notice(Notice.Level.INFO, "Test lab: reachable", now + 2_000, Notice.connection("lab"));
        d.render(s.withNotices(other, List.of(stale, failed, other)));
        assertThat(message(d).getText()).isEqualTo("Connection local failed: password authentication failed");
        assertThat(message(d).getForeground()).isEqualTo(Theme.DANGER);

        Notice tested = new Notice(Notice.Level.INFO, "Test local: reachable", now + 3_000, local);
        d.render(s.withNotices(tested, List.of(stale, failed, other, tested)));
        assertThat(message(d).getText()).isEqualTo("Test local: reachable");
        assertThat(message(d).getForeground()).isEqualTo(Theme.MUTED);
    }

    // ---- choices and advice

    @Test
    void onlyTheModelTakesTypedValuesAndNewOnAStoredConversationWarns() {
        AppState s = AppState.initial(List.of(new PanelState(States.P1, "one", "", "")), List.of(),
                List.of(SessionState.closed(SessionSpec.imported("imp", "/tmp", "c0ffee")), SessionState.closed(SessionSpec.fresh("n", "/tmp"))));
        ResourceDialog<SessionSpec> d = sessions(new RecordingSink());
        d.prepare(s, "n");
        assertThat(combo(d, "model").isEditable()).isTrue();
        assertThat(combo(d, "effort").isEditable()).isFalse();
        assertThat(combo(d, "permissionMode").isEditable()).isFalse();
        assertThat(combo(d, "startMode").isEditable()).isFalse();
        assertThat(isShownIn(combo(d, "startMode"), d.content())).isFalse();

        d.prepare(s, "imp");
        assertThat(isShownIn(combo(d, "startMode"), d.content())).isTrue();
        combo(d, "startMode").setSelectedItem("NEW");
        assertThat(message(d).getText()).isEqualTo("The stored conversation c0ffee will not be continued");
        combo(d, "startMode").setSelectedItem("RESUME");
        assertThat(message(d).getText()).isBlank();
    }

    // ---- new session directory

    @Test
    void aNewSessionStartsInTheFocusedPanelsSessionDirectoryElseTheLastUsedElseHome() {
        AppState bound = States.twoPanels();
        ResourceDialog<SessionSpec> d = sessions(new RecordingSink());
        d.prepareNew(bound);
        assertThat(text(d, "workDir").getText()).isEqualTo("/tmp");

        AppState unbound = AppState.initial(List.of(new PanelState(new PanelId("p9"), "nine", "", "")), List.of(),
                List.of(SessionState.closed(SessionSpec.fresh("work", "/tmp"))));
        assertThat(ResourceDialog.defaultDirectory(unbound, "")).isEqualTo(System.getProperty("user.home"));
        assertThat(ResourceDialog.defaultDirectory(unbound, "/srv/last")).isEqualTo("/srv/last");
        assertThat(ResourceDialog.defaultDirectory(bound, "/srv/last")).isEqualTo("/tmp");

        ResourceDialog<SessionSpec> e = sessions(new RecordingSink());
        e.prepareNew(unbound);
        text(e, "name").setText("x");
        text(e, "workDir").setText("/opt/proj");
        click(e, "Save");
        e.prepareNew(unbound);
        assertThat(text(e, "workDir").getText()).isEqualTo("/opt/proj");
    }

    // ---- helpers

    private static ResourceDialog<SessionSpec> sessions(RecordingSink sink) {
        return new ResourceDialog<>(null, new ResourceDialog.Config<>(
                SessionKind.INSTANCE,
                s -> s.sessions().stream().map(SessionState::spec).toList(),
                s -> {
                    Map<String, Status> m = new LinkedHashMap<>();
                    s.sessions().forEach(x -> m.put(x.name(), x.status()));
                    return m;
                },
                Intent.SaveSession::new,
                null,
                Intent.StartSession::new,
                Intent.StopSession::new,
                (s, name) -> new Intent.BindSession(s.focused(), name)), sink);
    }

    /** The keys bound with {@link JComponent#registerKeyboardAction}: the look binds action names, that call binds actions. */
    private static List<KeyStroke> registered(JRootPane root) {
        InputMap map = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        KeyStroke[] keys = map.keys();
        return keys == null ? List.of() : Stream.of(keys).filter(k -> !(map.get(k) instanceof String)).toList();
    }

    private static ResourceDialog<ConnectionSpec> connections(RecordingSink sink) {
        return new ResourceDialog<>(null, new ResourceDialog.Config<>(
                ConnectionKind.INSTANCE,
                s -> s.connections().stream().map(ConnectionState::spec).toList(),
                s -> {
                    Map<String, Status> m = new LinkedHashMap<>();
                    s.connections().forEach(x -> m.put(x.name(), x.status()));
                    return m;
                },
                Intent.SaveConnection::new,
                Intent.TestConnection::new,
                Intent.OpenConnection::new,
                Intent.CloseConnection::new,
                (s, name) -> new Intent.BindConnection(s.focused(), name)), sink);
    }

    private static void assertEveryButtonVisible(JComponent content, String... expected) {
        content.setSize(ResourceDialog.DEFAULT_SIZE);
        layout(content);
        List<AbstractButton> buttons = all(content, AbstractButton.class,
                b -> b.getText() != null && !b.getText().isEmpty() && isShownIn(b, content));
        assertThat(buttons).extracting(AbstractButton::getText).contains(expected);
        for (JLabel hint : all(content, JLabel.class, l -> l.getName() != null && l.getName().startsWith("hint:") && isShownIn(l, content))) {
            assertThat(hint.getFontMetrics(hint.getFont()).stringWidth(hint.getText())).as("width of hint %s", hint.getText())
                    .isLessThanOrEqualTo(hint.getWidth());
        }
        for (AbstractButton b : buttons) {
            Rectangle visible = b.getVisibleRect();
            assertThat(b.getWidth()).as("width of %s", b.getText()).isPositive();
            assertThat(visible.width).as("visible width of %s", b.getText()).isEqualTo(b.getWidth());
            assertThat(visible.height).as("visible height of %s", b.getText()).isEqualTo(b.getHeight());
        }
    }

    private static void layout(Container c) {
        c.doLayout();
        for (Component child : c.getComponents()) {
            if (child instanceof Container sub) {
                layout(sub);
            }
        }
    }

    /** Visible here and in every parent up to {@code top}. */
    private static boolean isShownIn(Component c, Component top) {
        for (Component x = c; x != null; x = x.getParent()) {
            if (!x.isVisible()) {
                return false;
            }
            if (x == top) {
                return true;
            }
        }
        return false;
    }

    private static <T extends Component> List<T> all(Container root, Class<T> type, Predicate<T> match) {
        List<T> out = new ArrayList<>();
        for (Component c : root.getComponents()) {
            if (type.isInstance(c) && match.test(type.cast(c))) {
                out.add(type.cast(c));
            }
            if (c instanceof Container sub) {
                out.addAll(all(sub, type, match));
            }
        }
        return out;
    }

    private static <T extends Component> T one(Container root, Class<T> type, Predicate<T> match) {
        List<T> found = all(root, type, match);
        assertThat(found).as("one %s", type.getSimpleName()).hasSize(1);
        return found.getFirst();
    }

    /** Clicks a button of the dialog outside the question row. */
    private static void click(ResourceDialog<?> d, String text) {
        Container row = confirmRow(d);
        one(d.content(), JButton.class, b -> text.equals(b.getText()) && !isInside(b, row)).doClick(0);
    }

    /** Clicks a button of the question row. */
    private static void click(ResourceDialog<?> d, String text, Container row) {
        one(row, JButton.class, b -> text.equals(b.getText())).doClick(0);
    }

    private static boolean isInside(Component c, Container parent) {
        for (Component x = c; x != null; x = x.getParent()) {
            if (x == parent) {
                return true;
            }
        }
        return false;
    }

    private static JTextField text(ResourceDialog<?> d, String key) {
        return one(d.content(), JTextField.class, t -> key.equals(t.getName()));
    }

    @SuppressWarnings("unchecked")
    private static JComboBox<String> combo(ResourceDialog<?> d, String key) {
        return one(d.content(), JComboBox.class, c -> key.equals(c.getName()));
    }

    private static JLabel message(ResourceDialog<?> d) {
        return one(d.content(), JLabel.class, l -> "message".equals(l.getName()));
    }

    private static Container confirmRow(ResourceDialog<?> d) {
        return one(d.content(), Container.class, c -> "confirm".equals(c.getName()));
    }

    @Test
    void theFolderChooserOpensOnTheFieldsFirstFolderOrHome() {
        String home = System.getProperty("user.home");
        assertThat(ResourceDialog.chooserStart("")).isEqualTo(Path.of(home));
        assertThat(ResourceDialog.chooserStart(File.pathSeparator)).as("separators only").isEqualTo(Path.of(home));
        assertThat(ResourceDialog.chooserStart(" " + File.pathSeparator + "/w/b")).isEqualTo(Path.of("/w/b"));
        assertThat(ResourceDialog.chooserStart("/w/a\0b")).as("no path").isEqualTo(Path.of(home));
    }

    private static JLabel label(Container row) {
        return all(row, JLabel.class, l -> true).getFirst();
    }
}
