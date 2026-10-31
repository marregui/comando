package dev.marregui.comando.ui.kit.text;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [CaseFold] folds as [String#equalsIgnoreCase] compares, keeping the length, and allocates nothing for folded text.
/// - Greek final sigma and capital sigma fold alike; dotted capital I folds to `i` in one char.
/// - Text with nothing to fold comes back as the same instance.
/// - [CaseFold#contains] matches a folded needle at the start, the end, absent, or longer than the text.
class CaseFoldTest {

    /// `ΛΌΓΟΣ` and `λόγος` fold to the same text, though `Σ` lowers to `σ` and the word ends in `ς`; `İstanbul`
    /// folds to `istanbul`, eight chars, where [String#toLowerCase] makes nine.
    @Test
    void foldsAsEqualsIgnoreCaseComparesAndKeepsTheLength() {
        assertThat(CaseFold.fold("ΛΌΓΟΣ")).isEqualTo(CaseFold.fold("λόγος"));
        assertThat("ΛΌΓΟΣ".equalsIgnoreCase("λόγος")).as("the comparison the fold follows").isTrue();
        assertThat(CaseFold.fold("İstanbul")).isEqualTo("istanbul").hasSize("İstanbul".length());
        assertThat(CaseFold.fold("MaTcH")).isEqualTo("match");
    }

    /// A text already folded, and the empty text, come back as the same instance; a text with one char to fold at
    /// its end does not.
    @Test
    void returnsTheSameInstanceWhenNothingChanges() {
        final String folded = "a row of plain text 42";
        assertThat(CaseFold.fold(folded)).isSameAs(folded);
        assertThat(CaseFold.fold("")).isSameAs("");
        final String upperAtEnd = "a row of plain text X";
        assertThat(CaseFold.fold(upperAtEnd)).isNotSameAs(upperAtEnd).isEqualTo("a row of plain text x");
    }

    /// A folded needle is found ignoring case at the start and the end of a text, and not when absent or longer.
    @Test
    void containsMatchesAFoldedNeedleIgnoringCase() {
        final String needle = CaseFold.fold("MaTcH");
        assertThat(CaseFold.contains(new StringBuilder("A MATCH here"), needle)).isTrue();
        assertThat(CaseFold.contains(new StringBuilder("ends in match"), needle)).isTrue();
        assertThat(CaseFold.contains(new StringBuilder("matc"), needle)).isFalse();
        assertThat(CaseFold.contains(new StringBuilder("no hit"), needle)).isFalse();
        assertThat(CaseFold.contains("ΛΌΓΟΣ", CaseFold.fold("λόγος"))).isTrue();
    }
}
