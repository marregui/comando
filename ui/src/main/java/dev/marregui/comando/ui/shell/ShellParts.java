package dev.marregui.comando.ui.shell;

import javax.swing.JFrame;

import dev.marregui.comando.ui.panels.LowerPanel;
import dev.marregui.comando.ui.panels.SessionBar;
import dev.marregui.comando.ui.panels.SessionsSidebar;
import dev.marregui.comando.ui.panels.SidePane;
import dev.marregui.comando.ui.panels.StatusBar;
import dev.marregui.comando.ui.panels.Workspace;
import dev.marregui.comando.ui.widget.capability.FindBar;

/** The window's parts, built once on the EDT by the shell; what its collaborators act on. */
record ShellParts(
        JFrame frame,
        Workspace workspace,
        LowerPanel lower,
        SidePane side,
        SessionsSidebar sidebar,
        StatusBar status,
        SessionBar sessionBar,
        FindBar findBar,
        DiffWindow diffWindow,
        LivePlots livePlots) {
}
