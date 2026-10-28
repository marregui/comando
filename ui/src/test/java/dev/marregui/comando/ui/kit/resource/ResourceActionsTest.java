package dev.marregui.comando.ui.kit.resource;

import com.formdev.flatlaf.FlatDarkLaf;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.resource.SessionKind;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.swing.DefaultListModel;
import javax.swing.JList;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/// [ResourceActions] adds, edits, clones and removes the resources of a list, asking before a removal.
/// - Add opens the editor on a new resource.
/// - Remove asks first; Cancel or closing the question keeps the resource.
/// - A resource in use is offered to be stopped and removed, naming the panels it takes with it, or left.
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

    /// [ResourceActions#addNew] calls the editor's add once.
    @Test
    void addOpensTheEditorOnANewResource() throws Exception {
        AppState state = AppState.initial(List.of(new PanelState(new PanelId("p"), "main", "", "")), List.of(), List.of());
        actions(new JList<>(), state).addNew();
        assertThat(opened).containsExactly("new");
    }

    /// A closed session asks `Remove, Cancel` and is removed only on the first; an open session's question names its
    /// panel, offers `Stop and remove, Leave it`, and closing the dialog (-1) leaves it like `Leave it`.
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
                .isEqualTo("Remove work: Remove session work? This stops it and removes its panel main with its text. [Stop and remove, Leave it]");
        assertThat(removed).isEmpty();
        answer.set(-1);
        actions.remove(work);
        assertThat(removed).as("closing the question leaves it too").isEmpty();
        answer.set(0);
        actions.remove(work);
        assertThat(removed).containsExactly("work");
    }

    private ResourceActions<SessionState> actions(JList<SessionState> list, AppState state) throws Exception {
        AtomicReference<ResourceActions<SessionState>> a = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> a.set(new ResourceActions<>(list,
                ResourceActions.Rows.of(SessionKind.INSTANCE, SessionState::name, SessionState::status, "Stop", () -> state),
                editor, removed::add, (parent, text, title, options) -> {
                    asked.add(title + ": " + text + " " + List.of(options));
                    return answer.get();
                })));
        return a.get();
    }

    private static DefaultListModel<SessionState> model(SessionState... items) {
        DefaultListModel<SessionState> m = new DefaultListModel<>();
        m.addAll(List.of(items));
        return m;
    }
}
