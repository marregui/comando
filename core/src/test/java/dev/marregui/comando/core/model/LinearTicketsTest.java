package dev.marregui.comando.core.model;

import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [LinearTickets] learns trusted workspaces and teams from URLs, then links only their whole ticket identifiers.
///
/// - untrusted identifier-shaped text is ignored
/// - the final paragraph has priority
/// - each ticket appears once up to the requested limit
class LinearTicketsTest {

    /// Only valid workspace issue URLs establish a workspace and team; later trusted URLs add teams while unrelated
    /// text preserves the existing site.
    @Test
    void theWorkspaceAndTeamsAreLearntFromLinearsLinksAndNothingElse() {
        final LinearTickets.Site none = LinearTickets.Site.NONE;
        assertThat(LinearTickets.learn(none, "ENG-1125 and UTF-8")).isSameAs(none);
        final LinearTickets.Site site = LinearTickets.learn(none, "see https://linear.app/acme/issue/ENG-1125/fix-the-browse and done");
        assertThat(site).isEqualTo(new LinearTickets.Site("acme", Set.of("ENG")));
        assertThat(site.known()).isTrue();
        assertThat(LinearTickets.learn(site, "https://linear.app/acme/issue/OPS-7")).isEqualTo(new LinearTickets.Site("acme", Set.of("ENG", "OPS")));
        assertThat(LinearTickets.learn(site, "nothing new")).isSameAs(site);
    }

    /// Known-team tickets from the final paragraph lead the unique result, followed by earlier tickets newest first,
    /// while word fragments and unknown sites yield none.
    @Test
    void theTicketsOfTheLastParagraphComeFirstThenTheOthersNewestFirstEachOnce() {
        final LinearTickets.Site site = new LinearTickets.Site("acme", Set.of("ENG", "OPS"));
        final String text = "ENG-1 then ENG-2, UTF-8 and ISO-8859, ABC-9\n\nmore on ENG-2 and OPS-3\n  \nnow ENG-1125 with ENG-7 (ENG-1125 again)";
        assertThat(LinearTickets.in(text, site, 9)).extracting(LinearTickets.Ticket::id)
                .containsExactly("ENG-1125", "ENG-7", "OPS-3", "ENG-2", "ENG-1");
        assertThat(LinearTickets.in(text, site, 2)).extracting(LinearTickets.Ticket::id).containsExactly("ENG-1125", "ENG-7");
        assertThat(LinearTickets.in(text, site, 1).getFirst().url()).isEqualTo("https://linear.app/acme/issue/ENG-1125");
        assertThat(LinearTickets.in("a path /x/ENG-4 and xENG-5", site, 9)).as("only whole words").isEmpty();
        assertThat(LinearTickets.in(text, LinearTickets.Site.NONE, 9)).as("nothing learnt, nothing linked").isEmpty();
    }
}
