package dev.marregui.comando.ui.kit.text;

import dev.marregui.comando.core.loop.Threads;
import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.parser.LoaderContext;
import com.github.weisj.jsvg.parser.SVGLoader;
import com.github.weisj.jsvg.parser.resources.ResourcePolicy;
import com.github.weisj.jsvg.view.FloatSize;
import com.github.weisj.jsvg.view.ViewBox;
import dev.marregui.comando.core.model.AtomicFiles;
import dev.marregui.comando.core.loop.ExecutorStop;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * The pictures a Markdown document names by a local path, for the preview: a PNG, JPEG or GIF as it is, an SVG drawn
 * once into a PNG in a cache folder. Nothing comes from the network: a web address is not resolved to a picture, and
 * an SVG may name only files beside it. Pictures are read on the {@code local-images} strand, one at a time; whoever
 * asked hears when it is ready, and a picture changed on disk is read again.
 */
public final class LocalImages {

    private static final System.Logger LOG = System.getLogger(LocalImages.class.getName());
    /** Pixels per point of a drawn SVG: drawn twice as large and shown at half, sharp on a Retina screen. */
    static final int SCALE = 2;
    /** The largest file read as a picture. */
    static final long MAX_BYTES = 32L * 1024 * 1024;
    /** The largest side, in points, an SVG is drawn at. */
    static final int MAX_SIDE = 4096;
    /** The size an SVG that states none is drawn at. */
    private static final int DEFAULT_SIDE = 300;
    private static final Set<String> RASTER = Set.of("png", "jpg", "jpeg", "gif");
    private static final String SVG = "svg";

    /** What the preview can show for a picture. */
    public sealed interface Picture {
        /** The file to show, a PNG drawn from an SVG or the picture itself, with the size to show it at, in points. */
        record Ready(Path file, int width, int height) implements Picture {
        }

        /** Being read; the caller hears when it is ready. */
        record Pending() implements Picture {
        }

        /** Not shown, and why. */
        record Unavailable(String why) implements Picture {
        }
    }

    /** A picture as it is on disk now: a change to the file is a new key. */
    private record Key(Path file, long modified, long size) {
    }

    private static volatile LocalImages installed = new LocalImages(null);

    private final Path cache;
    private final Map<Key, Picture> read = new ConcurrentHashMap<>();
    /** Keys being read, with who waits for each. */
    private final Map<Key, List<Runnable>> waiting = new ConcurrentHashMap<>();
    private final ExecutorService reader =
            Threads.strand("local-images");

    /** Pictures read from disk, SVGs drawn into {@code cache}; without a cache an SVG is not shown. */
    LocalImages(Path cache) {
        this.cache = cache;
    }

    /** The pictures the previews use. */
    public static LocalImages current() {
        return installed;
    }

    /** Draws SVGs into {@code cache} from now on. */
    public static void install(Path cache) {
        installed.close();
        installed = new LocalImages(cache);
    }

    /** Stops reading: a picture being read is abandoned. */
    public static void shutdown() {
        installed.close();
    }

    void close() {
        ExecutorStop.now("local-images", reader);
    }

    /**
     * The picture file an image link names, as {@link LocalLinks#file} reads it: a PNG, JPEG, GIF or SVG; empty for
     * any other kind of file.
     */
    public static Optional<Path> resolve(Path folder, String destination) {
        return LocalLinks.file(folder, destination).filter(f -> shown(f.getFileName().toString()));
    }

