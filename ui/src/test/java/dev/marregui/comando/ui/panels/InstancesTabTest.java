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
import javax.swing.event.TreeModelEvent;
import javax.swing.event.TreeModelListener;
import javax.swing.plaf.basic.BasicHTML;
import javax.swing.tree.DefaultMutableTreeNode;

import com.formdev.flatlaf.FlatDarkLaf;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.port.InstancesPort;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.InstancesState;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.testkit.States;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// [InstancesTab]: the comando instances on the Edge, their sessions, and the questions between them.
/// - a peer's name, its sessions' names and its question reach the tab as text, never as HTML
/// - an instance the user opened and selected stays so when what its row says changes
/// - a change only the icons show draws the rows again
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
            parsed[0] = html(row);
            final JComponent sessionRow = (JComponent) tree.getCellRenderer().getTreeCellRendererComponent(tree, memberNode, false, false, true, 2, false);
            parsed[1] = html(sessionRow);
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

    /// An instance the user opened and selected stays open and selected when its role, which its row says after its
    /// name, changes: a row is matched by its name across a refresh, not by all it says.
    @Test
    void anOpenedSelectedInstanceStaysSoWhenItsRoleChanges() throws Exception {
        final InstancesState.Instance me = new InstancesState.Instance("main", "comando", "2.0.0", "", "", false, "", List.of(), false, true, 0L);
        final InstancesState.Member member = new InstancesState.Member("helper", false, "OPEN", "", 0L, "", "", -1, "", "", List.of(), "", "");
        final InstancesState.Instance before = new InstancesState.Instance("x", "Expert", "2.0.0", "reviews", "", false, "", List.of(member), false, true, 0L);
        final InstancesState.Instance after = new InstancesState.Instance("x", "Expert", "2.0.0", "designs", "", false, "", List.of(member), false, true, 0L);
        final Object[] seen = new Object[3];
        SwingUtilities.invokeAndWait(() -> {
            final InstancesTab t = new InstancesTab(i -> { }, () -> InstancesPort.NONE, () -> null);
            t.render(States.twoPanels().withInstances(new InstancesState(true, me, List.of(before), List.of(), List.of())));
            final JTree tree = find(t.component(), JTree.class);
            tree.expandRow(1);
            tree.setSelectionRow(1);
            t.render(States.twoPanels().withInstances(new InstancesState(true, me, List.of(after), List.of(), List.of())));
            seen[0] = tree.isExpanded(1);
            seen[1] = tree.getSelectionRows() == null ? null : tree.getSelectionRows()[0];
            seen[2] = ((ViewSpec.TreeNode) ((DefaultMutableTreeNode) tree.getPathForRow(1).getLastPathComponent()).getUserObject()).detail();
        });
        assertThat(seen[0]).as("the instance still open").isEqualTo(true);
        assertThat(seen[1]).as("the instance still selected").isEqualTo(1);
        assertThat(seen[2]).as("the row says the new role after the name").isEqualTo("designs");
    }

    /// A peer refused shows only in its row's dot, its row saying the same: the rows are drawn again, so the dot
    /// turns to the refused colour at once; the row is named, for a screen reader, by all it says.
    @Test
    void aChangeOnlyTheIconShowsRedrawsTheRows() throws Exception {
        final InstancesState.Instance me = new InstancesState.Instance("main", "comando", "2.0.0", "", "", false, "", List.of(), false, true, 0L);
        final InstancesState.Instance peer = new InstancesState.Instance("x", "Expert", "2.0.0", "reviews", "", false, "", List.of(), false, true, 0L);
        final int[] changed = new int[1];
        final String[] named = new String[1];
        SwingUtilities.invokeAndWait(() -> {
            final InstancesTab t = new InstancesTab(i -> { }, () -> InstancesPort.NONE, () -> null);
            t.render(States.twoPanels().withInstances(new InstancesState(true, me, List.of(peer), List.of(), List.of())));
            final JTree tree = find(t.component(), JTree.class);
            tree.getModel().addTreeModelListener(new TreeModelAdapter(changed));
            t.render(States.twoPanels().withInstances(new InstancesState(true, me, List.of(peer), List.of(), List.of("x"))));
            final Object row = tree.getPathForRow(1).getLastPathComponent();
            final Component cell = tree.getCellRenderer().getTreeCellRendererComponent(tree, row, false, false, true, 1, false);
            named[0] = cell.getAccessibleContext().getAccessibleName();
        });
        assertThat(changed[0]).as("rows drawn again after the peer was refused").isPositive();
        assertThat(named[0]).as("the row's name for a screen reader").isEqualTo("Expert · reviews");
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

    /// Counts the nodes the model says changed into `changed[0]`.
    private record TreeModelAdapter(int[] changed) implements TreeModelListener {
        @Override
        public void treeNodesChanged(final TreeModelEvent e) {
            changed[0]++;
        }

        @Override
        public void treeNodesInserted(final TreeModelEvent e) {
        }

        @Override
        public void treeNodesRemoved(final TreeModelEvent e) {
        }

        @Override
        public void treeStructureChanged(final TreeModelEvent e) {
        }
    }

    /// The HTML view Swing parsed for `c`, or for any component inside it; null when it parsed none.
    private static Object html(final Component c) {
        if (c instanceof JComponent j && j.getClientProperty(BasicHTML.propertyKey) != null) {
            return j.getClientProperty(BasicHTML.propertyKey);
        }
        if (c instanceof Container k) {
            for (final Component child : k.getComponents()) {
                final Object parsed = html(child);
                if (parsed != null) {
                    return parsed;
                }
            }
        }
        return null;
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
