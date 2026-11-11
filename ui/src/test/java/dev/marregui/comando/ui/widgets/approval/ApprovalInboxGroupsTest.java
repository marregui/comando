package dev.marregui.comando.ui.widgets.approval;

import java.awt.Component;
import java.awt.Container;
import java.awt.event.ContainerAdapter;
import java.awt.event.ContainerEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.ApprovalKind;
import dev.marregui.comando.ui.kit.action.ActionRegistry;
import dev.marregui.comando.ui.widget.WidgetContext;

import static org.assertj.core.api.Assertions.assertThat;

/// [ApprovalInbox] as one queue in two groups (record 131).
/// - this session's cards first under its name, then the other sessions' under each name, the oldest group first
/// - Up and Down and the first card follow that order; a card in its place stays while other groups change, and is
///   never taken out of the list while a card above it leaves
class ApprovalInboxGroupsTest {

    private static final long T0 = 1_000_000_000L;

    private final List<Intent> sent = new ArrayList<>();
    private final AtomicLong now = new AtomicLong(T0 + 120_000);
    private final FakeFocus focus = new FakeFocus();
    private ApprovalInbox inbox;

    @BeforeEach
    void mount() throws Exception {
        SwingUtilities.invokeAndWait(() -> inbox = inboxOf());
    }

    @AfterEach
    void unmount() throws Exception {
        SwingUtilities.invokeAndWait(inbox::unmount);
    }

    private ApprovalInbox inboxOf() {
        return new ApprovalInbox(new WidgetContext(sent::add, new ActionRegistry()), now::get, focus);
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

    /** Approvals of three sessions, out of session order, oldest first overall; `c`'s card is the oldest. */
    private static final List<Approval> MIXED = List.of(
            bash(3, "c", T0 + 5), bash(1, "b", T0 + 10), bash(2, "work", T0 + 20), bash(4, "b", T0 + 30), bash(5, "work", T0 + 40));

    /// This session's cards come first under its name, then the other sessions' under each name with the session of
    /// the oldest card first; within a group the oldest comes first.
    @Test
    void theGroupsComeInOrderThisSessionThenOthers() throws Exception {
        SwingUtilities.invokeAndWait(() -> inbox.render(new ApprovalInbox.Queue(MIXED, "work")));
        assertThat(sequence()).containsExactly(
                "§THIS SESSION", "@work", "#2", "#5",
                "§OTHER SESSIONS", "@c", "#3", "@b", "#1", "#4");
    }

    /// With no session in the focused panel every card is in one group of sessions; with no card of the focused session
    /// there is no This session group; with no card at all the list is empty and no age ticks.
    @Test
    void anEmptyGroupIsNotShown() throws Exception {
        SwingUtilities.invokeAndWait(() -> inbox.render(new ApprovalInbox.Queue(MIXED, "")));
        assertThat(sequence()).containsExactly("§SESSIONS", "@c", "#3", "@b", "#1", "#4", "@work", "#2", "#5");

        SwingUtilities.invokeAndWait(() -> inbox.render(new ApprovalInbox.Queue(List.of(bash(1, "b", T0)), "work")));
        assertThat(sequence()).containsExactly("§OTHER SESSIONS", "@b", "#1");

        SwingUtilities.invokeAndWait(() -> inbox.render(new ApprovalInbox.Queue(List.of(), "work")));
        assertThat(sequence()).isEmpty();
        assertThat(inbox.ageWanted()).isFalse();
    }

    /// Up and Down move in view order, across groups; the first card is this session's oldest.
    @Test
    void theKeysAndTheFirstCardFollowTheViewOrder() throws Exception {
        SwingUtilities.invokeAndWait(() -> inbox.render(new ApprovalInbox.Queue(MIXED, "work")));
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
        SwingUtilities.invokeAndWait(() -> inbox.render(new ApprovalInbox.Queue(List.of(bash(2, "work", T0)), "work")));
        final Component card = item("approval-2");
        final List<Approval> two = List.of(bash(2, "work", T0), bash(6, "b", T0 + 1));
        SwingUtilities.invokeAndWait(() -> inbox.render(new ApprovalInbox.Queue(two, "work")));
        assertThat(item("approval-2")).isSameAs(card);
        assertThat(sequence()).containsExactly("§THIS SESSION", "@work", "#2", "§OTHER SESSIONS", "@b", "#6");

        SwingUtilities.invokeAndWait(() -> inbox.render(new ApprovalInbox.Queue(two, "b")));
        assertThat(item("approval-2")).isSameAs(card);
        assertThat(sequence()).containsExactly("§THIS SESSION", "@b", "#6", "§OTHER SESSIONS", "@work", "#2");
    }

    // ---- reading the list

    /** The list's rows in order: `§` a group's header, `@` a session's name, `#` a card's id. */
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
            }
        }
        return out;
    }

    /// A card above the one the keyboard is in leaves, of another group or of the same one: the staying card is never
    /// taken out of the list and put back, which would move the keyboard out of it (AWT's `removeNotify` passes the
    /// focus on).
    @Test
    void aStayingCardIsNotTakenOutWhenACardAboveLeaves() throws Exception {
        for (final String second : List.of("b", "work")) {
            final ApprovalInbox[] made = new ApprovalInbox[1];
            SwingUtilities.invokeAndWait(() -> made[0] = inboxOf());
            final ApprovalInbox box = made[0];
            try {
                SwingUtilities.invokeAndWait(() -> box.render(new ApprovalInbox.Queue(List.of(bash(2, "work", T0), bash(5, second, T0 + 1)),
                        "work")));
                final List<String> removed = new ArrayList<>();
                SwingUtilities.invokeAndWait(() -> listOf(box).addContainerListener(new ContainerAdapter() {
                    @Override
                    public void componentRemoved(final ContainerEvent e) {
                        removed.add(String.valueOf(e.getChild().getName()));
                    }
                }));
                SwingUtilities.invokeAndWait(() -> box.render(new ApprovalInbox.Queue(List.of(bash(5, second, T0 + 1)),
                        "work")));
                assertThat(removed).as("taken out of the list, card 5 of session " + second).doesNotContain("approval-5")
                        .contains("approval-2");
                assertThat(order(box)).as("the staying card in its place").contains("approval-5");
            } finally {
                SwingUtilities.invokeAndWait(box::unmount);
            }
        }
    }

    private static List<String> order(final ApprovalInbox box) throws Exception {
        final List<String> names = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            for (final Component c : listOf(box).getComponents()) {
                names.add(String.valueOf(c.getName()));
            }
        });
        return names;
    }

    private static Container listOf(final ApprovalInbox box) {
        return (Container) ((JScrollPane) find(box.component(), c -> c instanceof JScrollPane)).getViewport().getView();
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
