package dev.marregui.comando.ui.widgets.search;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.model.SearchHit;
import dev.marregui.comando.core.view.ViewSpec;

import static org.assertj.core.api.Assertions.assertThat;

/// [SearchTree]: the rows of the Search view, built from a batch of hits.
/// - a row per folder of the folder tabs in the order of its first hit, with its hits and files counted
/// - a chain of single folders is one row; folders come before files, each by name
/// - two folders of one name are told apart by path; a hit outside its folder goes under its parent
/// - a file's name match and its lines, in line order with their headings
/// - the conversations last, with their counts
/// - every link is a target, and the hits are walked in the order shown
class SearchTreeTest {

    private static SearchHit line(final String root, final String source, final int line, final String heading) {
        final String group = root.substring(root.lastIndexOf('/') + 1);
        return new SearchHit(SearchHit.Kind.FILE, source, "", group, line, "  text " + line + "  ", 2, 4, 0L)
                .placed(root, heading);
    }

    private static SearchHit name(final String root, final String source) {
        final String group = root.substring(root.lastIndexOf('/') + 1);
        return new SearchHit(SearchHit.Kind.NAME, source, "", group, 0, source, 0, 1, 0L).placed(root, "");
    }

    private static SearchHit talk(final String id, final String title, final String snippet) {
        return new SearchHit(SearchHit.Kind.CONVERSATION, "/t/" + id + ".jsonl", id, title, 0, snippet, 0, 1, 0L,
                "context of " + snippet, 0, "", "you · 10:00");
    }

    private static ViewSpec.TreeNode child(final ViewSpec.TreeNode node, final String label) {
        return node.children().stream().filter(c -> c.label().equals(label)).findFirst().orElseThrow();
    }

    private static List<ViewSpec.TreeNode> rows(final ViewSpec.TreeNode node) {
        final List<ViewSpec.TreeNode> out = new ArrayList<>();
        for (final ViewSpec.TreeNode c : node.children()) {
            out.add(c);
            out.addAll(rows(c));
        }
        return out;
    }

    /// Each folder searched is a row, in the order its first hit came, counting its hits and its files, singular for
    /// one; the root carries the view's title.
    @Test
    void foldersComeInTheOrderOfTheirFirstHitWithTheirCounts() {
        final Map<String, SearchHit> targets = new HashMap<>();
        final ViewSpec.TreeNode root = SearchTree.build(List.of(
                line("/w/notes", "/w/notes/a.md", 3, ""),
                line("/w/code", "/w/code/x.java", 1, ""),
                line("/w/notes", "/w/notes/a.md", 9, ""),
                line("/w/notes", "/w/notes/b.md", 1, "")), targets);
        assertThat(root.label()).isEqualTo(ViewSpec.Search.TITLE);
        assertThat(root.children()).extracting(ViewSpec.TreeNode::label).containsExactly("notes", "code");
        assertThat(root.children()).extracting(ViewSpec.TreeNode::detail)
                .containsExactly("3 hits in 2 files", "1 hit in 1 file");
    }

    /// A folder holding only one folder joins it in one row, "a/b/c", counting the hits below; folders come before
    /// files, and each kind is sorted by name.
    @Test
    void aChainOfSingleFoldersIsOneRowAndFoldersComeFirst() {
        final Map<String, SearchHit> targets = new HashMap<>();
        final ViewSpec.TreeNode root = SearchTree.build(List.of(
                line("/w/r", "/w/r/z.md", 1, ""),
                line("/w/r", "/w/r/a/b/c/one.md", 1, ""),
                line("/w/r", "/w/r/a/b/c/one.md", 2, ""),
                line("/w/r", "/w/r/a/b/c/d/two.md", 5, ""),
                line("/w/r", "/w/r/m.md", 1, "")), targets);
        final ViewSpec.TreeNode folder = root.children().getFirst();
        assertThat(folder.children()).extracting(ViewSpec.TreeNode::label).containsExactly("a/b/c", "m.md", "z.md");
        final ViewSpec.TreeNode chain = folder.children().getFirst();
        assertThat(chain.detail()).isEqualTo("3 hits");
        assertThat(chain.children()).extracting(ViewSpec.TreeNode::label).containsExactly("d", "one.md");
        assertThat(chain.children().getFirst().detail()).isEqualTo("1 hit");
    }

