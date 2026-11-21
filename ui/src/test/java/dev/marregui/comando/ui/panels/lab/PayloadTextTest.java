package dev.marregui.comando.ui.panels.lab;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [PayloadText], a payload as the frontear tab shows it.
/// - a JSON object or array is indented by two spaces, one field or element per line
/// - plain text, a JSON scalar, JSON cut short and a text over the parse bound are shown as they came
class PayloadTextTest {

    /// An object nested in an array, given on one line, comes back indented by two spaces at each level.
    @Test
    void jsonIsIndentedByTwoSpaces() {
        assertThat(PayloadText.shown("{\"n\":1,\"tags\":[\"a\"],\"at\":{\"s\":2}}").lines().toList()).containsExactly(
                "{",
                "  \"n\" : 1,",
                "  \"tags\" : [ \"a\" ],",
                "  \"at\" : {",
                "    \"s\" : 2",
                "  }",
                "}");
        assertThat(PayloadText.shown("  [1,2]").lines().toList()).as("leading space").containsExactly("[ 1, 2 ]");
    }

    /// Text that is not one JSON object or array is returned as it is, character for character.
    @Test
    void anythingElseIsShownAsItCame() {
        assertThat(PayloadText.shown("")).isEmpty();
        assertThat(PayloadText.shown("temperature 21.5")).isEqualTo("temperature 21.5");
        assertThat(PayloadText.shown("42")).as("a scalar").isEqualTo("42");
        assertThat(PayloadText.shown("\"quoted\"")).isEqualTo("\"quoted\"");
        assertThat(PayloadText.shown("{\"cut\": [1, 2")).as("cut short").isEqualTo("{\"cut\": [1, 2");
        assertThat(PayloadText.shown("{} trailing")).as("trailing text").isEqualTo("{} trailing");
    }

    /// A JSON text one character over the bound is not parsed; one at the bound is.
    @Test
    void aTextOverTheBoundIsNotParsed() {
        final String at = "[" + " ".repeat(PayloadText.PARSED_CHARS - 2) + "]";
        assertThat(at).hasSize(PayloadText.PARSED_CHARS);
        assertThat(PayloadText.shown(at)).isEqualTo("[ ]");
        final String over = at + " ";
        assertThat(PayloadText.shown(over)).isSameAs(over);
    }
}
