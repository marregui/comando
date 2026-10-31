package dev.marregui.comando.ui.panels;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.result.ResultRegistry;
import dev.marregui.comando.core.schema.SchemaRegistry;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.transcript.TranscriptRegistry;
import dev.marregui.comando.ui.PanelTexts;
import dev.marregui.comando.ui.kit.action.ActionRegistry;
import dev.marregui.comando.ui.kit.resource.ResourceActions;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.widget.WidgetContext;
import dev.marregui.comando.ui.widget.WidgetRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/// A session whose checkpoint runs locks every view bound to it (record 130), read from
/// `SessionState.isCheckpointing()`.
/// - its panel reads only, greyed, Run and Clear off, Stop on; a tool's text is still taken
/// - its tab's title greys; the Session tab greys while it shows that session
/// - the session bar keeps only the plug on and says how to carry on
/// - the sidebar's menu keeps Stop, Interrupt, Open, Copy and Clone; the rest is off
/// - another session's views stay as they are, and the lock lifts when the checkpoint ends
class CheckpointLockTest {

    private static final PanelId OWN = new PanelId("own");
    private static final PanelId NOTES = new PanelId("notes");
    private static final PanelId OTHER = new PanelId("other");

    /** Panel text kept nowhere. */
    private static final PanelTexts TEXTS = new PanelTexts() {
        @Override
        public String load(final PanelId panel) {
            return "some notes";
        }

        @Override
        public void changed(final PanelId panel, final String text) {
        }

        @Override
        public void forget(final PanelId panel) {
        }

        @Override
        public void onOutsideChange(final Consumer<Outside> listener) {
        }
    };

    /// The session's panel takes no typing while the checkpoint runs, and greys; Run and Clear are off, Stop is on; a
    /// tool's text is still taken. Once the checkpoint ends, the panel is as before, and a session that only works
    /// locks nothing.
    @Test
    void aLockedPanelReadsOnlyGreyedWithStopOn() throws Exception {
        final EditorPanel[] p = new EditorPanel[1];
        final PanelState own = state(true).panel(OWN).orElseThrow();
        SwingUtilities.invokeAndWait(() -> {
            p[0] = new EditorPanel(own, i -> { }, new SchemaRegistry());
            p[0].editor().load("some notes");
            p[0].render(own, state(true));
        });
        assertThat(p[0].locked()).isTrue();
        assertThat(p[0].editor().area().isEditable()).as("the user's typing waits").isFalse();
        assertThat(p[0].veiled()).as("greyed").isTrue();
        assertThat(p[0].editable()).as("a prompt another session sends is still written in").isTrue();
        assertThat(button(p[0], "Run").isEnabled()).isFalse();
        assertThat(p[0].clearOffered()).isFalse();
        assertThat(button(p[0], "Stop").isEnabled()).as("Stop interrupts the checkpoint").isTrue();

        SwingUtilities.invokeAndWait(() -> p[0].render(own, state(false)));
        assertThat(p[0].locked()).as("a turn that is no checkpoint").isFalse();
        assertThat(p[0].editor().area().isEditable()).isTrue();
        assertThat(p[0].veiled()).isFalse();
        assertThat(button(p[0], "Run").isEnabled()).isTrue();
        assertThat(p[0].clearOffered()).isTrue();
        assertThat(button(p[0], "Stop").isEnabled()).as("the session still works").isTrue();
    }

