package dev.marregui.comando.ui.panels;

import javax.swing.JTabbedPane;

import dev.marregui.comando.ui.kit.format.KeyText;

/**
 * The look the workspace's two strips share, the main tabs and the right-hand partition's (record 141): card tabs,
 * one scroll button when they overflow, a close button on each panel's tab. A tab whose content sets
 * {@link #CLOSABLE} false (a view, whose header has its own) shows none.
 */
final class TabLook {

    static final String CLOSABLE = "JTabbedPane.tabClosable";
    static final String CLOSE_CALLBACK = "JTabbedPane.tabCloseCallback";
    /** The close button's tooltip: the strip's, unless a tab's content sets its own. */
    static final String CLOSE_TIP = "JTabbedPane.tabCloseToolTipText";

    private TabLook() {
    }

    static void apply(final JTabbedPane tabs) {
        tabs.putClientProperty("JTabbedPane.tabType", "card");
        tabs.putClientProperty("JTabbedPane.scrollButtonsPolicy", "asNeededSingle");
        tabs.putClientProperty(CLOSABLE, true);
        tabs.putClientProperty(CLOSE_TIP, KeyText.of("Close the panel (⌘W; ⌘⇧T reopens it)"));
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
    }
}
