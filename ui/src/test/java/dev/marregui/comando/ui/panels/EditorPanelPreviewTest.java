package dev.marregui.comando.ui.panels;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.BadLocationException;

import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.schema.SchemaRegistry;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.ui.kit.desktop.Browser;
import dev.marregui.comando.ui.kit.desktop.Links;
import dev.marregui.comando.ui.kit.text.LocalImages;
import dev.marregui.comando.ui.widgets.editor.CommandEditor;

import static org.assertj.core.api.Assertions.assertThat;

/// How [EditorPanel] shows a file by kind: log, Markdown, HTML, source, or the panel's own text; and how it follows
/// links.
/// - a log is read-only and a live view: it follows its end while the end is in view, and keeps the reader's place and
///   selection
/// - a Markdown document opens rendered, with an Edit switch, and stays editable
/// - an HTML document opens as text with a browser button; comando does not render HTML
/// - Clear empties the panel's own text, undoably, and never a document
/// - the panel's own text opens in the editor with the panel style and may be previewed
/// - a source file is coloured as its language and has no preview
/// - the preview shows pictures beside the Markdown file and follows double-clicked links to files, headings and pages
class EditorPanelPreviewTest {

    private static String shown(final EditorPanel p) {
        try {
            return p.rendered().getDocument().getText(0, p.rendered().getDocument().getLength());
        } catch (final BadLocationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static EditorPanel panel(final String title, final Consumer<EditorPanel> setUp) throws Exception {
        final EditorPanel[] p = new EditorPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            p[0] = new EditorPanel(new PanelState(new PanelId(title), title, "", ""), intent -> {
            }, new SchemaRegistry());
            setUp.accept(p[0]);
        });
        return p[0];
    }

    /// A document whose name ends in `.log` opens read-only with the caret at its end, follows appended text on reload,
    /// and keeps a caret the reader moved up; a Markdown file stays editable.
    @Test
    void aLogIsReadOnlyAndFollowsItsEnd() throws Exception {
        final EditorPanel log = panel("comando-0.log", p -> {
            p.editor().load("one\ntwo\n");
            p.file(Path.of("/home/.comando/logs/comando-0.log"), true);
        });
        assertThat(log.isLog()).isTrue();
        assertThat(log.editor().area().isEditable()).isFalse();
        assertThat(log.editor().area().getCaretPosition()).as("opens at its end").isEqualTo(8);
        SwingUtilities.invokeAndWait(() -> log.reload("one\ntwo\nthree\n"));
        assertThat(log.editor().area().getCaretPosition()).as("follows what was appended").isEqualTo(14);

        final EditorPanel notes = panel("notes", p -> p.file(Path.of("/s/notes.md"), true));
        assertThat(notes.editor().area().isEditable()).isTrue();
    }

