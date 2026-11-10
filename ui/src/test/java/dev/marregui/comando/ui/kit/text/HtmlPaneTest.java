package dev.marregui.comando.ui.kit.text;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Rectangle2D;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntUnaryOperator;
import javax.imageio.ImageIO;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.event.HyperlinkEvent;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.Element;
import javax.swing.text.ElementIterator;
import javax.swing.text.StyleConstants;
import javax.swing.text.View;
import javax.swing.text.html.CSS;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.ImageView;

import com.formdev.flatlaf.util.Graphics2DProxy;
import org.fife.ui.rsyntaxtextarea.TokenTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.ui.kit.theme.Ground;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.theme.Tint;

import static org.assertj.core.api.Assertions.assertThat;

/// [HtmlPane] shows rendered Markdown in the dark page style, with local pictures, diagrams and followable links.
/// - The page is transparent over the window's ground, its text ivory and its code in the editor's colours.
/// - A picture is no wider than the pane, and its alt text cannot add an attribute.
/// - A document shows the pictures it names beside it and only those, loading first, then drawn.
/// - Note links and addresses are followed at a real double click; a heading link scrolls the heading to the top.
/// - A picture rendered before the pane had a width fits once laid out; one drawn above the reader's heading keeps it
///   in view; one the reader scrolled, dragged or followed a heading's link away from while the page is drawn again
///   stays where the reader put it, and so through a second change to the page made before the view is put back.
/// - Diagrams drawn later take their place without rendering the rest of the page again.
/// - A page styled before it joined a window takes the theme chosen since, and only then is drawn again.
class HtmlPaneTest {

