package dev.marregui.comando.core.logbook;

import java.nio.file.Path;
import java.util.List;

import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.files.Mapped;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/// [IndexParser] reads the open tasks of a scratchpad index, tolerating what sessions write by hand.
/// - Only the tasks under `## Open` count, in order, each with its topic folder, its State, Next and Ask lines, the paths
///   in backticks of its Documents and the decisions it holds for the user.
/// - A key is a bullet or a bare line; its value continues until the next key, a heading or a blank line.
/// - Needs you gives one decision per sub-item, or its inline text, and nothing when it says none.
/// - A task that waits on Miguel or holds a decision needs the user; the others do not.
/// - A heading without a number, a heading in a code fence, `## Waiting on Miguel` and `## Scheduled reports` are no
///   tasks; an unknown key is ignored and a missing one leaves its field empty.
/// - A heading's closing parenthetical that names who asked or when the task started is its attribution, left out of
///   the task's name; one that names neither stays in the name.
/// - A task's tickets are the ids of its Ticket line, then those that open its heading, each once.
class IndexParserTest {

    private static Logbook logbook;

    @BeforeAll
    static void parseTheSample() throws Exception {
        final Path sample = Path.of(IndexParserTest.class.getResource("/logbook/index-sample.md").toURI());
        logbook = IndexParser.parse("/scratchpad/day/INDEX.md", Mapped.text(sample));
    }

    /// The open tasks come in the index's order and none of the others appears.
    @Test
    void onlyTheOpenTasksComeInOrder() {
        assertThat(logbook.index()).isEqualTo("/scratchpad/day/INDEX.md");
        assertThat(logbook.problem()).isEmpty();
        assertThat(logbook.tasks()).extracting(LogbookTask::number).containsExactly(1, 2, 3, 5, 6, 7);
    }

    /// Each task carries the folder of the topic heading above it.
    @Test
    void aTaskCarriesItsTopicFolder() {
        assertThat(logbook.tasks()).extracting(LogbookTask::topic)
                .containsExactly("alpha/", "alpha/", "alpha/", "beta/", "beta/", "beta/");
    }

    /// A heading's title is the text after its number.
    @Test
    void theTitleIsTheTextAfterTheNumber() {
        assertThat(task(1).title()).isEqualTo("First synthetic task (Ticket-1)");
    }

    /// Bullet keys give State, Next and Documents; a value that wraps runs on as one line, and every path in backticks
    /// is a document, as written.
    @Test
    void bulletKeysGiveTheirFieldsAndAWrappedValueRunsOn() {
        final LogbookTask t = task(1);
        assertThat(t.state()).isEqualTo("open, two writers running");
        assertThat(t.next()).isEqualTo("write the report, then run the gates on the merged tree");
        assertThat(t.ask()).isEmpty();
        assertThat(t.documents()).containsExactly("alpha/01-first.md", "alpha/work/first-spec.md", "alpha/work/{a,b}-report.md");
        assertThat(t.needsYou()).isEmpty();
    }

    /// Bare keys work as bullets do; a value goes on over the next lines and ends at a blank line, and the Documents
    /// of a bare key may start on the line below it.
    @Test
    void aBareKeyTakesTheLinesBelowItAsItsValue() {
        final LogbookTask t = task(2);
        assertThat(t.state()).isEqualTo("waiting on Miguel: pick a flavour");
        assertThat(t.ask()).isEqualTo("which flavour of synthetic task to build and why it matters");
        assertThat(t.next()).isEqualTo("build it");
        assertThat(t.documents()).containsExactly("alpha/02-second.md", "alpha/03-third.md");
    }

    /// Prose after a blank line is not part of a value, and stays in the task's markdown.
    @Test
    void proseAfterABlankLineBelongsToNoValue() {
        assertThat(task(2).next()).doesNotContain("Prose");
        assertThat(task(2).markdown()).contains("Prose after a blank line");
    }

    /// Needs you with sub-items gives one decision each, a wrapped sub-item as one decision.
    @Test
    void needsYouGivesOneDecisionPerSubItem() {
        final LogbookTask t = task(3);
        assertThat(t.needsYou()).containsExactly("choose the flavour (A or B)", "approve the plan, which is long and wraps onto a second line");
        assertThat(t.next()).isEqualTo("continue once decided");
    }

    /// Needs you with its text inline and no sub-items gives that text as the one decision.
    @Test
    void needsYouWithInlineTextIsOneDecision() {
        assertThat(task(5).needsYou()).containsExactly("approve the budget");
        assertThat(task(5).documents()).isEmpty();
    }

