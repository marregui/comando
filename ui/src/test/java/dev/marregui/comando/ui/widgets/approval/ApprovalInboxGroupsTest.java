package dev.marregui.comando.ui.widgets.approval;

import java.awt.Component;
import java.awt.Container;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.ApprovalKind;
import dev.marregui.comando.core.model.Decision;
import dev.marregui.comando.ui.kit.action.ActionRegistry;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.widget.WidgetContext;

import static org.assertj.core.api.Assertions.assertThat;

/// [ApprovalInbox] as one queue in three groups (record 131).
/// - this session's cards first under its name, then the other sessions' under each name, the oldest group first, then
///   Later, the decisions held back, under each session's name
/// - Up and Down and the first card follow that order; a card in its place stays while other groups change
/// - a decision shows its title as text, its detail muted and its age; Settle and Dismiss dispatch their intents
/// - decisions alone show the list, not the empty text, and take no keyboard
class ApprovalInboxGroupsTest {

    private static final long T0 = 1_000_000_000L;

    private final List<Intent> sent = new ArrayList<>();
    private final AtomicLong now = new AtomicLong(T0 + 120_000);
    private final FakeFocus focus = new FakeFocus();
    private final ApprovalInbox inbox = new ApprovalInbox(new WidgetContext(sent::add, new ActionRegistry()), now::get, focus);

    @AfterEach
    void unmount() {
        inbox.unmount();
    }

    /** The focus owner a test sets, and every component the inbox asked to focus. */
    private static final class FakeFocus implements ApprovalInbox.Focus {
        Component owner;
        final List<Component> requested = new ArrayList<>();

        @Override
        public Component owner() {
            return owner;
        }

        @Override
        public void request(final Component c) {
            requested.add(c);
        }
    }

    private static Approval bash(final long id, final String session, final long created) {
        return new Approval(id, session, ApprovalKind.TOOL, "Bash", "{\"command\":\"ls\"}", "ls", created);
    }

    private static Decision decision(final long id, final String session, final long created) {
        return new Decision(id, session, "Keep the KB doc " + id + "?", "It disagrees with the code.", created);
    }

    /** Approvals of three sessions, out of session order, oldest first overall; `c`'s card is the oldest. */
    private static final List<Approval> MIXED = List.of(
            bash(3, "c", T0 + 5), bash(1, "b", T0 + 10), bash(2, "work", T0 + 20), bash(4, "b", T0 + 30), bash(5, "work", T0 + 40));

    /** Decisions of two sessions; `c`'s first is the oldest. */
    private static final List<Decision> HELD = List.of(decision(7, "c", T0 + 1), decision(8, "work", T0 + 2), decision(9, "c", T0 + 3));

    /// This session's cards come first under its name, then the other sessions' under each name with the session of
    /// the oldest card first, then Later with the decisions under each session's name, the oldest session first; within
    /// a group the oldest comes first.
    @Test
    void theGroupsComeInOrderThisSessionOthersThenLater() throws Exception {
        SwingUtilities.invokeAndWait(() -> inbox.render(new ApprovalInbox.Queue(MIXED, "work", HELD)));
        assertThat(sequence()).containsExactly(
                "§THIS SESSION", "@work", "#2", "#5",
                "§OTHER SESSIONS", "@c", "#3", "@b", "#1", "#4",
                "§LATER", "@c", "D7", "D9", "@work", "D8");
    }

    /// With no session in the focused panel every card is in one group of sessions; with no card of the focused session
    /// there is no This session group; with no decision there is no Later group.
    @Test
    void anEmptyGroupIsNotShown() throws Exception {
        SwingUtilities.invokeAndWait(() -> inbox.render(new ApprovalInbox.Queue(MIXED, "", List.of())));
        assertThat(sequence()).containsExactly("§SESSIONS", "@c", "#3", "@b", "#1", "#4", "@work", "#2", "#5");

        SwingUtilities.invokeAndWait(() -> inbox.render(new ApprovalInbox.Queue(List.of(bash(1, "b", T0)), "work", List.of())));
        assertThat(sequence()).containsExactly("§OTHER SESSIONS", "@b", "#1");
    }

