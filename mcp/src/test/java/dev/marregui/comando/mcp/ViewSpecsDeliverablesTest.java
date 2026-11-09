package dev.marregui.comando.mcp;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [ViewSpecs] opens the files a session wrote as `ui_show_view` type `deliverables` (record 143).
///
/// - `deliverables` parses to [ViewSpec.Deliverables], whatever data comes with it.
/// - The view shows in the side pane unless the call places it; every other view keeps the lower panel.
/// - An unknown type names `deliverables` among the types to use.
class ViewSpecsDeliverablesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ViewSpec TEXT = new ViewSpec.Text("", "body", "", 0);

    /// [ViewSpecs#parse] maps `deliverables` to the view, with or without data.
    @Test
    void deliverablesParsesToTheFilesWrittenView() throws Exception {
        assertThat(ViewSpecs.parse("deliverables", json("{}"))).isEqualTo(new ViewSpec.Deliverables());
        assertThat(ViewSpecs.parse("deliverables", json("{\"ignored\": 1}"))).isEqualTo(new ViewSpec.Deliverables());
    }

    /// [ViewSpecs#placement] puts the files written in the side pane when the call names no placement.
    @Test
    void theFilesWrittenShowInTheSidePaneByDefault() throws Exception {
        assertThat(ViewSpecs.placement(new ViewSpec.Deliverables(), json("{\"type\": \"deliverables\"}")))
                .isEqualTo(Placement.SIDE);
    }

    /// [ViewSpecs#placement] keeps a placement the call names, for the files written as for any view.
    @Test
    void aPlacementTheCallNamesWins() throws Exception {
        assertThat(ViewSpecs.placement(new ViewSpec.Deliverables(), json("{\"placement\": \"lower\"}")))
                .isEqualTo(Placement.LOWER);
        assertThat(ViewSpecs.placement(new ViewSpec.Deliverables(), json("{\"placement\": \"SIDE\"}")))
                .isEqualTo(Placement.SIDE);
        assertThat(ViewSpecs.placement(TEXT, json("{\"placement\": \"side\"}"))).isEqualTo(Placement.SIDE);
    }

    /// [ViewSpecs#placement] keeps every other view in the lower panel when the call names no placement.
    @Test
    void anyOtherViewShowsInTheLowerPanelByDefault() throws Exception {
        assertThat(ViewSpecs.placement(TEXT, json("{}"))).isEqualTo(Placement.LOWER);
    }

    /// [ViewSpecs#parse] refuses an unknown type as invalid input.
    @Test
    void anUnknownTypeIsRefused() throws Exception {
        final JsonNode data = json("{}");
        assertThatThrownBy(() -> ViewSpecs.parse("chart", data)).isInstanceOf(IllegalArgumentException.class);
    }

    private static JsonNode json(final String text) throws Exception {
        return JSON.readTree(text);
    }
}
