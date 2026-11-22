package dev.marregui.comando.ui.dialogs.machines;

import java.awt.Component;
import java.awt.Container;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.port.MachinesPort;
import dev.marregui.comando.core.port.MachinesPort.Grant;
import dev.marregui.comando.core.port.MachinesPort.Paired;
import dev.marregui.comando.ui.kit.resource.ResourceActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// [MachinesEditor] shows this machine and the machines paired with it (record 189), over a fake [MachinesPort].
/// - This machine's id, port, fingerprint and token show, and both paired machines are listed.
/// - Pairing a token asks first, naming the fingerprint the token holds, then calls `pair` with the grant chosen;
///   Cancel calls nothing, and a text that is not a token asks nothing and calls nothing.
/// - What the port refuses shows in the message line.
/// - Unpairing asks first, then calls `unpair` on the selected machine; Keep calls nothing.
/// - Setting the listen address calls `listen`; Copy hands the token over.
/// - Every call to the port runs off the event thread.
class MachinesEditorTest {

    private static final String SELF_FINGERPRINT = "0123456789abcdef".repeat(4);
    private static final String THEIR_FINGERPRINT = "fedcba9876543210".repeat(4);
    private static final String TOKEN = "comando-pair:1:carol:100.64.0.3:18893:" + THEIR_FINGERPRINT;
    private static final String LISTEN = "Listen on this address; empty stops listening";
    private static final String UNPAIR = "Unpair the selected machine: it reaches this one no more";
    private static final Paired A = new Paired("a", "100.64.0.1", 18893, "aa".repeat(32), Grant.PEER, true, 0L);
    private static final Paired B = new Paired("b", "100.64.0.2", 18893, "bb".repeat(32), Grant.OVERSEER, false,
            1_700_000_000_000L);

    private final Fake port = new Fake();
    private final List<String> asked = new CopyOnWriteArrayList<>();
    private final List<String> copied = new CopyOnWriteArrayList<>();
    /// The option the user picks at the next question, by index: set by the test's thread, read on the event thread
    /// where the editor asks.
    private volatile int answer;

    /// The fake port: this machine and A and B; records every call, with whether it ran on the event thread.
    private static final class Fake implements MachinesPort {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<Boolean> onEdt = new CopyOnWriteArrayList<>();
        /// Why `pair` refuses, empty for none: set by the test's thread, read by the editor's `machines-*` errand that
        /// pairs.
        volatile String refusal = "";

        private void record(final String call) {
            calls.add(call);
            onEdt.add(SwingUtilities.isEventDispatchThread());
        }

        @Override
        public Self self() {
            record("self");
            return new Self("me", "100.64.0.9", 18893, SELF_FINGERPRINT,
                    "comando-pair:1:me:100.64.0.9:18893:" + SELF_FINGERPRINT);
        }

        @Override
        public List<Paired> paired() {
            record("paired");
            return List.of(A, B);
        }

        @Override
        public void listen(final String host) {
            record("listen " + host);
        }

        @Override
        public Paired pair(final String token, final Grant grant) {
            record("pair " + token + " " + grant);
            if (!refusal.isEmpty()) {
                throw new IllegalArgumentException(refusal);
            }
            return new Paired("carol", "100.64.0.3", 18893, THEIR_FINGERPRINT, grant, false, 0L);
        }

        @Override
        public boolean unpair(final String machine) {
            record("unpair " + machine);
            return true;
        }
    }

