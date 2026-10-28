package dev.marregui.comando.ui.kit.format;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [Json] parses and pretty-prints the JSON that tools send.
/// - Valid JSON prints indented; text that is not JSON reads as an empty object.
class JsonTest {

    /// [Json#pretty] indents with two spaces and ` : ` between key and value on the platform's line separator, and
    /// [Json#parse] turns malformed text into an empty object rather than failing.
    @Test
    void jsonReadsIndentedAndTextThatIsNotJsonReadsAsAnEmptyObject() {
        assertThat(Json.pretty(Json.parse("{\"command\":\"ls\",\"n\":2}")))
                .isEqualTo("{" + System.lineSeparator() + "  \"command\" : \"ls\"," + System.lineSeparator() + "  \"n\" : 2"
                        + System.lineSeparator() + "}");
        assertThat(Json.parse("{not json").isObject()).isTrue();
        assertThat(Json.parse("{not json").isEmpty()).isTrue();
    }
}