    /// Needs you that says none holds no decision.
    @Test
    void needsYouThatSaysNoneHoldsNoDecision() {
        assertThat(task(6).needsYou()).isEmpty();
    }

    /// A plain key wins over one qualified in the heading's style, whatever their order; a qualified key fills a field
    /// whose plain key is absent.
    @Test
    void aPlainKeyWinsOverAQualifiedOne() {
        assertThat(task(6).state()).isEqualTo("plain state wins over the qualified one above");
        assertThat(task(6).next()).isEqualTo("the plain next");
    }

    /// A task that waits on Miguel by its State, or holds a decision, needs the user; the others do not, and
    /// the Logbook lists them in the index's order.
    @Test
    void tasksThatWaitOnTheUserAreTheOnesNeedingHim() {
        assertThat(logbook.needingUser()).extracting(LogbookTask::number).containsExactly(2, 3, 5);
    }

    /// A task with only a heading and prose has empty fields and its section as markdown.
    @Test
    void aBareTaskHasEmptyFields() {
        final LogbookTask t = task(7);
        assertThat(t.state()).isEmpty();
        assertThat(t.next()).isEmpty();
        assertThat(t.ask()).isEmpty();
        assertThat(t.documents()).isEmpty();
        assertThat(t.markdown()).isEqualTo("#### 7. Sixth synthetic task, a bare section\n\nOnly a heading and prose.");
    }

    /// The markdown is the section from its heading to the next heading, trailing blank lines left out, and a fence
    /// in it stays.
    @Test
    void theMarkdownIsTheWholeSection() {
        assertThat(task(1).markdown()).startsWith("#### 1. First synthetic task (Ticket-1)\n\n- **Ticket:** TICKET-1")
                .endsWith("- **Hold:** an unknown key is ignored");
        assertThat(task(3).markdown()).endsWith("```\n#### 4. A heading inside a code fence is no task\n```");
    }

    /// An index without `## Open` has no tasks, and text with Windows line ends reads as with Unix ones.
    @Test
    void anIndexWithoutOpenHasNoTasksAndLineEndsDoNotMatter() {
        assertThat(IndexParser.parse("i", "# Scratchpad\n\n## Folders\n\n#### 1. Not open\n").tasks()).isEmpty();
        final Logbook crlf = IndexParser.parse("i", "## Open\r\n\r\n### `t/`: x\r\n\r\n#### 4. Task\r\n\r\n- **State:** open\r\n");
        assertThat(crlf.tasks()).extracting(LogbookTask::number, LogbookTask::topic, LogbookTask::state)
                .containsExactly(tuple(4, "t/", "open"));
    }

    /// A topic heading without backticks names its topic by the text before the colon.
    @Test
    void aTopicWithoutBackticksIsTheTextBeforeTheColon() {
        final Logbook l = IndexParser.parse("i", "## Open\n\n### misc: odds and ends\n\n#### 2. Task\n");
        assertThat(l.tasks()).extracting(LogbookTask::topic).isEqualTo(List.of("misc"));
    }

    /// An attribution of a name and a date gives who asked, when the task started and the attribution as written,
    /// and the name is the title before it; extra parts, backticks and text after the date do not matter.
    @Test
    void aNameAndADateMakeTheAttribution() {
        assertThat(List.of(
                headed("EDG-1227: Sam's review of the EDG-1217 plan, 34 findings (Miguel, 2026-11-04)"),
                headed("Friction log batch 2026-11-08..10, second (Miguel, 2026-11-10 /reflect: \"Now\")"),
                headed("EDG-1232 follow-up: Martin's hivemq-edge #1804 + testsuite #1701 "
                        + "(Martin, `#C08JYBMQ821` ts `1794243728.342979`, 2026-11-09)"),
                headed("Some synthetic follow-up (Martin's ask, 2026-10-27)"),
                headed("EDG-1176: polling adapter stops after 10 poll timeouts (Dominic, `#edge-team`, 2026-10-26)")))
                .extracting(LogbookTask::name, LogbookTask::by, LogbookTask::started, LogbookTask::from)
                .containsExactly(
                        tuple("EDG-1227: Sam's review of the EDG-1217 plan, 34 findings", "Miguel", "2026-11-04",
                                "Miguel, 2026-11-04"),
                        tuple("Friction log batch 2026-11-08..10, second", "Miguel", "2026-11-10",
                                "Miguel, 2026-11-10 /reflect: \"Now\""),
                        tuple("EDG-1232 follow-up: Martin's hivemq-edge #1804 + testsuite #1701", "Martin",
                                "2026-11-09",
                                "Martin, `#C08JYBMQ821` ts `1794243728.342979`, 2026-11-09"),
                        tuple("Some synthetic follow-up", "Martin", "2026-10-27", "Martin's ask, 2026-10-27"),
                        tuple("EDG-1176: polling adapter stops after 10 poll timeouts", "Dominic", "2026-10-26",
                                "Dominic, `#edge-team`, 2026-10-26"));
    }

