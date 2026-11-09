package dev.marregui.comando.ui.widgets.approval;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import javax.swing.AbstractButton;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.ApprovalKind;
import dev.marregui.comando.core.model.Decision;
import dev.marregui.comando.ui.kit.action.ActionRegistry;
import dev.marregui.comando.ui.kit.async.OnEdt;
import dev.marregui.comando.ui.widget.WidgetContext;

import static org.assertj.core.api.Assertions.assertThat;

/// The Later group's decisions answered as a batch (record 142).
/// - a decision with options shows one radio button per option, one choice, and an Other answer line, as a question
///   card does; one without shows the typed line and no option
/// - Send answers is off until a card holds an answer, and sends every answered card's answer in the group's order;
///   the cards not answered are not in it
/// - a choice and a typed line stay through a render of the group
@ExtendWith(OnEdt.class)
class ApprovalInboxAnswersTest {

    private static final long T0 = 1_000_000_000L;
    /// Decisions of two sessions: `c`'s are the oldest, so the group's order is 7, 9, 8.
    private static final List<Decision> HELD = List.of(
            new Decision(7, "c", "Keep the KB doc?", "It disagrees with the code.", T0 + 1, List.of("Keep", "Drop")),
            new Decision(8, "work", "Name the release?", "", T0 + 2),
            new Decision(9, "c", "Which theme?", "", T0 + 3, List.of("Graphite", "Gold", "Both")));

    private final List<Intent> sent = new ArrayList<>();
    private ApprovalInbox inbox;

    @BeforeEach
    void mount() throws Exception {
        sent.clear();
        SwingUtilities.invokeAndWait(() -> inbox = new ApprovalInbox(new WidgetContext(sent::add, new ActionRegistry()),
                () -> T0 + 60_000, new NoFocus()));
    }

    @AfterEach
    void unmount() throws Exception {
        SwingUtilities.invokeAndWait(inbox::unmount);
    }

    /// A decision with options shows one radio button per option, as text, in its order, one choice at a time, and an
    /// Other answer line; a decision without options shows the typed line and no option.
    @Test
    void aDecisionWithOptionsShowsOneToggleEachAndOneWithoutATypedLine() {
        inbox.render(new ApprovalInbox.Queue(List.of(), "", HELD));
        final List<AbstractButton> options = options(item("decision-9"));
        assertThat(options).extracting(AbstractButton::getText).containsExactly("Graphite", "Gold", "Both");
        assertThat(options).allMatch(b -> b instanceof JRadioButton);
        assertThat(options.getFirst().getClientProperty("html.disable")).isEqualTo(Boolean.TRUE);
        assertThat(typed(item("decision-9")).getClientProperty("JTextField.placeholderText"))
                .as("an Other answer beside options").isEqualTo("Other answer");
        options.getFirst().doClick();
        options.get(1).doClick();
        assertThat(options).extracting(AbstractButton::isSelected).containsExactly(false, true, false);

        assertThat(options(item("decision-8"))).isEmpty();
        assertThat(all(item("decision-8"), c -> "answer".equals(c.getName()))).hasSize(1);
    }

