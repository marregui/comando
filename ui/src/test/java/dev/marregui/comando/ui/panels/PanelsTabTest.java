package dev.marregui.comando.ui.panels;

import com.formdev.flatlaf.FlatDarkLaf;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.ui.PanelTexts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PanelsTabTest {

    private static LookAndFeel previous;

    @BeforeAll
    static void flatLaf() throws Exception {
        previous = UIManager.getLookAndFeel();
        UIManager.setLookAndFeel(new FlatDarkLaf());
    }

    @AfterAll
    static void restore() throws Exception {
        UIManager.setLookAndFeel(previous);
    }

    private static final PanelId MAIN = new PanelId("m");
    private static final PanelId REVIEWS = new PanelId("r");
    private static final PanelId DOC = new PanelId("d");

    /** Texts in memory, with a file per panel named after its id. */
    private static final class Texts implements PanelTexts {
        final Map<PanelId, String> texts = new ConcurrentHashMap<>();

        @Override
        public String load(PanelId panel) {
            return texts.getOrDefault(panel, "");
        }

        @Override
        public void changed(PanelId panel, String text) {
            texts.put(panel, text);
        }

        @Override
        public void forget(PanelId panel) {
        }

        @Override
        public Optional<Path> file(PanelId panel) {
            return Optional.of(Path.of("/data/panels", panel.value() + ".md"));
        }
    }

    private final List<Intent> intents = new CopyOnWriteArrayList<>();
    private final List<String> asked = new CopyOnWriteArrayList<>();
    private final AtomicInteger answer = new AtomicInteger();
    private final Texts texts = new Texts();

    private AppState state() {
        return AppState.initial(List.of(new PanelState(MAIN, "main", "local", "standard"), new PanelState(DOC, "PLAN", "", "", true)),
                List.of(), List.of(SessionState.closed(SessionSpec.fresh("standard", "/w")), SessionState.closed(SessionSpec.fresh("reviews", "/w"))))
                .withClosed(List.of(new PanelState(REVIEWS, "reviews", "local", "reviews")));
    }

    private PanelsTab tab() throws Exception {
        PanelsTab[] t = new PanelsTab[1];
        SwingUtilities.invokeAndWait(() -> {
            t[0] = new PanelsTab(intents::add, texts, (parent, text, title, options) -> {
                asked.add(title + ": " + text + " " + List.of(options));
                return answer.get();
            });
            t[0].render(state());
            t[0].list().setSize(260, 400);
            t[0].list().doLayout();
        });
        return t[0];
    }

    @Test
    void comandosOwnPanelsOpenAndClosedByTitleWithoutDocuments() throws Exception {
        PanelsTab tab = tab();
        JList<PanelsTab.Row> list = tab.list();
        assertThat(list.getModel().getSize()).isEqualTo(2);
        assertThat(List.of(list.getModel().getElementAt(0), list.getModel().getElementAt(1))).containsExactly(
                new PanelsTab.Row(new PanelState(MAIN, "main", "local", "standard"), true),
                new PanelsTab.Row(new PanelState(REVIEWS, "reviews", "local", "reviews"), false));
        String closed = ((JLabel) list.getCellRenderer().getListCellRendererComponent(list, list.getModel().getElementAt(1), 1, false, false)).getText();
        assertThat(closed).contains("reviews").contains("closed").contains("reviews · local · r.md");
    }

    @Test
    void doubleClickReopensAClosedPanelAndBringsAnOpenOneForward() throws Exception {
        JList<PanelsTab.Row> list = tab().list();
        doubleClick(list, 1);
        doubleClick(list, 0);
        assertThat(intents).containsExactly(new Intent.ReopenPanel(REVIEWS), new Intent.FocusPanel(MAIN));
    }

    @Test
    void removeAsksNamingTheFileAndTheSessionAndClosesAnOpenPanelFirst() throws Exception {
        PanelsTab tab = tab();
        assertThat(tab.question(tab.list().getModel().getElementAt(1))).as("a session's own panel goes with its session")
                .isEqualTo("Remove session reviews? This removes its panel reviews with its text.");
        assertThat(tab.question(tab.list().getModel().getElementAt(0)))
                .isEqualTo("Remove session standard? This removes its panel main with its text.");
        assertThat(tab.question(new PanelsTab.Row(new PanelState(new PanelId("n"), "notes", "", ""), true)))
                .as("a panel without a session").isEqualTo("Remove panel notes? Its file n.md is deleted. The panel closes first.");

        answer.set(1);
        SwingUtilities.invokeAndWait(() -> rowItem(tab, 0, "Remove…"));
        assertThat(asked.getLast()).endsWith("[Close and remove, Leave it]");
        assertThat(intents).isEmpty();

        answer.set(0);
        SwingUtilities.invokeAndWait(() -> rowItem(tab, 1, "Remove…"));
        assertThat(asked.getLast()).endsWith("[Remove, Cancel]");
        assertThat(intents).containsExactly(new Intent.RemovePanel(REVIEWS));
    }

    @Test
    void cloneCopiesTheTextAndTheConnectionButNotTheSession() throws Exception {
        texts.changed(REVIEWS, "# notes");
        PanelsTab tab = tab();
        SwingUtilities.invokeAndWait(() -> rowItem(tab, 1, "Clone"));
        assertThat(intents).hasSize(2);
        Intent.AddPanel add = (Intent.AddPanel) intents.get(0);
        assertThat(add.title()).isEqualTo("reviews copy");
        assertThat(texts.load(add.panel())).isEqualTo("# notes");
        assertThat(intents.get(1)).isEqualTo(new Intent.BindConnection(add.panel(), "local"));
    }

    /** Clicks the item named {@code text} in the menu of row {@code index}, as a right click on that row offers it. */
    private static void rowItem(PanelsTab tab, int index, String text) {
        for (var c : tab.menu(tab.list().getModel().getElementAt(index)).getComponents()) {
            if (c instanceof AbstractButton item && text.equals(item.getText())) {
                item.doClick();
                return;
            }
        }
        throw new AssertionError("no item " + text);
    }

    private static void doubleClick(JList<?> list, int index) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Rectangle r = list.getCellBounds(index, index);
            list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_CLICKED, 0, 0, r.x + 10, r.y + r.height / 2, 2, false,
                    MouseEvent.BUTTON1));
        });
    }
}
