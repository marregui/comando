package dev.marregui.comando.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

import dev.marregui.comando.core.model.AtomicFiles;

/**
 * One store file. A write goes to a temporary file in the same directory and is moved over the old one
 * atomically, so a crash leaves the old or the new content, never a torn file. A file that cannot be read
 * is renamed aside ({@code .corrupt-<time>}) instead of being overwritten by the next save.
 */
final class JsonFile<T> {

    private static final System.Logger LOG = System.getLogger(JsonFile.class.getName());
    static final ObjectMapper JSON = filed().enable(SerializationFeature.INDENT_OUTPUT).build();

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
    private final TypeReference<T> type;

    JsonFile(final Path path, final TypeReference<T> type) {
        this.path = path;
        this.type = type;
    }

    Path path() {
        return path;
    }

    boolean exists() {
        return Files.isRegularFile(path);
    }

    /** The content as it is now, or null when the file is missing; an unreadable file stays where it is. */
    T readAsIs() throws IOException {
        try {
            return exists() ? JSON.readValue(path.toFile(), type) : null;
        } catch (final JacksonException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** The content, or null when the file is missing or unreadable (then it is moved aside). */
    T read() {
        if (!exists()) {
            return null;
        }
        try {
            return JSON.readValue(path.toFile(), type);
        } catch (final JacksonException e) {
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

    void write(final T value) throws IOException {
        if (!Files.isDirectory(path.getParent())) {
            Files.createDirectories(path.getParent()); // on a folder that exists it throws and catches inside the JDK
        }
        AtomicFiles.write(path, JSON.writeValueAsBytes(value));
    }
}
