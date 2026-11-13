package dev.marregui.comando.ui.panels;

import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.RepoStatus;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.async.OnEdt;

import static org.assertj.core.api.Assertions.assertThat;

/// [FilesTab]'s groups as rows (record 118): the brain and the kit, each one unit.
/// - a group of folders is one row in capitals, the folder holding them, with only them inside it (record 152); a
///   group of one is its folder's row, named in capitals
/// - a group's folders inside its row keep their icons, are watched when open, and their entries resolve to their paths
/// - only a top-level row shows its changes, on a double click or in its menu, and gains the window's items (the
///   backup)
/// - a group's row has no icon and names its repository's commit, the kit's newer release after it (record 119)
/// - reveal opens every row and folder on a file's way, waits for a folder's read, selects the file; says so when no
///   folder shows it
/// - the Kit, Brain and Scratchpad tabs each hold their shelf's folders, share one watch, and say how closely they hold
///   a file (record 152)
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
        final FilesTab tab = new FilesTab(intents::add, p -> { }, p -> { }, (parent, text, title, options) -> 1);
        tab.render(AppState.initial(List.of(new PanelState(new PanelId("p"), "scratch", "", "")), List.of(), List.of(),
                List.of(SCRATCHPAD, KB, MEMORY, KIT)));
        tab.onFolderMenu((f, menu) -> {
            extras.add(f.name());
            menu.add(new JMenuItem("backup " + f.name()));
        });
        tab.tree().component().setSize(260, 400);
        return tab;
    }

    /// The Kit tab holds KIT, the Brain tab BRAIN with KB and Memory, the Scratchpad tab the scratchpad as a row of its
    /// own named SCRATCHPAD; the open folders of the tabs that show are watched as one union, a tab no longer shown
    /// takes its share out; each tab holds a file as closely as its nearest folder, -1 for none.
    @Test
    void theThreeShelvesSplitTheFoldersAndShareOneWatch() {
        final FolderWatch watch = new FolderWatch(intents::add);
        final List<FilesTab> tabs = new ArrayList<>();
        for (final FilesShelf shelf : FilesShelf.values()) {
            final FilesTab t = new FilesTab(intents::add, shelf, watch.part(), _ -> { }, _ -> { }, (_, _, _, _) -> 1);
            t.render(AppState.initial(List.of(new PanelState(new PanelId("p"), "scratch", "", "")), List.of(),
                    List.of(), List.of(SCRATCHPAD, KB, MEMORY, KIT)));
            tabs.add(t);
        }
        final JTree kit = tabs.get(0).tree().tree();
        final JTree brain = tabs.get(1).tree().tree();
        final JTree pad = tabs.get(2).tree().tree();
        assertThat(rows(kit)).containsExactly("KIT");
        brain.expandRow(0);
        assertThat(rows(brain)).as("the brain without its scratchpad").containsExactly("BRAIN", "KB", "Memory");
        assertThat(rows(pad)).as("a row of its own, named as KIT and BRAIN are").containsExactly("SCRATCHPAD");

        intents.clear();
        brain.expandRow(row(brain, "Memory"));
        pad.expandRow(0);
        assertThat(intents).last().isEqualTo(new Intent.WatchFolders(List.of("Memory", "Scratchpad"),
                Map.of("Memory", List.of(), "Scratchpad", List.of())));
        tabs.get(1).attention(false);
        assertThat(intents).as("a tab that no longer shows takes its folders out").last()
                .isEqualTo(new Intent.WatchFolders(List.of("Scratchpad"), Map.of("Scratchpad", List.of())));

        final Path note = Path.of("/b/SCRATCHPAD/2026-10-28/a.md");
        assertThat(tabs.stream().map(t -> t.holds(note)).toList()).as("only the Scratchpad tab holds a scratchpad file")
                .containsExactly(-1, -1, 2);
        assertThat(tabs.get(1).holds(Path.of("/b/MEMORY/MEMORY.md"))).isEqualTo(2);
        assertThat(tabs.get(0).holds(Path.of("/k/BRAIN/MEMORY/MEMORY.md"))).as("the kit leaves BRAIN out")
                .isEqualTo(-1);
    }

    private static ViewSpec.TreeNode file(final String path) {
        return new ViewSpec.TreeNode(Path.of(path).getFileName().toString(), "", "comando-file:" + path + "#0", List.of());
    }

    private static ViewSpec.TreeNode folder(final String name, final ViewSpec.TreeNode... children) {
        return new ViewSpec.TreeNode(name, children.length + " entries", List.of(children));
    }

    private static List<String> rows(final JTree tree) {
        final List<String> out = new ArrayList<>();
        for (int i = 0; i < tree.getRowCount(); i++) {
            out.add(((ViewSpec.TreeNode) ((DefaultMutableTreeNode) tree.getPathForRow(i).getLastPathComponent()).getUserObject()).label());
        }
        return out;
    }

    private static int row(final JTree tree, final String label) {
        final int i = rows(tree).indexOf(label);
        assertThat(i).as("a row " + label).isNotNegative();
        return i;
    }

    private static ViewSpec.TreeNode select(final JTree tree, final String label) {
        final int i = row(tree, label);
        tree.setSelectionRow(i);
        return (ViewSpec.TreeNode) ((DefaultMutableTreeNode) tree.getPathForRow(i).getLastPathComponent()).getUserObject();
    }

    private static List<String> items(final JPopupMenu menu) {
        final List<String> out = new ArrayList<>();
        for (final Component c : menu.getComponents()) {
            if (c instanceof JMenuItem m) {
                out.add(m.getText());
            }
        }
        return out;
    }

    private static void doubleClick(final JTree tree, final int row) {
        final Rectangle r = tree.getRowBounds(row);
        tree.dispatchEvent(new MouseEvent(tree, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, r.x + 4, r.y + r.height / 2, 2, false,
                MouseEvent.BUTTON1));
    }

    /// The Brain group's three folders share `/b`: one BRAIN row for `/b` holds them and nothing else, so a folder of
    /// `/b` configured for no group (EVAL) never shows (record 152); the Kit group's one folder is the KIT row. Opening
    /// BRAIN reads nothing, its folders show what they hold; opening a folder inside reads that folder.
    @Test
    void aGroupIsOneRowInCapitalsHoldingOnlyItsFolders() {
        assertThat(FolderRoot.groupRoots(List.of(SCRATCHPAD, KB, MEMORY, KIT))).containsExactly(BRAIN);
        final FilesTab tab = tab();
        final JTree tree = tab.tree().tree();
        assertThat(rows(tree)).containsExactly("BRAIN", "KIT");

        intents.clear();
        tree.expandRow(0);
        assertThat(rows(tree)).containsExactly("BRAIN", "Scratchpad", "KB", "Memory", "KIT");
        assertThat(intents).as("BRAIN itself is not read")
                .noneMatch(i -> i instanceof Intent.WatchFolders w && w.names().contains("BRAIN"));
        tab.show(BRAIN, folder("BRAIN", folder("EVAL", file("/b/EVAL/run.md")), folder(".cache", file("/b/.cache/x")), file("/b/README.md")));
        assertThat(rows(tree)).as("its configured folders alone, EVAL not")
                .containsExactly("BRAIN", "Scratchpad", "KB", "Memory", "KIT");

        tree.expandRow(row(tree, "Memory"));
        assertThat(intents).last().isEqualTo(new Intent.WatchFolders(List.of("Memory"), Map.of("Memory", List.of())));
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
        final FilesTab tab = tab();
        final JTree tree = tab.tree().tree();
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

    /// BRAIN and KIT are drawn with no icon and say their repository's short commit; KIT adds the kit's newer release;
    /// their tooltip says where they are. A folder inside BRAIN keeps its icon.
    @Test
    void aGroupsRowHasNoIconAndNamesItsCommit() {
        final FilesTab tab = tab();
        final JTree tree = tab.tree().tree();
        tree.expandRow(0);
        tab.showRepos(List.of(new RepoStatus("/b", "main", List.of(), false, "", "737f602aa11bb22cc33dd44ee55ff6677889900a"),
                new RepoStatus("/k", "master", List.of(), false, "", "c312e98ff00ee11dd22cc33bb44aa5566778899b")));
        tab.kitRelease(Path.of("/k"), "v2.19.0");
        assertThat(detail(tree, "BRAIN")).isEqualTo("737f602");
        assertThat(detail(tree, "KIT")).isEqualTo("c312e98 · v2.19.0 available");
        assertThat(icon(tree, "BRAIN")).as("no icon, not even the look's").isNull();
        assertThat(icon(tree, "KIT")).isNull();
        assertThat(icon(tree, "Memory")).isNotNull();
        assertThat(tree.getToolTipText(at(tree, "BRAIN"))).startsWith("/b · On main");
        tab.kitRelease(Path.of("/k"), "");
        assertThat(detail(tree, "KIT")).isEqualTo("c312e98");
    }

    /// Revealing a file in a closed, unread folder inside BRAIN opens BRAIN and the folder, waits for its read, then
    /// opens the subfolder and selects the file; a file in no folder of the tab is said beside the tree.
    @Test
    void revealOpensTheWayAndSelectsTheFile() {
        final List<String> told = new ArrayList<>();
        final FilesTab tab = tab();
        tab.tell((owner, at, text) -> told.add(text));
        final JTree tree = tab.tree().tree();
        tab.reveal(Path.of("/b/MEMORY/topic/notes.md"));
        assertThat(rows(tree)).as("opened as far as read").containsExactly("BRAIN", "Scratchpad", "KB", "Memory", FilesTab.READING, "KIT");
        assertThat(intents).last().isEqualTo(new Intent.WatchFolders(List.of("Memory"), Map.of("Memory", List.of())));
        tab.show(MEMORY, folder("Memory", folder("topic", file("/b/MEMORY/topic/a.md"), file("/b/MEMORY/topic/notes.md")), file("/b/MEMORY/MEMORY.md")));
        assertThat(tree.getSelectionPath()).isNotNull();
        assertThat(((ViewSpec.TreeNode) ((DefaultMutableTreeNode) tree.getSelectionPath().getLastPathComponent()).getUserObject()).label())
                .isEqualTo("notes.md");
        assertThat(tree.isExpanded(tree.getSelectionPath().getParentPath())).isTrue();

        tree.collapseRow(0);
        tab.reveal(Path.of("/b/MEMORY/MEMORY.md"));
        assertThat(((ViewSpec.TreeNode) ((DefaultMutableTreeNode) tree.getSelectionPath().getLastPathComponent()).getUserObject()).label())
                .as("a row the user closed is opened again").isEqualTo("MEMORY.md");

        tab.reveal(Path.of("/elsewhere/x.md"));
        assertThat(told).containsExactly("Not in a folder the sidebar shows: /elsewhere/x.md");
    }

    private static MouseEvent at(final JTree tree, final String label) {
        final Rectangle r = tree.getRowBounds(row(tree, label));
        return new MouseEvent(tree, MouseEvent.MOUSE_MOVED, 0, 0, r.x + 4, r.y + r.height / 2, 0, false);
    }

    private static Component cell(final JTree tree, final String label) {
        final int i = row(tree, label);
        return tree.getCellRenderer().getTreeCellRendererComponent(tree, tree.getPathForRow(i).getLastPathComponent(), false, false, false, i, false);
    }

    private static String detail(final JTree tree, final String label) {
        return ((ViewSpec.TreeNode) ((DefaultMutableTreeNode) tree.getPathForRow(row(tree, label)).getLastPathComponent()).getUserObject()).detail();
    }

    /** The icon of the row's name: the first label in its cell, under a separator's space when it has one. */
    private static Icon icon(final JTree tree, final String label) {
        return firstLabel(cell(tree, label)).getIcon();
    }

    private static JLabel firstLabel(final Component c) {
        if (c instanceof JLabel l) {
            return l;
        }
        for (final Component child : ((Container) c).getComponents()) {
            if (child instanceof Container k && (k instanceof JLabel || k.getComponentCount() > 0)) {
                final JLabel found = firstLabel(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