    /// Send answers is off while no card holds an answer, a blank line counting as none; once a choice and a line are
    /// given it sends one `DecisionsAnswered` with both, in the group's order (9 of the older session before 8), and
    /// the unanswered decision 7 is not in it.
    @Test
    void sendAnswersSendsEveryAnsweredCardInTheGroupsOrder() {
        inbox.render(new ApprovalInbox.Queue(List.of(), "", HELD));
        final AbstractButton send = send();
        assertThat(send.isEnabled()).as("nothing answered").isFalse();
        typed(item("decision-8")).setText("   ");
        assertThat(send.isEnabled()).as("a blank line answers nothing").isFalse();
        typed(item("decision-8")).setText(" 2026.16 ");
        assertThat(send.isEnabled()).isTrue();
        options(item("decision-9")).getLast().doClick();
        send.doClick();
        final Map<Long, String> expected = new LinkedHashMap<>();
        expected.put(9L, "Both");
        expected.put(8L, "2026.16");
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst()).isInstanceOfSatisfying(Intent.DecisionsAnswered.class,
                a -> assertThat(a.answers()).containsExactlyEntriesOf(expected));
    }

    /// A card with options sends its Other answer while one is typed, chosen option or not, as a question card does;
    /// with the line emptied, the option chosen.
    @Test
    void anOtherAnswerIsSentWhileOneIsTyped() {
        inbox.render(new ApprovalInbox.Queue(List.of(), "", HELD));
        options(item("decision-9")).getFirst().doClick();
        typed(item("decision-9")).setText(" Neither ");
        send().doClick();
        assertThat(sent.getFirst()).isInstanceOfSatisfying(Intent.DecisionsAnswered.class,
                a -> assertThat(a.answers()).containsExactlyEntriesOf(Map.of(9L, "Neither")));
        typed(item("decision-9")).setText("");
        send().doClick();
        assertThat(sent.getLast()).isInstanceOfSatisfying(Intent.DecisionsAnswered.class,
                a -> assertThat(a.answers()).containsExactlyEntriesOf(Map.of(9L, "Graphite")));
    }

    /// A choice and a typed line stay while the group renders again (an approval arrives, decision 7 leaves), and Send
    /// answers stays on; once the answered decisions leave, the one never answered is still shown and Send is off.
    @Test
    void aChoiceSurvivesARenderOfTheGroup() {
        inbox.render(new ApprovalInbox.Queue(List.of(), "", HELD));
        options(item("decision-9")).getFirst().doClick();
        typed(item("decision-8")).setText("later");
        final Approval bash = new Approval(3, "work", ApprovalKind.TOOL, "Bash", "{\"command\":\"ls\"}", "ls", T0);
        inbox.render(new ApprovalInbox.Queue(List.of(bash), "", HELD.subList(1, 3)));
        assertThat(options(item("decision-9")).getFirst().isSelected()).isTrue();
        assertThat(typed(item("decision-8")).getText()).isEqualTo("later");
        assertThat(send().isEnabled()).isTrue();

        inbox.render(new ApprovalInbox.Queue(List.of(), "", HELD.subList(0, 1)));
        assertThat(options(item("decision-7"))).extracting(AbstractButton::isSelected).containsExactly(false, false);
        assertThat(send().isEnabled()).isFalse();
    }

    private static List<AbstractButton> options(final Component row) {
        return all(row, c -> "option".equals(c.getName())).stream().map(AbstractButton.class::cast).toList();
    }

    private static JTextField typed(final Component row) {
        return (JTextField) all(row, c -> "answer".equals(c.getName())).getFirst();
    }

    private static List<Component> all(final Component root, final Predicate<Component> p) {
        final List<Component> out = new ArrayList<>();
        collect(root, p, out);
        return out;
    }

    private static void collect(final Component c, final Predicate<Component> p, final List<Component> out) {
        if (p.test(c)) {
            out.add(c);
        }
        if (c instanceof Container k) {
            for (final Component child : k.getComponents()) {
                collect(child, p, out);
            }
        }
    }

    private AbstractButton send() {
        return (AbstractButton) all(list(), c -> "send-answers".equals(c.getName())).getFirst();
    }

    private Container list() {
        final JScrollPane scroll = (JScrollPane) all(inbox.component(), c -> c instanceof JScrollPane).getFirst();
        return (Container) scroll.getViewport().getView();
    }

    private Component item(final String name) {
        for (final Component c : list().getComponents()) {
            if (name.equals(c.getName())) {
                return c;
            }
        }
        throw new AssertionError("no " + name);
    }

    /// No focus owner; a request moves nothing.
    private static final class NoFocus implements ApprovalInbox.Focus {
        @Override
        public Component owner() {
            return null;
        }

        @Override
        public void request(final Component c) {
            // nothing holds the keyboard in these tests
        }
    }
}
