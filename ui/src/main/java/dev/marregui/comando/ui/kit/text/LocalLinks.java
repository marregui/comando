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
    public static Optional<Path> file(Path folder, String destination) {
        if (destination == null || destination.isBlank()) {
            return Optional.empty();
        }
        String raw = destination.strip();
        String path;
        try {
            URI uri = new URI(raw);
            if (uri.getScheme() != null && !uri.getScheme().equalsIgnoreCase("file") || uri.getRawAuthority() != null) {
                return Optional.empty();
            }
            path = uri.getPath();
        } catch (URISyntaxException e) {
            // a destination in angle brackets keeps its spaces, which a URI does not take
            if (raw.contains("://")) {
                return Optional.empty();
            }
            int cut = indexOfAny(raw, '#', '?');
            path = cut < 0 ? raw : raw.substring(0, cut);
        }
        if (path == null || path.isEmpty() || path.endsWith("/")) {
            return Optional.empty();
        }
        Path target;
        try {
            target = Path.of(path);
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
        if (!target.isAbsolute()) {
            if (folder == null) {
                return Optional.empty();
            }
            target = folder.toAbsolutePath().resolve(target);
        }
        Path file = target.normalize();
        // "/.." is the root, which names no file
        return file.getFileName() == null ? Optional.empty() : Optional.of(file);
    }

    private static int indexOfAny(String s, char a, char b) {
        int i = s.indexOf(a);
        int j = s.indexOf(b);
        return i < 0 ? j : j < 0 ? i : Math.min(i, j);
    }
}
