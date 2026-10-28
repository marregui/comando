package dev.marregui.comando.ui.panels;

import dev.marregui.comando.ui.kit.theme.Ground;
import dev.marregui.comando.ui.kit.theme.GroundIcon;
import dev.marregui.comando.ui.kit.theme.NamedTheme;

import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

/**
 * The swatch of the theme chosen, its ground with its tint's bar, at the top right beside New panel (record 104): its
 * tooltip names the theme, read when it shows; a click opens the six themes, also in the palette.
 */
public final class ThemeButton extends JButton {

    private Consumer<NamedTheme> chosen = t -> { };

    public ThemeButton() {
        super(new GroundIcon((Ground) null));
        putClientProperty("JButton.buttonType", "toolBarButton");
        setFocusable(false);
        getAccessibleContext().setAccessibleName("Choose the theme");
        // any text registers the button with the tooltip manager; the text shown is the theme's, read at each show by
        // getToolTipText(MouseEvent): overriding getToolTipText() instead hides the text set here, and the button never
        // registers (setToolTipText registers only when the text before was null)
        setToolTipText("Theme");
        addActionListener(e -> menu().show(this, 0, getHeight()));
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        return "Theme: " + NamedTheme.chosen().map(t -> t.label).orElse("none") + "; click for another";
    }

    /** {@code chosen} hears the theme picked from the menu. */
    public void onChosen(Consumer<NamedTheme> chosen) {
        this.chosen = chosen;
    }

    /** One entry per theme, each with its swatch, in the themes' order; the theme chosen is ticked. */
    JPopupMenu menu() {
        JPopupMenu menu = new JPopupMenu();
        ButtonGroup group = new ButtonGroup();
        NamedTheme now = NamedTheme.chosen().orElse(null);
        for (NamedTheme t : NamedTheme.values()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(t.label, new GroundIcon(t), t == now);
            item.addActionListener(e -> chosen.accept(t));
            group.add(item);
            menu.add(item);
        }
        return menu;
    }
}
