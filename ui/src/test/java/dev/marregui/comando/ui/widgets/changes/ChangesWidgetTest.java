package dev.marregui.comando.ui.widgets.changes;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.ChangeAsk;
import dev.marregui.comando.core.model.RepoStatus;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.action.ActionRegistry;
import dev.marregui.comando.ui.kit.async.OnEdt;
import dev.marregui.comando.ui.kit.vcs.RepoChanges;
import dev.marregui.comando.ui.widget.WidgetContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.Component;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OnEdt.class)
class ChangesWidgetTest {

    private static RepoStatus.FileChange change(String path, RepoStatus.Kind kind) {
        return new RepoStatus.FileChange(path, "", kind);
    }

    private static final RepoStatus REPO = new RepoStatus("/w/edge", "ENG-1", List.of(
            change("server/src/main/java/a/A.java", RepoStatus.Kind.MODIFIED),
            change("server/src/main/java/a/B.java", RepoStatus.Kind.ADDED),
            change("README.md", RepoStatus.Kind.DELETED),
            change("notes/x.md", RepoStatus.Kind.UNTRACKED)), false, "");

    private final List<Intent> sent = new ArrayList<>();
    private final RepoChanges changes = new RepoChanges();
    private final List<Path> opened = new ArrayList<>();

    private ChangesWidget widget() {
        return new ChangesWidget(new WidgetContext(sent::add, new ActionRegistry()), changes, opened::add);
    }

    @Test
    void aRepositoryListsWhatChangedWhatIsUntrackedAndWhatWentByFolder() {
        Map<String, ChangesWidget.Target> targets = new HashMap<>();
        ViewSpec.TreeNode root = ChangesWidget.build(List.of(REPO), targets);
        ViewSpec.TreeNode repo = root.children().getFirst();
        assertThat(repo.label()).isEqualTo("edge");
        assertThat(repo.detail()).isEqualTo("ENG-1 · 4 changes");
        assertThat(repo.children()).extracting(ViewSpec.TreeNode::label)
                .containsExactly(ChangesWidget.CHANGED, ChangesWidget.UNTRACKED, ChangesWidget.REMOVED);
        ViewSpec.TreeNode changed = repo.children().getFirst();
        assertThat(changed.detail()).isEqualTo("2 files");
        assertThat(changed.children()).extracting(ViewSpec.TreeNode::label)
                .as("a chain of single folders in one row").containsExactly("server/src/main/java/a");
        assertThat(changed.children().getFirst().children()).extracting(ViewSpec.TreeNode::label).containsExactly("A.java", "B.java");
        assertThat(repo.children().get(1).children()).extracting(ViewSpec.TreeNode::label).containsExactly("notes");
        ViewSpec.TreeNode removed = repo.children().get(2);
        assertThat(removed.children()).extracting(ViewSpec.TreeNode::label).containsExactly("README.md");
        assertThat(removed.children().getFirst().detail()).isEqualTo("deleted");
        assertThat(targets).hasSize(4);
    }

    @Test
    void twoRepositoriesOfOneNameAreToldApartByPath() {
        RepoStatus other = new RepoStatus("/z/edge", "main", List.of(), false, "");
        ViewSpec.TreeNode root = ChangesWidget.build(List.of(REPO, other), new HashMap<>());
        assertThat(root.children()).extracting(ViewSpec.TreeNode::label).containsExactly("/w/edge", "/z/edge");
        assertThat(root.children().get(1).detail()).isEqualTo("main · " + ChangesWidget.CLEAN);
    }

    @Test
    void onePathDeletedFromTheIndexAndUntrackedIsTwoRowsEachOpeningItsOwnComparison() {
        Map<String, ChangesWidget.Target> targets = new HashMap<>();
        ChangesWidget.build(List.of(new RepoStatus("/r", "main", List.of(change("a.txt", RepoStatus.Kind.DELETED),
                change("a.txt", RepoStatus.Kind.UNTRACKED)), false, "")), targets);
        assertThat(targets.values()).extracting(t -> t.change().kind())
                .containsExactlyInAnyOrder(RepoStatus.Kind.DELETED, RepoStatus.Kind.UNTRACKED);
    }

    @Test
    void aClosedFolderStaysClosedAndTheSelectionStaysWhenItsChainSplits() {
        ChangesWidget w = widget();
        changes.set(List.of(REPO));
        JTree tree = w.tree().tree();
        tree.collapseRow(rowOf(tree, "server/src/main/java/a"));
        tree.setSelectionRow(rowOf(tree, "README.md"));
        List<RepoStatus.FileChange> more = new ArrayList<>(REPO.changes());
        more.add(change("server/src/main/java/b/C.java", RepoStatus.Kind.MODIFIED));
        changes.set(List.of(new RepoStatus(REPO.root(), REPO.branch(), more, false, "")));
        assertThat(rowShown(tree, "server/src/main/java")).as("the chain split").isTrue();
        assertThat(tree.isExpanded(tree.getPathForRow(rowOf(tree, "a")))).as("the folder the user closed").isFalse();
        assertThat(tree.isExpanded(tree.getPathForRow(rowOf(tree, "b")))).as("a new folder comes open").isTrue();
        assertThat(((ViewSpec.TreeNode) ((DefaultMutableTreeNode) tree.getSelectionPath().getLastPathComponent()).getUserObject()).label())
                .isEqualTo("README.md");
    }

    private static boolean rowShown(JTree tree, String label) {
        try {
            rowOf(tree, label);
            return true;
        } catch (AssertionError e) {
            return false;
        }
    }

