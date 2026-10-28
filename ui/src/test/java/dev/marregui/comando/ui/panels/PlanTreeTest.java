package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.view.ViewSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// [PlanTree] turns an EXPLAIN result into a tree by indentation.
/// - QuestDB plans nest by indentation and the root counts the lines
/// - Postgres `->` arrows are dropped and multi-line rows split
/// - an `explain` statement is recognised with leading blanks
class PlanTreeTest {

    /// Six QuestDB plan lines nest by their indentation, including a jump of four columns, and the root's detail reads
    /// "6 lines".
    @Test
    void questDbPlanNestsByIndentation() {
        ViewSpec.TreeNode plan = PlanTree.parse(List.of(
                "Async Group By workers: 1",
                "  keys: [symbol]",
                "  filter: null",
                "    PageFrame",
                "        Row forward scan",
                "        Frame forward scan on: trades"));
        assertThat(plan.detail()).isEqualTo("6 lines");
        ViewSpec.TreeNode top = plan.children().getFirst();
        assertThat(top.label()).isEqualTo("Async Group By workers: 1");
        assertThat(top.children()).extracting(ViewSpec.TreeNode::label).containsExactly("keys: [symbol]", "filter: null");
        assertThat(top.children().get(1).children().getFirst().children()).extracting(ViewSpec.TreeNode::label)
                .containsExactly("Row forward scan", "Frame forward scan on: trades");
    }

    /// A Postgres plan delivered as one multi-line row splits into lines, nests by indentation and loses its `->`
    /// prefixes; [PlanTree#isExplain] accepts `explain` after leading blanks.
    @Test
    void postgresArrowsAreDropped() {
        ViewSpec.TreeNode plan = PlanTree.parse(List.of("Hash Join  (cost=1.0..2.0)\n  ->  Seq Scan on a\n  ->  Hash\n        ->  Seq Scan on b"));
        ViewSpec.TreeNode join = plan.children().getFirst();
        assertThat(join.children()).extracting(ViewSpec.TreeNode::label).containsExactly("Seq Scan on a", "Hash");
        assertThat(join.children().get(1).children().getFirst().label()).isEqualTo("Seq Scan on b");
        assertThat(PlanTree.isExplain("  explain select 1")).isTrue();
    }
}
