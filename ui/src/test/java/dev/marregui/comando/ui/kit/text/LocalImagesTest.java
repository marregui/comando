package dev.marregui.comando.ui.kit.text;

import com.github.weisj.jsvg.parser.resources.ResourcePolicy;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LocalImagesTest {

    @TempDir
    Path dir;

    private final List<LocalImages> made = new CopyOnWriteArrayList<>();

    @AfterEach
    void close() {
        made.forEach(LocalImages::close);
    }

    private LocalImages images(Path cache) {
        LocalImages i = new LocalImages(cache);
        made.add(i);
        return i;
    }

    /** The picture once read: asks, waits for the reading thread, asks again. */
    private static LocalImages.Picture read(LocalImages images, Path file) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        LocalImages.Picture first = images.picture(file, done::countDown);
        if (!(first instanceof LocalImages.Picture.Pending)) {
            return first;
        }
        assertThat(done.await(10, TimeUnit.SECONDS)).as("the asker hears when it is read").isTrue();
        return images.picture(file, () -> { });
    }

    private Path png(String name, int w, int h) throws Exception {
        Path file = dir.resolve(name);
        Files.createDirectories(file.getParent());
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", file.toFile());
        return file;
    }

    private Path svg(String name, String body) throws Exception {
        Path file = dir.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
        return file;
    }

    private static final String SQUARE = """
            <svg xmlns="http://www.w3.org/2000/svg" width="120" height="60" viewBox="0 0 120 60">
              <rect x="0" y="0" width="120" height="60" fill="#ff0000"/>
            </svg>
            """;

    @Test
    void anImageLinkNamesAFileBesideTheDocumentInAnyFolderOrByItsFullPath() {
        Path doc = Path.of("/kb/notes");
        assertThat(LocalImages.resolve(doc, "chart.png")).contains(Path.of("/kb/notes/chart.png"));
        assertThat(LocalImages.resolve(doc, "./images/chart.PNG")).contains(Path.of("/kb/notes/images/chart.PNG"));
        assertThat(LocalImages.resolve(doc, "img/sub/flow.svg")).contains(Path.of("/kb/notes/img/sub/flow.svg"));
        assertThat(LocalImages.resolve(doc, "../shared/logo.jpeg")).contains(Path.of("/kb/shared/logo.jpeg"));
        assertThat(LocalImages.resolve(doc, "assets/My%20Chart.jpg")).contains(Path.of("/kb/notes/assets/My Chart.jpg"));
        assertThat(LocalImages.resolve(doc, "my chart.gif")).as("an angle-bracket destination keeps its spaces")
                .contains(Path.of("/kb/notes/my chart.gif"));
        assertThat(LocalImages.resolve(doc, "/abs/pic.png")).contains(Path.of("/abs/pic.png"));
        assertThat(LocalImages.resolve(doc, "file:///abs/pic.svg")).contains(Path.of("/abs/pic.svg"));
        assertThat(LocalImages.resolve(doc, "chart.png#zoom")).contains(Path.of("/kb/notes/chart.png"));

        assertThat(LocalImages.resolve(doc, "https://example.org/chart.png")).as("never the network").isEmpty();
        assertThat(LocalImages.resolve(doc, "http://127.0.0.1/chart.png")).isEmpty();
        assertThat(LocalImages.resolve(doc, "//example.org/chart.png")).as("a scheme-relative address").isEmpty();
        assertThat(LocalImages.resolve(doc, "file://fileserver/share/chart.png")).as("a file on another machine").isEmpty();
        assertThat(LocalImages.resolve(doc, "data:image/png;base64,AAAA")).isEmpty();
        assertThat(LocalImages.resolve(doc, "photo.webp")).as("a kind Swing cannot show").isEmpty();
        assertThat(LocalImages.resolve(doc, "notes.md")).isEmpty();
        assertThat(LocalImages.resolve(doc, "folder.png/")).isEmpty();
        assertThat(LocalImages.resolve(doc, "/..")).as("the root names no file").isEmpty();
        assertThat(LocalImages.resolve(null, "chart.png")).as("relative to nothing").isEmpty();
        assertThat(LocalImages.resolve(null, "/abs/chart.png")).contains(Path.of("/abs/chart.png"));
        assertThat(LocalImages.resolve(doc, "")).isEmpty();
    }

    @Test
    void aPngJpegOrGifIsShownAsItIsAtItsOwnSize() throws Exception {
        LocalImages images = images(null);
        Path png = png("images/chart.png", 640, 480);
        assertThat(read(images, png)).isEqualTo(new LocalImages.Picture.Ready(png, 640, 480));
        Path jpg = dir.resolve("photo.jpg");
        ImageIO.write(new BufferedImage(30, 20, BufferedImage.TYPE_INT_RGB), "jpg", jpg.toFile());
        assertThat(read(images, jpg)).isEqualTo(new LocalImages.Picture.Ready(jpg, 30, 20));
        Path gif = dir.resolve("anim.gif");
        ImageIO.write(new BufferedImage(8, 9, BufferedImage.TYPE_BYTE_INDEXED), "gif", gif.toFile());
        assertThat(read(images, gif)).isEqualTo(new LocalImages.Picture.Ready(gif, 8, 9));
    }

    @Test
    void anSvgLoadsOnlyFilesInItsFolderAndEmbeddedData() {
        Path svg = Path.of("/kb/notes/img/flow.svg");
        URI base = svg.toUri();
        assertThat(ResourcePolicy.ALLOW_RELATIVE.resolveResourceURI(base, "//host/x.png")).as("the library's policy alone")
                .hasToString("file://host/x.png");
        ResourcePolicy beside = LocalImages.beside(svg);
        assertThat(beside.resolveResourceURI(base, "//host/x.png")).as("another machine").isNull();
        assertThat(beside.resolveResourceURI(base, "file://host/x.png")).isNull();
        assertThat(beside.resolveResourceURI(base, "https://example.org/x.png")).isNull();
        assertThat(beside.resolveResourceURI(base, "../../secret.png")).as("outside its folder").isNull();
        assertThat(beside.resolveResourceURI(base, "/etc/x.png")).isNull();
        assertThat(beside.resolveResourceURI(base, "logo.png")).isEqualTo(Path.of("/kb/notes/img/logo.png").toUri());
        assertThat(beside.resolveResourceURI(base, "parts/a.png")).isEqualTo(Path.of("/kb/notes/img/parts/a.png").toUri());
        assertThat(beside.resolveResourceURI(base, "data:image/png;base64,AAAA")).isNotNull();
    }

    @Test
    void anSvgIsDrawnOnceIntoTheCacheTwiceAsLargeAndAgainWhenItChanges() throws Exception {
        Path cache = dir.resolve("cache");
        LocalImages images = images(cache);
        Path flow = svg("img/flow.svg", SQUARE);
        LocalImages.Picture p = read(images, flow);
        assertThat(p).isInstanceOfSatisfying(LocalImages.Picture.Ready.class, r -> {
            assertThat(r.width()).isEqualTo(120);
            assertThat(r.height()).isEqualTo(60);
            assertThat(r.file().getParent()).isEqualTo(cache);
            BufferedImage drawn = readImage(r.file());
            assertThat(drawn.getWidth()).as("drawn at twice its size").isEqualTo(240);
            assertThat(drawn.getHeight()).isEqualTo(120);
            assertThat(new Color(drawn.getRGB(120, 60), true)).as("what the SVG draws").isEqualTo(Color.RED);
        });
        assertThat(images.picture(flow, () -> { })).as("known: shown at once").isEqualTo(p);
        assertThat(pngsIn(cache)).hasSize(1);

        Files.writeString(flow, SQUARE.replace("#ff0000", "#0000ff"));
        Files.setLastModifiedTime(flow, FileTime.fromMillis(System.currentTimeMillis() + 5_000));
        LocalImages.Picture again = read(images, flow);
        assertThat(again).as("changed on disk: drawn again").isNotEqualTo(p)
                .isInstanceOfSatisfying(LocalImages.Picture.Ready.class,
                        r -> assertThat(new Color(readImage(r.file()).getRGB(120, 60), true)).isEqualTo(Color.BLUE));
        try (var files = Files.list(cache)) {
            assertThat(files.map(f -> f.getFileName().toString())).as("no temporary files left").allMatch(n -> n.endsWith(".png") && !n.startsWith("."));
        }
    }

    @Test
    void anSvgWithoutASizeTakesItsViewBoxAndAHugeOneIsDrawnSmaller() throws Exception {
        LocalImages images = images(dir.resolve("cache"));
        Path viewBox = svg("viewbox.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 400 100\"><rect width=\"400\" height=\"100\"/></svg>");
        assertThat(read(images, viewBox)).isInstanceOfSatisfying(LocalImages.Picture.Ready.class, r -> {
            assertThat(r.width()).isEqualTo(400);
            assertThat(r.height()).isEqualTo(100);
        });
        Path huge = svg("huge.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"40000\" height=\"20000\"><rect width=\"40000\" height=\"20000\"/></svg>");
        assertThat(read(images, huge)).isInstanceOfSatisfying(LocalImages.Picture.Ready.class, r -> {
            assertThat(r.width()).isEqualTo(LocalImages.MAX_SIDE);
            assertThat(r.height()).as("the shape kept").isEqualTo(LocalImages.MAX_SIDE / 2);
        });
    }

    @Test
    void whatCannotBeShownSaysWhy() throws Exception {
        LocalImages images = images(dir.resolve("cache"));
        assertThat(images.picture(dir.resolve("missing.png"), () -> { }))
                .isEqualTo(new LocalImages.Picture.Unavailable("no such file: missing.png"));
        Path notPng = svg("fake.png", "this is text, not a picture");
        assertThat(read(images, notPng)).isInstanceOfSatisfying(LocalImages.Picture.Unavailable.class,
                u -> assertThat(u.why()).isEqualTo("fake.png is not a picture comando can read"));
        Path broken = svg("broken.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"><rect");
        assertThat(read(images, broken)).isInstanceOf(LocalImages.Picture.Unavailable.class);
        Path big = dir.resolve("big.png");
        try (RandomAccessFile f = new RandomAccessFile(big.toFile(), "rw")) {
            f.setLength(LocalImages.MAX_BYTES + 1);
        }
        assertThat(read(images, big)).isEqualTo(new LocalImages.Picture.Unavailable("big.png is over 32 MB"));
        assertThat(read(images(null), svg("nocache.svg", SQUARE))).as("no cache to draw into")
                .isEqualTo(new LocalImages.Picture.Unavailable("SVG pictures are not drawn here"));
    }

    @Test
    void anSvgLoadsWhatIsBesideItAndNothingFromTheNetwork() throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        try {
            String web = "http://127.0.0.1:" + server.getAddress().getPort();
            png("beside.png", 10, 10);
            Path page = svg("page.svg", """
                    <svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="100" height="100">
                      <style>@import url(%1$s/imported.css);</style>
                      <image href="%1$s/remote.png" width="50" height="50"/>
                      <image xlink:href="%1$s/old-style.png" width="50" height="50"/>
                      <use href="%1$s/shapes.svg#dot"/>
                      <rect width="10" height="10" fill="url(%1$s/paint.svg#p)"/>
                      <image href="beside.png" x="50" y="50" width="10" height="10"/>
                    </svg>
                    """.formatted(web));
            assertThat(read(images(dir.resolve("cache")), page)).as("drawn, without what it names on the network")
                    .isInstanceOf(LocalImages.Picture.Ready.class);
            assertThat(requests).as("nothing is fetched from the network").isEmpty();
        } finally {
            server.stop(0);
        }
    }

    private static BufferedImage readImage(Path file) {
        try {
            return ImageIO.read(file.toFile());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<Path> pngsIn(Path dir) throws Exception {
        try (var files = Files.list(dir)) {
            return files.filter(f -> f.getFileName().toString().endsWith(".png")).toList();
        }
    }
}
