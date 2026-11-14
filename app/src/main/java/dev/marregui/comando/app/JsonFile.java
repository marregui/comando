package dev.marregui.comando.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Arrays;

import dev.marregui.comando.core.files.Mapped;
import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.JsonException;
import dev.marregui.comando.core.json.JsonNode;
import dev.marregui.comando.core.json.ObjectNode;
import dev.marregui.comando.core.json.bind.Binder;
import dev.marregui.comando.core.json.bind.TypeRef;
import dev.marregui.comando.core.model.AtomicFiles;

/**
 * One store file: a JSON object whose {@code version} names its schema (record 133), and whose list sits under
 * {@link #key}. A file written before versions, a bare JSON array, reads as version 1's list; an object without
 * {@code version} is version 1 too. A file of a later version than this comando reads is refused: kept as it is, never
 * written, and {@link #refused} says why, for the user. A write goes to a temporary file in the same directory and is
 * moved over the old one atomically, so a crash leaves the old or the new content, never a torn file. A write of the
 * bytes this run last wrote, to a file whose modification time and size are still those that write left, writes
 * nothing. A file that cannot be read is renamed aside ({@code .corrupt-<time>}) instead of being overwritten by the
 * next save.
 */
final class JsonFile<T> {

    private static final System.Logger LOG = System.getLogger(JsonFile.class.getName());
    /**
     * The mapping of every file comando keeps, the format its files have always had: records by their accessors, in
     * declaration order, a null primitive read as its default, enums by name, unknown properties ignored; a stored
     * panel leaves its null components out.
     */
    static final Binder BINDER = Binder.builder().omitNulls(Store.StoredPanel.class).build();
    /** The field every store file names its schema's version in. */
    static final String VERSION = "version";

    private final Path path;
    private final String key;
    private final int version;
    private final TypeRef<T> type;
    /** Why the file is neither read nor written: a later version; empty while it is this comando's. */
    private volatile String refused = "";
    /**
     * The bytes this run last wrote and the file's stamp right after; null until a write. Written and read by the thread
     * that writes this file ({@code Store}'s writer, its secrets writer for {@code connections.json}); volatile so a write
     * handed to another of them still sees the last one.
     */
    private volatile Written lastWritten;

    /** The file at {@code path}, of schema {@code version}, its list under {@code key}. */
    JsonFile(final Path path, final String key, final int version, final TypeRef<T> type) {
        this.path = path;
        this.key = key;
        this.version = version;
        this.type = type;
    }

    Path path() {
        return path;
    }

    boolean exists() {
        return Files.isRegularFile(path);
    }

    /**
     * Why the file is kept as it is and not written: it is of a later version than this comando reads; empty when it is
     * not.
     */
    String refused() {
        return refused;
    }

    /**
     * Keeps the file as it is for the rest of this run, {@code why} saying so: a file of this version that depends on
     * another one refused (a panel's binding to a session of a later {@code sessions.json}) is not written either.
     */
    void refuse(final String why) {
        if (refused.isEmpty()) {
            refused = why;
        }
    }

    /** The content as it is now, or null when the file is missing; an unreadable or later file stays where it is. */
    T readAsIs() throws IOException {
        if (!exists()) {
            return null;
        }
        try {
            return content(Mapped.read(path, m -> Json.readFirst(m.stream())));
        } catch (final JsonException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * The content, or null when the file is missing, unreadable (then it is moved aside), or of a later version (then it
     * stays, and {@link #refused} says so).
     */
    T read() {
        if (!exists()) {
            return null;
        }
        try {
            return content(Mapped.read(path, m -> Json.readFirst(m.stream())));
        } catch (final LaterVersion e) {
            refused = e.getMessage();
            LOG.log(System.Logger.Level.WARNING, "a store file of a later version, kept and not written [file=" + path + ", " + e.getMessage() + ']');
            return null;
        } catch (final JsonException | IOException e) {
            final Path aside = path.resolveSibling(path.getFileName() + ".corrupt-" + Instant.now().toEpochMilli());
            LOG.log(System.Logger.Level.ERROR, "unreadable store file kept aside [file=" + path + ", aside=" + aside + ']', e);
            try {
                Files.move(path, aside);
            } catch (final IOException moveFailed) {
                LOG.log(System.Logger.Level.ERROR, "cannot move the unreadable file aside [file=" + path + ']', moveFailed);
            }
            return null;
        }
    }

    /** The file's list, of version 1 when it is a bare array or names no version. */
    private T content(final JsonNode root) throws IOException {
        if (root == null || root.isMissingNode() || root.isNull()) {
            throw new IOException("empty");
        }
        if (root.isArray()) {
            return bind(root);
        }
        if (!root.isObject()) {
            throw new IOException("neither a JSON object nor a list");
        }
        final JsonNode v = root.path(VERSION);
        final int read = v.isMissingNode() ? 1 : v.asInt(-1);
        if (read < 1) {
            throw new IOException("\"version\" is " + v + ", not a schema version");
        }
        if (read > version) {
            throw new LaterVersion(path.getFileName() + " is of version " + read + ", written by a later comando; this one reads version " + version
                    + ". It is kept as it is and nothing is saved to it until a comando that reads it runs");
        }
        final JsonNode list = root.path(key);
        return list.isMissingNode() || list.isNull() ? null : bind(list);
    }

    /** {@code node} as the file's list type. */
    @SuppressWarnings("unchecked") // the binder builds a value of the type the reference captured, which is T's
    private T bind(final JsonNode node) {
        return (T) BINDER.fromTree(node, type.type());
    }

    /** The UTF-8 bytes of {@code node} as comando writes its files: indented two spaces, no line feed at the end. */
    static byte[] pretty(final JsonNode node) {
        return Json.prettyBytes(node);
    }

    /**
     * Writes {@code value} as the file's list, after its version; refused, the file left as it is, when the file is of
     * a later version.
     */
    void write(final T value) throws IOException {
        if (!refused.isEmpty()) {
            throw new IOException(refused);
        }
        if (!Files.isDirectory(path.getParent())) {
            Files.createDirectories(path.getParent()); // on a folder that exists it throws and catches inside the JDK
        }
        final ObjectNode root = Json.object();
        root.put(VERSION, version);
        root.set(key, BINDER.toTree(value));
        final byte[] bytes = pretty(root);
        final Written last = lastWritten;
        if (last != null && Arrays.equals(last.bytes(), bytes) && last.stamp().equals(stamp())) {
            return; // the file holds these bytes already: rewriting it the same churns the disk and its watchers
        }
        AtomicFiles.write(path, bytes);
        final Stamp after = stamp();
        lastWritten = after == Stamp.NONE ? null : new Written(bytes, after);
    }

    /** The file's modification time and size, or {@link Stamp#NONE} when it cannot be read. */
    private Stamp stamp() {
        try {
            final BasicFileAttributes a = Files.readAttributes(path, BasicFileAttributes.class);
            return new Stamp(a.lastModifiedTime(), a.size());
        } catch (final IOException e) {
            return Stamp.NONE;
        }
    }

    /** A file's modification time and size, which an outside write changes. */
    private record Stamp(FileTime modified, long size) {
        static final Stamp NONE = new Stamp(FileTime.fromMillis(0L), -1L);
    }

    /** What a write left: its bytes and the file's stamp right after. */
    private record Written(byte[] bytes, Stamp stamp) {
    }

    /** A file of a later version than this comando reads. */
    private static final class LaterVersion extends IOException {
        LaterVersion(final String message) {
            super(message);
        }
    }
}
