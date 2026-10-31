package dev.marregui.comando.ui.panels;

import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.schema.SchemaRegistry;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.ConnectionState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.ui.PanelTexts;
import dev.marregui.comando.ui.kit.icon.Icons;

import static org.assertj.core.api.Assertions.assertThat;

/// [EditorPanel]'s header: the connection control and icon-only tools; the session controls live in the session bar.
/// - no theme switch: comando is dark throughout
/// - the session's controls and name are not in the header; the connection toggle is
/// - the connection toggle is one icon coloured by state, first of the tools, and its name chip binds another
/// - every header button is an icon with a tooltip and no button shape
/// - the Locate button, after Stop and before Clear, shows a document in Files, else the panel's session in Sessions;
///   its icon is the tab's after a left arrow
class EditorPanelHeaderTest {

    private static final PanelId A = new PanelId("a");

    /** Panel text kept nowhere. */
    private static final PanelTexts TEXTS = new PanelTexts() {
        @Override
        public String load(final PanelId panel) {
            return "";
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

    /// A panel rendered by [Workspace] has neither a "Light editors" nor a "Dark editors" button.
    @Test
    void aPanelsHeaderHasNoThemeSwitchComandoIsDarkThroughout() throws Exception {
        final Workspace[] w = new Workspace[1];
        SwingUtilities.invokeAndWait(() -> {
            w[0] = new Workspace(i -> { }, TEXTS, new SchemaRegistry());
            w[0].render(AppState.initial(List.of(new PanelState(A, "one", "", "")), List.of(), List.of()));
        });
        assertThat(button(w[0].panel(A), "Light editors")).as("no theme button (record 93)").isNull();
        assertThat(button(w[0].panel(A), "Dark editors")).isNull();
    }

    /// The header bound to an open session carries none of the session controls nor the session's name; it keeps the
    /// connection toggle, whose tooltip says how to bind one when unbound, and shows no "no connection" word chip.
    @Test
    void theHeaderHoldsTheConnectionAndTheControlsAndTheSessionBarTheSession() throws Exception {
        final SessionState running = SessionState.closed(SessionSpec.fresh("work", "/w").withCheckpoint("/reflect")).withStatus(Status.OPEN, "");
        final PanelState panel = new PanelState(A, "one", "", "work");
        final EditorPanel[] p = new EditorPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            p[0] = new EditorPanel(panel, i -> { }, new SchemaRegistry());
            p[0].render(panel, AppState.initial(List.of(panel), List.of(), List.of(running)));
        });
        for (final String name : List.of("Session", "Start or stop the session", "Start a new conversation", "Checkpoint", "Compact the conversation")) {
            assertThat(button(p[0], name)).as(name + ": in the session bar (record 105)").isNull();
        }
        final List<AbstractButton> named = new ArrayList<>();
        collectText(p[0], "work", named);
        assertThat(named).as("the session's name is not in the header").isEmpty();
        final AbstractButton toggle = button(p[0], "Connect or disconnect");
        assertThat(toggle).as("the connection stays in the header").isNotNull();
        assertThat(toggle.getToolTipText()).as("unbound, it says how to bind").isEqualTo("No connection; click to bind one");
        final List<AbstractButton> chips = new ArrayList<>();
        collectText(p[0], "no connection", chips);
        assertThat(chips).as("no chip of words when there is no connection").isEmpty();
    }