    /// The tab of each of the session's panels greys, the others' do not; the Session tab greys while it shows the
    /// locked session, not another's; both come back once the checkpoint ends.
    @Test
    void theTabsAndTheSessionTabGreyWithTheSession() throws Exception {
        final Workspace[] w = new Workspace[1];
        final LowerPanel[] lower = new LowerPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            w[0] = new Workspace(i -> { }, TEXTS, new SchemaRegistry());
            w[0].render(state(true));
            lower[0] = new LowerPanel(new WidgetContext(i -> { }, new ActionRegistry()), new ResultRegistry(),
                    new TranscriptRegistry(), new WidgetRegistry());
            lower[0].render(state(true));
        });
        assertThat(title(w[0], OWN)).as("the session's panel").isEqualTo(LockVeil.dimmed());
        assertThat(title(w[0], NOTES)).as("no session").isNotEqualTo(LockVeil.dimmed());
        assertThat(title(w[0], OTHER)).as("another session").isNotEqualTo(LockVeil.dimmed());
        assertThat(lower[0].sessionVeiled()).as("the Session tab shows the locked session").isTrue();

        SwingUtilities.invokeAndWait(() -> lower[0].render(state(true).withFocused(OTHER)));
        assertThat(lower[0].sessionVeiled()).as("it shows another session").isFalse();

        SwingUtilities.invokeAndWait(() -> {
            w[0].render(state(false));
            lower[0].render(state(false));
        });
        assertThat(title(w[0], OWN)).as("unlocked").isNotEqualTo(LockVeil.dimmed());
        assertThat(lower[0].sessionVeiled()).isFalse();
    }

    /// The session bar keeps the plug, which stops the session, on; the chip, model, effort and Checkpoint are off; the
    /// activity, purple, says how to carry on. Once the checkpoint ends they are on again.
    @Test
    void theSessionBarKeepsOnlyThePlugAndSaysHowToCarryOn() throws Exception {
        final SessionBar[] bar = new SessionBar[1];
        SwingUtilities.invokeAndWait(() -> {
            bar[0] = new SessionBar(i -> { });
            bar[0].render(state(true));
        });
        for (final String name : List.of("Session", "Model", "Effort", "Checkpoint")) {
            assertThat(button(bar[0].component(), name).isEnabled()).as(name).isFalse();
        }
        assertThat(button(bar[0].component(), "Start or stop the session").isEnabled()).as("the plug").isTrue();
        final JLabel activity = label(bar[0].component(), "Activity");
        assertThat(activity.getText()).startsWith("checkpoint · ").endsWith(" · " + SessionBar.LOCKED_HINT);
        assertThat(activity.getForeground()).isEqualTo(Theme.THINKING);

        SwingUtilities.invokeAndWait(() -> bar[0].render(state(false)));
        for (final String name : List.of("Session", "Model", "Effort", "Checkpoint")) {
            assertThat(button(bar[0].component(), name).isEnabled()).as(name).isTrue();
        }
        assertThat(label(bar[0].component(), "Activity").getText()).doesNotContain(SessionBar.LOCKED_HINT);
        assertThat(label(bar[0].component(), "Activity").getForeground()).isEqualTo(Theme.MUTED);
    }

    /// The sidebar's menu for the locked session keeps what leaves it or only reads it, Open, Stop, Interrupt, Copy
    /// and Clone, and turns off what acts on it; another session's menu is untouched.
    @Test
    void theSidebarsMenuKeepsStopAndInterrupt() throws Exception {
        final SessionsSidebar[] s = new SessionsSidebar[1];
        final JPopupMenu[] menus = new JPopupMenu[2];
        SwingUtilities.invokeAndWait(() -> {
            s[0] = new SessionsSidebar(i -> { }, new NoDialogs(), TEXTS, () -> { });
            s[0].render(state(true));
            menus[0] = s[0].sessionMenu(state(true).session("work").orElseThrow());
            menus[1] = s[0].sessionMenu(state(true).session("other").orElseThrow());
        });
        final List<String> off = List.of("Checkpoint", "Compact the conversation", "Rewrite work as a document",
                "Edit…", "Remove…");
        final List<String> on = List.of("Open in a panel", "Stop", "Interrupt the turn", "Copy details", "Clone…");
        for (final String name : off) {
            assertThat(item(menus[0], name).isEnabled()).as("locked: " + name).isFalse();
            assertThat(item(menus[1], name.replace("work", "other")).isEnabled()).as("another: " + name).isTrue();
        }
        for (final String name : on) {
            assertThat(item(menus[0], name).isEnabled()).as("locked: " + name).isTrue();
        }
        assertThat(item(menus[0], "Model: ").isEnabled()).isFalse();
        assertThat(item(menus[0], "Effort: ").isEnabled()).isFalse();
    }

    /**
     * Two sessions, both working: {@code work} on its checkpoint when {@code locked}, else on a prompt;
     * {@code other} on a prompt. The focused panel is {@code work}'s own; {@code notes} has no session.
     */
    private static AppState state(final boolean locked) {
        final SessionState busy = SessionState.closed(SessionSpec.fresh("work", "/w").withCheckpoint("/reflect"))
                .withStatus(Status.OPEN, "").withTurns(1, 1L);
        final SessionState work = locked ? busy.withCheckpointTurns(1) : busy;
        final SessionState other = SessionState.closed(SessionSpec.fresh("other", "/o").withCheckpoint("/reflect"))
                .withStatus(Status.OPEN, "").withTurns(1, 1L);
        final List<PanelState> panels = List.of(new PanelState(OWN, "work", "", "work"),
                new PanelState(NOTES, "notes", "", ""), new PanelState(OTHER, "other", "", "other"));
        return AppState.initial(panels, List.of(), List.of(work, other)).withFocused(OWN);
    }

    private static AbstractButton button(final Container root, final String name) {
        final List<AbstractButton> out = new ArrayList<>();
        collect(root, AbstractButton.class, b -> name.equals(b.getAccessibleContext().getAccessibleName()), out);
        assertThat(out).as("a %s button", name).isNotEmpty();
        return out.getFirst();
    }

    private static JLabel label(final Container root, final String name) {
        final List<JLabel> out = new ArrayList<>();
        collect(root, JLabel.class, l -> name.equals(l.getAccessibleContext().getAccessibleName()), out);
        assertThat(out).as("a %s label", name).isNotEmpty();
        return out.getFirst();
    }

    /** The colour of the title of {@code panel}'s tab. */
    private static Color title(final Workspace w, final PanelId panel) {
        final JTabbedPane tabs = (JTabbedPane) w.component();
        return tabs.getForegroundAt(tabs.indexOfComponent(w.panel(panel)));
    }

    /** The menu's item whose text starts with {@code text}. */
    private static JMenuItem item(final JPopupMenu menu, final String text) {
        for (final Component c : menu.getComponents()) {
            if (c instanceof JMenuItem i && i.getText() != null && i.getText().startsWith(text)) {
                return i;
            }
        }
        throw new AssertionError("no item " + text);
    }

    private static <T extends Component> void collect(final Component c, final Class<T> type, final Predicate<T> which,
            final List<T> out) {
        if (type.isInstance(c) && which.test(type.cast(c))) {
            out.add(type.cast(c));
        }
        if (c instanceof Container k) {
            for (final Component child : k.getComponents()) {
                collect(child, type, which, out);
            }
        }
    }

    /** Dialogs that do nothing: the menus are only read. */
    private static final class NoDialogs implements SessionsSidebar.Dialogs {
        @Override
        public ResourceActions.Editor sessions() {
            return null;
        }

        @Override
        public void importSessions() {
        }

        @Override
        public ResourceActions.Editor connections() {
            return null;
        }

        @Override
        public void exploreSchema(final String connection) {
        }

        @Override
        public void openFile(final Path file) {
        }
    }
}
