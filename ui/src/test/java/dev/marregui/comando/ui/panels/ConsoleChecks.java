package dev.marregui.comando.ui.panels;

import java.awt.Component;
import java.awt.Rectangle;
import java.util.Arrays;
import java.util.List;
import javax.swing.JTabbedPane;

import dev.marregui.comando.ui.kit.theme.Ground;
import dev.marregui.comando.ui.kit.theme.NamedTheme;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.theme.Tint;

/// What the panels' tests of the console style (record 151) share: a theme chosen for a body and put back after it,
/// and a tabbed pane laid out at one size.
final class ConsoleChecks {

    private ConsoleChecks() {
    }

    /// Runs `body` under `theme`, then chooses the theme found again, so no other test inherits it. EDT only.
    static void inTheme(final NamedTheme theme, final Runnable body) {
        final Ground ground = Theme.groundChosen();
        final Tint tint = Theme.tint();
        try {
            Theme.choose(theme.ground, theme.tint);
            body.run();
        } finally {
            Theme.choose(ground, tint);
        }
    }

    /// The bounds of the children of `tabs` once it is laid out at 400 by 300.
    static List<Rectangle> laidOut(final JTabbedPane tabs) {
        tabs.setSize(400, 300);
        tabs.doLayout();
        return Arrays.stream(tabs.getComponents()).map(Component::getBounds).toList();
    }
}
