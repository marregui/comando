package dev.marregui.comando.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.core.type.TypeReference;

import static org.assertj.core.api.Assertions.assertThat;

/// [JsonFile] writes a store file only when it would change: the same content again leaves the file as it is, unless
/// something outside changed or removed it since.
class JsonFileTest {

    @TempDir
    Path dir;

    /// A second write of the same list leaves the file in place (same file key); a different list replaces it.
    @Test
    void theSameContentAgainLeavesTheFileAsItIs() throws IOException {
        final JsonFile<List<String>> f = file();
        f.write(List.of("a", "b"));
        final Object first = key(f);
        f.write(List.of("a", "b"));
        assertThat(key(f)).as("not written again").isEqualTo(first);
        f.write(List.of("a", "b", "c"));
        assertThat(key(f)).as("other content is written").isNotEqualTo(first);
        assertThat(f.read()).containsExactly("a", "b", "c");
    }

    /// A file another program rewrote since the last write is written again, though the list is the same.
    @Test
    void aFileChangedOutsideIsWrittenAgainWithTheSameContent() throws IOException {
        final JsonFile<List<String>> f = file();
        f.write(List.of("a"));
        Files.writeString(f.path(), "{\"version\":1,\"names\":[\"outside\"]}");
        f.write(List.of("a"));
        assertThat(f.read()).containsExactly("a");
    }

    /// A file removed since the last write is written again, though the list is the same.
    @Test
    void aFileRemovedOutsideIsWrittenAgainWithTheSameContent() throws IOException {
        final JsonFile<List<String>> f = file();
        f.write(List.of("a"));
        Files.delete(f.path());
        f.write(List.of("a"));
        assertThat(f.read()).containsExactly("a");
    }

    private JsonFile<List<String>> file() {
        return new JsonFile<>(dir.resolve("names.json"), "names", 1, new TypeReference<>() {
        });
    }

    /// Every write replaces the file by a move, so a new file key means the file was written again.
    private Object key(final JsonFile<?> f) throws IOException {
        return Files.readAttributes(f.path(), BasicFileAttributes.class).fileKey();
    }
}
