package dev.marregui.comando.ui.shell;

import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.Year;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/// [About] draws the About card from its Markdown file, filled with this run's facts.
/// - The file words the card; this run fills its facts, links and footer.
/// - A fact left empty is not shown; an unknown placeholder stays as written.
/// - The card paints at scale 2 with rounded corners, and a clicked link goes where it says.
/// - A value too long is cut in its middle.
class AboutTest {

    /// [About#load] gives the name, tagline, the five fact rows with the version, runtime, CLI version and the data
    /// folder shortened to `~`, the three links, and a footer with the current year.
    @Test
    void theFileWordsTheCardAndThisRunFillsItsFacts() {
        Map<String, String> facts = About.facts("2.0.0", Path.of(System.getProperty("user.home"), ".comando"), "2.1.302");
        About.Content c = About.load(facts);
        assertThat(c.name()).isEqualTo("comando");
        assertThat(c.tagline()).isEqualTo("One editor for SQL and Claude Code.");
        assertThat(c.rows()).extracting(About.Row::label).containsExactly("Version", "Java", "Claude Code", "System", "Data");
        assertThat(c.rows()).contains(new About.Row("Version", "2.0.0"), new About.Row("Claude Code", "2.1.302"),
                new About.Row("Data", "~/.comando"));
        assertThat(c.rows().get(1).value()).as("the runtime this runs on").startsWith(Runtime.version().toString());
        assertThat(c.links()).containsExactly(new About.Way("Repository", "https://github.com/marregui/comando"),
                new About.Way("Tutorial", "comando:tutorial"), new About.Way("Data folder", "comando:home"));
        assertThat(c.footer()).isEqualTo("Made by hand by Miguel Arregui, " + Year.now().getValue() + ".");
    }

    /// [About#parse] drops a row whose fact is empty, keeps an unknown `{placeholder}` literally, takes the last
    /// paragraph as the footer, and gives no footer when there is only a tagline.
    @Test
    void aFactLeftEmptyIsNotShownAndAnUnknownPlaceholderStaysAsWritten() {
        About.Content c = About.parse("""
                # comando

                Words.

                | | |
                |---|---|
                | Claude Code | {claude} |
                | Edition | {edition} |
                | Built | today |

                Made here.
                """, Map.of("claude", ""));
        assertThat(c.rows()).containsExactly(new About.Row("Edition", "{edition}"), new About.Row("Built", "today"));
        assertThat(c.links()).isEmpty();
        assertThat(c.footer()).isEqualTo("Made here.");
        assertThat(About.parse("# x\n\nOnly a tagline.\n", Map.of()).footer()).as("one paragraph is the tagline alone").isEmpty();
    }

    /// Painted at scale 2, the card is opaque inside and transparent at its rounded corner; it has three link bounds, a
    /// point inside the second hits it and a corner hits none, and a click follows `comando:tutorial`.
    @Test
    void theCardPaintsAtRetinaScaleAndALinkClickedGoesWhereItSays() {
        List<String> followed = new CopyOnWriteArrayList<>();
        About.Content c = About.load(About.facts("2.0.0", Path.of("/tmp/x"), ""));
        About.Card card = new About.Card(c, followed::add);
        card.setSize(card.getPreferredSize());
        assertThat(card.getPreferredSize().width).isEqualTo(About.Card.WIDTH);
        BufferedImage img = new BufferedImage(card.getWidth() * 2, card.getHeight() * 2, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.scale(2, 2);
        card.paint(g);
        g.dispose();
        assertThat(img.getRGB(img.getWidth() / 2, img.getHeight() / 2) >>> 24).as("the card is opaque inside").isEqualTo(255);
        assertThat(img.getRGB(1, 1) >>> 24).as("its rounded corner is not").isZero();

        List<Rectangle> links = card.linkBounds();
        assertThat(links).hasSize(3);
        Rectangle tutorial = links.get(1);
        Point inside = new Point((int) tutorial.getCenterX(), (int) tutorial.getCenterY());
        assertThat(card.linkAt(inside)).isEqualTo(1);
        assertThat(card.linkAt(new Point(5, 5))).isEqualTo(-1);
        card.dispatchEvent(new MouseEvent(card, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, inside.x, inside.y, 1, false,
                MouseEvent.BUTTON1));
        assertThat(followed).containsExactly("comando:tutorial");
    }

    /// [About.Card#fit] keeps both ends of a long path around `…` within the width, and leaves a short value whole.
    @Test
    void aValueTooLongIsCutInItsMiddle() {
        String path = "/Users/someone/Library/Application Support/comando/a/very/deep/folder/of/data";
        String cut = About.Card.fit(path, 160);
        assertThat(cut).contains("…").startsWith("/Users").endsWith("data").hasSizeLessThan(path.length());
        assertThat(About.Card.fit("2.0.0", 160)).isEqualTo("2.0.0");
    }

    /// A width too narrow for even one character on each side of the cut leaves only the ellipsis.
    @Test
    void aWidthTooNarrowForAnyTextLeavesTheEllipsis() {
        assertThat(About.Card.fit("/Users/someone/data", 1)).isEqualTo("…");
    }
}
