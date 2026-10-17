package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.resource.ResourceActions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.AbstractButton;
import javax.swing.JMenuItem;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
        assertThat(FilesTab.iconName(file("NEVSKY-PLAN.md"), none)).isEqualTo("plan");
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
        assertThat(f.labels()).containsExactly("No folders yet. + adds one.");
    }

    @Test
    void theFilterSearchesEveryFolderAndClearingItBringsBackTheTreeAsLeft() throws Exception {
        Fixture f = new Fixture(new FolderRoot("Scratchpad", "/s", "", List.of()), new FolderRoot("KB", "/kb", "", List.of()));
        f.edt(() -> f.tree().expandRow(0));
        f.edt(() -> f.show("Scratchpad", folder("Scratchpad", folder("2026-10-16", file("NEVSKY-PLAN.md")), file("notes.md"))));
        f.intents.clear();

        f.edt(() -> f.tab.filter().setText("plan"));
        assertThat(f.intents).as("the folders never read are read once, so the filter searches them too")
                .containsExactly(new Intent.BrowseFolders(List.of("KB")));
        assertThat(f.foundLabels()).containsExactly("Scratchpad", "2026-10-16", "NEVSKY-PLAN.md");

        f.edt(() -> f.show("KB", folder("KB", folder("plans", file("q4.md")), file("design.md"))));
        assertThat(f.foundLabels()).as("a matching folder stays closed").containsExactly("Scratchpad", "2026-10-16", "NEVSKY-PLAN.md", "KB", "plans");
        assertThat(f.labels()).as("the tree behind keeps KB closed").containsExactly("Scratchpad", "2026-10-16", "NEVSKY-PLAN.md", "notes.md", "KB");

        f.intents.clear();
        f.edt(() -> f.tab.filter().setText("plann"));
        assertThat(f.intents).as("every folder read: nothing more to read").isEmpty();
        f.edt(() -> f.tab.filter().setText("zzz"));
        assertThat(f.foundLabels()).containsExactly(FilesTab.NO_MATCH);

        f.edt(() -> f.tab.filter().setText(""));
        assertThat(f.tab.tree().component().isVisible()).isTrue();
        assertThat(f.tab.found().component().isVisible()).isFalse();
        assertThat(f.labels()).containsExactly("Scratchpad", "2026-10-16", "NEVSKY-PLAN.md", "notes.md", "KB");
    }

    @Test
    void theBarEditsAndRemovesTheSelectedFolderFromOneGroupAtTheLeftEdge() throws Exception {
        Fixture f = new Fixture(new FolderRoot("Scratchpad", "/s", "", List.of()), new FolderRoot("KB", "/kb", "", List.of()));
        AbstractButton edit = f.edit();
        AbstractButton remove = f.remove();
        assertThat(edit.isEnabled()).isFalse();
        assertThat(f.button("Read the open folders again")).as("open folders follow the disk on their own").isNull();
        BorderLayout bar = (BorderLayout) ((Container) f.tab.actions()).getLayout();
        assertThat(bar.getLayoutComponent(BorderLayout.EAST)).as("nothing at the right edge").isNull();
        assertThat(SwingUtilities.isDescendingFrom(remove, bar.getLayoutComponent(BorderLayout.WEST))).isTrue();
        assertThat(SwingUtilities.isDescendingFrom(f.add(), bar.getLayoutComponent(BorderLayout.WEST))).isTrue();

        AbstractButton add = f.add();
        assertThat(add.isEnabled()).as("nothing selected: a folder can be added").isTrue();
        assertThat(add.getToolTipText()).isEqualTo("Add a folder to the Files tab…");
        assertThat(edit.getToolTipText()).isEqualTo("Edit the selected folder or file");
        f.edt(() -> f.tree().expandRow(0));
        f.edt(() -> f.show("Scratchpad", folder("Scratchpad", folder("plans", file("a.md")), file("notes.md"))));
        assertThat(f.labels()).containsExactly("Scratchpad", "plans", "notes.md", "KB");
        f.edt(() -> f.tree().setSelectionRow(1));
        assertThat(add.isEnabled()).as("a folder inside a folder: add").isTrue();
        assertThat(edit.isEnabled()).as("but it is not a folder of the tab").isFalse();
        assertThat(edit.getToolTipText()).isEqualTo("Edit folder plans: only files and the tab's own folders");
        f.edt(() -> f.tree().setSelectionRow(3));
        assertThat(List.of(add, edit, remove)).as("a folder row: all on").allMatch(AbstractButton::isEnabled);
        assertThat(List.of(edit.getToolTipText(), f.cloneOf().getToolTipText(), remove.getToolTipText()))
                .containsExactly("Edit folder KB…", "Clone folder KB…", "Remove folder KB from the Files tab…");
        f.edt(edit::doClick);
        assertThat(f.edited).containsExactly("edit KB");
        f.intents.clear();
        f.edt(remove::doClick);
        assertThat(f.asked).singleElement().asString().contains("Remove folder KB from the Files tab? Its files stay on disk.");
        assertThat(f.intents).containsExactly(new Intent.RemoveFolder("KB"));
    }

    @Test
    void aReadOnlyFolderIsNeitherEditedClonedNorRemovedAndSaysSo() throws Exception {
        Fixture f = new Fixture(new FolderRoot("Memory", "/m", "MEMORY.md", List.of(), "memory", true),
                new FolderRoot("KB", "/kb", "", List.of(), "knowledge", false));
        f.edt(() -> f.tree().setSelectionRow(0));
        for (AbstractButton b : List.of(f.edit(), f.cloneOf(), f.remove())) {
            assertThat(b.isEnabled()).as(b.getToolTipText()).isFalse();
        }
        assertThat(f.edit().getToolTipText()).isEqualTo("Edit folder Memory: it is read-only");
        f.edt(() -> f.show("Memory", folder("Memory", file("MEMORY.md"))));
        f.edt(() -> f.tree().expandRow(0));
        f.edt(() -> f.tree().setSelectionRow(1));
        assertThat(List.of(f.add(), f.edit(), f.cloneOf(), f.remove())).as("a read-only folder's file").noneMatch(AbstractButton::isEnabled);
        assertThat(f.remove().getToolTipText()).isEqualTo("Remove file MEMORY.md: folder Memory is read-only");
        f.edt(() -> assertThat(f.tab.menuFor(file("MEMORY.md")).getComponents()).extracting(c -> ((JMenuItem) c).getText())
                .containsExactly("Open in a panel"));
        f.edt(() -> f.tree().collapseRow(0));
        f.edt(() -> f.tree().setSelectionRow(0));
        f.edt(() -> assertThat(f.tab.menuFor(new ViewSpec.TreeNode("Memory", "/m · read-only", List.of())).getComponents())
                .extracting(c -> ((JMenuItem) c).getText()).containsExactly("Open MEMORY.md"));
        f.edt(() -> f.tree().setSelectionRow(1));
        assertThat(f.edit().isEnabled()).isTrue();

        assertThat(f.tab.rowIcon(new ViewSpec.TreeNode("Memory", "/m · read-only", List.of()))).as("its row says so").contains("folder-memory");
        assertThat(f.tab.rowIcon(new ViewSpec.TreeNode("KB", "/kb", List.of()))).contains("folder-knowledge");
        assertThat(f.tab.rowIcon(folder("Memory"))).as("a subfolder named as a folder").isEmpty();
    }

    @Test
    void theBarEditsClonesAndDeletesAFileNamingItInItsTips() throws Exception {
        Fixture f = new Fixture(new FolderRoot("Scratchpad", "/s", "", List.of()));
        f.edt(() -> f.tree().expandRow(0));
        f.edt(() -> f.show("Scratchpad", folder("Scratchpad", file("notes.md"))));
        f.edt(() -> f.tree().setSelectionRow(1));
        assertThat(f.add().isEnabled()).as("add adds a folder to the tab: not for a file").isFalse();
        assertThat(List.of(f.edit(), f.cloneOf(), f.remove())).allMatch(AbstractButton::isEnabled);
        assertThat(List.of(f.edit().getToolTipText(), f.cloneOf().getToolTipText(), f.remove().getToolTipText()))
                .containsExactly("Edit file notes.md in a panel", "Clone file notes.md…", "Delete file notes.md…");

        f.edt(f.edit()::doClick);
        assertThat(f.editedFiles).containsExactly(Path.of("/s/notes.md"));

        f.intents.clear();
        f.names.add("notes 2.md");
        f.edt(f.cloneOf()::doClick);
        assertThat(f.offered).containsExactly("notes copy.md");
        assertThat(f.intents).containsExactly(new Intent.CopyFile("/s/notes.md", "/s/notes 2.md"));
        f.intents.clear();
        f.names.add("../up.md");
        f.edt(f.cloneOf()::doClick);
        assertThat(f.intents).singleElement().isInstanceOfSatisfying(Intent.Notify.class,
                n -> assertThat(n.text()).isEqualTo("Cannot clone notes.md: \"../up.md\" is not a new name in its folder"));
        f.intents.clear();
        f.edt(f.cloneOf()::doClick);
        assertThat(f.intents).as("the name question cancelled").isEmpty();

        f.edt(f.remove()::doClick);
        assertThat(f.asked).containsExactly("Delete notes.md for good? This desktop has no Trash: it cannot be undone.");
        assertThat(f.options).containsExactly(List.of("Delete", "Cancel"));
        assertThat(f.intents).containsExactly(new Intent.DeleteFile("/s/notes.md", false));

        f.trash = true;
        f.intents.clear();
        f.edt(() -> f.tree().clearSelection());
        f.edt(() -> f.tree().setSelectionRow(1));
        assertThat(f.remove().getToolTipText()).isEqualTo("Move file notes.md to the Trash…");
        f.edt(f.remove()::doClick);
        assertThat(f.options).last().isEqualTo(List.of("Move to Trash", "Cancel"));
        assertThat(f.intents).containsExactly(new Intent.DeleteFile("/s/notes.md", true));

        f.edt(() -> assertThat(f.tab.menuFor(file("notes.md")).getComponents()).extracting(c -> ((JMenuItem) c).getText())
                .containsExactly("Open in a panel", "Edit in a panel", "Clone…", "Move to the Trash…"));
    }

    @Test
    void aCopyIsNamedInItsFolderOnly() {
        assertThat(FilesTab.isNewName("notes 2.md", "notes.md")).isTrue();
        for (String bad : List.of("", ".", "..", "notes.md", "a/b.md", "../x.md")) {
            assertThat(FilesTab.isNewName(bad, "notes.md")).as(bad).isFalse();
        }
    }

    @Test
    void everyIconAFolderMayHaveIsDrawn() {
        for (String icon : FolderRoot.ICONS) {
            String name = FilesTab.rowIconName(new FolderRoot("F", "/f", "", List.of(), icon, false));
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
    void whileFilteringTheBarAndTheMenuActOnTheFolderPickedInTheFiltersTree() throws Exception {
        Fixture f = new Fixture(new FolderRoot("Scratchpad", "/s", "", List.of()), new FolderRoot("KB", "/kb", "ALPHA.md", List.of()));
        f.edt(() -> f.show("Scratchpad", folder("Scratchpad", file("notes.md"))));
        f.edt(() -> f.show("KB", folder("KB", file("notes.md"))));
        f.edt(() -> f.tree().setSelectionRow(0));
        f.edt(() -> f.tab.filter().setText("notes"));
        AbstractButton edit = f.edit();
        assertThat(edit.isEnabled()).as("nothing picked in the filter's tree yet").isFalse();
        f.edt(() -> f.tab.found().tree().setSelectionRow(2));
        assertThat(f.foundLabels().get(2)).isEqualTo("KB");
        assertThat(edit.isEnabled()).isTrue();
        f.edt(edit::doClick);
        assertThat(f.edited).containsExactly("edit KB");

        List<String> items = new ArrayList<>();
        f.edt(() -> {
            for (Component c : f.tab.menuFor(new ViewSpec.TreeNode("KB", "/kb", List.of())).getComponents()) {
                items.add(((JMenuItem) c).getText());
            }
        });
        assertThat(items).containsExactly("Open ALPHA.md", "Edit…", "Clone…", "Remove…");
        f.edt(() -> f.tab.found().tree().setSelectionRow(3));
        f.edt(() -> {
            assertThat(f.tab.menuFor(file("notes.md")).getComponents()).extracting(c -> ((JMenuItem) c).getText())
                    .containsExactly("Open in a panel", "Edit in a panel", "Clone…", "Delete…");
            assertThat(f.tab.menuFor(new ViewSpec.TreeNode("KB", "/kb", List.of())).getComponentCount()).as("a file selected: no folder menu").isZero();
        });
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

    /** A tab built and rendered on the event thread, recording what it sends, opens and asks. */
    private static final class Fixture {
        final List<Intent> intents = new CopyOnWriteArrayList<>();
        final List<Path> opened = new CopyOnWriteArrayList<>();
        final List<String> edited = new CopyOnWriteArrayList<>();
        final List<String> asked = new CopyOnWriteArrayList<>();
        final List<List<Object>> options = new CopyOnWriteArrayList<>();
        final List<Path> editedFiles = new CopyOnWriteArrayList<>();
        /** The names the user types when asked, in turn; none left answers as Cancel. */
        final List<String> names = new CopyOnWriteArrayList<>();
        final List<String> offered = new CopyOnWriteArrayList<>();
        volatile boolean trash;
        AppState state;
        FilesTab tab;

        Fixture(FolderRoot... folders) throws Exception {
            ResourceActions.Editor editor = new ResourceActions.Editor() {
                @Override
                public void add() {
                    edited.add("new");
                }

                @Override
                public void edit(String name) {
                    edited.add("edit " + name);
                }

                @Override
                public void cloneOf(String name) {
                    edited.add("clone " + name);
                }
            };
            ResourceActions.Questions yes = (parent, text, title, choices) -> {
                asked.add(text);
                options.add(List.of(choices));
                return 0;
            };
            FilesTab.Names typed = (parent, title, text, initial) -> {
                offered.add(initial);
                return names.isEmpty() ? null : names.removeFirst();
            };
            edt(() -> {
                tab = new FilesTab(intents::add, opened::add, editedFiles::add, editor, yes, typed, () -> trash, () -> state);
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

        AbstractButton button(String tip) {
            return find(tab.actions(), tip);
        }

        /** The bar's buttons in their order: add, edit, clone, remove. */
        private AbstractButton bar(int i) {
            List<AbstractButton> out = new ArrayList<>();
            collect(tab.actions(), out);
            return out.get(i);
        }

        AbstractButton add() {
            return bar(0);
        }

        AbstractButton edit() {
            return bar(1);
        }

        AbstractButton cloneOf() {
            return bar(2);
        }

        AbstractButton remove() {
            return bar(3);
        }

        private static void collect(Component c, List<AbstractButton> out) {
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

        private static AbstractButton find(Component c, String tip) {
            if (c instanceof AbstractButton b && tip.equals(b.getToolTipText())) {
                return b;
            }
            if (c instanceof Container k) {
                for (Component child : k.getComponents()) {
                    AbstractButton b = find(child, tip);
                    if (b != null) {
                        return b;
                    }
                }
            }
            return null;
        }
    }
}
