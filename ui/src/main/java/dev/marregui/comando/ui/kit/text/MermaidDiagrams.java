package dev.marregui.comando.ui.kit.text;

import dev.marregui.comando.core.view.Clip;
import dev.marregui.comando.ui.kit.desktop.Platform;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Mermaid diagrams drawn by {@code mmdc} (the Mermaid command-line renderer) into a cache folder, once per diagram
 * text, for the Markdown preview: Swing's HTML renderer runs no JavaScript, so it shows the picture {@code mmdc} made.
 * A diagram not drawn yet is drawn on a thread of its own, one at a time; whoever asked hears when it is ready. A
 * diagram {@code mmdc} rejects is remembered with the reason, so it is not drawn again until its text changes.
 * Until {@link #install} runs, and where {@code mmdc} is not installed, no diagram is drawn.
 */
public final class MermaidDiagrams {

    private static final System.Logger LOG = System.getLogger(MermaidDiagrams.class.getName());
    /** Pixels per point: the picture is drawn twice as large and shown at half, sharp on a Retina screen. */
    static final int SCALE = 2;
    /** Part of every cache key: a change of how diagrams are drawn draws them again. */
    private static final String STYLE = "dark|transparent|" + SCALE;
    /** As {@link #STYLE}, for a light preview: Mermaid's default theme, dark lines on the page. */
    private static final String LIGHT_STYLE = "default|transparent|" + SCALE;
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
    private final Map<String, List<Runnable>> waiting = new ConcurrentHashMap<>();
    private final ExecutorService drawer;
    private volatile Process drawing;

    MermaidDiagrams(Path cache, Path mmdc) {
        this(cache, mmdc, TIMEOUT);
    }

    MermaidDiagrams(Path cache, Path mmdc, Duration timeout) {
        this.cache = cache;
        this.mmdc = mmdc;
        this.timeout = timeout;
        this.drawer = cache == null || mmdc == null ? null
                : Executors.newSingleThreadExecutor(r -> Thread.ofPlatform().name("mermaid").daemon(true).unstarted(r));
    }

    /** The diagrams the previews use. */
    public static MermaidDiagrams current() {
        return installed;
    }

    /** Draws diagrams into {@code cache} from now on, with the {@code mmdc} found on this machine, if any. */
    public static void install(Path cache) {
        Optional<Path> found = findMmdc();
        if (found.isEmpty()) {
            LOG.log(System.Logger.Level.INFO, "mmdc not found; Mermaid diagrams show as their source");
        }
        installed.close();
        installed = new MermaidDiagrams(cache, found.orElse(null));
    }

    /** Stops drawing: the diagram being drawn is abandoned and its {@code mmdc} stopped. */
    public static void shutdown() {
        installed.close();
    }

    /**
     * The picture of {@code source}, or why there is none; when it is not drawn yet, drawing starts and
     * {@code whenReady} runs, on the drawing thread, once it is drawn or rejected.
     */
    public Diagram diagram(String source, Runnable whenReady) {
        return diagram(source, false, whenReady);
    }

    /** As {@link #diagram(String, Runnable)}, drawn for a light page or a dark one. */
    public Diagram diagram(String source, boolean light, Runnable whenReady) {
        if (drawer == null) {
            return new Diagram.Unavailable(mmdc == null && cache != null
                    ? "install mermaid-cli (npm install -g @mermaid-js/mermaid-cli) to see this diagram drawn"
                    : "diagrams are not drawn here");
        }
        String key = key(source, light);
        String failed = failures.get(key);
        if (failed != null) {
            return new Diagram.Unavailable(failed);
        }
        Path png = cache.resolve(key + ".png");
        if (Files.isRegularFile(png)) {
            int[] size = sizes.computeIfAbsent(key, k -> size(png));
            if (size[0] > 0) {
                return new Diagram.Ready(png, Math.max(1, size[0] / SCALE), Math.max(1, size[1] / SCALE));
            }
            // drawn, but not a picture: shown as a failure, not drawn and announced again and again
            return new Diagram.Unavailable(unreadable(key, png));
        }
        boolean[] first = new boolean[1];
        waiting.compute(key, (k, list) -> {
            List<Runnable> l = list == null ? new ArrayList<>() : list;
            first[0] = list == null;
            l.add(whenReady);
            return l;
        });
        if (first[0]) {
            try {
                drawer.execute(() -> draw(key, source, light, png));
            } catch (RejectedExecutionException e) {
                waiting.remove(key);
                return new Diagram.Unavailable("comando is closing");
            }
        }
        return new Diagram.Pending();
    }

    private void draw(String key, String source, boolean light, Path png) {
        if (Files.isRegularFile(png)) {
            // asked again while the last drawing of it finished: it is there
            finish(key);
            return;
        }
        Process p = null;
        try {
            Files.createDirectories(cache);
            Path in = Files.createTempFile(cache, "." + key, ".mmd");
            Path out = cache.resolve("." + key + ".tmp.png");
            Path err = Files.createTempFile(cache, "." + key, ".err");
            try {
                Files.writeString(in, source, StandardCharsets.UTF_8);
                ProcessBuilder pb = new ProcessBuilder(mmdc.toString(), "-q", "-i", in.toString(), "-o", out.toString(),
                        "-t", light ? "default" : "dark", "-b", "transparent", "-s", Integer.toString(SCALE))
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(err.toFile());
                // mmdc is a node script: an app started from Finder has no node on its PATH, so its own folder goes first
                pb.environment().put("PATH", mmdc.getParent() + File.pathSeparator + System.getenv().getOrDefault("PATH", "/usr/bin:/bin"));
                p = pb.start();
                drawing = p;
                p.getOutputStream().close();
                if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    // mmdc drives a headless Chromium; stopping node alone would leave the browser running
                    stop(p);
                    failures.put(key, "mmdc did not finish within " + timeout.toSeconds() + " s");
                } else if (p.exitValue() != 0 || !Files.isRegularFile(out)) {
                    failures.put(key, "mmdc cannot draw it: " + firstLine(Files.readString(err, StandardCharsets.UTF_8)));
                } else {
                    try {
                        Files.move(out, png, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException e) {
                        Files.move(out, png, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            } finally {
                drawing = null;
                Files.deleteIfExists(in);
                Files.deleteIfExists(out);
                Files.deleteIfExists(err);
            }
        } catch (IOException | RuntimeException e) {
            // any failure is the diagram's, told once: a key left waiting would show as drawing forever
            failures.put(key, "cannot draw it: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (p != null) {
                // close interrupted the wait before it could see this process: it would outlive comando
                stop(p);
            }
            waiting.remove(key);
            return;
        }
        String failed = failures.get(key);
        if (failed != null) {
            LOG.log(System.Logger.Level.WARNING, "Mermaid diagram not drawn [key=" + key + "]: " + failed);
        }
        finish(key);
    }

    /** Records that the cached picture of {@code key} cannot be read and removes it; returns why. */
    private String unreadable(String key, Path png) {
        String why = "mmdc drew a picture that cannot be read";
        failures.put(key, why);
        sizes.remove(key);
        try {
            Files.deleteIfExists(png);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "cannot remove " + png + ": " + e.getMessage());
        }
        LOG.log(System.Logger.Level.WARNING, "Mermaid diagram not shown [key=" + key + "]: " + why);
        return why;
    }

    /** Tells whoever waits for {@code key} that it is drawn or rejected. */
    private void finish(String key) {
        List<Runnable> callbacks = waiting.remove(key);
        if (callbacks != null) {
            callbacks.forEach(Runnable::run);
        }
    }

    void close() {
        if (drawer != null) {
            drawer.shutdownNow();
            Process p = drawing;
            if (p != null) {
                stop(p);
            }
        }
    }

    /** Stops {@code p} and every process it started; the children first, as they leave its tree when it ends. */
    private static void stop(Process p) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
    }

    /** The cache file name of a diagram: its text and how it is drawn, hashed. */
    static String key(String source) {
        return key(source, false);
    }

    static String key(String source, boolean light) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update((light ? LIGHT_STYLE : STYLE).getBytes(StandardCharsets.UTF_8));
            sha.update((byte) 0);
            sha.update(source.strip().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sha.digest(), 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing", e);
        }
    }

    /** A PNG's size in pixels from its header, or zeros when it cannot be read. */
    private static int[] size(Path png) {
        try (ImageInputStream in = ImageIO.createImageInputStream(png.toFile())) {
            Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
            if (readers == null || !readers.hasNext()) {
                return new int[]{0, 0};
            }
            ImageReader r = readers.next();
            try {
                r.setInput(in);
                return new int[]{r.getWidth(0), r.getHeight(0)};
            } finally {
                r.dispose();
            }
        } catch (IOException e) {
            return new int[]{0, 0};
        }
    }

    /**
     * {@code mmdc} on this machine: on the PATH, else in the newest nvm Node's folder, else Homebrew's or
     * {@code /usr/local}'s, as an app started from Finder has a short PATH.
     */
    static Optional<Path> findMmdc() {
        List<Path> candidates = new ArrayList<>(Platform.onPath("mmdc"));
        Path nvm = Path.of(System.getProperty("user.home"), ".nvm", "versions", "node");
        if (Files.isDirectory(nvm)) {
            try (Stream<Path> versions = Files.list(nvm)) {
                versions.sorted((a, b) -> compareVersions(b.getFileName().toString(), a.getFileName().toString()))
                        .forEach(v -> candidates.add(v.resolve("bin").resolve("mmdc")));
            } catch (IOException e) {
                LOG.log(System.Logger.Level.DEBUG, "cannot list " + nvm + ": " + e.getMessage());
            }
        }
        candidates.add(Path.of("/opt/homebrew/bin/mmdc"));
        candidates.add(Path.of("/usr/local/bin/mmdc"));
        return candidates.stream().filter(Files::isExecutable).findFirst();
    }

    /** Compares "v22.20.0" and "v20.9.1" by their numbers. */
    static int compareVersions(String a, String b) {
        String[] x = a.replaceFirst("^v", "").split("\\.");
        String[] y = b.replaceFirst("^v", "").split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int c = Integer.compare(number(x, i), number(y, i));
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    private static int number(String[] parts, int i) {
        try {
            return i < parts.length ? Integer.parseInt(parts[i]) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String firstLine(String text) {
        String s = text == null ? "" : text.strip();
        for (String line : s.split("\\R")) {
            if (!line.isBlank()) {
                return Clip.keepStart(line.strip(), MAX_REASON_CHARS);
            }
        }
        return "no reason given";
    }
}
