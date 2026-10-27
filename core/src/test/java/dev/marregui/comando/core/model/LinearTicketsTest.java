package dev.marregui.comando.core.model;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LinearTicketsTest {

    @Test
    void theWorkspaceAndTeamsAreLearntFromLinearsLinksAndNothingElse() {
        LinearTickets.Site none = LinearTickets.Site.NONE;
        assertThat(LinearTickets.learn(none, "ENG-1125 and UTF-8")).isSameAs(none);
        LinearTickets.Site site = LinearTickets.learn(none, "see https://linear.app/acme/issue/ENG-1125/fix-the-browse and done");
        assertThat(site).isEqualTo(new LinearTickets.Site("acme", Set.of("ENG")));
        assertThat(site.known()).isTrue();
        assertThat(LinearTickets.learn(site, "https://linear.app/acme/issue/OPS-7")).isEqualTo(new LinearTickets.Site("acme", Set.of("ENG", "OPS")));
        assertThat(LinearTickets.learn(site, "nothing new")).isSameAs(site);
    }

    @Test
    void theTicketsOfTheLastParagraphComeFirstThenTheOthersNewestFirstEachOnce() {
        LinearTickets.Site site = new LinearTickets.Site("acme", Set.of("ENG", "OPS"));
        String text = "ENG-1 then ENG-2, UTF-8 and ISO-8859, ABC-9\n\nmore on ENG-2 and OPS-3\n  \nnow ENG-1125 with ENG-7 (ENG-1125 again)";
        assertThat(LinearTickets.in(text, site, 9)).extracting(LinearTickets.Ticket::id)
                .containsExactly("ENG-1125", "ENG-7", "OPS-3", "ENG-2", "ENG-1");
        assertThat(LinearTickets.in(text, site, 2)).extracting(LinearTickets.Ticket::id).containsExactly("ENG-1125", "ENG-7");
        assertThat(LinearTickets.in(text, site, 1).getFirst().url()).isEqualTo("https://linear.app/acme/issue/ENG-1125");
        assertThat(LinearTickets.in("a path /x/ENG-4 and xENG-5", site, 9)).as("only whole words").isEmpty();
        assertThat(LinearTickets.in(text, LinearTickets.Site.NONE, 9)).as("nothing learnt, nothing linked").isEmpty();
    }
}
