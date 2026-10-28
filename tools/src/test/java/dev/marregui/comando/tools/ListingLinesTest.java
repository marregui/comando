package dev.marregui.comando.tools;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// [ListingLines] removes an entry's own Markdown lines while reporting remaining whole-name references.
///
/// - List items remove their indented continuation lines and table rows remove as one line.
/// - Folder paths require a trailing slash and nested paths do not own a folder row.
/// - Fenced code, word fragments, and line endings retain their original meaning.
class ListingLinesTest {

    private static final String ALPHA = """
            # 2026-10-18

            ## To read

            1. `READ-01-ATLAS-OCT-18.md`: Atlas P0 step 1.
               Thirteen incidents look real.
            2. `READ-02-OTHER-OCT-18.md`: something else.

            ## 1. comando Atlas

            - **State:** step 1 done: candidates in READ-01-ATLAS-OCT-18.md.

            | Artefact | Holds |
            |---|---|
            | READ-01-ATLAS-OCT-18.md | result, findings |
            | `work/` | `MINE.py` (the miner), `CANDIDATES.json` |
            | [notes.md](notes.md#top) | notes |
            """;

    /// [ListingLines#without] removes a file's numbered item, continuation, and table row while reporting the still
    /// retained prose mention by its new one-based line number.
    @Test
    void aFileLosesItsListItemWithTheLinesUnderItAndItsTableRowAndTheProseThatNamesItIsReported() {
        ListingLines.Result r = ListingLines.without(ALPHA, List.of("READ-01-ATLAS-OCT-18.md"));
        assertThat(r.removed()).as("the item, its continuation line and the table row").isEqualTo(3);
        assertThat(r.text()).doesNotContain("1. `READ-01", "Thirteen incidents", "| READ-01-ATLAS-OCT-18.md |")
                .contains("2. `READ-02-OTHER-OCT-18.md`", "candidates in READ-01-ATLAS-OCT-18.md.", "| `work/` |");
        assertThat(r.mentions()).as("the State line, numbered in the text returned").containsExactly(9);
        assertThat(r.text().split("\n")[8]).startsWith("- **State:**");
    }

    /// [ListingLines#without] removes a folder's own table row but keeps it unchanged when only a file below that
    /// folder is requested, reporting the shared row as a mention instead.
    @Test
    void aFolderLosesItsRowAndAFileInsideItIsOnlyReportedFromTheSharedRow() {
        ListingLines.Result folder = ListingLines.without(ALPHA, List.of("work/"));
        assertThat(folder.removed()).isEqualTo(1);
        assertThat(folder.text()).doesNotContain("`work/`").contains("| READ-01-ATLAS-OCT-18.md |");

        ListingLines.Result nested = ListingLines.without(ALPHA, List.of("work/MINE.py"));
        assertThat(nested.removed()).as("named in a row about the folder, not its own").isZero();
        assertThat(nested.text()).isEqualTo(ALPHA);
        assertThat(nested.mentions()).containsExactly(16);
    }

    /// [ListingLines#without] treats a Markdown link target as the entry name, while an absent target leaves all text
    /// and mentions unchanged.
    @Test
    void aLinkNamesTheFileItPointsToAndNothingElseIsTouched() {
        ListingLines.Result r = ListingLines.without(ALPHA, List.of("notes.md"));
        assertThat(r.removed()).isEqualTo(1);
        assertThat(r.text()).doesNotContain("[notes.md]");
        ListingLines.Result none = ListingLines.without(ALPHA, List.of("missing.md"));
        assertThat(none.removed()).isZero();
        assertThat(none.text()).isEqualTo(ALPHA);
        assertThat(none.mentions()).isEmpty();
    }

    /// [ListingLines#without] requires `work/` to own a list item, so prose beginning with the ordinary word `work`
    /// and its continuation are retained.
    @Test
    void aFolderIsNamedWithItsSlashSoAWordLikeItsNameStays() {
        String text = "- work in progress: the miner\n  still running\n- `work/`: scripts\n";
        ListingLines.Result r = ListingLines.without(text, List.of("work/"));
        assertThat(r.text()).isEqualTo("- work in progress: the miner\n  still running\n");
        assertThat(r.removed()).isEqualTo(1);
    }

    /// [ListingLines#without] removes the requested CRLF list line and preserves the remaining line's CRLF ending.
    @Test
    void windowsLineEndsAreReadAndKept() {
        ListingLines.Result r = ListingLines.without("1. `a.md`: x\r\n2. `b.md`: y\r\n", List.of("a.md"));
        assertThat(r.removed()).isEqualTo(1);
        assertThat(r.text()).isEqualTo("2. `b.md`: y\r\n");
    }

    /// [ListingLines#without] never treats a fenced-code line as owned and reports only whole `a.md` names, excluding
    /// `data.md` and `a.md.bak` fragments.
    @Test
    void aCodeBlockIsNeverAnEntrysOwnLineAndAWholeNameIsAMention() {
        String text = "```\n- a.md\n```\n- see data.md and a.md.bak\n- then a.md.\n";
        ListingLines.Result r = ListingLines.without(text, List.of("a.md"));
        assertThat(r.removed()).as("the fenced line stays").isZero();
        assertThat(r.mentions()).as("the fenced line and the sentence; not data.md, not a.md.bak").containsExactly(2, 5);
    }

    /// [ListingLines#subject] extracts the first list or table element after Markdown marks, but returns empty for a
    /// rule row, prose, and heading.
    @Test
    void theSubjectOfALineIsItsFirstElement() {
        assertThat(ListingLines.subject("- `a.md`: x")).isEqualTo("a.md");
        assertThat(ListingLines.subject("* ./b.md, then")).isEqualTo("b.md");
        assertThat(ListingLines.subject("12) work/: scripts")).isEqualTo("work/");
        assertThat(ListingLines.subject("| **c.md** | d |")).isEqualTo("c.md");
        assertThat(ListingLines.subject("| c.md and more | d |")).as("a cell names one thing or nothing").isEqualTo("c.md and more");
        assertThat(ListingLines.subject("|---|---|")).isEmpty();
        assertThat(ListingLines.subject("Prose about a.md")).isEmpty();
        assertThat(ListingLines.subject("## 1. a.md")).isEmpty();
    }
}
