package dev.marregui.comando.ui.dialogs.resource;

import com.formdev.flatlaf.FlatDarkLaf;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.model.KitRoot;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.StartMode;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.resource.Attribute;
import dev.marregui.comando.core.resource.ConnectionKind;
import dev.marregui.comando.core.resource.SessionKind;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.ConnectionState;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.testkit.RecordingSink;
import dev.marregui.comando.testkit.States;
import dev.marregui.comando.ui.dialogs.sessions.StoredConversation;
import dev.marregui.comando.ui.kit.desktop.Platform;
import dev.marregui.comando.ui.kit.theme.Theme;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.AbstractButton;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JRootPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
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

/// [ResourceDialog] edits one session or connection, guarding unsaved changes on every path that replaces the form.
/// - A stored change reaches the form except in fields the user changed.
/// - Every button is visible at the default size; the dialog edits one resource, with no list of its own.
/// - Enter saves and ⌘⏎ saves and assigns; Escape, the close button and opening another form ask before discarding.
/// - New and clone open their own form; reopening the resource being edited keeps the edits.
/// - Assign and Start save an unsaved form first and say so; an invalid form says why; Test reads the unsaved form.
/// - The message shows the latest notice about the resource after the click.
/// - Only the model takes typed values; a new start on a stored conversation warns.
/// - A new session's folder and the folder chooser's start follow the focused panel, the last used, or home.
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

    /// [ResourceDialog#merge] keeps a field the user edited and takes a field the store changed while the form still
    /// holds what was filled, so a started session's new id and start mode arrive.
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

    /// When the user and the store both changed the same field, the user's value wins.
    @Test
    void aFieldTheUserChangedWinsOverTheStoredChange() {
        Map<String, String> filled = Map.of("sessionId", "A");
        assertThat(ResourceDialog.merge(Map.of("sessionId", "C"), filled, Map.of("sessionId", "B"))).containsEntry("sessionId", "C");
    }

    // ---- layout

    /// Under FlatLaf, a fresh session, an imported session and a connection show all their action buttons at the
    /// default size, and the dialog has no add, clone, remove or import buttons and no resource list.
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

    /// The default button is Save, and comando registers only Escape and the menu-shortcut Enter on the root pane.
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

    /// Escape on a clean form asks nothing; on a dirty one it shows `Discard changes to work?` with Keep editing as the
    /// default; Keep editing keeps the edit and restores Save, Discard restores the stored values and sends no intent.
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
                List.of(SessionState.closed(SessionSpec.fresh("edge", "/tmp")), SessionState.closed(SessionSpec.fresh("lane2", "/var"))));
    }

    /// With unsaved edits, opening another session, a new one or a copy asks first: Keep editing keeps the edits,
    /// Escape keeps editing too, Discard opens the copy, and the question names `the new session` for an unsaved new
    /// form.
    @Test
    void openingOnAnotherResourceANewOneOrACopyAsksFirstWhenTheFormHasUnsavedChanges() {
        RecordingSink sink = new RecordingSink();
        ResourceDialog<SessionSpec> d = sessions(sink);
        JRootPane root = new JRootPane();
        d.install(root);
        AppState s = twoSessions();
        d.prepare(s, "edge");
        combo(d, "model").setSelectedItem("opus");
        text(d, "checkpoint").setText("/compact");

        d.prepare(s, "lane2");
        assertThat(label(confirmRow(d)).getText()).isEqualTo("Discard changes to edge?");
        assertThat(text(d, "name").getText()).isEqualTo("edge");
        click(d, "Keep editing", confirmRow(d));
        assertThat(text(d, "checkpoint").getText()).isEqualTo("/compact");
        assertThat(combo(d, "model").getSelectedItem()).isEqualTo("opus");
        assertThat(d.isDirty()).isTrue();

        d.prepareNew(s);
        d.escape();
        assertThat(text(d, "name").getText()).as("Esc keeps editing too").isEqualTo("edge");
        assertThat(d.isDirty()).isTrue();

        d.prepareClone(s, "lane2");
        click(d, "Discard", confirmRow(d));
        assertThat(text(d, "name").getText()).isEqualTo("lane2 copy");
        assertThat(text(d, "workDir").getText()).isEqualTo("/var");
        assertThat(d.isDirty()).isFalse();

        text(d, "name").setText("mine");
        d.prepare(s, "lane2");
        assertThat(label(confirmRow(d)).getText()).isEqualTo("Discard changes to the new session?");
        click(d, "Discard", confirmRow(d));
        assertThat(text(d, "name").getText()).isEqualTo("lane2");
        assertThat(sink.intents()).isEmpty();
    }

    /// The close button asks like Escape, Discard leaves a reopened dialog with the stored name, and a question already
    /// open when close is clicked stays and sends nothing.
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
        d.prepare(s, "lane2");
        d.close();
        assertThat(label(confirmRow(d)).getText()).isEqualTo("Discard changes to edge?");
        assertThat(sink.intents()).isEmpty();
    }

    /// New opens `new session` titled New session, a clone is named `… copy` with the original's folder and is new
    /// until saved, and an empty name opens a new form, sending no intent.
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

        d.prepareClone(s, "lane2");
        assertThat(text(d, "name").getText()).isEqualTo("lane2 copy");
        assertThat(text(d, "workDir").getText()).isEqualTo("/var");
        assertThat(d.title()).as("a copy is new until saved").isEqualTo("New session");

        d.prepare(s, "");
        assertThat(text(d, "name").getText()).as("no name: a new one").isEqualTo("new session");
        assertThat(sink.intents()).isEmpty();
    }

    /// Preparing the same resource again asks nothing and keeps the edit.
    @Test
    void openingOnTheResourceBeingEditedKeepsTheEdits() {
        ResourceDialog<SessionSpec> d = sessions(new RecordingSink());
        AppState s = twoSessions();
        d.prepare(s, "edge");
        text(d, "checkpoint").setText("/compact");
        d.prepare(s, "edge");
        assertThat(confirmRow(d).isVisible()).isFalse();
        assertThat(text(d, "checkpoint").getText()).isEqualTo("/compact");
    }

    // ---- save, assign, open

    /// Open its panel on a new form sends SaveSession with no previous name, then BindSession to the focused panel, and
    /// says `Saved and opened …`.
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

    /// A session without a working directory sends nothing and shows the reason in the danger colour.
    @Test
    void assignOnAnInvalidFormSaysWhyAndDoesNothing() {
        RecordingSink sink = new RecordingSink();
        ResourceDialog<SessionSpec> d = sessions(sink);
        d.prepare(States.twoPanels(), "work");
        text(d, "workDir").setText("");
        click(d, "Open its panel");
        assertThat(sink.intents()).isEmpty();
        assertThat(message(d).getText()).isEqualTo("workDir is empty");
        assertThat(message(d).getForeground()).isEqualTo(Theme.DANGER);
    }

    /// An unchanged form sends only BindSession and says `Opened … in its own panel`.
    @Test
    void assignOnAnUnchangedFormOnlyBinds() {
        RecordingSink sink = new RecordingSink();
        ResourceDialog<SessionSpec> d = sessions(sink);
        d.prepare(States.twoPanels(), "work");
        click(d, "Open its panel");
        assertThat(sink.intents()).containsExactly(new Intent.BindSession(States.P1, "work"));
        assertThat(message(d).getText()).isEqualTo("Opened work in its own panel");
    }

    /// Test sends TestConnection with the typed host and does not save.
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

    /// Start on a dirty form sends SaveSession with the edit, then StartSession, and says `Saved and starting`.
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

    /// After Connect, a notice older than the click is ignored, a later error about this connection shows in the danger
    /// colour despite a newer notice about another, and a later info replaces it in the muted colour.
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

    /// Only the model combo is editable; the conversation shows for every session, and choosing a new one on an
    /// imported one says the next start begins a new conversation, choosing its own again clearing that.
    @Test
    void onlyTheModelTakesTypedValuesAndNewOnAStoredConversationWarns() {
        AppState s = AppState.initial(List.of(new PanelState(States.P1, "one", "", "")), List.of(),
                List.of(SessionState.closed(SessionSpec.imported("imp", "/tmp", "c0ffee")), SessionState.closed(SessionSpec.fresh("n", "/tmp"))));
        ResourceDialog<SessionSpec> d = sessions(new RecordingSink());
        d.prepare(s, "n");
        assertThat(combo(d, "model").isEditable()).isTrue();
        assertThat(combo(d, "effort").isEditable()).isFalse();
        assertThat(combo(d, "permissionMode").isEditable()).isFalse();
        assertThat(picker(d).isEditable()).isFalse();
        assertThat(isShownIn(picker(d), d.content())).isTrue();

        d.prepare(s, "imp");
        assertThat(isShownIn(picker(d), d.content())).isTrue();
        picker(d).setSelectedIndex(0);
        assertThat(message(d).getText()).isEqualTo("The next start begins a new conversation; the one before stays on disk");
        picker(d).setSelectedIndex(1);
        assertThat(message(d).getText()).isBlank();
    }

    // ---- new session directory

    /// A new session starts in the kit's folder, as the user's sessions do, with More folded (record 125). The form
    /// asks the name first; each empty choice shows the default it stands for, the kit's or Claude Code's model once a
    /// start reported it; a label with more to say carries the ⓘ and its text; the dialog says where every session
    /// runs, asks no budget, and an edit keeps a stored budget.
    @Test
    void aNewSessionStartsInTheKitsFolderAndTheFormSaysWhatItsDefaultsAre(@TempDir Path kit) {
        KitRoot before = KitRoot.current();
        KitRoot.use(KitRoot.plain(kit));
        SessionKind.INSTANCE.defaults(new SessionKind.Defaults("", "medium", "bypassPermissions"));
        SessionKind.INSTANCE.cliModel("claude-opus-5-5");
        try {
            RecordingSink sink = new RecordingSink();
            ResourceDialog<SessionSpec> d = sessions(sink);
            d.prepareNew(States.twoPanels());
            assertThat(text(d, "workDir").getText()).isEqualTo(kit.toString());
            assertThat(isShownIn(text(d, "workDir"), d.content())).as("More folded: the kit's folder needs no look").isFalse();
            assertThat(one(d.content(), JLabel.class, l -> "context".equals(l.getName())).getText()).startsWith("Runs on folder ")
                    .contains("instance ");
            List<String> order = SessionKind.INSTANCE.attributes().stream().map(Attribute::key).toList();
            assertThat(order.subList(0, 2)).as("the name first, then the conversation").containsExactly("name", "claudeSessionId");
            assertThat(shown(combo(d, "effort"))).isEqualTo("medium · the kit's default");
            assertThat(shown(combo(d, "permissionMode"))).isEqualTo("bypassPermissions · the kit's default: runs every tool unasked");
            assertThat(combo(d, "model").getClientProperty("JTextField.placeholderText")).as("an open choice shows it while empty")
                    .isEqualTo("claude-opus-5-5 · Claude Code's default");
            JLabel name = one(d.content(), JLabel.class, l -> "about:name".equals(l.getName()));
            assertThat(name.getIcon()).as("the ⓘ").isNotNull();
            JRootPane pane = new JRootPane();
            pane.getContentPane().add(d.content());
            pane.setSize(900, 700);
            layout(pane);
            Attribute about = SessionKind.INSTANCE.attributes().getFirst();
            d.explain(name, about);
            assertThat(d.explainer().shown()).as("a click on the ⓘ shows the card").isEqualTo("name");
            assertThat(one(pane.getLayeredPane(), JTextArea.class, t -> "tab-card-topic".equals(t.getName())).getText())
                    .startsWith("A session is one ongoing line of work");
            d.explain(name, about);
            assertThat(d.explainer().shown()).as("a second click closes it").isNull();
            assertThat(all(d.content(), JTextField.class, t -> "budgetUsd".equals(t.getName()))).as("no budget asked").isEmpty();

            AppState budgeted = States.twoPanels().withSessions(List.of(SessionState.closed(new SessionSpec("api", kit.toString(), "", "", "",
                    List.of(), 2.5, "", StartMode.NEW, SessionSpec.DEFAULT_CHECKPOINT, ""))));
            d.prepare(budgeted, "api");
            combo(d, "effort").setSelectedItem("high");
            click(d, "Save");
            assertThat(sink.of(Intent.SaveSession.class).getLast().spec().budgetUsd()).as("a stored budget is kept").isEqualTo(2.5);
        } finally {
            SessionKind.INSTANCE.defaults(SessionKind.Defaults.NONE);
            SessionKind.INSTANCE.cliModel("");
            KitRoot.use(before);
        }
    }

    /// Save closes the dialog once the form is saved, the ⓘ's card with it; a form Save refuses stays open, card and
    /// all, with the reason. (The tests run headless, without the window: the card is what shows the close.)
    @Test
    void saveClosesTheDialogOnceSavedAndKeepsARefusedFormOpen() {
        RecordingSink sink = new RecordingSink();
        ResourceDialog<SessionSpec> d = sessions(sink);
        JRootPane pane = new JRootPane();
        pane.getContentPane().add(d.content());
        pane.setSize(900, 700);
        layout(pane);
        d.prepare(States.twoPanels(), "work");
        JLabel name = one(d.content(), JLabel.class, l -> "about:name".equals(l.getName()));
        Attribute about = SessionKind.INSTANCE.attributes().getFirst();

        d.explain(name, about);
        String dir = text(d, "workDir").getText();
        text(d, "workDir").setText("");
        click(d, "Save");
        assertThat(sink.of(Intent.SaveSession.class)).as("refused: nothing saved").isEmpty();
        assertThat(d.explainer().shown()).as("a refused form stays open").isEqualTo("name");

        text(d, "workDir").setText(dir);
        click(d, "Save");
        assertThat(sink.of(Intent.SaveSession.class)).hasSize(1);
        assertThat(d.explainer().shown()).as("saved: the dialog closes, its card with it").isNull();
    }

    /// The conversation is picked, never typed (record 125, 2026-10-31): a new session offers a new conversation first,
    /// then the ones stored on disk by title, folder and age, without those another session holds; how it is reached
    /// shows only beside a stored one. Picking one copies it, as an import does, and runs the session in its folder,
    /// More open to show it; Continue it resumes it instead.
    @Test
    void aStoredConversationIsPickedByWhatItIsAndCopiedOrContinued() {
        RecordingSink sink = new RecordingSink();
        ResourceDialog<SessionSpec> d = sessions(sink);
        AppState s = States.twoPanels().withSessions(List.of(SessionState.closed(SessionSpec.imported("held", "/tmp", "held-1"))));
        d.prepareNew(s);
        long hour = System.currentTimeMillis() - 3_600_000L;
        d.offer(List.of(new StoredConversation("c0ffee-1", "/work/edge", "main", "Edge release notes", "", hour, 10),
                new StoredConversation("held-1", "/tmp", "", "Held elsewhere", "", hour, 10),
                new StoredConversation("beef-2", "/work/jfrq", "", "", "why is the GC busy", hour, 10)));
        assertThat(rows(d)).containsExactly(ConversationField.NEW_ONE,
                ConversationField.label(new StoredConversation("c0ffee-1", "/work/edge", "main", "Edge release notes", "", hour, 10), System.currentTimeMillis()),
                ConversationField.label(new StoredConversation("beef-2", "/work/jfrq", "", "", "why is the GC busy", hour, 10), System.currentTimeMillis()));
        assertThat(rows(d).get(1)).startsWith("Edge release notes · /work/edge · ");
        assertThat(rows(d).get(2)).as("no title: its last prompt").startsWith("why is the GC busy · ");
        assertThat(isShownIn(combo(d, "startMode"), d.content())).as("nothing to continue or copy").isFalse();

        text(d, "name").setText("notes");
        picker(d).setSelectedIndex(1);
        assertThat(combo(d, "startMode").getSelectedItem()).isEqualTo(ConversationField.COPY);
        assertThat(text(d, "workDir").getText()).as("its folder").isEqualTo("/work/edge");
        assertThat(isShownIn(text(d, "workDir"), d.content())).as("More open to show it").isTrue();
        click(d, "Save");
        SessionSpec copied = sink.of(Intent.SaveSession.class).getLast().spec();
        assertThat(copied.claudeSessionId()).isEqualTo("c0ffee-1");
        assertThat(copied.startMode()).isEqualTo(StartMode.FORK);
        assertThat(copied.workDir()).isEqualTo("/work/edge");

        d.prepareNew(s);
        d.offer(List.of(new StoredConversation("c0ffee-1", "/work/edge", "main", "Edge release notes", "", hour, 10)));
        text(d, "name").setText("notes 2");
        picker(d).setSelectedIndex(1);
        combo(d, "startMode").setSelectedItem(ConversationField.CONTINUE);
        click(d, "Save");
        assertThat(sink.of(Intent.SaveSession.class).getLast().spec().startMode()).isEqualTo(StartMode.RESUME);
    }

    /// A saved session lists its own conversation, by its title once the stored ones are read, selected with how its
    /// next start reaches it; a new conversation keeps its id, as NEW did, for the next start to replace.
    @Test
    void aSavedSessionShowsItsOwnConversationAndANewOneKeepsItsId() {
        RecordingSink sink = new RecordingSink();
        ResourceDialog<SessionSpec> d = sessions(sink);
        AppState s = States.twoPanels().withSessions(List.of(SessionState.closed(SessionSpec.imported("imp", "/tmp", "c0ffee"))));
        d.prepare(s, "imp");
        assertThat(String.valueOf(picker(d).getSelectedItem())).isEqualTo("Its own · stored conversation c0ffee");
        assertThat(combo(d, "startMode").getSelectedItem()).as("an import copies at its first start").isEqualTo(ConversationField.COPY);
        d.offer(List.of(new StoredConversation("c0ffee", "/tmp", "", "Edge release notes", "", System.currentTimeMillis(), 10)));
        assertThat(String.valueOf(picker(d).getSelectedItem())).startsWith("Its own · Edge release notes · ");

        picker(d).setSelectedIndex(0);
        assertThat(isShownIn(combo(d, "startMode"), d.content())).isFalse();
        click(d, "Save");
        SessionSpec saved = sink.of(Intent.SaveSession.class).getLast().spec();
        assertThat(saved.startMode()).isEqualTo(StartMode.NEW);
        assertThat(saved.claudeSessionId()).isEqualTo("c0ffee");
    }

    private static JComboBox<?> picker(ResourceDialog<?> d) {
        return one(d.content(), JComboBox.class, c -> "claudeSessionId".equals(c.getName()));
    }

    private static List<String> rows(ResourceDialog<?> d) {
        JComboBox<?> c = picker(d);
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < c.getItemCount(); i++) {
            rows.add(String.valueOf(c.getItemAt(i)));
        }
        return rows;
    }

    /// What a combo shows for its selected value, as its renderer draws it.
    private static String shown(JComboBox<String> c) {
        @SuppressWarnings("unchecked")
        ListCellRenderer<Object> r = (ListCellRenderer<Object>) (ListCellRenderer<?>) c.getRenderer();
        Component drawn = r.getListCellRendererComponent(new JList<>(), c.getSelectedItem(), -1, false, false);
        return ((JLabel) drawn).getText();
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

    /**
     * The keys bound with {@link JComponent#registerKeyboardAction}: the look binds action names, that call binds
     * actions.
     */
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

    /// [ResourceDialog#chooserStart] takes the first non-blank entry of a path list; empty, separators only, or an
    /// invalid path with a NUL start in `user.home`.
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