    /** Whether a file of this name is a kind of picture the preview shows. */
    private static boolean shown(String name) {
        String ext = extension(name);
        return RASTER.contains(ext) || ext.equals(SVG);
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return dot <= slash ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * The picture {@code file} holds, or why there is none; when it is not read yet, reading starts and
     * {@code whenReady} runs, on the reading thread, once it is read or refused: once, however often the same callback
     * is handed in meanwhile.
     */
    public Picture picture(Path file, Runnable whenReady) {
        Key key;
        try {
            if (!Files.isRegularFile(file)) {
                return new Picture.Unavailable("no such file: " + file.getFileName());
            }
            key = new Key(file, Files.getLastModifiedTime(file).toMillis(), Files.size(file));
        } catch (IOException e) {
            return new Picture.Unavailable("cannot read " + file.getFileName() + ": " + e.getMessage());
        }
        Picture known = read.get(key);
        if (known != null) {
            return known;
        }
        boolean[] first = new boolean[1];
        waiting.compute(key, (k, list) -> {
            List<Runnable> l = list == null ? new ArrayList<>() : list;
            first[0] = list == null;
            // a preview rendered again while it waits hands in the same callback: told once, not once per render
            if (!l.contains(whenReady)) {
                l.add(whenReady);
            }
            return l;
        });
        if (first[0]) {
            try {
                reader.execute(() -> {
                    read.put(key, load(key));
                    List<Runnable> callbacks = waiting.remove(key);
                    if (callbacks != null) {
                        callbacks.forEach(Runnable::run);
                    }
                });
            } catch (RejectedExecutionException e) {
                waiting.remove(key);
                return new Picture.Unavailable("comando is closing");
            }
        }
        return new Picture.Pending();
    }

    private Picture load(Key key) {
        Path file = key.file();
        String name = file.getFileName().toString();
        if (key.size() > MAX_BYTES) {
            return new Picture.Unavailable(name + " is over " + MAX_BYTES / (1024 * 1024) + " MB");
        }
        try {
            return extension(name).equals(SVG) ? drawSvg(key) : rasterSize(file);
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "picture not shown [file=" + file + ']', e);
            return new Picture.Unavailable("cannot read " + name + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    /** A PNG, JPEG or GIF is shown as it is: only its size is read, from its header. */
    private static Picture rasterSize(Path file) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
            if (readers == null || !readers.hasNext()) {
                return new Picture.Unavailable(file.getFileName() + " is not a picture comando can read");
            }
            ImageReader r = readers.next();
            try {
                r.setInput(in, true, true);
                return new Picture.Ready(file, r.getWidth(0), r.getHeight(0));
            } finally {
                r.dispose();
            }
        }
    }

    /**
     * An SVG is drawn once into a PNG in the cache, twice as large as it is shown; what it names outside its folder is
     * not loaded.
     */
    private Picture drawSvg(Key key) throws IOException {
        if (cache == null) {
            return new Picture.Unavailable("SVG pictures are not drawn here");
        }
        Path png = cache.resolve(cacheName(key));
        SVGDocument doc;
        try (InputStream in = Files.newInputStream(key.file())) {
            LoaderContext context = LoaderContext.builder().externalResourcePolicy(beside(key.file())).build();
            doc = new SVGLoader().load(in, key.file().toUri(), context);
        }
        if (doc == null) {
            return new Picture.Unavailable(key.file().getFileName() + " is not an SVG comando can read");
        }
        FloatSize size = doc.size();
        double w = size.width > 0 ? size.width : DEFAULT_SIDE;
        double h = size.height > 0 ? size.height : DEFAULT_SIDE;
        double fit = Math.min(1.0, MAX_SIDE / Math.max(w, h));
        int width = Math.max(1, (int) Math.round(w * fit));
        int height = Math.max(1, (int) Math.round(h * fit));
        if (!Files.isRegularFile(png)) {
            BufferedImage image = new BufferedImage(width * SCALE, height * SCALE, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                g.scale(SCALE * fit, SCALE * fit);
                doc.render((Component) null, g, new ViewBox(0, 0, (float) w, (float) h));
            } finally {
                g.dispose();
            }
            Files.createDirectories(cache);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(image, "png", bytes);
            AtomicFiles.write(png, bytes.toByteArray());
        }
        return new Picture.Ready(png, width, height);
    }

    /**
     * What an SVG may load: embedded data, and files in its folder or below it. The library's relative policy alone
     * resolves {@code //host/x.png} to {@code file://host/x.png}, which the JDK opens on that machine, and
     * {@code ../x.png} to a file outside the folder.
     */
    static ResourcePolicy beside(Path svg) {
        Path folder = svg.toAbsolutePath().normalize().getParent();
        return new ResourcePolicy() {
            @Override
            public URI resolveResourceURI(URI base, String path) {
                return confine(ResourcePolicy.ALLOW_RELATIVE.resolveResourceURI(base, path));
            }

            @Override
            public URI resolveResourceURI(URI base, URI resource) {
                return confine(ResourcePolicy.ALLOW_RELATIVE.resolveResourceURI(base, resource));
            }

            private URI confine(URI uri) {
                if (uri == null || "data".equals(uri.getScheme())) {
                    return uri;
                }
                if (folder == null || !"file".equalsIgnoreCase(uri.getScheme()) || uri.getRawAuthority() != null) {
                    return null;
                }
                try {
                    return Path.of(uri).normalize().startsWith(folder) ? uri : null;
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }
        };
    }

    /** The drawing's file in the cache: one per SVG as it is on disk now, at this scale. */
    private static String cacheName(Key key) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update((key.file().toAbsolutePath() + "|" + key.modified() + "|" + key.size() + "|" + SCALE).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sha.digest(), 0, 16) + ".png";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing", e);
        }
    }
}
