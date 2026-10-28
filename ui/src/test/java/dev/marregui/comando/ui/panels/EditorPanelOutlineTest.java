package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.schema.SchemaRegistry;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.ui.kit.text.MarkdownHtml;
import dev.marregui.comando.ui.kit.theme.SidebarLook;
import dev.marregui.comando.ui.kit.theme.Theme;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.SwingUtilities;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** A Markdown document's outline at the right of its panel (record 113). */
class EditorPanelOutlineTest {

    private static final String DOC = """
            ---
            name: plan
            ---
            # Plan

            Intro.

            ## Scope

            ```
            # not a heading
            ```

            ## Scope
            ### Detail
            """;

    private static EditorPanel panel(String title, Path file, String text) throws Exception {
        EditorPanel[] p = new EditorPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            p[0] = new EditorPanel(new PanelState(new PanelId(title), title, "", ""), intent -> { }, new SchemaRegistry());
            p[0].editor().load(text);
            p[0].file(file, true);
            p[0].setSize(900, 600);
            p[0].doLayout();
        });
        return p[0];
    }

    private static JButton button(EditorPanel p) {
        return find(p, "Outline");
    }

    private static JButton find(java.awt.Container c, String name) {
        for (java.awt.Component k : c.getComponents()) {
            if (k instanceof JButton b && name.equals(b.getAccessibleContext().getAccessibleName())) {
                return b;
            }
            if (k instanceof java.awt.Container sub) {
                JButton f = find(sub, name);
                if (f != null) {
                    return f;
                }
            }
        }
        return null;
    }

    @Test
    void theOutlineListsTheHeadingsWithTheRenderedPagesIdsAndItsButtonRemembersTheChoice() throws Exception {
        List<Boolean> kept = new CopyOnWriteArrayList<>();
        EditorPanel p = panel("plan.md", Path.of("/d/plan.md"), DOC);
        SwingUtilities.invokeAndWait(() -> p.outline(false, kept::add));
        assertThat(button(p).isVisible()).as("a Markdown document has the button").isTrue();
        assertThat(p.outlineShows()).isFalse();

        SwingUtilities.invokeAndWait(() -> button(p).doClick());
        assertThat(p.outlineShows()).isTrue();
        assertThat(kept).as("the next panel opens the same way").containsExactly(true);
        Awaitility.await("the headings read").atMost(Duration.ofSeconds(5)).until(() -> p.outlinePane().sections().size() == 4);
        assertThat(p.outlinePane().sections()).containsExactly(
                new MarkdownHtml.Section(1, "Plan", "plan", 3),
                new MarkdownHtml.Section(2, "Scope", "scope", 7),
                new MarkdownHtml.Section(2, "Scope", "scope-1", 13),
                new MarkdownHtml.Section(3, "Detail", "detail", 14));
        String html = MarkdownHtml.render(DOC);
        assertThat(html).as("the ids the rendered page gives").contains("id=\"plan\"", "id=\"scope\"", "id=\"scope-1\"", "id=\"detail\"");

        // typing changes the headings: read again once it pauses
        SwingUtilities.invokeAndWait(() -> p.editor().area().append("## Added\n"));
        Awaitility.await("read again").atMost(Duration.ofSeconds(5))
                .until(() -> p.outlinePane().sections().stream().anyMatch(s -> s.text().equals("Added")));

        SwingUtilities.invokeAndWait(() -> button(p).doClick());
        assertThat(p.outlineShows()).isFalse();
        assertThat(kept).containsExactly(true, false);
    }

    @Test
    void aDoubleClickOnAHeadingPutsItsLineAtTheTopOfTheText() throws Exception {
        EditorPanel p = panel("plan.md", Path.of("/d/plan.md"), DOC + "\nfiller\n".repeat(200) + "## Far\n");
        SwingUtilities.invokeAndWait(() -> {
            p.outline(true, on -> { });
            p.edit();
            p.setSize(900, 300);
            p.validate();
        });
        Awaitility.await("the headings read").atMost(Duration.ofSeconds(5))
                .until(() -> p.outlinePane().sections().stream().anyMatch(s -> s.text().equals("Far")));
        int far = p.outlinePane().sections().size() - 1;
        int line = p.outlinePane().sections().get(far).line();
        SwingUtilities.invokeAndWait(() -> {
            var list = p.outlinePane().list();
            list.setSize(200, 400);
            var r = list.getCellBounds(far, far);
            list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_CLICKED, 0, 0, r.x + 5, r.y + 2, 1, false, MouseEvent.BUTTON1));
        });
        assertThat(p.editor().area().getCaretLineNumber()).as("a single click goes nowhere").isZero();
        SwingUtilities.invokeAndWait(() -> {
            var list = p.outlinePane().list();
            var r = list.getCellBounds(far, far);
            list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_CLICKED, 0, 0, r.x + 5, r.y + 2, 2, false, MouseEvent.BUTTON1));
        });
        assertThat(p.editor().area().getCaretLineNumber()).isEqualTo(line);
    }

    @Test
    void theOutlineIsInTheSidebarsLook() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            OutlinePane o = new OutlinePane();
            javax.swing.JList<?> l = o.list();
            assertThat(l.getBackground()).as("the theme's ground").isEqualTo(Theme.ground());
            assertThat(l.getForeground()).as("ivory names").isEqualTo(SidebarLook.TEXT);
            assertThat(l.getSelectionBackground()).as("a band of the tint").isEqualTo(Theme.tint().tableHeadGround());
            assertThat(l.getSelectionForeground()).as("the name in its highlight").isEqualTo(Theme.tint().highlight);
            assertThat(o.component().getBackground()).as("the pane's ground").isEqualTo(Theme.ground());
        });
    }

    @Test
    void textThatDoesNotRenderAsAPageHasNoOutline() throws Exception {
        EditorPanel p = panel("query.sql", Path.of("/d/query.sql"), "select 1;\n");
        SwingUtilities.invokeAndWait(() -> p.outline(true, on -> { }));
        assertThat(button(p).isVisible()).isFalse();
        assertThat(p.outlineShows()).as("wanted, but a SQL file is no page").isFalse();
    }
}
