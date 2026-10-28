package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.RepoStatus;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.async.OnEdt;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.Component;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// [FilesTab]'s groups as rows (record 118): the brain and the kit, each one unit.
/// - a group of folders is one row in capitals, the folder holding them, with them inside it and its other folders, no
///   file and nothing hidden; a group of one is its folder's row, named in capitals
/// - a group's folders inside its row keep their icons, are watched when open, and their entries resolve to their paths
/// - only a top-level row shows its changes, on a double click or in its menu, and gains the window's items (the backup)
@ExtendWith(OnEdt.class)
class FilesTabGroupsTest {

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    private static final FolderRoot SCRATCHPAD = new FolderRoot("Scratchpad", "/b/SCRATCHPAD", "", List.of(), "scratchpad", true, "INDEX.md", "Brain");
    private static final FolderRoot KB = new FolderRoot("KB", "/b/KB", "00_START_HERE.md", List.of(), "knowledge", false, "", "Brain");
    private static final FolderRoot MEMORY = new FolderRoot("Memory", "/b/MEMORY", "MEMORY.md", List.of(), "memory", false, "", "Brain");
    private static final FolderRoot KIT = new FolderRoot("Kit", "/k", "", List.of("BRAIN"), "folder", false, "", "Kit");
    private static final FolderRoot BRAIN = new FolderRoot("BRAIN", "/b", "", List.of("SCRATCHPAD", "KB", "MEMORY"), "folder", false, "", "Brain");

    private final List<Intent> intents = new ArrayList<>();
    private final List<String> extras = new ArrayList<>();

    private FilesTab tab() {
        FilesTab tab = new FilesTab(intents::add, p -> { }, p -> { }, (parent, text, title, options) -> 1);
        tab.render(AppState.initial(List.of(new PanelState(new PanelId("p"), "scratch", "", "")), List.of(), List.of(),
                List.of(SCRATCHPAD, KB, MEMORY, KIT)));
        tab.onFolderMenu((f, menu) -> {
            extras.add(f.name());
            menu.add(new JMenuItem("backup " + f.name()));
        });
        tab.tree().component().setSize(260, 400);
        return tab;
    }

    private static ViewSpec.TreeNode file(String path) {
        return new ViewSpec.TreeNode(Path.of(path).getFileName().toString(), "", "comando-file:" + path + "#0", List.of());
    }

    private static ViewSpec.TreeNode folder(String name, ViewSpec.TreeNode... children) {
        return new ViewSpec.TreeNode(name, children.length + " entries", List.of(children));
    }