    /// Who asked is the first part that is a name, even before a later name, and a word that is no name
    /// (`parked`) is passed over.
    @Test
    void whoAskedIsTheFirstPartThatIsAName() {
        assertThat(List.of(
                headed("EDG-1212: read-only config on Kubernetes, clearer message "
                        + "(Dominic's question, Miguel, 2026-11-02)"),
                headed("Some synthetic export (Kenneth, OPINT-166; Miguel, 2026-11-02)"),
                headed("Recall metrics in QuestDB (parked, Miguel, 2026-10-27)")))
                .extracting(LogbookTask::name, LogbookTask::by, LogbookTask::started)
                .containsExactly(
                        tuple("EDG-1212: read-only config on Kubernetes, clearer message", "Dominic", "2026-11-02"),
                        tuple("Some synthetic export", "Kenneth", "2026-11-02"),
                        tuple("Recall metrics in QuestDB", "Miguel", "2026-10-27"));
    }

    /// A date that does not begin its part (`closed 2026-11-03`), ticket ids and lower-case words are neither a start
    /// nor a name: such a parenthetical, like a title without one, is no attribution and the name is the whole title.
    @Test
    void aParentheticalThatNamesNeitherIsPartOfTheName() {
        final List<String> titles = List.of(
                "Protocol pack and mesh demo (from task 2, closed 2026-11-03)",
                "Southbound design for Edge 2 (EDG-1211, supersedes EDG-813; EDG-1112)",
                "Nevsky plan (living plan)",
                "EDG-839 hot-reload plan v03: review ahead of Sam's ask",
                "First synthetic task (Ticket-1)");
        assertThat(titles.stream().map(IndexParserTest::headed).toList())
                .extracting(LogbookTask::name, LogbookTask::by, LogbookTask::started, LogbookTask::from)
                .containsExactly(titles.stream().map(t -> tuple(t, "", "", "")).toArray(Tuple[]::new));
    }

    /// A parenthetical that does not close the title is no attribution, the last one is, and a date alone is one.
    @Test
    void onlyTheParentheticalThatClosesTheTitleIsTheAttribution() {
        final LogbookTask t = headed("hivemq-edge2#122 (EDG-1148 + EDG-1146): open, waiting on approval (2026-11-04)");
        assertThat(t.name()).isEqualTo("hivemq-edge2#122 (EDG-1148 + EDG-1146): open, waiting on approval");
        assertThat(t.started()).isEqualTo("2026-11-04");
        assertThat(t.by()).isEmpty();
        assertThat(headed("Draft (Miguel, 2026-11-04) then more").from()).isEmpty();
    }

    /// The tickets are the ids of the Ticket line first, then those that open the heading, each once; an id inside
    /// the heading's text, or after its opening ids, is no ticket of the task.
    @Test
    void theTicketsAreTheTicketLineThenTheHeadingsOpeningIds() {
        final LogbookTask t = IndexParser.parse("i", """
                ## Open

                #### 57. EDG-1212, OPS-7: read-only config, see EDG-99 (Miguel, 2026-11-02)

                - **Ticket:** EDG-1300, [EDG-1212](https://linear.app/acme/issue/EDG-1212)
                - **State:** open
                """).tasks().getFirst();
        assertThat(t.tickets()).containsExactly("EDG-1300", "EDG-1212", "OPS-7");
        assertThat(headed("EDG-839 hot-reload plan v03: review ahead of Sam's ask").tickets())
                .containsExactly("EDG-839");
        assertThat(headed("Southbound design for Edge 2 (EDG-1211, supersedes EDG-813; EDG-1112)").tickets()).isEmpty();
        assertThat(task(1).tickets()).containsExactly("TICKET-1");
    }

    /// The task of an index whose only open task has the heading `title`.
    private static LogbookTask headed(final String title) {
        return IndexParser.parse("i", "## Open\n\n### `t/`: x\n\n#### 1. " + title + "\n\n- **State:** open\n")
                .tasks().getFirst();
    }

    private static LogbookTask task(final int number) {
        return logbook.tasks().stream().filter(t -> t.number() == number).findFirst().orElseThrow();
    }
}
