package dev.marregui.comando.core.port;

import dev.marregui.comando.core.intent.EditMode;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.PlanUsage;
import dev.marregui.comando.core.view.Layout;
import dev.marregui.comando.core.view.ViewSpec;

/** What the reducer asks of the UI beyond rendering the state: the editor text it owns, notifications. */
public interface UiPort {

    void editPanel(PanelId panel, String text, EditMode mode);

    void notifyDesktop(String title, String text);

    void forgetPanel(PanelId panel);

    /** A panel was removed: its own file goes, a document's stays; its terminal ends. */
    void deletePanel(PanelId panel);

    /** Reads {@code path} (relative to {@code baseDir}) and shows it in the side pane at {@code line}. */
    void showFile(String session, String path, String baseDir, int line);

    /** Shows or hides panes and moves the split. */
    void arrange(Layout layout);

    /** Shows the scratchpad's tree in the sidebar's Scratchpad tab. */
    void showScratchpad(ViewSpec.TreeNode root);

    /** Shows the plan's usage limits in the status bar. */
    void showPlanUsage(PlanUsage usage);
}
