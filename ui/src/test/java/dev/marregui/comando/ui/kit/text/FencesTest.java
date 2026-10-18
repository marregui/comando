package dev.marregui.comando.ui.kit.text;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FencesTest {

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
