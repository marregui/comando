package dev.marregui.comando.ui.kit.resource;

import com.formdev.flatlaf.FlatDarkLaf;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.resource.SessionKind;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.ui.kit.action.ToolButton;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.swing.AbstractButton;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JList;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Component;
import java.awt.Container;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceActionsTest {

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

    private final List<String> opened = new CopyOnWriteArrayList<>();
    private final List<String> removed = new CopyOnWriteArrayList<>();
    private final List<String> asked = new CopyOnWriteArrayList<>();
    private final AtomicInteger answer = new AtomicInteger();
    private final ResourceActions.Editor editor = new ResourceActions.Editor() {
        @Override
        public void add() {
            opened.add("new");
        }

        @Override
        public void edit(String name) {
            opened.add("edit " + name);
        }

        @Override
        public void cloneOf(String name) {
            opened.add("clone " + name);
        }
    };

    @Test
    void addAndWhatBringsResourcesInOnTheLeftAndTheSelectedRowsActionsOnTheRight() throws Exception {
        SessionState edge = SessionState.closed(SessionSpec.fresh("edge", "/w/edge"));
        AppState state = AppState.initial(List.of(new PanelState(new PanelId("p"), "main", "", "")), List.of(), List.of(edge));
        JList<SessionState> list = new JList<>(model(edge));
        JButton more = ToolButton.of("import", "Import…", () -> opened.add("import"));
        Container bar = (Container) actions(list, state, more).component();
        AbstractButton add = button(bar, "New session…");
        AbstractButton edit = button(bar, "Edit the selected session…");
        AbstractButton clone = button(bar, "Clone the selected session…");
        AbstractButton remove = button(bar, "Remove the selected session…");

        SwingUtilities.invokeAndWait(() -> {
            bar.setSize(300, 30);
            layout(bar);
        });
        assertThat(add.getParent()).as("add and import, one group").isSameAs(more.getParent()).isNotSameAs(edit.getParent());
        assertThat(List.of(clone.getParent(), remove.getParent())).allMatch(p -> p == edit.getParent());
        assertThat(add.getParent().getX()).as("on the left").isLessThan(10);
        assertThat(edit.getParent().getX() + edit.getParent().getWidth()).as("on the right").isGreaterThan(290);
        assertThat(more.getX() + more.getWidth()).as("a gap between the groups").isLessThan(edit.getParent().getX());

        assertThat(List.of(edit, clone, remove)).as("nothing selected, nothing to act on").noneMatch(AbstractButton::isEnabled);
        add.doClick();
        more.doClick();
        assertThat(opened).as("+ only adds").containsExactly("new", "import");

        SwingUtilities.invokeAndWait(() -> list.setSelectedIndex(0));
        assertThat(List.of(edit, clone, remove)).allMatch(AbstractButton::isEnabled);
        edit.doClick();
        clone.doClick();
        assertThat(opened).containsExactly("new", "import", "edit edge", "clone edge");

        SwingUtilities.invokeAndWait(list::clearSelection);
        assertThat(List.of(edit, clone, remove)).noneMatch(AbstractButton::isEnabled);
    }

    @Test
    void removeAsksFirstAndOffersToStopAResourceInUseOrLeaveIt() throws Exception {
        SessionState edge = SessionState.closed(SessionSpec.fresh("edge", "/w/edge"));
        SessionState work = SessionState.closed(SessionSpec.fresh("work", "/w/work")).withStatus(Status.OPEN, "");
        AppState state = AppState.initial(List.of(new PanelState(new PanelId("p"), "main", "", "work")), List.of(), List.of(edge, work));
        JList<SessionState> list = new JList<>(model(edge, work));
        ResourceActions<SessionState> actions = actions(list, state);

        answer.set(1);
        actions.remove(edge);
        assertThat(asked.getLast()).isEqualTo("Remove edge: Remove session edge? [Remove, Cancel]");
        assertThat(removed).as("Cancel keeps it").isEmpty();
        answer.set(0);
        actions.remove(edge);
        assertThat(removed).containsExactly("edge");

        removed.clear();
        answer.set(1);
        actions.remove(work);
        assertThat(asked.getLast()).as("a resource in use is stopped first, or left as it is")
                .isEqualTo("Remove work: Remove session work? This stops it and unbinds 1 panel. [Stop and remove, Leave it]");
        assertThat(removed).isEmpty();
        answer.set(-1);
        actions.remove(work);
        assertThat(removed).as("closing the question leaves it too").isEmpty();
        answer.set(0);
        actions.remove(work);
        assertThat(removed).containsExactly("work");

        removed.clear();
        SwingUtilities.invokeAndWait(() -> list.setSelectedIndex(0));
        button((Container) actions.component(), "Remove the selected session…").doClick();
        assertThat(removed).as("the button removes the selected row, with the same question").containsExactly("edge");
    }

    private ResourceActions<SessionState> actions(JList<SessionState> list, AppState state, JButton... more) throws Exception {
        AtomicReference<ResourceActions<SessionState>> a = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> a.set(new ResourceActions<>(list,
                ResourceActions.Rows.of(SessionKind.INSTANCE, SessionState::name, SessionState::status, "Stop", () -> state),
                editor, removed::add, (parent, text, title, options) -> {
                    asked.add(title + ": " + text + " " + List.of(options));
                    return answer.get();
                }, more)));
        return a.get();
    }

    private static DefaultListModel<SessionState> model(SessionState... items) {
        DefaultListModel<SessionState> m = new DefaultListModel<>();
        m.addAll(List.of(items));
        return m;
    }

    private static void layout(Component c) {
        if (c instanceof Container k) {
            k.doLayout();
            for (Component child : k.getComponents()) {
                layout(child);
            }
        }
    }

    private static AbstractButton button(Container root, String name) {
        for (Component c : root.getComponents()) {
            if (c instanceof AbstractButton b && name.equals(b.getAccessibleContext().getAccessibleName())) {
                return b;
            }
            if (c instanceof Container k) {
                AbstractButton found = button(k, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
