package dev.marregui.comando.ui.panels;

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
import org.junit.jupiter.api.Test;

import javax.swing.AbstractButton;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class EditorPanelHeaderTest {

    private static final PanelId A = new PanelId("a");

    /** Panel text kept nowhere. */
    private static final PanelTexts TEXTS = new PanelTexts() {
        @Override
        public String load(PanelId panel) {
            return "";
        }

        @Override
        public void changed(PanelId panel, String text) {
        }

        @Override
        public void forget(PanelId panel) {
        }

        @Override
        public void onOutsideChange(Consumer<Outside> listener) {
        }
    };

    @Test
    void aPanelsHeaderHasNoThemeSwitchComandoIsDarkThroughout() throws Exception {
        Workspace[] w = new Workspace[1];
        SwingUtilities.invokeAndWait(() -> {
            w[0] = new Workspace(i -> { }, TEXTS, new SchemaRegistry());
            w[0].render(AppState.initial(List.of(new PanelState(A, "one", "", "")), List.of(), List.of()));
        });
        assertThat(button(w[0].panel(A), "Light editors")).as("no theme button (record 93)").isNull();
        assertThat(button(w[0].panel(A), "Dark editors")).isNull();
    }

    @Test
    void theHeaderHoldsTheConnectionAndTheControlsAndTheSessionBarTheSession() throws Exception {
        SessionState running = SessionState.closed(SessionSpec.fresh("work", "/w").withCheckpoint("/reflect")).withStatus(Status.OPEN, "");
        PanelState panel = new PanelState(A, "one", "", "work");
        EditorPanel[] p = new EditorPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            p[0] = new EditorPanel(panel, i -> { }, new SchemaRegistry());
            p[0].render(panel, AppState.initial(List.of(panel), List.of(), List.of(running)));
        });
        for (String name : List.of("Session", "Start or stop the session", "Start a new conversation", "Checkpoint", "Compact the conversation")) {
            assertThat(button(p[0], name)).as(name + ": in the session bar (record 105)").isNull();
        }
        List<AbstractButton> named = new ArrayList<>();
        collectText(p[0], "work", named);
        assertThat(named).as("the session's name is not in the header").isEmpty();
        AbstractButton toggle = button(p[0], "Connect or disconnect");
        assertThat(toggle).as("the connection stays in the header").isNotNull();
        assertThat(toggle.getToolTipText()).as("unbound, it says how to bind").isEqualTo("No connection; click to bind one");
        List<AbstractButton> chips = new ArrayList<>();
        collectText(p[0], "no connection", chips);
        assertThat(chips).as("no chip of words when there is no connection").isEmpty();
    }

    @Test
    void theConnectionIsOneIconInItsStatesColourFirstOfTheToolsItsNameBindsAnother() throws Exception {
        ConnectionState local = ConnectionState.closed(ConnectionSpec.questDbDefaults("local"));
        PanelState panel = new PanelState(A, "one", "local", "");
        List<Intent> sent = new ArrayList<>();
        EditorPanel[] p = new EditorPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            p[0] = new EditorPanel(panel, sent::add, new SchemaRegistry());
            p[0].render(panel, AppState.initial(List.of(panel), List.of(local), List.of()));
        });
        AbstractButton toggle = button(p[0], "Connect or disconnect");
        assertThat(toggle.getIcon()).as("grey while closed").isSameAs(Icons.get("database-off"));
        assertThat(toggle.getToolTipText()).isEqualTo("local disconnected; click to connect; its name binds another");
        List<AbstractButton> names = new ArrayList<>();
        collectText(p[0], "local", names);
        assertThat(names).as("the name beside it, no dot").extracting(AbstractButton::getText).containsExactly("local ▾");
        assertThat(names.getFirst().getToolTipText()).endsWith("; click to bind another connection or unbind");
        List<Component> row = List.of(toggle.getParent().getComponents());
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

    @Test
    void theHeadersButtonsAreIconsWithoutWords() throws Exception {
        EditorPanel[] p = new EditorPanel[1];
        SwingUtilities.invokeAndWait(() -> p[0] = new EditorPanel(new PanelState(A, "one", "", ""), i -> { }, new SchemaRegistry()));
        for (String name : List.of("Preview", "Run", "Stop", "Clear")) {
            AbstractButton b = button(p[0], name);
            assertThat(b).as(name).isNotNull();
            assertThat(b.getText()).as(name).isNullOrEmpty();
            assertThat(b.getIcon()).as(name).isNotNull();
            assertThat(b.getToolTipText()).as(name).isNotBlank();
            assertThat(b.getClientProperty("JButton.buttonType")).as(name + ": no button shape").isEqualTo("toolBarButton");
        }
    }

    private static void collectText(Component c, String part, List<AbstractButton> out) {
        if (c instanceof AbstractButton b && b.getText() != null && b.getText().contains(part)) {
            out.add(b);
        }
        if (c instanceof Container k) {
            for (Component child : k.getComponents()) {
                collectText(child, part, out);
            }
        }
    }

    private static AbstractButton button(Container root, String name) {
        List<AbstractButton> found = new ArrayList<>();
        collect(root, name, found);
        return found.isEmpty() ? null : found.getFirst();
    }

    private static void collect(Component c, String name, List<AbstractButton> out) {
        if (c instanceof AbstractButton b && name.equals(b.getAccessibleContext().getAccessibleName())) {
            out.add(b);
        }
        if (c instanceof Container k) {
            for (Component child : k.getComponents()) {
                collect(child, name, out);
            }
        }
    }
}
