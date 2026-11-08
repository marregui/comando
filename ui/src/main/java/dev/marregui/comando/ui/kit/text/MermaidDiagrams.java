package dev.marregui.comando.ui.kit.text;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

import dev.marregui.comando.core.files.Mapped;
import dev.marregui.comando.core.loop.ExecutorStop;
import dev.marregui.comando.core.loop.Threads;
import dev.marregui.comando.core.model.AtomicFiles;
import dev.marregui.comando.core.view.Clip;
import dev.marregui.comando.ui.kit.async.Waiters;
import dev.marregui.comando.ui.kit.desktop.Platform;
import dev.marregui.comando.ui.kit.format.Versions;
import dev.marregui.comando.ui.kit.theme.Theme;

/**
 * Mermaid diagrams drawn by {@code mmdc} (the Mermaid command-line renderer) into a cache folder, once per diagram
 * text and mode, for the Markdown preview: in Mermaid's dark theme on a dark theme, its default one on a light theme,
 * so lines and arrows show on either ground. Swing's HTML renderer runs no JavaScript, so it shows the picture
 * {@code mmdc} made.
 * A diagram not drawn yet is drawn on the {@code mermaid} strand, one at a time, within {@link #TIMEOUT}; whoever
 * asked hears when it is ready. A diagram {@code mmdc} rejects is remembered with the reason, so it is not drawn
 * again until its text changes. Until {@link #install} runs, and where {@code mmdc} is not installed, no diagram is
 * drawn.
 */
public final class MermaidDiagrams {

    private static final System.Logger LOG = System.getLogger(MermaidDiagrams.class.getName());
    /** Pixels per point: the picture is drawn twice as large and shown at half, sharp on a Retina screen. */
    static final int SCALE = 2;
    /** Part of every cache key: a change of how diagrams are drawn draws them again. */
    private static final String STYLE = "transparent|" + SCALE;
    static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_REASON_CHARS = 200;

    /** What the preview can show for a diagram. */
    public sealed interface Diagram {
        /** The picture, with the size to show it at, in points. */
        record Ready(Path png, int width, int height) implements Diagram {
        }

        /** Being drawn; the caller hears when it is ready. */
        record Pending() implements Diagram {
        }

        /** Not drawn, and why: no {@code mmdc}, or the diagram's text is not valid Mermaid. */
        record Unavailable(String why) implements Diagram {
        }
    }

    private static volatile MermaidDiagrams installed = new MermaidDiagrams(null, null);

    private final Path cache;
    private final Path mmdc;
    private final Duration timeout;
    private final Map<String, String> failures = new ConcurrentHashMap<>();
    private final Map<String, int[]> sizes = new ConcurrentHashMap<>();
    /** Keys being drawn, with who waits for each. */
    private final Waiters<String> waiting = new Waiters<>();
    private final ExecutorService drawer;
    private volatile Process drawing;

    MermaidDiagrams(final Path cache, final Path mmdc) {
        this(cache, mmdc, TIMEOUT);
    }

    MermaidDiagrams(final Path cache, final Path mmdc, final Duration timeout) {
        this.cache = cache;
        this.mmdc = mmdc;
        this.timeout = timeout;
        this.drawer = cache == null || mmdc == null ? null
                : Threads.strand("mermaid");
    }

    /** The diagrams the previews use. */
    public static MermaidDiagrams current() {
        return installed;
    }

    /** Draws diagrams into {@code cache} from now on, with the {@code mmdc} found on this machine, if any. */
    public static void install(final Path cache) {
        final Optional<Path> found = findMmdc();
        if (found.isEmpty()) {
            LOG.log(System.Logger.Level.INFO, "mmdc not found; Mermaid diagrams show as their source");
        }
        install(cache, found.orElse(null));
    }

    /** Draws diagrams into {@code cache} with {@code mmdc} from now on; none without it. For tests. */
    static void install(final Path cache, final Path mmdc) {
        installed.close();
        installed = new MermaidDiagrams(cache, mmdc);
    }

