package dev.marregui.comando.core.view;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [Clip] shortens label text without exceeding its character cap or splitting a surrogate pair.
///
/// - Start and end clips include the ellipsis in the cap.
/// - Emoji surrogate pairs remain whole at a clip boundary.
/// - A cap below one character is rejected.
class ClipTest {

    /// [Clip] preserves text at or below the cap and replaces the omitted side with one ellipsis when it exceeds the
    /// cap; the one-character cap leaves only the ellipsis.
    @Test
    void aClippedTextIsAtMostMaxCharactersTheEllipsisIncluded() {
        assertThat(Clip.keepStart("abcdefghij", 10)).isEqualTo("abcdefghij");
        assertThat(Clip.keepStart("abcdefghijk", 10)).isEqualTo("abcdefghi…").hasSize(10);
        assertThat(Clip.keepEnd("/a/b/c/d/project", 10)).isEqualTo("…d/project").hasSize(10);
        assertThat(Clip.keepEnd("short", 10)).isEqualTo("short");
        assertThat(Clip.keepStart("abc", 1)).isEqualTo("…");
    }

    /// [Clip] excludes the whole emoji when either end boundary would cut its UTF-16 surrogate pair.
    @Test
    void aClipNeverSplitsASurrogatePair() {
        String face = "😀";
        assertThat(Clip.keepStart("abc" + face + "def", 5)).isEqualTo("abc…");
        assertThat(Clip.keepEnd("abc" + face + "def", 5)).isEqualTo("…def");
        assertThat(Clip.keepEnd("abcd" + face + "ef", 4)).isEqualTo("…ef");
    }

    /// [Clip] rejects a zero-character cap because even a shortened value must contain the ellipsis.
    @Test
    void aClipKeepsAtLeastTheEllipsis() {
        assertThatThrownBy(() -> Clip.keepStart("abc", 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
