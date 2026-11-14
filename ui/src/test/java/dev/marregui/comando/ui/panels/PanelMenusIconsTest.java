package dev.marregui.comando.ui.panels;

import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.LookAndFeel;
import javax.swing.UIManager;

import com.formdev.flatlaf.FlatDarkLaf;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import dev.marregui.comando.core.port.InstancesPort;
import dev.marregui.comando.core.schema.SchemaRegistry;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.InstancesState;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.testkit.States;
import dev.marregui.comando.ui.kit.async.OnEdt;

import static org.assertj.core.api.Assertions.assertThat;

/// Every item of the panels' own context menus carries an icon, as every menu of comando does.
/// - an instance's row menu, for this instance, one that left and one present
/// - the recent notices, with none and with one, and a notice's own menu
/// - a panel's connection menu, bound and with no connection to bind
@ExtendWith(OnEdt.class)
class PanelMenusIconsTest {

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

    /// This instance's row offers Set the role and Copy, one that left Forget and Copy, a present one Ask, Refuse and
    /// Copy; each with its icon, the check box item too.
    @Test
    void everyItemOfAnInstancesRowMenuHasAnIcon() {
        final InstancesTab tab = new InstancesTab(_ -> { }, () -> InstancesPort.NONE, () -> null);
        final List<JMenuItem> items = new ArrayList<>();
        items.addAll(items(tab.menuFor(new InstancesTab.Row(instance("main", "comando", true), null, true))));
        items.addAll(items(tab.menuFor(new InstancesTab.Row(instance("gone", "Expert", false), null, false))));
        items.addAll(items(tab.menuFor(new InstancesTab.Row(instance("x", "Reviewer", true), null, false))));

        assertThat(texts(items)).containsExactly("Set the role…", "Copy the instance id (main)", "Forget Expert",
                "Copy the instance id (gone)", "Ask Reviewer…", "Refuse questions from Reviewer",
                "Copy the instance id (x)");
        assertThat(bare(items)).as("items without an icon").isEmpty();
    }

    /// The recent notices say there are none and offer Dismiss, or list the notices and offer Dismiss; a notice's own
    /// menu offers Copy. Each item with its icon.
    @Test
    void everyItemOfTheRecentNoticesAndOfANoticesMenuHasAnIcon() {
        final StatusBar empty = new StatusBar(_ -> { });
        empty.render(States.twoPanels());
        final Notice saved = new Notice(Notice.Level.INFO, "Saved notes", 6L);
        final StatusBar some = new StatusBar(_ -> { });
        some.render(States.twoPanels().withNotices(saved, List.of(saved)));
        final List<JMenuItem> items = new ArrayList<>();
        items.addAll(items(empty.historyMenu()));
        items.addAll(items(some.historyMenu()));
        items.addAll(items(StatusBar.noticeMenu(saved)));

        assertThat(texts(items)).containsExactly("No notices yet", "Dismiss", "Dismiss", "Copy");
        assertThat(bare(items)).as("items without an icon").isEmpty();
    }

    /// A panel bound to a connection offers each connection and Unbind; with no connection at all the menu says how
    /// to make one. Each item with its icon, the disabled note too.
    @Test
    void everyItemOfAPanelsConnectionMenuHasAnIcon() {
        final AppState bound = States.twoPanels();
        final PanelState one = bound.panels().getFirst();
        final EditorPanel panel = new EditorPanel(one, _ -> { }, new SchemaRegistry());
        panel.render(one, bound);
        final List<JMenuItem> items = new ArrayList<>(items(panel.connectionMenu()));
        final PanelState unbound = new PanelState(States.P2, "two", "", "");
        final EditorPanel alone = new EditorPanel(unbound, _ -> { }, new SchemaRegistry());
        alone.render(unbound, AppState.initial(List.of(unbound), List.of(), List.of()));
        items.addAll(items(alone.connectionMenu()));

        assertThat(texts(items)).hasSize(3).contains("Unbind",
                "No connections; New connection… in the Connections tab's menu adds one");
        assertThat(bare(items)).as("items without an icon").isEmpty();
    }

    private static InstancesState.Instance instance(final String slug, final String name, final boolean present) {
        return new InstancesState.Instance(slug, name, "2.0.0", "", "", false, "", List.of(), false, present, 0L);
    }

    /// Every item of `menu`, those of its submenus included.
    private static List<JMenuItem> items(final JPopupMenu menu) {
        final List<JMenuItem> out = new ArrayList<>();
        collect(menu.getComponents(), out);
        return out;
    }

    private static void collect(final Component[] components, final List<JMenuItem> out) {
        for (final Component c : components) {
            if (c instanceof JMenu m) {
                out.add(m);
                collect(m.getMenuComponents(), out);
            } else if (c instanceof JMenuItem i) {
                out.add(i);
            }
        }
    }

    private static List<String> texts(final List<JMenuItem> items) {
        return items.stream().map(JMenuItem::getText).toList();
    }

    private static List<String> bare(final List<JMenuItem> items) {
        return items.stream().filter(i -> i.getIcon() == null).map(JMenuItem::getText).toList();
    }
}
