package dev.marregui.comando.app;

import java.awt.Color;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.ui.kit.theme.Ground;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.theme.Tint;
import dev.marregui.comando.ui.kit.web.PageView;
import dev.marregui.comando.ui.kit.web.PageViews;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// An HTML page rendered by JavaFX's WebView in a window, as a panel shows it. JavaFX needs a display, so this runs in
/// `./gradlew :app:uiTest`.
/// - the page's title and visible text read back, a `<details>` and an inline SVG among them
/// - a snapshot is the component's size and shows the page
/// - a reload shows the file as it is on disk now, at the scroll position it had
/// - a link to the web is handed over and not followed; an anchor in the page scrolls in place
/// - the panel behind the page takes the theme's colour
/// - the arrow keys scroll a page that was clicked
/// - closing twice is fine, and the futures fail after it
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class WebPageViewUiTest {

    /**
     * The bound on a page's load and a script's answer: the first test of a JVM, which starts WebKit, took 4.2 s on
     * the MacBook Pro this was written on (2026-11-22); over three times that.
     */
    private static final Duration PAGE = Duration.ofSeconds(15);

    /** How long a link not followed is watched for a navigation that should not happen; chosen, not measured. */
    private static final Duration STAYS = Duration.ofSeconds(2);

    private static final int WIDTH = 640;

    private static final int HEIGHT = 400;

    @TempDir
    private Path dir;

    private final List<URI> links = new CopyOnWriteArrayList<>();

    private PageView page;

    private JFrame frame;

    @BeforeEach
    void openPage() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            page = PageViews.create();
            page.onLink(links::add);
            frame = new JFrame("page");
            frame.setUndecorated(true);
            frame.setAutoRequestFocus(false);
            frame.add(page.component());
            frame.setBounds(-5_000, 0, WIDTH, HEIGHT);
            frame.setVisible(true);
        });
    }

    @AfterEach
    void closePage() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            page.close();
            frame.dispose();
        });
    }

    /// The title, the summary of a closed `<details>` and the text around an inline SVG read back as the page's text.
    @Test
    void thePageReadsBackItsTitleAndItsVisibleText() throws Exception {
        load(write("fixture.html", """
                <!doctype html><html><head><title>Fixture page</title></head><body>
                <h1>Heading words</h1>
                <details><summary>More below</summary><p>Folded words</p></details>
                <svg width="80" height="40"><rect width="80" height="40" fill="#c03030"/></svg>
                <p>Closing words</p>
                </body></html>
                """));

        final PageView.Text text = awaitText(t -> t.body().contains("Closing words"));

        assertThat(text.title()).isEqualTo("Fixture page");
        assertThat(text.body()).contains("Heading words", "More below", "Closing words");
        assertThat(text.selection()).isEmpty();
    }

    /// A snapshot is as large as the component and shows the page: more than one colour.
    @Test
    void aSnapshotIsTheComponentsSizeAndShowsThePage() throws Exception {
        load(write("picture.html", """
                <!doctype html><html><head><title>Picture</title></head>
                <body style="background:#ffffff"><div style="width:200px;height:100px;background:#c03030"></div>
                <p>Words</p></body></html>
                """));
        awaitText(t -> t.body().contains("Words"));

        final BufferedImage image = page.snapshot().get(PAGE.toMillis(), TimeUnit.MILLISECONDS);

        final JComponent component = page.component();
        assertThat(image.getWidth()).isEqualTo(component.getWidth());
        assertThat(image.getHeight()).isEqualTo(component.getHeight());
        final Set<Integer> colours = new HashSet<>();
        for (int y = 0; y < image.getHeight(); y += 10) {
            for (int x = 0; x < image.getWidth(); x += 10) {
                colours.add(image.getRGB(x, y));
            }
        }
        assertThat(colours).as("not blank").hasSizeGreaterThan(1);
        assertThat(new Color(image.getRGB(50, 30))).as("the red block at the top left").isEqualTo(new Color(0xc03030));
    }

    /// A reload after the file changed on disk shows the new text.
    @Test
    void aReloadShowsTheFileAsItIsOnDiskNow() throws Exception {
        final Path file = write("changing.html", "<html><body><p>First text</p></body></html>");
        load(file);
        awaitText(t -> t.body().contains("First text"));

        Files.writeString(file, "<html><body><p>Second text</p></body></html>");
        SwingUtilities.invokeAndWait(page::reload);

        awaitText(t -> t.body().contains("Second text"));
    }

    /// A click on an anchor of the page scrolls it in place without handing a link over; a reload after the file
    /// changed keeps that scroll position.
    @Test
    void anAnchorScrollsInPlaceAndAReloadKeepsTheScrollPosition() throws Exception {
        final Path file = write("long.html", longPage("Before"));
        load(file);
        awaitText(t -> t.body().contains("Before"));

        click(40, 20);
        final String scrolled = awaitText(t -> t.title().startsWith("y=") && !"y=0".equals(t.title())).title();
        assertThat(links).as("an anchor of the page is not handed over").isEmpty();

        Files.writeString(file, longPage("After"));
        SwingUtilities.invokeAndWait(page::reload);

        awaitText(t -> t.body().contains("After"));
        final PageView.Text kept = awaitText(t -> t.title().equals(scrolled));
        assertThat(kept.title()).isEqualTo(scrolled);
    }

    /// A click on a link to a web address hands the address to the link consumer and the page stays where it is.
    @Test
    void aLinkToTheWebIsHandedOverAndNotFollowed() throws Exception {
        load(write("links.html", """
                <!doctype html><html><head><title>Links page</title></head><body style="margin:0">
                <a href="https://example.com/" style="display:block;width:300px;height:60px">Out to the web</a>
                </body></html>
                """));
        awaitText(t -> t.body().contains("Out to the web"));

        click(40, 20);

        Awaitility.await().atMost(PAGE).until(() -> !links.isEmpty());
        assertThat(links).containsExactly(URI.create("https://example.com/"));
        Awaitility.await().during(STAYS).atMost(STAYS.plus(PAGE)).until(() -> "Links page".equals(text().title()));
    }

    /// The same link clicked twice is handed over twice, and the page still stays: the second click changes no
    /// address, so only a check on every load it schedules stops it.
    @Test
    void theSameLinkClickedTwiceIsHandedOverTwiceAndNotFollowed() throws Exception {
        write("other.html", "<!doctype html><html><head><title>Other page</title></head><body>other</body></html>");
        load(write("twice.html", """
                <!doctype html><html><head><title>Twice page</title></head><body style="margin:0">
                <a href="other.html" style="display:block;width:300px;height:60px">To the other page</a>
                </body></html>
                """));
        awaitText(t -> t.body().contains("To the other page"));

        click(40, 20);
        Awaitility.await().atMost(PAGE).until(() -> links.size() == 1);
        click(40, 20);
        Awaitility.await().atMost(PAGE).until(() -> links.size() == 2);

        assertThat(links).containsExactly(dir.resolve("other.html").toUri(), dir.resolve("other.html").toUri());
        Awaitility.await().during(STAYS).atMost(STAYS.plus(PAGE)).until(() -> "Twice page".equals(text().title()));
    }

    /// A page loaded without scripts shows its text, yet its own script does not run: it cannot read another file
    /// on this machine. The view still reads the page.
    @Test
    void aPageWithoutScriptsDoesNotRunItsOwn() throws Exception {
        write("secret.txt", "SECRET-CONTENT");
        final Path untrusted = write("untrusted.html", """
                <!doctype html><html><head><title>Untrusted</title></head><body><p>Visible text</p>
                <div id="out">none</div><script>
                var x = new XMLHttpRequest(); x.open('GET', 'secret.txt', false); x.send();
                document.getElementById('out').textContent = 'read:' + x.responseText;
                </script></body></html>
                """);
        SwingUtilities.invokeAndWait(() -> page.load(untrusted, false));

        final PageView.Text shown = awaitText(t -> t.body().contains("Visible text"));

        assertThat(shown.title()).isEqualTo("Untrusted");
        assertThat(shown.body()).contains("none").doesNotContain("SECRET-CONTENT");
    }

    /// The panel behind the page takes the look's panel colour, and takes it again when another theme is chosen.
    @Test
    void thePanelBehindThePageFollowsTheTheme() throws Exception {
        SwingUtilities.invokeAndWait(Theme::install);
        try {
            SwingUtilities.invokeAndWait(() -> Theme.choose(Ground.PAPER, Tint.TREASURE));
            assertThat(onEdt(() -> page.component().getBackground()))
                    .isEqualTo(onEdt(() -> UIManager.getColor("Panel.background")));
            final Color paper = onEdt(() -> page.component().getBackground());

            SwingUtilities.invokeAndWait(() -> Theme.choose(Ground.GRAPHITE, Tint.TREASURE));

            assertThat(onEdt(() -> page.component().getBackground()))
                    .isEqualTo(onEdt(() -> UIManager.getColor("Panel.background")))
                    .isNotEqualTo(paper);
        } finally {
            SwingUtilities.invokeAndWait(() -> Theme.choose(Ground.GRAPHITE, Tint.TREASURE));
        }
    }

    /// A page with no style sheet of its own takes the theme's ground, and the next theme's when another is chosen; a
    /// page with its own style sheet keeps its own ground under either theme.
    @Test
    void aPageWithoutStyleTakesTheThemeAndOneWithStyleKeepsItsOwn() throws Exception {
        SwingUtilities.invokeAndWait(Theme::install);
        try {
            SwingUtilities.invokeAndWait(() -> Theme.choose(Ground.PAPER, Tint.TREASURE));
            final Color paper = onEdt(Theme::ground);
            load(write("plain.html", "<!doctype html><html><head><title>Plain</title></head><body><p>Plain text</p>"
                    + "</body></html>"));
            awaitText(t -> t.body().contains("Plain text"));
            Awaitility.await().atMost(PAGE).until(() -> near(corner(), paper));

            SwingUtilities.invokeAndWait(() -> Theme.choose(Ground.GRAPHITE, Tint.TREASURE));
            final Color graphite = onEdt(Theme::ground);
            assertThat(graphite).isNotEqualTo(paper);
            Awaitility.await().atMost(PAGE).until(() -> near(corner(), graphite));

            load(write("styled.html", "<!doctype html><html><head><title>Styled</title>"
                    + "<style>body { background: #336699; }</style></head><body><p>Styled text</p></body></html>"));
            awaitText(t -> t.title().equals("Styled"));
            Awaitility.await().atMost(PAGE).until(() -> near(corner(), new Color(0x336699)));
        } finally {
            SwingUtilities.invokeAndWait(() -> Theme.choose(Ground.GRAPHITE, Tint.TREASURE));
        }
    }

    /// A page whose last load was a link stopped before it left the page still takes the next theme.
    @Test
    void aPageAfterAStoppedLinkStillTakesTheNextTheme() throws Exception {
        SwingUtilities.invokeAndWait(Theme::install);
        try {
            SwingUtilities.invokeAndWait(() -> Theme.choose(Ground.PAPER, Tint.TREASURE));
            write("away.html", "<!doctype html><html><head><title>Away</title></head><body>away</body></html>");
            load(write("stay.html", """
                    <!doctype html><html><head><title>Stay</title></head><body style="margin:0">
                    <a href="away.html" style="display:block;width:300px;height:60px">Away</a>
                    </body></html>
                    """));
            awaitText(t -> t.title().equals("Stay"));
            click(40, 20);
            Awaitility.await().atMost(PAGE).until(() -> links.size() == 1);

            SwingUtilities.invokeAndWait(() -> Theme.choose(Ground.GRAPHITE, Tint.TREASURE));
            final Color graphite = onEdt(Theme::ground);

            Awaitility.await().atMost(PAGE).until(() -> near(corner(320, 200), graphite));
            assertThat(text().title()).isEqualTo("Stay");
        } finally {
            SwingUtilities.invokeAndWait(() -> Theme.choose(Ground.GRAPHITE, Tint.TREASURE));
        }
    }

    /** The colour of the page's top-left corner as the view draws it now. */
    private Color corner() throws Exception {
        return corner(4, 4);
    }

    /** The colour of the page at {@code (x, y)} as the view draws it now. */
    private Color corner(final int x, final int y) throws Exception {
        return new Color(page.snapshot().get(PAGE.toMillis(), TimeUnit.MILLISECONDS).getRGB(x, y));
    }

    /** Whether {@code a} and {@code b} differ by at most 2 in each channel: WebKit may round a colour it blends. */
    private static boolean near(final Color a, final Color b) {
        return Math.abs(a.getRed() - b.getRed()) <= 2 && Math.abs(a.getGreen() - b.getGreen()) <= 2
                && Math.abs(a.getBlue() - b.getBlue()) <= 2;
    }

    /// Once the page is clicked, the down arrow scrolls it: the keys reach the page through its Swing component.
    @Test
    void theDownArrowScrollsAClickedPage() throws Exception {
        load(write("keys.html", """
                <!doctype html><html><head><title>keys</title></head><body style="margin:0">
                <div style="height:3000px">Keys page</div>
                <script>
                window.addEventListener('scroll', function () {
                    document.title = 'y=' + Math.round(window.scrollY);
                });
                </script>
                </body></html>
                """));
        awaitText(t -> t.body().contains("Keys page"));
        SwingUtilities.invokeAndWait(() -> {
            final JComponent c = page.component();
            c.dispatchEvent(new FocusEvent(c, FocusEvent.FOCUS_GAINED));
        });
        click(40, 20);

        press(KeyEvent.VK_DOWN);
        awaitText(t -> t.title().startsWith("y=") && !"y=0".equals(t.title()));
    }

    /// Closing twice is fine; a future asked for after the close fails.
    @Test
    void closingTwiceIsFineAndTheFuturesFailAfterIt() throws Exception {
        load(write("closing.html", "<html><body><p>Closing</p></body></html>"));
        awaitText(t -> t.body().contains("Closing"));

        SwingUtilities.invokeAndWait(() -> {
            page.close();
            page.close();
        });

        assertThatThrownBy(() -> page.text().get(PAGE.toMillis(), TimeUnit.MILLISECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> page.snapshot().get(PAGE.toMillis(), TimeUnit.MILLISECONDS))
                .isInstanceOf(ExecutionException.class);
    }

    /** A page taller than the view with an anchor at its top to its foot; a scroll sets its title to the position. */
    private static String longPage(final String marker) {
        return """
                <!doctype html><html><head><title>start</title></head><body style="margin:0">
                <a href="#foot" style="display:block;width:300px;height:60px">To the foot</a>
                <div style="height:3000px">%s</div>
                <p id="foot">Foot</p>
                <script>
                window.addEventListener('scroll', function () {
                    document.title = 'y=' + Math.round(window.scrollY);
                });
                </script>
                </body></html>
                """.formatted(marker);
    }

    private Path write(final String name, final String html) throws Exception {
        return Files.writeString(dir.resolve(name), html);
    }

    private void load(final Path file) throws Exception {
        SwingUtilities.invokeAndWait(() -> page.load(file, true));
    }

    private PageView.Text text() throws Exception {
        return page.text().get(PAGE.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** The page's text once {@code ready} holds for it, within {@link #PAGE}. */
    private PageView.Text awaitText(final Predicate<PageView.Text> ready) {
        final AtomicReference<PageView.Text> seen = new AtomicReference<>();
        Awaitility.await().atMost(PAGE).ignoreExceptions().until(() -> {
            seen.set(text());
            return ready.test(seen.get());
        });
        return seen.get();
    }

    /** A left click at {@code (x, y)} of the page's component, as the mouse would deliver it. */
    private void click(final int x, final int y) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final JComponent c = page.component();
            final long now = System.currentTimeMillis();
            c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_PRESSED, now, InputEvent.BUTTON1_DOWN_MASK, x, y, 1,
                    false, MouseEvent.BUTTON1));
            c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_RELEASED, now, 0, x, y, 1, false,
                    MouseEvent.BUTTON1));
            c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_CLICKED, now, 0, x, y, 1, false,
                    MouseEvent.BUTTON1));
        });
    }

    /** A press and release of {@code key}, a key that types no character, as the keyboard would deliver them. */
    private void press(final int key) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final JComponent c = page.component();
            final long now = System.currentTimeMillis();
            c.dispatchEvent(new KeyEvent(c, KeyEvent.KEY_PRESSED, now, 0, key, KeyEvent.CHAR_UNDEFINED));
            c.dispatchEvent(new KeyEvent(c, KeyEvent.KEY_RELEASED, now, 0, key, KeyEvent.CHAR_UNDEFINED));
        });
    }

    private static <T> T onEdt(final Supplier<T> read) throws Exception {
        final AtomicReference<T> out = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> out.set(read.get()));
        return out.get();
    }
}
