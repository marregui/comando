package dev.marregui.comando.ui.widgets.transcript.render;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.ui.kit.async.OnEdt;
import dev.marregui.comando.ui.kit.theme.Theme;

import static org.assertj.core.api.Assertions.assertThat;

/// [ToolCallItem]'s title row, read from what is painted past the tool's name.
/// - a Bash call reads by its description when it has one, else by its command; the export keeps the command
/// - a sub-agent's latest call reads as that call's card does
/// - a title that does not fit ends in an ellipsis at every width, for every tool; one that fits has none
@ExtendWith(OnEdt.class)
class ToolCallItemTest {

    /// Draws nothing: past the name, the only ink of a title made of it is the ellipsis.
    private static final String BLANK = "\u00A0";
    /// Where a title's summary is past the tool's name.
    private static final int PAST_NAME = 110;
    /// What the title row leaves to the status at its right end.
    private static final int STATUS_ROOM = 70;

    private final List<String> opened = new ArrayList<>();
    private final TranscriptCanvas canvas = new TranscriptCanvas(opened::add);

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    /// A description that draws nothing leaves the row empty past the name, however long the command is.
    @Test
    void aBashCallIsTitledByItsDescription() {
        add("Bash", "{\"command\":\"" + "m".repeat(120) + "\",\"description\":\"" + BLANK + "\"}");
        assertThat(inkPastTheName(900)).isZero();
    }

    /// A blank description is no description: the row reads the command.
    @Test
    void aBashCallWithABlankDescriptionIsTitledByItsCommand() {
        add("Bash", "{\"command\":\"" + "m".repeat(120) + "\",\"description\":\"  \"}");
        assertThat(inkPastTheName(900)).isPositive();
    }

    /// The description is the title only: the export still gives the command, as its summary and as its fence.
    @Test
    void theExportKeepsTheCommand() {
        add("Bash", "{\"command\":\"ls -la\",\"description\":\"List the files\"}");
        assertThat(canvas.markdown()).contains("**Bash** · ls -la\n\n```bash\nls -la\n```")
                .doesNotContain("List the files");
    }

    /// The latest call a sub-agent card names reads as its own card does.
    @Test
    void aSubAgentsLatestBashCallReadsByItsDescription() {
        final String input = "{\"command\":\"ls -la\",\"description\":\"List the files\"}";
        final Block.ToolCall call = new Block.ToolCall("b", "Bash", input, "", Block.ToolStatus.RUNNING, "");
        assertThat(ToolCallItem.lastLabel(call)).isEqualTo("Bash List the files");
    }

    /// Each input gives a title far wider than the row: its one visible mark past the name is the ellipsis.
    @ParameterizedTest
    @ValueSource(strings = {"Bash:command", "Grep:pattern", "Glob:pattern"})
    void aCutTitleEndsInAnEllipsisAtEveryWidth(final String toolAndField) {
        final int colon = toolAndField.indexOf(':');
        final String field = toolAndField.substring(colon + 1);
        add(toolAndField.substring(0, colon), "{\"" + field + "\":\"" + BLANK.repeat(130) + "\"}");
        for (final int width : new int[]{220, 300, 420, 500}) {
            assertThat(inkPastTheName(width)).as("width %d", width).isPositive();
        }
    }

    /// A title that fits shows all of itself and nothing more.
    @Test
    void aTitleThatFitsHasNoEllipsis() {
        add("Bash", "{\"command\":\"" + BLANK.repeat(4) + "\"}");
        assertThat(inkPastTheName(900)).isZero();
    }

    private void add(final String tool, final String input) {
        canvas.append(0, new Block.ToolCall("t", tool, input, "", Block.ToolStatus.DONE, ""));
    }

    /// Pixels neither ground nor card in the title row, between the tool's name and the status.
    private int inkPastTheName(final int width) {
        final BufferedImage img = paint(width);
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = PAST_NAME; x < width - STATUS_ROOM; x++) {
                final int rgb = img.getRGB(x, y);
                if (rgb != Theme.ground().getRGB() && rgb != Theme.CARD.getRGB()) {
                    n++;
                }
            }
        }
        return n;
    }

    private BufferedImage paint(final int width) {
        canvas.setSize(width, 100);
        canvas.ensureLayout(width);
        final int height = canvas.contentHeight();
        canvas.setSize(width, height);
        final BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = img.createGraphics();
        g.setColor(Theme.ground());
        g.fillRect(0, 0, width, height);
        g.setClip(0, 0, width, height);
        canvas.paint(g);
        g.dispose();
        return img;
    }
}
