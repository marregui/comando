package dev.marregui.comando.mcp;

import dev.marregui.comando.core.port.QueryPort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// [McpTools] formats query rows as bounded tab-separated text for a protocol response.
///
/// - An oversized cell consumes the result budget and reports truncation.
/// - Rows that fit the budget retain their header and every value.
class McpToolsFormatTest {

    /// [McpTools#format] cuts a 10 MB cell at 64 kB, keeps the first row only, and says that later rows remain, so one
    /// cell cannot exceed the response budget.
    @Test
    void oneHugeCellIsCutToTheBudgetNotPassedWhole() {
        String huge = "x".repeat(10 * 1024 * 1024);
        String text = McpTools.format(new QueryPort.Rows(List.of("id", "body"), List.of(List.of("1", huge), List.of("2", "short")), -1L, false));
        assertThat(text.length()).as("64 kB of text to Claude, and a footer").isLessThan(McpTools.MAX_RESULT_CHARS + 200);
        assertThat(text).endsWith("(1 row, the last cut at 64 kB, more rows exist; refine the query or raise max_rows)");
    }

    /// [McpTools#format] writes the header and both short rows unchanged when their combined text is below 64 kB.
    @Test
    void rowsWithinTheBudgetArePassedWhole() {
        String text = McpTools.format(new QueryPort.Rows(List.of("n"), List.of(List.of("1"), List.of("2")), -1L, false));
        assertThat(text).isEqualTo("n\n1\n2\n(2 rows)");
    }
}
