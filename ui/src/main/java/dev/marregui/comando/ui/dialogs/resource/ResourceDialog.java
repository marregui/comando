package dev.marregui.comando.ui.dialogs.resource;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.resource.Attribute;
import dev.marregui.comando.core.resource.ResourceKind;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.ui.kit.async.Edt;
import dev.marregui.comando.ui.kit.desktop.FileChoosers;
import dev.marregui.comando.ui.kit.desktop.Platform;
import dev.marregui.comando.ui.kit.format.KeyText;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.kit.theme.Theme;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRootPane;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.Scrollable;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The one dialog for configurable resources: the form of one resource, generated from the kind's attributes, with
 * Test, Open/Close, Assign and Save. It edits the resource the caller names, or a new one, or an unsaved copy of one;
 * the sidebar's lists add, pick, clone and remove resources. Connections and sessions are configurations of it.
 *
 * <p>⏎ saves, ⌘⏎ saves and assigns to the focused panel, Esc or the window's close button closes and asks
 * first when the form has unsaved changes, so a closed dialog never holds an edit that was not saved. Opening the
 * dialog on another resource while the form has unsaved changes asks the same question first; keeping the edits
 * cancels the opening. Questions are asked in a row inside the dialog. The outcome of Test, Connect and Start is the
 * latest notice about the resource, shown on the line above the buttons.</p>
 *
 * @param <S> the spec record
 */
public final class ResourceDialog<S> {

    /** The content's size when the dialog first opens: every control is visible at this size. */
    static final Dimension DEFAULT_SIZE = new Dimension(608, 520);

    /**
     * What differs between connections and sessions. {@code test}, {@code open} (with {@code close}) and
     * {@code assign} are null for a kind without them, and the dialog then has no such button.
     */
    public record Config<S>(
            ResourceKind<S> kind,
            Function<AppState, List<S>> specs,
            Function<AppState, Map<String, Status>> statuses,
            BiFunction<String, S, Intent> save,
            Function<S, Intent> test,
            Function<String, Intent> open,
            Function<String, Intent> close,
            BiFunction<AppState, String, Intent> assign) {
    }

    private final Config<S> config;
    private final IntentSink intents;
    private final Window owner;
    private final String noun;
    private final boolean session;
    private final JPanel content = new JPanel(new BorderLayout());
    /** The resources' names as of the last render: a saved name must be free, a new one is made free. */
    private List<String> names = List.of();
    private final Map<String, JComponent> fields = new LinkedHashMap<>();
    private final JLabel message = Plain.only(new JLabel());
    private final JButton openClose = new JButton();
    private final JButton save;
    private final JPanel advanced = new JPanel(new GridBagLayout());
    private final JButton advancedToggle = new JButton();
    private final JPanel confirmRow = new JPanel(new BorderLayout(Theme.GAP, 0));
    private final JLabel confirmText = Plain.only(new JLabel());
    private final JButton confirmYes = new JButton();
    private final JButton confirmNo = new JButton();
    private Runnable onConfirm;
    private JDialog dialog;
    private JRootPane root;
    private AppState state;
    private String editing = "";
    /** The values the form was last filled with: what the user has not changed since is refreshed from the state. */
    private Map<String, String> filled = Map.of();
    private boolean filling;
    private String advice = "";
    private String lastDirectory = "";
    /** The notice subject whose newer notices the message shows, after Test, Connect or Start. */
    private String watched = "";
    private long watchedSince;
    private Notice shown;
    /** The resource asked to open: when it is open and no notice says otherwise, the message says so. */
    private String opening = "";

