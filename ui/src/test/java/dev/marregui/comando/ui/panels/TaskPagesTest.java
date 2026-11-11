package dev.marregui.comando.ui.panels;

import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.logbook.IndexParser;
import dev.marregui.comando.core.logbook.LogbookTask;
import dev.marregui.comando.core.model.LinearTickets;

import static org.assertj.core.api.Assertions.assertThat;

/// [TaskPages] gives a task's section with its tickets and documents as links, and nothing else changed.
/// - A ticket id becomes a link when its team is the site's or the task names it; other id-shaped words, and ids in
///   code spans, code blocks of any fence, indented blocks and links, stay as written.
/// - A document path in backticks becomes a `file:` link under the day folder, on the Documents line and elsewhere.
/// - With no workspace there are no ticket links, with no day folder no document links, and with neither the section
///   is returned as it is.
class TaskPagesTest {

    private static final LinearTickets.Site ACME = new LinearTickets.Site("acme", Set.of("EDG"));
    private static final Path DAY = Path.of("/scratchpad/day");
    private static final LogbookTask TASK = task("""
            #### 57. EDG-1212: read-only config (Miguel, 2026-11-02)

            - **Ticket:** OPS-7
            - **State:** open, see EDG-99, OPS-7 and UTF-8, not `EDG-5` nor [EDG-6](https://example.com/EDG-6)
            - **Documents:** `alpha/01-first.md`, `alpha/work/a spec.md`
            - **Next:** read `alpha/01-first.md` again, not `alpha/other.md`

            ```
            EDG-7 `alpha/01-first.md`
            ```
            """);

    /// A ticket of one of the site's teams, or one the task names, links to its issue; an id-shaped word of another
    /// team stays as written.
    @Test
    void aTicketOfTheSitesTeamsOrOfTheTaskLinksToItsIssue() {
        final String page = TaskPages.markdown(TASK, null, ACME);
        assertThat(page).startsWith("#### 57. [EDG-1212](https://linear.app/acme/issue/EDG-1212): read-only config");
        assertThat(page).contains("- **Ticket:** [OPS-7](https://linear.app/acme/issue/OPS-7)\n");
        assertThat(page).contains("see [EDG-99](https://linear.app/acme/issue/EDG-99), "
                + "[OPS-7](https://linear.app/acme/issue/OPS-7) and UTF-8,");
    }

    /// An id in a code span, in a fenced block or in an existing link is left as written.
    @Test
    void anIdInCodeOrInALinkIsLeftAsWritten() {
        final String page = TaskPages.markdown(TASK, DAY, ACME);
        assertThat(page).contains("not `EDG-5` nor [EDG-6](https://example.com/EDG-6)\n");
        assertThat(page).endsWith("```\nEDG-7 `alpha/01-first.md`\n```");
    }

    /// An id or a document in a block fenced with tildes is left as written.
    @Test
    void aBlockFencedWithTildesIsLeftAsWritten() {
        final String block = "~~~\nEDG-8 `alpha/01-first.md`\n~~~";
        assertThat(blockPage(block)).isEqualTo(linkedBlock(block));
    }

    /// An id or a document in a block fenced with four backticks, holding a line of three, is left as written.
    @Test
    void aBlockFencedWithFourBackticksIsLeftAsWritten() {
        final String block = "````\n```\nEDG-8 `alpha/01-first.md`\n```\n````";
        assertThat(blockPage(block)).isEqualTo(linkedBlock(block));
    }

    /// An id or a document in an indented block is left as written.
    @Test
    void anIndentedBlockIsLeftAsWritten() {
        final String block = "    EDG-8 `alpha/01-first.md`\n    EDG-9";
        assertThat(blockPage(block)).isEqualTo(linkedBlock(block));
    }

