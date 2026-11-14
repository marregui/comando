package dev.marregui.comando.ui.panels;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.LookAndFeel;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.ui.kit.async.OnEdt;
import dev.marregui.comando.ui.kit.theme.Ground;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.theme.Tint;

import static org.assertj.core.api.Assertions.assertThat;

/// [SessionGroupMenu], the menu of several selected sessions: each item acts on the sessions it applies to and skips
/// the rest, says how many, and is disabled when none.
/// - Open in a panel: a new panel for each session with none open, never taking the focused one
/// - Start, Stop and Start a new conversation: the sessions their own menu offers them for
/// - a session running its checkpoint takes Open and Stop only, and the submenus name what the others run
/// - Model and Effort: a choice is checked only when every session has it, and sets it where it differs
/// - every item and submenu carries an icon, and the popup paints from the dark and the light theme
@ExtendWith(OnEdt.class)
class SessionGroupMenuTest {

    private static final PanelId FOCUSED = new PanelId("main");

    private final List<Intent> sent = new ArrayList<>();

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @BeforeEach
    void clearSent() {
        sent.clear();
    }

    /// Start goes to the stopped sessions, not to a running or a stopping one; Stop to the running ones, the one
    /// running its checkpoint included; Start a new conversation only to a stopped one with a conversation.
    @Test
    void startStopAndFreshGoToTheSessionsTheyApplyTo() {
        final SessionState stopped = stopped("stopped");
        final SessionState resumable = SessionState.closed(SessionSpec.fresh("resumable", "/w").resumable("conv-1"));
        final SessionState running = running("running");
        final SessionState stopping = stopped("stopping").withStatus(Status.CLOSING, "");
        final SessionState reflecting = checkpointing("reflecting");
        final List<SessionState> selected = List.of(stopped, resumable, running, stopping, reflecting);
        final JPopupMenu m = menu(selected, state(selected));

        click(m, "Start 2 of 5");
        assertThat(sent).containsExactly(new Intent.StartSession("stopped"), new Intent.StartSession("resumable"));
        sent.clear();
        click(m, "Stop 2 of 5");
        assertThat(sent).containsExactly(new Intent.StopSession("running"), new Intent.StopSession("reflecting"));
        sent.clear();
        click(m, "Start a new conversation 1 of 5");
        assertThat(sent).containsExactly(new Intent.StartFresh("resumable"));
    }

    /// An item that applies to no selected session is disabled and says 0; one that applies to some is enabled.
    @Test
    void anItemThatAppliesToNoneIsDisabled() {
        final List<SessionState> running = List.of(running("a"), running("b"));
        final JPopupMenu all = menu(running, state(running));
        assertThat(item(all, "Start 0 of 2").isEnabled()).as("both run already").isFalse();
        assertThat(item(all, "Start a new conversation 0 of 2").isEnabled()).as("nothing to leave behind").isFalse();
        assertThat(item(all, "Stop").isEnabled()).isTrue();

        final List<SessionState> stopped = List.of(stopped("a"), stopped("b"));
        final JPopupMenu none = menu(stopped, state(stopped));
        assertThat(item(none, "Stop 0 of 2").isEnabled()).as("neither runs").isFalse();
        assertThat(item(none, "Start").isEnabled()).isTrue();
        assertThat(item(none, "Start a new conversation 0 of 2").isEnabled()).as("no conversation yet").isFalse();
    }

    /// Open in a panel opens one for each session with no panel open, each its own new panel on the focused panel's
    /// connection, and leaves a session whose panel is open where it is.
    @Test
    void openInAPanelOpensOneForEachSessionWithoutOne() {
        final List<SessionState> selected = List.of(stopped("bound"), stopped("loose"), running("other"));
        final AppState state = AppState.initial(List.of(new PanelState(FOCUSED, "main", "", "bound")), List.of(),
                selected);
        click(menu(selected, state), "Open in a panel 2 of 3");

        assertThat(sent).hasSize(2).allSatisfy(i -> assertThat(i).isInstanceOf(Intent.OpenSession.class));
        final List<Intent.OpenSession> opens = sent.stream().map(Intent.OpenSession.class::cast).toList();
        assertThat(opens).extracting(Intent.OpenSession::session).containsExactly("loose", "other");
        assertThat(opens).extracting(Intent.OpenSession::target).as("the focused panel lends its connection")
                .containsOnly(FOCUSED);
        assertThat(opens).extracting(Intent.OpenSession::newPanel).as("a new panel each, never the focused one")
                .doesNotHaveDuplicates().doesNotContain(FOCUSED);

        final List<SessionState> bound = List.of(stopped("bound"));
        final JPopupMenu none = menu(bound, state);
        assertThat(item(none, "Open in a panel 0 of 1").isEnabled()).isFalse();
    }

