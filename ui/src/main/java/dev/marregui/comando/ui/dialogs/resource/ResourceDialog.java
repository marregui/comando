package dev.marregui.comando.ui.dialogs.resource;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRootPane;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.Scrollable;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.resource.Attribute;
import dev.marregui.comando.core.resource.ResourceKind;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.ui.dialogs.sessions.StoredConversation;
import dev.marregui.comando.ui.kit.async.Edt;
import dev.marregui.comando.ui.kit.async.UiErrands;
import dev.marregui.comando.ui.kit.desktop.FileChoosers;
import dev.marregui.comando.ui.kit.desktop.Platform;
import dev.marregui.comando.ui.kit.format.KeyText;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.kit.text.Tooltips;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.panels.TabCard;

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
    static final Dimension DEFAULT_SIZE = new Dimension(680, 520);

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

    private static final System.Logger LOG = System.getLogger(ResourceDialog.class.getName());

    /** The ⓘ after a label whose attribute says more; the sidebar's rows use the same mark. */
    private static final Icon INFO = Icons.get("info", 12);
    /** The explanation behind a label's ⓘ: the sidebar's card, in its explaining form. */
    private final TabCard explainer = new TabCard();

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
    /** The conversations Claude Code stored on disk, read when the dialog opens; none for a kind without them. */
    private Supplier<List<StoredConversation>> conversations = List::of;

    public ResourceDialog(final Window owner, final Config<S> config, final IntentSink intents) {
        this.config = config;
        this.intents = intents;
        this.owner = owner;
        this.noun = config.kind().noun();
        this.session = noun.equals("session");
        final JPanel form = new WidthTracking(new GridBagLayout());
        final GridBagConstraints g = new GridBagConstraints();
        int row = 0;
        int advancedRow = 0;
        for (final Attribute a : config.kind().attributes()) {
            final JComponent field = field(a);
            fields.put(a.key(), field);
            final JComponent cell = field instanceof JTextField t && (a.kind() == Attribute.Kind.DIRECTORY || a.kind() == Attribute.Kind.DIRECTORIES)
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
        final JScrollPane formScroll = new JScrollPane(form, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        formScroll.setBorder(BorderFactory.createEmptyBorder());
        formScroll.getVerticalScrollBar().setUnitIncrement(16);

        message.setText(" ");
        message.setName("message");
        message.setForeground(Theme.MUTED);
        confirmRow.setName("confirm");
        confirmRow.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Theme.ATTENTION),
                BorderFactory.createEmptyBorder(Theme.GAP, Theme.GAP, Theme.GAP, Theme.GAP)));
        final JPanel confirmButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.GAP, 0));
        confirmButtons.add(confirmNo);
        confirmButtons.add(confirmYes);
        confirmYes.addActionListener(e -> {
            final Runnable r = onConfirm;
            hideConfirm();
            if (r != null) {
                r.run();
            }
        });
        confirmNo.addActionListener(e -> hideConfirm());
        confirmRow.add(confirmText, BorderLayout.CENTER);
        confirmRow.add(confirmButtons, BorderLayout.EAST);
        confirmRow.setVisible(false);

        final JPanel right = new JPanel(new BorderLayout(0, Theme.GAP));
        right.setBorder(BorderFactory.createEmptyBorder(Theme.PAD, Theme.PAD, Theme.PAD, Theme.PAD));
        right.add(formScroll, BorderLayout.CENTER);
        final String context = config.kind().context();
        if (!context.isEmpty()) {
            // what holds for every resource of the kind, which the form does not ask: where a session runs
            final JLabel line = Plain.only(new JLabel(context));
            line.setName("context");
            line.setToolTipText(context);
            line.setForeground(Theme.MUTED);
            right.add(line, BorderLayout.NORTH);
        }
        final JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.GAP, 0));
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
        save = button("Save", "check", "Save the form and close (⏎)", this::saveAndClose);
        save.putClientProperty("JButton.buttonType", "default");
        actions.add(save);
        final JPanel south = new JPanel(new BorderLayout(0, Theme.GAP));
        south.add(confirmRow, BorderLayout.NORTH);
        south.add(message, BorderLayout.CENTER);
        south.add(actions, BorderLayout.SOUTH);
        right.add(south, BorderLayout.SOUTH);

        content.add(right, BorderLayout.CENTER);
        content.setPreferredSize(DEFAULT_SIZE);
    }

    /** Shows the dialog on the resource named {@code select}, or on a new one when it is empty or unknown. */
    public void show(final AppState s, final String select) {
        open(() -> prepare(s, select));
    }

    /** Shows the dialog on a new resource, as Add does. */
    public void showNew(final AppState s) {
        open(() -> prepareNew(s));
    }

    /** Shows the dialog on a new resource named {@code name}, not saved yet: a panel's "New session ‹title›…". */
    public void showNew(final AppState s, final String name) {
        open(() -> {
            render(s);
            replacingForm(() -> add(name));
        });
    }

    /** Shows the dialog on a copy of {@code of}, not saved yet, as Clone does. */
    public void showClone(final AppState s, final String of) {
        open(() -> prepareClone(s, of));
    }

    private void open(final Runnable prepare) {
        if (dialog == null) {
            dialog = new JDialog(owner, title());
            dialog.setModal(false);
            dialog.setContentPane(content);
            install(dialog.getRootPane());
            // the close button is Esc: unsaved changes are kept or discarded on the user's word, never left behind
            dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            dialog.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(final WindowEvent e) {
                    close();
                }
            });
            dialog.pack();
        }
        prepare.run();
        readConversations();
        dialog.setTitle(title());
        if (!dialog.isVisible()) {
            // placed at each opening: the main window may have moved, or restored its bounds, since the last
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
    void prepare(final AppState s, final String select) {
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
        final String capital = Character.toUpperCase(noun.charAt(0)) + noun.substring(1);
        return editing.isEmpty() ? "New " + noun : capital + " " + editing;
    }

    void prepareNew(final AppState s) {
        render(s);
        replacingForm(this::add);
    }

    void prepareClone(final AppState s, final String of) {
        render(s);
        if (names.contains(of)) {
            replacingForm(() -> cloneOf(of));
        } else {
            prepare(s, "");
        }
    }

    /** Default button, Esc and ⌘⏎ on the root pane the content is shown in. */
    void install(final JRootPane rootPane) {
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
    public void render(final AppState s) {
        state = s;
        names = config.specs().apply(s).stream().map(config.kind()::nameOf).toList();
        conversationField().ifPresent(c -> c.held(heldElsewhere(s)));
        refreshUntouchedFields();
        updateOpenClose();
        showOutcome(s);
    }

    private void refreshUntouchedFields() {
        if (editing.isEmpty() || state == null) {
            return;
        }
        for (final S spec : config.specs().apply(state)) {
            if (config.kind().nameOf(spec).equals(editing)) {
                final Map<String, String> stored = config.kind().toAttributes(spec);
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
    static Map<String, String> merge(final Map<String, String> form, final Map<String, String> filled, final Map<String, String> stored) {
        final Map<String, String> merged = new HashMap<>(form);
        for (final Map.Entry<String, String> e : stored.entrySet()) {
            if (Objects.equals(form.get(e.getKey()), filled.get(e.getKey()))) {
                merged.put(e.getKey(), e.getValue());
            }
        }
        return merged;
    }

    /** The latest notice about {@code subject} at or after {@code sinceMillis}, or null. */
    static Notice outcome(final List<Notice> notices, final String subject, final long sinceMillis) {
        for (int i = notices.size() - 1; i >= 0; i--) {
            final Notice n = notices.get(i);
            if (n.atMillis() < sinceMillis) {
                return null;
            }
            if (n.subject().equals(subject)) {
                return n;
            }
        }
        return null;
    }

    private void showOutcome(final AppState s) {
        if (watched.isEmpty()) {
            return;
        }
        final Notice n = outcome(s.notices(), watched, watchedSince);
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

    private void watch(final String name) {
        watched = config.kind().noticeSubject(name);
        watchedSince = System.currentTimeMillis();
        shown = null;
    }

    private void unwatch() {
        watched = "";
        shown = null;
        opening = "";
    }

    private void edit(final String name) {
        if (state == null) {
            return;
        }
        for (final S spec : config.specs().apply(state)) {
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

    private void add(final String name) {
        hideConfirm();
        unwatch();
        editing = "";
        // the kind's template says where a new one starts: a session in the kit's folder (record 125)
        final Map<String, String> values = config.kind().toAttributes(config.kind().template(name));
        filled = values;
        fill(values);
        setAdvancedOpen(config.kind().showAdvanced(values));
        say(Theme.MUTED, "New " + config.kind().noun() + "; Save adds it.");
        advice = "";
        updateOpenClose();
        retitle();
        // a new resource is named first: the keyboard goes to the first field, its default selected to type over;
        // later, as the dialog may only now be opening
        final JComponent first = fields.values().stream().filter(JTextComponent.class::isInstance).findFirst().orElse(fields.values().iterator().next());
        if (first instanceof JTextComponent t) {
            t.selectAll();
        }
        Edt.later(first::requestFocusInWindow);
    }

    private void cloneOf(final String of) {
        final S spec = spec(of);
        if (spec == null) {
            return;
        }
        final String name = unique(config.kind().nameOf(spec) + " copy");
        unwatch();
        editing = "";
        filled = config.kind().toAttributes(config.kind().cloneOf(spec, name));
        fill(filled);
        setAdvancedOpen(config.kind().showAdvanced(filled));
        say(Theme.MUTED, "Clone of " + config.kind().nameOf(spec) + "; Save adds it.");
        updateOpenClose();
        retitle();
    }

    /** Save and ⏎: the form saved, the dialog closes; an invalid or refused form stays open with the reason. */
    private void saveAndClose() {
        if (save()) {
            hide();
        }
    }

    /** Saves the form; false, with the reason in the message, when it is invalid or refused. */
    private boolean save() {
        try {
            final S spec = config.kind().fromAttributes(read());
            final String name = config.kind().nameOf(spec);
            if (!name.equals(editing) && names.contains(name)) {
                say(Theme.DANGER, "A " + config.kind().noun() + " named " + name + " exists; choose another name");
                return false;
            }
            final Status current = editing.isEmpty() ? Status.CLOSED : config.statuses().apply(state).getOrDefault(editing, Status.CLOSED);
            if (!name.equals(editing) && !editing.isEmpty() && session && current != Status.CLOSED && current != Status.FAILED) {
                // the application refuses this rename; "Saved" would leave the form on a name that does not exist
                say(Theme.DANGER, "Stop " + editing + " before renaming it");
                return false;
            }
            intents.dispatch(config.save().apply(editing, spec));
            filled = config.kind().toAttributes(spec);
            editing = config.kind().nameOf(spec);
            for (final Attribute a : config.kind().attributes()) {
                if (a.kind() == Attribute.Kind.DIRECTORY && !filled.getOrDefault(a.key(), "").isBlank()) {
                    lastDirectory = filled.get(a.key());
                    break;
                }
            }
            say(Theme.MUTED, "Saved " + editing);
            retitle();
            updateOpenClose();
            return true;
        } catch (final IllegalArgumentException e) {
            say(Theme.DANGER, e.getMessage());
            return false;
        }
    }

    private void testForm() {
        try {
            final S spec = config.kind().fromAttributes(read());
            intents.dispatch(config.test().apply(spec));
            watch(config.kind().nameOf(spec));
            opening = "";
            say(Theme.MUTED, "Testing " + config.kind().nameOf(spec) + "…");
        } catch (final IllegalArgumentException e) {
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
        final String verb = session ? "starting" : "connecting";
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

    /** Esc: an open question gets its safe answer; else the dialog closes, asking first about unsaved changes. */
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

    /** The window's close button: an open question gets its safe answer, then the dialog closes as with Esc. */
    void close() {
        hideConfirm();
        escape();
    }

    /**
     * Runs {@code replace}, which fills the form with something else, at once when the form has no unsaved
     * changes; else asks first. Discard runs it; Keep editing (or Esc, or any other button) cancels it.
     */
    private void replacingForm(final Runnable replace) {
        explainer.hide();
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
        explainer.hide();
        if (dialog != null) {
            dialog.setVisible(false);
        }
    }

    /** Whether the form differs from what it was last filled with or saved as. */
    boolean isDirty() {
        return !filled.isEmpty() && !read().equals(filled);
    }

    private void confirm(final String question, final String yes, final String no, final Runnable action) {
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
        final Status st = editing.isEmpty() || state == null ? Status.CLOSED : config.statuses().apply(state).getOrDefault(editing, Status.CLOSED);
        final boolean open = st.isUsable();
        openClose.setText(open ? (session ? "Stop" : "Disconnect") : (session ? "Start" : "Connect"));
        openClose.setIcon(Icons.get(open ? (session ? "stop" : "unplug") : (session ? "play" : "plug")));
        openClose.setToolTipText(open ? null : "Save if changed, then " + (session ? "start the session" : "connect"));
    }

    /** The stored conversations the session form offers, read from {@code catalogue} each time the dialog opens. */
    public void conversations(final Supplier<List<StoredConversation>> catalogue) {
        conversations = catalogue;
    }

    /** Offers {@code stored} at once; for tests, which fill the form without opening the dialog. */
    void offer(final List<StoredConversation> stored) {
        conversationField().ifPresent(c -> c.stored(stored));
    }

    private void readConversations() {
        if (conversationField().isEmpty()) {
            return;
        }
        final Supplier<List<StoredConversation>> catalogue = conversations;
        UiErrands.start("read-conversations", () -> {
            try {
                final List<StoredConversation> read = catalogue.get();
                Edt.later(() -> offer(read));
            } catch (final RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "cannot read the stored conversations", e);
            }
        });
    }

    private Optional<ConversationField> conversationField() {
        return fields.values().stream().filter(ConversationField.class::isInstance).map(ConversationField.class::cast).findFirst();
    }

    /** The conversation ids the other sessions hold: the form's own is not among them. */
    private Set<String> heldElsewhere(final AppState s) {
        final Set<String> ids = new HashSet<>();
        for (final S spec : config.specs().apply(s)) {
            if (!config.kind().nameOf(spec).equals(editing)) {
                final String id = config.kind().toAttributes(spec).getOrDefault("claudeSessionId", "");
                if (!id.isEmpty()) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    /** A stored conversation picked: the session runs in its folder, where Claude Code keeps it, and More shows that. */
    private void runIn(final String cwd) {
        if (cwd.isBlank() || !(fields.get("workDir") instanceof JTextField dir) || dir.getText().equals(cwd)) {
            return;
        }
        dir.setText(cwd);
        setAdvancedOpen(true);
    }

    private void setAdvancedOpen(final boolean open) {
        if (advanced.getComponentCount() == 0) {
            return;
        }
        advanced.setVisible(open);
        advancedToggle.setText((open ? "▾ " : "▸ ") + config.kind().advancedLabel());
        content.revalidate();
    }

    private void say(final Color color, final String text) {
        message.setForeground(color);
        message.setText(text.isEmpty() ? " " : text);
        message.setToolTipText(text.isEmpty() ? null : Plain.text(text)); // the whole text when the line cuts it
    }

    /**
     * Shows the kind's warning about the form when it changed, or always when {@code always}; clears a warning that no
     * longer holds.
     */
    private void showAdvice(final boolean always) {
        final String now = config.kind().advice(read());
        if (now.equals(advice) && !always) {
            return;
        }
        final boolean showing = !advice.isEmpty() && advice.equals(message.getText());
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

    private S spec(final String name) {
        for (final S spec : config.specs().apply(state)) {
            if (config.kind().nameOf(spec).equals(name)) {
                return spec;
            }
        }
        return null;
    }

    private String unique(final String base) {
        String name = base;
        for (int i = 2; names.contains(name); i++) {
            name = base + " " + i;
        }
        return name;
    }

    private void fill(final Map<String, String> values) {
        filling = true;
        try {
            for (final Attribute a : config.kind().attributes()) {
                final JComponent f = fields.get(a.key());
                final String v = values.getOrDefault(a.key(), "");
                if (f instanceof ConversationField c) {
                    c.fill(values, !editing.isEmpty());
                    continue;
                }
                switch (f) {
                    case JCheckBox c -> c.setSelected(Boolean.parseBoolean(v));
                    case JComboBox<?> c -> {
                        showEmpty(c, a);
                        select(c, v);
                    }
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
    private static void select(final JComboBox<?> c, final String v) {
        if (!c.isEditable() && c.getModel() instanceof DefaultComboBoxModel<?> m && m.getIndexOf(v) < 0) {
            @SuppressWarnings("unchecked")
            final DefaultComboBoxModel<String> strings = (DefaultComboBoxModel<String>) m;
            strings.addElement(v);
        }
        c.setSelectedItem(v);
    }

    /** The form's values, over those it was filled with: a value the form does not show (a session's budget) is kept. */
    private Map<String, String> read() {
        final Map<String, String> m = new LinkedHashMap<>(filled);
        for (final Attribute a : config.kind().attributes()) {
            final JComponent f = fields.get(a.key());
            if (f instanceof ConversationField c) {
                c.into(m);
                continue;
            }
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

    private JComponent field(final Attribute a) {
        if (a.kind() == Attribute.Kind.CONVERSATION) {
            final ConversationField c = new ConversationField();
            c.onChange(this::formChanged);
            c.onPicked(this::runIn);
            c.getAccessibleContext().setAccessibleName(a.label());
            c.picker().getAccessibleContext().setAccessibleName(a.label());
            c.picker().setToolTipText(Tooltips.wrapped(a.hint()));
            c.setName("conversation");
            return c;
        }
        final JComponent f = switch (a.kind()) {
            case SECRET -> new JPasswordField(24);
            case BOOLEAN -> new JCheckBox();
            case CHOICE -> {
                final JComboBox<String> c = new JComboBox<>(a.choices().toArray(String[]::new));
                c.setEditable(a.editable());
                c.setRenderer(new EmptyAs(c));
                showEmpty(c, a);
                c.addActionListener(e -> formChanged());
                yield c;
            }
            default -> new JTextField(24);
        };
        if (f instanceof JTextField t) {
            t.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(final DocumentEvent e) {
                    formChanged();
                }

                @Override
                public void removeUpdate(final DocumentEvent e) {
                    formChanged();
                }

                @Override
                public void changedUpdate(final DocumentEvent e) {
                    formChanged();
                }
            });
        }
        f.setName(a.key());
        f.getAccessibleContext().setAccessibleName(a.label());
        if (!a.hint().isEmpty()) {
            f.setToolTipText(Tooltips.wrapped(a.hint()));
            f.getAccessibleContext().setAccessibleDescription(a.about().isEmpty() ? a.hint() : a.about());
        }
        return f;
    }

    /**
     * The ⓘ after {@code label}: a click shows {@code a}'s explanation in the sidebar's card under the label, a second
     * click closes it, and so does the pointer drifting off the label without reaching the card, or the card's close
     * button (record 125).
     */
    private void explains(final JLabel label, final Attribute a) {
        label.setIcon(INFO);
        label.setHorizontalTextPosition(JLabel.LEADING);
        label.setIconTextGap(4);
        label.setName("about:" + a.key());
        label.setToolTipText("What is " + a.label() + "? Click the ⓘ");
        label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(final MouseEvent e) {
                explain(label, a);
            }

            @Override
            public void mouseEntered(final MouseEvent e) {
                if (a.key().equals(explainer.shown())) {
                    explainer.stay();
                }
            }

            @Override
            public void mouseExited(final MouseEvent e) {
                explainer.left();
            }
        });
    }

    /** The card behind the labels' ⓘ; for tests. */
    TabCard explainer() {
        return explainer;
    }

    /** Shows {@code a}'s explanation under {@code label}, or closes it when it is the one shown. */
    void explain(final JLabel label, final Attribute a) {
        if (a.key().equals(explainer.shown())) {
            explainer.hide();
        } else {
            explainer.explain(a.key(), label, new Rectangle(0, 0, label.getWidth(), label.getHeight()), a.label(), a.about());
        }
    }

    /**
     * What the empty value of {@code a} stands for, in its place: the list's empty row, and the field of an open choice
     * while nothing is typed. Set again at each fill, as the default may have changed since (the CLI's model, read
     * from a session's start).
     */
    private static void showEmpty(final JComboBox<?> c, final Attribute a) {
        c.putClientProperty(EmptyAs.KEY, a.whenEmpty());
        c.putClientProperty("JTextField.placeholderText", a.whenEmpty().isEmpty() ? null : a.whenEmpty());
        c.repaint();
    }

    /** A choice's renderer that draws the empty value as what it stands for, muted, instead of a blank row. */
    private static final class EmptyAs implements ListCellRenderer<Object> {
        static final String KEY = "comando.whenEmpty";
        private final JComboBox<?> owner;
        private final ListCellRenderer<Object> base;

        @SuppressWarnings("unchecked")
        EmptyAs(final JComboBox<?> owner) {
            this.owner = owner;
            this.base = (ListCellRenderer<Object>) owner.getRenderer();
        }

        @Override
        public Component getListCellRendererComponent(final JList<?> list, final Object value, final int index, final boolean selected, final boolean focus) {
            final String empty = owner.getClientProperty(KEY) instanceof String t ? t : "";
            final boolean stands = (value == null || value.toString().isEmpty()) && !empty.isEmpty();
            final Component c = base.getListCellRendererComponent(list, stands ? empty : value, index, selected, focus);
            if (stands && !selected) {
                c.setForeground(Theme.MUTED);
            }
            return c;
        }
    }

    /**
     * A label, with the ⓘ after it when the attribute says more, the field and, under it, the hint in a muted line;
     * returns the next free row.
     */
    private int addRow(final JPanel panel, final int row, final Attribute a, final JComponent cell) {
        final GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.gridy = row;
        g.anchor = GridBagConstraints.LINE_END;
        g.insets = new Insets(3, 3, 0, 3);
        final JLabel label = new JLabel(a.label());
        label.setLabelFor(cell);
        if (!a.about().isEmpty()) {
            explains(label, a);
        }
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
        final JLabel hint = new JLabel(a.hint()) {
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

    private JComponent withChooser(final JTextField field, final String label, final boolean many) {
        final JPanel p = new JPanel(new BorderLayout(4, 0));
        p.add(field, BorderLayout.CENTER);
        final JButton browse = new JButton("…");
        browse.setToolTipText("Choose a folder");
        browse.getAccessibleContext().setAccessibleName("Choose " + label);
        browse.addActionListener(e -> {
            final String text = field.getText();
            FileChoosers.directory(content, label, chooserStart(text)).ifPresent(dir -> {
                final String path = dir.toAbsolutePath().toString();
                lastDirectory = path;
                field.setText(many && !text.isBlank() ? text + File.pathSeparator + path : path);
            });
        });
        p.add(browse, BorderLayout.EAST);
        return p;
    }

    /** Where the folder chooser opens: the field's first folder, else the home folder. */
    static Path chooserStart(final String text) {
        for (final String part : text.split(Pattern.quote(File.pathSeparator))) {
            if (!part.isBlank()) {
                try {
                    return Path.of(part.strip());
                } catch (final InvalidPathException e) {
                    break;
                }
            }
        }
        return Path.of(System.getProperty("user.home"));
    }

    private JButton button(final String text, final String icon, final String tip, final Runnable r) {
        final JButton b = new JButton(text, Icons.get(icon));
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

        WidthTracking(final GridBagLayout layout) {
            super(layout);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(final Rectangle visible, final int orientation, final int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(final Rectangle visible, final int orientation, final int direction) {
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
