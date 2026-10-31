package dev.marregui.comando.ui.panels;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.AbstractButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.Reflection;
import dev.marregui.comando.core.state.SessionInfo;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.ui.kit.format.Labels;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.theme.Theme;

import static org.assertj.core.api.Assertions.assertThat;

/// [SessionBar] shows the focused panel's session: its chip, plug, model, effort, context and checkpoint.
/// - it follows the focus, and a panel without a session shows only a chip to bind one
/// - what the session does changes no control's place
/// - the model and effort are menus that send the change
/// - the context turns purple once a checkpoint is due, and only if the session has one
/// - the checkpoint sits beside the context; the bar neither clears nor rewrites
/// - the chip's menu follows the focused panel's kind
/// - a session named like HTML is shown as text
class SessionBarTest {

    private static final PanelId A = new PanelId("a");

    /// For a panel bound to an open session the bar shows its name with the session icon, the plug, the model, the
    /// effort and the context share with no cost; focusing a panel without a session leaves only "no session ▾" and
    /// hides the rest.
    @Test
    void theBarShowsTheFocusedPanelsSessionAndFollowsTheFocus() throws Exception {
        final SessionState work = SessionState.closed(SessionSpec.fresh("work", "/w").withModel("opus").withEffort("high"))
                .withStatus(Status.OPEN, "").withUsage(1.0, 412_000, 1_000_000, 1L);
        final PanelState own = new PanelState(A, "work", "", "work");
        final PanelState notes = new PanelState(new PanelId("n"), "notes", "", "");
        final AppState state = AppState.initial(List.of(own, notes), List.of(), List.of(work));
        final SessionBar[] bar = new SessionBar[1];
        SwingUtilities.invokeAndWait(() -> {
            bar[0] = new SessionBar(i -> { });
            bar[0].render(state.withFocused(A));
        });
        assertThat(button(bar[0], "Session").getText()).as("no dot: the icon shows the state").isEqualTo("work");
        assertThat(button(bar[0], "Session").getIcon()).isSameAs(Icons.get("claude"));
        assertThat(button(bar[0], "Session").getToolTipText()).startsWith("Running");
        assertThat(button(bar[0], "Start or stop the session").isVisible()).isTrue();
        assertThat(button(bar[0], "Compact the conversation")).as("compact is a command, not a button").isNull();
        assertThat(button(bar[0], "Model").getText()).isEqualTo("opus ▾");
        assertThat(button(bar[0], "Effort").getText()).isEqualTo("high ▾");
        assertThat(label(bar[0], "Context").getText()).isEqualTo("ctx " + Labels.tokens(412_000) + " (41%)").doesNotContain("$");

        SwingUtilities.invokeAndWait(() -> bar[0].render(state.withFocused(notes.id())));
        assertThat(button(bar[0], "Session").getText()).as("a panel without a session binds one").isEqualTo("no session ▾");
        for (final String name : List.of("Start or stop the session", "Start a new conversation", "Checkpoint", "Model", "Effort")) {
            assertThat(button(bar[0], name).isVisible()).as(name).isFalse();
        }
        assertThat(label(bar[0], "Context").isVisible()).isFalse();
        assertThat(label(bar[0], "Activity").isVisible()).isFalse();
    }

