package dev.marregui.comando.ui.kit.action;

import javax.swing.JButton;

import dev.marregui.comando.ui.kit.icon.Icons;

/** A flat icon button for a bar above a list or a tab: its tooltip is its accessible name, and it takes no focus. */
public final class ToolButton {

    private ToolButton() {
    }

    public static JButton of(final String icon, final String tip, final Runnable r) {
        final JButton b = new JButton(Icons.get(icon));
        b.setToolTipText(tip);
        b.getAccessibleContext().setAccessibleName(tip);
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.setFocusable(false);
        b.addActionListener(e -> r.run());
        return b;
    }
}
