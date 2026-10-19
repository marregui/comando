package dev.marregui.comando.core.port;

import dev.marregui.comando.core.intent.EditMode;
import dev.marregui.comando.core.model.ChangeAsk;
import dev.marregui.comando.core.model.ChangeTexts;
import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.PlanUsage;
import dev.marregui.comando.core.model.RepoStatus;
import dev.marregui.comando.core.view.Layout;
import dev.marregui.comando.core.view.ViewSpec;

import java.util.List;

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

    /** Shows a folder's tree in the sidebar's Files tab; the folder as configured when it was read. */
    void showFolder(FolderRoot folder, ViewSpec.TreeNode tree);

    /** Shows the watched git repositories' changes in the Changes view and the Files tab. */
    void showRepos(List<RepoStatus> repos);

    /** Shows a changed file's two versions side by side, as {@code ask} says. */
    void showChange(ChangeTexts texts, ChangeAsk ask);

    /** Shows the plan's usage limits in the status bar. */
    void showPlanUsage(PlanUsage usage);
}