    public ResourceDialog(Window owner, Config<S> config, IntentSink intents) {
        this.config = config;
        this.intents = intents;
        this.owner = owner;
        this.noun = config.kind().noun();
        this.session = noun.equals("session");
        JPanel form = new WidthTracking(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        int row = 0;
        int advancedRow = 0;
        for (Attribute a : config.kind().attributes()) {
            JComponent field = field(a);
            fields.put(a.key(), field);
            JComponent cell = field instanceof JTextField t && (a.kind() == Attribute.Kind.DIRECTORY || a.kind() == Attribute.Kind.DIRECTORIES)
                    ? withChooser(t, a.label(), a.kind() == Attribute.Kind.DIRECTORIES) : field;
            if (a.advanced()) {
                advancedRow = addRow(advanced, advancedRow, a, cell);
            } else {
                row = addRow(form, row, a, cell);
            }
        }
        if (advanced.getComponentCount() > 0) {
            advancedToggle.setBorderPainted(false);
            advancedToggle.setContentAreaFilled(false);
            advancedToggle.setHorizontalAlignment(JButton.LEADING);
            advancedToggle.addActionListener(e -> setAdvancedOpen(!advanced.isVisible()));
            g.gridx = 0;
            g.gridy = row++;
            g.gridwidth = 2;
            g.anchor = GridBagConstraints.LINE_START;
            g.insets = new Insets(Theme.GAP, 0, 0, 0);
            form.add(advancedToggle, g);
            g.gridy = row++;
            g.fill = GridBagConstraints.HORIZONTAL;
            g.weightx = 1;
            g.insets = new Insets(0, 0, 0, 0);
            form.add(advanced, g);
            setAdvancedOpen(false);
        }
        g.gridx = 0;
        g.gridy = row;
        g.gridwidth = 2;
        g.weighty = 1;
        g.fill = GridBagConstraints.BOTH;
        form.add(new JPanel(), g);
        JScrollPane formScroll = new JScrollPane(form, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        formScroll.setBorder(null);
        formScroll.getVerticalScrollBar().setUnitIncrement(16);

        message.setText(" ");
        message.setName("message");
        message.setForeground(Theme.MUTED);
        confirmRow.setName("confirm");
        confirmRow.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Theme.ATTENTION),
                BorderFactory.createEmptyBorder(Theme.GAP, Theme.GAP, Theme.GAP, Theme.GAP)));
        JPanel confirmButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.GAP, 0));
        confirmButtons.add(confirmNo);
        confirmButtons.add(confirmYes);
        confirmYes.addActionListener(e -> {
            Runnable r = onConfirm;
            hideConfirm();
            if (r != null) {
                r.run();
            }
        });
        confirmNo.addActionListener(e -> hideConfirm());
        confirmRow.add(confirmText, BorderLayout.CENTER);
        confirmRow.add(confirmButtons, BorderLayout.EAST);
        confirmRow.setVisible(false);

        JPanel right = new JPanel(new BorderLayout(0, Theme.GAP));
        right.setBorder(BorderFactory.createEmptyBorder(Theme.PAD, Theme.PAD, Theme.PAD, Theme.PAD));
        right.add(formScroll, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.GAP, 0));
        if (config.test() != null) {
            actions.add(button("Test", "check", "Try the values without saving them", this::testForm));
        }
        if (config.open() != null) {
            actions.add(openClose);
            openClose.addActionListener(e -> {
                hideConfirm();
                toggleOpen();
            });
        }
        if (config.assign() != null) {
            // a session has its own panel, titled with its name (record 88): the button brings it forward
            actions.add(button(session ? "Open its panel" : "Assign to panel", "pin", KeyText.of(session
                            ? "Save if changed, then bring the session's own panel forward (⌘⏎)"
                            : "Save if changed, then bind it to the focused panel (⌘⏎)"),
                    this::assign));
        }
        save = button("Save", "check", "Save the form (⏎)", this::save);
        save.putClientProperty("JButton.buttonType", "default");
        actions.add(save);
        JPanel south = new JPanel(new BorderLayout(0, Theme.GAP));
        south.add(confirmRow, BorderLayout.NORTH);
        south.add(message, BorderLayout.CENTER);
        south.add(actions, BorderLayout.SOUTH);
        right.add(south, BorderLayout.SOUTH);

        content.add(right, BorderLayout.CENTER);
        content.setPreferredSize(DEFAULT_SIZE);
    }

    /** Shows the dialog on the resource named {@code select}, or on a new one when it is empty or unknown. */
    public void show(AppState s, String select) {
        open(() -> prepare(s, select));
    }

    /** Shows the dialog on a new resource, as Add does. */
    public void showNew(AppState s) {
        open(() -> prepareNew(s));
    }

    /** Shows the dialog on a new resource named {@code name}, not saved yet: a panel's "New session ‹title›…". */
    public void showNew(AppState s, String name) {
        open(() -> {
            render(s);
            replacingForm(() -> add(name));
        });
    }

    /** Shows the dialog on a copy of {@code of}, not saved yet, as Clone does. */
    public void showClone(AppState s, String of) {
        open(() -> prepareClone(s, of));
    }

    private void open(Runnable prepare) {
        if (dialog == null) {
            dialog = new JDialog(owner, title());
            dialog.setModal(false);
            dialog.setContentPane(content);
            install(dialog.getRootPane());
            // the close button is Esc: unsaved changes are kept or discarded on the user's word, never left behind
            dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            dialog.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    close();
                }
            });
            dialog.pack();
        }
        prepare.run();
        dialog.setTitle(title());
        if (!dialog.isVisible()) {
            // placed on each opening: the main window may have moved, or restored its bounds, since the dialog was built
            dialog.setLocationRelativeTo(dialog.getOwner());
        }
        dialog.setVisible(true);
        dialog.toFront();
    }

    public boolean isVisible() {
        return dialog != null && dialog.isVisible();
    }

    /**
     * Fills the form for {@code select}, or for a new resource when it is empty or names none. Unsaved changes to
     * another resource are kept until the user discards them; unsaved changes to {@code select} itself stay.
     */
    void prepare(AppState s, String select) {
        render(s);
        if (select.isEmpty() || !names.contains(select)) {
            prepareNew(s);
        } else if (select.equals(editing) && isDirty()) {
            hideConfirm(); // a pending switch elsewhere is dropped: the form stays on select
        } else {
            replacingForm(() -> edit(select));
        }
    }

    private void retitle() {
        if (dialog != null) {
            dialog.setTitle(title());
        }
    }

    /** The window's title: what the form edits. */
    String title() {
        String capital = Character.toUpperCase(noun.charAt(0)) + noun.substring(1);
        return editing.isEmpty() ? "New " + noun : capital + " " + editing;
    }

    void prepareNew(AppState s) {
        render(s);
        replacingForm(this::add);
    }

    void prepareClone(AppState s, String of) {
        render(s);
        if (names.contains(of)) {
            replacingForm(() -> cloneOf(of));
        } else {
            prepare(s, "");
        }
    }

    /** Default button, Esc and ⌘⏎ on the root pane the content is shown in. */
    void install(JRootPane rootPane) {
        root = rootPane;
        rootPane.setDefaultButton(save);
        rootPane.registerKeyboardAction(e -> escape(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        if (config.assign() != null) {
            rootPane.registerKeyboardAction(e -> {
                hideConfirm();
                assign();
            }, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, Platform.menuShortcutMask()), JComponent.WHEN_IN_FOCUSED_WINDOW);
        }
    }

    JComponent content() {
        return content;
    }

    /**
     * Takes the state's resources. The form keeps what the user changed; every other field takes the stored value,
     * so a value the application changed meanwhile (a session's conversation id after a start) is not written back
     * stale by the next Save.
     */
    public void render(AppState s) {
        state = s;
        names = config.specs().apply(s).stream().map(config.kind()::nameOf).toList();
        refreshUntouchedFields();
        updateOpenClose();
        showOutcome(s);
    }

    private void refreshUntouchedFields() {
        if (editing.isEmpty() || state == null) {
            return;
        }
        for (S spec : config.specs().apply(state)) {
            if (config.kind().nameOf(spec).equals(editing)) {
                Map<String, String> stored = config.kind().toAttributes(spec);
                if (stored.equals(filled)) {
                    return;
                }
                fill(merge(read(), filled, stored));
                filled = stored;
                if (config.kind().showAdvanced(stored)) {
                    setAdvancedOpen(true);
                }
                return;
            }
        }
    }

    /** The form after the stored values changed: a field the user left as filled takes the stored value. */
    static Map<String, String> merge(Map<String, String> form, Map<String, String> filled, Map<String, String> stored) {
        Map<String, String> merged = new HashMap<>(form);
        for (Map.Entry<String, String> e : stored.entrySet()) {
            if (Objects.equals(form.get(e.getKey()), filled.get(e.getKey()))) {
                merged.put(e.getKey(), e.getValue());
            }
        }
        return merged;
    }

    /** The latest notice about {@code subject} at or after {@code sinceMillis}, or null. */
    static Notice outcome(List<Notice> notices, String subject, long sinceMillis) {
        for (int i = notices.size() - 1; i >= 0; i--) {
            Notice n = notices.get(i);
            if (n.atMillis() < sinceMillis) {
                return null;
            }
            if (n.subject().equals(subject)) {
                return n;
            }
        }
        return null;
    }

    private void showOutcome(AppState s) {
        if (watched.isEmpty()) {
            return;
        }
        Notice n = outcome(s.notices(), watched, watchedSince);
        if (n != null && !n.equals(shown)) {
            shown = n;
            say(n.level() == Notice.Level.ERROR ? Theme.DANGER : Theme.MUTED, n.text());
        }
        if (!opening.isEmpty() && config.statuses().apply(s).getOrDefault(opening, Status.CLOSED) == Status.OPEN) {
            if (n == null || n.level() != Notice.Level.ERROR) {
                say(Theme.MUTED, (session ? "Started " : "Connected ") + opening);
            }
            opening = "";
        }
    }

    private void watch(String name) {
        watched = config.kind().noticeSubject(name);
        watchedSince = System.currentTimeMillis();
        shown = null;
    }

    private void unwatch() {
        watched = "";
        shown = null;
        opening = "";
    }

    private void edit(String name) {
        if (state == null) {
            return;
        }
        for (S spec : config.specs().apply(state)) {
            if (config.kind().nameOf(spec).equals(name)) {
                hideConfirm();
                unwatch();
                editing = name;
                filled = config.kind().toAttributes(spec);
                fill(filled);
                setAdvancedOpen(config.kind().showAdvanced(filled));
                say(Theme.MUTED, "");
                showAdvice(true);
                updateOpenClose();
        retitle();
                return;
            }
        }
    }

    private void add() {
        add(unique("new " + config.kind().noun()));
    }

    private void add(String name) {
        hideConfirm();
        unwatch();
        editing = "";
        Map<String, String> values = config.kind().toAttributes(config.kind().template(name));
        for (Attribute a : config.kind().attributes()) {
            if (a.kind() == Attribute.Kind.DIRECTORY && state != null) {
                values.put(a.key(), defaultDirectory(state, lastDirectory));
                break;
            }
        }
        filled = values;
        fill(values);
        setAdvancedOpen(config.kind().showAdvanced(values));
        say(Theme.MUTED, "New " + config.kind().noun() + "; Save adds it.");
        advice = "";
        updateOpenClose();
        retitle();
        // a new resource is named first: the keyboard goes to the first field, its default selected to type over;
        // later, as the dialog may only now be opening
        JComponent first = fields.values().iterator().next();
        if (first instanceof JTextComponent t) {
            t.selectAll();
        }
        Edt.later(first::requestFocusInWindow);
    }

    /**
     * Where a new resource's directory starts: the working directory of the focused panel's session, else
     * the directory used last in this dialog, else the home directory.
     */
    static String defaultDirectory(AppState s, String last) {
        String panelSession = s.focusedPanel().session();
        String dir = s.session(panelSession).map(SessionState::spec).map(x -> x.workDir()).orElse("");
        if (!dir.isBlank()) {
            return dir;
        }
        return last.isBlank() ? System.getProperty("user.home") : last;
    }

    private void cloneOf(String of) {
        S spec = spec(of);
        if (spec == null) {
            return;
        }
        String name = unique(config.kind().nameOf(spec) + " copy");
        unwatch();
        editing = "";
        filled = config.kind().toAttributes(config.kind().cloneOf(spec, name));
        fill(filled);
        setAdvancedOpen(config.kind().showAdvanced(filled));
        say(Theme.MUTED, "Clone of " + config.kind().nameOf(spec) + "; Save adds it.");
        updateOpenClose();
        retitle();
    }

    /** Saves the form; false, with the reason in the message, when it is invalid or refused. */
    private boolean save() {
        try {
            S spec = config.kind().fromAttributes(read());
            String name = config.kind().nameOf(spec);
            if (!name.equals(editing) && names.contains(name)) {
                say(Theme.DANGER, "A " + config.kind().noun() + " named " + name + " exists; choose another name");
                return false;
            }
            Status current = editing.isEmpty() ? Status.CLOSED : config.statuses().apply(state).getOrDefault(editing, Status.CLOSED);
            if (!name.equals(editing) && !editing.isEmpty() && session && current != Status.CLOSED && current != Status.FAILED) {
                // the application refuses this rename; saying "Saved" would leave the form on a name that does not exist
                say(Theme.DANGER, "Stop " + editing + " before renaming it");
                return false;
            }
            intents.dispatch(config.save().apply(editing, spec));
            filled = config.kind().toAttributes(spec);
            editing = config.kind().nameOf(spec);
            for (Attribute a : config.kind().attributes()) {
                if (a.kind() == Attribute.Kind.DIRECTORY && !filled.getOrDefault(a.key(), "").isBlank()) {
                    lastDirectory = filled.get(a.key());
                    break;
                }
            }
            say(Theme.MUTED, "Saved " + editing);
            retitle();
            updateOpenClose();
            return true;
        } catch (IllegalArgumentException e) {
            say(Theme.DANGER, e.getMessage());
            return false;
        }
    }

    private void testForm() {
        try {
            S spec = config.kind().fromAttributes(read());
            intents.dispatch(config.test().apply(spec));
            watch(config.kind().nameOf(spec));
            opening = "";
            say(Theme.MUTED, "Testing " + config.kind().nameOf(spec) + "…");
        } catch (IllegalArgumentException e) {
            say(Theme.DANGER, e.getMessage());
        }
    }

    /** Opens the resource, saving the form first when it is new or changed; closes it when open. */
    private void toggleOpen() {
        if (state == null || config.open() == null) {
            return;
        }
        if (!editing.isEmpty() && config.statuses().apply(state).getOrDefault(editing, Status.CLOSED).isUsable()) {
            intents.dispatch(config.close().apply(editing));
            watch(editing);
            opening = "";
            say(Theme.MUTED, (session ? "Stopping " : "Disconnecting ") + editing);
            return;
        }
        boolean saved = false;
        if (editing.isEmpty() || isDirty()) {
            if (!save()) {
                return;
            }
            saved = true;
        }
        intents.dispatch(config.open().apply(editing));
        watch(editing);
        opening = editing;
        String verb = session ? "starting" : "connecting";
        say(Theme.MUTED, saved ? "Saved and " + verb : Character.toUpperCase(verb.charAt(0)) + verb.substring(1) + " " + editing);
    }

    /** Binds the resource to the focused panel, saving the form first when it is new or changed. */
    private void assign() {
        if (state == null || config.assign() == null) {
            return;
        }
        boolean saved = false;
        if (editing.isEmpty() || isDirty()) {
            if (!save()) {
                return;
            }
            saved = true;
        }
        intents.dispatch(config.assign().apply(state, editing));
        // a session opens in its own panel, never another one
        say(Theme.MUTED, session ? (saved ? "Saved and opened " : "Opened ") + editing + " in its own panel"
                : (saved ? "Saved and assigned " : "Assigned ") + editing + " to panel " + state.focusedPanel().title());
    }

    /** Esc: answers an open question with its safe button; closes the dialog, asking first when the form has unsaved changes. */
    void escape() {
        if (confirmRow.isVisible()) {
            hideConfirm();
            return;
        }
        if (isDirty()) {
            confirm(discardQuestion(), "Discard", "Keep editing", () -> {
                fill(filled);
                say(Theme.MUTED, "");
                hide();
            });
            return;
        }
        hide();
    }

    /** The window's close button: an open question is answered with its safe button, then the dialog closes as with Esc. */
    void close() {
        hideConfirm();
        escape();
    }

    /**
     * Runs {@code replace}, which fills the form with something else, at once when the form has no unsaved
     * changes; else asks first. Discard runs it; Keep editing (or Esc, or any other button) cancels it.
     */
    private void replacingForm(Runnable replace) {
        if (!isDirty()) {
            replace.run();
            return;
        }
        confirm(discardQuestion(), "Discard", "Keep editing", replace);
    }

    private String discardQuestion() {
        return "Discard changes to " + (editing.isEmpty() ? "the new " + config.kind().noun() : editing) + "?";
    }

    private void hide() {
        if (dialog != null) {
            dialog.setVisible(false);
        }
    }

    /** Whether the form differs from what it was last filled with or saved as. */
    boolean isDirty() {
        return !filled.isEmpty() && !read().equals(filled);
    }

    private void confirm(String question, String yes, String no, Runnable action) {
        confirmText.setText(question);
        confirmYes.setText(yes);
        confirmNo.setText(no);
        onConfirm = action;
        confirmRow.setVisible(true);
        if (root != null) {
            root.setDefaultButton(confirmNo); // ⏎ takes the safe answer
        }
        confirmNo.requestFocusInWindow();
        content.revalidate();
    }

    private void hideConfirm() {
        if (!confirmRow.isVisible()) {
            return;
        }
        onConfirm = null;
        confirmRow.setVisible(false);
        if (root != null) {
            root.setDefaultButton(save);
        }
        content.revalidate();
    }

    private void updateOpenClose() {
        if (config.open() == null) {
            return;
        }
        Status st = editing.isEmpty() || state == null ? Status.CLOSED : config.statuses().apply(state).getOrDefault(editing, Status.CLOSED);
        boolean open = st.isUsable();
        openClose.setText(open ? (session ? "Stop" : "Disconnect") : (session ? "Start" : "Connect"));
        openClose.setIcon(Icons.get(open ? (session ? "stop" : "unplug") : (session ? "play" : "plug")));
        openClose.setToolTipText(open ? null : "Save if changed, then " + (session ? "start the session" : "connect"));
    }

    private void setAdvancedOpen(boolean open) {
        if (advanced.getComponentCount() == 0) {
            return;
        }
        advanced.setVisible(open);
        advancedToggle.setText((open ? "▾ " : "▸ ") + "Advanced");
        content.revalidate();
    }

    private void say(Color color, String text) {
        message.setForeground(color);
        message.setText(text.isEmpty() ? " " : text);
        message.setToolTipText(text.isEmpty() ? null : Plain.text(text)); // the whole text when the line cuts it
    }

    /** Shows the kind's warning about the form when it changed, or always when {@code always}; clears a warning that no longer holds. */
    private void showAdvice(boolean always) {
        String now = config.kind().advice(read());
        if (now.equals(advice) && !always) {
            return;
        }
        boolean showing = !advice.isEmpty() && advice.equals(message.getText());
        advice = now;
        if (!now.isEmpty()) {
            say(Theme.ATTENTION, now);
        } else if (showing) {
            say(Theme.MUTED, "");
        }
    }

    private void formChanged() {
        if (!filling) {
            showAdvice(false);
        }
    }

    private S spec(String name) {
        for (S spec : config.specs().apply(state)) {
            if (config.kind().nameOf(spec).equals(name)) {
                return spec;
            }
        }
        return null;
    }

    private String unique(String base) {
        String name = base;
        for (int i = 2; names.contains(name); i++) {
            name = base + " " + i;
        }
        return name;
    }

    private void fill(Map<String, String> values) {
        filling = true;
        try {
            for (Attribute a : config.kind().attributes()) {
                JComponent f = fields.get(a.key());
                String v = values.getOrDefault(a.key(), "");
                switch (f) {
                    case JCheckBox c -> c.setSelected(Boolean.parseBoolean(v));
                    case JComboBox<?> c -> select(c, v);
                    case JTextField t -> {
                        if (!t.getText().equals(v)) {
                            t.setText(v); // unchanged fields keep their caret
                        }
                    }
                    default -> {
                        // no other field kinds
                    }
                }
            }
        } finally {
            filling = false;
        }
    }

    /** Selects {@code v}; a stored value outside the list is added, so a fixed list never changes it silently. */
    private static void select(JComboBox<?> c, String v) {
        if (!c.isEditable() && c.getModel() instanceof DefaultComboBoxModel<?> m && m.getIndexOf(v) < 0) {
            @SuppressWarnings("unchecked")
            DefaultComboBoxModel<String> strings = (DefaultComboBoxModel<String>) m;
            strings.addElement(v);
        }
        c.setSelectedItem(v);
    }

    private Map<String, String> read() {
        Map<String, String> m = new LinkedHashMap<>();
        for (Attribute a : config.kind().attributes()) {
            JComponent f = fields.get(a.key());
            m.put(a.key(), switch (f) {
                case JCheckBox c -> Boolean.toString(c.isSelected());
                case JComboBox<?> c -> String.valueOf(c.getSelectedItem() == null ? "" : c.getSelectedItem());
                case JPasswordField p -> new String(p.getPassword());
                case JTextField t -> t.getText();
                default -> "";
            });
        }
        return m;
    }

    private JComponent field(Attribute a) {
        JComponent f = switch (a.kind()) {
            case SECRET -> new JPasswordField(24);
            case BOOLEAN -> new JCheckBox();
            case CHOICE -> {
                JComboBox<String> c = new JComboBox<>(a.choices().toArray(String[]::new));
                c.setEditable(a.editable());
                c.addActionListener(e -> formChanged());
                yield c;
            }
            default -> new JTextField(24);
        };
        if (f instanceof JTextField t) {
            t.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    formChanged();
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    formChanged();
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                    formChanged();
                }
            });
        }
        f.setName(a.key());
        f.getAccessibleContext().setAccessibleName(a.label());
        if (!a.hint().isEmpty()) {
            f.setToolTipText(a.hint());
            f.getAccessibleContext().setAccessibleDescription(a.hint());
        }
        return f;
    }

    /** A label, the field and, under it, the hint in a muted line; returns the next free row. */
    private static int addRow(JPanel panel, int row, Attribute a, JComponent cell) {
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.gridy = row;
        g.anchor = GridBagConstraints.LINE_END;
        g.insets = new Insets(3, 3, 0, 3);
        JLabel label = new JLabel(a.label());
        label.setLabelFor(cell);
        panel.add(label, g);
        g.gridx = 1;
        g.anchor = GridBagConstraints.LINE_START;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.weightx = 1;
        panel.add(cell, g);
        if (a.hint().isEmpty()) {
            return row + 1;
        }
        // no width of its own: the fields set the column's width, and a hint never widens the form past the dialog
        JLabel hint = new JLabel(a.hint()) {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(0, super.getPreferredSize().height);
            }
        };
        hint.setName("hint:" + a.key());
        hint.setToolTipText(a.hint());
        hint.setForeground(Theme.MUTED);
        hint.setFont(Theme.font(hint.getFont(), Theme.TextSize.SMALL));
        g.gridy = row + 1;
        g.insets = new Insets(0, 6, 2, 3);
        panel.add(hint, g);
        return row + 2;
    }

    private JComponent withChooser(JTextField field, String label, boolean many) {
        JPanel p = new JPanel(new BorderLayout(4, 0));
        p.add(field, BorderLayout.CENTER);
        JButton browse = new JButton("…");
        browse.setToolTipText("Choose a folder");
        browse.getAccessibleContext().setAccessibleName("Choose " + label);
        browse.addActionListener(e -> {
            String text = field.getText();
            FileChoosers.directory(content, label, chooserStart(text)).ifPresent(dir -> {
                String path = dir.toAbsolutePath().toString();
                lastDirectory = path;
                field.setText(many && !text.isBlank() ? text + File.pathSeparator + path : path);
            });
        });
        p.add(browse, BorderLayout.EAST);
        return p;
    }

    /** Where the folder chooser opens: the field's first folder, else the home folder. */
    static Path chooserStart(String text) {
        for (String part : text.split(Pattern.quote(File.pathSeparator))) {
            if (!part.isBlank()) {
                try {
                    return Path.of(part.strip());
                } catch (InvalidPathException e) {
                    break;
                }
            }
        }
        return Path.of(System.getProperty("user.home"));
    }

    private JButton button(String text, String icon, String tip, Runnable r) {
        JButton b = new JButton(text, Icons.get(icon));
        if (tip != null) {
            b.setToolTipText(tip);
        }
        b.addActionListener(e -> {
            hideConfirm();
            r.run();
        });
        return b;
    }

    /** A form panel that takes the scroll pane's width, so its fields stretch and only its height scrolls. */
    private static final class WidthTracking extends JPanel implements Scrollable {

        WidthTracking(GridBagLayout layout) {
            super(layout);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(16, visible.height - 16);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return getParent() != null && getParent().getHeight() > getPreferredSize().height;
        }
    }
}