    /// Up and Down move in view order, across groups; the first card is this session's oldest.
    @Test
    void theKeysAndTheFirstCardFollowTheViewOrder() throws Exception {
        SwingUtilities.invokeAndWait(() -> inbox.render(new ApprovalInbox.Queue(MIXED, "work", HELD)));
        SwingUtilities.invokeAndWait(inbox::focusFirst);
        assertThat(focus.requested.getLast().getName()).isEqualTo("approval-2");

        focus.owner = item("approval-5");
        assertThat(press(focus.owner, KeyEvent.VK_DOWN)).isTrue();
        assertThat(focus.requested.getLast().getName()).as("past this session, the oldest other group").isEqualTo("approval-3");
        focus.owner = item("approval-3");
        assertThat(press(focus.owner, KeyEvent.VK_UP)).isTrue();
        assertThat(focus.requested.getLast().getName()).isEqualTo("approval-5");
    }

    /// A card of another session arriving, and the focused panel changing, keep the same card for the same request:
    /// what the user typed in it stays.
    @Test
    void aCardStaysTheSameWhileTheGroupsChange() throws Exception {
        SwingUtilities.invokeAndWait(() -> inbox.render(new ApprovalInbox.Queue(List.of(bash(2, "work", T0)), "work", List.of())));
        final Component card = item("approval-2");
        SwingUtilities.invokeAndWait(() -> inbox.render(new ApprovalInbox.Queue(List.of(bash(2, "work", T0), bash(6, "b", T0 + 1)), "work",
                List.of(decision(7, "c", T0)))));
        assertThat(item("approval-2")).isSameAs(card);
        assertThat(sequence()).containsExactly("§THIS SESSION", "@work", "#2", "§OTHER SESSIONS", "@b", "#6", "§LATER", "@c", "D7");

        SwingUtilities.invokeAndWait(() -> inbox.render(new ApprovalInbox.Queue(List.of(bash(2, "work", T0), bash(6, "b", T0 + 1)), "b",
                List.of())));
        assertThat(item("approval-2")).isSameAs(card);
        assertThat(sequence()).containsExactly("§THIS SESSION", "@b", "#6", "§OTHER SESSIONS", "@work", "#2");
    }

    /// Settle dispatches SettleDecision and Dismiss dispatches DismissDecision, each with the decision's id; neither
    /// button takes the keyboard.
    @Test
    void settleAndDismissDispatchTheirIntents() throws Exception {
        SwingUtilities.invokeAndWait(() -> inbox.render(new ApprovalInbox.Queue(List.of(), "work", HELD)));
        final AbstractButton settle = (AbstractButton) named(item("decision-9"), "settle");
        final AbstractButton dismiss = (AbstractButton) named(item("decision-8"), "dismiss");
        assertThat(settle.isFocusable()).isFalse();
        assertThat(dismiss.isFocusable()).isFalse();

        SwingUtilities.invokeAndWait(settle::doClick);
        SwingUtilities.invokeAndWait(dismiss::doClick);
        assertThat(sent).containsExactly(new Intent.SettleDecision(9), new Intent.DismissDecision(8));
    }

    /// Settle is off on a decision whose session runs its checkpoint (the reducer would refuse it, record 130), and on
    /// again once the checkpoint has ended; Dismiss stays on.
    @Test
    void settleIsOffWhileTheDecisionsSessionRunsItsCheckpoint() throws Exception {
        SwingUtilities.invokeAndWait(() -> inbox.render(new ApprovalInbox.Queue(List.of(), "work", HELD, Set.of(9L))));
        final AbstractButton settle = (AbstractButton) named(item("decision-9"), "settle");
        assertThat(settle.isEnabled()).isFalse();
        assertThat(named(item("decision-9"), "dismiss").isEnabled()).isTrue();
        assertThat(named(item("decision-8"), "settle").isEnabled()).isTrue();

        SwingUtilities.invokeAndWait(() -> inbox.render(new ApprovalInbox.Queue(List.of(), "work", HELD)));
        assertThat(settle.isEnabled()).isTrue();
    }