    /// Two folders searched under one name are labelled by their paths, and a third of its own name keeps it.
    @Test
    void foldersOfOneNameAreToldApartByPath() {
        final Map<String, SearchHit> targets = new HashMap<>();
        final ViewSpec.TreeNode root = SearchTree.build(List.of(
                line("/w/a/docs", "/w/a/docs/x.md", 1, ""),
                line("/w/b/docs", "/w/b/docs/y.md", 1, ""),
                line("/w/code", "/w/code/z.md", 1, "")), targets);
        assertThat(root.children()).extracting(ViewSpec.TreeNode::label)
                .containsExactly("/w/a/docs", "/w/b/docs", "code");
    }

    /// A hit whose folder is empty, or a folder that does not hold its file (a name that only starts like it), goes
    /// under a row named for the file's parent folder, the file directly in it.
    @Test
    void aHitOutsideItsFolderGoesUnderItsParentFolder() {
        final Map<String, SearchHit> targets = new HashMap<>();
        final ViewSpec.TreeNode root = SearchTree.build(List.of(
                line("", "/x/y/loose.md", 4, ""),
                line("/w/ab", "/w/abc/k/near.md", 2, "")), targets);
        assertThat(root.children()).extracting(ViewSpec.TreeNode::label).containsExactly("/x/y", "/w/abc/k");
        assertThat(root.children().getFirst().children()).extracting(ViewSpec.TreeNode::label)
                .containsExactly("loose.md");
        assertThat(root.children().get(1).children()).extracting(ViewSpec.TreeNode::label).containsExactly("near.md");
    }

    /// A file found by its name alone says "name matches" and opens at its name; found by name and on lines, it
    /// counts the lines beside and still opens at its name.
    @Test
    void aNameMatchAloneAndWithLinesSaysSo() {
        final Map<String, SearchHit> targets = new HashMap<>();
        final SearchHit alone = name("/w/r", "/w/r/plan.md");
        final SearchHit both = name("/w/r", "/w/r/plan2.md");
        final ViewSpec.TreeNode root = SearchTree.build(List.of(
                alone,
                line("/w/r", "/w/r/plan2.md", 7, ""),
                both,
                line("/w/r", "/w/r/plan2.md", 3, "")), targets);
        final ViewSpec.TreeNode folder = root.children().getFirst();
        assertThat(folder.detail()).isEqualTo("4 hits in 2 files");
        final ViewSpec.TreeNode one = child(folder, "plan.md");
        assertThat(one.detail()).isEqualTo("name matches");
        assertThat(one.children()).isEmpty();
        assertThat(targets.get(one.link())).isEqualTo(alone);
        final ViewSpec.TreeNode two = child(folder, "plan2.md");
        assertThat(two.detail()).isEqualTo("name matches · 2 hits");
        assertThat(targets.get(two.link())).isEqualTo(both);
    }

    /// A file's lines come in line order whatever order they were found in, each its stripped text, its headings and
    /// line beside; the file opens at its first line.
    @Test
    void linesComeInLineOrderWithTheirHeadings() {
        final Map<String, SearchHit> targets = new HashMap<>();
        final SearchHit late = line("/w/r", "/w/r/doc.md", 40, "Intro" + SearchHit.HEADING_SEPARATOR + "Use");
        final SearchHit early = line("/w/r", "/w/r/doc.md", 2, "");
        final ViewSpec.TreeNode root = SearchTree.build(List.of(late, early), targets);
        final ViewSpec.TreeNode file = root.children().getFirst().children().getFirst();
        assertThat(file.detail()).isEqualTo("2 hits");
        assertThat(file.children()).extracting(ViewSpec.TreeNode::label).containsExactly("text 2", "text 40");
        assertThat(file.children()).extracting(ViewSpec.TreeNode::detail)
                .containsExactly("line 2", "Intro › Use · line 40");
        assertThat(targets.get(file.link())).isEqualTo(early);
        assertThat(targets.get(file.children().get(1).link())).isEqualTo(late);
    }

