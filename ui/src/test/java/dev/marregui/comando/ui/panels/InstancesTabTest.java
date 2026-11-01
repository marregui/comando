package dev.marregui.comando.ui.panels;

import java.awt.Component;
import java.awt.Container;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JTree;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.plaf.basic.BasicHTML;

import com.formdev.flatlaf.FlatDarkLaf;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.port.InstancesPort;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.InstancesState;
import dev.marregui.comando.testkit.States;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// [InstancesTab]: the comando instances on the Edge, their sessions, and the questions between them.
/// - a peer's name, its sessions' names and its question reach the tab as text, never as HTML
/// - refusing, forgetting and setting the role run off the event thread
class InstancesTabTest {

    private static final String MARKUP = "<html><b>peer</b>";
    private static LookAndFeel previous;

    @BeforeAll
    static void flatLaf() throws Exception {
        previous = UIManager.getLookAndFeel();
        UIManager.setLookAndFeel(new FlatDarkLaf());
    }

    @AfterAll
    static void restore() throws Exception {
        UIManager.setLookAndFeel(previous);
    }

    /// A peer instance named with markup, its session's row and the tooltip of the question it asked render with
    /// Swing's HTML off; the tooltip, which keeps the question's lines, shows the markup escaped, as text (own rule 6).
    @Test
    void aPeersNameSessionsAndQuestionReachTheTabAsText() throws Exception {
        final InstancesState.Instance me = new InstancesState.Instance("main", "comando", "2.0.0", "", "", false, "", List.of(), false, true, 0L);
        final InstancesState.Member member = new InstancesState.Member(MARKUP, false, "OPEN", "", 0L, "", "", -1, "", "", List.of(), "", "");
        final InstancesState.Instance peer = new InstancesState.Instance("x", MARKUP, "2.0.0", "", "", false, "", List.of(member), false, true, 0L);
        final InstancesState.Consult asked = new InstancesState.Consult("1", InstancesState.Direction.ANSWERED, "x", MARKUP, "consult-x",
                MARKUP, InstancesState.Status.WAITING, 1_000, 0, "");
        final AppState s = States.twoPanels().withInstances(new InstancesState(true, me, List.of(peer), List.of(asked), List.of()));
        final Object[] parsed = new Object[3];
        final String[] tip = new String[1];
        SwingUtilities.invokeAndWait(() -> {
            final InstancesTab t = new InstancesTab(i -> { }, () -> InstancesPort.NONE, () -> null);
            t.render(s);
            final JTree tree = find(t.component(), JTree.class);
            final Object root = tree.getModel().getRoot();
            final Object peerNode = tree.getModel().getChild(root, 1);
            final Object memberNode = tree.getModel().getChild(peerNode, 0);
            final JComponent row = (JComponent) tree.getCellRenderer().getTreeCellRendererComponent(tree, peerNode, false, true, false, 1, false);
            parsed[0] = row.getClientProperty(BasicHTML.propertyKey);
            final JComponent sessionRow = (JComponent) tree.getCellRenderer().getTreeCellRendererComponent(tree, memberNode, false, false, true, 2, false);
            parsed[1] = sessionRow.getClientProperty(BasicHTML.propertyKey);
            @SuppressWarnings("unchecked")
            final JList<InstancesState.Consult> list = find(t.component(), JList.class);
            final JComponent cell = (JComponent) list.getCellRenderer().getListCellRendererComponent(list, asked, 0, false, false);
            tip[0] = cell.getToolTipText();
            parsed[2] = cell.getClientProperty(BasicHTML.propertyKey);
        });
        final List<String> parsedAsHtml = new ArrayList<>();
        if (parsed[0] != null) {
            parsedAsHtml.add("the peer's name, in its row of the instances tree");
        }
        if (parsed[1] != null) {
            parsedAsHtml.add("the peer's session name, in its row under the instance");
        }
        if (parsed[2] != null) {
            parsedAsHtml.add("the peer's question, in its row in Questions");
        }
        if (tip[0] != null && tip[0].contains("<b>")) {
            parsedAsHtml.add("the peer's question, as the tooltip of its row in Questions");
        }
        assertThat(parsedAsHtml).as("text a peer instance supplies that Swing parses as HTML (own rule 6)").isEmpty();
        assertThat(tip[0]).as("the question, escaped").contains("&lt;b&gt;peer");
    }

    /// Refusing a peer's questions, forgetting a peer that left and setting this instance's role read and write files
    /// in the data root: the port hears each on a thread that is not the event thread.
    @Test
    void refusingForgettingAndSettingTheRoleRunOffTheEventThread() throws Exception {
        final Map<String, Boolean> onEdt = new ConcurrentHashMap<>();
        final InstancesPort port = new InstancesPort() {
            @Override
            public void ask(final String instance, final String text) {
            }

            @Override
            public void deny(final String instance, final boolean denied) {
                onEdt.put("deny " + instance + " " + denied, SwingUtilities.isEventDispatchThread());
            }

            @Override
            public void forget(final String instance) {
                onEdt.put("forget " + instance, SwingUtilities.isEventDispatchThread());
            }

            @Override
            public void role(final String role) {
                onEdt.put("role " + role, SwingUtilities.isEventDispatchThread());
            }
        };
        SwingUtilities.invokeAndWait(() -> {
            final InstancesTab t = new InstancesTab(i -> { }, () -> port, () -> null);
            t.deny("x", true);
            t.forget("y");
            t.role("reviews");
        });
        await().atMost(Duration.ofSeconds(5)).until(() -> onEdt.size() == 3);
        assertThat(onEdt).containsOnly(Map.entry("deny x true", false), Map.entry("forget y", false), Map.entry("role reviews", false));
    }

    private static <T extends Component> T find(final Component root, final Class<T> type) {
        if (type.isInstance(root)) {
            return type.cast(root);
        }
        if (root instanceof Container c) {
            for (final Component child : c.getComponents()) {
                final T found = find(child, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
