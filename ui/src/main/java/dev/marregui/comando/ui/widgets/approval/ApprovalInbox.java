package dev.marregui.comando.ui.widgets.approval;

import com.fasterxml.jackson.databind.JsonNode;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.ApprovalDecision;
import dev.marregui.comando.core.model.ApprovalKind;
import dev.marregui.comando.core.model.PermissionRules;
import dev.marregui.comando.ui.kit.async.Edt;
import dev.marregui.comando.ui.kit.desktop.Platform;
import dev.marregui.comando.ui.kit.format.Json;
import dev.marregui.comando.ui.kit.format.Labels;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.overlay.Overlay;
import dev.marregui.comando.ui.kit.text.DiffPane;
import dev.marregui.comando.ui.kit.text.Diffs;
import dev.marregui.comando.ui.kit.text.Editors;
import dev.marregui.comando.ui.kit.text.HtmlPane;
import dev.marregui.comando.ui.kit.text.MarkdownHtml;
import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.widget.Widget;
import dev.marregui.comando.ui.widget.WidgetContext;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

import javax.swing.AbstractAction;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.Border;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Every pending approval of every session as a card. The keys act while the focus is anywhere in a card: Enter or
 * A gives the primary answer, S allows for the session, L twice always allows (only where the rule may be saved;
 * edits and MCP tools are allowed for the session only), D or Escape denies (with the note, if typed), Up and Down
 * move between cards. In a text field of the card the letters type; Enter in the note denies with the note when it
 * holds text and gives the primary answer when it is empty, the menu key with Enter denies with the note, Escape
 * returns to the card. A question's Other answer is a plain text box in the editors' font ({@link Editors#plain}), 120
 * characters wide: Enter starts a line, no key in it answers (the Answer button does), Escape returns to the card; it
 * holds nothing once the card is answered. Questions show their options with descriptions; a plan shows rendered; an edit shows its
 * diff.
 *
 * <p>Each time the inbox comes forward ({@link #shown}) it shows its top, the oldest request first, unless the keyboard
 * is in a card. A request arriving while it is in front moves nothing.</p>
 *
 * <p>When the card that holds the keyboard leaves because the user answered it here, the keyboard moves to the
 * card that took its place. When it leaves for any other reason (withdrawn by Claude Code, its session ended), the
 * keyboard goes to the inbox itself, which has no answer keys: what the user was typing answers nothing.</p>
 */
public final class ApprovalInbox implements Widget<List<Approval>> {

    /** How long a first L waits for the second. */
    static final long CONFIRM_MILLIS = 3_000;
    private static final int AGE_TICK_MILLIS = 30_000;
    private static final int MENU = Platform.menuShortcutMask();
    private static final String EMPTY = "No approvals waiting. Permission requests from every session arrive here.";
    /** The Other answer's size: a paragraph of five lines, 120 characters wide. */
    static final int OTHER_ROWS = 5;
    static final int OTHER_COLUMNS = 120;

    /** Where the keyboard focus is and how to move it: the window's focus manager, or a fake in tests. */
    interface Focus {
        Component owner();

        void request(Component c);

        Focus WINDOW = new Focus() {
            @Override
            public Component owner() {
                return KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            }

            @Override
            public void request(Component c) {
                c.requestFocusInWindow();
            }
        };
    }

    private final WidgetContext context;
    private final LongSupplier clock;
    private final Focus focus;
    private final BiFunction<String, Function<String, String>, PermissionRules.Rules> ruleSource;
    private final JPanel list = new ListPanel();
    private final JScrollPane scroll = new JScrollPane(list);
    private final Overlay overlay = new Overlay(scroll);
    private final Map<Long, Card> cards = new LinkedHashMap<>();
    /** The approvals the user answered in this inbox whose cards are still shown. */
    private final Set<Long> answered = new HashSet<>();
    private final Timer ages = new Timer(AGE_TICK_MILLIS, e -> tick());

    public ApprovalInbox(WidgetContext context) {
        this(context, System::currentTimeMillis, Focus.WINDOW);
    }

    ApprovalInbox(WidgetContext context, LongSupplier clock, Focus focus) {
        this(context, clock, focus, PermissionRules::rules);
    }

    ApprovalInbox(WidgetContext context, LongSupplier clock, Focus focus,
                  BiFunction<String, Function<String, String>, PermissionRules.Rules> ruleSource) {
        this.context = context;
        this.clock = clock;
        this.focus = focus;
        this.ruleSource = ruleSource;
        list.setName("inbox");
        // where the keyboard goes when the card holding it is withdrawn: no answer keys here
        list.setFocusable(true);
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setBorder(BorderFactory.createEmptyBorder(Theme.PAD, Theme.PAD, Theme.PAD, Theme.PAD));
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        overlay.showEmpty(EMPTY);
    }

    @Override
    public JComponent component() {
        return overlay;
    }

    @Override
    public void render(List<Approval> approvals) {
        List<Card> before = new ArrayList<>(cards.values());
        Set<Long> answeredHere = new HashSet<>(answered);
        Card holding = cardOf(focus.owner());
        Set<Long> ids = new HashSet<>();
        for (Approval a : approvals) {
            ids.add(a.id());
            if (!cards.containsKey(a.id())) {
                Card c = new Card(a);
                cards.put(a.id(), c);
                list.add(c);
                list.add(Box.createVerticalStrut(Theme.GAP));
            }
        }
        cards.values().removeIf(c -> {
            if (ids.contains(c.approval.id())) {
                return false;
            }
            int i = indexOf(c);
            list.remove(c);
            if (i >= 0 && i < list.getComponentCount()) {
                list.remove(i); // the strut after the card
            }
            return true;
        });
        answered.retainAll(ids);
        if (cards.isEmpty()) {
            overlay.showEmpty(EMPTY);
            ages.stop();
        } else {
            overlay.showContent();
            if (!ages.isRunning()) {
                ages.start();
            }
        }
        list.revalidate();
        list.repaint();
        if (holding != null && !cards.containsValue(holding)) {
            if (answeredHere.contains(holding.approval.id())) {
                Card next = successor(before, holding);
                if (next != null) {
                    focus.request(next);
                }
            } else {
                // withdrawn while the user worked in it: the next key must not answer a card the user never looked at
                focus.request(list);
            }
        }
    }

    @Override
    public void unmount() {
        ages.stop();
    }

    /**
     * The inbox came forward: it shows its top, so the first request is the first thing read; again once the cards are
     * laid out, as a card's text, set as it is built, can scroll its viewport. Nothing moves while the keyboard is in a
     * card: the user is answering it.
     */
    public void shown() {
        if (holdsFocus()) {
            return;
        }
        toTop();
        Edt.later(() -> {
            if (!holdsFocus()) {
                toTop();
            }
        });
    }

    private void toTop() {
        scroll.getViewport().setViewPosition(new Point(0, 0));
    }

    /** The scroll position, for tests. */
    int scrollTop() {
        return scroll.getViewport().getViewPosition().y;
    }

    /** Moves the keyboard focus to the oldest card. */
    public void focusFirst() {
        if (!cards.isEmpty()) {
            focus.request(cards.values().iterator().next());
        }
    }

    /** Whether the keyboard focus is inside a card, where the card's keys take precedence over window-wide ones. */
    public boolean holdsFocus() {
        return cardOf(focus.owner()) != null;
    }

    /** Refreshes each card's age. */
    void tick() {
        long now = clock.getAsLong();
        for (Card c : cards.values()) {
            c.age.setText(age(c.approval.createdMillis(), now));
        }
    }

    boolean ticking() {
        return ages.isRunning();
    }

    /** The card that took the place of {@code gone} in view order: the next remaining one, else the previous one. */
    private Card successor(List<Card> before, Card gone) {
        int at = before.indexOf(gone);
        for (int i = at + 1; i < before.size(); i++) {
            if (cards.containsValue(before.get(i))) {
                return before.get(i);
            }
        }
        for (int i = at - 1; i >= 0; i--) {
            if (cards.containsValue(before.get(i))) {
                return before.get(i);
            }
        }
        return null;
    }

    private Card cardOf(Component c) {
        for (Component x = c; x != null; x = x.getParent()) {
            if (x instanceof Card card && cards.containsValue(card)) {
                return card;
            }
        }
        return null;
    }

    private void neighbour(Card from, int step) {
        List<Card> order = new ArrayList<>(cards.values());
        int i = order.indexOf(from) + step;
        if (i >= 0 && i < order.size()) {
            focus.request(order.get(i));
        }
    }

    private int indexOf(Component c) {
        for (int i = 0; i < list.getComponentCount(); i++) {
            if (list.getComponent(i) == c) {
                return i;
            }
        }
        return -1;
    }

    private void answer(Approval a, ApprovalDecision d) {
        answered.add(a.id());
        context.intents().dispatch(new Intent.ApprovalAnswered(a.id(), d));
    }

    static String age(long createdMillis, long now) {
        String ago = Labels.ago(createdMillis, now);
        return ago.isEmpty() || ago.equals("now") ? ago : ago + " ago";
    }

    /** One approval. */
    private final class Card extends JPanel {
        private final Approval approval;
        private final JTextField note = new JTextField(28);
        private final JLabel age = Plain.only(new JLabel());
        /** Each question's option buttons: radio buttons in a group, or check boxes when it allows several. */
        private final Map<String, List<AbstractButton>> questions = new LinkedHashMap<>();
        private final Map<String, JTextComponent> other = new LinkedHashMap<>();
        /** The rules "For session" and "Always" add; null for anything but a tool call. */
        private final PermissionRules.Rules rules;
        private final JPanel confirm = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.GAP, 0));
        private final JLabel confirmText = Plain.only(new JLabel());
        private final JLabel choose = Plain.only(new JLabel("Choose an answer"));
        private JButton primaryButton;
        /** When a first L asked for the second, or -1; a click on Always arms without a deadline. */
        private long armedAt = -1;
        private boolean armedByClick;
        private Timer disarm;

        Card(Approval a) {
            this.approval = a;
            JsonNode in = Json.parse(a.inputJson());
            this.rules = a.kind() == ApprovalKind.TOOL ? ruleSource.apply(a.toolName(), k -> in.path(k).asText("")) : null;
            setLayout(new BorderLayout(0, Theme.GAP));
            setBorder(border(Theme.CARD_BORDER));
            setBackground(Theme.CARD);
            setFocusable(true);
            setAlignmentX(Component.LEFT_ALIGNMENT);
            add(header(a), BorderLayout.NORTH);
            add(body(a, in), BorderLayout.CENTER);
            add(footer(a), BorderLayout.SOUTH);
            keys();
            wire(this);
            updateReady();
        }

        private JComponent header(Approval a) {
            JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            p.setOpaque(false);
            JLabel head = new JLabel("<html><b>" + MarkdownHtml.escape(a.session()) + "</b>&nbsp;&nbsp;" + MarkdownHtml.escape(title(a)) + "&nbsp;&nbsp;</html>");
            head.setIcon(Icons.get(switch (a.kind()) {
                case QUESTION -> "question";
                case PLAN -> "plan";
                case SQL_WRITE -> "database";
                case TOOL -> "warning";
            }));
            age.setName("age");
            age.setForeground(Theme.MUTED);
            age.setText(ApprovalInbox.age(a.createdMillis(), clock.getAsLong()));
            p.add(head);
            p.add(age);
            return p;
        }

        private JComponent body(Approval a, JsonNode in) {
            return switch (a.kind()) {
                case QUESTION -> questionsPanel(in);
                case PLAN -> {
                    HtmlPane plan = new HtmlPane();
                    plan.setMarkdown(in.path("plan").asText(""));
                    JScrollPane sp = new JScrollPane(plan);
                    sp.setPreferredSize(new Dimension(400, 260));
                    yield sp;
                }
                case SQL_WRITE -> code(in.path("sql").asText(""), "sql", 8);
                case TOOL -> {
                    Diffs.Edit edit = Diffs.edit(a.toolName(), in);
                    if (edit != null) {
                        yield diff(edit.path(), edit.before(), edit.after());
                    }
                    yield a.toolName().equals("Bash") ? code(in.path("command").asText("") + describe(in), "sh", 10) : code(Json.pretty(in), "json", 12);
                }
            };
        }

        private static String describe(JsonNode in) {
            String d = in.path("description").asText("");
            return d.isEmpty() ? "" : "\n# " + d;
        }

        private JComponent questionsPanel(JsonNode in) {
            JPanel p = new JPanel();
            p.setOpaque(false);
            p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
            for (JsonNode q : in.path("questions")) {
                String question = q.path("question").asText("");
                JLabel label = new JLabel("<html><b>" + MarkdownHtml.escape(question) + "</b></html>");
                label.setAlignmentX(Component.LEFT_ALIGNMENT);
                p.add(label);
                boolean multi = q.path("multiSelect").asBoolean(false);
                ButtonGroup group = new ButtonGroup();
                List<AbstractButton> buttons = new ArrayList<>();
                for (JsonNode o : q.path("options")) {
                    String option = o.path("label").asText("");
                    // option labels and descriptions come from Claude: shown as text, never as HTML
                    AbstractButton b = Plain.only(multi ? new JCheckBox() : new JRadioButton());
                    b.setText(option); // after the guard: Swing decides on HTML when the text is set
                    b.setActionCommand(option);
                    b.setOpaque(false);
                    b.setAlignmentX(Component.LEFT_ALIGNMENT);
                    b.addItemListener(e -> updateReady());
                    if (!multi) {
                        group.add(b);
                    }
                    buttons.add(b);
                    p.add(b);
                    String description = o.path("description").asText("");
                    if (!description.isBlank()) {
                        JTextArea d = muted(description);
                        d.setName("description");
                        // indented under the option's text, past the radio or check mark
                        d.setBorder(BorderFactory.createEmptyBorder(0, 24, 2, 0));
                        p.add(d);
                    }
                }
                JLabel otherLabel = Plain.only(new JLabel("Other answer"));
                otherLabel.setForeground(Theme.MUTED);
                otherLabel.setBorder(BorderFactory.createEmptyBorder(Theme.GAP, 0, 2, 0));
                otherLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
                p.add(otherLabel);
                JTextArea free = Editors.plain(OTHER_ROWS, OTHER_COLUMNS);
                free.setName("other");
                free.getDocument().addDocumentListener(new DocumentListener() {
                    @Override
                    public void insertUpdate(DocumentEvent e) {
                        updateReady();
                    }

                    @Override
                    public void removeUpdate(DocumentEvent e) {
                        updateReady();
                    }

                    @Override
                    public void changedUpdate(DocumentEvent e) {
                        updateReady();
                    }
                });
                JScrollPane editor = new JScrollPane(free);
                editor.setAlignmentX(Component.LEFT_ALIGNMENT);
                // its columns wide and its rows tall, scrolling past them; narrower only when the card is
                editor.setMaximumSize(editor.getPreferredSize());
                p.add(editor);
                questions.put(question, buttons);
                other.put(question, free);
            }
            return p;
        }

        private JComponent footer(Approval a) {
            JPanel p = new JPanel();
            p.setOpaque(false);
            p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.GAP, 0));
            buttons.setOpaque(false);
            buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
            switch (a.kind()) {
                case QUESTION -> {
                    primaryButton = button("Answer  ⏎", "check", this::answerQuestions, true);
                    buttons.add(primaryButton);
                    buttons.add(button("Skip", "deny", this::deny, false));
                }
                case PLAN -> {
                    buttons.add(button("Approve plan  ⏎", "check", () -> answer(a, ApprovalDecision.allowOnce()), true));
                    JButton edits = button("Approve, auto-accept edits", "check", () -> answer(a, new ApprovalDecision.Allow(ApprovalDecision.Scope.SESSION)), false);
                    edits.setToolTipText("Approve the plan and accept file edits for the rest of this session");
                    buttons.add(edits);
                    buttons.add(button("Keep planning", "deny", this::deny, false));
                }
                case SQL_WRITE -> {
                    buttons.add(button("Run  ⏎", "check", () -> answer(a, ApprovalDecision.allowOnce()), true));
                    buttons.add(button("Deny  D", "deny", this::deny, false));
                }
                case TOOL -> {
                    buttons.add(button("Allow once  ⏎", "check", () -> answer(a, ApprovalDecision.allowOnce()), true));
                    JButton session = button("For session  S", null, () -> answer(a, new ApprovalDecision.Allow(ApprovalDecision.Scope.SESSION)), false);
                    session.setName("session");
                    session.setToolTipText(Plain.text("Adds " + rules.text() + " for the rest of this session"));
                    buttons.add(session);
                    if (rules.persistable()) {
                        JButton always = button("Always  L", null, this::alwaysClicked, false);
                        always.setName("always");
                        always.setToolTipText(Plain.text("Adds " + rules.text() + " to this project's .claude/settings.local.json"));
                        buttons.add(always);
                    }
                    buttons.add(button("Deny  D", "deny", this::deny, false));
                }
            }
            note.setName("note");
            note.putClientProperty("JTextField.placeholderText", "Note for Claude: ⏎ denies with it");
            buttons.add(note);
            p.add(buttons);
            confirm.setVisible(false); // shown by a first L or a click on Always; Escape reads it
            if (a.kind() == ApprovalKind.TOOL) {
                JTextArea scope = muted((rules.persistable() ? "Always: " : "For session: ") + rules.text());
                scope.setName("rule");
                p.add(scope);
            }
            if (a.kind() == ApprovalKind.TOOL && rules.persistable()) {
                confirmText.setName("confirm");
                confirm.setOpaque(false);
                confirm.setAlignmentX(Component.LEFT_ALIGNMENT);
                confirm.add(confirmText);
                JButton yes = button("Always allow", "check", () -> answer(a, new ApprovalDecision.Allow(ApprovalDecision.Scope.ALWAYS)), false);
                yes.setName("confirm-always");
                confirm.add(yes);
                confirm.add(button("Cancel", null, this::disarm, false));
                p.add(confirm);
            }
            if (a.kind() == ApprovalKind.QUESTION) {
                choose.setName("choose");
                choose.setForeground(Theme.ATTENTION);
                p.add(choose);
            }
            return p;
        }

        private String noteOr(String fallback) {
            String n = note.getText().strip();
            return n.isEmpty() ? fallback : n;
        }

        /** Whether every question has a chosen option or an answer of its own. */
        private boolean ready() {
            for (var e : questions.entrySet()) {
                boolean chosen = !other.get(e.getKey()).getText().isBlank();
                for (AbstractButton b : e.getValue()) {
                    chosen |= b.isSelected();
                }
                if (!chosen) {
                    return false;
                }
            }
            return true;
        }

        private void updateReady() {
            if (approval.kind() != ApprovalKind.QUESTION) {
                return;
            }
            boolean ready = ready();
            choose.setVisible(!ready);
            if (primaryButton != null) {
                primaryButton.setEnabled(ready);
            }
        }

        private void answerQuestions() {
            if (!ready()) {
                return;
            }
            Map<String, String> answers = new LinkedHashMap<>();
            for (var e : questions.entrySet()) {
                String free = other.get(e.getKey()).getText().strip();
                if (!free.isEmpty()) {
                    answers.put(e.getKey(), free);
                } else {
                    // several chosen options of a multi-select question go back as one comma-separated answer
                    String chosen = String.join(", ", e.getValue().stream().filter(AbstractButton::isSelected)
                            .map(AbstractButton::getActionCommand).toList());
                    if (!chosen.isEmpty()) {
                        answers.put(e.getKey(), chosen);
                    }
                }
            }
            answer(approval, new ApprovalDecision.Answer(answers));
        }

        /** The first L asks for a second within {@link #CONFIRM_MILLIS}; a rule in the local settings outlives the session. */
        private void alwaysKey() {
            long now = clock.getAsLong();
            if (armedByClick || (armedAt >= 0 && now - armedAt <= CONFIRM_MILLIS)) {
                answer(approval, new ApprovalDecision.Allow(ApprovalDecision.Scope.ALWAYS));
                return;
            }
            armedAt = now;
            showConfirm("Press L again to always allow " + rules.text());
            if (disarm != null) {
                disarm.stop();
            }
            disarm = new Timer((int) CONFIRM_MILLIS, e -> {
                if (!armedByClick) {
                    disarm();
                }
            });
            disarm.setRepeats(false);
            disarm.start();
        }

        private void alwaysClicked() {
            armedByClick = true;
            showConfirm("Always allow " + rules.text() + " in this project?");
        }

        private void showConfirm(String text) {
            confirmText.setText(text);
            confirm.setVisible(true);
            revalidate();
            repaint();
        }

        private void disarm() {
            armedAt = -1;
            armedByClick = false;
            confirm.setVisible(false);
            revalidate();
            repaint();
        }

        private void keys() {
            bind(KeyEvent.VK_ENTER, () -> primary().run());
            bind(KeyEvent.VK_A, () -> primary().run());
            bind(KeyEvent.VK_ESCAPE, () -> {
                if (confirm.isVisible()) {
                    disarm();
                } else {
                    deny();
                }
            });
            bind(KeyEvent.VK_D, this::deny);
            if (approval.kind() == ApprovalKind.TOOL) {
                bind(KeyEvent.VK_S, () -> answer(approval, new ApprovalDecision.Allow(ApprovalDecision.Scope.SESSION)));
                // a rule that may not be saved has no Always: L does nothing, and reaches nothing else
                bind(KeyEvent.VK_L, rules.persistable() ? this::alwaysKey : () -> { });
            }
            bind(KeyEvent.VK_DOWN, () -> neighbour(this, 1));
            bind(KeyEvent.VK_UP, () -> neighbour(this, -1));
        }

        private Runnable primary() {
            return switch (approval.kind()) {
                case QUESTION -> this::answerQuestions;
                default -> () -> answer(approval, ApprovalDecision.allowOnce());
            };
        }

        private void deny() {
            String fallback = switch (approval.kind()) {
                case QUESTION -> "The user skipped the questions.";
                case PLAN -> "Keep planning.";
                default -> "";
            };
            answer(approval, new ApprovalDecision.Deny(noteOr(fallback)));
        }

        /** A key of the card, wherever in the card the focus is. */
        private void bind(int key, Runnable r) {
            String id = "approval-" + key;
            getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(key, 0), id);
            getActionMap().put(id, keyAction(r));
        }

        /**
         * Gives each part of the card its share of the keys and the mouse: a text field types its letters and
         * answers on Enter; a read-only viewer passes Enter and Escape to the card; a press on anything that is not
         * a control focuses the card; the border shows the focus anywhere inside.
         */
        private void wire(Container root) {
            FocusAdapter border = new FocusAdapter() {
                @Override
                public void focusGained(FocusEvent e) {
                    setBorder(border(Theme.ACCENT));
                    if (e.getComponent() == Card.this) {
                        scrollRectToVisible(new Rectangle(0, 0, getWidth(), getHeight()));
                    }
                }

                @Override
                public void focusLost(FocusEvent e) {
                    Component to = e.getOppositeComponent();
                    if (to == null || !SwingUtilities.isDescendingFrom(to, Card.this)) {
                        setBorder(border(Theme.CARD_BORDER));
                    }
                }
            };
            MouseAdapter press = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    focus.request(Card.this);
                }
            };
            List<Component> all = new ArrayList<>();
            collect(root, all);
            for (Component c : all) {
                if (c.isFocusable() && (c == this || c instanceof JTextComponent || c instanceof AbstractButton)) {
                    c.addFocusListener(border);
                }
                if (c instanceof JTextComponent t && other.containsValue(t)) {
                    answerKeys(t);
                } else if (c instanceof JTextComponent t && t.isEditable() && t.isFocusable()) {
                    fieldKeys(t);
                } else if (c instanceof JTextComponent t && t.isFocusable()) {
                    InputMap m = t.getInputMap(JComponent.WHEN_FOCUSED);
                    m.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "none");
                    m.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "none");
                } else if (!(c instanceof AbstractButton) && !(c instanceof JScrollBar)) {
                    c.addMouseListener(press);
                }
            }
        }

        private void fieldKeys(JTextComponent field) {
            InputMap m = typesLetters(field);
            m.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "approval-enter");
            field.getActionMap().put("approval-enter", keyAction(() -> {
                if (field == note && !note.getText().isBlank()) {
                    deny(); // a typed note is an objection: it never goes with a call that runs
                } else {
                    primary().run();
                }
            }));
            m.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, MENU), "approval-deny");
            field.getActionMap().put("approval-deny", keyAction(this::deny));
            m.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "approval-card");
            field.getActionMap().put("approval-card", keyAction(() -> focus.request(this)));
        }

        /**
         * The Other answer keeps its own keys, Enter's new line and the edit keys included; no key in it answers, so
         * a paragraph is never sent half written. Escape returns to the card.
         */
        private void answerKeys(JTextComponent area) {
            InputMap m = typesLetters(area);
            // the card's menu key with Enter (deny with the note) does not reach past the box either
            m.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, MENU), "approval-type");
            m.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "approval-card");
            area.getActionMap().put("approval-card", keyAction(() -> focus.request(this)));
        }

        /** The card's letters type in {@code field}: their press stops there, and the typed character still reaches it. */
        private InputMap typesLetters(JTextComponent field) {
            InputMap m = field.getInputMap(JComponent.WHEN_FOCUSED);
            field.getActionMap().put("approval-type", keyAction(() -> { }));
            for (int letter : new int[] {KeyEvent.VK_A, KeyEvent.VK_S, KeyEvent.VK_L, KeyEvent.VK_D}) {
                m.put(KeyStroke.getKeyStroke(letter, 0), "approval-type");
            }
            return m;
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    }

    private static void collect(Component c, List<Component> out) {
        out.add(c);
        if (c instanceof Container k) {
            for (Component child : k.getComponents()) {
                collect(child, out);
            }
        }
    }

    private static AbstractAction keyAction(Runnable r) {
        return new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        };
    }

    private static Border border(Color line) {
        return BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(line, 1, true),
                BorderFactory.createEmptyBorder(Theme.PAD, Theme.PAD, Theme.PAD, Theme.PAD));
    }

    /** Muted, wrapped, read-only text; a text area never renders HTML, so text from Claude stays text. */
    private static JTextArea muted(String text) {
        JTextArea t = new JTextArea(text);
        t.setEditable(false);
        t.setFocusable(false);
        t.setOpaque(false);
        t.setLineWrap(true);
        t.setWrapStyleWord(true);
        t.setForeground(Theme.MUTED);
        t.setFont(new JLabel().getFont());
        t.setBorder(BorderFactory.createEmptyBorder());
        t.setAlignmentX(Component.LEFT_ALIGNMENT);
        return t;
    }

    static String title(Approval a) {
        return switch (a.kind()) {
            case QUESTION -> "asks";
            case PLAN -> "presents a plan";
            case SQL_WRITE -> {
                // the connection the statement would change is part of the question, not only of the status bar
                String connection = Json.parse(a.inputJson()).path("connection").asText("");
                yield connection.isEmpty() ? "wants to run a statement" : "wants to run a statement on " + connection;
            }
            case TOOL -> "wants to use " + a.toolName() + ": " + a.summary();
        };
    }

    private static JButton button(String text, String icon, Runnable action, boolean primary) {
        JButton b = new JButton(text, icon == null ? null : Icons.get(icon));
        b.setFocusable(false);
        if (primary) {
            b.putClientProperty("JButton.buttonType", "default");
        }
        b.addActionListener(e -> action.run());
        return b;
    }

    private static JComponent code(String text, String syntax, int maxLines) {
        RSyntaxTextArea area = Editors.viewer(syntax);
        area.setText(text);
        area.setCaretPosition(0);
        area.setLineWrap(true);
        int lines = Math.min(maxLines, Math.max(1, area.getLineCount()));
        JScrollPane sp = new JScrollPane(area);
        sp.setPreferredSize(new Dimension(400, lines * area.getLineHeight() + 12));
        return sp;
    }

    private static JComponent diff(String path, String before, String after) {
        DiffPane d = new DiffPane();
        d.show(path, before, after);
        JComponent c = d.component();
        c.setPreferredSize(new Dimension(400, Math.min(320, d.area().getLineCount() * d.area().getLineHeight() + 12)));
        return c;
    }

    private static final class ListPanel extends JPanel implements Scrollable {
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return 24;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(24, visible.height - 48);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}
