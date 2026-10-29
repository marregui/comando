package dev.marregui.comando.ui.kit.text;

import dev.marregui.comando.ui.kit.theme.Theme;
import org.fife.ui.rsyntaxtextarea.TokenTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.ElementIterator;
import javax.swing.text.StyleConstants;
import javax.swing.text.View;
import javax.swing.text.html.CSS;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.ImageView;
import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.event.InputEvent;
import java.awt.geom.Rectangle2D;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/// [HtmlPane] shows rendered Markdown in the dark page style, with local pictures, diagrams and followable links.
/// - The page is transparent over the window's ground, its text ivory and its code in the editor's colours.
/// - A picture is no wider than the pane, and its alt text cannot add an attribute.
/// - A document shows the pictures it names beside it and only those, loading first, then drawn.
/// - Note links and addresses are followed at a real double click; a heading link scrolls the heading to the top.
/// - A picture rendered before the pane had a width fits once laid out; one drawn above the reader's heading keeps it
///   in view.
/// - Diagrams drawn later take their place without rendering the rest of the page again.
class HtmlPaneTest {

    /// The pane is not opaque, its body colour is the transcript text, and a Java fence carries the editor's keyword
    /// colour.
    @Test
    void aPageIsDarkTheWindowsGroundWithItsCodeInTheEditorsColours() throws Exception {
        HtmlPane[] p = new HtmlPane[1];
        SwingUtilities.invokeAndWait(() -> {
            p[0] = new HtmlPane();
            p[0].setMarkdown("# Plan\n\n```java\nint x = 1;\n```\n");
        });
        assertThat(p[0].isOpaque()).as("the window's ground shows through").isFalse();
        assertThat(bodyColor(p[0])).isEqualTo(Theme.hex(Theme.TRANSCRIPT_TEXT).toLowerCase(Locale.ROOT));
        assertThat(p[0].getText()).as("the code in the editor theme's colours")
                .contains(Theme.hex(Editors.colorOf(TokenTypes.RESERVED_WORD)).substring(1));
    }

    /// [HtmlPane#pictureHtml] scales a wide picture to the pane keeping its ratio, keeps a small one or one in an
    /// unlaid pane at its own size, keeps a hostile alt and a path with quotes and brackets inside their attributes as
    /// Swing parses them, says `loading …` while pending, and shows an unavailable picture's escaped alt with its
    /// reason.
    @Test
    void aPictureIsShownNoWiderThanThePaneAndItsAltCannotAddAnAttribute() throws Exception {
        Path file = Path.of("/kb/img/chart.png");
        String wide = HtmlPane.pictureHtml(new LocalImages.Picture.Ready(file, 1200, 600), "chart", file, 600);
        assertThat(wide).isEqualTo("<img src=\"file:///kb/img/chart.png\" width=\"600\" height=\"300\" alt=\"chart\">");
        assertThat(HtmlPane.pictureHtml(new LocalImages.Picture.Ready(file, 100, 50), "", file, 600))
                .as("a small one at its own size").contains("width=\"100\" height=\"50\"");
        assertThat(HtmlPane.pictureHtml(new LocalImages.Picture.Ready(file, 100, 50), "", file, 0))
                .as("a pane not laid out yet").contains("width=\"100\" height=\"50\"");

        String hostile = HtmlPane.pictureHtml(new LocalImages.Picture.Ready(file, 10, 10),
                "x\" src=\"http://127.0.0.1/steal.png' onload='y", file, 600);
        assertThat(img(hostile)).as("as Swing reads it: the file's src, the whole text as alt").containsExactlyInAnyOrderEntriesOf(Map.of(
                "src", "file:///kb/img/chart.png", "width", "10", "height", "10",
                "alt", "x\" src=\"http://127.0.0.1/steal.png' onload='y"));
        Path odd = Path.of("/kb/my \"quoted\" <dir>/a.png");
        assertThat(img(HtmlPane.pictureHtml(new LocalImages.Picture.Ready(odd, 10, 10), "a", odd, 600)))
                .as("a path is a URI: nothing in it ends the attribute")
                .containsEntry("src", odd.toUri().toString()).containsEntry("alt", "a").hasSize(4);

        assertThat(HtmlPane.pictureHtml(new LocalImages.Picture.Pending(), "chart", file, 600)).contains("loading chart.png…");
        assertThat(HtmlPane.pictureHtml(new LocalImages.Picture.Unavailable("no such file: chart.png"), "<b>chart</b>", file, 600))
                .isEqualTo("<i>&lt;b&gt;chart&lt;/b&gt;</i> <font color=\"" + Theme.hex(Theme.MUTED) + "\">(no such file: chart.png)</font>");
    }

