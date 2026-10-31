package dev.marregui.comando.core.view;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import dev.marregui.comando.core.model.HomeDir;

/**
 * A file shown as a view, read the one way every caller shows a file (the user's click on a link, Claude's
 * {@code ui_open_file}): titled with its parent folder, so two files with one name get two tabs; a Markdown file at no
 * particular line rendered, any other text file as text coloured by its extension at the line; a file that is not
 * UTF-8 text said to be binary rather than shown.
 *
 * @param path   the file, absolute
 * @param title  the view's title: {@code folder/name}
 * @param binary true when the file is not UTF-8 text: the view says so instead of showing it
 */
public record FileView(Path path, String title, ViewSpec spec, boolean binary) {

    /** Files larger than this are not shown. */
    public static final long MAX_BYTES = 4L * 1024 * 1024;

    /**
     * {@code path} as typed ({@code ~/x} under the home folder; relative to {@code baseDir}) as an absolute path.
     */
    public static Path resolve(final String path, final String baseDir) {
        final Path p = Path.of(HomeDir.expand(path));
        return p.isAbsolute() ? p : Path.of(baseDir).resolve(p);
    }

    /**
     * Reads {@code path} as a view at {@code line} (0 for none).
     *
     * @throws IOException whose message says why, without the path, for the caller to put after it: no such file, not
     *                     a file, larger than the limit, or the reason the read failed
     */
    public static FileView read(final Path path, final int line) throws IOException {
        if (!Files.exists(path)) {
            throw new IOException("no such file");
        }
        if (!Files.isRegularFile(path)) {
            throw new IOException("not a file");
        }
        byte[] bytes;
        // the read itself is bounded: a file that grows between a size check and the read is still refused
        try (InputStream in = Files.newInputStream(path)) {
            bytes = in.readNBytes((int) MAX_BYTES + 1);
        } catch (final FileSystemException e) {
            throw new IOException(e.getReason() != null ? e.getReason() : e.getClass().getSimpleName(), e);
        }
        if (bytes.length > MAX_BYTES) {
            throw new IOException("larger than " + MAX_BYTES / (1024 * 1024) + " MB");
        }
        final Path parent = path.getParent() == null ? null : path.getParent().getFileName();
        final String title = (parent == null ? "" : parent + "/") + path.getFileName();
        final String ext = extension(path);
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (final CharacterCodingException e) {
            return new FileView(path, title, new ViewSpec.Text(path.toString(),
                    path.getFileName() + " is a binary file of " + bytes.length + " bytes; only text files are shown.", "", 0), true);
        }
        final ViewSpec spec = (ext.equals("md") || ext.equals("markdown")) && line <= 0
                ? new ViewSpec.Markdown(text) : new ViewSpec.Text(path.toString(), text, ext, line);
        return new FileView(path, title, spec, false);
    }

    /** The extension in lower case, empty when none: the syntax a text view colours by. */
    public static String extension(final Path path) {
        final String n = path.getFileName().toString().toLowerCase(Locale.ROOT);
        final int dot = n.lastIndexOf('.');
        return dot <= 0 ? "" : n.substring(dot + 1);
    }
}