    /// A session running its checkpoint takes Open and Stop, as its own menu offers them, and waits for the rest, the
    /// submenus included.
    @Test
    void aSessionRunningItsCheckpointTakesOpenAndStopOnly() {
        final List<SessionState> selected = List.of(checkpointing("a"), checkpointing("b"));
        final JPopupMenu m = menu(selected, state(selected));
        assertThat(item(m, "Open in a panel").isEnabled()).isTrue();
        assertThat(item(m, "Stop").isEnabled()).isTrue();
        assertThat(item(m, "Start a new conversation 0 of 2").isEnabled()).isFalse();
        assertThat(submenu(m, "Model").isEnabled()).isFalse();
        assertThat(submenu(m, "Effort").isEnabled()).isFalse();

        final List<SessionState> mixed = List.of(
                running("free").withSpec(SessionSpec.fresh("free", "/w").withModel("opus")),
                checkpointing("busy"));
        click(submenu(menu(mixed, state(mixed)), "Model: opus"), "sonnet");
        assertThat(sent).containsExactly(new Intent.SetSessionModel("free", "sonnet"));
    }

    /// The sessions share a model: the submenu names it and checks it, and another model is set on both.
    @Test
    void aModelEverySessionHasIsCheckedAndAnotherIsSetOnEach() {
        final List<SessionState> selected = List.of(withModel("a", "opus"), withModel("b", "opus"));
        final JMenu models = submenu(menu(selected, state(selected)), "Model: opus");
        final JMenuItem opus = item(models, "opus");
        assertThat(opus.isSelected()).as("both have it").isTrue();
        click(models, "sonnet");
        assertThat(sent).containsExactly(new Intent.SetSessionModel("a", "sonnet"),
                new Intent.SetSessionModel("b", "sonnet"));
    }

    /// The sessions differ: nothing is checked, and a choice goes only to the sessions that do not have it.
    @Test
    void mixedModelsCheckNoneAndSetOnlyWhereTheyDiffer() {
        final List<SessionState> selected = List.of(withModel("a", "opus"), withModel("b", "sonnet"));
        final JMenu models = submenu(menu(selected, state(selected)), "Model");
        assertThat(Stream.of(models.getMenuComponents()).map(JMenuItem.class::cast).filter(JMenuItem::isSelected))
                .as("not every session has one").isEmpty();
        click(models, "opus");
        assertThat(sent).containsExactly(new Intent.SetSessionModel("b", "opus"));
    }

    /// Effort works as Model: the shared one checked, another set only where it differs.
    @Test
    void effortIsCheckedWhenSharedAndSetWhereItDiffers() {
        final List<SessionState> shared = List.of(withEffort("a", "high"), withEffort("b", "high"));
        final JMenu same = submenu(menu(shared, state(shared)), "Effort: high");
        assertThat(item(same, "high").isSelected()).isTrue();

        final List<SessionState> mixed = List.of(withEffort("a", "high"), withEffort("b", "low"));
        final JMenu efforts = submenu(menu(mixed, state(mixed)), "Effort");
        click(efforts, "low");
        assertThat(sent).containsExactly(new Intent.SetSessionEffort("a", "low"));
    }