    /// The conversations come after every folder, whatever the order of the hits, counting hits and conversations;
    /// each conversation is a row in the order first found, its messages in the order found, who and when beside.
    @Test
    void conversationsComeLastWithTheirCounts() {
        final Map<String, SearchHit> targets = new HashMap<>();
        final SearchHit first = talk("c1", "Release", " ship it ");
        final ViewSpec.TreeNode root = SearchTree.build(List.of(
                first,
                talk("c2", "Review", "look"),
                line("/w/r", "/w/r/a.md", 1, ""),
                talk("c1", "Release", "again")), targets);
        assertThat(root.children()).extracting(ViewSpec.TreeNode::label).containsExactly("r", SearchTree.CONVERSATIONS);
        final ViewSpec.TreeNode talks = root.children().getLast();
        assertThat(talks.detail()).isEqualTo("3 hits in 2 conversations");
        assertThat(talks.children()).extracting(ViewSpec.TreeNode::label).containsExactly("Release", "Review");
        assertThat(talks.children()).extracting(ViewSpec.TreeNode::detail).containsExactly("2 hits", "1 hit");
        final ViewSpec.TreeNode release = talks.children().getFirst();
        assertThat(targets.get(release.link())).isEqualTo(first);
        assertThat(release.children()).extracting(ViewSpec.TreeNode::label).containsExactly("ship it", "again");
        assertThat(release.children()).extracting(ViewSpec.TreeNode::detail).containsOnly("you · 10:00");
    }

    /// Every row that opens something has a link of its own, every link is a target, and a new build forgets the
    /// targets of the last one.
    @Test
    void everyLinkIsAUniqueTarget() {
        final Map<String, SearchHit> targets = new HashMap<>();
        targets.put("stale", talk("old", "Old", "x"));
        final ViewSpec.TreeNode root = SearchTree.build(List.of(
                name("/w/r", "/w/r/a/n.md"),
                line("/w/r", "/w/r/a/n.md", 1, ""),
                line("/w/r", "/w/r/b.md", 2, ""),
                talk("c1", "T", "m")), targets);
        final List<String> links = rows(root).stream().map(ViewSpec.TreeNode::link).filter(l -> !l.isEmpty()).toList();
        assertThat(links).doesNotHaveDuplicates().hasSize(6);
        assertThat(targets.keySet()).containsExactlyInAnyOrderElementsOf(links);
    }

    /// The walk F7 takes lists every hit once, top to bottom as shown: a name match at its file's row, then the file's
    /// lines in line order, then the next file, the conversations last.
    @Test
    void orderWalksEveryHitTopToBottom() {
        final Map<String, SearchHit> targets = new HashMap<>();
        final SearchHit message = talk("c1", "T", "m");
        final SearchHit b9 = line("/w/r", "/w/r/b.md", 9, "");
        final SearchHit named = name("/w/r", "/w/r/a.md");
        final SearchHit b1 = line("/w/r", "/w/r/b.md", 1, "");
        final SearchHit a5 = line("/w/r", "/w/r/a.md", 5, "");
        final List<SearchHit> hits = List.of(message, b9, named, b1, a5);
        final ViewSpec.TreeNode root = SearchTree.build(hits, targets);
        assertThat(SearchTree.order(root, targets)).containsExactly(named, a5, b1, b9, message);
    }

    /// A row's key is the labels below the root joined by '/', so a joined chain and the same folders apart agree.
    @Test
    void keyJoinsTheLabelsBelowTheRoot() {
        final TreePath joined = new TreePath(new Object[] {
                new DefaultMutableTreeNode(new ViewSpec.TreeNode(ViewSpec.Search.TITLE, "", List.of())),
                new DefaultMutableTreeNode(new ViewSpec.TreeNode("r", "", List.of())),
                new DefaultMutableTreeNode(new ViewSpec.TreeNode("a/b", "", List.of()))});
        final TreePath apart = new TreePath(new Object[] {
                new DefaultMutableTreeNode(new ViewSpec.TreeNode(ViewSpec.Search.TITLE, "", List.of())),
                new DefaultMutableTreeNode(new ViewSpec.TreeNode("r", "", List.of())),
                new DefaultMutableTreeNode(new ViewSpec.TreeNode("a", "", List.of())),
                new DefaultMutableTreeNode(new ViewSpec.TreeNode("b", "", List.of()))});
        assertThat(SearchTree.key(joined)).isEqualTo("r/a/b").isEqualTo(SearchTree.key(apart));
    }

    /// The hits of one source are those whose source is the hit's, in the order given.
    @Test
    void sameSourceKeepsTheHitsOfOneSourceInOrder() {
        final SearchHit a9 = line("/w/r", "/w/r/a.md", 9, "");
        final SearchHit b1 = line("/w/r", "/w/r/b.md", 1, "");
        final SearchHit a2 = line("/w/r", "/w/r/a.md", 2, "");
        assertThat(SearchTree.sameSource(List.of(a9, b1, a2), a2)).containsExactly(a9, a2);
    }
}
