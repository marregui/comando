package dev.marregui.comando.ui.widgets.approval;

import dev.marregui.comando.ui.kit.text.Plain;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.ApprovalDecision;
import dev.marregui.comando.core.model.ApprovalKind;
import dev.marregui.comando.ui.kit.format.Labels;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.overlay.Overlay;
import dev.marregui.comando.ui.kit.text.DiffPane;
import dev.marregui.comando.ui.kit.text.Editors;
import dev.marregui.comando.ui.kit.text.HtmlPane;
import dev.marregui.comando.ui.kit.text.MarkdownHtml;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.widget.Widget;
import dev.marregui.comando.ui.widget.WidgetContext;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.Scrollable;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every pending approval of every session as a card. Keys on a focused card: Enter or A allows once,
 * S allows for the session, L always allows, D or Escape denies (with the note, if typed). Questions show
 * their options; a plan shows rendered; an edit shows its diff.
 */
public final class ApprovalInbox implements Widget<List<Approval>> {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final WidgetContext context;
    private final JPanel list = new ListPanel();
    private final JScrollPane scroll = new JScrollPane(list);
    private final Overlay overlay = new Overlay(scroll);
    private final Map<Long, Card> cards = new LinkedHashMap<>();

    public ApprovalInbox(WidgetContext context) {
        this.context = context;
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setBorder(BorderFactory.createEmptyBorder(Theme.PAD, Theme.PAD, Theme.PAD, Theme.PAD));
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        overlay.showEmpty("No approvals waiting. Permission requests from every session arrive here.");
    }

    @Override
    public JComponent component() {
        return overlay;
    }

    @Override
    public void render(List<Approval> approvals) {
        List<Long> ids = new ArrayList<>();
        for (Approval a : approvals) {
            ids.add(a.id());
            if (!cards.containsKey(a.id())) {
                Card c = new Card(a);
                cards.put(a.id(), c);
                list.add(c);
                list.add(Box.createVerticalStrut(Theme.GAP));
            }
        }
        boolean removed = cards.keySet().removeIf(id -> {
            if (ids.contains(id)) {
                return false;
            }
            Card c = cards.get(id);
            int i = indexOf(c);
            list.remove(c);
            if (i >= 0 && i < list.getComponentCount()) {
                list.remove(i); // the strut after the card
            }
            return true;
        });
        if (cards.isEmpty()) {
            overlay.showEmpty("No approvals waiting. Permission requests from every session arrive here.");
        } else {
            overlay.showContent();
        }
        list.revalidate();
        list.repaint();
        if (removed && !cards.isEmpty()) {
            cards.values().iterator().next().requestFocusInWindow();
        }
    }

