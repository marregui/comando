package dev.marregui.comando.core.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// [KitRoot] resolves the configuration root from an explicit directory, an own kit, a cloned kit, or the plain user
/// directory.
///
/// - an explicit directory takes precedence over the user directory
/// - an unreadable manifest counts as no kit
/// - manifest paths resolve under the selected root
class KitRootTest {

    private static final KitRoot.Manifest KIT = new KitRoot.Manifest("marregui", "BRAIN",
            List.of(new FolderRoot("Memory", "BRAIN/MEMORY", "MEMORY.md", List.of(), "memory"),
                    new FolderRoot("Scratchpad", "BRAIN/SCRATCHPAD", "", List.of(), "scratchpad", true, "INDEX.md")),
            List.of(), List.of(), List.of());

    /** Reads every {@code comando-kit.json} as {@link #KIT}, but one whose text is {@code broken}. */
    private static final KitRoot.Reader READER = file -> {
        if (Files.readString(file).contains("broken")) {
            throw new IOException("not JSON");
        }
        return KIT;
    };

    private static Path kitJson(final Path dir, final String text) throws IOException {
        return Files.writeString(Files.createDirectories(dir).resolve(KitRoot.FILE), text);
    }

    /// A non-blank explicit configuration directory wins over the user directory and loads its manifest only when its
    /// own manifest exists.
    @Test
    void comandoStartedWithAConfigDirRunsOnItWithItsKitOrNone(@TempDir final Path home) throws Exception {
        final Path other = home.resolve("elsewhere");
        kitJson(home.resolve(".claude"), "{}");

        final KitRoot bare = KitRoot.resolve(other.toString(), home, READER);
        assertThat(bare.dir()).isEqualTo(other);
        assertThat(bare.kit()).as("the folder named wins over a kit in ~/.claude").isEmpty();
        assertThat(bare.own()).isFalse();

        kitJson(other, "{}");
        final KitRoot kit = KitRoot.resolve(other.toString(), home, READER);
        assertThat(kit.kit()).contains(KIT);
        assertThat(kit.userDir()).isEqualTo(home.resolve(".claude"));
    }

    /// Resolution chooses the cloned kit when the own directory lacks one, then chooses the own kit when both manifests
    /// exist.
    @Test
    void aKitInTheUsersOwnFolderComesFirstThenTheClonedKit(@TempDir final Path home) throws Exception {
        final Path user = home.resolve(".claude");
        kitJson(user.resolve(KitRoot.CLONE), "{}");

        final KitRoot cloned = KitRoot.resolve(null, home, READER);
        assertThat(cloned.dir()).isEqualTo(user.resolve("comando-kit"));
        assertThat(cloned.kit()).contains(KIT);
        assertThat(cloned.own()).isFalse();

        kitJson(user, "{}");
        final KitRoot own = KitRoot.resolve(" ", home, READER);
        assertThat(own.dir()).as("a blank CLAUDE_CONFIG_DIR is none").isEqualTo(user);
        assertThat(own.kit()).contains(KIT);
        assertThat(own.own()).isTrue();
    }

    /// Without a readable manifest, resolution returns the user's `.claude` directory with empty kit-derived values.
    @Test
    void withoutAKitTheRootIsTheUsersOwnFolder(@TempDir final Path home) {
        final KitRoot none = KitRoot.resolve(null, home, READER);

        assertThat(none).isEqualTo(new KitRoot(home.resolve(".claude"), Optional.empty(), home.resolve(".claude")));
        assertThat(none.own()).isTrue();
        assertThat(none.brain()).isEmpty();
        assertThat(none.memory()).isEmpty();
        assertThat(none.folders()).isEmpty();
        assertThat(none.name()).isEmpty();
    }

    /// A malformed own manifest is skipped in favour of a readable clone, while reader failures leave the selected root
    /// without a kit.
    @Test
    void aKitJsonThatDoesNotReadCountsAsNone(@TempDir final Path home) throws Exception {
        final Path user = home.resolve(".claude");
        kitJson(user, "broken");

        assertThat(KitRoot.resolve(null, home, READER)).as("no kit at all").isEqualTo(new KitRoot(user, Optional.empty(), user));
        kitJson(user.resolve(KitRoot.CLONE), "{}");
        assertThat(KitRoot.resolve(null, home, READER).dir()).as("the clone, whose file reads").isEqualTo(user.resolve("comando-kit"));
        assertThat(KitRoot.resolve(null, home, file -> {
            throw new IllegalArgumentException("no name");
        }).kit()).as("a schema broken").isEmpty();
    }

    /// A selected manifest maps its name, brain, memory, projects, and folder paths beneath the kit root only.
    @Test
    void theKitsFoldersBrainAndMemoryAreUnderTheRoot(@TempDir final Path home) throws Exception {
        final Path root = home.resolve(".claude/comando-kit");
        kitJson(root, "{}");
        final KitRoot r = KitRoot.resolve(null, home, READER);

        assertThat(r.name()).isEqualTo("marregui");
        assertThat(r.brain()).contains(root.resolve("BRAIN"));
        assertThat(r.memory()).contains(root.resolve("BRAIN/MEMORY"));
        assertThat(r.projects()).isEqualTo(root.resolve("projects"));
        assertThat(r.folders()).containsExactly(
                new FolderRoot("Memory", root.resolve("BRAIN/MEMORY").toString(), "MEMORY.md", List.of(), "memory"),
                new FolderRoot("Scratchpad", root.resolve("BRAIN/SCRATCHPAD").toString(), "", List.of(), "scratchpad", true, "INDEX.md"));
        assertThat(r.isRoot(root + "/")).isTrue();
        assertThat(r.isRoot(root.resolve("BRAIN").toString())).isFalse();
    }
}