    private static List<String> rows(JTree tree) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < tree.getRowCount(); i++) {
            out.add(((ViewSpec.TreeNode) ((DefaultMutableTreeNode) tree.getPathForRow(i).getLastPathComponent()).getUserObject()).label());
        }
        return out;
    }

    private static int row(JTree tree, String label) {
        int i = rows(tree).indexOf(label);
        assertThat(i).as("a row " + label).isNotNegative();
        return i;
    }

    private static ViewSpec.TreeNode select(JTree tree, String label) {
        int i = row(tree, label);
        tree.setSelectionRow(i);
        return (ViewSpec.TreeNode) ((DefaultMutableTreeNode) tree.getPathForRow(i).getLastPathComponent()).getUserObject();
    }

    private static List<String> items(JPopupMenu menu) {
        List<String> out = new ArrayList<>();
        for (Component c : menu.getComponents()) {
            if (c instanceof JMenuItem m) {
                out.add(m.getText());
            }
        }
        return out;
    }

    private static void doubleClick(JTree tree, int row) {
        Rectangle r = tree.getRowBounds(row);
        tree.dispatchEvent(new MouseEvent(tree, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, r.x + 4, r.y + r.height / 2, 2, false,
                MouseEvent.BUTTON1));
    }

    /// The Brain group's three folders share `/b`: one BRAIN row for `/b` holds them, then its other folder EVAL, not its
    /// file or its hidden folder; the Kit group's one folder is the KIT row. Opening BRAIN reads it as a folder of its
    /// own, its group's folders left out; opening a folder inside reads that folder.
    @Test
    void aGroupIsOneRowInCapitalsHoldingItsFoldersAndItsOtherFolders() {
        assertThat(FolderRoot.groupRoots(List.of(SCRATCHPAD, KB, MEMORY, KIT))).containsExactly(BRAIN);
        FilesTab tab = tab();
        JTree tree = tab.tree().tree();
        assertThat(rows(tree)).containsExactly("BRAIN", "KIT");

        tree.expandRow(0);
        assertThat(rows(tree)).containsExactly("BRAIN", "Scratchpad", "KB", "Memory", "KIT");
        assertThat(intents).last().isEqualTo(new Intent.WatchFolders(List.of("BRAIN")));
        tab.show(BRAIN, folder("BRAIN", folder("EVAL", file("/b/EVAL/run.md")), folder(".cache", file("/b/.cache/x")), file("/b/README.md")));
        assertThat(rows(tree)).as("its other folders, no file, nothing hidden").containsExactly("BRAIN", "Scratchpad", "KB", "Memory", "EVAL", "KIT");

        tree.expandRow(row(tree, "Memory"));
        assertThat(intents).last().isEqualTo(new Intent.WatchFolders(List.of("Memory", "BRAIN")));
        assertThat(tab.rowIcon((ViewSpec.TreeNode) ((DefaultMutableTreeNode) tree.getPathForRow(row(tree, "Memory")).getLastPathComponent()).getUserObject()))
                .as("a group's folder keeps its icon").contains("folder-memory");
        assertThat(tab.header((DefaultMutableTreeNode) tree.getPathForRow(row(tree, "KIT")).getLastPathComponent())).as("a hairline before KIT").isEmpty();
        assertThat(tab.header((DefaultMutableTreeNode) tree.getPathForRow(row(tree, "Memory")).getLastPathComponent())).isNull();
    }

    /// The BRAIN and KIT rows show their repository's changes on a double click and in their menu, which gains the
    /// window's items; a folder inside BRAIN offers only its index, a subfolder nothing but the Trash in a deletable
    /// folder, which moves the right path.
    @Test
    void onlyATopRowShowsItsChangesAndGainsTheBackup() {
        FilesTab tab = tab();
        JTree tree = tab.tree().tree();
        tree.expandRow(0);
        tab.show(SCRATCHPAD, folder("Scratchpad", folder("2026-10-28", file("/b/SCRATCHPAD/2026-10-28/a.md")), file("/b/SCRATCHPAD/INDEX.md")));
        tab.showRepos(List.of(
                new RepoStatus("/b", "main", List.of(new RepoStatus.FileChange("MEMORY/a.md", "", RepoStatus.Kind.MODIFIED)), false, ""),
                new RepoStatus("/k", "master", List.of(new RepoStatus.FileChange("CLAUDE.md", "", RepoStatus.Kind.MODIFIED)), false, "")));

        assertThat(items(tab.menuFor(select(tree, "BRAIN")))).containsExactly("Show changes in BRAIN", "backup BRAIN");
        assertThat(items(tab.menuFor(select(tree, "KIT")))).containsExactly("Show changes in KIT", "backup Kit");
        assertThat(items(tab.menuFor(select(tree, "Memory")))).as("its index, no changes, no backup").containsExactly("Open MEMORY.md");
        assertThat(extras).containsExactly("BRAIN", "Kit");

        tree.expandRow(row(tree, "Scratchpad"));
        assertThat(items(tab.menuFor(select(tree, "2026-10-28")))).as("a subfolder: the Trash alone").containsExactly("Move 2026-10-28 to the Trash…");
        assertThat(tab.trashable()).hasValueSatisfying(t -> {
            assertThat(t.getKey()).isEqualTo(SCRATCHPAD);
            assertThat(t.getValue()).containsExactly(Path.of("/b/SCRATCHPAD/2026-10-28"));
        });

        intents.clear();
        doubleClick(tree, row(tree, "Memory"));
        assertThat(intents).as("a folder inside BRAIN shows no changes of its own").noneMatch(i -> i instanceof Intent.ShowView);
        doubleClick(tree, row(tree, "BRAIN"));
        assertThat(intents).contains(new Intent.ShowView("", ViewSpec.Changes.TITLE, new ViewSpec.Changes("/b", "", "BRAIN"), Placement.SIDE));
    }
}
