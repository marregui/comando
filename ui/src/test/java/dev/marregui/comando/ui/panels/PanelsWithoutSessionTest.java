package dev.marregui.comando.ui.panels;

import com.formdev.flatlaf.FlatDarkLaf;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.ui.PanelTexts;
import dev.marregui.comando.ui.kit.resource.ResourceActions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PanelsWithoutSessionTest {

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
    private static final PanelId NOTES = new PanelId("n");
    private static final PanelId SCRATCH = new PanelId("s");
    private static final PanelId GHOST = new PanelId("g");

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
    private final List<String> newSessions = new CopyOnWriteArrayList<>();
    private final AtomicInteger answer = new AtomicInteger();
    private final Texts texts = new Texts();

    private static final List<SessionState> SESSIONS = List.of(SessionState.closed(SessionSpec.fresh("standard", "/w")),
            SessionState.closed(SessionSpec.fresh("reviews", "/w")));

    /** Two sessions' own panels, a document, a panel without a session open, one closed, and one whose session is gone. */
    private static AppState state() {
        return AppState.initial(List.of(new PanelState(MAIN, "main", "local", "standard"), new PanelState(DOC, "PLAN", "", "", true),
                        new PanelState(NOTES, "notes", "", "")), List.of(), SESSIONS)
                .withClosed(List.of(new PanelState(REVIEWS, "reviews", "local", "reviews"), new PanelState(SCRATCH, "scratch", "local", ""),
                        new PanelState(GHOST, "ghost", "", "gone")));
    }

    private PanelsWithoutSession section(AppState state) throws Exception {
        PanelsWithoutSession[] t = new PanelsWithoutSession[1];
        SwingUtilities.invokeAndWait(() -> {
            t[0] = new PanelsWithoutSession(intents::add, texts, newSessions::add, (parent, text, title, options) -> {
                asked.add(title + ": " + text + " " + List.of(options));
                return answer.get();
            });
            t[0].render(state);
            t[0].list().setSize(260, 400);
            t[0].list().doLayout();
        });
        return t[0];
    }

    @Test
    void comandosOwnPanelsWithoutASessionOpenAndClosedByTitle() throws Exception {
        PanelsWithoutSession section = section(state());
        JList<PanelsWithoutSession.Row> list = section.list();
        assertThat(rows(list)).as("no session's own panel, no document; a session that is gone counts as none").containsExactly(
                new PanelsWithoutSession.Row(new PanelState(GHOST, "ghost", "", "gone"), false),
                new PanelsWithoutSession.Row(new PanelState(NOTES, "notes", "", ""), true),
                new PanelsWithoutSession.Row(new PanelState(SCRATCH, "scratch", "local", ""), false));
        assertThat(section.component().isVisible()).isTrue();
        // the renderer's label is shared with the list's own layout, which runs on the EDT
        String[] closed = new String[1];
        SwingUtilities.invokeAndWait(() -> closed[0] = ((JLabel) list.getCellRenderer()
                .getListCellRendererComponent(list, list.getModel().getElementAt(2), 2, false, false)).getText());
        assertThat(closed[0]).contains("scratch").contains("closed").contains("local · s.md");
    }

    @Test
    void theSectionHidesWhileEveryPanelIsASessionsOwn() throws Exception {
        AppState owned = AppState.initial(List.of(new PanelState(MAIN, "main", "local", "standard"), new PanelState(DOC, "PLAN", "", "", true)),
                List.of(), SESSIONS).withClosed(List.of(new PanelState(REVIEWS, "reviews", "local", "reviews")));
        PanelsWithoutSession section = section(state());
        SwingUtilities.invokeAndWait(() -> section.render(owned));
        assertThat(section.list().getModel().getSize()).isZero();
        assertThat(section.component().isVisible()).as("as the tab looked before: sessions alone").isFalse();
        SwingUtilities.invokeAndWait(() -> section.render(state()));
        assertThat(section.component().isVisible()).as("back once a panel has no session").isTrue();
    }

    @Test
    void theSectionSitsUnderTheSessionsInTheirTab() throws Exception {
        SessionsSidebar[] s = new SessionsSidebar[1];
        SwingUtilities.invokeAndWait(() -> {
            s[0] = new SessionsSidebar(intents::add, new SessionsSidebar.Dialogs() {
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
                public void exploreSchema(String connection) {
                }

                @Override
                public void openFile(Path file) {
                }

                @Override
                public void newSession(String name) {
                    newSessions.add(name);
                }
            }, () -> {
            });
            s[0].render(state());
        });
        PanelsWithoutSession section = s[0].loosePanels();
        JPanel tab = (JPanel) section.component().getParent();
        assertThat(((BorderLayout) tab.getLayout()).getConstraints(section.component())).isEqualTo(BorderLayout.SOUTH);
        JScrollPane above = (JScrollPane) ((BorderLayout) tab.getLayout()).getLayoutComponent(BorderLayout.CENTER);
        assertThat(above.getViewport().getView().getAccessibleContext().getAccessibleName()).isEqualTo("Sessions");
        SwingUtilities.invokeAndWait(() -> rowItem(section, 2, "New session scratch…"));
        assertThat(newSessions).as("the sidebar's dialog, named after the panel").containsExactly("scratch");
    }

    @Test
    void doubleClickReopensAClosedPanelAndBringsAnOpenOneForward() throws Exception {
        JList<PanelsWithoutSession.Row> list = section(state()).list();
        doubleClick(list, 2);
        doubleClick(list, 1);
        assertThat(intents).containsExactly(new Intent.ReopenPanel(SCRATCH), new Intent.FocusPanel(NOTES));
    }

    @Test
    void aRowsMenuOpensRenamesMakesASessionAndRemovesButDoesNotClone() throws Exception {
        PanelsWithoutSession section = section(state());
        List<String> items = new ArrayList<>();
        for (Component c : section.menu(section.list().getModel().getElementAt(2)).getComponents()) {
            items.add(c instanceof AbstractButton b ? b.getText() : "—");
        }
        assertThat(items).containsExactly("Open", "—", "Rename…", "New session scratch…", "—", "Remove…");
        SwingUtilities.invokeAndWait(() -> rowItem(section, 2, "New session scratch…"));
        assertThat(newSessions).containsExactly("scratch");
        assertThat(intents).as("the dialog makes the session; saved, the panel becomes its own").isEmpty();
    }

    @Test
    void removeAsksNamingTheFileAndClosesAnOpenPanelFirst() throws Exception {
        PanelsWithoutSession section = section(state());
        assertThat(section.question(section.list().getModel().getElementAt(1)))
                .isEqualTo("Remove panel notes? Its file n.md is deleted. The panel closes first.");
        assertThat(section.question(section.list().getModel().getElementAt(2))).isEqualTo("Remove panel scratch? Its file s.md is deleted.");

        answer.set(1);
        SwingUtilities.invokeAndWait(() -> rowItem(section, 1, "Remove…"));
        assertThat(asked.getLast()).endsWith("[Close and remove, Leave it]");
        assertThat(intents).isEmpty();

        answer.set(0);
        SwingUtilities.invokeAndWait(() -> rowItem(section, 2, "Remove…"));
        assertThat(asked.getLast()).endsWith("[Remove, Cancel]");
        assertThat(intents).containsExactly(new Intent.RemovePanel(SCRATCH));
    }

    private static List<PanelsWithoutSession.Row> rows(JList<PanelsWithoutSession.Row> list) {
        List<PanelsWithoutSession.Row> rows = new ArrayList<>();
        for (int i = 0; i < list.getModel().getSize(); i++) {
            rows.add(list.getModel().getElementAt(i));
        }
        return rows;
    }

    /** Clicks the item named {@code text} in the menu of row {@code index}, as a right click on that row offers it. */
    private static void rowItem(PanelsWithoutSession section, int index, String text) {
        for (var c : section.menu(section.list().getModel().getElementAt(index)).getComponents()) {
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
