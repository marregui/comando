package dev.marregui.comando.app;

import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.Point;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import javax.imageio.ImageIO;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.ui.kit.desktop.Browser;
import dev.marregui.comando.ui.kit.desktop.Links;
import dev.marregui.comando.ui.kit.text.HtmlPane;

import static org.assertj.core.api.Assertions.assertThat;

/// A memory file in a panel, as comando shows it. Double clicks are dispatched to the rendered page in the real window,
/// which needs a display: `./gradlew :app:uiTest`. The page is written to `build/snapshots/memory-page.png`.
/// - front matter shows as facts, not a rule and a heading
/// - a double click on a `[[name]]` link opens the file whose front matter declares that name, in a new panel
/// - a double click on a web address goes to the browser
/// - a file link without a line is found under the session's folder and opens in a panel, once
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class LinksUiTest {

    @TempDir
    Path home;

    /// The page shows the front matter's `name` and `metadata.type` as facts and the note link as its name without
    /// brackets; a double click on the note link opens the file declaring that name in a new panel, and on a URL calls
    /// the browser.
    @Test
    void aDoubleClickOnANoteLinkOpensItsFileInAPanelAndOnAnAddressTheBrowser() throws Exception {
        final Path brain = home.resolve("brain");
        Files.createDirectories(brain.resolve(".git"));
        final Path memory = Files.createDirectories(brain.resolve("MEMORY"));
        final Path page = memory.resolve("reference_guide_page.md");
        Files.writeString(page, """
                ---
                name: reference-guide-page
                description: "A page that links to another memory"
                metadata:
                  type: reference
                ---

                Read [[feedback-verification-discipline]] before https://example.org/guide today.
                """);
        Files.writeString(memory.resolve("feedback_verification_discipline.md"), """
                ---
                name: feedback-verification-discipline
                description: x
                ---

                Verify.
                """);
        final List<String> browsed = new CopyOnWriteArrayList<>();
        final App app = new App(new AppConfig(home.resolve("data"), home.resolve("no-claude").toString(), home.resolve("projects"), false),
                "links", () -> {
        });
        try {
            app.start();
            final StateLoop loop = app.loop();
            Awaitility.await("the window").atMost(Duration.ofSeconds(20)).until(() -> loop.state().focused() != null);
            Links.browseWith(browsed::add);
            SwingUtilities.invokeAndWait(() -> Links.open(page.toString(), null));
            Awaitility.await("the page's panel").atMost(Duration.ofSeconds(10)).until(() -> tabs().contains("reference_guide_page.md"));
            Awaitility.await("the page rendered").atMost(Duration.ofSeconds(10)).until(() -> pane() != null);
            final HtmlPane pane = pane();
            final String shown = onEdt(() -> pane.getDocument().getText(0, pane.getDocument().getLength()));
            assertThat(shown).as("the front matter's facts").contains("name", "reference-guide-page", "metadata.type");
            assertThat(shown).as("the note link shows as its name, without brackets").contains("feedback-verification-discipline").doesNotContain("[[", "---");
            final String html = onEdt(() -> pane.getText());
            assertThat(html).doesNotContain("<h2");
            snapshot(pane);

            doubleClick(pane, shown.indexOf("feedback-verification-discipline") + 3);
            Awaitility.await("the linked memory's panel").atMost(Duration.ofSeconds(10))
                    .until(() -> tabs().stream().anyMatch(t -> t.startsWith("feedback_verification_discipl")));

            Awaitility.await("the page in front again").atMost(Duration.ofSeconds(10)).until(() -> {
                SwingUtilities.invokeAndWait(() -> Links.open(page.toString(), null));
                return pane() != null;
            });
            doubleClick(pane(), shown.indexOf("example.org"));
            Awaitility.await("the browser").atMost(Duration.ofSeconds(5)).until(() -> !browsed.isEmpty());
            assertThat(browsed).containsExactly("https://example.org/guide");
        } finally {
            Links.browseWith(Browser::open);
            app.close();
        }
    }

    /// A file link without a line, written relative to a folder deep under the session's, is found there and opens in a
    /// panel as the Files tab opens a file, not as a side view; a second follow brings that panel forward instead of
    /// opening another.
    @Test
    void aFileLinkWithoutALineIsFoundUnderTheSessionsFolderAndOpensInAPanelAsTheFilesTabOpensIt() throws Exception {
        final Path work = Files.createDirectories(home.resolve("work"));
        final Path doc = work.resolve("BRAIN/SCRATCHPAD/2026-10-28/harness/07-fleet-vision.md");
        Files.createDirectories(doc.getParent());
        Files.writeString(doc, "# Fleet vision\n");
        final App app = new App(new AppConfig(home.resolve("data"), home.resolve("no-claude").toString(), home.resolve("projects"), false),
                "links", () -> {
        });
        try {
            app.start();
            final StateLoop loop = app.loop();
            Awaitility.await("the window").atMost(Duration.ofSeconds(20)).until(() -> loop.state().focused() != null);
            loop.dispatch(new Intent.SaveSession("", SessionSpec.fresh("lane", work.toString())));
            Awaitility.await("the session").atMost(Duration.ofSeconds(10)).until(() -> loop.state().session("lane").isPresent());
            final int before = loop.state().panels().size();

            loop.dispatch(new Intent.OpenFile("lane", "harness/07-fleet-vision.md", 0));

            Awaitility.await("a panel of the file").atMost(Duration.ofSeconds(10))
                    .until(() -> loop.state().panels().stream().anyMatch(p -> p.title().equals("07-fleet-vision.md")));
            final PanelState panel = loop.state().panels().stream().filter(p -> p.title().equals("07-fleet-vision.md")).findFirst().orElseThrow();
            assertThat(loop.state().views()).as("not a side view").noneMatch(v -> v.title().contains("07-fleet-vision"));
            Awaitility.await("in front").atMost(Duration.ofSeconds(5)).until(() -> loop.state().focused().equals(panel.id()));

            final int after = loop.state().panels().size();
            assertThat(after).isEqualTo(before + 1);
            loop.dispatch(new Intent.OpenFile("lane", "harness/07-fleet-vision.md", 0));
            Awaitility.await("followed again: the same panel").during(Duration.ofMillis(800)).atMost(Duration.ofSeconds(3))
                    .until(() -> loop.state().panels().size() == after);
        } finally {
            app.close();
        }
    }

    /** A left double click, as the desktop delivers it, on the character at {@code offset} of the page. */
    private static void doubleClick(final HtmlPane pane, final int offset) throws Exception {
        final Point p = onEdt(() -> {
            final Rectangle2D r = pane.modelToView2D(offset);
            return new Point((int) r.getCenterX(), (int) r.getCenterY());
        });
        SwingUtilities.invokeAndWait(() -> {
            for (int clicks = 1; clicks <= 2; clicks++) {
                final long now = System.currentTimeMillis();
                pane.dispatchEvent(new MouseEvent(pane, MouseEvent.MOUSE_PRESSED, now, InputEvent.BUTTON1_DOWN_MASK, p.x, p.y, clicks, false, MouseEvent.BUTTON1));
                pane.dispatchEvent(new MouseEvent(pane, MouseEvent.MOUSE_RELEASED, now, 0, p.x, p.y, clicks, false, MouseEvent.BUTTON1));
                pane.dispatchEvent(new MouseEvent(pane, MouseEvent.MOUSE_CLICKED, now, 0, p.x, p.y, clicks, false, MouseEvent.BUTTON1));
            }
        });
    }

    private static void snapshot(final HtmlPane pane) throws Exception {
        final Path out = Path.of("build/snapshots/memory-page.png");
        Files.createDirectories(out.getParent());
        final BufferedImage image = onEdt(() -> {
            final BufferedImage b = new BufferedImage(Math.max(1, pane.getWidth()), Math.max(1, Math.min(400, pane.getHeight())), BufferedImage.TYPE_INT_ARGB);
            pane.paint(b.getGraphics());
            return b;
        });
        ImageIO.write(image, "png", out.toFile());
    }

    /** The rendered page of the reference page's panel, while it shows. */
    private static HtmlPane pane() throws Exception {
        return onEdt(() -> first(HtmlPane.class, p -> p.isShowing() && p.getText().contains("reference-guide-page")));
    }

    /** The titles of the panels' tabs; a long one ends in "…". */
    private static List<String> tabs() throws Exception {
        return onEdt(() -> {
            final List<JTabbedPane> panes = new ArrayList<>();
            for (final Frame f : Frame.getFrames()) {
                if (f.isVisible()) {
                    collect(f, JTabbedPane.class, t -> true, panes);
                }
            }
            final List<String> out = new ArrayList<>();
            for (final JTabbedPane t : panes) {
                for (int i = 0; i < t.getTabCount(); i++) {
                    out.add(t.getTitleAt(i));
                }
            }
            return out;
        });
    }

    private static <T extends Component> T first(final Class<T> type, final Predicate<T> which) {
        final List<T> out = new ArrayList<>();
        for (final Frame f : Frame.getFrames()) {
            if (f.isVisible()) {
                collect(f, type, which, out);
            }
        }
        return out.isEmpty() ? null : out.getFirst();
    }

    private static <T extends Component> void collect(final Component c, final Class<T> type, final Predicate<T> which, final List<T> out) {
        if (type.isInstance(c) && which.test(type.cast(c))) {
            out.add(type.cast(c));
        }
        if (c instanceof Container k) {
            for (final Component child : k.getComponents()) {
                collect(child, type, which, out);
            }
        }
    }

    private static <T> T onEdt(final Callable<T> call) throws Exception {
        final AtomicReference<T> out = new AtomicReference<>();
        final AtomicReference<Exception> failed = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                out.set(call.call());
            } catch (final Exception e) {
                failed.set(e);
            }
        });
        if (failed.get() != null) {
            throw failed.get();
        }
        return out.get();
    }
}