    /// Runs `f` on the event thread and answers what it gives.
    private static <T> T on(final Supplier<T> f) throws Exception {
        final AtomicReference<T> out = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> out.set(f.get()));
        return out.get();
    }

    /// The editor over the fake, once its first read of the machines has come back.
    private MachinesEditor editor() throws Exception {
        final ResourceActions.Questions questions = (parent, text, title, options) -> {
            asked.add(text);
            return answer;
        };
        final MachinesEditor e = on(() -> new MachinesEditor(port, questions, copied::add));
        await().atMost(Duration.ofSeconds(5)).until(() -> on(() -> list(e).getModel().getSize()) == 2);
        return e;
    }

    /// This machine's id, port, fingerprint and token show, and A and B are listed, A selected.
    @Test
    void listsThisMachineAndTwoPairedMachines() throws Exception {
        final MachinesEditor e = editor();
        assertThat(on(() -> ((JLabel) named(e, "Machine")).getText())).isEqualTo("me");
        assertThat(on(() -> ((JLabel) named(e, "Port")).getText())).isEqualTo("18893");
        assertThat(on(() -> ((JLabel) named(e, "Fingerprint")).getText())).isEqualTo(SELF_FINGERPRINT);
        assertThat(on(() -> ((JTextComponent) named(e, "Token")).getText())).endsWith(":me:100.64.0.9:18893:"
                + SELF_FINGERPRINT);
        assertThat(on(() -> ((JTextComponent) named(e, "Listen on")).getText())).isEqualTo("100.64.0.9");
        final List<String> ids = on(() -> {
            final List<String> out = new ArrayList<>();
            for (int i = 0; i < list(e).getModel().getSize(); i++) {
                out.add(list(e).getModel().getElementAt(i).machine());
            }
            return out;
        });
        assertThat(ids).containsExactly("a", "b");
        assertThat(on(() -> ((JLabel) named(e, "Address")).getText())).isEqualTo("100.64.0.1:18893");
        assertThat(on(() -> ((JLabel) named(e, "Connection")).getText())).isEqualTo("connected");
    }

    /// The selected machine's facts say its grant and, not connected, when it last was.
    @Test
    void theSelectedMachineSaysItsGrantAndWhenItLastConnected() throws Exception {
        final MachinesEditor e = editor();
        on(() -> {
            e.select("b");
            return null;
        });
        assertThat(on(() -> ((JLabel) named(e, "Grant")).getText())).startsWith("overseer:");
        assertThat(on(() -> ((JLabel) named(e, "Connection")).getText())).startsWith("not connected; last connected ");
        assertThat(on(() -> ((JLabel) named(e, "Its fingerprint")).getText())).isEqualTo("bb".repeat(32));
    }

    /// A token typed shows the machine and fingerprint it holds; Pair asks, naming that fingerprint, and Pair in the
    /// question calls `pair` with the token and the grant chosen, overseer here.
    @Test
    void pairingATokenCallsPairWithTheChosenGrantAfterTheConfirm() throws Exception {
        final MachinesEditor e = editor();
        answer = 0;
        on(() -> {
            ((JTextComponent) named(e, "Pair with")).setText(TOKEN);
            ((JComboBox<?>) named(e, "Grant it")).setSelectedItem(Grant.OVERSEER);
            return null;
        });
        assertThat(on(() -> ((JLabel) named(e, "Token holds")).getText())).isEqualTo("carol at 100.64.0.3:18893");
        assertThat(on(() -> ((JLabel) named(e, "Token's fingerprint")).getText())).isEqualTo(THEIR_FINGERPRINT);
        on(() -> {
            e.pairButton().doClick();
            return null;
        });
        await().atMost(Duration.ofSeconds(5)).until(() -> on(e::messageShown).startsWith("Paired with carol"));
        assertThat(asked).hasSize(1);
        assertThat(asked.getFirst()).contains(THEIR_FINGERPRINT).contains("overseer");
        assertThat(port.calls).contains("pair " + TOKEN + " OVERSEER");
        assertThat(on(e::messageShown)).isEqualTo("Paired with carol, granted overseer");
    }

    /// The grant is peer unless the user picks another.
    @Test
    void theGrantIsPeerByDefault() throws Exception {
        final MachinesEditor e = editor();
        assertThat(on(() -> ((JComboBox<?>) named(e, "Grant it")).getSelectedItem())).isEqualTo(Grant.PEER);
    }

    /// Cancel in the question calls nothing.
    @Test
    void cancellingThePairingCallsNothing() throws Exception {
        final MachinesEditor e = editor();
        answer = 1;
        on(() -> {
            ((JTextComponent) named(e, "Pair with")).setText(TOKEN);
            e.pairButton().doClick();
            return null;
        });
        assertThat(asked).hasSize(1);
        assertThat(on(() -> e.pairButton().isEnabled())).as("nothing under way").isTrue();
        assertThat(port.calls).noneMatch(c -> c.startsWith("pair "));
    }

    /// A text that does not read as a token says so, and asks nothing and calls nothing.
    @Test
    void aTextThatIsNotATokenAsksNothingAndCallsNothing() throws Exception {
        final MachinesEditor e = editor();
        on(() -> {
            ((JTextComponent) named(e, "Pair with")).setText("carol");
            e.pairButton().doClick();
            return null;
        });
        assertThat(on(() -> ((JLabel) named(e, "Token holds")).getText())).isEqualTo("not a pairing token");
        assertThat(on(e::messageShown)).startsWith("Not a pairing token");
        assertThat(asked).isEmpty();
        assertThat(port.calls).noneMatch(c -> c.startsWith("pair "));
    }

    /// The message of the port's `IllegalArgumentException` shows in the message line.
    @Test
    void aRefusedTokenShowsTheMessage() throws Exception {
        final MachinesEditor e = editor();
        port.refusal = "the token names this machine [machine=carol]";
        answer = 0;
        on(() -> {
            ((JTextComponent) named(e, "Pair with")).setText(TOKEN);
            e.pairButton().doClick();
            return null;
        });
        await().atMost(Duration.ofSeconds(5)).until(() -> on(e::messageShown).equals(port.refusal));
        assertThat(port.calls).contains("pair " + TOKEN + " PEER");
        assertThat(on(() -> ((JTextComponent) named(e, "Pair with")).getText())).as("kept to correct").isEqualTo(TOKEN);
    }

    /// Unpair asks first; Keep calls nothing, Unpair calls `unpair` on the selected machine.
    @Test
    void unpairAsksThenCallsUnpair() throws Exception {
        final MachinesEditor e = editor();
        final AbstractButton unpair = on(() -> tipped(e, UNPAIR));
        on(() -> {
            e.select("b");
            return null;
        });
        answer = 1;
        on(() -> {
            unpair.doClick();
            return null;
        });
        assertThat(asked).hasSize(1);
        assertThat(asked.getFirst()).contains("Unpair b?");
        assertThat(port.calls).noneMatch(c -> c.startsWith("unpair "));
        answer = 0;
        on(() -> {
            unpair.doClick();
            return null;
        });
        await().atMost(Duration.ofSeconds(5)).until(() -> on(e::messageShown).equals("Unpaired b"));
        assertThat(asked).hasSize(2);
        assertThat(port.calls).contains("unpair b");
    }

    /// The listen address set calls `listen` with it; Copy hands this machine's token over.
    @Test
    void settingTheListenAddressCallsListenAndCopyHandsTheTokenOver() throws Exception {
        final MachinesEditor e = editor();
        on(() -> {
            ((JTextComponent) named(e, "Listen on")).setText(" 100.64.0.10 ");
            tipped(e, LISTEN).doClick();
            return null;
        });
        await().atMost(Duration.ofSeconds(5)).until(() -> on(e::messageShown).startsWith("Listening on 100.64.0.10"));
        assertThat(port.calls).contains("listen 100.64.0.10");
        idle(e);
        on(() -> {
            tipped(e, "Copy this machine's token, to pair it on the other one").doClick();
            return null;
        });
        assertThat(copied).containsExactly("comando-pair:1:me:100.64.0.9:18893:" + SELF_FINGERPRINT);
    }

    /// Reading, pairing and unpairing all ran off the event thread.
    @Test
    void everyCallToThePortRunsOffTheEventThread() throws Exception {
        final MachinesEditor e = editor();
        answer = 0;
        on(() -> {
            ((JTextComponent) named(e, "Pair with")).setText(TOKEN);
            e.pairButton().doClick();
            return null;
        });
        await().atMost(Duration.ofSeconds(5)).until(() -> on(e::messageShown).startsWith("Paired with"));
        idle(e);
        on(() -> {
            tipped(e, UNPAIR).doClick();
            return null;
        });
        await().atMost(Duration.ofSeconds(5)).until(() -> on(e::messageShown).startsWith("Unpaired"));
        assertThat(port.calls).contains("self", "paired", "pair " + TOKEN + " PEER", "unpair a");
        assertThat(port.onEdt).as("calls on the event thread").doesNotContain(true);
    }

    @SuppressWarnings("unchecked")
    private static JList<Paired> list(final MachinesEditor e) {
        return (JList<Paired>) named(e, "Paired machines");
    }

    /// The field or fact whose accessible name is `name`, not the label that shows that name.
    private static Component named(final MachinesEditor e, final String name) {
        final List<Component> found = new ArrayList<>();
        find(e.component(), c -> c instanceof JComponent j && name.equals(j.getAccessibleContext().getAccessibleName())
                && !(c instanceof JLabel l && name.equals(l.getText())), found);
        assertThat(found).as("a component named " + name).isNotEmpty();
        return found.getFirst();
    }

    /// Waits until no call to the port is under way: the listen button, enabled but for one, says so.
    private static void idle(final MachinesEditor e) {
        await().atMost(Duration.ofSeconds(5)).until(() -> on(() -> tipped(e, LISTEN).isEnabled()));
    }

    /// The button whose tooltip is `tip`.
    private static AbstractButton tipped(final MachinesEditor e, final String tip) {
        final List<Component> found = new ArrayList<>();
        find(e.component(), c -> c instanceof AbstractButton b && tip.equals(b.getToolTipText()), found);
        assertThat(found).as("a button tipped " + tip).isNotEmpty();
        return (AbstractButton) found.getFirst();
    }

    private static void find(final Component c, final Predicate<Component> wanted,
                             final List<Component> found) {
        if (wanted.test(c)) {
            found.add(c);
        }
        if (c instanceof Container k) {
            for (final Component child : k.getComponents()) {
                find(child, wanted, found);
            }
        }
    }
}
