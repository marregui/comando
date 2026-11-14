package dev.marregui.comando.ui.widgets.terminal;

import java.util.List;
import java.util.stream.Stream;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.ui.kit.icon.Icons;

import static org.assertj.core.api.Assertions.assertThat;

/// [TerminalWidget#withIcons] gives JediTerm's right-click menu the icons every comando menu has (record 156).
class TerminalMenuTest {

    /// Each item JediTerm names gets its own icon, one it might add later the terminal's; separators stay as they are.
    @Test
    void everyItemOfTheTerminalsMenuGetsAnIcon() {
        final JPopupMenu menu = new JPopupMenu();
        Stream.of("Copy", "Paste", "Select All", "Clear Buffer", "Find", "Open as URL")
                .map(JMenuItem::new)
                .forEach(menu::add);
        menu.addSeparator();
        menu.add(new JMenuItem("Something new"));

        final List<JMenuItem> items = Stream.of(TerminalWidget.withIcons(menu).getComponents())
                .filter(JMenuItem.class::isInstance)
                .map(JMenuItem.class::cast)
                .toList();

        assertThat(items).hasSize(7).allSatisfy(i -> assertThat(i.getIcon()).as(i.getText()).isNotNull());
        assertThat(items.getFirst().getIcon()).as("Copy").isSameAs(Icons.get("copy"));
        assertThat(items.getLast().getIcon()).as("an item JediTerm adds later").isSameAs(Icons.get("terminal"));
    }
}
