package dev.marregui.comando.ui.kit.vcs;

import dev.marregui.comando.ui.kit.async.UiErrands;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * The files the user opened in comando, by absolute path: a new file opened once is no longer marked new in the Files
 * tab. Kept in a file of one path per line, oldest first, at most {@link #MAX} paths; read once when made, written
 * whole after each path added, off the EDT, one write at a time. Used on the EDT.
 */
public final class OpenedFiles {

    /** The paths kept; past it the oldest are dropped. */
    public static final int MAX = 20_000;
    private static final System.Logger LOG = System.getLogger(OpenedFiles.class.getName());
    private static final Executor WRITER = task -> UiErrands.start("opened-files", task);

    /** Where the paths are kept; null keeps them in memory only. */
    private final Path file;
    private final LinkedHashSet<Path> paths = new LinkedHashSet<>();
    private final List<Runnable> listeners = new ArrayList<>();
    private CompletableFuture<Void> written = CompletableFuture.completedFuture(null);

    private OpenedFiles(Path file) {
        this.file = file;
    }

    /** The paths kept in {@code file}; none when it is missing or cannot be read, which the log says. */
    public static OpenedFiles load(Path file) {
        OpenedFiles o = new OpenedFiles(file);
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    o.keep(Path.of(line));
                }
            }
        } catch (NoSuchFileException e) {
            // nothing opened yet
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot read the opened files " + file + ": " + e.getMessage());
        }
        return o;
    }

    /** Paths kept in memory only, for tests and windows without a home. */
    public static OpenedFiles inMemory() {
        return new OpenedFiles(null);
    }

    /** Whether the file at {@code path} was opened. */
    public boolean contains(Path path) {
        return paths.contains(path.toAbsolutePath().normalize());
    }

    /** Records that the file at {@code path} was opened; listeners hear of it and the file is written when it is new. */
    public void add(Path path) {
        if (!keep(path)) {
            return;
        }
        for (Runnable l : List.copyOf(listeners)) {
            l.run();
        }
        if (file != null) {
            List<String> lines = paths.stream().map(Path::toString).toList();
            written = written.thenRunAsync(() -> write(lines), WRITER);
        }
    }

    /** Calls {@code listener} after each path added; the returned action removes it. */
    public Runnable listen(Runnable listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** Completes when every write asked for so far has ended. */
    public CompletableFuture<Void> written() {
        return written;
    }

    private boolean keep(Path path) {
        if (!paths.add(path.toAbsolutePath().normalize())) {
            return false;
        }
        while (paths.size() > MAX) {
            paths.removeFirst();
        }
        return true;
    }

    private void write(List<String> lines) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, lines, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot write the opened files " + file + ": " + e.getMessage());
        }
    }
}
