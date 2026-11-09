package dev.marregui.comando.ui.widgets.approval;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;
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

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import tools.jackson.databind.JsonNode;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.ApprovalDecision;
import dev.marregui.comando.core.model.ApprovalKind;
import dev.marregui.comando.core.model.Decision;
import dev.marregui.comando.core.model.PermissionRules;
import dev.marregui.comando.core.view.Clip;
import dev.marregui.comando.ui.kit.action.ToolButton;
import dev.marregui.comando.ui.kit.async.Edt;
import dev.marregui.comando.ui.kit.async.ShownTimer;
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
import dev.marregui.comando.ui.kit.theme.FocusRing;
import dev.marregui.comando.ui.kit.theme.SidebarLook;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.widget.Widget;
import dev.marregui.comando.ui.widget.WidgetContext;

/**
 * Every pending approval of every session as a card. The keys act while the focus is anywhere in a card: Enter or A
 * gives the primary answer, S allows for the session, L twice always allows (only where the rule may be saved; edits
 * and MCP tools are allowed for the session only), D or Escape denies (with the note, if typed), Up and Down move
 * between cards. In a text field of the card the letters type; Enter in the note denies with the note when it holds
 * text and gives the primary answer when it is empty, the menu key with Enter denies with the note, Escape returns to
 * the card. A question's Other answer is a plain text box in the editors' font ({@link Editors#plain}), 120 characters
 * wide: Enter starts a line, no key in it answers (the Answer button does), Escape returns to the card; it holds
 * nothing once the card is answered. Questions show their options with descriptions; a plan shows rendered; an edit
 * shows its diff; a Write shows the file's text and its line count, as the transcript's card does.
 *
 * <p>One queue for the window, in three groups (record 131): this session's cards, the focused panel's session's, under
 * its name, first; then the other sessions' cards under each session's name, the session with the oldest card first;
 * then Later, the decisions held back (by a checkpoint, a report, a question with no answer in time), under each
 * session's name, each with Settle (its session asks now) and Dismiss. Within a group the oldest comes first. Up and
 * Down follow that order. A decision takes no keyboard but in its typed answer: its buttons are clicked.</p>
 *
 * <p>A decision is answered on its card (record 142): one of its options, chosen as a question's, or, when it has
 * none, a line typed. Send answers sends every card answered at once, in the group's order; each decision's session
 * gets one prompt with its answers, and the cards not answered stay. A choice stays through every render while its
 * decision is held.</p>
 *
 * <p>Each time the inbox comes forward ({@link #shown}) it shows its top, this session's oldest request first, unless
 * the keyboard is in a card. A request arriving while it is in front moves nothing.</p>
 *
 * <p>When the card that holds the keyboard leaves because the user answered it here, the keyboard moves to the
 * card that took its place. When it leaves for any other reason (withdrawn by Claude Code, its session ended), the
 * keyboard goes to the inbox itself, which has no answer keys: what the user was typing answers nothing.</p>
 */
public final class ApprovalInbox implements Widget<ApprovalInbox.Queue> {

    /**
     * What the inbox shows.
     *
     * @param approvals every session's waiting requests, oldest first
     * @param session   the focused panel's session, whose cards come first; empty when the panel has none
     * @param later     the decisions held back, oldest first
     * @param locked    the decisions whose Settle the reducer refuses now: their session's checkpoint runs (record 130)
     * @param refused   the other decisions whose Settle the reducer refuses now, with why (no session to settle it in)
     * @param targets   the session each decision's Settle goes to, as the reducer chooses it (record 131)
     * @param gone      the decisions whose own session was removed, so that Settle goes elsewhere
     */
    public record Queue(List<Approval> approvals, String session, List<Decision> later, Set<Long> locked,
                        Map<Long, String> refused, Map<Long, String> targets, Set<Long> gone) {
        public Queue {
            approvals = List.copyOf(approvals);
            session = session == null ? "" : session;
            later = List.copyOf(later);
            locked = locked == null ? Set.of() : Set.copyOf(locked);
            refused = refused == null ? Map.of() : Map.copyOf(refused);
            targets = targets == null ? Map.of() : Map.copyOf(targets);
            gone = gone == null ? Set.of() : Set.copyOf(gone);
        }

        public Queue(final List<Approval> approvals, final String session, final List<Decision> later,
                     final Set<Long> locked, final Map<Long, String> refused, final Map<Long, String> targets) {
            this(approvals, session, later, locked, refused, targets, Set.of());
        }

        public Queue(final List<Approval> approvals, final String session, final List<Decision> later,
                     final Set<Long> locked, final Map<Long, String> refused) {
            this(approvals, session, later, locked, refused, Map.of());
        }

        public Queue(final List<Approval> approvals, final String session, final List<Decision> later,
                     final Set<Long> locked) {
            this(approvals, session, later, locked, Map.of(), Map.of());
        }

        public Queue(final List<Approval> approvals, final String session, final List<Decision> later) {
            this(approvals, session, later, Set.of(), Map.of(), Map.of());
        }
    }

