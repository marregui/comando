package dev.marregui.comando.core.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// [KitRoot] resolves the configuration root from an explicit directory, an own kit, a cloned kit, or the plain user
/// directory.
///
/// - an explicit directory takes precedence over the user directory
/// - the first folder holding a manifest is the kit's; one that does not read leaves comando without a kit, told why
/// - manifest paths resolve under the selected root
/// - the kit is found in `CLAUDE_CONFIG_DIR`, `~/.claude`, `~/comando-kit`, `~/.claude/comando-kit`, in that order
/// - the brain is `COMANDO_BRAIN`, else the manifest's, else the kit's `BRAIN`, and none without a kit; a folder lies in
///   the kit or the brain
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

    /// The first folder that holds a manifest is the kit's: when the manifest does not read, comando runs with no kit,
    /// on `~/.claude`, and says why and the way back; a readable manifest further down the list (`~/comando-kit`, the
    /// clone) is never taken in its place. A reader that throws anything leaves the root without a kit too.
    @Test
    void aKitJsonThatDoesNotReadLeavesComandoWithoutAKitNeverOnTheNext(@TempDir final Path home) throws Exception {
        final Path user = home.resolve(".claude");
        kitJson(user, "broken");
        kitJson(home.resolve(KitRoot.CLONE), "{}");
        kitJson(user.resolve(KitRoot.CLONE), "{}");

        final KitRoot none = KitRoot.resolve(null, home, READER);
        assertThat(new KitRoot(none.dir(), none.kit(), none.userDir())).as("no kit at all, not the next folder's")
                .isEqualTo(new KitRoot(user, Optional.empty(), user));
        assertThat(none.refused()).as("and the user is told why").startsWith(HomeDir.shorten(user.resolve(KitRoot.FILE).toString()) + " is not used")
                .contains("not JSON").endsWith("Correct the file, then restart comando to run on the kit");
        assertThat(none.problems()).containsExactly(none.refused());

        Files.delete(user.resolve(KitRoot.FILE));
        kitJson(home.resolve(KitRoot.CLONE), "broken");
        final KitRoot clone = KitRoot.resolve(null, home, READER);
        assertThat(clone.kit()).as("~/comando-kit holds one that does not read: not the one made before").isEmpty();
        assertThat(clone.refused()).startsWith(HomeDir.shorten(home.resolve(KitRoot.CLONE).resolve(KitRoot.FILE).toString()));

        assertThat(KitRoot.resolve(null, home, file -> {
            throw new IllegalArgumentException("no name");
        }).kit()).as("a schema broken").isEmpty();
    }

    /// A `comando-kit.json` that is no file (a folder by that name, a link to nothing) is still held: it is refused and
    /// told, not passed over.
    @Test
    void aKitJsonThatIsNoFileIsHeldAndRefused(@TempDir final Path home) throws Exception {
        final Path user = home.resolve(".claude");
        Files.createDirectories(user.resolve(KitRoot.FILE));
        kitJson(home.resolve(KitRoot.CLONE), "{}");
        final KitRoot r = KitRoot.resolve(null, home, file -> {
            Files.readString(file);
            return KIT;
        });
        assertThat(r.kit()).isEmpty();
        assertThat(r.refused()).startsWith(HomeDir.shorten(user.resolve(KitRoot.FILE).toString()) + " is not used");
    }

    /// Without a kit there is no brain: a `COMANDO_BRAIN` comando was started with names none, so neither the brain nor
    /// its memory reaches a session.
    @Test
    void withoutAKitThereIsNoBrainEvenWithComandoBrain(@TempDir final Path home) throws Exception {
        final KitRoot none = KitRoot.resolve(null, "/env/brain", home, READER);
        assertThat(none.kit()).isEmpty();
        assertThat(none.brain()).as("no kit, no brain").isEmpty();
        assertThat(none.memory()).isEmpty();

        kitJson(home.resolve(KitRoot.CLONE), "{}");
        assertThat(KitRoot.resolve(null, "/env/brain", home, READER).brain()).as("with a kit, the variable's").contains(Path.of("/env/brain"));
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

    /// The kit is found by `CLAUDE_CONFIG_DIR`, else `~/.claude` when it holds a manifest, else `~/comando-kit`, where a
    /// new install puts it, else `~/.claude/comando-kit`, for kits made before (D10): each step is taken only when the
    /// steps before find no manifest.
    @Test
    void theKitIsFoundInItsFourPlacesInOrder(@TempDir final Path home) throws Exception {
        final Path user = home.resolve(".claude");
        kitJson(user.resolve(KitRoot.CLONE), "{}");
        assertThat(KitRoot.resolve(null, home, READER).dir()).as("kits made before").isEqualTo(user.resolve(KitRoot.CLONE));
        kitJson(home.resolve(KitRoot.CLONE), "{}");
        assertThat(KitRoot.resolve(null, home, READER).dir()).as("a new install's, over one made before").isEqualTo(home.resolve(KitRoot.CLONE));
        kitJson(user, "{}");
        assertThat(KitRoot.resolve(null, home, READER).dir()).as("~/.claude with a manifest, over both").isEqualTo(user);
        final Path named = home.resolve("kits/mine");
        assertThat(KitRoot.resolve(named.toString(), home, READER).dir()).as("CLAUDE_CONFIG_DIR, over every other").isEqualTo(named);
    }

    /// The brain is `COMANDO_BRAIN` when comando was started with it, over the manifest's; else the manifest's, absolute,
    /// `~/…` or relative to the kit (outside it too); else the kit's `BRAIN`. The memory follows the brain.
    @Test
    void theBrainIsTheVariableElseTheManifestsElseTheKitsBrainFolder(@TempDir final Path home) throws Exception {
        final Path kit = home.resolve(KitRoot.CLONE);
        kitJson(kit, "{}");
        final KitRoot.Reader none = file -> manifest("", Set.of());
        assertThat(KitRoot.resolve(null, home, none).brain()).as("none named: the kit's").contains(kit.resolve("BRAIN"));
        assertThat(KitRoot.resolve(null, home, file -> manifest("/brains/b", Set.of())).brain()).as("absolute").contains(Path.of("/brains/b"));
        assertThat(KitRoot.resolve(null, home, file -> manifest("~/comando-brain", Set.of())).brain()).as("in the home")
                .contains(Path.of(HomeDir.home(), "comando-brain"));
        assertThat(KitRoot.resolve(null, home, file -> manifest("../comando-brain", Set.of())).brain()).as("beside the kit")
                .contains(home.resolve("comando-brain"));
        final KitRoot env = KitRoot.resolve(null, "/env/brain", home, file -> manifest("/brains/b", Set.of()));
        assertThat(env.brain()).as("COMANDO_BRAIN wins").contains(Path.of("/env/brain"));
        assertThat(env.memory()).contains(Path.of("/env/brain/MEMORY"));
        assertThat(KitRoot.resolve(null, " ", home, none).brain()).as("a blank COMANDO_BRAIN is none").contains(kit.resolve("BRAIN"));
        assertThat(KitRoot.resolve(null, "/env/brain", home, READER).brainEnv()).isEqualTo("/env/brain");
    }

    /// A folder whose `root` is the brain lies in the brain the instance resolved, so `COMANDO_BRAIN` moves it; the
    /// others lie in the kit.
    @Test
    void aFolderOfTheBrainMovesWithTheBrain(@TempDir final Path home) throws Exception {
        final Path kit = home.resolve(KitRoot.CLONE);
        kitJson(kit, "{}");
        final KitRoot.Reader reader = file -> manifest("~/comando-brain", Set.of("Memory"));
        final List<FolderRoot> folders = KitRoot.resolve(null, home, reader).folders();
        assertThat(folders).extracting(FolderRoot::path).containsExactly(Path.of(HomeDir.home(), "comando-brain", "MEMORY").toString(),
                kit.resolve("notes").toString());
        assertThat(KitRoot.resolve(null, "/env/brain", home, reader).folders()).extracting(FolderRoot::path)
                .containsExactly("/env/brain/MEMORY", kit.resolve("notes").toString());
    }

    /// A manifest naming `brain` and two folders, `Memory` at `MEMORY` and `Notes` at `notes`, `inBrain` in the brain.
    private static KitRoot.Manifest manifest(final String brain, final Set<String> inBrain) {
        return new KitRoot.Manifest("k", brain, List.of(new FolderRoot("Memory", "MEMORY", "", List.of()), new FolderRoot("Notes", "notes", "", List.of())),
                List.of(), List.of(), List.of(), List.of(), inBrain);
    }
}
