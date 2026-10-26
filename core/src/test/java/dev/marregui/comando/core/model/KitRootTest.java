package dev.marregui.comando.core.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class KitRootTest {

    private static final KitRoot.Manifest KIT = new KitRoot.Manifest("marregui", "BRAIN",
            List.of(new FolderRoot("Memory", "BRAIN/MEMORY", "MEMORY.md", List.of(), "memory"),
                    new FolderRoot("Scratchpad", "BRAIN/SCRATCHPAD", "", List.of(), "scratchpad", true, "INDEX.md")),
            List.of(), List.of(), List.of());

    /** Reads every {@code kit.json} as {@link #KIT}, but one whose text is {@code broken}. */
    private static final KitRoot.Reader READER = file -> {
        if (Files.readString(file).contains("broken")) {
            throw new IOException("not JSON");
        }
        return KIT;
    };

    private static Path kitJson(Path dir, String text) throws IOException {
        return Files.writeString(Files.createDirectories(dir).resolve(KitRoot.FILE), text);
    }

    @Test
    void comandoStartedWithAConfigDirRunsOnItWithItsKitOrNone(@TempDir Path home) throws Exception {
        Path other = home.resolve("elsewhere");
        kitJson(home.resolve(".claude"), "{}");

        KitRoot bare = KitRoot.resolve(other.toString(), home, READER);
        assertThat(bare.dir()).isEqualTo(other);
        assertThat(bare.kit()).as("the folder named wins over a kit in ~/.claude").isEmpty();
        assertThat(bare.own()).isFalse();

        kitJson(other, "{}");
        KitRoot kit = KitRoot.resolve(other.toString(), home, READER);
        assertThat(kit.kit()).contains(KIT);
        assertThat(kit.userDir()).isEqualTo(home.resolve(".claude"));
    }

    @Test
    void aKitInTheUsersOwnFolderComesFirstThenTheClonedKit(@TempDir Path home) throws Exception {
        Path user = home.resolve(".claude");
        kitJson(user.resolve(KitRoot.CLONE), "{}");

        KitRoot cloned = KitRoot.resolve(null, home, READER);
        assertThat(cloned.dir()).isEqualTo(user.resolve("kit"));
        assertThat(cloned.kit()).contains(KIT);
        assertThat(cloned.own()).isFalse();

        kitJson(user, "{}");
        KitRoot own = KitRoot.resolve(" ", home, READER);
        assertThat(own.dir()).as("a blank CLAUDE_CONFIG_DIR is none").isEqualTo(user);
        assertThat(own.kit()).contains(KIT);
        assertThat(own.own()).isTrue();
    }

    @Test
    void withoutAKitTheRootIsTheUsersOwnFolder(@TempDir Path home) {
        KitRoot none = KitRoot.resolve(null, home, READER);

        assertThat(none).isEqualTo(new KitRoot(home.resolve(".claude"), Optional.empty(), home.resolve(".claude")));
        assertThat(none.own()).isTrue();
        assertThat(none.brain()).isEmpty();
        assertThat(none.memory()).isEmpty();
        assertThat(none.folders()).isEmpty();
        assertThat(none.name()).isEmpty();
    }

    @Test
    void aKitJsonThatDoesNotReadCountsAsNone(@TempDir Path home) throws Exception {
        Path user = home.resolve(".claude");
        kitJson(user, "broken");

        assertThat(KitRoot.resolve(null, home, READER)).as("no kit at all").isEqualTo(new KitRoot(user, Optional.empty(), user));
        kitJson(user.resolve(KitRoot.CLONE), "{}");
        assertThat(KitRoot.resolve(null, home, READER).dir()).as("the clone, whose file reads").isEqualTo(user.resolve("kit"));
        assertThat(KitRoot.resolve(null, home, file -> {
            throw new IllegalArgumentException("no name");
        }).kit()).as("a schema broken").isEmpty();
    }

    @Test
    void theKitsFoldersBrainAndMemoryAreUnderTheRoot(@TempDir Path home) throws Exception {
        Path root = home.resolve(".claude/kit");
        kitJson(root, "{}");
        KitRoot r = KitRoot.resolve(null, home, READER);

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
