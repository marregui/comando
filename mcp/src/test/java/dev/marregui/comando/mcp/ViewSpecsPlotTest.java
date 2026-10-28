package dev.marregui.comando.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.marregui.comando.core.view.ViewSpec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ViewSpecsPlotTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode json(String text) throws Exception {
        return JSON.readTree(text);
    }

    @Test
    void seriesAreReadAsBefore() throws Exception {
        ViewSpec.Plot p = ViewSpecs.plot(json("{\"title\": \"t\", \"series\": [{\"name\": \"a\", \"x\": [1, 2], \"y\": [3, 4]}]}"));
        assertThat(p.series()).singleElement().satisfies(s -> {
            assertThat(s.name()).isEqualTo("a");
            assertThat(s.y()).containsExactly(3, 4);
        });
    }

    @Test
    void oneSeriesMayBeGivenAsXAndYBesideTheTitleEvenAsJsonText() throws Exception {
        // what a model sent on 2026-10-28: x and y beside the title, each an array written as text
        ViewSpec.Plot p = ViewSpecs.plot(json("{\"title\": \"price\", \"placement\": \"side\", \"x\": \"[1,2,3]\", \"y\": \"[101.5,102,99.25]\"}"));
        assertThat(p.series()).singleElement().satisfies(s -> {
            assertThat(s.name()).as("named after the title").isEqualTo("price");
            assertThat(s.x()).containsExactly(1, 2, 3);
            assertThat(s.y()).containsExactly(101.5, 102, 99.25);
        });
        assertThat(ViewSpecs.plot(json("{\"title\": \"p\", \"x\": [1], \"y\": [2]}")).series()).hasSize(1);
    }

    @Test
    void whatIsWrongIsNamed() {
        assertThatThrownBy(() -> ViewSpecs.plot(json("{\"title\": \"p\"}"))).hasMessage(
                "series must be a non-empty array of {name, x, y}, or give x and y for one series");
        assertThatThrownBy(() -> ViewSpecs.plot(json("{\"title\": \"p\", \"x\": \"one two\", \"y\": [1]}")))
                .hasMessage("x must be an array of numbers, not text: one two");
        assertThatThrownBy(() -> ViewSpecs.plot(json("{\"title\": \"p\", \"x\": \"[1, \\\"a\\\"]\", \"y\": [1, 2]}")))
                .hasMessageStartingWith("x[1] is not a number");
    }
}