    /** How long a first L waits for the second. */
    static final long CONFIRM_MILLIS = 3_000;
    private static final int AGE_TICK_MILLIS = 30_000;
    /** The length of a command's description in a card's head, as long as the summary that stands in for it. */
    private static final int TITLE_CHARS = 160;
    private static final int MENU = Platform.menuShortcutMask();
    private static final String EMPTY = "No approvals waiting. Permission requests from every session arrive here.";
    /**
     * Where Settle sends (record 131): the decision's own session, or, when it is gone or has no checkpoint command, the
     * focused panel's; one line of at most 80 characters with names of 24.
     */
    private static final String SETTLE_TIP = "Runs the checkpoint of %s";
    private static final String SETTLE_ELSEWHERE = "Runs %s's checkpoint; %s has no checkpoint command";
    private static final String SETTLE_GONE = "Runs %s's checkpoint; session %s was removed";
    private static final String SETTLE_LOCKED = "The session runs its checkpoint; Settle once it has ended";
    private static final String LATER_NOTE = "Held back for you. Answer and send, or Settle: the session asks now.";
    private static final String SEND_TIP = "Send answers: each answered decision's session gets them in one prompt";
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
            public void request(final Component c) {
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
    /** The cards in view order: this session's, the other sessions', each group oldest first. */
    private final List<Card> order = new ArrayList<>();
    private final Map<Long, DecisionRow> decisions = new LinkedHashMap<>();
    /** The session names over the groups, by group and name, kept while their group is shown. */
    private final Map<String, JLabel> names = new LinkedHashMap<>();
    private final JLabel thisSession = section("This session");
    private final JLabel otherSessions = section("Other sessions");
    private final JLabel later = section("Later");
    private final JTextArea laterNote = muted(LATER_NOTE);
    /** The Later group's bar under its note: Send answers. */
    private final JPanel laterBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0)) {
        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    };
    private final JButton sendAnswers = ToolButton.of("check", SEND_TIP, this::sendAnswers);
    /** The decisions in view order: by session, the session with the oldest first, each oldest first. */
    private final List<DecisionRow> laterOrder = new ArrayList<>();
    /** The approvals the user answered in this inbox whose cards are still shown. */
    private final Set<Long> answered = new HashSet<>();
    private final ShownTimer ages = new ShownTimer(overlay, AGE_TICK_MILLIS, this::tick);

    public ApprovalInbox(final WidgetContext context) {
        this(context, System::currentTimeMillis, Focus.WINDOW);
    }

    ApprovalInbox(final WidgetContext context, final LongSupplier clock, final Focus focus) {
        this(context, clock, focus, PermissionRules::rules);
    }

    ApprovalInbox(final WidgetContext context, final LongSupplier clock, final Focus focus,
                  final BiFunction<String, Function<String, String>, PermissionRules.Rules> ruleSource) {
        this.context = context;
        this.clock = clock;
        this.focus = focus;
        this.ruleSource = ruleSource;
        list.setName("inbox");
        // where the keyboard goes when the card holding it is withdrawn: no answer keys here
        list.setFocusable(true);
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setBorder(BorderFactory.createEmptyBorder(Theme.PAD, Theme.PAD, Theme.PAD, Theme.PAD));
        FocusRing.install(scroll);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        overlay.showEmpty(EMPTY);
        sendAnswers.setName("send-answers");
        laterBar.setName("later-bar");
        laterBar.setOpaque(false);
        laterBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        laterBar.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_XS, 0, Theme.SPACE_XS, 0));
        laterBar.add(sendAnswers);
    }

    @Override
    public JComponent component() {
        return overlay;
    }

    @Override
    public void render(final Queue queue) {
        final List<Card> before = new ArrayList<>(order);
        final Set<Long> answeredHere = new HashSet<>(answered);
        final Card holding = cardOf(focus.owner());
        final Set<Long> ids = new HashSet<>();
        for (final Approval a : queue.approvals()) {
            ids.add(a.id());
            cards.computeIfAbsent(a.id(), k -> new Card(a));
        }
        cards.values().removeIf(c -> !ids.contains(c.approval.id()));
        final Set<Long> held = new HashSet<>();
        for (final Decision d : queue.later()) {
            held.add(d.id());
            decisions.computeIfAbsent(d.id(), k -> new DecisionRow(d)).settleable(queue.locked().contains(d.id()),
                    queue.refused().get(d.id()), queue.targets().getOrDefault(d.id(), d.session()), queue.gone().contains(d.id()));
        }
        decisions.values().removeIf(r -> !held.contains(r.decision.id()));
        answered.retainAll(ids);
        lay(queue);
        updateSend();
        if (cards.isEmpty() && decisions.isEmpty()) {
            overlay.showEmpty(EMPTY);
        } else {
            overlay.showContent();
        }
        ages.want(!cards.isEmpty() || !decisions.isEmpty());
        list.revalidate();
        list.repaint();
        if (holding != null && !cards.containsValue(holding)) {
            if (answeredHere.contains(holding.approval.id())) {
                final Card next = successor(before, holding);
                if (next != null) {
                    focus.request(next);
                }
            } else {
                // withdrawn while the user worked in it: the next key must not answer a card the user never looked at
                focus.request(list);
            }
        }
    }

    /** Every card under no session in front, nothing held back; for tests. */
    void render(final List<Approval> approvals) {
        render(new Queue(approvals, "", List.of()));
    }

    /**
     * Lays the groups out in order. A component that stays is never taken out of the list, only moved within it, so
     * the card the keyboard is in keeps it while cards above it or of other groups come and go; what is no longer shown
     * goes.
     */
    private void lay(final Queue queue) {
        final List<Component> want = new ArrayList<>();
        final Set<String> named = new HashSet<>();
        order.clear();
        laterOrder.clear();
        final String own = queue.session();
        final List<Approval> mine = queue.approvals().stream().filter(a -> !own.isEmpty() && a.session().equals(own)).toList();
        if (!mine.isEmpty()) {
            want.add(thisSession);
            want.add(name("this", own, named));
            for (final Approval a : mine) {
                place(cards.get(a.id()), want);
            }
        }
        final Map<String, List<Approval>> others = bySession(queue.approvals().stream().filter(a -> own.isEmpty() || !a.session().equals(own)).toList(),
                Approval::session, Approval::createdMillis);
        if (!others.isEmpty()) {
            otherSessions.setText(own.isEmpty() ? "SESSIONS" : "OTHER SESSIONS");
            want.add(otherSessions);
            for (final Map.Entry<String, List<Approval>> e : others.entrySet()) {
                want.add(name("other", e.getKey(), named));
                for (final Approval a : e.getValue()) {
                    place(cards.get(a.id()), want);
                }
            }
        }
        final Map<String, List<Decision>> held = bySession(queue.later(), Decision::session, Decision::createdMillis);
        if (!held.isEmpty()) {
            want.add(later);
            want.add(laterNote);
            want.add(laterBar);
            for (final Map.Entry<String, List<Decision>> e : held.entrySet()) {
                want.add(name("later", e.getKey(), named));
                for (final Decision d : e.getValue()) {
                    final DecisionRow r = decisions.get(d.id());
                    laterOrder.add(r);
                    want.add(r);
                    want.add(r.gap);
                }
            }
        }
        names.keySet().retainAll(named);
        // what is no longer shown goes first; then each component is put in its place without leaving the list:
        // list.add would take a child out and put it back, and a focus owner taken out passes the keyboard on
        final Set<Component> shown = Collections.newSetFromMap(new IdentityHashMap<>());
        shown.addAll(want);
        for (int i = list.getComponentCount() - 1; i >= 0; i--) {
            if (!shown.contains(list.getComponent(i))) {
                list.remove(i);
            }
        }
        for (int i = 0; i < want.size(); i++) {
            final Component c = want.get(i);
            if (i < list.getComponentCount() && list.getComponent(i) == c) {
                continue;
            }
            if (c.getParent() == list) {
                // moved within the list: no removeNotify, the focus stays
                list.setComponentZOrder(c, i);
            } else {
                // new to the list: added, as it holds no focus yet (setComponentZOrder refuses a component of no window)
                list.add(c, i);
            }
        }
    }

    private void place(final Card c, final List<Component> want) {
        order.add(c);
        want.add(c);
        want.add(c.gap);
    }

    /** The session's name over a group, the same label while the group stays. */
    private JLabel name(final String group, final String session, final Set<String> named) {
        final String key = group + "\n" + session;
        named.add(key);
        return names.computeIfAbsent(key, k -> {
            final JLabel l = Plain.only(new JLabel(session.isEmpty() ? "comando" : session));
            l.setName("session");
            l.setFont(l.getFont().deriveFont(Font.BOLD));
            l.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_XS, 0, Theme.SPACE_XS, 0));
            l.setAlignmentX(Component.LEFT_ALIGNMENT);
            return l;
        });
    }

    /** {@code items} by session, each oldest first as given, the session with the oldest item first. */
    private static <T> Map<String, List<T>> bySession(final List<T> items, final Function<T, String> session, final ToLongFunction<T> created) {
        final Map<String, List<T>> groups = new LinkedHashMap<>();
        for (final T t : items) {
            groups.computeIfAbsent(session.apply(t), k -> new ArrayList<>()).add(t);
        }
        final List<Map.Entry<String, List<T>>> sorted = new ArrayList<>(groups.entrySet());
        sorted.sort(Comparator.comparingLong(e -> e.getValue().stream().mapToLong(created).min().orElse(Long.MAX_VALUE)));
        final Map<String, List<T>> out = new LinkedHashMap<>();
        for (final Map.Entry<String, List<T>> e : sorted) {
            out.put(e.getKey(), e.getValue());
        }
        return out;
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

    /** Moves the keyboard focus to the first card: this session's oldest, else the oldest of the session next in line. */
    public void focusFirst() {
        if (!order.isEmpty()) {
            focus.request(order.getFirst());
        }
    }

    /** Whether the keyboard focus is inside a card, where the card's keys take precedence over window-wide ones. */
    public boolean holdsFocus() {
        return cardOf(focus.owner()) != null;
    }

    /** Refreshes each card's age. */
    void tick() {
        final long now = clock.getAsLong();
        for (final Card c : cards.values()) {
            c.age.setText(age(c.approval.createdMillis(), now));
        }
        for (final DecisionRow r : decisions.values()) {
            r.age.setText(age(r.decision.createdMillis(), now));
        }
    }

    boolean ticking() {
        return ages.running();
    }

    /** For tests: whether waiting cards want their ages ticked, shown or not. */
    boolean ageWanted() {
        return ages.wanted();
    }

    /** The card that took the place of {@code gone} in view order: the next remaining one, else the previous one. */
    private Card successor(final List<Card> before, final Card gone) {
        final int at = before.indexOf(gone);
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

    private Card cardOf(final Component c) {
        for (Component x = c; x != null; x = x.getParent()) {
            if (x instanceof Card card && cards.containsValue(card)) {
                return card;
            }
        }
        return null;
    }

    private void neighbour(final Card from, final int step) {
        final int i = order.indexOf(from) + step;
        if (i >= 0 && i < order.size()) {
            focus.request(order.get(i));
        }
    }

    /** Send answers: on while a card of the Later group holds an answer. */
    private void updateSend() {
        boolean any = false;
        for (final DecisionRow r : laterOrder) {
            any |= !r.answer().isEmpty();
        }
        sendAnswers.setEnabled(any);
    }

    /** Every answered card's answer, by its decision's id, in the group's order; the cards not answered stay. */
    private void sendAnswers() {
        final Map<Long, String> answers = new LinkedHashMap<>();
        for (final DecisionRow r : laterOrder) {
            final String a = r.answer();
            if (!a.isEmpty()) {
                answers.put(r.decision.id(), a);
            }
        }
        if (!answers.isEmpty()) {
            context.intents().dispatch(new Intent.DecisionsAnswered(answers));
        }
    }

    private void answer(final Approval a, final ApprovalDecision d) {
        answered.add(a.id());
        context.intents().dispatch(new Intent.ApprovalAnswered(a.id(), d));
    }

    static String age(final long createdMillis, final long now) {
        final String ago = Labels.ago(createdMillis, now);
        return ago.isEmpty() || ago.equals("now") ? ago : ago + " ago";
    }

    /** One approval. */
    private final class Card extends JPanel {
        private final Approval approval;
        private final JTextField note = new JTextField(28);
        private final JLabel age = Plain.only(new JLabel());
        /** The space under the card. */
        private final Component gap = Box.createVerticalStrut(Theme.GAP);
        /** Each question's option buttons: radio buttons in a group, or check boxes when it allows several. */
        private final Map<String, List<AbstractButton>> questions = new LinkedHashMap<>();
        private final Map<String, JTextComponent> other = new LinkedHashMap<>();
        /** The rules "For session" and "Always" add; null for anything but a tool call. */
        private final PermissionRules.Rules rules;
        /** {@link #rules} in plain words, as the card says them; empty for anything but a tool call. */
        private final String scope;
        /**
         * The Always confirmation: its text wrapped on lines of its own, its buttons on a row below. One flowing row
         * reports the height of one line while its buttons wrap below it, out of the card.
         */
        private final JPanel confirm = new JPanel();
        private final JTextArea confirmText = wrapped();
        private final JLabel choose = Plain.only(new JLabel("Choose an answer"));
        private JButton primaryButton;
        /** When a first L asked for the second, or -1; a click on Always arms without a deadline. */
        private long armedAt = -1;
        private boolean armedByClick;
        private Timer disarm;

        Card(final Approval a) {
            this.approval = a;
            setName("approval-" + a.id());
            final JsonNode in = Json.parse(a.inputJson());
            this.rules = a.kind() == ApprovalKind.TOOL ? ruleSource.apply(a.toolName(), k -> in.path(k).asString("")) : null;
            this.scope = rules == null ? "" : inWords(rules);
            setLayout(new BorderLayout(0, Theme.GAP));
            setBorder(border(Theme.CARD_BORDER));
            setBackground(Theme.CARD);
            setFocusable(true);
            setAlignmentX(Component.LEFT_ALIGNMENT);
            add(header(a, in), BorderLayout.NORTH);
            add(body(a, in), BorderLayout.CENTER);
            add(footer(a), BorderLayout.SOUTH);
            keys();
            wire(this);
            updateReady();
        }

        private JComponent header(final Approval a, final JsonNode in) {
            final JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            p.setOpaque(false);
            final JLabel head = new JLabel("<html><b>" + MarkdownHtml.escape(a.session()) + "</b>&nbsp;&nbsp;" + MarkdownHtml.escape(title(a)) + "&nbsp;&nbsp;</html>");
            head.setIcon(Icons.get(switch (a.kind()) {
                case QUESTION -> "question";
                case PLAN -> "plan";
                case SQL_WRITE, CONNECTION -> "database";
                case TERMINAL -> "terminal";
                case TOOL -> "warning";
            }));
            age.setName("age");
            age.setForeground(Theme.MUTED);
            age.setText(ApprovalInbox.age(a.createdMillis(), clock.getAsLong()));
            p.add(head);
            if (isWrite(a)) {
                final JLabel lines = Plain.only(new JLabel(Diffs.writtenLines(in)));
                lines.setName("lines");
                lines.setForeground(Theme.MUTED);
                lines.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, Theme.GAP));
                p.add(lines);
            }
            p.add(age);
            return p;
        }

        private JComponent body(final Approval a, final JsonNode in) {
            return switch (a.kind()) {
                case QUESTION -> questionsPanel(in);
                case PLAN -> {
                    final HtmlPane plan = new HtmlPane();
                    plan.setMarkdown(in.path("plan").asString(""));
                    final JScrollPane sp = new JScrollPane(plan);
                    sp.setPreferredSize(new Dimension(400, 260));
                    yield sp;
                }
                case SQL_WRITE -> code(in.path("sql").asString(""), "sql", 8);
                // what the agent would weaken, then the connection as it would be saved
                case CONNECTION -> code(Json.pretty(in), "json", 10);
                // what would be typed, as the shell would read it
                case TERMINAL -> code(in.path("text").asString(""), "sh", 8);
                case TOOL -> {
                    if (isWrite(a)) {
                        // sixteen lines stand as tall as the tallest diff, so a Write's card keeps its height
                        yield code(Diffs.writtenText(in), extension(in.path("file_path").asString("")), 16);
                    }
                    final Diffs.Edit edit = Diffs.edit(a.toolName(), in);
                    if (edit != null) {
                        yield diff(edit.path(), edit.before(), edit.after());
                    }
                    yield a.toolName().equals("Bash") ? code(in.path("command").asString("") + describe(in), "sh", 10) : code(Json.pretty(in), "json", 12);
                }
            };
        }

        private static String describe(final JsonNode in) {
            final String d = in.path("description").asString("");
            return d.isEmpty() ? "" : "\n# " + d;
        }

        private JComponent questionsPanel(final JsonNode in) {
            final JPanel p = new JPanel();
            p.setOpaque(false);
            p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
            for (final JsonNode q : in.path("questions")) {
                final String question = q.path("question").asString("");
                final JLabel label = new JLabel("<html><b>" + MarkdownHtml.escape(question) + "</b></html>");
                label.setAlignmentX(Component.LEFT_ALIGNMENT);
                p.add(label);
                final boolean multi = q.path("multiSelect").asBoolean(false);
                final ButtonGroup group = new ButtonGroup();
                final List<AbstractButton> buttons = new ArrayList<>();
                for (final JsonNode o : q.path("options")) {
                    final String option = o.path("label").asString("");
                    // option labels and descriptions come from Claude: shown as text, never as HTML
                    final AbstractButton b = Plain.only(multi ? new JCheckBox() : new JRadioButton());
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
                    final String description = o.path("description").asString("");
                    if (!description.isBlank()) {
                        final JTextArea d = muted(description);
                        d.setName("description");
                        // indented under the option's text, past the radio or check mark
                        d.setBorder(BorderFactory.createEmptyBorder(0, 24, 2, 0));
                        p.add(d);
                    }
                }
                final JLabel otherLabel = Plain.only(new JLabel("Other answer"));
                otherLabel.setForeground(Theme.MUTED);
                otherLabel.setBorder(BorderFactory.createEmptyBorder(Theme.GAP, 0, 2, 0));
                otherLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
                p.add(otherLabel);
                final JTextArea free = Editors.plain(OTHER_ROWS, OTHER_COLUMNS);
                free.setName("other");
                free.getDocument().addDocumentListener(new DocumentListener() {
                    @Override
                    public void insertUpdate(final DocumentEvent e) {
                        updateReady();
                    }

                    @Override
                    public void removeUpdate(final DocumentEvent e) {
                        updateReady();
                    }

                    @Override
                    public void changedUpdate(final DocumentEvent e) {
                        updateReady();
                    }
                });
                final JScrollPane editor = new JScrollPane(free);
                editor.setAlignmentX(Component.LEFT_ALIGNMENT);
                // its columns wide and its rows tall, scrolling past them; narrower only when the card is
                editor.setMaximumSize(editor.getPreferredSize());
                p.add(editor);
                questions.put(question, buttons);
                other.put(question, free);
            }
            return p;
        }

        private JComponent footer(final Approval a) {
            final JPanel p = new JPanel();
            p.setOpaque(false);
            p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
            final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.GAP, 0));
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
                    final JButton edits = button("Approve, auto-accept edits", "check", () -> answer(a, new ApprovalDecision.Allow(ApprovalDecision.Scope.SESSION)), false);
                    edits.setToolTipText("Approve the plan and accept file edits for the rest of this session; a session in bypass stays in bypass");
                    buttons.add(edits);
                    buttons.add(button("Keep planning", "deny", this::deny, false));
                }
                case SQL_WRITE -> {
                    buttons.add(button("Run  ⏎", "check", () -> answer(a, ApprovalDecision.allowOnce()), true));
                    buttons.add(button("Deny  D", "deny", this::deny, false));
                }
                case CONNECTION, TERMINAL -> {
                    buttons.add(button("Allow  ⏎", "check", () -> answer(a, ApprovalDecision.allowOnce()), true));
                    buttons.add(button("Deny  D", "deny", this::deny, false));
                }
                case TOOL -> {
                    buttons.add(button("Allow once  ⏎", "check", () -> answer(a, ApprovalDecision.allowOnce()), true));
                    final JButton session = button("For session  S", null, () -> answer(a, new ApprovalDecision.Allow(ApprovalDecision.Scope.SESSION)), false);
                    session.setName("session");
                    session.setToolTipText(Plain.text("Adds " + rules.text() + " for the rest of this session"));
                    buttons.add(session);
                    if (rules.persistable()) {
                        final JButton always = button("Always  L", null, this::alwaysClicked, false);
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
                final JTextArea line = muted((rules.persistable() ? "Always: " : "For session: ") + scope);
                line.setName("rule");
                p.add(line);
            }
            if (a.kind() == ApprovalKind.TOOL && rules.persistable()) {
                confirmText.setName("confirm");
                confirm.setOpaque(false);
                confirm.setLayout(new BoxLayout(confirm, BoxLayout.Y_AXIS));
                confirm.setAlignmentX(Component.LEFT_ALIGNMENT);
                confirm.setBorder(BorderFactory.createEmptyBorder(Theme.GAP, 0, 0, 0));
                confirm.add(confirmText);
                final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.GAP, 0));
                row.setOpaque(false);
                row.setAlignmentX(Component.LEFT_ALIGNMENT);
                final JButton yes = button("Always allow", "check", () -> answer(a, new ApprovalDecision.Allow(ApprovalDecision.Scope.ALWAYS)), false);
                yes.setName("confirm-always");
                row.add(yes);
                final JButton cancel = button("Cancel", null, this::disarm, false);
                cancel.setName("confirm-cancel");
                row.add(cancel);
                confirm.add(row);
                p.add(confirm);
            }
            if (a.kind() == ApprovalKind.QUESTION) {
                choose.setName("choose");
                choose.setForeground(Theme.ATTENTION);
                p.add(choose);
            }
            return p;
        }

        private String noteOr(final String fallback) {
            final String n = note.getText().strip();
            return n.isEmpty() ? fallback : n;
        }

        /** Whether every question has a chosen option or an answer of its own. */
        private boolean ready() {
            for (final var e : questions.entrySet()) {
                boolean chosen = !other.get(e.getKey()).getText().isBlank();
                for (final AbstractButton b : e.getValue()) {
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
            final boolean ready = ready();
            choose.setVisible(!ready);
            if (primaryButton != null) {
                primaryButton.setEnabled(ready);
            }
        }

        private void answerQuestions() {
            if (!ready()) {
                return;
            }
            final Map<String, String> answers = new LinkedHashMap<>();
            for (final var e : questions.entrySet()) {
                final String free = other.get(e.getKey()).getText().strip();
                if (!free.isEmpty()) {
                    answers.put(e.getKey(), free);
                } else {
                    // several chosen options of a multi-select question go back as one comma-separated answer
                    final String chosen = String.join(", ", e.getValue().stream().filter(AbstractButton::isSelected)
                            .map(AbstractButton::getActionCommand).toList());
                    if (!chosen.isEmpty()) {
                        answers.put(e.getKey(), chosen);
                    }
                }
            }
            answer(approval, new ApprovalDecision.Answer(answers));
        }

        /** A first L asks for a second within {@link #CONFIRM_MILLIS}; a local settings rule outlives the session. */
        private void alwaysKey() {
            final long now = clock.getAsLong();
            if (armedByClick || (armedAt >= 0 && now - armedAt <= CONFIRM_MILLIS)) {
                answer(approval, new ApprovalDecision.Allow(ApprovalDecision.Scope.ALWAYS));
                return;
            }
            armedAt = now;
            showConfirm("Press L again to always allow " + scope);
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
            showConfirm("Always allow " + scope + " in this project?");
        }

        private void showConfirm(final String text) {
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
            final String fallback = switch (approval.kind()) {
                case QUESTION -> "The user skipped the questions.";
                case PLAN -> "Keep planning.";
                default -> "";
            };
            answer(approval, new ApprovalDecision.Deny(noteOr(fallback)));
        }

        /** A key of the card, wherever in the card the focus is. */
        private void bind(final int key, final Runnable r) {
            final String id = "approval-" + key;
            getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(key, 0), id);
            getActionMap().put(id, keyAction(r));
        }

        /**
         * Gives each part of the card its share of the keys and the mouse: a text field types its letters and
         * answers on Enter; a read-only viewer passes Enter and Escape to the card; a press on anything that is not
         * a control focuses the card; the border shows the focus anywhere inside.
         */
        private void wire(final Container root) {
            final FocusAdapter border = new FocusAdapter() {
                @Override
                public void focusGained(final FocusEvent e) {
                    setBorder(border(Theme.tint().body));
                    if (e.getComponent() == Card.this) {
                        scrollRectToVisible(new Rectangle(0, 0, getWidth(), getHeight()));
                    }
                }

                @Override
                public void focusLost(final FocusEvent e) {
                    final Component to = e.getOppositeComponent();
                    if (to == null || !SwingUtilities.isDescendingFrom(to, Card.this)) {
                        setBorder(border(Theme.CARD_BORDER));
                    }
                }
            };
            final MouseAdapter press = new MouseAdapter() {
                @Override
                public void mousePressed(final MouseEvent e) {
                    focus.request(Card.this);
                }
            };
            final List<Component> all = new ArrayList<>();
            collect(root, all);
            for (final Component c : all) {
                if (c.isFocusable() && (c == this || c instanceof JTextComponent || c instanceof AbstractButton)) {
                    c.addFocusListener(border);
                }
                if (c instanceof JTextComponent t && other.containsValue(t)) {
                    answerKeys(t);
                } else if (c instanceof JTextComponent t && t.isEditable() && t.isFocusable()) {
                    fieldKeys(t);
                } else if (c instanceof JTextComponent t && t.isFocusable()) {
                    final InputMap m = t.getInputMap(JComponent.WHEN_FOCUSED);
                    m.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "none");
                    m.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "none");
                } else if (!(c instanceof AbstractButton) && !(c instanceof JScrollBar)) {
                    c.addMouseListener(press);
                }
            }
        }

        private void fieldKeys(final JTextComponent field) {
            final InputMap m = typesLetters(field);
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
        private void answerKeys(final JTextComponent area) {
            final InputMap m = typesLetters(area);
            // the card's menu key with Enter (deny with the note) does not reach past the box either
            m.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, MENU), "approval-type");
            m.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "approval-card");
            area.getActionMap().put("approval-card", keyAction(() -> focus.request(this)));
        }

        /** The card's letters type in {@code field}: their press stops there; the typed character still reaches it. */
        private InputMap typesLetters(final JTextComponent field) {
            final InputMap m = field.getInputMap(JComponent.WHEN_FOCUSED);
            field.getActionMap().put("approval-type", keyAction(() -> { }));
            for (final int letter : new int[] {KeyEvent.VK_A, KeyEvent.VK_S, KeyEvent.VK_L, KeyEvent.VK_D}) {
                m.put(KeyStroke.getKeyStroke(letter, 0), "approval-type");
            }
            return m;
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    }

    /**
     * A decision held back: its title, its detail (muted), its age, its answer (one of its options, or a line typed
     * when it has none), Settle and Dismiss. It takes no keyboard but in the typed line.
     */
    private final class DecisionRow extends JPanel {
        private final Decision decision;
        private final JLabel age = Plain.only(new JLabel());
        /** One radio button per option, in a group: one choice. */
        private final List<AbstractButton> options = new ArrayList<>();
        /** The typed answer; shown only for a decision without options. */
        private final JTextField typed = new JTextField(OTHER_COLUMNS);
        private final JButton settle;
        /** The space under the row. */
        private final Component gap = Box.createVerticalStrut(Theme.GAP);

        DecisionRow(final Decision d) {
            this.decision = d;
            setName("decision-" + d.id());
            setFocusable(false);
            setLayout(new BorderLayout(0, Theme.GAP));
            setBorder(border(Theme.CARD_BORDER));
            setBackground(Theme.CARD);
            setAlignmentX(Component.LEFT_ALIGNMENT);
            final JPanel head = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            head.setOpaque(false);
            // the title comes from Claude: shown as text, never as HTML
            final JLabel title = Plain.only(new JLabel());
            title.setText(d.title() + "  ");
            title.setName("title");
            title.setFont(title.getFont().deriveFont(Font.BOLD));
            title.setIcon(Icons.get("hourglass"));
            age.setName("age");
            age.setForeground(Theme.MUTED);
            age.setText(ApprovalInbox.age(d.createdMillis(), clock.getAsLong()));
            head.add(title);
            head.add(age);
            add(head, BorderLayout.NORTH);
            add(body(d), BorderLayout.CENTER);
            final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.GAP, 0));
            buttons.setOpaque(false);
            settle = button("Settle", "checkpoint", () -> context.intents().dispatch(new Intent.SettleDecision(d.id())), false);
            settle.setName("settle");
            settle.setToolTipText(Plain.text(SETTLE_TIP.formatted(d.session())));
            buttons.add(settle);
            final JButton dismiss = button("Dismiss", "close", () -> context.intents().dispatch(new Intent.DismissDecision(d.id())), false);
            dismiss.setName("dismiss");
            dismiss.setToolTipText("Drops the decision unasked");
            buttons.add(dismiss);
            add(buttons, BorderLayout.SOUTH);
        }

        /** Its detail, then its options and an Other answer, as a question card offers, or the typed line alone. */
        private JComponent body(final Decision d) {
            final JPanel p = new JPanel();
            p.setOpaque(false);
            p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
            if (!d.detail().isBlank()) {
                final JTextArea detail = muted(d.detail());
                detail.setName("detail");
                p.add(detail);
            }
            final ButtonGroup group = new ButtonGroup();
            for (final String option : d.options()) {
                // options come from Claude: shown as text, never as HTML
                final AbstractButton b = Plain.only(new JRadioButton());
                b.setText(option); // after the guard: Swing decides on HTML when the text is set
                b.setActionCommand(option);
                b.setName("option");
                b.setOpaque(false);
                b.setFocusable(false);
                b.setAlignmentX(Component.LEFT_ALIGNMENT);
                b.addItemListener(e -> updateSend());
                group.add(b);
                options.add(b);
                p.add(b);
            }
            typed.setName("answer");
            typed.putClientProperty("JTextField.placeholderText", options.isEmpty() ? "Your answer" : "Other answer");
            typed.setAlignmentX(Component.LEFT_ALIGNMENT);
            // its columns wide, as a question's Other answer; narrower only when the card is
            typed.setMaximumSize(typed.getPreferredSize());
            typed.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(final DocumentEvent e) {
                    updateSend();
                }

                @Override
                public void removeUpdate(final DocumentEvent e) {
                    updateSend();
                }

                @Override
                public void changedUpdate(final DocumentEvent e) {
                    updateSend();
                }
            });
            p.add(Box.createVerticalStrut(Theme.SPACE_XS));
            p.add(typed);
            return p;
        }

        /** The line typed, stripped, else the option chosen, as a question card answers; empty while it has neither. */
        String answer() {
            final String line = typed.getText().strip();
            if (!line.isEmpty()) {
                return line;
            }
            for (final AbstractButton b : options) {
                if (b.isSelected()) {
                    return b.getActionCommand();
                }
            }
            return "";
        }

        /**
         * Settle off while the session it would go to runs its checkpoint, which refuses it (record 130), or while the
         * reducer refuses it for {@code refused} (null when it does not); the tooltip says why, or names
         * {@code target}, the session it sends to, and when that is another, why: the decision's own session has no
         * checkpoint command, or was removed ({@code gone}).
         */
        void settleable(final boolean locked, final String refused, final String target, final boolean gone) {
            settle.setEnabled(!locked && refused == null);
            final String s = decision.session();
            final String tip = locked ? SETTLE_LOCKED : refused != null ? refused
                    : target.equals(s) ? SETTLE_TIP.formatted(s) : (gone ? SETTLE_GONE : SETTLE_ELSEWHERE).formatted(target, s);
            settle.setToolTipText(Plain.text(tip));
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    }

    /** A group's header, as the sidebar's and the Files tab's. */
    private static JLabel section(final String text) {
        final JLabel l = SidebarLook.header(text);
        l.setName("section");
        l.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_M, 0, Theme.SPACE_XS, 0));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private static void collect(final Component c, final List<Component> out) {
        out.add(c);
        if (c instanceof Container k) {
            for (final Component child : k.getComponents()) {
                collect(child, out);
            }
        }
    }

    private static AbstractAction keyAction(final Runnable r) {
        return new AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent e) {
                r.run();
            }
        };
    }

    private static Border border(final Color line) {
        return BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(line, 1, true),
                BorderFactory.createEmptyBorder(Theme.PAD, Theme.PAD, Theme.PAD, Theme.PAD));
    }

    /** Wrapped, read-only text in the theme's text colour, for the Always confirmation. */
    private static JTextArea wrapped() {
        final JTextArea t = muted("");
        t.setForeground(null); // the look and feel's, which follows a theme change
        return t;
    }

    /** Muted, wrapped, read-only text; a text area never renders HTML, so text from Claude stays text. */
    private static JTextArea muted(final String text) {
        final JTextArea t = new JTextArea(text);
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

    static String title(final Approval a) {
        return switch (a.kind()) {
            case QUESTION -> "asks";
            case PLAN -> "presents a plan";
            case SQL_WRITE -> {
                // the connection the statement would change is part of the question, not only of the status bar
                final String connection = Json.parse(a.inputJson()).path("connection").asString("");
                yield connection.isEmpty() ? "wants to run a statement" : "wants to run a statement on " + connection;
            }
            case CONNECTION -> "wants to change connection " + a.summary();
            case TERMINAL -> "wants to type into the Terminal of " + a.summary();
            case TOOL -> toolTitle(a, Json.parse(a.inputJson()));
        };
    }

    /** What the tool call {@code a} wants, each thing said once: a file by its path, a command by itself. */
    private static String toolTitle(final Approval a, final JsonNode in) {
        final String tool = a.toolName();
        final String path = switch (tool) {
            case "Write", "Edit", "MultiEdit" -> in.path("file_path").asString("").strip();
            case "NotebookEdit" -> in.path("notebook_path").asString("").strip();
            default -> "";
        };
        if (!path.isEmpty()) {
            return (tool.equals("Write") ? "wants to write " : "wants to edit ") + Labels.shortPath(path);
        }
        if (tool.equals("Bash")) {
            // the command, never the description: it is what runs, and the agent's own words about it could be wrong
            return "wants to run: " + Clip.keepStart(a.summary(), TITLE_CHARS);
        }
        final String rest = withoutToolWord(tool, a.summary());
        return "wants to use " + tool + (rest.isEmpty() ? "" : ": " + Labels.shortPath(rest));
    }

    /** {@code summary} without its first word when the tool's name ends in it: "Read /a", "Fetch https://a.org". */
    private static String withoutToolWord(final String tool, final String summary) {
        final int space = summary.indexOf(' ');
        final String first = space < 0 ? summary : summary.substring(0, space);
        if (first.isEmpty() || !tool.endsWith(first)) {
            return summary;
        }
        return space < 0 ? "" : summary.substring(space + 1);
    }

    /** What {@code rules} allow in plain words, joined as {@link PermissionRules.Rules#text} joins them. */
    private static String inWords(final PermissionRules.Rules rules) {
        return String.join(", ", rules.rules().stream().map(ApprovalInbox::inWords).toList());
    }

    /** What {@code rule} allows in plain words; a shape with no words prints as the rule. */
    private static String inWords(final PermissionRules.Rule rule) {
        final String content = rule.ruleContent();
        return switch (rule.toolName()) {
            case "Edit" -> filesInWords("any edit", rule);
            case "Read" -> filesInWords("any read", rule);
            case "Bash" -> {
                if (content.isEmpty()) {
                    yield "any command";
                }
                yield content.endsWith(":*")
                        ? "commands starting \"" + content.substring(0, content.length() - 2) + "\""
                        : "the command \"" + content + "\"";
            }
            case "WebFetch" -> {
                if (content.isEmpty()) {
                    yield "any fetch";
                }
                yield content.startsWith("domain:")
                        ? "any fetch from " + content.substring(content.indexOf(':') + 1)
                        : rule.text();
            }
            default -> rule.text();
        };
    }

    /** {@code any}, narrowed to the folder a rule of {@code //abs/dir/**} or {@code ./dir/**} names. */
    private static String filesInWords(final String any, final PermissionRules.Rule rule) {
        final String content = rule.ruleContent();
        if (content.isEmpty()) {
            return any;
        }
        if (!content.endsWith("/**")) {
            return rule.text();
        }
        final String folder = content.substring(0, content.length() - 2);
        if (folder.startsWith("//")) {
            return any + " under " + Labels.shortPath(folder.substring(1));
        }
        return folder.startsWith("./") ? any + " under " + folder : rule.text();
    }

    private static boolean isWrite(final Approval a) {
        return a.kind() == ApprovalKind.TOOL && a.toolName().equals("Write");
    }

    /** What follows the last dot of {@code path}'s file name, or the whole name: what {@link #code} colours by. */
    private static String extension(final String path) {
        final String name = path.substring(path.lastIndexOf('/') + 1);
        return name.substring(name.lastIndexOf('.') + 1);
    }

    private static JButton button(final String text, final String icon, final Runnable action, final boolean primary) {
        final JButton b = new JButton(text, icon == null ? null : Icons.get(icon));
        b.setFocusable(false);
        if (primary) {
            b.putClientProperty("JButton.buttonType", "default");
        }
        b.addActionListener(e -> action.run());
        return b;
    }

    private static JComponent code(final String text, final String syntax, final int maxLines) {
        final RSyntaxTextArea area = Editors.viewer(syntax);
        area.setText(text);
        area.setCaretPosition(0);
        area.setLineWrap(true);
        final int lines = Math.min(maxLines, Math.max(1, area.getLineCount()));
        final JScrollPane sp = new JScrollPane(area);
        sp.setPreferredSize(new Dimension(400, lines * area.getLineHeight() + 12));
        return sp;
    }

    private static JComponent diff(final String path, final String before, final String after) {
        final DiffPane d = new DiffPane();
        d.show(path, before, after);
        final JComponent c = d.component();
        c.setPreferredSize(new Dimension(400, Math.min(320, d.area().getLineCount() * d.area().getLineHeight() + 12)));
        return c;
    }

    private static final class ListPanel extends JPanel implements Scrollable {
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(final Rectangle visible, final int orientation, final int direction) {
            return 24;
        }

        @Override
        public int getScrollableBlockIncrement(final Rectangle visible, final int orientation, final int direction) {
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
