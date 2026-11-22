package dev.marregui.comando.ui.shell;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.port.PagePort;
import dev.marregui.comando.core.schema.SchemaRegistry;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.ui.kit.web.FakePageView;
import dev.marregui.comando.ui.kit.web.PageView;
import dev.marregui.comando.ui.panels.EditorPanel;

import static org.assertj.core.api.Assertions.assertThat;

/// [PageAccess] lets agents see the HTML pages the editor panels show rendered, as a picture and as text.
/// - the list names every panel whose file is an HTML page, in the panels' order, with whether it shows rendered
/// - a snapshot writes the page's picture to a PNG file and answers its absolute path
/// - a read cuts the page's text at the characters asked for and says whether more was left out
/// - a panel with no HTML page, one showing the page's text, an unknown panel, and every action before the window is
///   built, are refused, the refusal naming the panel
class PageAccessTest {

    private static final PanelId PAGE = new PanelId("page");
    private static final PanelId NOTES = new PanelId("notes");
    private static final PanelId TEXT = new PanelId("text");

    private final BufferedImage picture = new BufferedImage(7, 5, BufferedImage.TYPE_INT_RGB);
    private final FakePageView page = new FakePageView(picture, new PageView.Text("Report", "sel", "0123456789"));
    private final FakePageView textPage = new FakePageView();

    /// Three panels: `page` shows `report.html` rendered, `notes` holds a Markdown file, `text` shows `old.html` as its
    /// text.
    private PageAccess access() throws Exception {
        final AppState s = AppState.initial(List.of(new PanelState(PAGE, "Report", "", ""),
                new PanelState(NOTES, "Notes", "", ""), new PanelState(TEXT, "Old", "", "")), List.of(), List.of());
        final EditorPanel[] views = new EditorPanel[3];
        SwingUtilities.invokeAndWait(() -> {
            views[0] = view(s.panels().get(0), page, Path.of("/r/report.html"));
            views[1] = view(s.panels().get(1), new FakePageView(), Path.of("/r/notes.md"));
            views[2] = view(s.panels().get(2), textPage, Path.of("/r/old.html"));
            views[2].togglePreview();
        });
        final Map<PanelId, EditorPanel> byId = Map.of(PAGE, views[0], NOTES, views[1], TEXT, views[2]);
        return new PageAccess(() -> byId::get, () -> s);
    }

    /// The panel `p` showing `file`, its page drawn by `view`; on the EDT.
    private static EditorPanel view(final PanelState p, final FakePageView view, final Path file) {
        final EditorPanel v = new EditorPanel(p, intent -> {
        }, new SchemaRegistry(), () -> view);
        v.editor().load("<html></html>");
        v.file(file, true);
        return v;
    }

    /// The list names the two HTML panels in the panels' order, the one showing its text as not rendered; the
    /// Markdown panel is not among them.
    @Test
    void theListNamesTheHtmlPanels() throws Exception {
        assertThat(access().list()).containsExactly(
                new PagePort.Page(PAGE, "Report", Path.of("/r/report.html"), true),
                new PagePort.Page(TEXT, "Old", Path.of("/r/old.html"), false));
    }

    /// A snapshot writes the page's picture to a new PNG file and answers its absolute path.
    @Test
    void aSnapshotWritesAPngAndAnswersItsPath() throws Exception {
        final String answer = access().snapshot(PAGE);
        final Path png = Path.of(answer);
        try {
            assertThat(png.isAbsolute()).as(answer).isTrue();
            assertThat(png.getFileName().toString()).startsWith("page-").endsWith(".png");
            assertThat(png.getParent().getFileName().toString()).startsWith("comando-pages-");
            final BufferedImage read = ImageIO.read(png.toFile());
            assertThat(read.getWidth()).isEqualTo(7);
            assertThat(read.getHeight()).isEqualTo(5);
        } finally {
            Files.deleteIfExists(png);
        }
    }

    /// Snapshots go to one folder of this process, which keeps the newest pictures only: after more snapshots than it
    /// keeps, it holds exactly that many, the last answered among them.
    @Test
    void theSnapshotFolderKeepsOnlyTheNewestPictures() throws Exception {
        final PageAccess access = access();
        String last = "";
        for (int i = 0; i < PageAccess.KEPT_PICTURES + 5; i++) {
            last = access.snapshot(PAGE);
        }
        final Path folder = Path.of(last).getParent();
        try (Stream<Path> held = Files.list(folder)) {
            final List<Path> pictures = held.toList();
            assertThat(pictures).hasSize(PageAccess.KEPT_PICTURES).contains(Path.of(last));
        }
    }

    /// A read cuts the page's text at the characters asked for and says more was left out; one that holds it all says
    /// none was; title, file and selection come with it.
    @Test
    void aReadCutsAtMaxCharsAndSetsMore() throws Exception {
        final PageAccess access = access();

        assertThat(access.read(PAGE, 4)).contains(
                new PagePort.Reading("Report", Path.of("/r/report.html"), "sel", "0123", true));
        assertThat(access.read(PAGE, 10)).contains(
                new PagePort.Reading("Report", Path.of("/r/report.html"), "sel", "0123456789", false));
        assertThat(access.read(PAGE, 0).map(PagePort.Reading::text)).contains("");
    }

    /// A panel with no HTML page, one showing the page's text and an unknown panel are refused by a snapshot, the
    /// refusal naming the panel; a read of them is empty.
    @Test
    void aPanelWithNoRenderedPageIsRefused() throws Exception {
        final PageAccess access = access();

        assertThat(access.snapshot(NOTES)).startsWith("refused").contains("panel=notes");
        assertThat(access.snapshot(TEXT)).startsWith("refused").contains("panel=text");
        assertThat(access.snapshot(new PanelId("gone"))).startsWith("refused").contains("panel=gone");
        assertThat(access.read(NOTES, 100)).isEmpty();
        assertThat(access.read(TEXT, 100)).isEmpty();
        assertThat(access.read(new PanelId("gone"), 100)).isEmpty();
    }

    /// Before the window is built no page is listed, a snapshot is refused naming the panel, and a read is empty.
    @Test
    void beforeTheWindowIsBuiltEverythingIsRefused() {
        final AppState s = AppState.initial(List.of(new PanelState(PAGE, "Report", "", "")), List.of(), List.of());
        final PageAccess access = new PageAccess(() -> null, () -> s);

        assertThat(access.list()).isEmpty();
        assertThat(access.snapshot(PAGE)).startsWith("refused").contains("panel=page");
        assertThat(access.read(PAGE, 10)).isEqualTo(Optional.empty());
    }
}
