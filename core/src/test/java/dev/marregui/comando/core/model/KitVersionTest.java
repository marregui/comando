package dev.marregui.comando.core.model;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class KitVersionTest {

    private static final String LS_REMOTE = """
            1111111111111111111111111111111111111111\trefs/tags/v1.2.0
            2222222222222222222222222222222222222222\trefs/tags/v1.10.0
            3333333333333333333333333333333333333333\trefs/tags/v1.10.0^{}
            4444444444444444444444444444444444444444\trefs/tags/v1.3.1
            5555555555555555555555555555555555555555\trefs/tags/v2.0.0-rc1
            6666666666666666666666666666666666666666\trefs/tags/nightly
            7777777777777777777777777777777777777777\trefs/tags/v1.1.9
            """;

    @Test
    void aDescribedVersionIsAtOrPastARelease() {
        assertThat(KitVersion.release("v1.2.0")).contains("v1.2.0");
        assertThat(KitVersion.release("v1.2.0-3-gabc1234\n")).as("three commits past it").contains("v1.2.0");
        assertThat(KitVersion.release("abc1234")).as("no tag yet").isEmpty();
        assertThat(KitVersion.release("v1.2")).isEmpty();
        assertThat(KitVersion.release("")).isEmpty();
    }

    @Test
    void theNewerReleasesComeNewestFirstEachOnceComparedByNumber() {
        assertThat(KitVersion.newerThan(Optional.of("v1.2.0"), LS_REMOTE))
                .as("1.10 after 1.3, the peeled twin once, no candidate, no other tag").containsExactly("v1.10.0", "v1.3.1");
        assertThat(KitVersion.newerThan(Optional.of("v1.10.0"), LS_REMOTE)).isEmpty();
        assertThat(KitVersion.newerThan(Optional.empty(), LS_REMOTE)).as("past no release: all of them")
                .containsExactly("v1.10.0", "v1.3.1", "v1.2.0", "v1.1.9");
        assertThat(KitVersion.newerThan(Optional.of("v1.2.0"), "")).isEmpty();
    }

    @Test
    void theStatusBarSaysTheKitsNameVersionAndTheNewestRelease() {
        KitVersion past = KitVersion.of("v1.2.0-3-gabc1234\n", LS_REMOTE);
        assertThat(past.shown()).isEqualTo("v1.2.0");
        assertThat(past.newest()).contains("v1.10.0");
        assertThat(past.label("marregui")).isEqualTo("kit marregui v1.2.0 → v1.10.0 available");

        KitVersion latest = KitVersion.of("v1.10.0", LS_REMOTE);
        assertThat(latest.label("marregui")).isEqualTo("kit marregui v1.10.0");
        assertThat(KitVersion.of("abc1234", "").label("marregui")).as("no release yet: the commit").isEqualTo("kit marregui abc1234");
        assertThat(latest.failedRemote("offline").problem()).isEqualTo("offline");
        assertThat(latest.failedRemote("offline").newer()).isEmpty();
    }
}
