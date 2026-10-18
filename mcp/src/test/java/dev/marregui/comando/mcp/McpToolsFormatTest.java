package dev.marregui.comando.mcp;

import dev.marregui.comando.core.port.QueryPort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolsFormatTest {

    @Test
    void oneHugeCellIsCutToTheBudgetNotPassedWhole() {
        String huge = "x".repeat(10 * 1024 * 1024);
        String text = McpTools.format(new QueryPort.Rows(List.of("id", "body"), List.of(List.of("1", huge), List.of("2", "short")), -1L, false));
        assertThat(text.length()).as("64 kB of text to Claude, and a footer").isLessThan(McpTools.MAX_RESULT_CHARS + 200);
        assertThat(text).endsWith("(1 row, the last cut at 64 kB, more rows exist; refine the query or raise max_rows)");
    }

    @Test
    void rowsWithinTheBudgetArePassedWhole() {
        String text = McpTools.format(new QueryPort.Rows(List.of("n"), List.of(List.of("1"), List.of("2")), -1L, false));
        assertThat(text).isEqualTo("n\n1\n2\n(2 rows)");
    }
}
