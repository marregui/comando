package dev.marregui.comando.ui.panels.lab;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.port.LabPort.Kind;
import dev.marregui.comando.core.port.LabPort.Packet;
import dev.marregui.comando.ui.panels.lab.TrafficRows.Row;

import static org.assertj.core.api.Assertions.assertThat;

/// [TrafficRows], the packet log's rows without Swing.
/// - a run of one origin's presences is one row with its count, unless every presence is asked for
/// - the topic filter and the kind filter keep only what they name
/// - frontear's control traffic is left out unless asked for
/// - a publish's path is its appends by topic and size within five seconds, then the steps at their offsets
/// - each packet says what it is: a comando presence, question or reply by its instance, else by its kind
class TrafficRowsTest {

    /// Five presences one origin published in a row are one row counting 5, named by the first; asking for every
    /// presence shows the five.
    @Test
    void fivePresencesOfOneOriginAreOneRowUntilEveryPresenceIsAsked() {
        final List<Packet> packets = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            packets.add(published(i, 1_000L * i, "alpha", "comando/instances/alpha/presence"));
        }
        final List<Row> collapsed = TrafficRows.of(packets, "", null, false, false);
        assertThat(collapsed).hasSize(1);
        assertThat(collapsed.getFirst().count()).isEqualTo(5);
        assertThat(collapsed.getFirst().firstSeq()).isEqualTo(1L);
        assertThat(collapsed.getFirst().packet().seq()).as("the latest of the run").isEqualTo(5L);
        assertThat(TrafficRows.of(packets, "", null, false, true)).hasSize(5).allMatch(r -> r.count() == 1);
    }

    /// A presence of another origin, or any other packet between two presences, starts a new row.
    @Test
    void anotherOriginOrPacketBetweenPresencesStartsANewRow() {
        final List<Packet> packets = List.of(
                published(1, 1L, "alpha", "$frontear/presence/alpha"),
                published(2, 2L, "beta", "$frontear/presence/beta"),
                published(3, 3L, "beta", "$frontear/presence/beta"),
                published(4, 4L, "beta", "work/x"),
                published(5, 5L, "beta", "$frontear/presence/beta"));
        assertThat(TrafficRows.of(packets, "", null, true, false)).extracting(Row::count).containsExactly(1, 2, 1, 1);
    }

    /// The topic filter keeps the packets whose topic holds its text; the kind filter those of its kind; both apply
    /// together, and a step with no topic never matches a topic.
    @Test
    void theTopicAndKindFiltersKeepOnlyWhatTheyName() {
        final List<Packet> packets = List.of(
                published(1, 1L, "alpha", "work/a"),
                step(2, 2L, Kind.APPENDED, "beta", "work/a"),
                step(3, 3L, Kind.SENT, "beta", "work/a"),
                published(4, 4L, "alpha", "other/b"),
                step(5, 5L, Kind.CONNECTED, "beta", ""));
        assertThat(TrafficRows.of(packets, "work", null, false, false)).extracting(r -> r.packet().seq())
                .containsExactly(1L, 2L, 3L);
        assertThat(TrafficRows.of(packets, "", Kind.SENT, false, false)).extracting(r -> r.packet().seq())
                .containsExactly(3L);
        assertThat(TrafficRows.of(packets, "other", Kind.PUBLISHED, false, false)).extracting(r -> r.packet().seq())
                .containsExactly(4L);
        assertThat(TrafficRows.of(packets, "", Kind.CONNECTED, false, false)).hasSize(1);
        assertThat(TrafficRows.of(packets, "x", Kind.CONNECTED, false, false)).isEmpty();
    }

    /// The topics starting `$frontear/` or `comando/v1/local/fabric/` are control traffic, left out unless asked for,
    /// presences of the fabric included; a topic that holds such a start further in is not.
    @Test
    void controlTrafficIsLeftOutUnlessAskedFor() {
        final List<Packet> packets = List.of(
                published(1, 1L, "alpha", "$frontear/check/beta"),
                published(2, 2L, "alpha", "comando/v1/local/fabric/state/alpha"),
                published(3, 3L, "alpha", "work/a"),
                published(4, 4L, "alpha", "work/$frontear/"),
                step(5, 5L, Kind.APPENDED, "beta", "$frontear/check/beta"));
        assertThat(TrafficRows.of(packets, "", null, false, true)).extracting(r -> r.packet().seq())
                .containsExactly(3L, 4L);
        assertThat(TrafficRows.of(packets, "", null, true, true)).extracting(r -> r.packet().seq())
                .containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(packets).filteredOn(TrafficRows::control).extracting(Packet::seq).containsExactly(1L, 2L, 5L);
    }

    /// The path of a publish is, per link, the first APPENDED with its topic and size within five seconds, then the
    /// SENT and DROPPED of that link at that offset however much later; a second APPENDED of the same topic on that
    /// link, another topic, another offset, an APPENDED later than five seconds, or a row that is no publish has none.
    @Test
    void aPublishsPathIsItsAppendsAndTheStepsAtTheirOffsets() {
        final List<Row> rows = TrafficRows.of(List.of(
                published(1, 10_000L, "alpha", "work/a"),
                step(2, 10_010L, Kind.APPENDED, "beta", "work/a", 70L),
                step(3, 10_011L, Kind.APPENDED, "gamma", "work/a", 5L),
                step(4, 10_020L, Kind.APPENDED, "beta", "work/b", 71L),
                step(5, 10_030L, Kind.APPENDED, "beta", "work/a", 72L),
                step(6, 11_000L, Kind.SENT, "beta", "work/a", 70L),
                step(7, 11_001L, Kind.SENT, "beta", "work/a", 72L),
                step(8, 11_002L, Kind.DROPPED, "gamma", "work/a", 5L),
                step(9, 15_001L, Kind.APPENDED, "delta", "work/a", 3L),
                step(10, 40_000L, Kind.SENT, "gamma", "work/a", 5L),
                step(11, 40_001L, Kind.SENT, "delta", "work/a", 3L)), "", null, false, false);
        assertThat(TrafficRows.path(rows, 0)).as("gamma's late SENT is on it; delta's late APPENDED is not")
                .containsExactly(1, 2, 5, 7, 9);
        assertThat(TrafficRows.path(rows, 1)).as("a step has no path").isEmpty();
        assertThat(TrafficRows.path(rows, 99)).isEmpty();
    }

    /// A drop with no offset joins the path by topic and size, as an append does, and ends that link's path.
    @Test
    void aDropWithoutAnOffsetJoinsByTopicAndSize() {
        final List<Row> rows = TrafficRows.of(List.of(
                published(1, 10_000L, "alpha", "work/a"),
                step(2, 10_010L, Kind.DROPPED, "beta", "work/a", -1L),
                step(3, 10_020L, Kind.APPENDED, "beta", "work/a", 70L)), "", null, false, false);
        assertThat(TrafficRows.path(rows, 0)).containsExactly(1);
    }

    /// A comando topic names its instance: a presence, a question to it and a reply to it, with who published it and
    /// whether it came over a link; any other topic is named as it is; steps name their link.
    @Test
    void eachPacketSaysWhatItIs() {
        assertThat(TrafficRows.describe(published(1, 0L, "alice", "comando/v1/macbook-pro/instance/alice/presence")))
                .as("a presence names its own instance").isEqualTo(
                        "Presence of alice@macbook-pro: the instance, its sessions and their state");
        assertThat(TrafficRows.describe(published(1, 0L, "bob", "comando/v1/local/instance/bob/presence")))
                .as("this machine's instance by its slug").isEqualTo(
                        "Presence of bob: the instance, its sessions and their state");
        assertThat(TrafficRows.describe(published(1, 0L, "main", "comando/v1/laptop2/instance/bob/inbox")))
                .isEqualTo("Question to bob@laptop2, published by main");
        assertThat(TrafficRows.describe(published(1, 0L, "bob", "comando/v1/m/instance/main/reply")))
                .isEqualTo("Reply to main@m, published by bob");
        assertThat(TrafficRows.describe(published(1, 0L, "x", "work/a")))
                .isEqualTo("Published on work/a, published by x");
        assertThat(TrafficRows.describe(step(2, 0L, Kind.SENT, "beta", "work/a", 7L)))
                .isEqualTo("Sent to beta, which acknowledged it: work/a");
        final Packet from = published(1, 10_000L, "alpha", "work/a");
        assertThat(TrafficRows.step(from, step(2, 10_003L, Kind.APPENDED, "beta", "work/a", 70L)))
                .isEqualTo("+3 ms  appended to the log for beta at offset 70");
    }

    static Packet published(final long seq, final long atMillis, final String origin, final String topic) {
        return new Packet(seq, atMillis, Kind.PUBLISHED, origin, "", topic, 1, false, 12, 0, -1L, "0a0b", "{}", "");
    }

    static Packet step(final long seq, final long atMillis, final Kind kind, final String link, final String topic) {
        return step(seq, atMillis, kind, link, topic, seq);
    }

    static Packet step(
            final long seq,
            final long atMillis,
            final Kind kind,
            final String link,
            final String topic,
            final long offset
    ) {
        return new Packet(seq, atMillis, kind, "", link, topic, -1, false, 12, -1, offset, "", "", "");
    }
}