    /// A log is a live view of its file: what is appended shows at once and, while the view shows the end, keeps it
    /// there; a reader who scrolled up to read, or selected text to copy, keeps their place and selection while the text
    /// still grows below; back at the end, the view follows again; a file rolled over replaces the text.
    @Test
    void aLogIsALiveViewThatKeepsTheReadersPlaceAndSelection() throws Exception {
        final StringBuilder lines = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            lines.append("line ").append(i).append('\n');
        }
        final String first = lines.toString();
        final EditorPanel log = panel("hivemq.log", p -> {
            p.editor().load(first);
            p.file(Path.of("/home/.comando/edge/log/hivemq.log"), true);
            p.setSize(600, 300);
            layOut(p);
        });
        final JViewport v = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, log.editor().area());
        SwingUtilities.invokeAndWait(() -> log.reload(first + "line 200\n"));
        SwingUtilities.invokeAndWait(() -> { });
        assertThat(log.followsEnd()).isTrue();
        assertThat(atEnd(v)).as("following: the new line is in view").isTrue();

        // the reader scrolls up and selects a word to copy
        SwingUtilities.invokeAndWait(() -> {
            v.setViewPosition(new Point(0, 0));
            log.editor().area().select(5, 7);
        });
        assertThat(log.followsEnd()).as("scrolled up: no longer following").isFalse();
        SwingUtilities.invokeAndWait(() -> log.reload(first + "line 200\nline 201\n"));
        assertThat(log.editor().text()).endsWith("line 201\n");
        assertThat(v.getViewPosition().y).as("the reader's place").isZero();
        assertThat(log.editor().area().getSelectedText()).as("the reader's selection").isEqualTo("0\n");

        // back to the end: following again
        SwingUtilities.invokeAndWait(() -> v.setViewPosition(new Point(0, v.getView().getHeight() - v.getExtentSize().height)));
        assertThat(log.followsEnd()).isTrue();
        SwingUtilities.invokeAndWait(() -> log.reload(first + "line 200\nline 201\nline 202\n"));
        SwingUtilities.invokeAndWait(() -> { });
        assertThat(atEnd(v)).as("view " + v.getViewRect() + " of " + v.getView().getSize() + ", preferred " + v.getView().getPreferredSize()
                + ", follows " + log.followsEnd()).isTrue();

        SwingUtilities.invokeAndWait(() -> log.reload("rolled over\n"));
        assertThat(log.editor().text()).as("a new file replaces the text").isEqualTo("rolled over\n");
    }

    private static boolean atEnd(final JViewport v) {
        final Rectangle seen = v.getViewRect();
        return seen.y + seen.height >= v.getView().getHeight() - 20;
    }

    private static void layOut(final java.awt.Component c) {
        c.doLayout();
        if (c instanceof java.awt.Container k) {
            for (final java.awt.Component child : k.getComponents()) {
                layOut(child);
            }
        }
    }

    /// A Markdown document is editable, opens rendered with the Edit switch offered, and [EditorPanel#edit] shows the
    /// text; a JSON document is editable with no preview offered.
    @Test
    void aMarkdownDocumentOpensRenderedAndIsEditedLikeAnyOther() throws Exception {
        final EditorPanel memory = panel("MEMORY", p -> {
            p.editor().load("# Memory\n");
            p.file(Path.of("/m/MEMORY.md"), true);
        });
        assertThat(memory.editable()).isTrue();
        assertThat(memory.previewing()).as("Markdown opens rendered").isTrue();
        assertThat(memory.previewOffered()).as("with the Edit switch").isTrue();
        SwingUtilities.invokeAndWait(memory::edit);
        assertThat(memory.previewing()).as("asked to edit: the text").isFalse();

        final EditorPanel json = panel("agents", p -> p.file(Path.of("/m/agents.json"), true));
        assertThat(json.editable()).isTrue();
        assertThat(json.previewOffered()).as("only Markdown renders").isFalse();
    }

    /// An HTML document opens as editable text with the browser button and no preview; a Markdown document and a
    /// panel's own `.html` text get no browser button.
    @Test
    void anHtmlDocumentOpensAsTextWithTheBrowserAtHand() throws Exception {
        final EditorPanel page = panel("report", p -> {
            p.editor().load("<html><body><h1>Tests</h1></body></html>");
            p.file(Path.of("/build/reports/index.html"), true);
        });
        assertThat(page.previewing()).as("comando does not render HTML").isFalse();
        assertThat(page.previewOffered()).isFalse();
        assertThat(page.browserOffered()).isTrue();
        assertThat(page.editable()).isTrue();

        final EditorPanel notes = panel("notes", p -> p.file(Path.of("/s/notes.md"), true));
        assertThat(notes.browserOffered()).as("Markdown has no browser button").isFalse();
        final EditorPanel own = panel("own", p -> p.file(Path.of("/home/.comando/panels/own.html"), false));
        assertThat(own.browserOffered()).as("a panel's own text is never a page").isFalse();
    }

    /// Clear empties the panel's own text and is then no longer offered; undo restores the text and the offer; a
    /// document of its own is never offered Clear.
    @Test
    void clearEmptiesThePanelsOwnTextButNeverADocument() throws Exception {
        final EditorPanel own = panel("notes", p -> {
            p.editor().load("select 1;\n");
            p.file(Path.of("/home/.comando/panels/notes.md"), false);
        });
        assertThat(own.clearOffered()).isTrue();
        SwingUtilities.invokeAndWait(own::clickClear);
        assertThat(own.editor().text()).isEmpty();
        assertThat(own.clearOffered()).as("nothing left to clear").isFalse();
        SwingUtilities.invokeAndWait(() -> own.editor().area().undoLastAction());
        assertThat(own.editor().text()).isEqualTo("select 1;\n");
        assertThat(own.clearOffered()).isTrue();

        final EditorPanel document = panel("PLAN", p -> {
            p.editor().load("# Plan\n");
            p.file(Path.of("/s/PLAN.md"), true);
        });
        assertThat(document.clearOffered()).as("a file of its own is not emptied from the panel").isFalse();
    }

    /// A Markdown document opens rendered without its Markdown marks, with Markdown syntax colouring and a button
    /// naming the other view; an outside reload updates both views; toggling shows the text, and a later render keeps
    /// the editor.
    @Test
    void aMarkdownDocumentOpensRenderedAndEditSwitchesToTheText() throws Exception {
        final EditorPanel panel = panel("LANE-PLAN", p -> {
            p.editor().load("# Lane\n\nThe **plan**.\n");
            p.file(Path.of("/s/LANE-PLAN.md"), true);
        });
        assertThat(panel.previewOffered()).isTrue();
        assertThat(panel.previewing()).as("a scratchpad document opens rendered").isTrue();
        assertThat(panel.modeLabel()).as("the button names the other view").isEqualTo("Edit");
        assertThat(shown(panel)).contains("Lane").contains("The plan.").doesNotContain("**").doesNotContain("#");
        assertThat(panel.editor().area().getSyntaxEditingStyle()).isEqualTo(SyntaxConstants.SYNTAX_STYLE_MARKDOWN);

        SwingUtilities.invokeAndWait(() -> panel.reload("# Lane v2\n"));
        assertThat(panel.editor().text()).isEqualTo("# Lane v2\n");
        assertThat(shown(panel)).as("an outside change shows in the preview too").contains("Lane v2");

        SwingUtilities.invokeAndWait(panel::togglePreview);
        assertThat(panel.previewing()).as("Edit shows the text").isFalse();
        assertThat(panel.modeLabel()).isEqualTo("Preview");

        SwingUtilities.invokeAndWait(() -> panel.file(Path.of("/s/LANE-PLAN.md"), true));
        assertThat(panel.previewing()).as("a render does not take the editor away").isFalse();
    }

    /// The panel's own Markdown text opens in the editor with [CommandEditor#PANEL_STYLE], also after the panel is
    /// renamed, and the toggle can still preview it.
    @Test
    void aPanelsOwnTextStaysInTheEditorAsSqlAndPrompts() throws Exception {
        final EditorPanel panel = panel("spec", p -> {
            p.editor().load("# spec\n\nselect 1;\n");
            p.file(Path.of("/home/.comando/panels/spec.md"), false);
        });
        assertThat(panel.previewOffered()).isTrue();
        assertThat(panel.previewing()).isFalse();
        assertThat(panel.modeLabel()).isEqualTo("Preview");
        assertThat(panel.editor().area().getSyntaxEditingStyle()).isEqualTo(CommandEditor.PANEL_STYLE);

        SwingUtilities.invokeAndWait(() -> panel.file(Path.of("/home/.comando/panels/spec 2.md"), false));
        assertThat(panel.previewing()).as("a renamed panel's file is still its own").isFalse();

        SwingUtilities.invokeAndWait(panel::togglePreview);
        assertThat(panel.previewing()).isTrue();
    }

    /// A `.java` or `.py` file gets its language's syntax style, offers no preview and ignores a preview toggle.
    @Test
    void aSourceFileIsColouredAsItsLanguageAndStaysAsText() throws Exception {
        final EditorPanel java = panel("Red.java", p -> {
            p.file(Path.of("/s/work/Red.java"), true);
            p.togglePreview();
        });
        assertThat(java.previewOffered()).isFalse();
        assertThat(java.previewing()).isFalse();
        assertThat(java.editor().area().getSyntaxEditingStyle()).isEqualTo(SyntaxConstants.SYNTAX_STYLE_JAVA);

        final EditorPanel python = panel("rig.py", p -> p.file(Path.of("/s/work/rig.py"), true));
        assertThat(python.editor().area().getSyntaxEditingStyle()).isEqualTo(SyntaxConstants.SYNTAX_STYLE_PYTHON);
    }

    /// A relative picture link resolves against the document's folder, also as `./images/…` after a reload; the panel's
    /// own text, whose folder has no such picture, shows "no such file".
    @Test
    void aMarkdownFileShowsThePicturesBesideItAsItIsEditedAndReloaded(@TempDir final Path dir) throws Exception {
        final Path doc = dir.resolve("SCRATCHPAD/2026-10-17/READ-01.md");
        final Path chart = doc.resolveSibling("images/chart.png");
        Files.createDirectories(chart.getParent());
        ImageIO.write(new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB), "png", chart.toFile());
        LocalImages.install(dir.resolve("cache"));
        try {
            // read once, so the panel finds it read
            final CountDownLatch read = new CountDownLatch(1);
            if (LocalImages.current().picture(chart, read::countDown) instanceof LocalImages.Picture.Pending) {
                assertThat(read.await(10, TimeUnit.SECONDS)).isTrue();
            }
            final EditorPanel panel = panel("READ-01", p -> {
                p.editor().load("# Findings\n\n![chart](images/chart.png)\n");
                p.file(doc, true);
            });
            assertThat(panel.previewing()).isTrue();
            assertThat(panel.rendered().getText()).as("the picture beside the file").contains(chart.toUri().toString());
            SwingUtilities.invokeAndWait(() -> panel.reload("# Findings v2\n\n![chart](./images/chart.png)\n"));
            assertThat(panel.rendered().getText()).contains("Findings v2").contains(chart.toUri().toString());

            final EditorPanel own = panel("spec", p -> {
                p.editor().load("![chart](images/chart.png)\n");
                p.file(dir.resolve("panels/spec.md"), false);
                p.togglePreview();
            });
            assertThat(own.rendered().getText()).as("its folder holds no such picture").contains("no such file: chart.png");
        } finally {
            LocalImages.install(null);
        }
    }

    /// In the preview a double-clicked relative link of any file type opens in a panel, an `https` link opens in the
    /// browser only on a double click, and a `mailto` link does nothing.
    @Test
    void anyLinkDoubleClickedInThePreviewIsFollowed() throws Exception {
        final List<String> opened = new CopyOnWriteArrayList<>();
        final List<String> browsed = new CopyOnWriteArrayList<>();
        Links.browseWith(browsed::add);
        try {
            final EditorPanel doc = panel("build", p -> {
                p.editor().load("[build](build.gradle.kts), [site](https://example.com/x) and [mail](mailto:a@b.c)");
                p.file(Path.of("/kb/claude/INTERNALS.md"), true);
                p.onOpenFile((f, heading) -> opened.add(f.toString()));
            });
            SwingUtilities.invokeAndWait(() -> {
                doc.rendered().fireHyperlinkUpdate(link(doc, "https://example.com/x", 1));
                doc.rendered().fireHyperlinkUpdate(link(doc, "build.gradle.kts", 2));
                doc.rendered().fireHyperlinkUpdate(link(doc, "https://example.com/x", 2));
                doc.rendered().fireHyperlinkUpdate(link(doc, "mailto:a@b.c", 2));
            });
            assertThat(opened).as("a file of any kind beside the document opens in a panel").containsExactly("/kb/claude/build.gradle.kts");
            assertThat(browsed).as("a web page at the double click only").containsExactly("https://example.com/x");
        } finally {
            Links.browseWith(Browser::open);
        }
    }

    /// In the editor text, a URL and a Markdown link with a heading anchor are followed; a position on no link is not.
    @Test
    void aLinkDoubleClickedInTheTextIsFollowedAsInThePreview() throws Exception {
        final List<String> opened = new CopyOnWriteArrayList<>();
        final List<String> browsed = new CopyOnWriteArrayList<>();
        Links.browseWith(browsed::add);
        try {
            final String text = "SELECT 1;\n-- see https://example.org/q and [setup](setup.md#2-install) here\n";
            final EditorPanel own = panel("sql", p -> {
                p.editor().load(text);
                p.file(Path.of("/docs/guide.sql"), true);
                p.onOpenFile((f, heading) -> opened.add(f + (heading == null ? "" : " at " + heading)));
            });
            final boolean[] followed = new boolean[3];
            SwingUtilities.invokeAndWait(() -> {
                followed[0] = own.followLinkAt(text.indexOf("example.org"));
                followed[1] = own.followLinkAt(text.indexOf("setup]"));
                followed[2] = own.followLinkAt(text.indexOf("SELECT"));
            });
            assertThat(followed).containsExactly(true, true, false);
            assertThat(browsed).containsExactly("https://example.org/q");
            assertThat(opened).containsExactly("/docs/setup.md at 2-install");
        } finally {
            Links.browseWith(Browser::open);
        }
    }

    /// A rendered relative link keeps its target; a single click and a same-document `#heading` open nothing, and a
    /// double click opens the file, at the heading the link names.
    @Test
    void aMarkdownLinkDoubleClickedInThePreviewOpensItsFile() throws Exception {
        final List<String> opened = new CopyOnWriteArrayList<>();
        final EditorPanel doc = panel("guide", p -> {
            p.editor().load("See [the setup](setup.md), [its install part](setup.md#2-install) and [this part](#part).");
            p.file(Path.of("/docs/guide.md"), true);
            p.onOpenFile((f, heading) -> opened.add(f + (heading == null ? "" : " at " + heading)));
        });
        assertThat(doc.previewing()).isTrue();
        assertThat(doc.rendered().getText()).as("the rendered link keeps its relative target").contains("href=\"setup.md\"");
        SwingUtilities.invokeAndWait(() -> {
            doc.rendered().fireHyperlinkUpdate(link(doc, "setup.md", 1));
            doc.rendered().fireHyperlinkUpdate(link(doc, "#part", 2));
        });
        assertThat(opened).as("a single click and a heading of the same document open nothing").isEmpty();
        SwingUtilities.invokeAndWait(() -> doc.rendered().fireHyperlinkUpdate(link(doc, "setup.md", 2)));
        SwingUtilities.invokeAndWait(() -> doc.rendered().fireHyperlinkUpdate(link(doc, "setup.md#2-install", 2)));
        assertThat(opened).as("the file, and the heading the link names in it")
                .containsExactly("/docs/setup.md", "/docs/setup.md at 2-install");
    }

    private static HyperlinkEvent link(final EditorPanel p, final String href, final int clicks) {
        final MouseEvent click = new MouseEvent(p.rendered(), MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 10, 10, clicks, false,
                MouseEvent.BUTTON1);
        return new HyperlinkEvent(p.rendered(), HyperlinkEvent.EventType.ACTIVATED, null, href, null, click);
    }
}
