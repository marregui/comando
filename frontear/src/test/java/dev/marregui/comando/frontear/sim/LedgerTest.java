package dev.marregui.comando.frontear.sim;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.frontear.mqtt.Packet;

import static org.assertj.core.api.Assertions.assertThat;

/// The simulation's oracle on hand-made records: each check flags the failure it exists for, and lets through what
/// the contract allows (duplicates after a rewind, a refusal overtaken and counted, a message the far subscriber's
/// filters do not take).
class LedgerTest {

    /// A message acknowledged 0x00 for a covered topic that never reaches a matching subscriber of the far node is
    /// lost; one not acknowledged 0x00, not covered, or outside the subscriber's filters is not owed to it.
    @Test
    void aCoveredAcknowledgedMessageThatNeverArrivesIsLost() {
        final Ledger ledger = new Ledger(2);
        ledger.link("ab", "a", "b");
        final Ledger.Sink all = ledger.sink("b", "all", List.of("sim/#"));
        final Ledger.Sink some = ledger.sink("b", "some", List.of("sim/1"));
        publish(ledger, "a", "sim/0", Set.of("ab"), Packet.SUCCESS);
        publish(ledger, "a", "sim/0", Set.of("ab"), Packet.QUOTA_EXCEEDED);
        publish(ledger, "a", "sim/0", Set.of(), Packet.SUCCESS);
        publish(ledger, "a", "sim/0", Set.of("ab"), Ledger.NO_ACK);
        assertThat(ledger.missing()).isEqualTo(1);
        assertThat(ledger.violations()).containsExactly("lost: a 0 topic=sim/0 link=ab never arrived at b/all");
        all.arrived(new Ledger.Arrival("a", 0, 1));
        assertThat(ledger.missing()).isZero();
        assertThat(ledger.violations()).isEmpty();
        assertThat(some.size()).isZero();
    }

    /// A message that first arrives after a later one of the same link breaks order, and the later one overtook it.
    @Test
    void anOutOfOrderFirstArrivalIsFlagged() {
        final Ledger ledger = new Ledger(1);
        ledger.link("ab", "a", "b");
        final Ledger.Sink b = ledger.sink("b", "all", List.of("sim/#"));
        for (int i = 0; i < 3; i++) {
            publish(ledger, "a", "sim/0", Set.of("ab"), Packet.SUCCESS);
        }
        arrive(b, "a", 1, 0, 2, 1);
        assertThat(ledger.violations()).containsExactly(
                "order: a 1 first arrived at b/all after a 2 over ab",
                "overtaking: a 2 arrived at b/all before a 1, published earlier, over ab");
    }

    /// A message that arrives while an earlier one that must arrive never does overtook it, and the earlier is lost.
    @Test
    void aGapBeforeALaterArrivalIsOvertaking() {
        final Ledger ledger = new Ledger(1);
        ledger.link("ab", "a", "b");
        final Ledger.Sink b = ledger.sink("b", "all", List.of("sim/#"));
        for (int i = 0; i < 3; i++) {
            publish(ledger, "a", "sim/0", Set.of("ab"), Packet.SUCCESS);
        }
        arrive(b, "a", 1, 0, 2);
        assertThat(ledger.violations()).containsExactly(
                "lost: a 1 topic=sim/0 link=ab never arrived at b/all",
                "overtaking: a 2 arrived at b/all before a 1, published earlier, over ab");
    }

    /// A rewind after a reconnect sends again from the cursor: the duplicates it brings keep first arrivals in order.
    @Test
    void duplicatesAfterARewindAreAllowed() {
        final Ledger ledger = new Ledger(1);
        ledger.link("ab", "a", "b");
        final Ledger.Sink b = ledger.sink("b", "all", List.of("sim/#"));
        for (int i = 0; i < 4; i++) {
            publish(ledger, "a", "sim/0", Set.of("ab"), Packet.SUCCESS);
        }
        arrive(b, "a", 1, 0, 1, 2, 1, 2, 3, 3);
        assertThat(ledger.violations()).isEmpty();
    }

    /// A link whose peer refused a message and took a later one first counts it in `reordered`: its order is not
    /// checked, and its messages must still all arrive.
    @Test
    void aCountedRefusalExemptsTheLinkFromOrderOnly() {
        final Ledger ledger = new Ledger(1);
        ledger.link("ab", "a", "b");
        final Ledger.Sink b = ledger.sink("b", "all", List.of("sim/#"));
        for (int i = 0; i < 4; i++) {
            publish(ledger, "a", "sim/0", Set.of("ab"), Packet.SUCCESS);
        }
        arrive(b, "a", 1, 0, 2, 1);
        ledger.reordered("ab", 1);
        assertThat(ledger.violations()).containsExactly("lost: a 3 topic=sim/0 link=ab never arrived at b/all");
    }

    /// An arrival past the hop limit, one from another node without a hop count, and one never published are flagged;
    /// a subscriber left out of the checks is not looked at.
    @Test
    void hopsAndPhantomsAreFlaggedOnCheckedSubscribersOnly() {
        final Ledger ledger = new Ledger(2);
        ledger.link("ab", "a", "b");
        final Ledger.Sink b = ledger.sink("b", "all", List.of("sim/#"));
        final Ledger.Sink off = ledger.sink("b", "off", List.of("sim/#"));
        publish(ledger, "a", "sim/0", Set.of(), Packet.SUCCESS);
        b.arrived(new Ledger.Arrival("a", 0, 3));
        b.arrived(new Ledger.Arrival("a", 0, 0));
        b.arrived(new Ledger.Arrival("a", 7, 1));
        b.arrived(new Ledger.Arrival("?", -1, 1));
        off.arrived(new Ledger.Arrival("c", 0, 9));
        off.unchecked();
        assertThat(ledger.violations()).containsExactly(
                "hops: Arrival[source=a, seq=0, hops=3] at b/all made more hops than the limit [hopLimit=2]",
                "hops: Arrival[source=a, seq=0, hops=0] at b/all came from another node without a hop count",
                "phantom: Arrival[source=a, seq=7, hops=1] at b/all was never published",
                "phantom: Arrival[source=?, seq=-1, hops=1] at b/all was never published");
    }

    /// An arrival is read from the head of the payload and the hop count property, whatever padding follows.
    @Test
    void anArrivalIsReadFromTheMessage() {
        final Packet.Publish m = new Packet.Publish("sim/0", "c 42 xxxx".getBytes(StandardCharsets.US_ASCII), 1,
                false, false, 0, null, null, new Packet.Meta(-1, -1, null,
                        List.of(new Packet.UserProperty("frontear-hops", "2"))));
        assertThat(Ledger.Arrival.of(m)).isEqualTo(new Ledger.Arrival("c", 42, 2));
        assertThat(Ledger.Arrival.of(new Packet.Publish("sim/0", "garbage".getBytes(StandardCharsets.US_ASCII), 1,
                false, false, 0, null, null))).isEqualTo(new Ledger.Arrival("?", -1, 0));
    }

    private static void publish(final Ledger ledger, final String source, final String topic, final Set<String> links,
            final int code) {
        ledger.acked(source, ledger.publish(source, topic, links), code);
    }

    private static void arrive(final Ledger.Sink sink, final String source, final int hops, final long... seqs) {
        for (final long seq : seqs) {
            sink.arrived(new Ledger.Arrival(source, seq, hops));
        }
    }
}