    /// A PNG, an SVG and a GIF named relative to the document show `loading` first, then as images (the SVG from the
    /// cache), a missing one shows its reason and a web one stays alt text; a later render keeps the folder, and a
    /// render with no folder reads no relative picture.
    @Test
    void aDocumentShowsThePicturesItNamesBesideItAndOnlyThose(@TempDir Path dir) throws Exception {
        Path doc = dir.resolve("kb");
        Path chart = doc.resolve("images/chart.png");
        Path flow = doc.resolve("img/flow.svg");
        Path shared = dir.resolve("shared/logo.gif");
        for (Path p : new Path[]{chart, flow, shared}) {
            Files.createDirectories(p.getParent());
        }
        ImageIO.write(new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB), "png", chart.toFile());
        ImageIO.write(new BufferedImage(16, 16, BufferedImage.TYPE_BYTE_INDEXED), "gif", shared.toFile());
        Files.writeString(flow, "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"90\" height=\"30\"><rect width=\"90\" height=\"30\"/></svg>");
        String markdown = """
                # Report

                ![the chart](images/chart.png)

                ![the flow](./img/flow.svg "Flow")

                ![the logo](../shared/logo.gif) ![gone](images/gone.png) ![web](https://example.org/w.png)
                """;
        LocalImages.install(dir.resolve("cache"));
        try {
            HtmlPane[] pane = new HtmlPane[1];
            String[] first = new String[1];
            SwingUtilities.invokeAndWait(() -> {
                pane[0] = new HtmlPane();
                pane[0].setSize(800, 600);
                pane[0].setMarkdown(markdown, doc);
                // read here: once this returns, the EDT may already have drawn the pictures read meanwhile
                first[0] = pane[0].getText();
            });
            assertThat(first[0]).as("read on their own thread: loading first")
                    .contains("loading chart.png").contains("loading flow.svg").contains("loading logo.gif");
            // the pane asked first, so its redraw is queued on the EDT before the last of these is heard
            for (Path p : new Path[]{chart, flow, shared}) {
                CountDownLatch read = new CountDownLatch(1);
                if (LocalImages.current().picture(p, read::countDown) instanceof LocalImages.Picture.Pending) {
                    assertThat(read.await(10, TimeUnit.SECONDS)).isTrue();
                }
            }
            SwingUtilities.invokeAndWait(() -> { });
            SwingUtilities.invokeAndWait(() -> { });
            String html = pane[0].getText();
            assertThat(html).doesNotContain("loading");
            assertThat(html).contains(chart.toUri().toString()).contains("width=\"64\"");
            assertThat(html).contains(shared.toUri().toString());
            assertThat(html).as("the SVG shown as its drawing in the cache").contains(dir.resolve("cache").toUri().toString())
                    .contains("width=\"90\"").doesNotContain("flow.svg\"");
            assertThat(html).contains("no such file: gone.png");
            assertThat(html).as("a web picture stays its alt text").contains("<i>web</i>").doesNotContain("example.org");
            assertThat(images(pane[0])).as("Swing draws the three").hasSize(3);

            SwingUtilities.invokeAndWait(() -> pane[0].setMarkdown("![the chart](images/chart.png)"));
            assertThat(pane[0].getText()).as("a later render keeps the folder").contains(chart.toUri().toString());
            SwingUtilities.invokeAndWait(() -> pane[0].setMarkdown("![the chart](images/chart.png)", null));
            assertThat(pane[0].getText()).as("text of no file: relative names are not read").doesNotContain("<img");
        } finally {
            LocalImages.install(null);
        }
    }

    /// Press, release and click events with click counts 1 then 2 on a note link and a URL hand the handler each href
    /// once.
    @Test
    void aNoteLinkAndAnAddressAreFollowedAtARealDoubleClick() throws Exception {
        List<String> opened = new CopyOnWriteArrayList<>();
        HtmlPane[] pane = new HtmlPane[1];
        SwingUtilities.invokeAndWait(() -> {
            pane[0] = new HtmlPane();
            pane[0].setLinkHandler(opened::add);
            pane[0].setMarkdown("Read [[feedback-verification-discipline]] before https://example.org/guide today.");
            JScrollPane scroll = new JScrollPane(pane[0]);
            scroll.setSize(600, 200);
            layOut(scroll);
        });
        for (String at : List.of("feedback-verification-discipline", "example.org")) {
            SwingUtilities.invokeAndWait(() -> {
                Rectangle2D r;
                try {
                    String shown = pane[0].getDocument().getText(0, pane[0].getDocument().getLength());
                    r = pane[0].modelToView2D(shown.indexOf(at) + 3);
                } catch (BadLocationException e) {
                    throw new IllegalStateException(e);
                }
                int x = (int) r.getCenterX();
                int y = (int) r.getCenterY();
                for (int clicks = 1; clicks <= 2; clicks++) {
                    pane[0].dispatchEvent(new MouseEvent(pane[0], MouseEvent.MOUSE_PRESSED, 0L, InputEvent.BUTTON1_DOWN_MASK, x, y, clicks, false, MouseEvent.BUTTON1));
                    pane[0].dispatchEvent(new MouseEvent(pane[0], MouseEvent.MOUSE_RELEASED, 0L, 0, x, y, clicks, false, MouseEvent.BUTTON1));
                    pane[0].dispatchEvent(new MouseEvent(pane[0], MouseEvent.MOUSE_CLICKED, 0L, 0, x, y, clicks, false, MouseEvent.BUTTON1));
                }
            });
        }
        assertThat(opened).containsExactly("comando-note:feedback-verification-discipline", "https://example.org/guide");
    }

    /// A same-page fragment moves nothing on a single click and on a double click puts the heading at the top with the
    /// caret; `#` goes to the top, a keyboard activation matches the heading's case-insensitive id up to the page's
    /// end, a percent-encoded id is found, an unknown one stays, and only a link to another file reaches the handler.
    @Test
    void aLinkToAHeadingDoubleClickedScrollsItToTheTop() throws Exception {
        StringBuilder md = new StringBuilder("# Report\n\nSee [the decisions](#3-decisions), [notes](#Notes) and [the top](#).\n\n");
        for (int i = 0; i < 60; i++) {
            md.append("Line ").append(i).append(" of the findings.\n\n");
        }
        md.append("## 3. Decisions\n\nThe lane stays.\n\n");
        for (int i = 0; i < 60; i++) {
            md.append("Line ").append(i).append(" of the decisions.\n\n");
        }
        md.append("## Notes\n\nLast.\n");
        List<String> opened = new CopyOnWriteArrayList<>();
        HtmlPane[] pane = new HtmlPane[1];
        JScrollPane[] scroll = new JScrollPane[1];
        SwingUtilities.invokeAndWait(() -> {
            pane[0] = new HtmlPane();
            pane[0].setLinkHandler(opened::add);
            pane[0].setMarkdown(md.toString());
            scroll[0] = new JScrollPane(pane[0]);
            scroll[0].setSize(500, 300);
            // never shown, so validate() lays nothing out
            layOut(scroll[0]);
            // as a panel opens its preview
            pane[0].setCaretPosition(0);
            scroll[0].getViewport().setViewPosition(new Point(0, 0));
        });
        int decisions = headingY(pane[0], "3. Decisions");
        assertThat(decisions).as("well below the first screen").isGreaterThan(600);

        SwingUtilities.invokeAndWait(() -> pane[0].fireHyperlinkUpdate(link(pane[0], "#3-decisions", 1)));
        assertThat(top(scroll[0])).as("a single click does not move").isZero();
        SwingUtilities.invokeAndWait(() -> pane[0].fireHyperlinkUpdate(link(pane[0], "#3-decisions", 2)));
        assertThat(top(scroll[0])).as("the heading at the top").isEqualTo(decisions);
        assertThat(pane[0].getCaretPosition()).as("the caret with it, which a new render keeps").isEqualTo(offsetOf(pane[0], "3. Decisions"));

        SwingUtilities.invokeAndWait(() -> pane[0].fireHyperlinkUpdate(link(pane[0], "#", 2)));
        assertThat(top(scroll[0])).as("an empty fragment: the top").isZero();
        SwingUtilities.invokeAndWait(() -> pane[0].fireHyperlinkUpdate(link(pane[0], "#Notes", 0)));
        assertThat(top(scroll[0])).as("from the keyboard, the case aside: as far as the page goes")
                .isEqualTo(pane[0].getHeight() - scroll[0].getViewport().getHeight());
        boolean[] found = new boolean[1];
        SwingUtilities.invokeAndWait(() -> found[0] = pane[0].scrollToHeading("3%2Ddecisions"));
        assertThat(found[0]).as("percent-encoded").isTrue();
        assertThat(top(scroll[0])).isEqualTo(decisions);

        SwingUtilities.invokeAndWait(() -> pane[0].fireHyperlinkUpdate(link(pane[0], "#no-such-heading", 2)));
        assertThat(top(scroll[0])).as("no such heading: stays").isEqualTo(decisions);
        assertThat(opened).as("a heading of the page is never handed on").isEmpty();
        SwingUtilities.invokeAndWait(() -> pane[0].fireHyperlinkUpdate(link(pane[0], "other.md#3-decisions", 2)));
        assertThat(opened).containsExactly("other.md#3-decisions");
    }

    /// A 2000 px picture rendered with no pane width is at its own size; after layout to 500 px and the resize
    /// settling, [HtmlPane#flushResize] re-renders it narrower.
    @Test
    void aPictureRenderedBeforeThePaneHadAWidthFitsOnceItIsLaidOut(@TempDir Path dir) throws Exception {
        Path wide = dir.resolve("wide.png");
        ImageIO.write(new BufferedImage(2000, 100, BufferedImage.TYPE_INT_RGB), "png", wide.toFile());
        LocalImages.install(dir.resolve("cache"));
        try {
            CountDownLatch read = new CountDownLatch(1);
            if (LocalImages.current().picture(wide, read::countDown) instanceof LocalImages.Picture.Pending) {
                assertThat(read.await(10, TimeUnit.SECONDS)).isTrue();
            }
            HtmlPane[] pane = new HtmlPane[1];
            SwingUtilities.invokeAndWait(() -> {
                pane[0] = new HtmlPane();
                pane[0].setMarkdown("![wide](wide.png)", dir);
                assertThat(pane[0].getText()).as("no width yet: as drawn").contains("width=\"2000\"");
                JScrollPane scroll = new JScrollPane(pane[0]);
                scroll.setSize(500, 300);
                layOut(scroll);
            });
            settle();
            SwingUtilities.invokeAndWait(() -> {
                assertThat(pane[0].flushResize()).as("a re-size waits for the resize to settle").isTrue();
                assertThat(pane[0].getText()).doesNotContain("width=\"2000\"");
            });
        } finally {
            LocalImages.install(null);
        }
    }

    /// When a 900 px picture above the heading the reader scrolled to arrives, the heading moves down by more than 500
    /// px and the view follows it.
    @Test
    void aPictureDrawnAboveTheHeadingTheReaderWasTakenToKeepsTheHeadingInView(@TempDir Path dir) throws Exception {
        Path chart = dir.resolve("images/chart.png");
        Files.createDirectories(chart.getParent());
        ImageIO.write(new BufferedImage(200, 900, BufferedImage.TYPE_INT_RGB), "png", chart.toFile());
        StringBuilder md = new StringBuilder("# Report\n\n![a tall chart](images/chart.png)\n\n");
        for (int i = 0; i < 40; i++) {
            md.append("Line ").append(i).append(".\n\n");
        }
        md.append("## Later\n\nThe end.\n\n");
        for (int i = 0; i < 40; i++) {
            md.append("Tail ").append(i).append(".\n\n");
        }
        LocalImages.install(dir.resolve("cache"));
        try {
            HtmlPane[] pane = new HtmlPane[1];
            JScrollPane[] scroll = new JScrollPane[1];
            int[] before = new int[1];
            SwingUtilities.invokeAndWait(() -> {
                pane[0] = new HtmlPane();
                scroll[0] = new JScrollPane(pane[0]);
                scroll[0].setSize(500, 300);
                pane[0].setMarkdown(md.toString(), dir);
                layOut(scroll[0]);
                pane[0].setCaretPosition(0);
                scroll[0].getViewport().setViewPosition(new Point(0, 0));
                assertThat(pane[0].scrollToHeading("later")).isTrue();
                before[0] = scroll[0].getViewport().getViewPosition().y;
                assertThat(pane[0].getText()).as("the chart not read yet").doesNotContain("<img");
            });
            assertThat(before[0]).as("at the heading, the chart still loading").isPositive();
            // the chart is read, and the page drawn again with it above the heading
            CountDownLatch read = new CountDownLatch(1);
            if (LocalImages.current().picture(chart, read::countDown) instanceof LocalImages.Picture.Pending) {
                assertThat(read.await(10, TimeUnit.SECONDS)).isTrue();
            }
            settle();
            assertThat(pane[0].getText()).contains("<img");
            int after = headingY(pane[0], "Later");
            assertThat(after).as("the picture moved the heading down").isGreaterThan(before[0] + 500);
            assertThat(top(scroll[0])).as("and the view went with it").isEqualTo(after);
        } finally {
            LocalImages.install(null);
        }
    }

    /// Three mermaid fences and a linked picture render as waiting placeholders; once drawn, all four show as images,
    /// the heading element is the same object, each diagram ran once, and the picture is still inside its link.
    @Test
    void diagramsDrawnLaterTakeTheirPlaceAndTheRestOfThePageIsNotRenderedAgain(@TempDir Path dir) throws Exception {
        Path picture = dir.resolve("picture.png");
        ImageIO.write(new BufferedImage(400, 120, BufferedImage.TYPE_INT_ARGB), "png", picture.toFile());
        Path gate = dir.resolve("gate");
        // a stand-in for mmdc that draws nothing until the test opens the gate, so every diagram waits
        Path mmdc = dir.resolve("bin").resolve("mmdc");
        Files.createDirectories(mmdc.getParent());
        Files.writeString(mmdc, """
                #!/bin/sh
                echo run >> '%s'
                out=""
                while [ $# -gt 0 ]; do
                  if [ "$1" = "-o" ]; then out="$2"; fi
                  shift
                done
                while [ ! -f '%s' ]; do sleep 0.02; done
                cp '%s' "$out"
                """.formatted(dir.resolve("runs"), gate, picture));
        mmdc.toFile().setExecutable(true);
        Path chart = dir.resolve("chart.png");
        ImageIO.write(new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB), "png", chart.toFile());
        List<String> sources = List.of("flowchart LR\n    A --> B\n", "flowchart LR\n    B --> C\n", "flowchart LR\n    C --> D\n");
        StringBuilder md = new StringBuilder("# Plan\n\n[![the chart](chart.png)](other.md)\n\n");
        for (String s : sources) {
            md.append("```mermaid\n").append(s).append("```\n\n");
        }
        MermaidDiagrams.install(dir.resolve("mermaid"), mmdc);
        LocalImages.install(dir.resolve("images"));
        try {
            HtmlPane[] pane = new HtmlPane[1];
            Element[] heading = new Element[1];
            SwingUtilities.invokeAndWait(() -> {
                pane[0] = new HtmlPane();
                pane[0].setSize(800, 600);
                pane[0].setMarkdown(md.toString(), dir);
                assertThat(pane[0].getText().split("id=\"comando:waiting:", -1)).as("each diagram and the chart stood in for")
                        .hasSize(sources.size() + 2);
                heading[0] = element(pane[0], "plan");
            });
            Files.createFile(gate);
            // the pane asked first, so its fill is posted before the last of these is heard
            for (String s : sources) {
                CountDownLatch drawn = new CountDownLatch(1);
                if (MermaidDiagrams.current().diagram(s, drawn::countDown) instanceof MermaidDiagrams.Diagram.Pending) {
                    assertThat(drawn.await(10, TimeUnit.SECONDS)).isTrue();
                }
            }
            CountDownLatch read = new CountDownLatch(1);
            if (LocalImages.current().picture(chart, read::countDown) instanceof LocalImages.Picture.Pending) {
                assertThat(read.await(10, TimeUnit.SECONDS)).isTrue();
            }
            settle();
            String html = pane[0].getText();
            assertThat(html).doesNotContain("drawing the diagram").doesNotContain("loading").doesNotContain("comando:waiting");
            assertThat(images(pane[0])).as("three diagrams and the chart").hasSize(4);
            SwingUtilities.invokeAndWait(() -> assertThat(element(pane[0], "plan"))
                    .as("the diagrams put in place, the rest of the page as it was").isSameAs(heading[0]));
            assertThat(Files.readAllLines(dir.resolve("runs"))).as("each diagram drawn once").hasSize(sources.size());
            SwingUtilities.invokeAndWait(() -> {
                Element img = null;
                ElementIterator it = new ElementIterator(pane[0].getDocument());
                for (Element e = it.next(); e != null; e = it.next()) {
                    AttributeSet a = e.getAttributes();
                    if (a.getAttribute(StyleConstants.NameAttribute) == HTML.Tag.IMG
                            && String.valueOf(a.getAttribute(HTML.Attribute.SRC)).equals(chart.toUri().toString())) {
                        img = e;
                    }
                }
                assertThat(img).as("the chart shown").isNotNull();
                assertThat(img.getAttributes().getAttribute(HTML.Tag.A)).as("still inside its link")
                        .isInstanceOfSatisfying(AttributeSet.class, a -> assertThat(a.getAttribute(HTML.Attribute.HREF)).isEqualTo("other.md"));
            });
        } finally {
            MermaidDiagrams.install(null, null);
            LocalImages.install(null);
        }
    }

    /** The element of the pane's page whose id is {@code id}. */
    private static Element element(HtmlPane pane, String id) {
        return ((HTMLDocument) pane.getDocument()).getElement(id);
    }

    /** Runs what the redraw and the layouts it causes post: each step posts the next while it runs. */
    private static void settle() throws Exception {
        for (int i = 0; i < 4; i++) {
            SwingUtilities.invokeAndWait(() -> {
            });
        }
    }

    private static HyperlinkEvent link(HtmlPane pane, String href, int clicks) {
        MouseEvent click = clicks == 0 ? null : new MouseEvent(pane, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 10, 10,
                clicks, false, MouseEvent.BUTTON1);
        return new HyperlinkEvent(pane, HyperlinkEvent.EventType.ACTIVATED, null, href, null, click);
    }

    private static void layOut(Container c) {
        c.doLayout();
        for (Component child : c.getComponents()) {
            if (child instanceof Container inner) {
                layOut(inner);
            }
        }
    }

    private static int top(JScrollPane scroll) throws Exception {
        int[] y = new int[1];
        SwingUtilities.invokeAndWait(() -> y[0] = scroll.getViewport().getViewPosition().y);
        return y[0];
    }

    private static int offsetOf(HtmlPane pane, String text) throws Exception {
        return pane.getDocument().getText(0, pane.getDocument().getLength()).indexOf(text);
    }

    /** Where the heading with this text starts on the page. */
    private static int headingY(HtmlPane pane, String text) throws Exception {
        int[] y = {-1};
        SwingUtilities.invokeAndWait(() -> {
            try {
                String all = pane.getDocument().getText(0, pane.getDocument().getLength());
                y[0] = (int) pane.modelToView2D(all.indexOf(text)).getY();
            } catch (BadLocationException e) {
                throw new IllegalStateException(e);
            }
        });
        return y[0];
    }

    /** The attributes of the one image in {@code html}, as Swing's parser reads them. */
    private static Map<String, String> img(String html) throws Exception {
        HTMLEditorKit kit = new HTMLEditorKit();
        HTMLDocument doc = (HTMLDocument) kit.createDefaultDocument();
        kit.read(new StringReader("<html><body>" + html + "</body></html>"), doc, 0);
        Map<String, String> found = new HashMap<>();
        ElementIterator it = new ElementIterator(doc);
        for (Element e = it.next(); e != null; e = it.next()) {
            AttributeSet a = e.getAttributes();
            if (a.getAttribute(StyleConstants.NameAttribute) == HTML.Tag.IMG) {
                assertThat(found).as("one image").isEmpty();
                for (Enumeration<?> names = a.getAttributeNames(); names.hasMoreElements(); ) {
                    Object name = names.nextElement();
                    if (name instanceof HTML.Attribute) {
                        found.put(name.toString(), String.valueOf(a.getAttribute(name)));
                    }
                }
            }
        }
        return found;
    }

    /** The image views of what the pane shows. */
    private static List<ImageView> images(HtmlPane pane) throws Exception {
        List<ImageView> found = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> collect(pane.getUI().getRootView(pane), found));
        return found;
    }

    private static void collect(View v, List<ImageView> found) {
        if (v instanceof ImageView image) {
            found.add(image);
        }
        for (int i = 0; i < v.getViewCount(); i++) {
            collect(v.getView(i), found);
        }
    }

    private static String bodyColor(HtmlPane p) {
        return String.valueOf(((HTMLDocument) p.getDocument()).getStyleSheet().getRule("body").getAttribute(CSS.Attribute.COLOR));
    }
}
