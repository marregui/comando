package dev.marregui.comando.ui.kit.text;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;

/** The file on this machine a link in a document names: the one reading of a link destination as a path. */
public final class LocalLinks {

    private LocalLinks() {
    }

    /**
     * The file a link destination names, a relative one read from {@code folder}: a path, or a {@code file:} URL
     * naming this machine. Empty for a web address, one naming another machine ({@code file://host/}, scheme-relative),
     * a {@code data:} or {@code mailto:} URI, a heading alone, a folder ({@code x/}), or a relative path with no
     * folder to read it from. A {@code #} or {@code ?} part after the name is dropped.
     */
    public static Optional<Path> file(final Path folder, final String destination) {
        if (destination == null || destination.isBlank()) {
            return Optional.empty();
        }
        final String raw = destination.strip();
        String path;
        try {
            final URI uri = new URI(raw);
            if (uri.getScheme() != null && !uri.getScheme().equalsIgnoreCase("file") || uri.getRawAuthority() != null) {
                return Optional.empty();
            }
            path = uri.getPath();
        } catch (final URISyntaxException e) {
            // a destination in angle brackets keeps its spaces, which a URI does not take
            if (raw.contains("://")) {
                return Optional.empty();
            }
            final int cut = indexOfAny(raw, '#', '?');
            path = cut < 0 ? raw : raw.substring(0, cut);
        }
        if (path == null || path.isEmpty() || path.endsWith("/")) {
            return Optional.empty();
        }
        Path target;
        try {
            target = Path.of(path);
        } catch (final InvalidPathException e) {
            return Optional.empty();
        }
        if (!target.isAbsolute()) {
            if (folder == null) {
                return Optional.empty();
            }
            target = folder.toAbsolutePath().resolve(target);
        }
        final Path file = target.normalize();
        // "/.." is the root, which names no file
        return file.getFileName() == null ? Optional.empty() : Optional.of(file);
    }

    private static int indexOfAny(final String s, final char a, final char b) {
        final int i = s.indexOf(a);
        final int j = s.indexOf(b);
        return i < 0 ? j : j < 0 ? i : Math.min(i, j);
    }
}
