package dev.marregui.comando.ui.widgets.editor;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Rectangle2D;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import javax.swing.AbstractAction;
import javax.swing.AbstractListModel;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.JWindow;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.event.CaretListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;

import org.jetbrains.annotations.TestOnly;

import dev.marregui.comando.ui.kit.async.Debouncer;
import dev.marregui.comando.ui.kit.text.MarkdownHtml;
import dev.marregui.comando.ui.kit.theme.Theme;

/**
 * The list of words that complete the one before a text area's caret, in a window of its own under the caret. It opens
 * when asked ({@link #TRIGGER}), or on its own once typing pauses after a letter while the area's {@code autoOpens}
 * holds, and lists the words the typed prefix starts, ignoring case, the first one selected; a single word left is
 * listed, never put in unasked. While it shows it follows the prefix as it grows or shrinks and takes these keys from
 * the area: Up and Down move and wrap, Page Up and Page Down move by the list's visible rows, Home and End go to the
 * ends, Enter, Tab or a double-click put the selected word in place of the prefix, Escape closes it; the area's own
 * bindings come back when it closes. It closes when the caret leaves the word, the area loses focus or stops showing,
 * or the area's window moves, resizes, hides or loses focus. EDT only.
 */
final class CompletionPopup {

    /** How long typing pauses before the list opens on its own: the delay the editor's completion has always had. */
    static final int DELAY_MILLIS = 400;
    /** The key that asks for the list anywhere in the area. */
    static final KeyStroke TRIGGER = KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK);

    /** The trigger's action in the area's action map. */
    private static final String OPEN = "completion.open";
    /** A row's padding: the command palette's. */
    private static final Border PADDING = BorderFactory.createEmptyBorder(4, 8, 4, 8);
    /** The window's size: the size the editor's completion list has always had. */
    private static final Dimension SIZE = new Dimension(350, 200);

    /** The list's actions by their name in the area's action map: the trigger's and those of {@link #keys}. */
    private final Map<String, Action> actions = new LinkedHashMap<>();
    private final CaretListener caret = e -> caretMoved();
    /** Moves {@link #typing} to the document the area is given. */
    private final PropertyChangeListener documents = this::documentReplaced;
    private final FocusAdapter focus = new FocusAdapter() {
        @Override
        public void focusLost(final FocusEvent e) {
            pause.stop();
            close();
        }
    };
    private final HierarchyListener hierarchy = this::hierarchyChanged;
    /** The keys the list takes from the area while it shows, with their action names. */
    private final Map<KeyStroke, String> keys = new LinkedHashMap<>();
    private final Choices model = new Choices();
    private final JList<Completion> list = new JList<>(model);
    private final PropertyChangeListener lookAndFeel = this::lookAndFeelChanged;
    private final WindowAdapter ownerFocus = new WindowAdapter() {
        @Override
        public void windowLostFocus(final WindowEvent e) {
            close();
        }
    };
    private final ComponentAdapter ownerMoves = new ComponentAdapter() {
        @Override
        public void componentMoved(final ComponentEvent e) {
            close();
        }

        @Override
        public void componentResized(final ComponentEvent e) {
            close();
        }

        @Override
        public void componentHidden(final ComponentEvent e) {
            close();
        }
    };
    private final JScrollPane pane = new JScrollPane(list);
    /** The pause after a letter typed, after which the list opens on its own. */
    private final Debouncer pause = new Debouncer(DELAY_MILLIS, this::refresh);
    /** The area's own bindings of {@link #keys}, kept while the list holds them. */
    private final List<Saved> saved = new ArrayList<>();
    private final DocumentListener typing = new DocumentListener() {
        @Override
        public void insertUpdate(final DocumentEvent e) {
            typed(e);
        }

        @Override
        public void removeUpdate(final DocumentEvent e) {
            pause.stop();
        }

        @Override
        public void changedUpdate(final DocumentEvent e) {
            // attributes only: the text, and so the prefix, is the same
        }
    };
    private final Completions words;

    private JTextComponent area;
    private BooleanSupplier autoOpens = () -> true;
    /** Set by a letter typed that starts the pause, so the caret move that follows it does not stop the pause. */
    private boolean justInserted;
    private boolean open;
    private Window owner;
    /** The area's own binding of {@link #TRIGGER}, given back by {@link #uninstall}. */
    private Saved trigger;
    private JWindow window;
    /** Where the word being completed starts; -1 while the list is closed. */
    private int wordStart = -1;

    CompletionPopup(final Completions words) {
        this.words = words;
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFocusable(false);
        list.setBackground(Theme.CARD);
        list.setCellRenderer(new Row());
        // rows are HTML labels: a prototype measures one row, where the list would lay out every word's HTML to size
        // itself, thousands of them for a large schema
        list.setPrototypeCellValue(new Completion("SHOW COLUMNS FROM", "keyword"));
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(final MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    insert();
                }
            }
        });
        pane.setBorder(BorderFactory.createLineBorder(Theme.CARD_BORDER));
        actions.put(OPEN, action(this::refresh));
        take(KeyEvent.VK_ESCAPE, "completion.close", this::close);
        take(KeyEvent.VK_UP, "completion.up", () -> step(-1));
        take(KeyEvent.VK_DOWN, "completion.down", () -> step(1));
        take(KeyEvent.VK_PAGE_UP, "completion.pageUp", () -> page(-1));
        take(KeyEvent.VK_PAGE_DOWN, "completion.pageDown", () -> page(1));
        take(KeyEvent.VK_HOME, "completion.first", () -> select(0));
        take(KeyEvent.VK_END, "completion.last", () -> select(model.getSize() - 1));
        take(KeyEvent.VK_ENTER, "completion.insert", this::insert);
        take(KeyEvent.VK_TAB, "completion.insertTab", this::insert);
    }

    /**
     * Completes in {@code area}: binds {@link #TRIGGER} and listens to its typing, caret and focus, and to look and
     * feel changes. The list opens on its own only while {@code autoOpens} holds; installed on another area first, it
     * leaves that one.
     */
    void install(final JTextComponent area, final BooleanSupplier autoOpens) {
        uninstall();
        this.area = area;
        this.autoOpens = autoOpens;
        final InputMap map = area.getInputMap(JComponent.WHEN_FOCUSED);
        trigger = Saved.of(map, TRIGGER);
        map.put(TRIGGER, OPEN);
        actions.forEach(area.getActionMap()::put);
        area.getDocument().addDocumentListener(typing);
        area.addPropertyChangeListener("document", documents);
        area.addCaretListener(caret);
        area.addFocusListener(focus);
        area.addHierarchyListener(hierarchy);
        UIManager.addPropertyChangeListener(lookAndFeel);
    }

    /** Closes the list, gives the area back its keys and removes every listener, the global one too; idempotent. */
    void uninstall() {
        final JTextComponent a = area;
        if (a == null) {
            return;
        }
        close();
        pause.stop();
        justInserted = false;
        area = null;
        trigger.restore(a.getInputMap(JComponent.WHEN_FOCUSED));
        trigger = null;
        actions.keySet().forEach(a.getActionMap()::remove);
        a.getDocument().removeDocumentListener(typing);
        a.removePropertyChangeListener("document", documents);
        a.removeCaretListener(caret);
        a.removeFocusListener(focus);
        a.removeHierarchyListener(hierarchy);
        UIManager.removePropertyChangeListener(lookAndFeel);
        dropWindow();
    }

    /** Whether the list shows (headless: whether it would). */
    @TestOnly
    boolean isOpen() {
        return open;
    }

    /** Whether a letter typed waits for the pause to open the list. */
    @TestOnly
    boolean pending() {
        return pause.pending();
    }

    /** The list of words offered. */
    @TestOnly
    JList<Completion> list() {
        return list;
    }

    private static Action action(final Runnable run) {
        return new AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent e) {
                run.run();
            }
        };
    }

    private static char charAt(final Document doc, final int offset) {
        try {
            return doc.getText(offset, 1).charAt(0);
        } catch (final BadLocationException e) {
            throw new IllegalStateException("insert outside its document [offset=" + offset + "]", e);
        }
    }

    private void take(final int keyCode, final String name, final Runnable run) {
        keys.put(KeyStroke.getKeyStroke(keyCode, 0), name);
        actions.put(name, action(run));
    }

    /**
     * A letter typed alone starts the pause, where {@code autoOpens} holds; any other insert, a paste or a word put in,
     * stops it.
     */
    private void typed(final DocumentEvent e) {
        justInserted = false;
        final boolean letter = e.getLength() == 1 && Character.isLetter(charAt(e.getDocument(), e.getOffset()));
        if (letter && autoOpens.getAsBoolean()) {
            pause.request();
            justInserted = true;
        } else {
            pause.stop();
        }
    }

    /** A caret move stops the pause unless a letter typed made it; with the list open, the list follows the word. */
    private void caretMoved() {
        if (justInserted) {
            justInserted = false;
        } else {
            pause.stop();
        }
        if (!open) {
            return;
        }
        final int dot = area.getCaretPosition();
        final String prefix = Completions.wordBefore(area.getDocument(), dot);
        if (prefix.isEmpty() || dot - prefix.length() != wordStart) {
            close();
        } else {
            offer(prefix, dot);
        }
    }

    /** Opens the list on the word before the caret, or refilters it; all words for no word, unless it is open. */
    private void refresh() {
        if (area == null) {
            return;
        }
        final int dot = area.getCaretPosition();
        final String prefix = Completions.wordBefore(area.getDocument(), dot);
        if (prefix.isEmpty() && open) {
            close();
        } else {
            offer(prefix, dot);
        }
    }

    private void offer(final String prefix, final int dot) {
        final List<Completion> found = words.matching(prefix);
        if (found.isEmpty()) {
            close();
            return;
        }
        model.set(found);
        select(0);
        wordStart = dot - prefix.length();
        if (!open) {
            open = true;
            takeKeys();
        }
        if (area.isShowing() && (window == null || !window.isVisible())) {
            showWindow();
        }
    }

    private void close() {
        if (!open) {
            return;
        }
        open = false;
        wordStart = -1;
        giveKeys();
        // the next open makes the window anew: a closed list keeps no native window and no listener on the area's
        dropWindow();
    }

    /** Puts the selected word in place of the prefix before the caret. */
    private void insert() {
        final Completion chosen = list.getSelectedValue();
        final JTextComponent a = area;
        close();
        if (chosen == null || a == null) {
            return;
        }
        final int dot = a.getCaretPosition();
        a.setCaretPosition(dot - Completions.wordBefore(a.getDocument(), dot).length());
        a.moveCaretPosition(dot);
        a.replaceSelection(chosen.text());
    }

    /** Up and Down: one row, from the last to the first and back. */
    private void step(final int rows) {
        final int size = model.getSize();
        if (size > 0) {
            select(Math.floorMod(list.getSelectedIndex() + rows, size));
        }
    }

    /** Page Up and Page Down: the list's visible row count, stopping at the ends. */
    private void page(final int pages) {
        final int size = model.getSize();
        if (size > 0) {
            select(Math.clamp(list.getSelectedIndex() + (long) pages * list.getVisibleRowCount(), 0, size - 1));
        }
    }

    private void select(final int index) {
        if (index >= 0 && index < model.getSize()) {
            list.setSelectedIndex(index);
            list.ensureIndexIsVisible(index);
        }
    }

    private void takeKeys() {
        final InputMap map = area.getInputMap(JComponent.WHEN_FOCUSED);
        saved.clear();
        keys.forEach((key, name) -> {
            saved.add(Saved.of(map, key));
            map.put(key, name);
        });
    }

    private void giveKeys() {
        final InputMap map = area.getInputMap(JComponent.WHEN_FOCUSED);
        saved.forEach(s -> s.restore(map));
        saved.clear();
    }

    /** Shows the window under the caret, made anew when the area has moved to another window. */
    private void showWindow() {
        final Window ancestor = SwingUtilities.getWindowAncestor(area);
        if (window == null || owner != ancestor) {
            dropWindow();
            owner = ancestor;
            window = new JWindow(ancestor);
            // the area keeps the focus and the keys: a click in the list selects without taking them
            window.setFocusableWindowState(false);
            window.getContentPane().add(pane);
            window.setSize(SIZE);
            if (ancestor != null) {
                ancestor.addComponentListener(ownerMoves);
                ancestor.addWindowFocusListener(ownerFocus);
            }
        }
        place(window);
        window.setVisible(true);
    }

    /** Under the caret's line, or over it where the screen ends below; inside the screen's width. */
    private void place(final JWindow w) {
        final Rectangle2D box;
        try {
            box = area.modelToView2D(area.getCaretPosition());
        } catch (final BadLocationException e) {
            throw new IllegalStateException("caret outside its document [caret=" + area.getCaretPosition() + "]", e);
        }
        final Rectangle line = box == null ? new Rectangle() : box.getBounds();
        final Point below = new Point(line.x, line.y + line.height);
        SwingUtilities.convertPointToScreen(below, area);
        final GraphicsConfiguration gc = area.getGraphicsConfiguration();
        final Rectangle screen = gc.getBounds();
        final Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        final int left = screen.x + insets.left;
        final int right = screen.x + screen.width - insets.right;
        final int bottom = screen.y + screen.height - insets.bottom;
        final int x = Math.max(left, Math.min(below.x, right - SIZE.width));
        final int y = below.y + SIZE.height <= bottom ? below.y : below.y - line.height - SIZE.height;
        w.setLocation(x, y);
    }

    private void dropWindow() {
        final JWindow w = window;
        final Window o = owner;
        window = null;
        owner = null;
        if (o != null) {
            o.removeComponentListener(ownerMoves);
            o.removeWindowFocusListener(ownerFocus);
        }
        if (w != null) {
            w.dispose();
        }
    }

    private void documentReplaced(final PropertyChangeEvent e) {
        if (e.getOldValue() instanceof Document old) {
            old.removeDocumentListener(typing);
        }
        if (e.getNewValue() instanceof Document now) {
            now.addDocumentListener(typing);
        }
        pause.stop();
        close();
    }

    /** The area stopped showing or moved: the list does not stay behind. */
    private void hierarchyChanged(final HierarchyEvent e) {
        if ((e.getChangeFlags() & (HierarchyEvent.SHOWING_CHANGED | HierarchyEvent.PARENT_CHANGED)) != 0) {
            pause.stop();
            close();
        }
    }

    /** A new look and feel restyles the list, shown or not. */
    private void lookAndFeelChanged(final PropertyChangeEvent e) {
        if ("lookAndFeel".equals(e.getPropertyName())) {
            SwingUtilities.updateComponentTreeUI(window != null ? window : pane);
        }
    }

    /** The words offered: a view of the sorted words, replaced whole with one event each for removal and addition. */
    private static final class Choices extends AbstractListModel<Completion> {

        private List<Completion> shown = List.of();

        @Override
        public int getSize() {
            return shown.size();
        }

        @Override
        public Completion getElementAt(final int index) {
            return shown.get(index);
        }

        void set(final List<Completion> words) {
            final int before = shown.size();
            shown = words;
            if (before > 0) {
                fireIntervalRemoved(this, 0, before - 1);
            }
            if (!words.isEmpty()) {
                fireIntervalAdded(this, 0, words.size() - 1);
            }
        }
    }

    /**
     * A row: the word, then what it is, muted, as in the command palette. Both are escaped first: a table or column
     * name comes from the database and may hold any characters, tags among them.
     */
    private static final class Row extends DefaultListCellRenderer {

        @Override
        public Component getListCellRendererComponent(final JList<?> l,
                                                      final Object value,
                                                      final int index,
                                                      final boolean selected,
                                                      final boolean focused) {
            final Completion c = (Completion) value;
            final JLabel label = (JLabel) super.getListCellRendererComponent(l, c.text(), index, selected, focused);
            label.setText("<html><nobr>" + MarkdownHtml.escape(c.text()) + "&nbsp;&nbsp;<span style='color:"
                    + Theme.hex(Theme.MUTED) + "'>" + MarkdownHtml.escape(c.description()) + "</span></nobr></html>");
            label.setBorder(PADDING);
            return label;
        }
    }

    /** A key's own binding in an input map before the list took it, or none: what giving the key back restores. */
    private record Saved(KeyStroke key, boolean had, Object name) {

        static Saved of(final InputMap map, final KeyStroke key) {
            final KeyStroke[] own = map.keys();
            final boolean had = own != null && Arrays.asList(own).contains(key);
            return new Saved(key, had, had ? map.get(key) : null);
        }

        void restore(final InputMap map) {
            if (had) {
                map.put(key, name);
            } else {
                map.remove(key);
            }
        }
    }
}