    /// The connection toggle shows the closed, open and failed icon of its state, sends `OpenConnection` when closed
    /// and `CloseConnection` when open, and sits first of the tools with the name chip ("local ▾") next to it. The
    /// failed state appends the failure reason to the tooltip.
    @Test
    void theConnectionIsOneIconInItsStatesColourFirstOfTheToolsItsNameBindsAnother() throws Exception {
        final ConnectionState local = ConnectionState.closed(ConnectionSpec.questDbDefaults("local"));
        final PanelState panel = new PanelState(A, "one", "local", "");
        final List<Intent> sent = new ArrayList<>();
        final EditorPanel[] p = new EditorPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            p[0] = new EditorPanel(panel, sent::add, new SchemaRegistry());
            p[0].render(panel, AppState.initial(List.of(panel), List.of(local), List.of()));
        });
        final AbstractButton toggle = button(p[0], "Connect or disconnect");
        assertThat(toggle.getIcon()).as("grey while closed").isSameAs(Icons.get("database-off"));
        assertThat(toggle.getToolTipText()).isEqualTo("local disconnected; click to connect; its name binds another");
        final List<AbstractButton> names = new ArrayList<>();
        collectText(p[0], "local", names);
        assertThat(names).as("the name beside it, no dot").extracting(AbstractButton::getText).containsExactly("local ▾");
        assertThat(names.getFirst().getToolTipText()).endsWith("; click to bind another connection or unbind");
        final List<Component> row = List.of(toggle.getParent().getComponents());
        assertThat(row.indexOf(toggle)).as("first of the tools at the right").isZero();
        assertThat(row.indexOf(names.getFirst())).isEqualTo(1);
        assertThat(row).as("with the other tools").contains(button(p[0], "Run"));
        SwingUtilities.invokeAndWait(toggle::doClick);
        assertThat(sent).as("a click connects").containsExactly(new Intent.OpenConnection("local"));
        SwingUtilities.invokeAndWait(() -> p[0].render(panel, AppState.initial(List.of(panel), List.of(local.with(Status.OPEN, "")), List.of())));
        assertThat(toggle.getIcon()).as("blue while open").isSameAs(Icons.get("database"));
        SwingUtilities.invokeAndWait(toggle::doClick);
        assertThat(sent).as("and disconnects").endsWith(new Intent.CloseConnection("local"));
        SwingUtilities.invokeAndWait(() -> p[0].render(panel, AppState.initial(List.of(panel), List.of(local.with(Status.FAILED, "refused")), List.of())));
        assertThat(toggle.getIcon()).isSameAs(Icons.get("database-failed"));
        assertThat(toggle.getToolTipText()).isEqualTo("local failed; click to connect; its name binds another · refused");
    }

    /// Preview, Run, Stop and Clear are icon buttons with no text, a tooltip, and the `toolBarButton` type.
    @Test
    void theHeadersButtonsAreIconsWithoutWords() throws Exception {
        final EditorPanel[] p = new EditorPanel[1];
        SwingUtilities.invokeAndWait(() -> p[0] = new EditorPanel(new PanelState(A, "one", "", ""), i -> { }, new SchemaRegistry()));
        for (final String name : List.of("Preview", "Run", "Stop", "Clear")) {
            final AbstractButton b = button(p[0], name);
            assertThat(b).as(name).isNotNull();
            assertThat(b.getText()).as(name).isNullOrEmpty();
            assertThat(b.getIcon()).as(name).isNotNull();
            assertThat(b.getToolTipText()).as(name).isNotBlank();
            assertThat(b.getClientProperty("JButton.buttonType")).as(name + ": no button shape").isEqualTo("toolBarButton");
        }
    }

    private static void collectText(final Component c, final String part, final List<AbstractButton> out) {
        if (c instanceof AbstractButton b && b.getText() != null && b.getText().contains(part)) {
            out.add(b);
        }
        if (c instanceof Container k) {
            for (final Component child : k.getComponents()) {
                collectText(child, part, out);
            }
        }
    }

    private static AbstractButton button(final Container root, final String name) {
        final List<AbstractButton> found = new ArrayList<>();
        collect(root, name, found);
        return found.isEmpty() ? null : found.getFirst();
    }

    private static void collect(final Component c, final String name, final List<AbstractButton> out) {
        if (c instanceof AbstractButton b && name.equals(b.getAccessibleContext().getAccessibleName())) {
            out.add(b);
        }
        if (c instanceof Container k) {
            for (final Component child : k.getComponents()) {
                collect(child, name, out);
            }
        }
    }

    /// A document's panel locates its file; a session's panel its session; a panel with neither has no Locate button.
    /// The button sits after Stop and before Clear.
    @Test
    void theLocateButtonShowsTheDocumentOrTheSessionWhereItComesFrom() throws Exception {
        final List<String> asked = new ArrayList<>();
        final EditorPanel[] p = new EditorPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            p[0] = new EditorPanel(new PanelState(A, "notes", "", "researcher"), intent -> { }, new SchemaRegistry());
            p[0].onLocate((file, session) -> asked.add(file + "|" + session));
        });
        SwingUtilities.invokeAndWait(() -> {
            final JButton b = p[0].locateButton();
            final Container tools = b.getParent();
            final List<String> names = new ArrayList<>();
            for (final Component c : tools.getComponents()) {
                names.add(c.getAccessibleContext().getAccessibleName());
            }
            assertThat(names.indexOf(b.getAccessibleContext().getAccessibleName())).as("after Stop, before Clear")
                    .isEqualTo(names.indexOf("Stop") + 1).isEqualTo(names.indexOf("Clear") - 1);
            assertThat(b.isVisible()).as("a session's panel").isTrue();
            b.doClick();
            p[0].file(Path.of("/b/MEMORY/x.md"), true);
            assertThat(b.getToolTipText()).isEqualTo("Show the file in the Files tab");
            assertThat(b.getIcon()).as("the Files tab's mark after an arrow, not the tab's own icon")
                    .isSameAs(Icons.locate("files")).isNotSameAs(Icons.get("files"));
            assertThat(b.getIcon().getIconWidth()).as("the arrow before the mark").isGreaterThan(Icons.get("files").getIconWidth());
            b.doClick();
            p[0].render(new PanelState(A, "notes", "", ""), AppState.initial(
                    List.of(new PanelState(A, "notes", "", "")), List.of(), List.of()));
            p[0].file(null, false);
            assertThat(b.isVisible()).as("neither a document nor a session").isFalse();
        });
        assertThat(asked).containsExactly("null|researcher", "/b/MEMORY/x.md|");
    }
}
