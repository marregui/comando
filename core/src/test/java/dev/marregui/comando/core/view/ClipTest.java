package dev.marregui.comando.core.view;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClipTest {

    @Test
    void aClippedTextIsAtMostMaxCharactersTheEllipsisIncluded() {
        assertThat(Clip.keepStart("abcdefghij", 10)).isEqualTo("abcdefghij");
        assertThat(Clip.keepStart("abcdefghijk", 10)).isEqualTo("abcdefghi…").hasSize(10);
        assertThat(Clip.keepEnd("/a/b/c/d/project", 10)).isEqualTo("…d/project").hasSize(10);
        assertThat(Clip.keepEnd("short", 10)).isEqualTo("short");
        assertThat(Clip.keepStart("abc", 1)).isEqualTo("…");
    }

    @Test
    void aClipNeverSplitsASurrogatePair() {
        String face = "😀";
        assertThat(Clip.keepStart("abc" + face + "def", 5)).isEqualTo("abc…");
        assertThat(Clip.keepEnd("abc" + face + "def", 5)).isEqualTo("…def");
        assertThat(Clip.keepEnd("abcd" + face + "ef", 4)).isEqualTo("…ef");
    }

    @Test
    void aClipKeepsAtLeastTheEllipsis() {
        assertThatThrownBy(() -> Clip.keepStart("abc", 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