    /// A session going from idle to three turns busy keeps every always-shown control at the same x, shows the activity
    /// with the queue count, and greys out the new-conversation button while it runs.
    @Test
    void whatTheSessionDoesChangesNoControlsPlace() throws Exception {
        final SessionState idle = SessionState.closed(SessionSpec.imported("work", "/w", "c1").withCheckpoint("/reflect"))
                .withStatus(Status.OPEN, "");
        final SessionState busy = idle.withTurns(3, System.currentTimeMillis() - 65_000);
        final PanelState own = new PanelState(A, "work", "", "work");
        final SessionBar[] bar = new SessionBar[1];
        final List<Integer> before = new ArrayList<>();
        final List<Integer> after = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            bar[0] = new SessionBar(i -> { });
            bar[0].render(AppState.initial(List.of(own), List.of(), List.of(idle)));
            before.addAll(places(bar[0]));
            bar[0].render(AppState.initial(List.of(own), List.of(), List.of(busy)));
            after.addAll(places(bar[0]));
        });
        assertThat(label(bar[0], "Activity").getText()).startsWith("working · ").endsWith(" · 2 queued");
        assertThat(after).as("the chip, plug, new conversation, model, effort and checkpoint stay put").isEqualTo(before);
        assertThat(button(bar[0], "Start a new conversation").isVisible()).as("it has a conversation").isTrue();
        assertThat(button(bar[0], "Start a new conversation").isEnabled()).as("greyed out while it runs").isFalse();
    }

    /// For a stopped session the model button names its configured model, as the session's row does, not a model the
    /// CLI reported earlier; its menu checks it, and picking items sends `SetSessionModel` and `SetSessionEffort`. An
    /// unset effort reads "default".
    @Test
    void theModelAndTheEffortAreMenusThatChangeThem() throws Exception {
        final List<Intent> intents = new CopyOnWriteArrayList<>();
        final SessionState x = SessionState.closed(SessionSpec.fresh("edge", "/w/edge").withModel("sonnet"))
                .withInfo(new SessionInfo("", "claude-opus-4-5", "", "", "", List.of()), 1L);
        final PanelState own = new PanelState(A, "edge", "", "edge");
        final SessionBar[] bar = new SessionBar[1];
        final List<String> checked = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            bar[0] = new SessionBar(intents::add);
            bar[0].render(AppState.initial(List.of(own), List.of(), List.of(x)));
            for (final Component c : bar[0].modelMenu().getComponents()) {
                if (c instanceof JCheckBoxMenuItem m && m.isSelected()) {
                    checked.add(m.getText());
                }
            }
            click(bar[0].modelMenu(), "opus");
            click(bar[0].effortMenu(), "max");
        });
        assertThat(button(bar[0], "Model").getText()).as("named as the session's row names it").isEqualTo(SessionRow.modelName(x) + " ▾").isEqualTo("sonnet ▾");
        assertThat(button(bar[0], "Model").getToolTipText()).endsWith("click to change");
        assertThat(checked).containsExactly("sonnet");
        assertThat(intents).containsExactly(new Intent.SetSessionModel("edge", "opus"), new Intent.SetSessionEffort("edge", "max"));
        assertThat(button(bar[0], "Effort").getText()).isEqualTo("default ▾");
    }

    /// The context label is muted until the reflection is due, then [Theme#THINKING] with a tooltip saying the
    /// checkpoint is due; a session without a checkpoint command stays muted even when due.
    @Test
    void theContextTurnsPurpleOnceTheCheckpointIsDue() throws Exception {
        final SessionState x = SessionState.closed(SessionSpec.fresh("work", "/w").withCheckpoint("/reflect"))
                .withStatus(Status.OPEN, "").withUsage(1.0, 700_000, 1_000_000, 1L);
        final PanelState own = new PanelState(A, "work", "", "work");
        final SessionBar[] bar = new SessionBar[1];
        SwingUtilities.invokeAndWait(() -> {
            bar[0] = new SessionBar(i -> { });
            bar[0].render(AppState.initial(List.of(own), List.of(), List.of(x.withReflection(new Reflection(false, true)))));
        });
        assertThat(label(bar[0], "Context").getForeground()).isEqualTo(Theme.MUTED);
        SwingUtilities.invokeAndWait(() -> bar[0].render(AppState.initial(List.of(own), List.of(), List.of(x.withReflection(new Reflection(true, false))))));
        assertThat(label(bar[0], "Context").getForeground()).as("as the session's row in the sidebar").isEqualTo(Theme.THINKING);
        assertThat(label(bar[0], "Context").getToolTipText()).endsWith("the checkpoint beside it is due");
        SwingUtilities.invokeAndWait(() -> bar[0].render(AppState.initial(List.of(own), List.of(),
                List.of(x.withSpec(x.spec().withCheckpoint("")).withReflection(new Reflection(true, false))))));
        assertThat(label(bar[0], "Context").getForeground()).as("no checkpoint, nothing due").isEqualTo(Theme.MUTED);
    }

    /// The bar has no clear or rewrite button; the checkpoint shares the context's group, the context reads "ctx –"
    /// before a turn reports it, and a click on the checkpoint sends `Checkpoint`.
    @Test
    void theCheckpointSitsBesideTheContextAndNeitherClearsNorRewritesFromTheBar() throws Exception {
        final List<Intent> intents = new CopyOnWriteArrayList<>();
        final SessionState running = SessionState.closed(SessionSpec.fresh("work", "/w").withCheckpoint("/reflect")).withStatus(Status.OPEN, "");
        final PanelState panel = new PanelState(A, "one", "", "work");
        final SessionBar[] bar = new SessionBar[1];
        SwingUtilities.invokeAndWait(() -> {
            bar[0] = new SessionBar(intents::add);
            bar[0].render(AppState.initial(List.of(panel), List.of(), List.of(running)));
        });
        assertThat(button(bar[0], "Clear the conversation")).as("no clear button: the checkpoint compacts").isNull();
        assertThat(button(bar[0], "Rewrite as a document")).as("in the session's menu in the sidebar").isNull();
        assertThat(button(bar[0], "Checkpoint").getParent()).as("in the context's group").isSameAs(label(bar[0], "Context").getParent());
        assertThat(label(bar[0], "Context").getText()).as("before a turn reports it").isEqualTo("ctx –");
        assertThat(label(bar[0], "Context").getToolTipText()).endsWith("the checkpoint beside it falls due at 60%");
        assertThat(button(bar[0], "Checkpoint").getToolTipText()).contains("then compacts the conversation");
        SwingUtilities.invokeAndWait(() -> button(bar[0], "Checkpoint").doClick());
        assertThat(intents).containsExactly(new Intent.Checkpoint("work"));
    }

    /// The chip's menu explains a session's own panel, offers a new or existing session to a panel without one, attach
    /// to a document and detach to an attached document; "New session" passes the panel's title, and "Open" sends
    /// `OpenSession`.
    @Test
    void theSessionChipsMenuFollowsTheFocusedPanelsKind() throws Exception {
        final List<Intent> intents = new CopyOnWriteArrayList<>();
        final List<String> created = new CopyOnWriteArrayList<>();
        final SessionState work = SessionState.closed(SessionSpec.fresh("work", "/w"));
        final PanelState own = new PanelState(A, "work", "", "work");
        final PanelState notes = new PanelState(new PanelId("n"), "notes", "", "");
        final PanelState doc = new PanelState(new PanelId("d"), "PLAN.md", "", "", true);
        final PanelState attached = new PanelState(new PanelId("e"), "NOTES.md", "", "work", true);
        final AppState state = AppState.initial(List.of(own, notes, doc, attached), List.of(), List.of(work));
        final List<List<String>> menus = new ArrayList<>();
        final SessionBar[] bar = new SessionBar[1];
        SwingUtilities.invokeAndWait(() -> {
            bar[0] = new SessionBar(intents::add);
            bar[0].onNewSession(created::add);
            for (final PanelState p : List.of(own, notes, doc, attached)) {
                bar[0].render(state.withFocused(p.id()));
                menus.add(texts(bar[0].sessionMenu()));
            }
        });
        assertThat(menus.get(0)).as("its own panel").containsExactly("work's own panel: removing either removes both");
        assertThat(menus.get(1)).as("a panel without a session").containsExactly("New session notes…", "Open work  /w");
        assertThat(menus.get(2)).as("a document").containsExactly("Attach to work  /w");
        assertThat(menus.get(3)).as("a document attached").containsExactly("Detach from work");

        SwingUtilities.invokeAndWait(() -> {
            bar[0].render(state.withFocused(notes.id()));
            click(bar[0].sessionMenu(), "New session notes…");
        });
        assertThat(created).containsExactly("notes");
        SwingUtilities.invokeAndWait(() -> click(bar[0].sessionMenu(), "Open work  /w"));
        assertThat(intents).singleElement().isInstanceOfSatisfying(Intent.OpenSession.class,
                o -> assertThat(o.session()).as("its own panel comes forward; this one is not taken").isEqualTo("work"));
    }

    /// A session named `<html><b>bold</b>` shows its name literally, not rendered as HTML.
    @Test
    void aSessionNamedLikeHtmlIsShownAsText() throws Exception {
        final SessionState imported = SessionState.closed(SessionSpec.fresh("<html><b>bold</b>", "/w"));
        final PanelState panel = new PanelState(A, "one", "", imported.name());
        final SessionBar[] bar = new SessionBar[1];
        SwingUtilities.invokeAndWait(() -> {
            bar[0] = new SessionBar(i -> { });
            bar[0].render(AppState.initial(List.of(panel), List.of(), List.of(imported)));
        });
        assertThat(button(bar[0], "Session").getText()).contains("bold</b>").doesNotStartWith("<html>");
    }

    /** The x of each control a session always shows, laid out at a fixed width. */
    private static List<Integer> places(final SessionBar bar) {
        final JComponent root = bar.component();
        root.setSize(900, 32);
        layOut(root);
        final List<Integer> xs = new ArrayList<>();
        for (final String name : List.of("Session", "Start or stop the session", "Start a new conversation", "Model", "Effort", "Checkpoint")) {
            final AbstractButton b = button(bar, name);
            xs.add(SwingUtilities.convertPoint(b.getParent(), b.getLocation(), root).x);
        }
        return xs;
    }

    private static void layOut(final Component c) {
        if (c instanceof Container k) {
            k.doLayout();
            for (final Component child : k.getComponents()) {
                layOut(child);
            }
        }
    }

    private static List<String> texts(final JPopupMenu menu) {
        final List<String> out = new ArrayList<>();
        for (final Component c : menu.getComponents()) {
            if (c instanceof JMenuItem m) {
                out.add(m.getText());
            }
        }
        return out;
    }

    private static void click(final JPopupMenu menu, final String text) {
        for (final Component c : menu.getComponents()) {
            if (c instanceof JMenuItem m && text.equals(m.getText())) {
                m.doClick();
                return;
            }
        }
        throw new AssertionError("no item " + text);
    }

    private static AbstractButton button(final SessionBar bar, final String name) {
        final List<AbstractButton> out = new ArrayList<>();
        collect(bar.component(), AbstractButton.class, name, out);
        return out.isEmpty() ? null : out.getFirst();
    }

    private static JLabel label(final SessionBar bar, final String name) {
        final List<JLabel> out = new ArrayList<>();
        collect(bar.component(), JLabel.class, name, out);
        assertThat(out).as("a label %s", name).isNotEmpty();
        return out.getFirst();
    }

    private static <T extends Component> void collect(final Component c, final Class<T> type, final String name, final List<T> out) {
        if (type.isInstance(c) && name.equals(c.getAccessibleContext().getAccessibleName())) {
            out.add(type.cast(c));
        }
        if (c instanceof Container k) {
            for (final Component child : k.getComponents()) {
                collect(child, type, name, out);
            }
        }
    }
}
