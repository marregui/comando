package dev.marregui.comando.ui.kit.resource;

import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.resource.ResourceKind;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.ui.kit.action.ToolButton;
import dev.marregui.comando.ui.kit.text.Plain;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The buttons above a list of resources (sessions, connections), in two groups: on the left, add and whatever else
 * brings resources in; on the right, edit, clone and remove, which act on the list's selected row and are off while
 * none is selected. Remove asks first, naming what removal does: a resource in use (neither closed nor failed) offers
 * "Stop and remove" (or "Disconnect and remove") or "Leave it", one not in use "Remove" or "Cancel". The safe answer is
 * the default, and closing the question gives it.
 *
 * @param <T> the list's rows
 */
public final class ResourceActions<T> {

    /** What the buttons open: the resource dialog on a new resource, on a stored one, or on an unsaved copy of one. */
    public interface Editor {
        void add();

        void edit(String name);

        void cloneOf(String name);
    }

    /** Asks a question with buttons; answers the index of the one chosen, -1 when closed. Tests answer for the user. */
    public interface Questions {
        Questions MODAL = (parent, text, title, options) -> JOptionPane.showOptionDialog(parent, text, title,
                JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[options.length - 1]);

        int ask(Component parent, String text, String title, Object[] options);
    }

    /**
     * The rows of the list as resources.
     *
     * @param kind      the kind: its noun names the buttons, its removal question is asked
     * @param name      a row's resource name
     * @param status    a row's status: in use unless closed or failed
     * @param closeVerb what ends the use ("Stop", "Disconnect"), for the removal of a resource in use
     */
    public record Rows<T>(ResourceKind<?> kind, Function<T, String> name, Function<T, Status> status, String closeVerb) {
    }

    private final JList<T> list;
    private final Rows<T> rows;
    private final Supplier<AppState> state;
    private final Consumer<String> remove;
    private final Questions questions;
    private final List<JButton> onSelection = new ArrayList<>();
    private final JPanel bar = new JPanel(new BorderLayout());

    /**
     * @param remove    removes the named resource, once the user said so
     * @param moreLeft  buttons after add, in the left group (import)
     */
    public ResourceActions(JList<T> list, Rows<T> rows, Supplier<AppState> state, Editor editor, Consumer<String> remove,
                           Questions questions, JButton... moreLeft) {
        this.list = list;
        this.rows = rows;
        this.state = state;
        this.remove = remove;
        this.questions = questions;
        String noun = rows.kind().noun();
        List<JButton> left = new ArrayList<>();
        left.add(ToolButton.of("add", "New " + noun + "…", editor::add));
        left.addAll(List.of(moreLeft));
        onSelection.add(ToolButton.of("edit", "Edit the selected " + noun + "…", () -> selected(t -> editor.edit(rows.name().apply(t)))));
        onSelection.add(ToolButton.of("copy", "Clone the selected " + noun + "…", () -> selected(t -> editor.cloneOf(rows.name().apply(t)))));
        onSelection.add(ToolButton.of("close", "Remove the selected " + noun + "…", () -> selected(this::remove)));
        bar.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        bar.add(group(FlowLayout.LEFT, left), BorderLayout.WEST);
        bar.add(group(FlowLayout.RIGHT, onSelection), BorderLayout.EAST);
        list.addListSelectionListener(e -> enable());
        enable();
    }

    /** The bar: the left group at the left edge, the right group at the right edge. */
    public JComponent component() {
        return bar;
    }

    /** Removes {@code row} after asking; the context menu's Remove does the same. */
    public void remove(T row) {
        AppState s = state.get();
        if (s == null) {
            return;
        }
        String name = rows.name().apply(row);
        Status st = rows.status().apply(row);
        boolean inUse = st != Status.CLOSED && st != Status.FAILED;
        Object[] options = inUse ? new Object[]{rows.closeVerb() + " and remove", "Leave it"} : new Object[]{"Remove", "Cancel"};
        if (questions.ask(list, Plain.text(rows.kind().removalQuestion(s, name)), "Remove " + Plain.text(name), options) == 0) {
            remove.accept(name);
        }
    }

    private void selected(Consumer<T> action) {
        T t = list.getSelectedValue();
        if (t != null) {
            action.accept(t);
        }
    }

    private void enable() {
        boolean any = list.getSelectedValue() != null;
        onSelection.forEach(b -> b.setEnabled(any));
    }

    private static JPanel group(int align, List<JButton> buttons) {
        JPanel g = new JPanel(new FlowLayout(align, 0, 0));
        buttons.forEach(g::add);
        return g;
    }
}
