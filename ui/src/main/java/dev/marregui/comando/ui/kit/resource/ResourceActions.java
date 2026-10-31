package dev.marregui.comando.ui.kit.resource;

import java.awt.Component;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.swing.JOptionPane;

import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.resource.ResourceKind;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.ui.kit.text.Plain;

/**
 * What a list of resources (sessions, connections, panels) does besides its rows' own actions: add one, which its tab's
 * menu offers, and remove one, which each row's menu offers with edit and clone. Remove asks first, naming what removal
 * does: a resource in use (a running session, an open connection or panel) offers "Stop and remove" ("Disconnect and
 * remove", "Close and remove") or "Leave it", one not in use "Remove" or "Cancel". The safe answer is the default, and
 * closing the question gives it. The list has no bar of buttons (record 74).
 *
 * @param <T> the list's rows
 */
public final class ResourceActions<T> {

    /** What the menus open: the resource dialog on a new resource, on a stored one, or on an unsaved copy of one. */
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
     * @param noun      names the resource ("session", "panel")
     * @param name      what identifies a row to the editor and to remove
     * @param label     a row as the user knows it, in the removal question's title
     * @param inUse     whether removing a row ends a use first, which the question then offers
     * @param closeVerb what ends the use ("Stop", "Disconnect", "Close")
     * @param question  the removal question, naming what removal does
     */
    public record Rows<T>(String noun, Function<T, String> name, Function<T, String> label, Predicate<T> inUse,
                          String closeVerb, Function<T, String> question) {

        /**
         * Rows of a resource kind: named by their resource name, in use unless closed or failed, asked its question.
         */
        public static <T> Rows<T> of(final ResourceKind<?> kind, final Function<T, String> name, final Function<T, Status> status, final String closeVerb,
                                     final Supplier<AppState> state) {
            return new Rows<>(kind.noun(), name, name, t -> status.apply(t) != Status.CLOSED && status.apply(t) != Status.FAILED,
                    closeVerb, t -> kind.removalQuestion(state.get(), name.apply(t)));
        }
    }

    private final Component parent;
    private final Rows<T> rows;
    private final Editor editor;
    private final Consumer<String> remove;
    private final Questions questions;

    /**
     * @param parent what the removal question is shown over
     * @param remove removes the named resource, once the user said so
     */
    public ResourceActions(final Component parent, final Rows<T> rows, final Editor editor, final Consumer<String> remove, final Questions questions) {
        this.parent = parent;
        this.rows = rows;
        this.editor = editor;
        this.remove = remove;
        this.questions = questions;
    }

    /** Opens the editor on a new resource: the tab menu's New…. */
    public void addNew() {
        editor.add();
    }

    /** Removes {@code row} after asking: the row menu's Remove…. */
    public void remove(final T row) {
        final Object[] options = rows.inUse().test(row) ? new Object[]{rows.closeVerb() + " and remove", "Leave it"} : new Object[]{"Remove", "Cancel"};
        if (questions.ask(parent, Plain.text(rows.question().apply(row)), "Remove " + Plain.text(rows.label().apply(row)), options) == 0) {
            remove.accept(rows.name().apply(row));
        }
    }
}
