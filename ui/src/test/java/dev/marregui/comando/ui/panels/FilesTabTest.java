package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.ChangeAsk;
import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.RepoStatus;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.icon.RowIcon;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.vcs.OpenedFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.AbstractButton;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JScrollBar;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class FilesTabTest {

    private static ViewSpec.TreeNode file(String name) {
        return new ViewSpec.TreeNode(name, "", "comando-file:/s/" + name + "#0", List.of());
    }

    private static ViewSpec.TreeNode folder(String name, ViewSpec.TreeNode... children) {
        return new ViewSpec.TreeNode(name, children.length + " entries", List.of(children));
    }

    @Test
    void eachKindOfEntryHasItsIcon() {
        Set<String> none = Set.of();
        assertThat(FilesTab.iconName(folder("2026-10-14"), none)).isEqualTo("folder");
        assertThat(FilesTab.iconName(folder("work"), none)).isEqualTo("folder-work");
        assertThat(FilesTab.iconName(file("LANE-PLAN.md"), none)).isEqualTo("plan");
        assertThat(FilesTab.iconName(file("READ-01-X-OCT-14.md"), none)).isEqualTo("read");
        assertThat(FilesTab.iconName(file("ALPHA.md"), Set.of("alpha.md"))).as("a folder's index").isEqualTo("index");
        assertThat(FilesTab.iconName(file("ALPHA.md"), none)).as("not an index anywhere").isEqualTo("markdown");
        assertThat(FilesTab.iconName(file("notes.md"), none)).isEqualTo("markdown");
        assertThat(FilesTab.iconName(file("Red.java"), none)).isEqualTo("code");
        assertThat(FilesTab.iconName(file("codex.json"), none)).isEqualTo("json");
        assertThat(FilesTab.iconName(file("run.sh"), none)).isEqualTo("terminal");
        assertThat(FilesTab.iconName(file("q.sql"), none)).isEqualTo("database");
        assertThat(FilesTab.iconName(file("shot.png"), none)).isEqualTo("image");
        assertThat(FilesTab.iconName(file("cpu.jfr"), none)).as("a recording is a file like any other").isEqualTo("file");
        assertThat(FilesTab.iconName(file("fix.patch"), none)).isEqualTo("diff");
        assertThat(FilesTab.iconName(file("gradle.log"), none)).isEqualTo("text");
        assertThat(FilesTab.iconName(file("blob"), none)).isEqualTo("file");
        for (String entry : List.of(FilesTab.READING, FilesTab.EMPTY, FilesTab.NO_MATCH, "✗ cannot be read: no such folder")) {
            assertThat(FilesTab.iconName(new ViewSpec.TreeNode(entry, "", List.of()), none)).as(entry).isEmpty();
        }
        assertThat(FilesTab.iconName(new ViewSpec.TreeNode(ViewSpec.TreeNode.MORE, "more entries than the tab reads", List.of()), none)).isEmpty();
    }

    @Test
    void theFilterKeepsMatchesWithTheirEntriesAndTheFoldersLeadingToThem() {
        ViewSpec.TreeNode plan = file("PLAN.md");
        ViewSpec.TreeNode work = folder("work", file("plan-notes.txt"), file("run.sh"));
        List<ViewSpec.TreeNode> tree = List.of(folder("2026-10-14", plan, file("ALPHA.md")), work, folder("empty"));

        List<ViewSpec.TreeNode> found = FilesTab.matches(tree, "plan");
        assertThat(found).extracting(ViewSpec.TreeNode::label).containsExactly("2026-10-14", "work");
        assertThat(found.getFirst().children()).containsExactly(plan);
        assertThat(found.get(1).children()).extracting(ViewSpec.TreeNode::label).containsExactly("plan-notes.txt");
        assertThat(FilesTab.matches(tree, "work")).as("a matching folder keeps every entry").containsExactly(work);
        assertThat(FilesTab.matches(tree, "zzz")).isEmpty();
    }

    @Test
    void openingAFolderWatchesItAndOpensNoFileAndARestoredRowChangesNothing(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("ALPHA.md"), "# index");
        Fixture f = new Fixture(new FolderRoot("KB", dir.toString(), "ALPHA.md", List.of()), new FolderRoot("Notes", "/n", "", List.of()));
        assertThat(f.intents).as("none open: the adapter watches none already").isEmpty();
        assertThat(f.labels()).containsExactly("KB", "Notes");

        f.edt(() -> f.tree().expandRow(0));
        assertThat(f.labels()).as("an unread folder says so until its tree arrives").containsExactly("KB", FilesTab.READING, "Notes");
        assertThat(f.opened).as("opening a folder opens no panel, its index neither").isEmpty();
        assertThat(f.intents).last().isEqualTo(new Intent.WatchFolders(List.of("KB")));

        f.intents.clear();
        f.opened.clear();
        f.edt(() -> f.show("KB", folder("KB", folder("2026-10-15", file("a.md")), folder("2026-10-16", file("b.md")), file("ALPHA.md"))));
        assertThat(f.labels()).as("the newest day opens at the first read")
                .containsExactly("KB", "2026-10-15", "2026-10-16", "b.md", "ALPHA.md", "Notes");
        f.edt(() -> f.show("KB", folder("KB", folder("2026-10-15", file("a.md")), folder("2026-10-16", file("b.md"), file("c.md")), file("ALPHA.md"))));
        assertThat(f.labels()).as("a later read keeps the tree as the user left it")
                .containsExactly("KB", "2026-10-15", "2026-10-16", "b.md", "c.md", "ALPHA.md", "Notes");
        assertThat(f.intents).as("rows restored by a read do not change what is watched").isEmpty();

        f.edt(() -> f.show("Gone", folder("Gone")));
        assertThat(f.labels()).as("a folder no longer configured is ignored").doesNotContain("Gone");

        f.edt(() -> f.tree().collapseRow(0));
        assertThat(f.intents).containsExactly(new Intent.WatchFolders(List.of()));

        f.edt(() -> f.tree().expandRow(1));
        assertThat(f.labels()).as("unread until its tree arrives").containsExactly("KB", "Notes", FilesTab.READING);
        f.edt(() -> f.show("Notes", new ViewSpec.TreeNode("Notes", "/n", List.of())));
        assertThat(f.labels()).as("an empty folder says so").containsExactly("KB", "Notes", FilesTab.EMPTY);
    }

    @Test
    void aFolderEditedWhileOpenIsWatchedAgainAndReadWithItsNewSettings() throws Exception {
        FolderRoot kb = new FolderRoot("KB", "/kb", "", List.of());
        Fixture f = new Fixture(kb, new FolderRoot("Notes", "/n", "", List.of()));
        f.edt(() -> f.tree().expandRow(0));
        f.edt(() -> f.show("KB", folder("KB", file("a.md"), folder("tools", file("x.py")))));
        f.intents.clear();

        f.render(new FolderRoot("KB", "/kb", "", List.of("tools")), new FolderRoot("Notes", "/n", "", List.of()));
        assertThat(f.intents).as("same names, new settings: watched again so the adapter reads it at once")
                .containsExactly(new Intent.WatchFolders(List.of("KB")));
        assertThat(f.labels()).as("its old tree is dropped").containsExactly("KB", FilesTab.READING, "Notes");

        f.intents.clear();
        f.render(new FolderRoot("Notes", "/n", "", List.of()));
        assertThat(f.labels()).containsExactly("Notes");
        assertThat(f.intents).containsExactly(new Intent.WatchFolders(List.of()));

        f.render();
        assertThat(f.labels()).containsExactly("No folders yet: right-click the Files tab to add some");
    }

    @Test
    void theFilterSearchesEveryFolderAndClearingItBringsBackTheTreeAsLeft() throws Exception {
        Fixture f = new Fixture(new FolderRoot("Scratchpad", "/s", "", List.of()), new FolderRoot("KB", "/kb", "", List.of()));
        f.edt(() -> f.tree().expandRow(0));
        f.edt(() -> f.show("Scratchpad", folder("Scratchpad", folder("2026-10-16", file("LANE-PLAN.md")), file("notes.md"))));
        f.intents.clear();

        f.edt(() -> f.tab.filter().setText("plan"));
        assertThat(f.intents).as("the folders never read are read once, so the filter searches them too")
                .containsExactly(new Intent.BrowseFolders(List.of("KB")));
        assertThat(f.foundLabels()).containsExactly("Scratchpad", "2026-10-16", "LANE-PLAN.md");

        f.edt(() -> f.show("KB", folder("KB", folder("plans", file("q4.md")), file("design.md"))));
        assertThat(f.foundLabels()).as("a matching folder stays closed").containsExactly("Scratchpad", "2026-10-16", "LANE-PLAN.md", "KB", "plans");
        assertThat(f.labels()).as("the tree behind keeps KB closed").containsExactly("Scratchpad", "2026-10-16", "LANE-PLAN.md", "notes.md", "KB");

        f.intents.clear();
        f.edt(() -> f.tab.filter().setText("plann"));
        assertThat(f.intents).as("every folder read: nothing more to read").isEmpty();
        f.edt(() -> f.tab.filter().setText("zzz"));
        assertThat(f.foundLabels()).containsExactly(FilesTab.NO_MATCH);

        f.edt(() -> f.tab.filter().setText(""));
        assertThat(f.tab.tree().component().isVisible()).isTrue();
        assertThat(f.tab.found().component().isVisible()).isFalse();
        assertThat(f.labels()).containsExactly("Scratchpad", "2026-10-16", "LANE-PLAN.md", "notes.md", "KB");
    }

    @Test
    void theTabHasNoButtonsAndItsMenusOnlyOpen() throws Exception {
        Fixture f = new Fixture(new FolderRoot("Memory", "/m", "MEMORY.md", List.of(), "memory"),
                new FolderRoot("KB", "/kb", "", List.of(), "knowledge"));
        List<AbstractButton> buttons = new ArrayList<>();
        f.edt(() -> Fixture.collect(f.tab.component(), buttons));
        assertThat(buttons).as("the tab writes nothing: no add, edit, clone or remove").isEmpty();

        f.edt(() -> f.show("Memory", folder("Memory", file("MEMORY.md"))));
        f.edt(() -> f.tree().expandRow(0));
        f.edt(() -> f.tree().setSelectionRow(1));
        f.edt(() -> {
            JPopupMenu menu = f.tab.menuFor(file("MEMORY.md"));
            assertThat(menu.getComponents()).extracting(c -> ((JMenuItem) c).getText()).containsExactly("Open in a panel", "Edit in a panel");
            ((JMenuItem) menu.getComponent(0)).doClick();
            ((JMenuItem) menu.getComponent(1)).doClick();
        });
        assertThat(f.opened).containsExactly(Path.of("/s/MEMORY.md"));
        assertThat(f.editedFiles).containsExactly(Path.of("/s/MEMORY.md"));

        f.edt(() -> f.tree().collapseRow(0));
        f.edt(() -> f.tree().setSelectionRow(0));
        f.edt(() -> assertThat(f.tab.menuFor(new ViewSpec.TreeNode("Memory", "/m", List.of())).getComponents())
                .extracting(c -> ((JMenuItem) c).getText()).containsExactly("Open MEMORY.md"));
        f.edt(() -> f.tree().setSelectionRow(1));
        f.edt(() -> assertThat(items(f.tab.menuFor(new ViewSpec.TreeNode("KB", "/kb", List.of()))))
                .as("a folder without an index, in no repository: nothing").isEmpty());

        assertThat(f.tab.rowIcon(new ViewSpec.TreeNode("Memory", "/m", List.of()))).contains("folder-memory");
        assertThat(f.tab.rowIcon(new ViewSpec.TreeNode("KB", "/kb", List.of()))).contains("folder-knowledge");
        assertThat(f.tab.rowIcon(folder("Memory"))).as("a subfolder named as a folder").isEmpty();
    }

    @Test
    void aDeletableFoldersSelectionGoesToTheTrashAfterAskingAndNoOtherFolderOffersIt() throws Exception {
        Fixture f = new Fixture(new FolderRoot("Scratchpad", "/s", "", List.of(), "scratchpad").withTrash("ALPHA.md"),
                new FolderRoot("KB", "/kb", "", List.of()));
        f.edt(() -> f.tree().expandRow(0));
        f.edt(() -> f.show("Scratchpad", folder("Scratchpad", folder("2026-10-18", file("ALPHA.md"), file("READ-01.md"), folder("work", file("a.py"))))));
        f.edt(() -> f.show("KB", folder("KB", file("design.md"))));
        assertThat(f.labels()).containsExactly("Scratchpad", "2026-10-18", "ALPHA.md", "READ-01.md", "work", "KB");

        f.edt(() -> f.tree().setSelectionRows(new int[]{3, 4}));
        f.edt(() -> {
            JPopupMenu menu = f.tab.menuFor(file("READ-01.md"));
            assertThat(items(menu)).containsExactly("Open in a panel", "Edit in a panel", "Move 2 entries to the Trash…");
            f.answer = 1;
            item(menu, "Move 2 entries to the Trash…").doClick();
        });
        assertThat(f.asked).hasSize(1);
        assertThat(f.asked.getFirst()).contains("Move 2 entries (READ-01.md, 2026-10-18/work) to the Trash?", "Their own lines in ALPHA.md are taken out.");
        assertThat(f.intents).as("cancelled: nothing moves").noneMatch(Intent.DeleteFiles.class::isInstance);

        f.edt(() -> {
            f.answer = 0;
            item(f.tab.menuFor(folder("work", file("a.py"))), "Move 2 entries to the Trash…").doClick();
        });
        assertThat(f.intents).filteredOn(Intent.DeleteFiles.class::isInstance)
                .containsExactly(new Intent.DeleteFiles("Scratchpad", List.of("/s/READ-01.md", "/s/2026-10-18/work")));

        f.edt(() -> f.tree().setSelectionRow(4));
        f.edt(() -> assertThat(items(f.tab.menuFor(folder("work", file("a.py"))))).as("a subfolder alone").containsExactly("Move work to the Trash…"));
        f.edt(() -> f.tree().setSelectionRow(0));
        f.edt(() -> assertThat(items(f.tab.menuFor(new ViewSpec.TreeNode("Scratchpad", "/s", List.of()))))
                .as("the folder's own row never goes").isEmpty());
        f.edt(() -> f.tree().setSelectionRows(new int[]{3, 5}));
        f.edt(() -> assertThat(items(f.tab.menuFor(file("READ-01.md")))).as("a selection reaching another folder").containsExactly("Open in a panel", "Edit in a panel"));
        f.edt(() -> f.tree().expandRow(5));
        f.edt(() -> f.tree().setSelectionRow(6));
        f.edt(() -> assertThat(items(f.tab.menuFor(file("design.md")))).as("a folder that keeps its files").containsExactly("Open in a panel", "Edit in a panel"));
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

    private static JMenuItem item(JPopupMenu menu, String text) {
        for (Component c : menu.getComponents()) {
            if (c instanceof JMenuItem m && m.getText().equals(text)) {
                return m;
            }
        }
        throw new AssertionError("no item " + text);
    }

    @Test
    void whileFilteringTheMenuActsOnTheEntryPickedInTheFiltersTree() throws Exception {
        Fixture f = new Fixture(new FolderRoot("Scratchpad", "/s", "", List.of()), new FolderRoot("KB", "/kb", "ALPHA.md", List.of()));
        f.edt(() -> f.show("Scratchpad", folder("Scratchpad", file("notes.md"))));
        f.edt(() -> f.show("KB", folder("KB", file("notes.md"))));
        f.edt(() -> f.tree().setSelectionRow(0));
        f.edt(() -> f.tab.filter().setText("notes"));
        f.edt(() -> f.tab.found().tree().setSelectionRow(2));
        assertThat(f.foundLabels().get(2)).isEqualTo("KB");
        f.edt(() -> assertThat(f.tab.menuFor(new ViewSpec.TreeNode("KB", "/kb", List.of())).getComponents())
                .extracting(c -> ((JMenuItem) c).getText()).containsExactly("Open ALPHA.md"));
        f.edt(() -> f.tab.found().tree().setSelectionRow(3));
        f.edt(() -> {
            assertThat(f.tab.menuFor(file("notes.md")).getComponents()).extracting(c -> ((JMenuItem) c).getText())
                    .containsExactly("Open in a panel", "Edit in a panel");
            assertThat(f.tab.menuFor(new ViewSpec.TreeNode("KB", "/kb", List.of())).getComponentCount()).as("a file selected: no folder menu").isZero();
        });
    }

    @Test
    void everyIconAFolderMayHaveIsDrawn() {
        for (String icon : FolderRoot.ICONS) {
            String name = FilesTab.rowIconName(new FolderRoot("F", "/f", "", List.of(), icon));
            assertThat(FilesTab.class.getResource("/icons/" + name + ".svg")).as(name).isNotNull();
        }
    }

    @Test
    void whileFilteringAReadKeepsWhatTheUserOpenedAndEachUnreadFolderIsAskedForOnce() throws Exception {
        Fixture f = new Fixture(new FolderRoot("Scratchpad", "/s", "", List.of()), new FolderRoot("KB", "/kb", "", List.of()));
        f.edt(() -> f.show("Scratchpad", folder("Scratchpad", folder("plans", file("a-plan.md"), file("b-plan.md")), file("plan.md"))));
        f.intents.clear();
        f.edt(() -> f.tab.filter().setText("p"));
        f.edt(() -> f.tab.filter().setText("pl"));
        f.edt(() -> f.tab.filter().setText("pla"));
        assertThat(f.intents).as("asked once, not at every keystroke").containsExactly(new Intent.BrowseFolders(List.of("KB")));
        assertThat(f.foundLabels()).containsExactly("Scratchpad", "plans", "plan.md");

        f.edt(() -> f.tab.found().tree().expandRow(1));
        f.edt(() -> f.tab.found().tree().setSelectionRow(2));
        f.edt(() -> f.show("KB", folder("KB", folder("2026", file("q4-plan.md")))));
        assertThat(f.foundLabels()).as("the folder opened by the user stays open; the new folder opens to its matches")
                .containsExactly("Scratchpad", "plans", "a-plan.md", "b-plan.md", "plan.md", "KB", "2026", "q4-plan.md");
        assertThat(f.selectedFound()).isEqualTo("a-plan.md");
        f.edt(() -> f.show("Scratchpad", folder("Scratchpad", folder("plans", file("a-plan.md"), file("b-plan.md")), file("plan.md"))));
        assertThat(f.selectedFound()).isEqualTo("a-plan.md");
    }

    @Test
    void aFolderRenamedWhileOpenStaysOpenWithItsTree() throws Exception {
        Fixture f = new Fixture(new FolderRoot("KB", "/kb", "", List.of()), new FolderRoot("Notes", "/n", "", List.of()));
        f.edt(() -> f.tree().expandRow(0));
        f.edt(() -> f.show("KB", folder("KB", file("a.md"))));
        f.intents.clear();
        f.render(new FolderRoot("Base", "/kb", "", List.of()), new FolderRoot("Notes", "/n", "", List.of()));
        assertThat(f.labels()).containsExactly("Base", "a.md", "Notes");
        assertThat(f.intents).containsExactly(new Intent.WatchFolders(List.of("Base")));
    }

    @Test
    void aChangedFileAndTheFoldersHoldingItTakeGitsColours() throws Exception {
        Fixture f = new Fixture(new FolderRoot("Repo", "/s", "", List.of()));
        f.edt(() -> f.tree().expandRow(0));
        f.edt(() -> f.show("Repo", folder("Repo", folder("src", new ViewSpec.TreeNode("A.java", "", "comando-file:/s/src/A.java#0", List.of())),
                file("new.md"), file("same.md"))));
        f.edt(() -> f.tree().expandRow(1));
        assertThat(f.labels()).containsExactly("Repo", "src", "A.java", "new.md", "same.md");
        Color[] before = new Color[1];
        f.edt(() -> before[0] = f.tab.colorOf(node(f.tree(), 2)));
        assertThat(before[0]).as("no repository read yet").isNull();
        f.edt(() -> f.tab.showRepos(List.of(new RepoStatus("/s", "main", List.of(
                new RepoStatus.FileChange("src/A.java", "", RepoStatus.Kind.MODIFIED),
                new RepoStatus.FileChange("new.md", "", RepoStatus.Kind.UNTRACKED)), false, ""))));
        Color[] colors = new Color[5];
        f.edt(() -> {
            for (int i = 0; i < 5; i++) {
                colors[i] = f.tab.colorOf(node(f.tree(), i));
            }
        });
        assertThat(colors).containsExactly(Theme.VCS_MODIFIED, Theme.VCS_MODIFIED, Theme.VCS_MODIFIED, Theme.gold().body, null);
    }

    @Test
    void aNewFileAndEveryFolderOnTheWayDownToItCarryTheMarkOfNewFiles() throws Exception {
        Fixture f = new Fixture(new FolderRoot("Repo", "/s", "", List.of()));
        f.edt(() -> f.tree().expandRow(0));
        f.edt(() -> f.show("Repo", folder("Repo",
                folder("notes", folder("day", new ViewSpec.TreeNode("n.md", "", "comando-file:/s/notes/day/n.md#0", List.of()))),
                folder("src", new ViewSpec.TreeNode("A.java", "", "comando-file:/s/src/A.java#0", List.of())),
                file("same.md"))));
        f.edt(() -> f.tree().expandRow(1));
        f.edt(() -> f.tree().expandRow(2));
        f.edt(() -> f.tree().expandRow(4));
        assertThat(f.labels()).containsExactly("Repo", "notes", "day", "n.md", "src", "A.java", "same.md");
        f.edt(() -> f.tab.showRepos(List.of(new RepoStatus("/s", "main", List.of(
                new RepoStatus.FileChange("src/A.java", "", RepoStatus.Kind.MODIFIED),
                new RepoStatus.FileChange("notes/day/n.md", "", RepoStatus.Kind.UNTRACKED)), false, ""))));
        Icon[] marks = new Icon[7];
        Icon[] selected = new Icon[1];
        f.edt(() -> {
            for (int i = 0; i < marks.length; i++) {
                Component cell = f.tree().getCellRenderer().getTreeCellRendererComponent(f.tree(), node(f.tree(), i), false, true, false, i, false);
                marks[i] = cell instanceof Container row && !(cell instanceof JLabel) ? ((JLabel) row.getComponent(1)).getIcon() : null;
            }
            Component cell = f.tree().getCellRenderer().getTreeCellRendererComponent(f.tree(), node(f.tree(), 3), true, false, true, 3, true);
            selected[0] = ((JLabel) ((Container) cell).getComponent(1)).getIcon();
        });
        Icon mark = Icons.get(FilesTab.NEW_ICON);
        assertThat(marks[0]).as("the repository's row: the mark of changes and the dot, the trail's start").isInstanceOf(RowIcon.class);
        assertThat(marks[0].getIconWidth()).isEqualTo(Icons.get(FilesTab.CHANGES_ICON).getIconWidth() + 2 + mark.getIconWidth());
        assertThat(Arrays.copyOfRange(marks, 1, marks.length)).as("the trail runs from it down to the new file")
                .containsExactly(mark, mark, mark, null, null, null);
        assertThat(selected[0]).as("a selected row loses its colour, not its mark").isSameAs(mark);
        f.edt(() -> {
            Rectangle r = f.tree().getRowBounds(1);
            String tip = f.tree().getToolTipText(new MouseEvent(f.tree(), MouseEvent.MOUSE_MOVED, 0, 0, r.x + 4, r.y + r.height / 2, 0, false));
            assertThat(tip).isEqualTo(FilesTab.NEW_TIP);
        });


        // opened once, the new file reads as any other: no gold, no trail, and its folders hold nothing marked
        OpenedFiles opened = OpenedFiles.inMemory();
        f.edt(() -> f.tab.watchOpened(opened));
        f.edt(() -> opened.add(Path.of("/s/notes/day/n.md")));
        Color[] colors = new Color[7];
        f.edt(() -> {
            for (int i = 0; i < marks.length; i++) {
                Component cell = f.tree().getCellRenderer().getTreeCellRendererComponent(f.tree(), node(f.tree(), i), false, true, false, i, false);
                marks[i] = cell instanceof Container row && !(cell instanceof JLabel) ? ((JLabel) row.getComponent(1)).getIcon() : null;
                colors[i] = f.tab.colorOf(node(f.tree(), i));
            }
        });
        assertThat(marks).containsExactly(Icons.get(FilesTab.CHANGES_ICON), null, null, null, null, null, null);
        assertThat(colors).as("the repository and src still hold the modified file")
                .containsExactly(Theme.VCS_MODIFIED, null, null, null, Theme.VCS_MODIFIED, Theme.VCS_MODIFIED, null);
        f.edt(() -> {
            Rectangle r = f.tree().getRowBounds(1);
            String tip = f.tree().getToolTipText(new MouseEvent(f.tree(), MouseEvent.MOUSE_MOVED, 0, 0, r.x + 4, r.y + r.height / 2, 0, false));
            assertThat(tip).isNull();
        });

        f.edt(() -> f.tab.showRepos(List.of(new RepoStatus("/s", "main", List.of(
                new RepoStatus.FileChange("notes/day/m.md", "", RepoStatus.Kind.UNTRACKED)), false, ""))));
        f.edt(() -> assertThat(f.tab.holdsNew(node(f.tree(), 1))).as("another new file in the same folder lights the trail again").isTrue());
    }

    @Test
    void aRepositoryRowHasTheGitIconAFlagWhileItHasChangesAndItsDoubleClickOpensTheChangesView() throws Exception {
        Fixture f = new Fixture(new FolderRoot("Repo", "/s", "", List.of()), new FolderRoot("Plain", "/p", "", List.of()));
        List<String> told = new CopyOnWriteArrayList<>();
        f.edt(() -> f.tab.tell((owner, at, text) -> told.add(text)));
        f.edt(() -> f.show("Repo", folder("Repo", folder("src", file("A.java")))));
        RepoStatus changed = new RepoStatus("/s", "main", List.of(new RepoStatus.FileChange("src/A.java", "", RepoStatus.Kind.MODIFIED)), false, "");
        f.edt(() -> f.tab.showRepos(List.of(changed)));
        boolean[] marks = new boolean[4];
        f.edt(() -> {
            marks[0] = f.tab.repoAt(node(f.tree(), 0)).isPresent();
            marks[1] = f.tab.repoAt(node(f.tree(), 1)).isPresent();
        });
        assertThat(marks[0]).as("the repository's row").isTrue();
        assertThat(marks[1]).as("a folder that is no repository's root").isFalse();

        f.intents.clear();
        f.edt(() -> doubleClick(f.tree(), 0));
        assertThat(f.intents).as("that repository alone, its row named after it")
                .containsExactly(new Intent.ShowView("", ViewSpec.Changes.TITLE, new ViewSpec.Changes("/s", "", "Repo"), Placement.SIDE));
        assertThat(told).isEmpty();

        f.intents.clear();
        f.edt(() -> f.tab.showRepos(List.of(new RepoStatus("/s", "main", List.of(), false, ""))));
        f.edt(() -> doubleClick(f.tree(), 0));
        assertThat(f.intents).as("nothing to see: no view").isEmpty();
        assertThat(told).containsExactly("No changes to see in Repo: its working tree matches HEAD");
        f.edt(() -> doubleClick(f.tree(), 1));
        assertThat(f.intents).isEmpty();
        assertThat(told).as("a folder's row in no repository says so").containsExactly(
                "No changes to see in Repo: its working tree matches HEAD", "No changes to see in Plain: no git repository holds it");
    }

    @Test
    void aFolderRowInARepositoryHasTheMarkOfItsChangesSaysThemAndItsDoubleClickShowsOnlyThose() throws Exception {
        Fixture f = new Fixture(new FolderRoot("Memory", "/c/MEMORY", "", List.of()), new FolderRoot("KB", "/c/KB", "", List.of()));
        List<String> told = new CopyOnWriteArrayList<>();
        f.edt(() -> f.tab.tell((owner, at, text) -> told.add(text)));
        f.edt(() -> f.tab.showRepos(List.of(new RepoStatus("/c", "master", List.of(
                new RepoStatus.FileChange("MEMORY/a.md", "", RepoStatus.Kind.MODIFIED),
                new RepoStatus.FileChange("MEMORY/new.md", "", RepoStatus.Kind.UNTRACKED),
                new RepoStatus.FileChange("CLAUDE.md", "", RepoStatus.Kind.MODIFIED)), false, ""))));
        assertThat(f.labels()).containsExactly("Memory", "KB");
        f.edt(() -> {
            Component marked = f.tree().getCellRenderer().getTreeCellRendererComponent(f.tree(), node(f.tree(), 0), false, false, false, 0, false);
            assertThat(f.tree().getRowBounds(0).width).as("a row widens for the marks after its name, its path not cut")
                    .isGreaterThanOrEqualTo(marked.getPreferredSize().width);
        });
        f.edt(() -> {
            Component memory = f.tree().getCellRenderer().getTreeCellRendererComponent(f.tree(), node(f.tree(), 0), false, false, false, 0, false);
            assertThat(((JLabel) ((Container) memory).getComponent(1)).getIcon()).as("a change and a new file inside").isInstanceOf(RowIcon.class);
            Component kb = f.tree().getCellRenderer().getTreeCellRendererComponent(f.tree(), node(f.tree(), 1), false, false, false, 1, false);
            assertThat(((Container) kb).getComponents()).as("nothing changed inside: its name and path, no mark")
                    .noneMatch(c -> c instanceof JLabel label && label.getIcon() != null && label != ((Container) kb).getComponent(0));
            assertThat(tip(f.tree(), 0)).isEqualTo("On master · 2 uncommitted here: 1 modified, 1 untracked");
            assertThat(tip(f.tree(), 1)).isEqualTo("On master · nothing to commit here");
        });

        f.intents.clear();
        f.edt(() -> doubleClick(f.tree(), 0));
        assertThat(f.intents).as("the changes inside Memory only, not the whole of the repository")
                .containsExactly(new Intent.ShowView("", ViewSpec.Changes.TITLE, new ViewSpec.Changes("/c", "MEMORY", "Memory"), Placement.SIDE));
        f.intents.clear();
        f.edt(() -> doubleClick(f.tree(), 1));
        assertThat(f.intents).isEmpty();
        assertThat(told).containsExactly("No changes to see in KB: it matches HEAD");
    }

    private static String tip(JTree tree, int row) {
        Rectangle r = tree.getRowBounds(row);
        return tree.getToolTipText(new MouseEvent(tree, MouseEvent.MOUSE_MOVED, 0, 0, r.x + 4, r.y + r.height / 2, 0, false));
    }

    @Test
    void everyFolderIsARowOfTheTreeAndItsMenusShowItsOwnChanges() throws Exception {
        Fixture f = new Fixture(new FolderRoot("Memory", "/c/MEMORY", "", List.of()), new FolderRoot("app", "/r", "", List.of()));
        assertThat(f.labels()).as("one row per folder, no node above them").containsExactly("Memory", "app");

        f.intents.clear();
        f.edt(() -> f.tree().expandRow(1));
        assertThat(f.intents).as("an open folder is read").containsExactly(new Intent.WatchFolders(List.of("app")));
        ViewSpec.TreeNode java = new ViewSpec.TreeNode("A.java", "", "comando-file:/r/A.java#0", List.of());
        f.edt(() -> f.show("app", folder("app", java)));
        assertThat(f.labels()).containsExactly("Memory", "app", "A.java");

        RepoStatus.FileChange changed = new RepoStatus.FileChange("A.java", "", RepoStatus.Kind.MODIFIED);
        f.edt(() -> f.tab.showRepos(List.of(
                new RepoStatus("/c", "master", List.of(new RepoStatus.FileChange("MEMORY/a.md", "", RepoStatus.Kind.MODIFIED)), false, ""),
                new RepoStatus("/r", "main", List.of(changed), false, ""))));
        f.edt(() -> assertThat(tip(f.tree(), 1)).as("a repository's top folder says its branch and what it holds")
                .isEqualTo("On main · 1 uncommitted: 1 modified"));
        f.edt(() -> {
            Component cell = f.tree().getCellRenderer().getTreeCellRendererComponent(f.tree(), node(f.tree(), 1), false, false, false, 1, false);
            assertThat(((JLabel) ((Container) cell).getComponent(1)).getIcon()).as("the mark of changes").isSameAs(Icons.get(FilesTab.CHANGES_ICON));
        });
        f.intents.clear();
        f.edt(() -> doubleClick(f.tree(), 1));
        assertThat(f.intents).containsExactly(new Intent.ShowView("", ViewSpec.Changes.TITLE, new ViewSpec.Changes("/r", "", "app"), Placement.SIDE));

        f.intents.clear();
        f.edt(() -> f.tree().setSelectionRow(2));
        f.edt(() -> {
            JPopupMenu menu = f.tab.menuFor(java);
            assertThat(items(menu)).containsExactly("Open in a panel", "Edit in a panel", "Compare with HEAD");
            ((JMenuItem) menu.getComponent(2)).doClick();
        });
        assertThat(f.intents).containsExactly(new Intent.OpenChange("/r", changed, ChangeAsk.OPEN));

        f.intents.clear();
        f.edt(() -> f.tree().setSelectionRow(0));
        f.edt(() -> {
            JPopupMenu menu = f.tab.menuFor(new ViewSpec.TreeNode("Memory", "/c/MEMORY", List.of()));
            assertThat(items(menu)).as("the changes of the repository holding it, inside it").containsExactly("Show changes in Memory");
            ((JMenuItem) menu.getComponent(0)).doClick();
        });
        assertThat(f.intents).containsExactly(new Intent.ShowView("", ViewSpec.Changes.TITLE, new ViewSpec.Changes("/c", "MEMORY", "Memory"), Placement.SIDE));
    }

    @Test
    void aRepositorysTooltipSaysItsBranchAndWhatItHoldsUncommitted() {
        RepoStatus.FileChange a = new RepoStatus.FileChange("a", "", RepoStatus.Kind.MODIFIED);
        RepoStatus.FileChange b = new RepoStatus.FileChange("b", "", RepoStatus.Kind.UNTRACKED);
        RepoStatus.FileChange c = new RepoStatus.FileChange("c", "", RepoStatus.Kind.MODIFIED);
        assertThat(FilesTab.repoTip(new RepoStatus("/r", "main", List.of(a, b, c), false, "")))
                .isEqualTo("On main · 3 uncommitted: 2 modified, 1 untracked");
        assertThat(FilesTab.repoTip(new RepoStatus("/r", "main", List.of(), false, ""))).isEqualTo("On main · nothing to commit");
        assertThat(FilesTab.repoTip(new RepoStatus("/r", "(detached)", List.of(), false, ""))).isEqualTo("Detached HEAD · nothing to commit");
        assertThat(FilesTab.repoTip(new RepoStatus("/r", "main", List.of(a), true, ""))).startsWith("On main · 1+ uncommitted");
        assertThat(FilesTab.repoTip(new RepoStatus("/r", "", List.of(), false, "not a git repository"))).isEqualTo("Git: cannot be read: not a git repository");
    }

    private static void doubleClick(JTree tree, int row) {
        Rectangle r = tree.getRowBounds(row);
        tree.dispatchEvent(new MouseEvent(tree, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0,
                r.x + 4, r.y + r.height / 2, 2, false, MouseEvent.BUTTON1));
    }

    private static DefaultMutableTreeNode node(JTree tree, int row) {
        return (DefaultMutableTreeNode) tree.getPathForRow(row).getLastPathComponent();
    }

    /** A tab built and rendered on the event thread, recording what it sends and opens. */
    private static final class Fixture {
        final List<Intent> intents = new CopyOnWriteArrayList<>();
        final List<Path> opened = new CopyOnWriteArrayList<>();
        final List<Path> editedFiles = new CopyOnWriteArrayList<>();
        /** The questions asked, and the button the user picks: 0 moves to the Trash, 1 cancels. */
        final List<String> asked = new CopyOnWriteArrayList<>();
        volatile int answer = 1;
        AppState state;
        FilesTab tab;

        Fixture(FolderRoot... folders) throws Exception {
            edt(() -> {
                tab = new FilesTab(intents::add, opened::add, editedFiles::add, (parent, text, title, options) -> {
                    asked.add(String.valueOf(text));
                    return answer;
                });
                tab.component().setSize(260, 400);
                tab.tree().component().setSize(260, 400);
            });
            render(folders);
        }

        void render(FolderRoot... folders) throws Exception {
            state = AppState.initial(List.of(new PanelState(new PanelId("p"), "scratch", "", "")), List.of(), List.of(), List.of(folders));
            edt(() -> tab.render(state));
        }

        /** Shows a read of the folder configured as {@code name}, or of one no longer configured. */
        void show(String name, ViewSpec.TreeNode read) {
            tab.show(state.folder(name).orElse(new FolderRoot(name, "/gone", "", List.of())), read);
        }

        JTree tree() {
            return tab.tree().tree();
        }

        List<String> labels() throws Exception {
            return rows(tree());
        }

        List<String> foundLabels() throws Exception {
            return rows(tab.found().tree());
        }

        String selectedFound() throws Exception {
            String[] out = new String[1];
            edt(() -> out[0] = tab.found().selected() == null ? null : tab.found().selected().label());
            return out[0];
        }

        private List<String> rows(JTree t) throws Exception {
            List<String> out = new ArrayList<>();
            edt(() -> {
                for (int i = 0; i < t.getRowCount(); i++) {
                    out.add(((ViewSpec.TreeNode) ((DefaultMutableTreeNode) t.getPathForRow(i).getLastPathComponent()).getUserObject()).label());
                }
            });
            return out;
        }

        /** The buttons under {@code c}, a scroll bar's arrows left out. */
        private static void collect(Component c, List<AbstractButton> out) {
            if (c instanceof JScrollBar) {
                return;
            }
            if (c instanceof AbstractButton b) {
                out.add(b);
            } else if (c instanceof Container k) {
                for (Component child : k.getComponents()) {
                    collect(child, out);
                }
            }
        }

        void edt(Runnable r) throws Exception {
            SwingUtilities.invokeAndWait(r);
        }
    }
}