    /** Moves the keyboard focus to the oldest card. */
    public void focusFirst() {
        if (!cards.isEmpty()) {
            cards.values().iterator().next().requestFocusInWindow();
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
        context.intents().dispatch(new Intent.ApprovalAnswered(a.id(), d));
    }

    /** One approval. */
    private final class Card extends JPanel {
        private final Approval approval;
        private final JTextField note = new JTextField(28);
        /** Each question's option buttons: radio buttons in a group, or check boxes when it allows several. */
        private final Map<String, java.util.List<javax.swing.AbstractButton>> questions = new LinkedHashMap<>();
        private final Map<String, JTextField> other = new LinkedHashMap<>();

        Card(Approval a) {
            this.approval = a;
            setLayout(new BorderLayout(0, Theme.GAP));
            setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Theme.CARD_BORDER, 1, true),
                    BorderFactory.createEmptyBorder(Theme.PAD, Theme.PAD, Theme.PAD, Theme.PAD)));
            setBackground(Theme.CARD);
            setFocusable(true);
            setAlignmentX(Component.LEFT_ALIGNMENT);
            JLabel head = new JLabel("<html><b>" + MarkdownHtml.escape(a.session()) + "</b>&nbsp;&nbsp;" + MarkdownHtml.escape(title(a))
                    + "&nbsp;&nbsp;<span style='color:" + Theme.hex(Theme.MUTED) + "'>" + Labels.ago(a.createdMillis(), System.currentTimeMillis())
                    + "</span></html>");
            head.setIcon(Icons.get(switch (a.kind()) {
                case QUESTION -> "question";
                case PLAN -> "plan";
                case SQL_WRITE -> "database";
                case TOOL -> "warning";
            }));
            add(head, BorderLayout.NORTH);
            add(body(a), BorderLayout.CENTER);
            add(buttons(a), BorderLayout.SOUTH);
            keys();
            addFocusListener(new FocusAdapter() {
                @Override
                public void focusGained(FocusEvent e) {
                    setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Theme.ACCENT, 1, true),
                            BorderFactory.createEmptyBorder(Theme.PAD, Theme.PAD, Theme.PAD, Theme.PAD)));
                    scrollRectToVisible(new Rectangle(0, 0, getWidth(), getHeight()));
                }

                @Override
                public void focusLost(FocusEvent e) {
                    setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Theme.CARD_BORDER, 1, true),
                            BorderFactory.createEmptyBorder(Theme.PAD, Theme.PAD, Theme.PAD, Theme.PAD)));
                }
            });
        }

        private JComponent body(Approval a) {
            JsonNode in = parse(a.inputJson());
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
                case TOOL -> switch (a.toolName()) {
                    case "Bash" -> code(in.path("command").asText("") + describe(in), "sh", 10);
                    case "Edit" -> diff(in.path("file_path").asText(""), in.path("old_string").asText(""), in.path("new_string").asText(""));
                    case "Write" -> diff(in.path("file_path").asText(""), "", in.path("content").asText(""));
                    default -> code(pretty(in), "json", 12);
                };
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
                java.util.List<javax.swing.AbstractButton> buttons = new java.util.ArrayList<>();
                JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.GAP, 2));
                options.setOpaque(false);
                options.setAlignmentX(Component.LEFT_ALIGNMENT);
                boolean first = true;
                for (JsonNode o : q.path("options")) {
                    String option = o.path("label").asText("");
                    // option labels and descriptions come from Claude: shown as text, never as HTML
                    javax.swing.AbstractButton b = Plain.only(multi ? new javax.swing.JCheckBox() : new JRadioButton());
                    b.setText(option); // after the guard: Swing decides on HTML when the text is set
                    b.setActionCommand(option);
                    b.setToolTipText(Plain.text(o.path("description").asText(null)));
                    if (!multi) {
                        b.setSelected(first);
                        group.add(b);
                    }
                    first = false;
                    buttons.add(b);
                    options.add(b);
                }
                JTextField free = new JTextField(16);
                free.putClientProperty("JTextField.placeholderText", "Other answer");
                options.add(free);
                questions.put(question, buttons);
                other.put(question, free);
                p.add(options);
            }
            return p;
        }

        private JComponent buttons(Approval a) {
            JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.GAP, 0));
            p.setOpaque(false);
            switch (a.kind()) {
                case QUESTION -> {
                    p.add(button("Answer  ⏎", "check", this::answerQuestions, true));
                    p.add(button("Skip", "deny", () -> answer(a, new ApprovalDecision.Deny(noteOr("The user skipped the questions."))), false));
                }
                case PLAN -> {
                    p.add(button("Approve plan  ⏎", "check", () -> answer(a, ApprovalDecision.allowOnce()), true));
                    p.add(button("Keep planning", "deny", () -> answer(a, new ApprovalDecision.Deny(noteOr("Keep planning."))), false));
                }
                case SQL_WRITE -> {
                    p.add(button("Run  ⏎", "check", () -> answer(a, ApprovalDecision.allowOnce()), true));
                    p.add(button("Deny  D", "deny", () -> answer(a, new ApprovalDecision.Deny(noteOr(""))), false));
                }
                case TOOL -> {
                    p.add(button("Allow once  ⏎", "check", () -> answer(a, ApprovalDecision.allowOnce()), true));
                    p.add(button("For session  S", null, () -> answer(a, new ApprovalDecision.Allow(ApprovalDecision.Scope.SESSION)), false));
                    p.add(button("Always  L", null, () -> answer(a, new ApprovalDecision.Allow(ApprovalDecision.Scope.ALWAYS)), false));
                    p.add(button("Deny  D", "deny", () -> answer(a, new ApprovalDecision.Deny(noteOr(""))), false));
                }
            }
            note.putClientProperty("JTextField.placeholderText", "Note for Claude (sent with a denial)");
            p.add(note);
            return p;
        }

        private String noteOr(String fallback) {
            String n = note.getText().strip();
            return n.isEmpty() ? fallback : n;
        }

        private void answerQuestions() {
            Map<String, String> answers = new LinkedHashMap<>();
            for (var e : questions.entrySet()) {
                String free = other.get(e.getKey()).getText().strip();
                if (!free.isEmpty()) {
                    answers.put(e.getKey(), free);
                } else {
                    // several chosen options of a multi-select question go back as one comma-separated answer
                    String chosen = String.join(", ", e.getValue().stream().filter(javax.swing.AbstractButton::isSelected)
                            .map(javax.swing.AbstractButton::getActionCommand).toList());
                    if (!chosen.isEmpty()) {
                        answers.put(e.getKey(), chosen);
                    }
                }
            }
            answer(approval, new ApprovalDecision.Answer(answers));
        }

        private void keys() {
            bind(KeyEvent.VK_ENTER, () -> primary().run());
            bind(KeyEvent.VK_A, () -> primary().run());
            bind(KeyEvent.VK_ESCAPE, this::deny);
            bind(KeyEvent.VK_D, this::deny);
            if (approval.kind() == ApprovalKind.TOOL) {
                bind(KeyEvent.VK_S, () -> answer(approval, new ApprovalDecision.Allow(ApprovalDecision.Scope.SESSION)));
                bind(KeyEvent.VK_L, () -> answer(approval, new ApprovalDecision.Allow(ApprovalDecision.Scope.ALWAYS)));
            }
            bind(KeyEvent.VK_DOWN, () -> transferFocus());
            bind(KeyEvent.VK_UP, () -> transferFocusBackward());
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

        private void bind(int key, Runnable r) {
            String id = "approval-" + key;
            getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key, 0), id);
            getActionMap().put(id, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    r.run();
                }
            });
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    }

    private static String title(Approval a) {
        return switch (a.kind()) {
            case QUESTION -> "asks";
            case PLAN -> "presents a plan";
            case SQL_WRITE -> "wants to run a statement";
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

    private static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (JsonProcessingException e) {
            return JSON.createObjectNode();
        }
    }

    private static String pretty(JsonNode n) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(n);
        } catch (JsonProcessingException e) {
            return n.toString();
        }
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