    /// Each path of the Documents line links to its file under the day folder, and a backticked document elsewhere
    /// does too; a backticked path that is no document stays as written.
    @Test
    void aDocumentPathLinksToItsFileUnderTheDayFolder() {
        final String page = TaskPages.markdown(TASK, DAY, LinearTickets.Site.NONE);
        assertThat(page).contains("- **Documents:** [`alpha/01-first.md`](file:///scratchpad/day/alpha/01-first.md), "
                + "[`alpha/work/a spec.md`](file:///scratchpad/day/alpha/work/a%20spec.md)\n");
        assertThat(page).contains("read [`alpha/01-first.md`](file:///scratchpad/day/alpha/01-first.md) again, "
                + "not `alpha/other.md`\n");
    }

    /// With no workspace the ids stay as written, and with no day folder the paths do; with neither the page is the
    /// task's section unchanged.
    @Test
    void noWorkspaceOrNoDayFolderLeavesItsLinksOut() {
        assertThat(TaskPages.markdown(TASK, null, LinearTickets.Site.NONE)).isEqualTo(TASK.markdown());
        assertThat(TaskPages.markdown(TASK, DAY, LinearTickets.Site.NONE)).isEqualTo("""
                #### 57. EDG-1212: read-only config (Miguel, 2026-11-02)

                - **Ticket:** OPS-7
                - **State:** open, see EDG-99, OPS-7 and UTF-8, not `EDG-5` nor [EDG-6](https://example.com/EDG-6)
                - **Documents:** [`alpha/01-first.md`](file:///scratchpad/day/alpha/01-first.md), \
                [`alpha/work/a spec.md`](file:///scratchpad/day/alpha/work/a%20spec.md)
                - **Next:** read [`alpha/01-first.md`](file:///scratchpad/day/alpha/01-first.md) again, \
                not `alpha/other.md`

                ```
                EDG-7 `alpha/01-first.md`
                ```""");
        assertThat(TaskPages.markdown(TASK, null, ACME)).isEqualTo("""
                #### 57. [EDG-1212](https://linear.app/acme/issue/EDG-1212): read-only config (Miguel, 2026-11-02)

                - **Ticket:** [OPS-7](https://linear.app/acme/issue/OPS-7)
                - **State:** open, see [EDG-99](https://linear.app/acme/issue/EDG-99), \
                [OPS-7](https://linear.app/acme/issue/OPS-7) and UTF-8, not `EDG-5` nor \
                [EDG-6](https://example.com/EDG-6)
                - **Documents:** `alpha/01-first.md`, `alpha/work/a spec.md`
                - **Next:** read `alpha/01-first.md` again, not `alpha/other.md`

                ```
                EDG-7 `alpha/01-first.md`
                ```""");
        final LinearTickets.Site noWorkspace = new LinearTickets.Site("", Set.of("EDG"));
        assertThat(TaskPages.markdown(TASK, null, noWorkspace)).isEqualTo(TASK.markdown());
    }

    /// The page of a task whose section ends with `block`, with both its ticket and its document linkable.
    private static String blockPage(final String block) {
        return TaskPages.markdown(task(section(block)), DAY, ACME);
    }

    /// The page [#blockPage] should give: the heading's ticket and the Documents line linked, `block` as written.
    private static String linkedBlock(final String block) {
        return section(block)
                .replace("EDG-1:", "[EDG-1](https://linear.app/acme/issue/EDG-1):")
                .replace("**Documents:** `alpha/01-first.md`",
                        "**Documents:** [`alpha/01-first.md`](file:///scratchpad/day/alpha/01-first.md)")
                .stripTrailing();
    }

    /// A task section whose Documents line names `alpha/01-first.md` and which ends with `block` after a paragraph.
    private static String section(final String block) {
        return "#### 58. EDG-1: blocks\n\n- **Documents:** `alpha/01-first.md`\n\nNotes.\n\n" + block + "\n";
    }

    /// The first task of an index whose Open part holds `section` under one topic.
    private static LogbookTask task(final String section) {
        return IndexParser.parse("/scratchpad/day/INDEX.md", "## Open\n\n### `alpha/`: x\n\n" + section)
                .tasks().getFirst();
    }
}
