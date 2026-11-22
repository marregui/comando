package dev.marregui.comando.frontear.alexander;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.JsonNode;
import dev.marregui.comando.frontear.octopus.LinkState;
import dev.marregui.comando.frontear.protocol.Names;
import dev.marregui.comando.frontear.protocol.Packet;

import static org.assertj.core.api.Assertions.assertThat;

/// A node's retained state topic, read by an injected clock:
/// - the first look publishes the links, retained, as JSON sorted by id
/// - an unchanged state is never republished
/// - a change is republished, but no sooner than one second after the last publish
/// - a clock that steps back does not hold a change back
class StateTopicTest {

    private static final long START_MILLIS = 1_800_000_000_000L;

    private final List<Packet.Publish> published = new ArrayList<>();
    private FakeOctopus octopus;
    private TestClock clock;
    private StateTopic topic;

    @BeforeEach
    void topic() {
        published.clear();
        octopus = new FakeOctopus();
        clock = new TestClock(START_MILLIS);
        topic = new StateTopic(octopus, "n1", clock, published::add);
    }

    /// The first look publishes on `$frontear/state/n1/links`, retained, QoS 1, JSON, with each link's fields, the
    /// links sorted by id.
    @Test
    void theFirstLookPublishesTheLinksRetained() {
        octopus.state = List.of(new LinkState("b", true, 1, 2, 3, 4, 5, 6), new LinkState("a", false, 0, 0, 0, 0, 0));
        assertThat(topic.publishIfChanged()).isTrue();
        final Packet.Publish message = published.getFirst();
        assertThat(message.topic()).isEqualTo("$frontear/state/n1/links");
        assertThat(message.retain()).isTrue();
        assertThat(message.qos()).isEqualTo(1);
        assertThat(message.meta().contentType()).isEqualTo(Names.JSON);
        final JsonNode links = Json.readTree(message.payload()).path("links");
        assertThat(links.size()).isEqualTo(2);
        assertThat(links.path(0).path("id").stringValue()).isEqualTo("a");
        final JsonNode b = links.path(1);
        assertThat(b.path("id").stringValue()).isEqualTo("b");
        assertThat(b.path("connected").booleanValue()).isTrue();
        assertThat(List.of(b.path("queued").asLong(), b.path("cursor").asLong(), b.path("tail").asLong(),
                b.path("sent").asLong(), b.path("dropped").asLong(), b.path("reordered").asLong()))
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
    }

    /// An empty octopus publishes an empty list once, and an unchanged state is not published again, however late.
    @Test
    void anUnchangedStateIsNotRepublished() {
        assertThat(topic.publishIfChanged()).isTrue();
        assertThat(Json.readTree(published.getFirst().payload()).path("links").size()).isZero();
        clock.advance(Duration.ofMinutes(5));
        assertThat(topic.publishIfChanged()).isFalse();
        assertThat(published).hasSize(1);
    }

    /// A change 1 ms after a publish waits; at 999 ms it still waits; at 1 s it is published, and its successor waits
    /// again.
    @Test
    void aChangeIsRepublishedAtMostOnceASecond() {
        topic.publishIfChanged();
        octopus.state = List.of(new LinkState("a", false, 0, 0, 0, 0, 0));
        clock.advance(Duration.ofMillis(1));
        assertThat(topic.publishIfChanged()).isFalse();
        clock.set(START_MILLIS + 999);
        assertThat(topic.publishIfChanged()).isFalse();
        clock.set(START_MILLIS + 1_000);
        assertThat(topic.publishIfChanged()).isTrue();
        octopus.state = List.of(new LinkState("a", true, 0, 0, 0, 0, 0));
        assertThat(topic.publishIfChanged()).isFalse();
        clock.advance(StateTopic.EVERY);
        assertThat(topic.publishIfChanged()).isTrue();
        assertThat(published).hasSize(3);
        assertThat(Json.readTree(published.getLast().payload()).path("links").path(0).path("connected")
                .booleanValue()).isTrue();
    }

    /// After the clock steps back, a change is published at once rather than held until the clock catches up.
    @Test
    void aClockThatStepsBackDoesNotHoldAChange() {
        topic.publishIfChanged();
        octopus.state = List.of(new LinkState("a", false, 0, 0, 0, 0, 0));
        clock.set(START_MILLIS - Duration.ofHours(1).toMillis());
        assertThat(topic.publishIfChanged()).isTrue();
    }
}