    /** Stops drawing: the diagram being drawn is abandoned and its {@code mmdc} stopped. */
    public static void shutdown() {
        installed.close();
    }

    /**
     * The picture of {@code source}, or why there is none; when it is not drawn yet, drawing starts and
     * {@code whenReady} runs, on the drawing thread, once it is drawn or rejected: once, however often the same
     * callback is handed in meanwhile.
     */
    public Diagram diagram(final String source, final Runnable whenReady) {
        return diagram(source, Theme.light(), whenReady);
    }

    /** As {@link #diagram(String, Runnable)}, drawn for a light theme when {@code light}, else for a dark one. */
    Diagram diagram(final String source, final boolean light, final Runnable whenReady) {
        if (drawer == null) {
            return new Diagram.Unavailable(mmdc == null && cache != null
                    ? "install mermaid-cli (npm install -g @mermaid-js/mermaid-cli) to see this diagram drawn"
                    : "diagrams are not drawn here");
        }
        final String key = key(source, light);
        final String failed = failures.get(key);
        if (failed != null) {
            return new Diagram.Unavailable(failed);
        }
        final Path png = cache.resolve(key + ".png");
        if (Files.isRegularFile(png)) {
            final int[] size = sizes.computeIfAbsent(key, k -> size(png));
            if (size[0] > 0) {
                return new Diagram.Ready(png, Math.max(1, size[0] / SCALE), Math.max(1, size[1] / SCALE));
            }
            // drawn, but not a picture: shown as a failure, not drawn and announced again and again
            return new Diagram.Unavailable(unreadable(key, png));
        }
        if (waiting.join(key, whenReady)) {
            try {
                drawer.execute(() -> draw(key, source, png, light));
            } catch (final RejectedExecutionException e) {
                waiting.abandon(key);
                return new Diagram.Unavailable("comando is closing");
            }
        }
        return new Diagram.Pending();
    }

