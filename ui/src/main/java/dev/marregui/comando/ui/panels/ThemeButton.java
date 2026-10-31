package dev.marregui.comando.ui.panels;

import java.awt.event.MouseEvent;
import java.util.function.Consumer;
import javax.swing.JButton;

import dev.marregui.comando.ui.kit.theme.GildedSplitIcon;
import dev.marregui.comando.ui.kit.theme.NamedTheme;

/**
 * The theme button at the top right beside New panel (record 104), its icon a gilded square split between the ground
 * chosen and its twin's (record 128): its tooltip names the theme, read when it shows; a click opens the theme chooser
 * under it, and closes it when open; the palette lists the themes too.
 */
public final class ThemeButton extends JButton {

    private Consumer<NamedTheme> chosen = t -> { };
    /** The chooser last opened from here, if any. */
    private ThemeChooser open;

    public ThemeButton() {
        super(new GildedSplitIcon());
        putClientProperty("JButton.buttonType", "toolBarButton");
        setFocusable(false);
        getAccessibleContext().setAccessibleName("Choose the theme");
        // any text registers the button with the tooltip manager; the text shown is the theme's, read at each show by
        // getToolTipText(MouseEvent): overriding getToolTipText() instead hides the text set here, and the button never
        // registers (setToolTipText registers only when the text before was null)
        setToolTipText("Theme");
        addActionListener(e -> {
            // the swatch opens the chooser and, open, closes it; the click that closed it by taking its focus opens nothing
            if (open != null && open.open()) {
                open.close();
            } else if (!ThemeChooser.justClosed()) {
                open = ThemeChooser.show(this, t -> chosen.accept(t));
            }
        });
    }

    @Override
    public String getToolTipText(final MouseEvent event) {
        return "Theme: " + NamedTheme.chosen().map(t -> t.label).orElse("none") + "; click for another";
    }

    /** {@code chosen} hears the theme picked in the chooser. */
    public void onChosen(final Consumer<NamedTheme> chosen) {
        this.chosen = chosen;
    }
}
