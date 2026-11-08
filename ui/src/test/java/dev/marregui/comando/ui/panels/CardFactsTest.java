package dev.marregui.comando.ui.panels;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.state.SessionSubject;
import dev.marregui.comando.ui.kit.format.Labels;

import static org.assertj.core.api.Assertions.assertThat;

/// [CardFacts] says what a session works on beside the folder it runs in.
/// - the Folder fact keeps the folder; Repository, Branch, Ticket and Also follow it
/// - a fact with nothing to say is left out
/// - a session with no writes has none of the four
/// - Copy details says the same on its lines
class CardFactsTest {

    private static final SessionState SESSION = SessionState.closed(SessionSpec.fresh("work", "/w"));

    /// A subject with a ticket and two other repositories gives the four facts, the others joined in order, after the
    /// folder and before the conversation.
    @Test
    void aSubjectGivesItsFactsAfterTheFolder() {
        final List<CardFacts.Fact> facts = facts(SESSION.withSubject(
                new SessionSubject("/src/edge", "feature/eng-1", "ENG-1", List.of("/src/one", "/src/two"))));
        assertThat(facts).containsSubsequence(
                new CardFacts.Fact("Folder", Labels.shortPath("/w")),
                new CardFacts.Fact("Repository", Labels.shortPath("/src/edge")),
                new CardFacts.Fact("Branch", "feature/eng-1"),
                new CardFacts.Fact("Ticket", "ENG-1"),
                new CardFacts.Fact("Also", Labels.shortPath("/src/one") + " · " + Labels.shortPath("/src/two")));
    }

    /// A branch that names no ticket and a session with one repository leave Ticket and Also out.
    @Test
    void aFactWithNothingToSayIsLeftOut() {
        assertThat(facts(SESSION.withSubject(new SessionSubject("/src/edge", "main", "", List.of()))))
                .extracting(CardFacts.Fact::label).contains("Repository", "Branch").doesNotContain("Ticket", "Also");
    }

    /// A session with no writes shows its folder and branch as one fact, as before, and none of the four.
    @Test
    void noWritesLeaveTheFolderAlone() {
        final List<CardFacts.Fact> facts = facts(SESSION.withBranch("main"));
        assertThat(facts).contains(new CardFacts.Fact("Folder", Labels.shortPath("/w") + " · main"));
        assertThat(facts).extracting(CardFacts.Fact::label).doesNotContain("Repository", "Branch", "Ticket", "Also");
    }

    /// Copy details carries the repository with its branch, the ticket and the others, full paths for a clipboard.
    @Test
    void copyDetailsNamesTheSubject() {
        final SessionSubject subject = new SessionSubject("/src/edge", "eng-1", "ENG-1", List.of("/src/one"));
        final String details = SessionRow.details(SESSION.withSubject(subject), 0L, ZoneOffset.UTC, "");
        assertThat(details).contains("Repository: /src/edge · eng-1\n", "Ticket: ENG-1\n", "Also: /src/one\n");
    }

    private static List<CardFacts.Fact> facts(final SessionState session) {
        return CardFacts.of(Optional.empty(), Optional.of(session), "", "", 0L, "").facts();
    }
}
