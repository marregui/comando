package dev.marregui.comando.frontear.sim;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.frontear.mqtt.Packet;

import static org.assertj.core.api.Assertions.assertThat;

/// The walks a message can make over links with OUT and IN filters, and the oracle that checks arrivals against
/// them, on hand-made records: each check flags the failure it exists for and lets through what the walks allow.
class PathsTest {

    private static final Ledger.Link AB = new Ledger.Link("ab", "a", "b");
    private static final Ledger.Link BA = new Ledger.Link("ba", "b", "a");
    private static final Ledger.Link BC = new Ledger.Link("bc", "b", "c");
    private static final List<String> ALL = List.of("sim/#");

    /// A walk stops at the hop limit and never takes the link it just took, either way: what the IN side brought in
    /// the OUT side does not send back, and the IN side does not fetch what the OUT side sent.
    @Test
    void aWalkStopsAtTheHopLimitAndNeverTakesTheSameLinkTwiceInARow() {
        final Paths paths = new Paths(2, List.of(AB, BA), Map.of("ab", ALL, "ba", ALL), Map.of("ab", ALL));
        assertThat(paths.walks("a", "sim/0")).extracting(Paths.Walk::toString)
                .containsExactly("local", "ab>", "ab>ba>");
        assertThat(paths.walks("b", "sim/0")).extracting(Paths.Walk::toString)
                .containsExactly("local", "ab<", "ba>", "ba>ab>");
        assertThat(paths.walks("b", "sim/0")).extracting(Paths.Walk::end).containsExactly("b", "a", "a", "b");
        assertThat(new Paths(3, List.of(AB, BA), Map.of("ab", ALL, "ba", ALL), Map.of()).walks("a", "sim/0"))
                .extracting(Paths.Walk::toString).containsExactly("local", "ab>", "ab>ba>", "ab>ba>ab>");
    }

    /// A side carries only the topics its filters match; a link without filters on a side carries nothing that way.
    @Test
    void aSideCarriesOnlyWhatItsFiltersMatch() {
        final Paths paths = new Paths(3, List.of(AB, BC), Map.of("ab", List.of("sim/0")),
                Map.of("ab", List.of("sim/+"), "bc", List.of("simb/#")));
        assertThat(paths.walks("a", "sim/0")).extracting(Paths.Walk::toString).containsExactly("local", "ab>");
        assertThat(paths.walks("a", "sim/1")).extracting(Paths.Walk::toString).containsExactly("local");
        assertThat(paths.walks("b", "sim/1")).extracting(Paths.Walk::toString).containsExactly("local", "ab<");
        assertThat(paths.walks("c", "simb/1")).extracting(Paths.Walk::toString).containsExactly("local", "bc<");
        assertThat(paths.walks("c", "sim/1")).extracting(Paths.Walk::toString).containsExactly("local");
    }

    /// Counting every walk: a copy no walk of its hop count gives, a copy more than its walks, a copy past the hop
    /// limit and a copy a walk owes that never came are each flagged.
    @Test
    void everyWalkIsCountedWhenExact() {
        final Ledger ledger = ledger(true);
        final Ledger.Sink b = ledger.sink("b", "all", ALL);
        final Ledger.Sink c = ledger.sink("c", "all", ALL);
        publish(ledger, "a", "sim/0", Packet.SUCCESS);
        publish(ledger, "a", "sim/0", Packet.SUCCESS);
        b.arrived(new Ledger.Arrival("a", 0, 1));
        b.arrived(new Ledger.Arrival("a", 0, 2));
        c.arrived(new Ledger.Arrival("a", 0, 2));
        c.arrived(new Ledger.Arrival("a", 0, 2));
        c.arrived(new Ledger.Arrival("a", 0, 3));
        assertThat(ledger.missing()).isEqualTo(2);
        assertThat(ledger.violations()).containsExactly(
                "lost: a 1 topic=sim/0 hops=1 arrived at b/all 0 of 1 times [paths=[ab>]]",
                "lost: a 1 topic=sim/0 hops=2 arrived at c/all 0 of 1 times [paths=[ab>bc>]]",
                "hops: Arrival[source=a, seq=0, hops=3] at c/all made more hops than the limit [hopLimit=2]",
                "path: a 0 topic=sim/0 arrived at b/all with hops=2, which no path admits [paths=[ab>]]",
                "path: a 0 topic=sim/0 arrived at c/all 2 times with hops=2, more than its paths [paths=[ab>bc>]]");
    }

    /// Under faults only walks of OUT sides are owed, since an IN side is a client session a cut ends, but a copy
    /// over an IN side must still be one some walk gives, and duplicates of a rewind are allowed.
    @Test
    void onlyOutWalksAreOwedUnderFaults() {
        final Ledger ledger = ledger(false);
        final Ledger.Sink a = ledger.sink("a", "all", ALL);
        final Ledger.Sink c = ledger.sink("c", "all", ALL);
        publish(ledger, "c", "sim/0", Packet.SUCCESS);
        publish(ledger, "a", "sim/0", Packet.SUCCESS);
        publish(ledger, "c", "sim/0", Packet.SUCCESS);
        c.arrived(new Ledger.Arrival("c", 0, 0));
        c.arrived(new Ledger.Arrival("c", 1, 0));
        c.arrived(new Ledger.Arrival("a", 0, 2));
        c.arrived(new Ledger.Arrival("a", 0, 2));
        a.arrived(new Ledger.Arrival("a", 0, 0));
        a.arrived(new Ledger.Arrival("c", 1, 2));
        a.arrived(new Ledger.Arrival("c", 0, 1));
        assertThat(ledger.missing()).isZero();
        assertThat(ledger.violations()).containsExactly(
                "path: c 0 topic=sim/0 arrived at a/all with hops=1, which no path admits [paths=[bc<ab<]]");
    }

    /// Over one group of walks first arrivals keep publish order, unless a link of the group counted a refusal.
    @Test
    void firstArrivalsKeepPublishOrderPerGroupOfWalks() {
        final Ledger ledger = ledger(false);
        final Ledger.Sink c = ledger.sink("c", "all", ALL);
        for (int i = 0; i < 3; i++) {
            publish(ledger, "a", "sim/0", Packet.SUCCESS);
        }
        c.arrived(new Ledger.Arrival("a", 1, 2));
        c.arrived(new Ledger.Arrival("a", 0, 2));
        c.arrived(new Ledger.Arrival("a", 2, 2));
        assertThat(ledger.violations()).containsExactly(
                "order: a 0 first arrived at c/all after a 1 over [a 2 ab>bc>]",
                "overtaking: a 1 arrived at c/all before a 0, published earlier, over [a 2 ab>bc>]");
        ledger.reordered("bc", 1);
        assertThat(ledger.violations()).isEmpty();
    }

    /// The chain a to b to c, hop limit 2, both links carrying every topic out and in.
    private static Ledger ledger(final boolean exact) {
        final Ledger ledger = new Ledger(new Paths(2, List.of(AB, BC), Map.of("ab", ALL, "bc", ALL),
                Map.of("ab", ALL, "bc", ALL)), exact);
        ledger.link(AB.id(), AB.from(), AB.to());
        ledger.link(BC.id(), BC.from(), BC.to());
        return ledger;
    }

    private static void publish(final Ledger ledger, final String source, final String topic, final int code) {
        ledger.acked(source, ledger.publish(source, topic, Set.of()), code);
    }
}