    @Test
    void aRepositoryThatCannotBeReadSaysWhy() {
        ViewSpec.TreeNode root = ChangesWidget.build(List.of(RepoStatus.failed("/w/edge", "not a git repository")), new HashMap<>());
        assertThat(root.children().getFirst().children().getFirst().label()).isEqualTo("✗ cannot be read: not a git repository");
        assertThat(ChangesWidget.build(List.of(), new HashMap<>()).children().getFirst().label()).isEqualTo(ChangesWidget.NO_REPOS);
    }

    @Test
    void everythingComesOpenAndAFileOpensItsComparison() {
        ChangesWidget w = widget();
        changes.set(List.of(REPO));
        JTree tree = w.tree().tree();
        assertThat(tree.getRowCount()).as("repository, three groups, two folders, four files").isEqualTo(10);
        int row = rowOf(tree, "A.java");
        tree.setSelectionRow(row);
        assertThat(sent).containsExactly(new Intent.OpenChange("/w/edge", REPO.changes().getFirst(), ChangeAsk.FOLLOW));
        sent.clear();
        w.tree().tree().getActionMap().get("activate").actionPerformed(null);
        assertThat(sent).containsExactly(new Intent.OpenChange("/w/edge", REPO.changes().getFirst(), ChangeAsk.OPEN));
    }

    @Test
    void aNewReadKeepsWhatTheUserClosedAndSelectedAndSendsNothing() {
        ChangesWidget w = widget();
        changes.set(List.of(REPO));
        JTree tree = w.tree().tree();
        tree.collapseRow(rowOf(tree, ChangesWidget.UNTRACKED));
        tree.setSelectionRow(rowOf(tree, "B.java"));
        sent.clear();
        List<RepoStatus.FileChange> more = new ArrayList<>(REPO.changes());
        more.add(change("docs/new.md", RepoStatus.Kind.UNTRACKED));
        changes.set(List.of(new RepoStatus(REPO.root(), REPO.branch(), more, false, "")));
        assertThat(sent).as("the selection restored is not the user's move").isEmpty();
        assertThat(tree.isExpanded(tree.getPathForRow(rowOf(tree, ChangesWidget.UNTRACKED)))).isFalse();
        assertThat(((ViewSpec.TreeNode) ((DefaultMutableTreeNode) tree.getSelectionPath().getLastPathComponent()).getUserObject()).label())
                .isEqualTo("B.java");
        w.unmount();
        changes.set(List.of());
        assertThat(tree.getRowCount()).as("unmounted: no longer follows").isGreaterThan(1);
    }

    @Test
    void theSelectionsMenuRollsBackOrRemovesEveryFileInItAfterOneQuestion() {
        List<String> calls = new ArrayList<>();
        List<String> asked = new ArrayList<>();
        int[] answer = {1};
        ChangesWidget.Actions recorder = new ChangesWidget.Actions() {
            @Override
            public void rollBack(String root, List<RepoStatus.FileChange> changes) {
                calls.add("rollBack " + root + " " + String.join(",", changes.stream().map(RepoStatus.FileChange::path).toList()));
            }

            @Override
            public void remove(String root, List<String> paths) {
                calls.add("remove " + root + " " + String.join(",", paths));
            }
        };
        ChangesWidget w = new ChangesWidget(new WidgetContext(sent::add, new ActionRegistry()), changes, opened::add, recorder,
                (parent, text, title, options) -> {
                    asked.add(text);
                    return answer[0];
                });
        changes.set(List.of(REPO));
        JTree tree = w.tree().tree();
        TreePath group = tree.getPathForRow(rowOf(tree, ChangesWidget.CHANGED));
        TreePath note = tree.getPathForRow(rowOf(tree, "x.md"));
        tree.setSelectionPaths(new TreePath[] {group, note});
        assertThat(w.chosen()).as("a group stands for every file under it").extracting(t -> t.change().path())
                .containsExactly("server/src/main/java/a/A.java", "server/src/main/java/a/B.java", "notes/x.md");
        JPopupMenu menu = w.menuFor(node(group));
        assertThat(items(menu)).containsExactly("Roll back 3 files to HEAD…", "Move 3 files to the Trash…");

        ((JMenuItem) menu.getComponent(0)).doClick();
        assertThat(calls).as("cancelled").isEmpty();
        answer[0] = 0;
        ((JMenuItem) menu.getComponent(0)).doClick();
        assertThat(asked.getLast()).startsWith("Roll back 3 files to HEAD? The versions you have go to the Trash");
        assertThat(calls).containsExactly("rollBack /w/edge server/src/main/java/a/A.java,server/src/main/java/a/B.java,notes/x.md");
        ((JMenuItem) menu.getComponent(1)).doClick();
        assertThat(calls.getLast()).isEqualTo("remove /w/edge server/src/main/java/a/A.java,server/src/main/java/a/B.java,notes/x.md");

        TreePath readme = tree.getPathForRow(rowOf(tree, "README.md"));
        tree.setSelectionPath(readme);
        JPopupMenu one = w.menuFor(node(readme));
        assertThat(items(one)).containsExactly("Compare with HEAD", "Roll back README.md to HEAD…", "Move to the Trash…");
        assertThat(one.getComponents()).filteredOn(c -> c instanceof JMenuItem m && m.getText().equals("Move to the Trash…"))
                .as("a removed file is not removed again").allMatch(c -> !c.isEnabled());
    }

    private static ViewSpec.TreeNode node(TreePath p) {
        return (ViewSpec.TreeNode) ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject();
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

    private static int rowOf(JTree tree, String label) {
        for (int i = 0; i < tree.getRowCount(); i++) {
            TreePath p = tree.getPathForRow(i);
            if (((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject() instanceof ViewSpec.TreeNode n && n.label().equals(label)) {
                return i;
            }
        }
        throw new AssertionError("no row " + label);
    }
}
