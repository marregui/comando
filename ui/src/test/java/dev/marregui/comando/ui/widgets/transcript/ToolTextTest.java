package dev.marregui.comando.ui.widgets.transcript;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ToolTextTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void aCallsSummaryLinksTheFileOrAddressItWorksOn() throws Exception {
        assertThat(ToolText.target("Read", JSON.readTree("{\"file_path\":\"/w/a.md\"}"))).isEqualTo("comando-file:/w/a.md");
        assertThat(ToolText.target("Write", JSON.readTree("{\"file_path\":\" docs/b.md \"}"))).isEqualTo("comando-file:docs/b.md");
        assertThat(ToolText.target("NotebookEdit", JSON.readTree("{\"notebook_path\":\"/w/n.ipynb\"}"))).isEqualTo("comando-file:/w/n.ipynb");
        assertThat(ToolText.target("WebFetch", JSON.readTree("{\"url\":\"https://example.org/x\"}"))).isEqualTo("https://example.org/x");
        assertThat(ToolText.target("WebFetch", JSON.readTree("{\"url\":\"javascript:alert(1)\"}"))).as("only web addresses").isEmpty();
        assertThat(ToolText.target("Bash", JSON.readTree("{\"command\":\"ls\"}"))).as("a command's links are in its text").isEmpty();
    }

    @Test
    void aSkillLinksItsSkillFileWhenItIsThere(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("deep"));
        Files.writeString(root.resolve("deep/SKILL.md"), "---\nname: deep\n---\n");
        assertThat(ToolText.skillFile("deep", root)).isEqualTo(root.resolve("deep/SKILL.md"));
        assertThat(ToolText.skillFile("/deep", root)).as("as typed").isEqualTo(root.resolve("deep/SKILL.md"));
        assertThat(ToolText.skillFile("missing", root)).isNull();
        assertThat(ToolText.skillFile("plugin:deep", root)).as("a plugin's skill lives elsewhere").isNull();
        assertThat(ToolText.skillFile("..", root)).isNull();
    }
}