    /// Under one dark and one light theme every item and submenu, and every choice in them, has an icon, and the popup
    /// paints its ground from the theme: dark under the dark one, light under the light one.
    @Test
    void everyItemHasAnIconAndThePopupPaintsFromTheTheme() throws UnsupportedLookAndFeelException {
        final List<SessionState> selected = List.of(stopped("a"), running("b"));
        final LookAndFeel before = UIManager.getLookAndFeel();
        Theme.install();
        try {
            Theme.choose(Ground.GRAPHITE, Tint.TREASURE);
            assertPainted(menu(selected, state(selected)), false);
            Theme.choose(Ground.FOG, Tint.SLATE_INK);
            assertPainted(menu(selected, state(selected)), true);
        } finally {
            Theme.choose(Ground.GRAPHITE, Tint.TREASURE);
            UIManager.setLookAndFeel(before);
        }
    }

    private static void assertPainted(final JPopupMenu m, final boolean light) {
        final List<JMenuItem> items = items(m);
        assertThat(items).hasSize(6);
        assertThat(items).allSatisfy(i -> assertThat(i.getIcon()).as(i.getText()).isNotNull());
        for (final JMenu sub : items.stream().filter(JMenu.class::isInstance).map(JMenu.class::cast).toList()) {
            assertThat(items(sub.getPopupMenu())).isNotEmpty()
                    .allSatisfy(i -> assertThat(i.getIcon()).as(sub.getText() + " > " + i.getText()).isNotNull());
        }
        m.setSize(m.getPreferredSize());
        m.doLayout();
        final BufferedImage image = new BufferedImage(m.getWidth(), m.getHeight(), BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = image.createGraphics();
        try {
            m.paint(g);
        } finally {
            g.dispose();
        }
        final JMenuItem last = items.getLast();
        final Color ground = new Color(image.getRGB(m.getWidth() / 2, last.getY() + last.getHeight() + 1));
        final float brightness = Color.RGBtoHSB(ground.getRed(), ground.getGreen(), ground.getBlue(), null)[2];
        assertThat(Theme.light()).isEqualTo(light);
        if (light) {
            assertThat(brightness).as("the light theme's ground").isGreaterThan(0.6f);
        } else {
            assertThat(brightness).as("the dark theme's ground").isLessThan(0.4f);
        }
    }

    private static AppState state(final List<SessionState> sessions) {
        return AppState.initial(List.of(new PanelState(FOCUSED, "main", "", "")), List.of(), sessions);
    }

    private static SessionState stopped(final String name) {
        return SessionState.closed(SessionSpec.fresh(name, "/w/" + name));
    }

    private static SessionState running(final String name) {
        return stopped(name).withStatus(Status.OPEN, "");
    }

    /// A running session in the turn of its checkpoint.
    private static SessionState checkpointing(final String name) {
        final SessionState s = running(name).withTurns(1, 1L).withCheckpointTurns(1);
        assertThat(s.isCheckpointing()).isTrue();
        return s;
    }

    private static SessionState withModel(final String name, final String model) {
        return SessionState.closed(SessionSpec.fresh(name, "/w/" + name).withModel(model));
    }

    private static SessionState withEffort(final String name, final String effort) {
        return SessionState.closed(SessionSpec.fresh(name, "/w/" + name).withEffort(effort));
    }

    private static List<JMenuItem> items(final JPopupMenu m) {
        return Stream.of(m.getComponents()).filter(JMenuItem.class::isInstance).map(JMenuItem.class::cast).toList();
    }

    private static JMenuItem item(final JPopupMenu m, final String text) {
        return items(m).stream().filter(i -> i.getText().equals(text)).findFirst()
                .orElseThrow(() -> new AssertionError("no item " + text + " in " + texts(items(m))));
    }

    private static JMenuItem item(final JMenu m, final String text) {
        return item(m.getPopupMenu(), text);
    }

    private static JMenu submenu(final JPopupMenu m, final String text) {
        return (JMenu) item(m, text);
    }

    private static void click(final JPopupMenu m, final String text) {
        final JMenuItem i = item(m, text);
        assertThat(i.isEnabled()).as(text).isTrue();
        i.doClick(0);
    }

    private static void click(final JMenu m, final String text) {
        click(m.getPopupMenu(), text);
    }

    private static List<String> texts(final List<? extends Component> items) {
        return items.stream().map(c -> ((JMenuItem) c).getText()).toList();
    }

    private JPopupMenu menu(final List<SessionState> selected, final AppState state) {
        return SessionGroupMenu.menu(selected, state, sent::add);
    }
}
