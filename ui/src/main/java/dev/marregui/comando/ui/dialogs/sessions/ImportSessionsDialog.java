package dev.marregui.comando.ui.dialogs.sessions;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.HomeDir;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.ui.kit.async.Edt;
import dev.marregui.comando.ui.kit.format.Labels;
import dev.marregui.comando.ui.kit.overlay.Overlay;
import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.kit.theme.Theme;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Lists the conversations stored on disk, newest first, and imports the selected ones as sessions that
 * resume them. Names come from the conversation's title, or its directory and id.
 */
public final class ImportSessionsDialog {

    private static final String[] COLUMNS = {"Title", "Directory", "Branch", "Last prompt", "Modified", "Size"};

    private final Window owner;
    private final JPanel content = new JPanel(new BorderLayout(0, Theme.GAP));
    private final JButton importButton = new JButton("Import selected");
    private JDialog dialog;
    private final IntentSink intents;
    private final Supplier<List<StoredConversation>> catalogue;
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final Overlay overlay = new Overlay(new JScrollPane(table));
    private final JTextField filter = new JTextField(24);
    private final JLabel info = new JLabel(" ");
    private List<StoredConversation> all = List.of();
    private Set<String> known = Set.of();
    private Set<String> knownNames = Set.of();

    public ImportSessionsDialog(Window owner, IntentSink intents, Supplier<List<StoredConversation>> catalogue) {
        this.intents = intents;
        this.catalogue = catalogue;
        this.owner = owner;
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        // titles and prompts come from stored conversations: shown as text, never as HTML
        for (Class<?> type : new Class<?>[]{Object.class, String.class}) {
            if (table.getDefaultRenderer(type) instanceof JComponent r) {
                Plain.only(r);
            }
        }
        table.setAutoCreateRowSorter(true);
        table.setRowHeight(table.getRowHeight() + 4);
        filter.putClientProperty("JTextField.placeholderText", "Filter by title, directory, branch or prompt");
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                applyFilter();
            }
        });
        importButton.putClientProperty("JButton.buttonType", "default");
        importButton.addActionListener(e -> importSelected());
        JButton close = new JButton("Close");
        close.addActionListener(e -> hide());
        JPanel top = new JPanel(new BorderLayout(Theme.GAP, 0));
        top.add(filter, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout());
        info.setForeground(Theme.MUTED);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(close);
        buttons.add(importButton);
        south.add(info, BorderLayout.CENTER);
        south.add(buttons, BorderLayout.EAST);
        content.setBorder(BorderFactory.createEmptyBorder(Theme.PAD, Theme.PAD, Theme.PAD, Theme.PAD));
        content.add(top, BorderLayout.NORTH);
        content.add(overlay, BorderLayout.CENTER);
        content.add(south, BorderLayout.SOUTH);
        content.setPreferredSize(new Dimension(1100, 560));
        int[] widths = {220, 220, 120, 360, 80, 60};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
    }

    /** ⏎ imports the selection (or the only row the filter leaves); Esc closes. */
    void install(JRootPane rootPane) {
        rootPane.setDefaultButton(importButton);
        rootPane.registerKeyboardAction(e -> hide(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    private void hide() {
        if (dialog != null) {
            dialog.setVisible(false);
        }
    }

    public void show(AppState s) {
        if (dialog == null) {
            dialog = new JDialog(owner, "Import stored conversations");
            dialog.setContentPane(content);
            install(dialog.getRootPane());
            dialog.pack();
        }
        known = new HashSet<>();
        knownNames = new HashSet<>();
        for (SessionState x : s.sessions()) {
            known.add(x.spec().claudeSessionId());
            knownNames.add(x.name());
        }
        overlay.showLoading("Reading stored conversations");
        if (!dialog.isVisible()) {
            dialog.setLocationRelativeTo(dialog.getOwner());
        }
        dialog.setVisible(true);
        Thread.ofVirtual().name("read-conversations").start(() -> {
            try {
                List<StoredConversation> read = catalogue.get();
                Edt.later(() -> {
                    all = read;
                    applyFilter();
                    if (all.isEmpty()) {
                        overlay.showEmpty("No stored conversations found under "
                                + HomeDir.shorten(HomeDir.claudeDir().resolve("projects").toString()) + ".");
                    } else {
                        overlay.showContent();
                    }
                });
            } catch (RuntimeException e) {
                Edt.later(() -> overlay.showError("Cannot read the conversations: " + e.getMessage()));
            }
        });
    }

    private void applyFilter() {
        String q = filter.getText().strip().toLowerCase(Locale.ROOT);
        List<StoredConversation> rows = new ArrayList<>();
        for (StoredConversation c : all) {
            if (q.isEmpty() || (c.title() + ' ' + c.cwd() + ' ' + c.branch() + ' ' + c.lastPrompt()).toLowerCase(Locale.ROOT).contains(q)) {
                rows.add(c);
            }
        }
        model.rows = rows;
        model.fireTableDataChanged();
        info.setText(rows.size() + " of " + all.size() + " conversations; already imported ones are marked ✓");
    }

    private void importSelected() {
        int[] selected = table.getSelectedRows();
        if (selected.length == 0 && table.getRowCount() == 1) {
            selected = new int[]{0};
        }
        List<SessionSpec> specs = new ArrayList<>();
        // a title equal to an existing session's name gets a number, so the import is not skipped as a duplicate
        Set<String> names = new HashSet<>(knownNames);
        for (int viewRow : selected) {
            StoredConversation c = model.rows.get(table.convertRowIndexToModel(viewRow));
            String name = uniqueName(nameFor(c), names);
            names.add(name);
            specs.add(SessionSpec.imported(name, c.cwd(), c.sessionId()));
        }
        if (!specs.isEmpty()) {
            intents.dispatch(new Intent.ImportSessions(specs));
            hide();
        }
    }

    static String nameFor(StoredConversation c) {
        String base = c.title().isBlank() ? Path.of(c.cwd()).getFileName() + " " + c.sessionId().substring(0, Math.min(8, c.sessionId().length()))
                : c.title().strip();
        return base.length() > 48 ? base.substring(0, 48).strip() : base;
    }

    private static String uniqueName(String base, Set<String> taken) {
        String name = base;
        for (int i = 2; taken.contains(name); i++) {
            name = base + " " + i;
        }
        return name;
    }

    private final class Model extends AbstractTableModel {
        List<StoredConversation> rows = List.of();

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int c) {
            return COLUMNS[c];
        }

        @Override
        public Object getValueAt(int r, int c) {
            StoredConversation x = rows.get(r);
            return switch (c) {
                case 0 -> (known.contains(x.sessionId()) ? "✓ " : "") + (x.title().isEmpty() ? x.sessionId() : x.title());
                case 1 -> Labels.shortPath(x.cwd());
                case 2 -> x.branch();
                case 3 -> x.lastPrompt();
                case 4 -> new Sortable(x.modifiedMillis(), Labels.ago(x.modifiedMillis(), System.currentTimeMillis()));
                default -> new Sortable(x.sizeBytes(), x.sizeBytes() < 1024 * 1024 ? x.sizeBytes() / 1024 + " KB" : x.sizeBytes() / (1024 * 1024) + " MB");
            };
        }

        @Override
        public Class<?> getColumnClass(int c) {
            return c >= 4 ? Sortable.class : String.class;
        }
    }

    /** A cell shown as text and sorted by its number: "3 h ago" before "2 d ago", "900 KB" before "2 MB". */
    record Sortable(long key, String text) implements Comparable<Sortable> {
        @Override
        public int compareTo(Sortable o) {
            return Long.compare(key, o.key);
        }

        @Override
        public String toString() {
            return text;
        }
    }
}
