package dev.marregui.comando.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.marregui.comando.core.view.ViewSpec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [ViewSpecs] validates plot JSON into numeric series for [ViewSpec.Plot].
///
/// - Array-form series retain their names and ordinate values.
/// - Top-level x and y create one title-named series, including JSON-text arrays.
/// - Missing or nonnumeric series data explains the invalid field.
class ViewSpecsPlotTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode json(String text) throws Exception {
        return JSON.readTree(text);
    }

    /// [ViewSpecs#plot] reads an array-form series with its supplied name and y values, preserving the established
    /// `series` representation.
    @Test
    void seriesAreReadAsBefore() throws Exception {
        ViewSpec.Plot p = ViewSpecs.plot(json("{\"title\": \"t\", \"series\": [{\"name\": \"a\", \"x\": [1, 2], \"y\": [3, 4]}]}"));
        assertThat(p.series()).singleElement().satisfies(s -> {
            assertThat(s.name()).isEqualTo("a");
            assertThat(s.y()).containsExactly(3, 4);
        });
    }

    /// [ViewSpecs#plot] accepts top-level x and y arrays, including arrays encoded as JSON text, and uses the title as
    /// the implied series name for a single-series plot.
    @Test
    void oneSeriesMayBeGivenAsXAndYBesideTheTitleEvenAsJsonText() throws Exception {
        // x and y may be supplied beside the title as JSON text arrays
        ViewSpec.Plot p = ViewSpecs.plot(json("{\"title\": \"price\", \"placement\": \"side\", \"x\": \"[1,2,3]\", \"y\": \"[101.5,102,99.25]\"}"));
        assertThat(p.series()).singleElement().satisfies(s -> {
            assertThat(s.name()).as("named after the title").isEqualTo("price");
            assertThat(s.x()).containsExactly(1, 2, 3);
            assertThat(s.y()).containsExactly(101.5, 102, 99.25);
        });
        assertThat(ViewSpecs.plot(json("{\"title\": \"p\", \"x\": [1], \"y\": [2]}")).series()).hasSize(1);
    }

    /// [ViewSpecs#plot] names a missing series, invalid JSON-text array, and nonnumeric array member in its exception
    /// message so the caller can correct the field.
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
