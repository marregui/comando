package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.schema.SchemaRegistry;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.ui.PanelTexts;
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
        assertThat(button(p[0], "Connect or disconnect")).as("the connection stays in the header").isNotNull();
        List<AbstractButton> chips = new ArrayList<>();
        collectText(p[0], "no connection", chips);
        assertThat(chips).as("the connection chip").hasSize(1);
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
