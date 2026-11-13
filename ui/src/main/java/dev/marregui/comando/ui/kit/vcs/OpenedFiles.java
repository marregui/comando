package dev.marregui.comando.ui.kit.vcs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import dev.marregui.comando.core.model.AtomicFiles;
import dev.marregui.comando.core.model.VersionedLines;
import dev.marregui.comando.ui.kit.async.UiErrands;

/**
 * The files the user opened in comando, by absolute path: a new file opened once is no longer marked new in the folder
 * tabs. Kept in a file of one path per line, oldest first, at most {@link #MAX} paths, after its version line (record
 * 133); read once when made, written whole after each path added, off the EDT, one write at a time. A file of a later
 * version is kept as it is and not written. Used on the EDT.
 */
public final class OpenedFiles {

    /** The paths kept; past it the oldest are dropped. */
    public static final int MAX = 20_000;
    private static final System.Logger LOG = System.getLogger(OpenedFiles.class.getName());
    private static final Executor WRITER = task -> UiErrands.start("opened-files", task);
    /** The file's format and version (record 133). */
    static final String FORMAT = "opened-files";
    static final int VERSION = 1;

    /** Where the paths are kept; null keeps them in memory only. */
    private final Path file;
    private final LinkedHashSet<Path> paths = new LinkedHashSet<>();
    /** Whether the file is of a later version: kept as it is, never written. */
    private boolean refused;
    /** Why the file is not read, in the words the user is told once (record 133); empty when it read. */
    private String refusal = "";
    private final List<Runnable> listeners = new ArrayList<>();
    private CompletableFuture<Void> written = CompletableFuture.completedFuture(null);

    private OpenedFiles(final Path file) {
        this.file = file;
    }

    /** The paths kept in {@code file}; none when it is missing or cannot be read, which the log says. */
    public static OpenedFiles load(final Path file) {
        final OpenedFiles o = new OpenedFiles(file);
        try {
            final VersionedLines.Read read = VersionedLines.read(file, VERSION);
            if (!read.refused().isEmpty()) {
                o.refused = true;
                o.refusal = read.refused();
                LOG.log(System.Logger.Level.WARNING, read.refused() + " [file=" + file + ']');
            }
            for (final String line : read.lines()) {
                o.keep(Path.of(line));
            }
        } catch (final NoSuchFileException e) {
            // nothing opened yet
        } catch (final IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot read the opened files [file=" + file + ']', e);
        }
        return o;
    }

    /** Why the file was not read, for the user, once (record 133); empty when it read. */
    public String refusal() {
        return refusal;
    }

    /** Paths kept in memory only, for tests and windows without a home. */
    public static OpenedFiles inMemory() {
        return new OpenedFiles(null);
    }

    /** Whether the file at {@code path} was opened. */
    public boolean contains(final Path path) {
        return paths.contains(path.toAbsolutePath().normalize());
    }

    /**
     * Records that the file at {@code path} was opened; when it is new, listeners hear of it and the file is written.
     */
    public void add(final Path path) {
        if (!keep(path)) {
            return;
        }
        for (final Runnable l : List.copyOf(listeners)) {
            l.run();
        }
        if (file != null && !refused) {
            final List<String> lines = paths.stream().map(Path::toString).toList();
            written = written.thenRunAsync(() -> write(lines), WRITER);
        }
    }

    /** Calls {@code listener} after each path added; the returned action removes it. */
    public Runnable listen(final Runnable listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** Completes when every write asked for so far has ended. */
    public CompletableFuture<Void> written() {
        return written;
    }

    private boolean keep(final Path path) {
        if (!paths.add(path.toAbsolutePath().normalize())) {
            return false;
        }
        while (paths.size() > MAX) {
            paths.removeFirst();
        }
        return true;
    }

    private void write(final List<String> lines) {
        try {
            Files.createDirectories(file.getParent());
            AtomicFiles.writeString(file, VersionedLines.text(FORMAT, VERSION, lines));
        } catch (final IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot write the opened files [file=" + file + ']', e);
        }
    }
}