    private void draw(final String key, final String source, final Path png, final boolean light) {
        if (Files.isRegularFile(png)) {
            // asked again while the last drawing of it finished: it is there
            waiting.finish(key);
            return;
        }
        Process p = null;
        try {
            Files.createDirectories(cache);
            final Path in = Files.createTempFile(cache, "." + key, ".mmd");
            final Path out = cache.resolve("." + key + ".tmp.png");
            final Path err = Files.createTempFile(cache, "." + key, ".err");
            try {
                Files.writeString(in, source, StandardCharsets.UTF_8);
                final ProcessBuilder pb = new ProcessBuilder(mmdc.toString(), "-q", "-i", in.toString(), "-o", out.toString(),
                        "-t", light ? "default" : "dark", "-b", "transparent", "-s", Integer.toString(SCALE))
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(err.toFile());
                // mmdc is a node script and an app started from Finder has no node on its PATH: mmdc's folder first
                pb.environment().put("PATH", mmdc.getParent() + File.pathSeparator + System.getenv().getOrDefault("PATH", "/usr/bin:/bin"));
                p = pb.start();
                drawing = p;
                p.getOutputStream().close();
                if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    // mmdc drives a headless Chromium; stopping node alone would leave the browser running
                    stop(p);
                    failures.put(key, "mmdc did not finish within " + timeout.toSeconds() + " s");
                } else if (p.exitValue() != 0 || !Files.isRegularFile(out)) {
                    failures.put(key, "mmdc cannot draw it: " + firstLine(Mapped.text(err)));
                } else {
                    AtomicFiles.move(out, png);
                }
            } finally {
                drawing = null;
                Files.deleteIfExists(in);
                Files.deleteIfExists(out);
                Files.deleteIfExists(err);
            }
        } catch (final IOException | RuntimeException e) {
            // any failure is the diagram's, told once: a key left waiting would show as drawing forever
            failures.put(key, "cannot draw it: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            if (p != null) {
                // close interrupted the wait before it could see this process: it would outlive comando
                stop(p);
            }
            waiting.abandon(key);
            return;
        }
        final String failed = failures.get(key);
        if (failed != null) {
            LOG.log(System.Logger.Level.WARNING, "Mermaid diagram not drawn [key=" + key + "]: " + failed);
        }
        waiting.finish(key);
    }

    /** Records that the cached picture of {@code key} cannot be read and removes it; returns why. */
    private String unreadable(final String key, final Path png) {
        final String why = "mmdc drew a picture that cannot be read";
        failures.put(key, why);
        sizes.remove(key);
        try {
            Files.deleteIfExists(png);
        } catch (final IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "cannot remove [file=" + png + ']', e);
        }
        LOG.log(System.Logger.Level.WARNING, "Mermaid diagram not shown [key=" + key + "]: " + why);
        return why;
    }

    void close() {
        if (drawer != null) {
            final Process p = drawing;
            if (p != null) {
                stop(p);
            }
            // after mmdc is stopped, so the drawer's wait for it ends at once
            ExecutorStop.now("mermaid", Map.of("mermaid", drawer));
        }
    }

    /** Stops {@code p} and every process it started; the children first, as they leave its tree when it ends. */
    private static void stop(final Process p) {
        // only while it lives: the JDK finds descendants by pid, and an ended process's pid may be another's
        if (p.isAlive()) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
        }
        p.destroyForcibly();
    }

    /** The cache file name of a diagram drawn for a dark theme: its text and how it is drawn, hashed. */
    static String key(final String source) {
        return key(source, false);
    }

    /** The cache file name of a diagram: its text, the mode it is drawn for and how it is drawn, hashed. */
    static String key(final String source, final boolean light) {
        try {
            final MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(((light ? "default|" : "dark|") + STYLE).getBytes(StandardCharsets.UTF_8));
            sha.update((byte) 0);
            sha.update(source.strip().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sha.digest(), 0, 16);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing", e);
        }
    }

    /** A PNG's size in pixels from its header, or zeros when it cannot be read. */
    private static int[] size(final Path png) {
        try {
            return Mapped.read(png, m -> {
                try (final ImageInputStream in = new MemoryCacheImageInputStream(m.stream())) {
                    final Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
                    if (!readers.hasNext()) {
                        return new int[]{0, 0};
                    }
                    final ImageReader r = readers.next();
                    try {
                        r.setInput(in);
                        return new int[]{r.getWidth(0), r.getHeight(0)};
                    } finally {
                        r.dispose();
                    }
                }
            });
        } catch (final IOException e) {
            return new int[]{0, 0};
        }
    }

    /**
     * {@code mmdc} on this machine: on the PATH, else in the newest nvm Node's folder, else Homebrew's or
     * {@code /usr/local}'s, as an app started from Finder has a short PATH.
     */
    static Optional<Path> findMmdc() {
        final List<Path> candidates = new ArrayList<>(Platform.onPath("mmdc"));
        final Path nvm = Path.of(System.getProperty("user.home"), ".nvm", "versions", "node");
        if (Files.isDirectory(nvm)) {
            try (final Stream<Path> versions = Files.list(nvm)) {
                versions.sorted((a, b) -> Versions.compare(b.getFileName().toString(), a.getFileName().toString()))
                        .forEach(v -> candidates.add(v.resolve("bin").resolve("mmdc")));
            } catch (final IOException e) {
                LOG.log(System.Logger.Level.DEBUG, "cannot list [dir=" + nvm + ']', e);
            }
        }
        candidates.add(Path.of("/opt/homebrew/bin/mmdc"));
        candidates.add(Path.of("/usr/local/bin/mmdc"));
        return candidates.stream().filter(Files::isExecutable).findFirst();
    }

    private static String firstLine(final String text) {
        final String s = text == null ? "" : text.strip();
        for (final String line : s.split("\\R")) {
            if (!line.isBlank()) {
                return Clip.keepStart(line.strip(), MAX_REASON_CHARS);
            }
        }
        return "no reason given";
    }
}
