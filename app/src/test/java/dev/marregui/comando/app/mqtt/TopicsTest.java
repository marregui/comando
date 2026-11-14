package dev.marregui.comando.app.mqtt;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Topic Names, Topic Filters and matching (§4.7), on the specification's own examples.
class TopicsTest {

    /// The filters §4.7.1 calls valid are valid, and those it calls invalid are not: `#` alone and last, `+` alone in
    /// its level; an empty filter and one with U+0000 neither.
    @Test
    void filtersAreValidExactlyWhereTheSpecificationSays() {
        for (final String valid : new String[] {"sport/tennis/player1/#", "sport/#", "#", "+", "+/tennis/#",
            "sport/+/player1", "/+", "+/+", "a//b", "/"}) {
            assertThat(Topics.validFilter(valid)).as(valid).isTrue();
        }
        for (final String invalid : new String[] {"sport/tennis#", "sport/tennis/#/ranking", "sport+", "#/a", "a/b+",
            "", "a\u0000b", "++"}) {
            assertThat(Topics.validFilter(invalid)).as(invalid).isFalse();
        }
    }

    /// A Topic Name holds no wildcard and no U+0000 and is not empty; a lone `/` or a space is a name.
    @Test
    void namesHoldNoWildcard() {
        assertThat(Topics.validName("sport/tennis/player1")).isTrue();
        assertThat(Topics.validName("/")).isTrue();
        assertThat(Topics.validName(" ")).isTrue();
        assertThat(Topics.validName("")).isFalse();
        assertThat(Topics.validName("sport/+")).isFalse();
        assertThat(Topics.validName("sport/#")).isFalse();
        assertThat(Topics.validName("a\u0000")).isFalse();
    }

    /// The matches of §4.7.1.2 and §4.7.1.3: `#` takes the parent level and every level below it, `+` one level,
    /// an empty one included, and a level is matched whole and case-sensitively.
    @Test
    void wildcardsMatchAsTheSpecificationsExamplesSay() {
        assertThat(Topics.matches("sport/tennis/player1/#", "sport/tennis/player1")).isTrue();
        assertThat(Topics.matches("sport/tennis/player1/#", "sport/tennis/player1/ranking")).isTrue();
        assertThat(Topics.matches("sport/tennis/player1/#", "sport/tennis/player1/score/wimbledon")).isTrue();
        assertThat(Topics.matches("sport/#", "sport")).isTrue();
        assertThat(Topics.matches("#", "sport/tennis")).isTrue();
        assertThat(Topics.matches("sport/tennis/+", "sport/tennis/player1")).isTrue();
        assertThat(Topics.matches("sport/tennis/+", "sport/tennis/player1/ranking")).isFalse();
        assertThat(Topics.matches("sport/+", "sport")).isFalse();
        assertThat(Topics.matches("sport/+", "sport/")).isTrue();
        assertThat(Topics.matches("+/+", "/finance")).isTrue();
        assertThat(Topics.matches("/+", "/finance")).isTrue();
        assertThat(Topics.matches("+", "/finance")).isFalse();
        assertThat(Topics.matches("sport/tennis", "sport/tennis")).isTrue();
        assertThat(Topics.matches("sport/tennis", "sport/Tennis")).isFalse();
        assertThat(Topics.matches("sport/tennis", "sport/tennis/")).isFalse();
        assertThat(Topics.matches("sport/ten", "sport/tennis")).isFalse();
        assertThat(Topics.matches("comando/v1/local/instance/+/presence", "comando/v1/local/instance/b/presence"))
                .isTrue();
        assertThat(Topics.matches("comando/v1/local/instance/+/presence", "comando/v1/local/instance/b/inbox"))
                .isFalse();
    }

    /// A name that starts with `$` is matched by no filter that starts with a wildcard (§4.7.2), and by one that
    /// names its first level.
    @Test
    void aDollarTopicIsMatchedOnlyByAFilterThatNamesItsFirstLevel() {
        assertThat(Topics.matches("#", "$SYS/monitor/Clients")).isFalse();
        assertThat(Topics.matches("+/monitor/Clients", "$SYS/monitor/Clients")).isFalse();
        assertThat(Topics.matches("$SYS/#", "$SYS/monitor/Clients")).isTrue();
        assertThat(Topics.matches("$SYS/monitor/+", "$SYS/monitor/Clients")).isTrue();
    }
}
