package dev.marregui.comando.ui.kit.action;

import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import java.awt.Component;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dev.marregui.comando.ui.kit.action.MenuEntry.SEPARATOR;
import static dev.marregui.comando.ui.kit.action.MenuEntry.menu;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [ActionRegistry#menuBar] builds the menu bar from [MenuEntry] trees and gives each command its menu path.
/// - Menus hold items, separators and submenus with icons and accelerators.
/// - Each command's group is its menu path; a command in no menu has an empty group or its area.
/// - Only commands in no menu get a window-wide key binding.
/// - Items and submenus carry the tooltips their providers give.
/// - A menu naming an unknown command, or one command twice, fails at once.
class MenuBarTest {

    private static final KeyStroke RUN = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.META_DOWN_MASK);
    private static final KeyStroke GO = KeyStroke.getKeyStroke(KeyEvent.VK_1, InputEvent.META_DOWN_MASK);

    private static ActionRegistry registry() {
        ActionRegistry r = new ActionRegistry();
        r.register(Command.of("run", "Run", "play", RUN, "", () -> { }));
        r.register(Command.of("toggle", "Connect or disconnect", "plug", null, "", () -> { }));
        r.register(Command.of("compact", "Compact", null, null, "", () -> { }));
        r.register(Command.of("model.opus", "opus", null, null, "", () -> { }));
        r.register(Command.of("go.1", "Go to panel 1", null, GO, "", () -> { }));
        return r;
    }

    private static List<String> labels(JMenu m) {
        List<String> out = new ArrayList<>();
        for (Component c : m.getMenuComponents()) {
            out.add(c instanceof JPopupMenu.Separator ? "—" : c instanceof JMenu sub ? sub.getText() + " ▸" : ((JMenuItem) c).getText());
        }
        return out;
    }

    /// A nested tree yields items, a separator and submenus in order; a submenu with an icon name carries the
    /// icon, an item carries its command's accelerator, and each command's group is its path joined with ` ▸ `.
    @Test
    void theBarHasSubmenusSeparatorsAndEachCommandsPath() {
        ActionRegistry r = registry();
        JMenuBar bar = r.menuBar(List.of(menu("Panel", null, "run", SEPARATOR,
                menu("Session", "claude", "toggle", "compact", menu("Model", null, "model.opus")))));
        JMenu panel = bar.getMenu(0);
        assertThat(panel.getText()).isEqualTo("Panel");
        assertThat(labels(panel)).containsExactly("Run", "—", "Session ▸");
        JMenu session = (JMenu) panel.getMenuComponent(2);
        assertThat(session.getIcon()).as("a submenu carries its subject's icon").isNotNull();
        assertThat(labels(session)).containsExactly("Connect or disconnect", "Compact", "Model ▸");
        assertThat(((JMenuItem) panel.getMenuComponent(0)).getAccelerator()).isEqualTo(RUN);

        assertThat(r.get("run").group()).isEqualTo("Panel");
        assertThat(r.get("toggle").group()).isEqualTo("Panel ▸ Session");
        assertThat(r.get("model.opus").group()).isEqualTo("Panel ▸ Session ▸ Model");
        assertThat(r.get("go.1").group()).as("in no menu").isEmpty();
    }

    /// [ActionRegistry#bind] maps a key to a command in no menu in the window input map, and leaves a menu
    /// item's accelerator to the menu.
    @Test
    void onlyCommandsInNoMenuGetAWindowWideKey() {
        ActionRegistry r = registry();
        r.menuBar(List.of(menu("Panel", null, "run")));
        JPanel root = new JPanel();
        r.bind(root);
        assertThat(root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(GO)).isEqualTo("go.1");
        assertThat(root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(RUN)).as("the menu item's accelerator runs it").isNull();
    }

    /// The item and submenu tooltip providers set the text they return; a null tip leaves no tooltip.
    @Test
    void itemsAndSubmenusCarryTheirTooltips() {
        JMenuBar bar = registry().menuBar(List.of(menu("Panel", null, "run", menu("Session", "claude", "toggle", "compact"))),
                id -> id.equals("run") ? "Runs the block" : null, title -> title.equals("Session") ? "The panel's session" : null);
        JMenu panel = bar.getMenu(0);
        assertThat(((JMenuItem) panel.getMenuComponent(0)).getToolTipText()).isEqualTo("Runs the block");
        JMenu session = (JMenu) panel.getMenuComponent(1);
        assertThat(session.getToolTipText()).isEqualTo("The panel's session");
        assertThat(((JMenuItem) session.getMenuComponent(1)).getToolTipText()).as("no tip, no tooltip").isNull();
    }

    /// [ActionRegistry#areas] names a menuless command's group by its area, keeps a menu path as it is, and
    /// keeps the window-wide key. Areas that leave a menuless command unnamed fail with
    /// [IllegalStateException] naming every such command.
    @Test
    void aCommandInNoMenuIsNamedByItsAreaAndKeepsItsWindowWideKey() {
        ActionRegistry r = registry();
        r.menuBar(List.of(menu("Panel", null, "run", "toggle", "compact", "model.opus")));
        r.areas(Map.of("go.1", "Panels"));
        assertThat(r.get("go.1").group()).isEqualTo("Panels");
        assertThat(r.get("run").group()).as("a menu's path stays").isEqualTo("Panel");
        JPanel root = new JPanel();
        r.bind(root);
        assertThat(root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(GO)).as("an area is no menu").isEqualTo("go.1");

        ActionRegistry missing = registry();
        missing.menuBar(List.of(menu("Panel", null, "run")));
        assertThatThrownBy(() -> missing.areas(Map.of("go.1", "Panels")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("toggle").hasMessageContaining("compact");
    }

    /// An unknown command id, or one id placed in two menus, fails with [IllegalArgumentException] naming the
    /// id or saying `placed twice`.
    @Test
    void aMenuNamingAnUnknownCommandOrOneTwiceFailsAtOnce() {
        assertThatThrownBy(() -> registry().menuBar(List.of(menu("File", null, "nope"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nope");
        assertThatThrownBy(() -> registry().menuBar(List.of(menu("File", null, "run"), menu("View", null, "run"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("placed twice");
    }
}
