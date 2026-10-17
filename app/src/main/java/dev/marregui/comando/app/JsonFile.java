package dev.marregui.comando.app;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

/**
 * One store file. A write goes to a temporary file in the same directory and is moved over the old one
 * atomically, so a crash leaves the old or the new content, never a torn file. A file that cannot be read
 * is renamed aside ({@code .corrupt-<time>}) instead of being overwritten by the next save.
 */
final class JsonFile<T> {

    private static final System.Logger LOG = System.getLogger(JsonFile.class.getName());
    static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final Path path;
    private final TypeReference<T> type;

    JsonFile(Path path, TypeReference<T> type) {
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
        return exists() ? JSON.readValue(path.toFile(), type) : null;
    }

    /** The content, or null when the file is missing or unreadable (then it is moved aside). */
    T read() {
        if (!exists()) {
            return null;
        }
        try {
            return JSON.readValue(path.toFile(), type);
        } catch (IOException e) {
            Path aside = path.resolveSibling(path.getFileName() + ".corrupt-" + Instant.now().toEpochMilli());
            LOG.log(System.Logger.Level.ERROR, "unreadable store file kept aside [file=" + path + ", aside=" + aside + "]: " + e.getMessage());
            try {
                Files.move(path, aside);
            } catch (IOException moveFailed) {
                LOG.log(System.Logger.Level.ERROR, "cannot move the unreadable file aside [file=" + path + "]: " + moveFailed.getMessage());
            }
            return null;
        }
    }

    void write(T value) throws IOException {
        if (!Files.isDirectory(path.getParent())) {
            Files.createDirectories(path.getParent()); // on a folder that exists it throws and catches inside the JDK
        }
        Path tmp = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".tmp");
        try {
            JSON.writeValue(tmp.toFile(), value);
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