    /// A decision shows its title as text even when it starts like HTML, its detail muted, and its age, which ticks;
    /// decisions alone show the list, not the empty text.
    @Test
    void aDecisionShowsItsTitleDetailAndAge() throws Exception {
        final Decision html = new Decision(1, "work", "<html><b>bold</b>", "Why it waits", T0);
        SwingUtilities.invokeAndWait(() -> inbox.render(new ApprovalInbox.Queue(List.of(), "", List.of(html))));
        final Component row = item("decision-1");
        final JLabel title = (JLabel) named(row, "title");
        assertThat(title.getText()).startsWith("<html><b>bold</b>");
        assertThat(title.getClientProperty("html.disable")).isEqualTo(Boolean.TRUE);
        final JTextArea detail = (JTextArea) named(row, "detail");
        assertThat(detail.getText()).isEqualTo("Why it waits");
        assertThat(detail.getForeground()).isEqualTo(Theme.MUTED);
        final JLabel age = (JLabel) named(row, "age");
        assertThat(age.getText()).isEqualTo("2 min ago");
        assertThat(row.isFocusable()).as("a decision takes no keyboard").isFalse();
        assertThat(inbox.ageWanted()).as("decisions alone keep the list and its ages").isTrue();

        now.addAndGet(120_000);
        SwingUtilities.invokeAndWait(inbox::tick);
        assertThat(age.getText()).isEqualTo("4 min ago");

        SwingUtilities.invokeAndWait(() -> inbox.render(new ApprovalInbox.Queue(List.of(), "", List.of())));
        assertThat(sequence()).isEmpty();
        assertThat(inbox.ageWanted()).isFalse();
    }

    // ---- reading the list

    /** The list's rows in order: `§` a group's header, `@` a session's name, `#` a card's id, `D` a decision's id. */
    private List<String> sequence() {
        final List<String> out = new ArrayList<>();
        for (final Component c : list().getComponents()) {
            final String name = c.getName();
            if (name == null) {
                continue;
            }
            if (name.equals("section")) {
                out.add("§" + ((JLabel) c).getText());
            } else if (name.equals("session")) {
                out.add("@" + ((JLabel) c).getText());
            } else if (name.startsWith("approval-")) {
                out.add("#" + name.substring("approval-".length()));
            } else if (name.startsWith("decision-")) {
                out.add("D" + name.substring("decision-".length()));
            }
        }
        return out;
    }

    private Container list() {
        return (Container) ((JScrollPane) find(inbox.component(), c -> c instanceof JScrollPane)).getViewport().getView();
    }

    private Component item(final String name) {
        for (final Component c : list().getComponents()) {
            if (name.equals(c.getName())) {
                return c;
            }
        }
        throw new AssertionError("no " + name + " in " + sequence());
    }

    private static Component find(final Component root, final Predicate<Component> p) {
        if (p.test(root)) {
            return root;
        }
        if (root instanceof Container k) {
            for (final Component c : k.getComponents()) {
                final Component f = find(c, p);
                if (f != null) {
                    return f;
                }
            }
        }
        return null;
    }

    private static Component named(final Component root, final String name) {
        final Component c = find(root, x -> name.equals(x.getName()));
        assertThat(c).as("a %s in %s", name, root.getName()).isNotNull();
        return c;
    }

    private static boolean press(final Component c, final int key) {
        return SwingUtilities.processKeyBindings(new KeyEvent(c, KeyEvent.KEY_PRESSED, 0, 0, key, KeyEvent.CHAR_UNDEFINED));
    }
}
