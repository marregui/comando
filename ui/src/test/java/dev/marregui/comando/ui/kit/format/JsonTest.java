package dev.marregui.comando.ui.kit.format;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JsonTest {

    @Test
    void jsonReadsIndentedAndTextThatIsNotJsonReadsAsAnEmptyObject() {
        assertThat(Json.pretty(Json.parse("{\"command\":\"ls\",\"n\":2}")))
                .isEqualTo("{" + System.lineSeparator() + "  \"command\" : \"ls\"," + System.lineSeparator() + "  \"n\" : 2"
                        + System.lineSeparator() + "}");
        assertThat(Json.parse("{not json").isObject()).isTrue();
        assertThat(Json.parse("{not json").isEmpty()).isTrue();
    }
}
