package dev.marregui.comando.core.files;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Finding text in bytes where they lie.
/// - ASCII letters match in either case, in the haystack's first byte and in the rest
/// - every other byte matches only itself, so a UTF-8 sequence is found as it is written and no other
/// - the search starts at `from`, ends with the haystack, and says -1 when there is nothing
class ByteScanTest {

    private static MemorySegment bytes(final String text) {
        return MemorySegment.ofArray(text.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] needle(final String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /// A lower-case needle finds the text however its letters are cased, at the first place it occurs.
    @Test
    void aLowerCaseNeedleFindsTheTextInEitherCase() {
        assertThat(ByteScan.indexOf(bytes("one TWO two"), needle("two"), 0L)).isEqualTo(4L);
        assertThat(ByteScan.indexOf(bytes("one tWo two"), needle("two"), 0L)).isEqualTo(4L);
        assertThat(ByteScan.indexOf(bytes("Two"), needle("two"), 0L)).isZero();
    }

    /// The search starts at `from`: an occurrence before it is not seen, one exactly at it is.
    @Test
    void theSearchStartsAtFrom() {
        final MemorySegment text = bytes("two two");
        assertThat(ByteScan.indexOf(text, needle("two"), 1L)).isEqualTo(4L);
        assertThat(ByteScan.indexOf(text, needle("two"), 4L)).isEqualTo(4L);
        assertThat(ByteScan.indexOf(text, needle("two"), 5L)).isEqualTo(-1L);
    }

    /// Text that is not there answers -1, a needle cut off by the end of the haystack too, and a needle longer than the
    /// haystack reads nothing past it.
    @Test
    void textThatIsNotThereAnswersMinusOne() {
        assertThat(ByteScan.indexOf(bytes("one"), needle("two"), 0L)).isEqualTo(-1L);
        assertThat(ByteScan.indexOf(bytes("one tw"), needle("two"), 0L)).isEqualTo(-1L);
        assertThat(ByteScan.indexOf(bytes("t"), needle("two"), 0L)).isEqualTo(-1L);
        assertThat(ByteScan.indexOf(bytes(""), needle("two"), 0L)).isEqualTo(-1L);
    }

    /// The match ending on the haystack's last byte is found.
    @Test
    void aMatchOnTheLastByteIsFound() {
        assertThat(ByteScan.indexOf(bytes("one two"), needle("two"), 0L)).isEqualTo(4L);
    }

    /// A first candidate that fails part way does not hide a match that starts inside it.
    @Test
    void aFailedCandidateDoesNotHideAMatchInsideIt() {
        assertThat(ByteScan.indexOf(bytes("aaab"), needle("aab"), 0L)).isEqualTo(1L);
    }

    /// Bytes that are not ASCII letters match only themselves: the case bit does not turn `@` into a backtick, `[` into
    /// `{` or a digit into anything, and a UTF-8 letter is found only as written.
    @Test
    void onlyAsciiLettersFoldTheirCase() {
        assertThat(ByteScan.indexOf(bytes("`"), needle("@"), 0L)).isEqualTo(-1L);
        assertThat(ByteScan.indexOf(bytes("@"), needle("`"), 0L)).isEqualTo(-1L);
        assertThat(ByteScan.indexOf(bytes("x{"), needle("x["), 0L)).isEqualTo(-1L);
        assertThat(ByteScan.indexOf(bytes("x["), needle("x{"), 0L)).isEqualTo(-1L);
        assertThat(ByteScan.indexOf(bytes("mañana"), needle("ñ"), 0L)).isEqualTo(2L);
        assertThat(ByteScan.indexOf(bytes("maÑana"), needle("ñ"), 0L)).isEqualTo(-1L);
    }

    /// A needle made from typed text folds its ASCII letters only, so it finds the text in either case.
    @Test
    void aNeedleFromTextFoldsItsAsciiLetters() {
        assertThat(ByteScan.needle("MaÑana")).isEqualTo(needle("maÑana"));
        assertThat(ByteScan.indexOf(bytes("the PLAN"), ByteScan.needle("Plan"), 0L)).isEqualTo(4L);
    }

    /// An empty needle occurs at `from` while `from` is within the haystack.
    @Test
    void anEmptyNeedleOccursAtFrom() {
        assertThat(ByteScan.indexOf(bytes("one"), new byte[0], 1L)).isEqualTo(1L);
        assertThat(ByteScan.indexOf(bytes("one"), new byte[0], 3L)).isEqualTo(3L);
        assertThat(ByteScan.indexOf(bytes("one"), new byte[0], 4L)).isEqualTo(-1L);
    }
}
