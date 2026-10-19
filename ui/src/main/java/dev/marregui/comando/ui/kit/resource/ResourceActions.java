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
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The buttons above a list of resources (sessions, connections, panels), in two groups: on the left, add and whatever
 * else brings resources in; on the right, edit, clone and remove, which act on the selected row and are off while none
 * is selected. Remove asks first, naming what removal does: a resource in use (a running session, an open
 * connection or panel) offers "Stop and remove" ("Disconnect and remove", "Close and remove") or "Leave it", one not
 * in use "Remove" or "Cancel". The safe answer is the default, and closing the question gives it.
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

    /** Where the selected row comes from. */
    public interface Selection<T> {
        /** The selected row, or null. */
        T selected();

        /** Calls {@code listener} whenever the selection may have changed. */
        void onChange(Runnable listener);

        /** What the removal question is shown over. */
        Component component();

        static <T> Selection<T> of(JList<T> list) {
            return new Selection<>() {
                @Override
                public T selected() {
                    return list.getSelectedValue();
                }

                @Override
                public void onChange(Runnable listener) {
                    list.addListSelectionListener(e -> listener.run());
                }

                @Override
                public Component component() {
                    return list;
                }
            };
        }
    }

    /**
     * The rows of the list as resources.
     *
     * @param noun      names the buttons ("session", "panel")
     * @param name      what identifies a row to the editor and to remove
     * @param label     a row as the user knows it, in the removal question's title
     * @param inUse     whether removing a row ends a use first, which the question then offers
     * @param closeVerb what ends the use ("Stop", "Disconnect", "Close")
     * @param question  the removal question, naming what removal does
     */
    public record Rows<T>(String noun, Function<T, String> name, Function<T, String> label, Predicate<T> inUse,
                          String closeVerb, Function<T, String> question) {

        /** Rows of a resource kind: named by their resource name, in use unless closed or failed, asked the kind's question. */
        public static <T> Rows<T> of(ResourceKind<?> kind, Function<T, String> name, Function<T, Status> status, String closeVerb,
                                     Supplier<AppState> state) {
            return new Rows<>(kind.noun(), name, name, t -> status.apply(t) != Status.CLOSED && status.apply(t) != Status.FAILED,
                    closeVerb, t -> kind.removalQuestion(state.get(), name.apply(t)));
        }
    }

    private final Selection<T> selection;
    private final Rows<T> rows;
    private final Consumer<String> remove;
    private final Questions questions;
    private final JButton add;
    private final JButton edit;
    private final JButton cloneOf;
    private final JButton removal;
    private final String editTip;
    private final String cloneTip;
    private final String removeTip;
    private final List<JButton> left = new ArrayList<>();
    private final List<JButton> onSelection = new ArrayList<>();
    private final JPanel bar = new JPanel(new BorderLayout());

    /**
     * @param remove    removes the named resource, once the user said so
     * @param moreLeft  buttons after add, in the left group (import)
     */
    public ResourceActions(JList<T> list, Rows<T> rows, Editor editor, Consumer<String> remove, Questions questions,
                           JButton... moreLeft) {
        this(Selection.of(list), rows, editor, remove, questions, moreLeft);
    }

    private ResourceActions(Selection<T> selection, Rows<T> rows, Editor editor, Consumer<String> remove, Questions questions,
                           JButton... moreLeft) {
        this.selection = selection;
        this.rows = rows;
        this.remove = remove;
        this.questions = questions;
        String noun = rows.noun();
        add = ToolButton.of("add", "New " + noun + "…", editor::add);
        left.add(add);
        left.addAll(List.of(moreLeft));
        editTip = "Edit the selected " + noun + "…";
        cloneTip = "Clone the selected " + noun + "…";
        removeTip = "Remove the selected " + noun + "…";
        edit = ToolButton.of("edit", editTip, () -> act(t -> editor.edit(rows.name().apply(t))));
        cloneOf = ToolButton.of("copy", cloneTip, () -> act(t -> editor.cloneOf(rows.name().apply(t))));
        removal = ToolButton.of("close", removeTip, () -> act(this::remove));
        onSelection.addAll(List.of(edit, cloneOf, removal));
        bar.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        bar.add(group(FlowLayout.LEFT, left), BorderLayout.WEST);
        bar.add(group(FlowLayout.RIGHT, onSelection), BorderLayout.EAST);
        selection.onChange(this::enable);
        enable();
    }

    /** Adds a resource, as the bar's + does; a tab's menu offers it too. */
    public void addNew() {
        add.doClick();
    }

    /** The bar: the left group at the left edge, the right group at the right edge. */
    public JComponent component() {
        return bar;
    }

    /** Removes {@code row} after asking; the context menu's Remove does the same. */
    public void remove(T row) {
        Object[] options = rows.inUse().test(row) ? new Object[]{rows.closeVerb() + " and remove", "Leave it"} : new Object[]{"Remove", "Cancel"};
        if (questions.ask(selection.component(), Plain.text(rows.question().apply(row)), "Remove " + Plain.text(rows.label().apply(row)), options) == 0) {
            remove.accept(rows.name().apply(row));
        }
    }

    /** A button's click: {@code onRow} on the selected resource. */
    private void act(Consumer<T> onRow) {
        T t = selection.selected();
        if (t != null) {
            onRow.accept(t);
        }
    }

    private void enable() {
        boolean any = selection.selected() != null;
        show(edit, editTip, any);
        show(cloneOf, cloneTip, any);
        show(removal, removeTip, any);
    }

    /** {@code tip} on the button, as its accessible name too, and the button on or off. */
    private static void show(JButton b, String tip, boolean on) {
        b.setToolTipText(tip);
        b.getAccessibleContext().setAccessibleName(tip);
        b.setEnabled(on);
    }

    private static JPanel group(int align, List<JButton> buttons) {
        JPanel g = new JPanel(new FlowLayout(align, 0, 0));
        buttons.forEach(g::add);
        return g;
    }
}
