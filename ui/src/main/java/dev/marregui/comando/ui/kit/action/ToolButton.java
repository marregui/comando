package dev.marregui.comando.ui.kit.action;

import dev.marregui.comando.ui.kit.icon.Icons;

import javax.swing.JButton;

/** A flat icon button for a bar above a list or a tab: its tooltip is its accessible name, and it takes no focus. */
public final class ToolButton {

    private ToolButton() {
    }

    public static JButton of(String icon, String tip, Runnable r) {
        JButton b = new JButton(Icons.get(icon));
        b.setToolTipText(tip);
        b.getAccessibleContext().setAccessibleName(tip);
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.setFocusable(false);
        b.addActionListener(e -> r.run());
        return b;
    }
}