    /// The pane is not opaque, its body colour is the transcript text, and a Java fence carries the editor's keyword
    /// colour.
    @Test
    void aPageIsDarkTheWindowsGroundWithItsCodeInTheEditorsColours() throws Exception {
        final HtmlPane[] p = new HtmlPane[1];
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
        final Path file = Path.of("/kb/img/chart.png");
        final String wide = HtmlPane.pictureHtml(new LocalImages.Picture.Ready(file, 1200, 600), "chart", file, 600);
        assertThat(wide).isEqualTo("<img src=\"file:///kb/img/chart.png\" width=\"600\" height=\"300\" alt=\"chart\">");
        assertThat(HtmlPane.pictureHtml(new LocalImages.Picture.Ready(file, 100, 50), "", file, 600))
                .as("a small one at its own size").contains("width=\"100\" height=\"50\"");
        assertThat(HtmlPane.pictureHtml(new LocalImages.Picture.Ready(file, 100, 50), "", file, 0))
                .as("a pane not laid out yet").contains("width=\"100\" height=\"50\"");

        final String hostile = HtmlPane.pictureHtml(new LocalImages.Picture.Ready(file, 10, 10),
                "x\" src=\"http://127.0.0.1/steal.png' onload='y", file, 600);
        assertThat(img(hostile)).as("as Swing reads it: the file's src, the whole text as alt").containsExactlyInAnyOrderEntriesOf(Map.of(
                "src", "file:///kb/img/chart.png", "width", "10", "height", "10",
                "alt", "x\" src=\"http://127.0.0.1/steal.png' onload='y"));
        final Path odd = Path.of("/kb/my \"quoted\" <dir>/a.png");
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
    void aDocumentShowsThePicturesItNamesBesideItAndOnlyThose(@TempDir final Path dir) throws Exception {
        final Path doc = dir.resolve("kb");
        final Path chart = doc.resolve("images/chart.png");
        final Path flow = doc.resolve("img/flow.svg");
        final Path shared = dir.resolve("shared/logo.gif");
        for (final Path p : new Path[]{chart, flow, shared}) {
            Files.createDirectories(p.getParent());
        }
        ImageIO.write(new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB), "png", chart.toFile());
        ImageIO.write(new BufferedImage(16, 16, BufferedImage.TYPE_BYTE_INDEXED), "gif", shared.toFile());
        Files.writeString(flow, "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"90\" height=\"30\"><rect width=\"90\" height=\"30\"/></svg>");
        final String markdown = """
                # Report

                ![the chart](images/chart.png)

                ![the flow](./img/flow.svg "Flow")

                ![the logo](../shared/logo.gif) ![gone](images/gone.png) ![web](https://example.org/w.png)
                """;
        LocalImages.install(dir.resolve("cache"));
        try {
            final HtmlPane[] pane = new HtmlPane[1];
            final String[] first = new String[1];
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
            for (final Path p : new Path[]{chart, flow, shared}) {
                final CountDownLatch read = new CountDownLatch(1);
                if (LocalImages.current().picture(p, read::countDown) instanceof LocalImages.Picture.Pending) {
                    assertThat(read.await(10, TimeUnit.SECONDS)).isTrue();
                }
            }
            SwingUtilities.invokeAndWait(() -> { });
            SwingUtilities.invokeAndWait(() -> { });
            final String html = pane[0].getText();
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
        final List<String> opened = new CopyOnWriteArrayList<>();
        final HtmlPane[] pane = new HtmlPane[1];
        SwingUtilities.invokeAndWait(() -> {
            pane[0] = new HtmlPane();
            pane[0].setLinkHandler(opened::add);
            pane[0].setMarkdown("Read [[feedback-verification-discipline]] before https://example.org/guide today.");
            final JScrollPane scroll = new JScrollPane(pane[0]);
            scroll.setSize(600, 200);
            layOut(scroll);
        });
        for (final String at : List.of("feedback-verification-discipline", "example.org")) {
            SwingUtilities.invokeAndWait(() -> {
                Rectangle2D r;
                try {
                    final String shown = pane[0].getDocument().getText(0, pane[0].getDocument().getLength());
                    r = pane[0].modelToView2D(shown.indexOf(at) + 3);
                } catch (final BadLocationException e) {
                    throw new IllegalStateException(e);
                }
                final int x = (int) r.getCenterX();
                final int y = (int) r.getCenterY();
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
        final StringBuilder md = new StringBuilder("# Report\n\nSee [the decisions](#3-decisions), [notes](#Notes) and [the top](#).\n\n");
        for (int i = 0; i < 60; i++) {
            md.append("Line ").append(i).append(" of the findings.\n\n");
        }
        md.append("## 3. Decisions\n\nThe lane stays.\n\n");
        for (int i = 0; i < 60; i++) {
            md.append("Line ").append(i).append(" of the decisions.\n\n");
        }
        md.append("## Notes\n\nLast.\n");
        final List<String> opened = new CopyOnWriteArrayList<>();
        final HtmlPane[] pane = new HtmlPane[1];
        final JScrollPane[] scroll = new JScrollPane[1];
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
        final int decisions = headingY(pane[0], "3. Decisions");
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
        final boolean[] found = new boolean[1];
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
    void aPictureRenderedBeforeThePaneHadAWidthFitsOnceItIsLaidOut(@TempDir final Path dir) throws Exception {
        final Path wide = dir.resolve("wide.png");
        ImageIO.write(new BufferedImage(2000, 100, BufferedImage.TYPE_INT_RGB), "png", wide.toFile());
        LocalImages.install(dir.resolve("cache"));
        try {
            final CountDownLatch read = new CountDownLatch(1);
            if (LocalImages.current().picture(wide, read::countDown) instanceof LocalImages.Picture.Pending) {
                assertThat(read.await(10, TimeUnit.SECONDS)).isTrue();
            }
            final HtmlPane[] pane = new HtmlPane[1];
            SwingUtilities.invokeAndWait(() -> {
                pane[0] = new HtmlPane();
                pane[0].setMarkdown("![wide](wide.png)", dir);
                assertThat(pane[0].getText()).as("no width yet: as drawn").contains("width=\"2000\"");
                final JScrollPane scroll = new JScrollPane(pane[0]);
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

    /// A wheel scroll the reader makes after the page is drawn again, and before the view is put back, is the reader's
    /// place: neither the caret's scroll nor the view put back takes it to where it was. The wheel event is queued as
    /// the reader's input is, behind the redraw, which queues those two behind it.
    @Test
    void aScrollTheReaderMakesWhileThePageIsDrawnAgainKeepsItsPlace() throws Exception {
        final StringBuilder md = new StringBuilder("# Report\n\n");
        for (int i = 0; i < 60; i++) {
            md.append("Line ").append(i).append(".\n\n");
        }
        final HtmlPane[] pane = new HtmlPane[1];
        final JScrollPane[] scroll = new JScrollPane[1];
        final int[] scrolled = new int[1];
        SwingUtilities.invokeAndWait(() -> {
            pane[0] = new HtmlPane();
            scroll[0] = new JScrollPane(pane[0]);
            scroll[0].setSize(500, 300);
            pane[0].setMarkdown(md.toString(), null);
            layOut(scroll[0]);
            pane[0].setCaretPosition(0);
            scroll[0].getViewport().setViewPosition(new Point(0, 0));
        });
        settle();
        SwingUtilities.invokeAndWait(() -> {
            SwingUtilities.invokeLater(() -> pane[0].anchors(MarkdownHtml.Anchors.gaps(Map.of(1, 40))));
            final MouseWheelEvent wheel = new MouseWheelEvent(scroll[0], MouseEvent.MOUSE_WHEEL,
                    System.currentTimeMillis(), 0, 10, 10, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 5);
            Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(wheel);
            SwingUtilities.invokeLater(() -> scrolled[0] = scroll[0].getViewport().getViewPosition().y);
        });
        settle();
        assertThat(scrolled[0]).as("the wheel scrolled down the page").isGreaterThan(100);
        assertThat(top(scroll[0])).as("the reader's place, not the one the redraw found").isEqualTo(scrolled[0]);
    }

    /// A wheel scroll that runs once the redraw's own events have moved the view off the reader's place, and before
    /// the view is put back, moves the reader from their place: the view ends at the place plus the scroll, not at
    /// where the redraw took it plus the scroll. The queue turns the wheel right after the first event that leaves the
    /// view off the place, which is where the reader's input lands when it arrives while the page is drawn again.
    @Test
    void aScrollTheReaderMakesOnceTheRedrawMovedTheViewStartsFromTheirPlace() throws Exception {
        final Moved w = movedInTheRedraw(page(120), _ -> 200, _ -> {
        }, HtmlPaneTest::wheel);
        assertThat(w.from()).as("where the redraw took the view, and the wheel turned").isBetween(0, 199);
        assertThat(w.by()).as("the wheel scrolled down the page").isGreaterThan(100);
        assertThat(w.top()).as("the reader's place plus their scroll").isEqualTo(200 + w.by());
    }

    /// The same scroll on a short page, from a place near its end: the wheel takes the view to the end of the page,
    /// which the place plus the scroll would pass, and the view stays at the end of the page.
    @Test
    void aScrollTheReaderMakesOnceTheRedrawMovedTheViewStopsAtTheEndOfThePage() throws Exception {
        final Moved w = movedInTheRedraw(page(24), last -> last - 50, _ -> {
        }, HtmlPaneTest::wheel);
        assertThat(w.from() + w.by()).as("the wheel took the view to the end of the page").isEqualTo(w.last());
        assertThat(w.top()).as("the end of the page").isEqualTo(w.last());
    }

    /// The thumb the reader drags in the same window puts the view where they dragged it: a drag places the view, so
    /// it is not a step taken from the reader's earlier place.
    @Test
    void aThumbTheReaderDragsOnceTheRedrawMovedTheViewStaysWhereTheyDraggedIt() throws Exception {
        final Moved w = movedInTheRedraw(page(120), _ -> 200,
                scroll -> scroll.getVerticalScrollBar().setUI(new Thumbed()), HtmlPaneTest::dragThumb);
        assertThat(w.from()).as("where the redraw took the view, and the thumb was dragged").isBetween(0, 199);
        assertThat(w.by()).as("the drag moved the view down the page").isGreaterThan(100);
        assertThat(w.top()).as("where the thumb was dragged").isEqualTo(w.from() + w.by());
    }

    /// A heading's link the reader follows in the same window takes the view to the heading, which stays at the top.
    @Test
    void aHeadingLinkTheReaderFollowsOnceTheRedrawMovedTheViewKeepsTheHeadingInView() throws Exception {
        final String md = "# Report\n\nSee [the notes](#notes).\n\n" + page(120) + "## Notes\n\nLast.\n";
        final Moved w = movedInTheRedraw(md, _ -> 200, _ -> {
        }, scroll -> followTheLink(scroll, "the notes"));
        assertThat(w.by()).as("the link moved the view down the page").isGreaterThan(400);
        assertThat(w.top()).as("the heading").isEqualTo(w.from() + w.by());
    }

    /// A second change to the page made in the same window, as a picture put in place in a redraw's wake, keeps the
    /// reader's place, not the one the first redraw's caret scrolled to.
    @Test
    void aChangeMadeWhileTheViewWaitsToBePutBackKeepsTheReadersPlace() throws Exception {
        final Moved w = movedInTheRedraw(page(120), _ -> 200, _ -> {
        }, scroll -> ((HtmlPane) scroll.getViewport().getView()).anchors(MarkdownHtml.Anchors.gaps(Map.of(1, 80))));
        assertThat(w.from()).as("where the redraw took the view, and the page changed again").isBetween(0, 199);
        assertThat(w.top()).as("the reader's place").isEqualTo(200);
    }

    /// A thumb dragged across the page in the same window places the view across the page only: down the page the
    /// view stays at the reader's place.
    @Test
    void aThumbDraggedAcrossOnceTheRedrawMovedTheViewKeepsThePlaceDownThePage() throws Exception {
        final String md = page(120) + "```\n" + "x".repeat(400) + "\n```\n";
        final Moved w = movedInTheRedraw(md, _ -> 200,
                scroll -> scroll.getHorizontalScrollBar().setUI(new Thumbed()), HtmlPaneTest::dragThumbAcross);
        assertThat(w.from()).as("where the redraw took the view, and the thumb was dragged").isBetween(0, 199);
        assertThat(w.left()).as("the drag moved the view across the page").isGreaterThan(50);
        assertThat(w.top()).as("the reader's place down the page").isEqualTo(200);
    }

    /// A step the bar repeats while the reader holds it, which comes from a timer and not from the reader's input,
    /// is the reader's: made in the same window, it moves the view on from the reader's place. The reader holds the
    /// thumb from before the page is drawn again, and the step is made as the bar's timer makes it, between events.
    @Test
    void aStepTheBarRepeatsWhileTheReaderHoldsItMovesOnFromTheirPlace() throws Exception {
        final JScrollBar[] bar = new JScrollBar[1];
        final Moved w = movedInTheRedraw(page(120), _ -> 200, scroll -> {
            bar[0] = scroll.getVerticalScrollBar();
            bar[0].setUI(new Thumbed());
        }, scroll -> scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getValue() + 100),
                () -> press(bar[0], MouseEvent.MOUSE_PRESSED), () -> press(bar[0], MouseEvent.MOUSE_RELEASED));
        assertThat(w.from()).as("where the redraw took the view, and the bar stepped").isBetween(0, 199);
        assertThat(w.by()).as("the step").isEqualTo(100);
        assertThat(w.top()).as("the reader's place plus the step").isEqualTo(300);
    }

    /// A second change to the page made in the same window while the reader holds the bar, as a picture put in place
    /// in a redraw's wake, keeps the reader's place: the views put back are the pane's own moves, not the reader's,
    /// though the bar is held.
    @Test
    void aChangeMadeWhileTheReaderHoldsTheBarKeepsTheirPlace() throws Exception {
        final JScrollBar[] bar = new JScrollBar[1];
        final Moved w = movedInTheRedraw(page(120), _ -> 200, scroll -> {
            bar[0] = scroll.getVerticalScrollBar();
            bar[0].setUI(new Thumbed());
        }, scroll -> ((HtmlPane) scroll.getViewport().getView()).anchors(MarkdownHtml.Anchors.gaps(Map.of(1, 80))),
                () -> press(bar[0], MouseEvent.MOUSE_PRESSED), () -> press(bar[0], MouseEvent.MOUSE_RELEASED));
        assertThat(w.from()).as("where the redraw took the view, and the page changed again").isBetween(0, 199);
        assertThat(w.top()).as("the reader's place").isEqualTo(200);
    }

    /// A link to the heading the view is at already, followed in the same window, takes the view nowhere and is still
    /// the reader's choice: the view stays at the heading, and is not put back below it for a frame until the caret's
    /// scroll brings it back. The reader followed the link once and scrolled on below the heading, where the caret
    /// stayed; the redraw's caret scroll takes the view back up to the heading, and the reader follows the link again.
    @Test
    void aLinkToTheHeadingInViewFollowedOnceTheRedrawMovedTheViewStaysThere() throws Exception {
        final String md = page(20) + "## Notes\n\nSee [the notes](#notes).\n\n" + page(100);
        final JScrollPane[] pane = new JScrollPane[1];
        final List<Integer> since = new CopyOnWriteArrayList<>();
        final Moved w = movedInTheRedraw(md, _ -> 0, scroll -> pane[0] = scroll, scroll -> {
            followTheLink(scroll, "the notes");
            scroll.getViewport().addChangeListener(_ -> since.add(scroll.getViewport().getViewPosition().y));
        }, () -> {
                    final JViewport vp = pane[0].getViewport();
                    ((HtmlPane) vp.getView()).scrollToHeading("notes");
                    final int heading = vp.getViewPosition().y;
                    SwingUtilities.invokeLater(() -> vp.setViewPosition(new Point(0, heading + 300)));
                }, () -> {
                });
        assertThat(w.from()).as("where the redraw took the view: the heading").isGreaterThan(300);
        assertThat(w.by()).as("the view is at the heading already").isZero();
        assertThat(w.top()).as("the heading").isEqualTo(w.from());
        assertThat(since).as("where the view went once the link was followed: nowhere but the heading")
                .allMatch(y -> y == w.from());
    }

    /// Presses or releases, by `id`, the left button on the thumb of `bar`, whose look is [Thumbed].
    private static void press(final JScrollBar bar, final int id) {
        final Rectangle thumb = ((Thumbed) bar.getUI()).thumb();
        bar.dispatchEvent(new MouseEvent(bar, id, 0L, id == MouseEvent.MOUSE_PRESSED ? InputEvent.BUTTON1_DOWN_MASK : 0,
                thumb.x + thumb.width / 2, thumb.y + thumb.height / 2, 1, false, MouseEvent.BUTTON1));
    }

    /// Drags the thumb of the horizontal bar of `scroll`, whose look is [Thumbed], 60 px across.
    private static void dragThumbAcross(final JScrollPane scroll) {
        final JScrollBar bar = scroll.getHorizontalScrollBar();
        final Rectangle thumb = ((Thumbed) bar.getUI()).thumb();
        final int x = thumb.x + thumb.width / 2;
        final int y = thumb.y + thumb.height / 2;
        bar.dispatchEvent(new MouseEvent(bar, MouseEvent.MOUSE_PRESSED, 0L, InputEvent.BUTTON1_DOWN_MASK, x, y, 1,
                false, MouseEvent.BUTTON1));
        bar.dispatchEvent(new MouseEvent(bar, MouseEvent.MOUSE_DRAGGED, 0L, InputEvent.BUTTON1_DOWN_MASK, x + 60, y, 1,
                false, MouseEvent.BUTTON1));
        bar.dispatchEvent(new MouseEvent(bar, MouseEvent.MOUSE_RELEASED, 0L, 0, x + 60, y, 1, false,
                MouseEvent.BUTTON1));
    }

    /// Where the view was when the reader moved it in the redraw, how far the move took it down the page, where it
    /// ended, the last place on the page drawn again, and where the view ended across the page.
    private record Moved(int from, int by, int top, int last, int left) {
    }

    /// A page of `lines` lines under one heading.
    private static String page(final int lines) {
        final StringBuilder md = new StringBuilder("# Report\n\n");
        for (int i = 0; i < lines; i++) {
            md.append("Line ").append(i).append(".\n\n");
        }
        return md.toString();
    }

    /// Shows `md`, made ready by `prepare`, with the view at the place `place` gives for the last place on the page,
    /// opens a gap at the top, and runs `reader` once the redraw's events have moved the view off that place.
    private static Moved movedInTheRedraw(final String md, final IntUnaryOperator place,
                                          final Consumer<JScrollPane> prepare, final Consumer<JScrollPane> reader)
            throws Exception {
        return movedInTheRedraw(md, place, prepare, reader, () -> {
        }, () -> {
        });
    }

    /// As [#movedInTheRedraw(String, IntUnaryOperator, Consumer, Consumer)], `before` run on the event thread once the
    /// view is at the place and before the page is drawn again, the place being where the view is once it has run,
    /// and `after` once the view is put back.
    private static Moved movedInTheRedraw(final String md, final IntUnaryOperator place,
                                          final Consumer<JScrollPane> prepare, final Consumer<JScrollPane> reader,
                                          final Runnable before, final Runnable after)
            throws Exception {
        final HtmlPane[] pane = new HtmlPane[1];
        final JScrollPane[] scroll = new JScrollPane[1];
        SwingUtilities.invokeAndWait(() -> {
            pane[0] = new HtmlPane();
            scroll[0] = new JScrollPane(pane[0]);
            prepare.accept(scroll[0]);
            scroll[0].setSize(500, 300);
            pane[0].setMarkdown(md, null);
            layOut(scroll[0]);
            pane[0].setCaretPosition(0);
        });
        settle();
        final int at = place.applyAsInt(last(scroll[0]));
        SwingUtilities.invokeAndWait(() -> scroll[0].getViewport().setViewPosition(new Point(0, at)));
        settle();
        assertThat(top(scroll[0])).as("the reader's place").isEqualTo(at);
        SwingUtilities.invokeAndWait(before);
        settle();
        final ReaderInTheRedraw queue = new ReaderInTheRedraw(scroll[0], top(scroll[0]), reader);
        Toolkit.getDefaultToolkit().getSystemEventQueue().push(queue);
        try {
            SwingUtilities.invokeAndWait(() -> {
                queue.armed = true;
                pane[0].anchors(MarkdownHtml.Anchors.gaps(Map.of(1, 40)));
            });
            settle();
        } finally {
            queue.done();
        }
        SwingUtilities.invokeAndWait(after);
        final int[] left = new int[1];
        SwingUtilities.invokeAndWait(() -> left[0] = scroll[0].getViewport().getViewPosition().x);
        return new Moved(queue.from, queue.by, top(scroll[0]), last(scroll[0]), left[0]);
    }

    /// Turns the wheel on `scroll` three units down.
    private static void wheel(final JScrollPane scroll) {
        scroll.dispatchEvent(new MouseWheelEvent(scroll, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0, 10, 10,
                0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 5));
    }

    /// Drags the thumb of the vertical bar of `scroll`, whose look is [Thumbed], 60 px down.
    private static void dragThumb(final JScrollPane scroll) {
        final JScrollBar bar = scroll.getVerticalScrollBar();
        final Rectangle thumb = ((Thumbed) bar.getUI()).thumb();
        final int x = thumb.x + thumb.width / 2;
        final int y = thumb.y + thumb.height / 2;
        bar.dispatchEvent(new MouseEvent(bar, MouseEvent.MOUSE_PRESSED, 0L, InputEvent.BUTTON1_DOWN_MASK, x, y, 1,
                false, MouseEvent.BUTTON1));
        bar.dispatchEvent(new MouseEvent(bar, MouseEvent.MOUSE_DRAGGED, 0L, InputEvent.BUTTON1_DOWN_MASK, x, y + 60, 1,
                false, MouseEvent.BUTTON1));
        bar.dispatchEvent(new MouseEvent(bar, MouseEvent.MOUSE_RELEASED, 0L, 0, x, y + 60, 1, false,
                MouseEvent.BUTTON1));
    }

    /// Double-clicks the link whose text is `text` on the page `scroll` shows.
    private static void followTheLink(final JScrollPane scroll, final String text) {
        final HtmlPane pane = (HtmlPane) scroll.getViewport().getView();
        final Rectangle2D r;
        try {
            final String shown = pane.getDocument().getText(0, pane.getDocument().getLength());
            r = pane.modelToView2D(shown.indexOf(text) + 3);
        } catch (final BadLocationException e) {
            throw new IllegalStateException(e);
        }
        final int x = (int) r.getCenterX();
        final int y = (int) r.getCenterY();
        for (int clicks = 1; clicks <= 2; clicks++) {
            pane.dispatchEvent(new MouseEvent(pane, MouseEvent.MOUSE_PRESSED, 0L, InputEvent.BUTTON1_DOWN_MASK, x, y,
                    clicks, false, MouseEvent.BUTTON1));
            pane.dispatchEvent(new MouseEvent(pane, MouseEvent.MOUSE_RELEASED, 0L, 0, x, y, clicks, false,
                    MouseEvent.BUTTON1));
            pane.dispatchEvent(new MouseEvent(pane, MouseEvent.MOUSE_CLICKED, 0L, 0, x, y, clicks, false,
                    MouseEvent.BUTTON1));
        }
    }

    /// A scroll bar's look that tells where its thumb is.
    private static final class Thumbed extends BasicScrollBarUI {
        Rectangle thumb() {
            return getThumbBounds();
        }
    }

    /// The last place the view of `scroll` can take on its page.
    private static int last(final JScrollPane scroll) throws Exception {
        final int[] y = new int[1];
        SwingUtilities.invokeAndWait(() -> y[0] = scroll.getViewport().getViewSize().height
                - scroll.getViewport().getExtentSize().height);
        return y[0];
    }

    /// The event queue of [#movedInTheRedraw]: once armed, it runs its reader on its scroll pane right after the first
    /// event that leaves the view off `place`, and records where the view was then and how far the reader moved it.
    /// Its fields are written on the event thread, and read by the test thread once `invokeAndWait` has returned
    /// behind the queue's events, which orders those reads after the writes.
    private static final class ReaderInTheRedraw extends EventQueue {
        private final JScrollPane scroll;
        private final int place;
        private final Consumer<JScrollPane> reader;
        private boolean armed;
        private int from = -1;
        private int by;

        ReaderInTheRedraw(final JScrollPane scroll, final int place, final Consumer<JScrollPane> reader) {
            this.scroll = scroll;
            this.place = place;
            this.reader = reader;
        }

        @Override
        protected void dispatchEvent(final AWTEvent event) {
            super.dispatchEvent(event);
            final int y = scroll.getViewport().getViewPosition().y;
            if (armed && y != place) {
                armed = false;
                from = y;
                reader.accept(scroll);
                by = scroll.getViewport().getViewPosition().y - from;
            }
        }

        /// Gives the queue's events back to the queue it was pushed on.
        void done() {
            pop();
        }
    }

    /// When a 900 px picture above the heading the reader scrolled to arrives, the heading moves down by more than 500
    /// px and the view follows it.
    @Test
    void aPictureDrawnAboveTheHeadingTheReaderWasTakenToKeepsTheHeadingInView(@TempDir final Path dir) throws Exception {
        final Path chart = dir.resolve("images/chart.png");
        Files.createDirectories(chart.getParent());
        ImageIO.write(new BufferedImage(200, 900, BufferedImage.TYPE_INT_RGB), "png", chart.toFile());
        final StringBuilder md = new StringBuilder("# Report\n\n![a tall chart](images/chart.png)\n\n");
        for (int i = 0; i < 40; i++) {
            md.append("Line ").append(i).append(".\n\n");
        }
        md.append("## Later\n\nThe end.\n\n");
        for (int i = 0; i < 40; i++) {
            md.append("Tail ").append(i).append(".\n\n");
        }
        LocalImages.install(dir.resolve("cache"));
        try {
            final HtmlPane[] pane = new HtmlPane[1];
            final JScrollPane[] scroll = new JScrollPane[1];
            final int[] before = new int[1];
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
            final CountDownLatch read = new CountDownLatch(1);
            if (LocalImages.current().picture(chart, read::countDown) instanceof LocalImages.Picture.Pending) {
                assertThat(read.await(10, TimeUnit.SECONDS)).isTrue();
            }
            settle();
            assertThat(pane[0].getText()).contains("<img");
            final int after = headingY(pane[0], "Later");
            assertThat(after).as("the picture moved the heading down").isGreaterThan(before[0] + 500);
            assertThat(top(scroll[0])).as("and the view went with it").isEqualTo(after);
        } finally {
            LocalImages.install(null);
        }
    }

    /// Three mermaid fences and a linked picture render as waiting placeholders; once drawn, all four show as images,
    /// the heading element is the same object, each diagram ran once, and the picture is still inside its link.
    @Test
    void diagramsDrawnLaterTakeTheirPlaceAndTheRestOfThePageIsNotRenderedAgain(@TempDir final Path dir) throws Exception {
        final Path picture = dir.resolve("picture.png");
        ImageIO.write(new BufferedImage(400, 120, BufferedImage.TYPE_INT_ARGB), "png", picture.toFile());
        final Path gate = dir.resolve("gate");
        // a stand-in for mmdc that draws nothing until the test opens the gate, so every diagram waits
        final Path mmdc = dir.resolve("bin").resolve("mmdc");
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
        final Path chart = dir.resolve("chart.png");
        ImageIO.write(new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB), "png", chart.toFile());
        final List<String> sources = List.of("flowchart LR\n    A --> B\n", "flowchart LR\n    B --> C\n", "flowchart LR\n    C --> D\n");
        final StringBuilder md = new StringBuilder("# Plan\n\n[![the chart](chart.png)](other.md)\n\n");
        for (final String s : sources) {
            md.append("```mermaid\n").append(s).append("```\n\n");
        }
        MermaidDiagrams.install(dir.resolve("mermaid"), mmdc);
        LocalImages.install(dir.resolve("images"));
        try {
            final HtmlPane[] pane = new HtmlPane[1];
            final Element[] heading = new Element[1];
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
            for (final String s : sources) {
                final CountDownLatch drawn = new CountDownLatch(1);
                if (MermaidDiagrams.current().diagram(s, drawn::countDown) instanceof MermaidDiagrams.Diagram.Pending) {
                    assertThat(drawn.await(10, TimeUnit.SECONDS)).isTrue();
                }
            }
            final CountDownLatch read = new CountDownLatch(1);
            if (LocalImages.current().picture(chart, read::countDown) instanceof LocalImages.Picture.Pending) {
                assertThat(read.await(10, TimeUnit.SECONDS)).isTrue();
            }
            settle();
            final String html = pane[0].getText();
            assertThat(html).doesNotContain("drawing the diagram").doesNotContain("loading").doesNotContain("comando:waiting");
            assertThat(images(pane[0])).as("three diagrams and the chart").hasSize(4);
            SwingUtilities.invokeAndWait(() -> assertThat(element(pane[0], "plan"))
                    .as("the diagrams put in place, the rest of the page as it was").isSameAs(heading[0]));
            assertThat(Files.readAllLines(dir.resolve("runs"))).as("each diagram drawn once").hasSize(sources.size());
            SwingUtilities.invokeAndWait(() -> {
                Element img = null;
                final ElementIterator it = new ElementIterator(pane[0].getDocument());
                for (Element e = it.next(); e != null; e = it.next()) {
                    final AttributeSet a = e.getAttributes();
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
    private static Element element(final HtmlPane pane, final String id) {
        return ((HTMLDocument) pane.getDocument()).getElement(id);
    }

    /** Runs what the redraw and the layouts it causes post: each step posts the next while it runs. */
    private static void settle() throws Exception {
        for (int i = 0; i < 4; i++) {
            SwingUtilities.invokeAndWait(() -> {
            });
        }
    }

    private static HyperlinkEvent link(final HtmlPane pane, final String href, final int clicks) {
        final MouseEvent click = clicks == 0 ? null : new MouseEvent(pane, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 10, 10,
                clicks, false, MouseEvent.BUTTON1);
        return new HyperlinkEvent(pane, HyperlinkEvent.EventType.ACTIVATED, null, href, null, click);
    }

    /// A page styled under one theme that joins a window under another takes the rules of the one chosen, as a pane
    /// made now has them; the listener it starts then is what keeps it there.
    @Test
    void aPageStyledBeforeItJoinedAWindowTakesTheThemeChosenSince() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Theme.choose(Ground.GRAPHITE, Tint.TREASURE);
            try {
                final HtmlPane pane = new HtmlPane();
                pane.setMarkdown("# Heading\n\nText.\n");
                final String before = rule(pane, "td");
                Theme.choose(Ground.FOG, Tint.SLATE_INK);
                final HtmlPane fresh = new HtmlPane();
                fresh.setMarkdown("# Heading\n\nText.\n");
                assertThat(rule(fresh, "td")).as("the two grounds' rules differ").isNotEqualTo(before);
                pane.addNotify();
                assertThat(rule(pane, "td")).isEqualTo(rule(fresh, "td"));
                assertThat(rule(pane, "h1")).isEqualTo(rule(fresh, "h1"));
                pane.removeNotify();
            } finally {
                Theme.choose(Ground.GRAPHITE, Tint.TREASURE);
            }
        });
    }

    /// A page styled under the theme still chosen keeps its document as it joins a window: no page is drawn twice.
    @Test
    void aPageStyledUnderTheThemeChosenIsNotDrawnAgainAsItJoinsAWindow() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final HtmlPane pane = new HtmlPane();
            pane.setMarkdown("# Heading\n");
            final Document document = pane.getDocument();
            pane.addNotify();
            assertThat(pane.getDocument()).isSameAs(document);
            pane.removeNotify();
        });
    }

    private static void layOut(final Container c) {
        c.doLayout();
        for (final Component child : c.getComponents()) {
            if (child instanceof Container inner) {
                layOut(inner);
            }
        }
    }

    /// The rule {@code selector} reads under on {@code pane}'s page, as the style sheet resolves it.
    private static String rule(final HtmlPane pane, final String selector) {
        final AttributeSet rule = ((HTMLDocument) pane.getDocument()).getStyleSheet().getRule(selector);
        return String.valueOf(rule.getAttribute(CSS.Attribute.BACKGROUND_COLOR))
                + rule.getAttribute(CSS.Attribute.COLOR);
    }

    private static int top(final JScrollPane scroll) throws Exception {
        final int[] y = new int[1];
        SwingUtilities.invokeAndWait(() -> y[0] = scroll.getViewport().getViewPosition().y);
        return y[0];
    }

    private static int offsetOf(final HtmlPane pane, final String text) throws Exception {
        return pane.getDocument().getText(0, pane.getDocument().getLength()).indexOf(text);
    }

    /** Where the heading with this text starts on the page. */
    private static int headingY(final HtmlPane pane, final String text) throws Exception {
        final int[] y = {-1};
        SwingUtilities.invokeAndWait(() -> {
            try {
                final String all = pane.getDocument().getText(0, pane.getDocument().getLength());
                y[0] = (int) pane.modelToView2D(all.indexOf(text)).getY();
            } catch (final BadLocationException e) {
                throw new IllegalStateException(e);
            }
        });
        return y[0];
    }

    /** The attributes of the one image in {@code html}, as Swing's parser reads them. */
    private static Map<String, String> img(final String html) throws Exception {
        final HTMLEditorKit kit = new HTMLEditorKit();
        final HTMLDocument doc = (HTMLDocument) kit.createDefaultDocument();
        kit.read(new StringReader("<html><body>" + html + "</body></html>"), doc, 0);
        final Map<String, String> found = new HashMap<>();
        final ElementIterator it = new ElementIterator(doc);
        for (Element e = it.next(); e != null; e = it.next()) {
            final AttributeSet a = e.getAttributes();
            if (a.getAttribute(StyleConstants.NameAttribute) == HTML.Tag.IMG) {
                assertThat(found).as("one image").isEmpty();
                for (final Enumeration<?> names = a.getAttributeNames(); names.hasMoreElements(); ) {
                    final Object name = names.nextElement();
                    if (name instanceof HTML.Attribute) {
                        found.put(name.toString(), String.valueOf(a.getAttribute(name)));
                    }
                }
            }
        }
        return found;
    }

    /** The image views of what the pane shows. */
    private static List<ImageView> images(final HtmlPane pane) throws Exception {
        final List<ImageView> found = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> collect(pane.getUI().getRootView(pane), found));
        return found;
    }

    private static void collect(final View v, final List<ImageView> found) {
        if (v instanceof ImageView image) {
            found.add(image);
        }
        for (int i = 0; i < v.getViewCount(); i++) {
            collect(v.getView(i), found);
        }
    }

    private static String bodyColor(final HtmlPane p) {
        return String.valueOf(((HTMLDocument) p.getDocument()).getStyleSheet().getRule("body").getAttribute(CSS.Attribute.COLOR));
    }

    /// A Markdown table paints its grid with no polygon: Swing draws a CSS border as a polygon per side of every cell,
    /// which on the Metal pipeline flushes the render queue each time; the grid is the table's ground between cells.
    @Test
    void aTableDrawsItsGridWithoutPolygons() throws Exception {
        final AtomicInteger polygons = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> {
            final HtmlPane pane = new HtmlPane();
            pane.setSize(600, 400);
            pane.setMarkdown("| a | b |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |\n");
            final BufferedImage image = new BufferedImage(600, 400, BufferedImage.TYPE_INT_ARGB);
            final Graphics2D g = image.createGraphics();
            try {
                pane.paint(new PolygonCount(g, polygons));
            } finally {
                g.dispose();
            }
        });
        assertThat(polygons).as("polygons drawn for the table's grid").hasValue(0);
    }

    /// A graphics that counts its polygon fills, its copies included.
    private static final class PolygonCount extends Graphics2DProxy {
        private final AtomicInteger count;

        PolygonCount(final Graphics2D delegate, final AtomicInteger count) {
            super(delegate);
            this.count = count;
        }

        @Override
        public Graphics create() {
            return new PolygonCount((Graphics2D) super.create(), count);
        }

        @Override
        public void fillPolygon(final int[] xPoints, final int[] yPoints, final int nPoints) {
            count.incrementAndGet();
            super.fillPolygon(xPoints, yPoints, nPoints);
        }

        @Override
        public void fillPolygon(final Polygon p) {
            count.incrementAndGet();
            super.fillPolygon(p);
        }
    }
}
