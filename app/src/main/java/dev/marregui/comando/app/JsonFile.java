package dev.marregui.comando.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import dev.marregui.comando.core.model.AtomicFiles;

/**
 * One store file: a JSON object whose {@code version} names its schema (record 133), and whose list sits under
 * {@link #key}. A file written before versions, a bare JSON array, reads as version 1's list; an object without
 * {@code version} is version 1 too. A file of a later version than this comando reads is refused: kept as it is, never
 * written, and {@link #refused} says why, for the user. A write goes to a temporary file in the same directory and is
 * moved over the old one atomically, so a crash leaves the old or the new content, never a torn file. A file that
 * cannot be read is renamed aside ({@code .corrupt-<time>}) instead of being overwritten by the next save.
 */
final class JsonFile<T> {

    private static final System.Logger LOG = System.getLogger(JsonFile.class.getName());
    static final ObjectMapper JSON = filed().enable(SerializationFeature.INDENT_OUTPUT).build();
    /** The field every store file names its schema's version in. */
    static final String VERSION = "version";

    /**
     * A mapper for files comando wrote with Jackson 2, keeping its defaults where Jackson 3 changed them: properties in
     * declaration order, a null primitive read as its default, enums by name, anything after the value ignored; and
     * unknown properties ignored.
     */
    static JsonMapper.Builder filed() {
        return JsonMapper.builder()
                .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(EnumFeature.READ_ENUMS_USING_TO_STRING, EnumFeature.WRITE_ENUMS_USING_TO_STRING);
    }

    private final Path path;
    private final String key;
    private final int version;
    private final TypeReference<T> type;
    /** Why the file is neither read nor written: a later version; empty while it is this comando's. */
    private volatile String refused = "";
    /** The object last read, for the numbers stored beside the list; empty until a read. */
    private volatile JsonNode lastRead = JSON.createObjectNode();

    /** The file at {@code path}, of schema {@code version}, its list under {@code key}. */
    JsonFile(final Path path, final String key, final int version, final TypeReference<T> type) {
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
            return content(JSON.readTree(path.toFile()));
        } catch (final JacksonException e) {
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
            return content(JSON.readTree(path.toFile()));
        } catch (final LaterVersion e) {
            refused = e.getMessage();
            LOG.log(System.Logger.Level.WARNING, "a store file of a later version, kept and not written [file=" + path + ", " + e.getMessage() + ']');
            return null;
        } catch (final JacksonException | IOException e) {
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

    /** The number stored as {@code field} beside the list in the file last read; 0 when it has none. */
    long number(final String field) {
        return lastRead.path(field).asLong(0L);
    }

    /** The file's list, of version 1 when it is a bare array or names no version. */
    private T content(final JsonNode root) throws IOException {
        if (root == null || root.isMissingNode() || root.isNull()) {
            throw new IOException("empty");
        }
        if (root.isArray()) {
            lastRead = JSON.createObjectNode();
            return JSON.treeToValue(root, JSON.constructType(type));
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
        lastRead = root;
        final JsonNode list = root.path(key);
        return list.isMissingNode() || list.isNull() ? null : JSON.treeToValue(list, JSON.constructType(type));
    }

    void write(final T value) throws IOException {
        write(value, Map.of());
    }

    /**
     * Writes {@code value} as the file's list, after its version and {@code numbers}; refused, the file left as it is,
     * when the file is of a later version.
     */
    void write(final T value, final Map<String, Long> numbers) throws IOException {
        if (!refused.isEmpty()) {
            throw new IOException(refused);
        }
        if (!Files.isDirectory(path.getParent())) {
            Files.createDirectories(path.getParent()); // on a folder that exists it throws and catches inside the JDK
        }
        final ObjectNode root = JSON.createObjectNode();
        root.put(VERSION, version);
        numbers.forEach(root::put);
        root.set(key, JSON.valueToTree(value));
        AtomicFiles.write(path, JSON.writeValueAsBytes(root));
    }

    /** A file of a later version than this comando reads. */
    private static final class LaterVersion extends IOException {
        LaterVersion(final String message) {
            super(message);
        }
    }
}
