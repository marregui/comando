package dev.marregui.comando.frontear.octopus;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.json.Json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// A link's kept spec: it reads back as written, the same spec gives the same bytes, and a file of another version,
/// with an unknown property, a missing one or a value of the wrong type is refused whole.
class LinkFileTest {

    /// A spec reads back equal, whatever order its filter sets came in.
    @Test
    void aSpecReadsBackAsWrittenAndTheSameSpecGivesTheSameBytes() {
        final LinkSpec spec = new LinkSpec("ab", "127.0.0.1", 1884, Set.of("b/#", "a/+"), Set.of("r/#"), 4);
        final byte[] bytes = LinkFile.write(spec);
        assertThat(LinkFile.read(bytes)).isEqualTo(spec);
        assertThat(LinkFile.write(new LinkSpec("ab", "127.0.0.1", 1884, Set.of("a/+", "b/#"), Set.of("r/#"), 4)))
                .isEqualTo(bytes);
    }

    /// A TLS link keeps `tls` across a restart. The property is written only when true, so a plain link's file is
    /// the one a node from before TLS wrote and reads; a file without it reads as plain, and a `tls` that is not a
    /// boolean is refused.
    @Test
    void tlsIsKeptAndWrittenOnlyForATlsLink() {
        final LinkSpec tls = new LinkSpec("ab", "127.0.0.1", 1884, Set.of("a/+"), Set.of(), 4, true);
        assertThat(LinkFile.read(LinkFile.write(tls))).isEqualTo(tls);
        final LinkSpec plain = new LinkSpec("ab", "127.0.0.1", 1884, Set.of("a/+"), Set.of(), 4);
        assertThat(Json.readTree(LinkFile.write(plain)).has("tls")).isFalse();
        assertThat(Json.readTree(LinkFile.write(tls)).get("tls").asBoolean()).isTrue();
        final String old = "{\"version\":1,\"id\":\"ab\",\"host\":\"h\",\"port\":1,\"out\":[],\"in\":[],\"hopLimit\":1";
        assertThat(LinkFile.read((old + "}").getBytes(StandardCharsets.UTF_8)).tls()).isFalse();
        assertThatThrownBy(() -> LinkFile.read((old + ",\"tls\":\"yes\"}").getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /// Each malformed file is refused: not JSON, not an object, another version, an unknown property, a missing
    /// one, a port that is not an int, a filter that is not a string.
    @Test
    void aFileThatIsNotThisVersionsObjectIsRefused() {
        for (final String text : List.of(
                "not json",
                "[]",
                "{\"version\":2,\"id\":\"ab\",\"host\":\"h\",\"port\":1,\"out\":[],\"in\":[],\"hopLimit\":1}",
                "{\"version\":1,\"id\":\"ab\",\"host\":\"h\",\"port\":1,\"out\":[],\"in\":[],\"hopLimit\":1,\"x\":0}",
                "{\"version\":1,\"id\":\"ab\",\"host\":\"h\",\"port\":1,\"out\":[],\"hopLimit\":1}",
                "{\"version\":1,\"id\":\"ab\",\"host\":\"h\",\"port\":\"1\",\"out\":[],\"in\":[],\"hopLimit\":1}",
                "{\"version\":1,\"id\":\"ab\",\"host\":\"h\",\"port\":1,\"out\":[1],\"in\":[],\"hopLimit\":1}")) {
            assertThatThrownBy(() -> LinkFile.read(text.getBytes(StandardCharsets.UTF_8))).as(text)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
