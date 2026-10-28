package dev.marregui.comando.ui.kit.text;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [Fences#language] reads a code fence's language from its info string.
/// - The language is the first word of the stripped info; blank or null info has none.
class FencesTest {

    /// Attributes after a space or tab and surrounding blanks are dropped; empty, blank and null give the empty string.
    @Test
    void theLanguageIsTheFirstWordOfTheStrippedInfo() {
        assertThat(Fences.language("python")).isEqualTo("python");
        assertThat(Fences.language("python\ttitle=x")).isEqualTo("python");
        assertThat(Fences.language("  java  linenos ")).isEqualTo("java");
        assertThat(Fences.language("")).isEmpty();
        assertThat(Fences.language("   ")).isEmpty();
        assertThat(Fences.language(null)).isEmpty();
    }
}
