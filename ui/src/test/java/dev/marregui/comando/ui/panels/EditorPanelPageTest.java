package dev.marregui.comando.ui.panels;

import com.sun.security.auth.module.UnixSystem;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.schema.SchemaRegistry;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.ui.kit.desktop.Browser;
import dev.marregui.comando.ui.kit.desktop.Links;
import dev.marregui.comando.ui.kit.web.FakePageView;
import dev.marregui.comando.ui.kit.web.PageView;

import static org.assertj.core.api.Assertions.assertThat;

/// How [EditorPanel] shows an HTML file rendered, in a page view.
/// - an HTML document opens rendered, the page view in the preview card and the keyboard's target, no outline
/// - the mode switch goes to the text and back, the page read again; an edit is written before the page shows again
/// - a file changed on disk is read again by the page while it shows
/// - the page view is made once, closed once when the panel closes or its file is no longer HTML, and kept on a move
/// - with no page renderer the file stays as its text, with no switch
/// - a link the page follows goes to the browser for a web address, to the panel's opener for a file
class EditorPanelPageTest {

    private static final String HTML = "<html><head><title>Tests</title></head><body><h1>Tests</h1></body></html>";

    @TempDir
    Path root;

    /// A panel whose page views `pages` makes, set up by `setUp` on the EDT.
    private static EditorPanel panel(final Supplier<PageView> pages, final Consumer<EditorPanel> setUp)
            throws Exception {
        final EditorPanel[] p = new EditorPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            p[0] = new EditorPanel(new PanelState(new PanelId("report"), "report", "", ""), intent -> {
            }, new SchemaRegistry(), pages);
            setUp.accept(p[0]);
        });
        return p[0];
    }

    /// An HTML panel opened on `file`, its page view `page`.
    private static EditorPanel opened(final FakePageView page, final Path file) throws Exception {
        return panel(() -> page, p -> {
            p.editor().load(HTML);
            p.file(file, true);
        });
    }

    /// Lets the EDT run what was queued on it, twice: a task queued by a queued task runs too.
    private static void settle() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
        });
        SwingUtilities.invokeAndWait(() -> {
        });
    }

    /// A page in a folder the Files tab lists runs its scripts; a page elsewhere, a sibling folder whose name only
    /// begins like the listed one included, loads with its scripts off.
    @Test
    void onlyPagesInTheListedFoldersRunTheirScripts() throws Exception {
        final PanelState report = new PanelState(new PanelId("report"), "report", "", "");
        final AppState s = AppState.initial(List.of(report), List.of(), List.of())
                .withFolders(List.of(new FolderRoot("Kit", "/kit/root", "", List.of())));
        assertThat(scriptsOf(report, s, "/kit/root/maps/a.html")).containsExactly(true);
        assertThat(scriptsOf(report, s, "/tmp/a.html")).containsExactly(false);
        assertThat(scriptsOf(report, s, "/kit/rootless/a.html")).containsExactly(false);
    }

    /// A page comando's own work made runs its scripts wherever it was made: in a session's working directory, or in
    /// Claude Code's temporary folder of this user; a page beside that folder, or another user's, does not.
    @Test
    void pagesInASessionsWorkingDirectoryOrClaudeCodesTemporaryFolderRunTheirScripts() throws Exception {
        final PanelState report = new PanelState(new PanelId("report"), "report", "", "");
        final AppState s = AppState.initial(List.of(report), List.of(),
                List.of(SessionState.closed(SessionSpec.fresh("work", "/clones/t7-edge"))));
        final String uid = String.valueOf(new UnixSystem().getUid());
        assertThat(scriptsOf(report, s, "/clones/t7-edge/docs/map.html")).containsExactly(true);
        assertThat(scriptsOf(report, s, "/tmp/claude-" + uid + "/session/scratchpad/a.html")).containsExactly(true);
        assertThat(scriptsOf(report, s, "/private/tmp/claude-" + uid + "/a.html")).containsExactly(true);
        assertThat(scriptsOf(report, s, "/tmp/claude-" + uid + "0/a.html")).containsExactly(false);
        assertThat(scriptsOf(report, s, "/tmp/a.html")).containsExactly(false);
    }

    /// A panel is given its file before its first state, as the workspace opens one: the page loads with its scripts
    /// off, since no folder is known yet, and loads again with them on once the state names the page's folder; a
    /// later state that changes nothing loads it no more.
    @Test
    void aPageOpenedBeforeThePanelsFirstStateRunsItsScriptsOnceTheStateNamesItsFolder() throws Exception {
        final PanelState report = new PanelState(new PanelId("report"), "report", "", "");
        final AppState s = AppState.initial(List.of(report), List.of(), List.of())
                .withFolders(List.of(new FolderRoot("Scratchpad", "/brain/scratchpad", "", List.of())));
        final FakePageView page = new FakePageView();
        final EditorPanel panel = panel(() -> page, x -> {
            x.editor().load(HTML);
            x.file(Path.of("/brain/scratchpad/edg/map.html"), true);
        });
        settle();
        SwingUtilities.invokeAndWait(() -> panel.render(report, s));
        settle();
        SwingUtilities.invokeAndWait(() -> panel.render(report, s));
        settle();
        assertThat(page.scripts()).containsExactly(false, true);
    }

    /// Whether the loads of a panel rendered with `s` and opened on the HTML file `file` ran the page's scripts.
    private static List<Boolean> scriptsOf(final PanelState p, final AppState s, final String file) throws Exception {
        final FakePageView page = new FakePageView();
        panel(() -> page, x -> {
            x.render(p, s);
            x.editor().load(HTML);
            x.file(Path.of(file), true);
        });
        settle();
        return page.scripts();
    }

    /// An HTML document opens rendered: the page view loads the file and is the card shown, the keyboard's target and
    /// the page agents reach; the text is not shown, the browser button stays, and there is no outline.
    @Test
    void anHtmlDocumentOpensRenderedInThePageView() throws Exception {
        final FakePageView page = new FakePageView();
        final Path file = Path.of("/build/reports/index.html");
        final EditorPanel p = opened(page, file);

        assertThat(p.previewing()).isTrue();
        assertThat(p.previewOffered()).isTrue();
        assertThat(p.modeLabel()).isEqualTo("Edit");
        assertThat(page.loaded()).containsExactly(file);
        assertThat(page.component().isVisible()).as("the page's card is shown").isTrue();
        assertThat(p.editor().component().isVisible()).as("the text's card is not").isFalse();
        assertThat(p.focusTarget()).isSameAs(page.component());
        assertThat(p.shownPage()).containsSame(page);
        assertThat(p.browserOffered()).isTrue();
        assertThat(p.outlineShows()).isFalse();
    }

    /// The mode switch shows the text to edit, then the page again, which reads its file again; the page view is made
    /// once.
    @Test
    void theModeSwitchGoesToTheTextAndBack() throws Exception {
        final FakePageView page = new FakePageView();
        final AtomicInteger made = new AtomicInteger();
        final Path file = Path.of("/build/reports/index.html");
        final EditorPanel p = panel(() -> {
            made.incrementAndGet();
            return page;
        }, x -> {
            x.editor().load(HTML);
            x.file(file, true);
        });

        SwingUtilities.invokeAndWait(p::togglePreview);
        assertThat(p.previewing()).isFalse();
        assertThat(p.editor().component().isVisible()).isTrue();
        assertThat(page.component().isVisible()).isFalse();
        assertThat(p.focusTarget()).isSameAs(p.editor().area());
        assertThat(p.shownPage()).isEmpty();

        SwingUtilities.invokeAndWait(p::togglePreview);
        assertThat(p.previewing()).isTrue();
        assertThat(page.component().isVisible()).isTrue();
        assertThat(page.loaded()).containsExactly(file);
        assertThat(page.reloads()).as("the page reads the file again").isEqualTo(1);
        assertThat(made.get()).isEqualTo(1);
    }

    /// Text edited in the text view is written to the file before the page shows again, and the page reads the file
    /// once it is written.
    @Test
    void anEditIsWrittenBeforeThePageShowsAgain() throws Exception {
        final FakePageView page = new FakePageView();
        final CompletableFuture<String> written = new CompletableFuture<>();
        final AtomicInteger saves = new AtomicInteger();
        final EditorPanel p = opened(page, Path.of("/build/reports/index.html"));
        SwingUtilities.invokeAndWait(() -> {
            p.onSaveNow(() -> {
                saves.incrementAndGet();
                return written;
            });
            p.togglePreview();
            p.editor().area().insert("<!-- edited -->", 0);
            p.togglePreview();
        });
        settle();
        assertThat(saves.get()).isEqualTo(1);
        assertThat(page.reloads()).as("not before the text is written").isZero();

        written.complete(null);
        settle();
        assertThat(page.reloads()).isEqualTo(1);
    }

    /// A file changed on disk is read again by the page while it shows, and not while the text shows; the text takes
    /// the new content either way.
    @Test
    void aReloadReadsThePageAgain() throws Exception {
        final FakePageView page = new FakePageView();
        final EditorPanel p = opened(page, Path.of("/build/reports/index.html"));

        SwingUtilities.invokeAndWait(() -> p.reload("<html><body>two</body></html>"));
        assertThat(page.reloads()).isEqualTo(1);
        assertThat(p.editor().text()).contains("two");

        SwingUtilities.invokeAndWait(() -> {
            p.togglePreview();
            p.reload("<html><body>three</body></html>");
        });
        assertThat(page.reloads()).as("the text shows").isEqualTo(1);
        assertThat(p.editor().text()).contains("three");
    }

    /// A panel taken out of its strip closes its page view once, a turn later; a panel moved, out and back in the
    /// same turn, keeps it.
    @Test
    void closingThePanelClosesItsPageOnce() throws Exception {
        final FakePageView page = new FakePageView();
        final EditorPanel p = opened(page, Path.of("/build/reports/index.html"));
        final JPanel strip = new JPanel();
        final JPanel other = new JPanel();

        SwingUtilities.invokeAndWait(() -> {
            strip.add(p);
            strip.remove(p);
            other.add(p);
        });
        settle();
        assertThat(page.closes()).as("a move keeps the page").isZero();
        assertThat(p.shownPage()).containsSame(page);

        SwingUtilities.invokeAndWait(() -> other.remove(p));
        settle();
        assertThat(page.closes()).isEqualTo(1);
        assertThat(p.shownPage()).isEmpty();

        SwingUtilities.invokeAndWait(() -> p.file(Path.of("/s/notes.md"), true));
        assertThat(page.closes()).as("closed once").isEqualTo(1);
    }

    /// A panel whose file becomes a Markdown file closes its page view and shows the Markdown rendered.
    @Test
    void aFileThatIsNoLongerHtmlClosesThePage() throws Exception {
        final FakePageView page = new FakePageView();
        final EditorPanel p = opened(page, Path.of("/build/reports/index.html"));

        SwingUtilities.invokeAndWait(() -> {
            p.editor().load("# Notes");
            p.file(Path.of("/s/notes.md"), true);
        });
        assertThat(page.closes()).isEqualTo(1);
        assertThat(p.shownPage()).isEmpty();
        assertThat(p.previewing()).isTrue();
        assertThat(p.focusTarget()).isSameAs(p.rendered());
        assertThat(p.rendered().getText()).contains("Notes");
    }

    /// With no page renderer (headless, or the toolkit failed) an HTML document stays as its editable text, with no
    /// switch and the browser button at hand.
    @Test
    void anHtmlDocumentWithNoRendererStaysAsText() throws Exception {
        final EditorPanel p = panel(() -> {
            throw new UnsupportedOperationException("no page renderer here");
        }, x -> {
            x.editor().load(HTML);
            x.file(Path.of("/build/reports/index.html"), true);
        });
        assertThat(p.previewing()).isFalse();
        assertThat(p.previewOffered()).isFalse();
        assertThat(p.browserOffered()).isTrue();
        assertThat(p.editable()).isTrue();
        assertThat(p.focusTarget()).isSameAs(p.editor().area());

        SwingUtilities.invokeAndWait(p::togglePreview);
        assertThat(p.previewing()).as("the switch does nothing").isFalse();
    }

    /// A link the page follows to a web address goes to the browser; to a local file, an HTML page included, it goes
    /// to the panel's opener.
    @Test
    void aLinkThePageFollowsGoesToTheBrowserOrTheOpener() throws Exception {
        final Path notes = Files.writeString(root.resolve("notes.md"), "# n");
        final Path other = Files.writeString(root.resolve("other.html"), "<p>o</p>");
        final FakePageView page = new FakePageView();
        final List<String> browsed = new CopyOnWriteArrayList<>();
        final List<Path> opened = new CopyOnWriteArrayList<>();
        Links.browseWith(browsed::add);
        try {
            final EditorPanel p = panel(() -> page, x -> {
                x.onOpenFile((f, heading) -> opened.add(f));
                x.editor().load(HTML);
                x.file(root.resolve("index.html"), true);
            });
            assertThat(p.previewing()).isTrue();
            SwingUtilities.invokeAndWait(() -> {
                page.follow(URI.create("https://example.org/x"));
                page.follow(notes.toUri());
                page.follow(other.toUri());
            });
            assertThat(browsed).containsExactly("https://example.org/x");
            assertThat(opened).containsExactly(notes, other);
        } finally {
            Links.browseWith(Browser::open);
        }
    }
}
