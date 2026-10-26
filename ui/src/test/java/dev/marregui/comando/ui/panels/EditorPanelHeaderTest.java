package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.intent.Intent;
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
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class EditorPanelHeaderTest {

    private static final PanelId A = new PanelId("a");
    private static final PanelId B = new PanelId("b");

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
    void theThemeButtonSwitchesEveryEditorAndItsPreviewAndTheChoiceIsTold() throws Exception {
        List<Boolean> told = new CopyOnWriteArrayList<>();
        Workspace[] w = new Workspace[1];
        AppState[] s = new AppState[1];
        SwingUtilities.invokeAndWait(() -> {
            w[0] = new Workspace(i -> { }, TEXTS, new SchemaRegistry());
            w[0].onThemeChanged(told::add);
            s[0] = AppState.initial(List.of(new PanelState(A, "one", "", ""), new PanelState(B, "two", "", "")), List.of(), List.of());
            w[0].render(s[0]);
        });
        assertThat(w[0].panel(A).light()).as("dark by default").isFalse();

        SwingUtilities.invokeAndWait(() -> button(w[0].panel(A), "Light editors").doClick());
        assertThat(told).containsExactly(true);
        assertThat(w[0].panel(A).light()).isTrue();
        assertThat(w[0].panel(B).light()).as("every panel, not only the one clicked").isTrue();
        assertThat(button(w[0].panel(B), "Dark editors")).as("the button names the other theme").isNotNull();

        PanelId c = new PanelId("c");
        SwingUtilities.invokeAndWait(() -> w[0].render(s[0].withPanels(List.of(s[0].panels().get(0), s[0].panels().get(1),
                new PanelState(c, "three", "", "")), A)));
        assertThat(w[0].panel(c).light()).as("a panel opened later follows the choice").isTrue();

        SwingUtilities.invokeAndWait(() -> button(w[0].panel(B), "Dark editors").doClick());
        assertThat(told).containsExactly(true, false);
        assertThat(List.of(w[0].panel(A).light(), w[0].panel(B).light(), w[0].panel(c).light())).containsOnly(false);
    }

    @Test
    void aRunningSessionsPanelCompactsItsConversationAndNeitherClearsNorRewritesFromItsHeader() throws Exception {
        List<Intent> intents = new CopyOnWriteArrayList<>();
        SessionState stopped = SessionState.closed(SessionSpec.fresh("work", "/w"));
        PanelState panel = new PanelState(A, "one", "", "work");
        EditorPanel[] p = new EditorPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            // made before its session was bound: the buttons act on the session it shows
            p[0] = new EditorPanel(new PanelState(A, "one", "", ""), intents::add, new SchemaRegistry());
            p[0].render(panel, AppState.initial(List.of(panel), List.of(), List.of(stopped)));
        });
        assertThat(button(p[0], "Compact the conversation").isVisible()).as("stopped: nothing to compact").isFalse();

        SwingUtilities.invokeAndWait(() -> p[0].render(panel, AppState.initial(List.of(panel), List.of(),
                List.of(stopped.withStatus(Status.OPEN, "")))));
        assertThat(button(p[0], "Clear the conversation")).as("the checkpoint clears").isNull();
        assertThat(button(p[0], "Rewrite as a document")).as("in the session's menu in the sidebar").isNull();
        assertThat(button(p[0], "Checkpoint").getToolTipText()).contains("then clears the conversation and carries its summary");
        SwingUtilities.invokeAndWait(() -> button(p[0], "Compact the conversation").doClick());
        assertThat(intents).containsExactly(new Intent.SessionCommand("work", SessionSpec.COMPACT_COMMAND));
    }

    @Test
    void theSessionChipsMenuFollowsThePanelsKind() throws Exception {
        List<Intent> intents = new CopyOnWriteArrayList<>();
        List<String> created = new CopyOnWriteArrayList<>();
        SessionState work = SessionState.closed(SessionSpec.fresh("work", "/w"));
        PanelState own = new PanelState(A, "work", "", "work");
        PanelState notes = new PanelState(new PanelId("n"), "notes", "", "");
        PanelState doc = new PanelState(new PanelId("d"), "PLAN.md", "", "", true);
        AppState state = AppState.initial(List.of(own, notes, doc), List.of(), List.of(work));
        List<List<String>> menus = new ArrayList<>();
        EditorPanel[] view = new EditorPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            for (PanelState p : List.of(own, notes, doc, doc.withSession("work"))) {
                EditorPanel e = new EditorPanel(p, intents::add, new SchemaRegistry());
                e.onNewSession(created::add);
                e.render(p, state);
                menus.add(texts(e.sessionMenu()));
                if (p == notes) {
                    view[0] = e;
                }
            }
        });
        assertThat(menus.get(0)).as("its own panel").containsExactly("work's own panel: removing either removes both");
        assertThat(menus.get(1)).as("a panel without a session").containsExactly("New session notes…", "Open work  /w");
        assertThat(menus.get(2)).as("a document").containsExactly("Attach to work  /w");
        assertThat(menus.get(3)).as("a document attached").containsExactly("Detach from work");

        SwingUtilities.invokeAndWait(() -> click(view[0].sessionMenu(), "New session notes…"));
        assertThat(created).containsExactly("notes");
        SwingUtilities.invokeAndWait(() -> click(view[0].sessionMenu(), "Open work  /w"));
        assertThat(intents).singleElement().isInstanceOfSatisfying(Intent.OpenSession.class,
                o -> assertThat(o.session()).as("its own panel comes forward; this one is not taken").isEqualTo("work"));
    }

    private static List<String> texts(JPopupMenu menu) {
        List<String> out = new ArrayList<>();
        for (Component c : menu.getComponents()) {
            if (c instanceof JMenuItem m) {
                out.add(m.getText());
            }
        }
        return out;
    }

    private static void click(JPopupMenu menu, String text) {
        for (Component c : menu.getComponents()) {
            if (c instanceof JMenuItem m && text.equals(m.getText())) {
                m.doClick();
                return;
            }
        }
        throw new AssertionError("no item " + text);
    }

    @Test
    void aSessionNamedLikeHtmlIsShownAsText() throws Exception {
        SessionState imported = SessionState.closed(SessionSpec.fresh("<html><b>bold</b>", "/w"));
        PanelState panel = new PanelState(A, "one", "", imported.name());
        EditorPanel[] p = new EditorPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            p[0] = new EditorPanel(panel, i -> { }, new SchemaRegistry());
            p[0].render(panel, AppState.initial(List.of(panel), List.of(), List.of(imported)));
        });
        List<AbstractButton> chips = new ArrayList<>();
        collectText(p[0], "bold</b>", chips);
        assertThat(chips).isNotEmpty().allSatisfy(b -> assertThat(b.getText()).doesNotStartWith("<html>"));
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

    @Test
    void theHeadersButtonsAreIconsWithoutWords() throws Exception {
        EditorPanel[] p = new EditorPanel[1];
        SwingUtilities.invokeAndWait(() -> p[0] = new EditorPanel(new PanelState(A, "one", "", ""), i -> { }, new SchemaRegistry()));
        for (String name : List.of("Preview", "Light editors", "Run", "Stop", "Clear")) {
            AbstractButton b = button(p[0], name);
            assertThat(b).as(name).isNotNull();
            assertThat(b.getText()).as(name).isNullOrEmpty();
            assertThat(b.getIcon()).as(name).isNotNull();
            assertThat(b.getToolTipText()).as(name).isNotBlank();
            assertThat(b.getClientProperty("JButton.buttonType")).as(name + ": no button shape").isEqualTo("toolBarButton");
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
