package dev.marregui.comando.claude;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.KitRoot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [KitJson] reads schema-version-one kit manifests and rejects roots or folders that escape the kit directory.
///
/// - Maps folders, requirements, credentials, and jobs from a complete manifest.
/// - Allows a Files folder at the root but not the brain itself.
/// - Rejects malformed, incomplete, incompatible, and escaping manifests; reads one without a version as version 1.
/// - Leaves out and names a folder that breaks the folders' rules, and repairs the file without it.
/// - Leaves comando without a kit, told why, when the first manifest found does not read; never on the next one.
class KitJsonTest {

    static final String KIT_JSON = """
            {
              "kit": 1,
              "name": "marregui",
              "brain": "BRAIN",
              "folders": [
                {"name": "Scratchpad", "path": "BRAIN/SCRATCHPAD", "index": "", "excludes": [], "icon": "scratchpad",
                 "deletable": true, "listedIn": "INDEX.md"},
                {"name": "Memory", "path": "BRAIN/MEMORY", "index": "MEMORY.md", "excludes": ["old"], "icon": "memory",
                 "deletable": false, "listedIn": ""}
              ],
              "requirements": [
                {"tool": "git", "check": ["git", "--version"],
                 "install": {"macos": ["brew", "install", "git"], "windows": ["winget", "install", "--id", "Git.Git", "-e", "--silent"]}}
              ],
              "credentials": [
                {"key": "LINEAR_API_KEY", "for": "linear-api.sh, /linear", "where": "Linear ▸ Settings ▸ API ▸ Personal API keys"}
              ],
              "jobs": [
                {"name": "transcript-sweep", "schedule": {"daily": "07:15"}, "command": ["bin/transcript-sweep"]}
              ]
            }
            """;

    private static Path write(final Path dir, final String text) throws IOException {
        return Files.writeString(Files.createDirectories(dir).resolve(KitRoot.FILE), text);
    }

    /// The complete version-one fixture maps its name, brain, two folders, platform installs, credential, and scheduled
    /// job, preserving all manifest sections rather than only the root fields.
    @Test
    void theKitsFileReadsWhole(@TempDir final Path tmp) throws Exception {
        final KitRoot.Manifest kit = KitJson.read(write(tmp, KIT_JSON));

        assertThat(kit.name()).isEqualTo("marregui");
        assertThat(kit.brain()).isEqualTo("BRAIN");
        assertThat(kit.folders()).containsExactly(
                new FolderRoot("Scratchpad", "BRAIN/SCRATCHPAD", "", List.of(), "scratchpad", true, "INDEX.md"),
                new FolderRoot("Memory", "BRAIN/MEMORY", "MEMORY.md", List.of("old"), "memory"));
        assertThat(kit.requirements()).containsExactly(new KitRoot.Requirement("git", List.of("git", "--version"),
                Map.of("macos", List.of("brew", "install", "git"), "windows", List.of("winget", "install", "--id", "Git.Git", "-e", "--silent"))));
        assertThat(kit.credentials()).containsExactly(new KitRoot.Credential("LINEAR_API_KEY", "linear-api.sh, /linear",
                "Linear ▸ Settings ▸ API ▸ Personal API keys"));
        assertThat(kit.jobs()).containsExactly(new KitRoot.Job("transcript-sweep", Map.of("daily", "07:15"),
                List.of("bin/transcript-sweep")));
    }

    /// A folder path of `.` resolves to the kit directory. The brain is a folder of its own, anywhere (D10): absolute,
    /// `~/…` or relative to the kit, outside it too, and a manifest that names none reads with an empty brain, the
    /// kit's `BRAIN`.
    @Test
    void aFolderMayBeTheKitsWholeFolderAndTheBrainAnyFolder(@TempDir final Path tmp) throws Exception {
        final KitRoot.Manifest kit = KitJson.read(write(tmp, KIT_JSON.replace("\"BRAIN/MEMORY\"", "\".\"")));
        assertThat(kit.folders()).extracting(FolderRoot::path).containsExactly("BRAIN/SCRATCHPAD", ".");
        assertThat(tmp.resolve(kit.folders().get(1).path()).normalize()).as("the kit's folder itself").isEqualTo(tmp);
        for (final String brain : List.of("/abs/brain", "~/comando-brain", "../comando-brain")) {
            assertThat(KitJson.read(write(tmp, KIT_JSON.replace("\"brain\": \"BRAIN\"", "\"brain\": \"" + brain + '"'))).brain()).isEqualTo(brain);
        }
        assertThat(KitJson.read(write(tmp, KIT_JSON.replace("\"brain\": \"BRAIN\",", ""))).brain()).as("none named").isEmpty();
    }

    /// A folder's `root` is `kit` (the default) or `brain`, its path relative to that root; any other root leaves the
    /// folder out, named in the problems, and the repair takes it out of the file.
    @Test
    void aFolderLiesInTheKitOrInTheBrainAndAnyOtherRootIsAProblem(@TempDir final Path tmp) throws Exception {
        final Path file = write(tmp, """
                {"kit": 1, "name": "k", "brain": "BRAIN", "folders": [
                  {"name": "Memory", "path": "MEMORY", "root": "brain"},
                  {"name": "Kit", "path": ".", "root": "kit"},
                  {"name": "Old", "path": "notes"},
                  {"name": "Odd", "path": "x", "root": "home"}]}""");
        final KitRoot.Manifest kit = KitJson.read(file);
        assertThat(kit.folders()).extracting(FolderRoot::name).containsExactly("Memory", "Kit", "Old");
        assertThat(kit.inBrain()).containsExactly("Memory");
        assertThat(kit.problems()).containsExactly("folders[3] (Odd): \"root\" is \"home\"; it is \"kit\" (the default) or \"brain\"");
        assertThat(KitJson.repair(file)).hasSize(1);
        assertThat(KitJson.read(file).problems()).isEmpty();
        assertThat(KitJson.read(file).folders()).extracting(FolderRoot::name).containsExactly("Memory", "Kit", "Old");
    }

    /// Truncated JSON, a non-object, another schema version, missing name, a folder's absolute or escaping path, and a
    /// folder's tilde path each fail manifest reading rather than constructing a partial kit.
    @Test
    void aFileThatBreaksTheSchemaIsRefused(@TempDir final Path tmp) {
        assertThatThrownBy(() -> KitJson.read(write(tmp, "{\"kit\": 1, \"name\": \"x\""))).as("cut short").isInstanceOf(IOException.class);
        assertThatThrownBy(() -> KitJson.read(write(tmp, "[]"))).hasMessage("not a JSON object");
        assertThatThrownBy(() -> KitJson.read(write(tmp, KIT_JSON.replace("\"kit\": 1", "\"kit\": 2")))).hasMessageContaining("version 1");
        assertThatThrownBy(() -> KitJson.read(write(tmp, KIT_JSON.replace("\"name\": \"marregui\",", "")))).hasMessage("\"name\" is missing");
        assertThatThrownBy(() -> KitJson.read(write(tmp, KIT_JSON.replace("BRAIN/MEMORY", "../MEMORY"))))
                .hasMessageContaining("folder Memory is not a folder inside the root");
        assertThatThrownBy(() -> KitJson.read(write(tmp, KIT_JSON.replace("BRAIN/MEMORY", "~/MEMORY")))).isInstanceOf(IOException.class);
    }

    /// A manifest written without the version key is version 1 (record 133); a later version is refused with the
    /// reason, naming the key and the version this comando reads.
    @Test
    void aManifestWithoutAVersionIsVersionOneAndALaterOneIsRefused(@TempDir final Path tmp) throws Exception {
        assertThat(KitJson.read(write(tmp, KIT_JSON.replace("\"kit\": 1,", ""))).name()).isEqualTo("marregui");
        assertThatThrownBy(() -> KitJson.read(write(tmp, KIT_JSON.replace("\"kit\": 1", "\"kit\": 2"))))
                .hasMessageContaining("\"kit\" is 2").hasMessageContaining("reads version 1").hasMessageContaining("upgrade comando");
        assertThatThrownBy(() -> KitJson.read(write(tmp, KIT_JSON.replace("\"kit\": 1", "\"kit\": \"one\"")))).hasMessageContaining("\"kit\" is");
    }

    /// A folder that breaks the rules every Files-tab folder meets (an icon outside the list, a listing file with a
    /// slash, a name used twice) is left out and named, by its place and name, with what is wrong; the others read.
    @Test
    void aFolderThatBreaksTheFoldersRulesIsLeftOutAndNamed(@TempDir final Path tmp) throws Exception {
        final KitRoot.Manifest kit = KitJson.read(write(tmp, KIT_JSON
                .replace("\"icon\": \"memory\"", "\"icon\": \"brain\"")
                .replace("\"listedIn\": \"INDEX.md\"", "\"listedIn\": \"a/INDEX.md\"")));
        assertThat(kit.folders()).isEmpty();
        assertThat(kit.problems()).containsExactly(
                "folders[0] (Scratchpad): the listing file must be a file name, such as INDEX.md: a/INDEX.md",
                "folders[1] (Memory): the icon must be one of " + String.join(", ", FolderRoot.ICONS) + ": brain");
        final KitRoot.Manifest twice = KitJson.read(write(tmp, KIT_JSON.replace("\"name\": \"Memory\"", "\"name\": \"Scratchpad\"")));
        assertThat(twice.folders()).extracting(FolderRoot::name).containsExactly("Scratchpad");
        assertThat(twice.problems()).containsExactly("folders[1] (Scratchpad): the name is used by another folder");
    }

    /// The repair rewrites the manifest without the folders comando left out, keeping every other entry and key, and
    /// the old file beside it under a name of its own; the repaired file reads with no problem. A file with nothing to
    /// repair is left as it is.
    @Test
    void theRepairRewritesTheManifestWithoutTheEntriesLeftOut(@TempDir final Path tmp) throws Exception {
        final Path file = write(tmp, KIT_JSON.replace("\"icon\": \"memory\"", "\"icon\": \"brain\""));
        final String before = Files.readString(file);
        assertThat(KitJson.repair(file)).containsExactly("folders[1] (Memory): the icon must be one of "
                + String.join(", ", FolderRoot.ICONS) + ": brain");
        assertThat(backups(tmp)).singleElement().satisfies(b -> assertThat(Files.readString(b)).isEqualTo(before));
        final KitRoot.Manifest repaired = KitJson.read(file);
        assertThat(repaired.problems()).isEmpty();
        assertThat(repaired.folders()).extracting(FolderRoot::name).containsExactly("Scratchpad");
        assertThat(repaired.jobs()).hasSize(1);
        final String once = Files.readString(file);
        assertThat(KitJson.repair(file)).as("nothing left to repair").isEmpty();
        assertThat(Files.readString(file)).isEqualTo(once);
    }

    /// A second repair keeps the first's copy: each repair leaves one of its own. A repair confirmed for the problems
    /// shown removes nothing when the file has changed since (the kit's upgrade rewrote it): no entry the user did not
    /// see goes.
    @Test
    void everyRepairKeepsItsOwnCopyAndRemovesOnlyWhatWasConfirmed(@TempDir final Path tmp) throws Exception {
        final Path file = write(tmp, KIT_JSON.replace("\"icon\": \"memory\"", "\"icon\": \"brain\""));
        final List<String> shown = KitJson.read(file).problems();
        KitJson.repair(file, shown);
        Files.writeString(file, KIT_JSON.replace("\"icon\": \"scratchpad\"", "\"icon\": \"nope\""));
        final String changed = Files.readString(file);
        assertThatThrownBy(() -> KitJson.repair(file, shown)).hasMessageContaining("changed since its problems were shown");
        assertThat(Files.readString(file)).as("nothing removed").isEqualTo(changed);
        KitJson.repair(file, KitJson.read(file).problems());
        assertThat(backups(tmp)).as("one copy per repair").hasSize(2);
    }

    private static List<Path> backups(final Path dir) throws IOException {
        try (final var files = Files.list(dir)) {
            return files.filter(f -> f.getFileName().toString().startsWith(KitRoot.FILE + ".bak-")).sorted().toList();
        }
    }

    /// The first folder that holds a manifest is the kit's (D7, D10): a malformed `~/.claude/comando-kit.json`, or one
    /// of a later version, leaves comando without a kit, on `~/.claude`, though `~/.claude/comando-kit` or
    /// `~/comando-kit` holds one that reads; the refusal names the file, why, and the way back.
    @Test
    void aMalformedOrNewerKitJsonLeavesTheRootWithoutAKitNeverOnAnother(@TempDir final Path home) throws Exception {
        final Path user = home.resolve(".claude");
        write(user, "{ not json");
        write(user.resolve(KitRoot.CLONE), KIT_JSON);
        write(home.resolve(KitRoot.CLONE), KIT_JSON);

        final KitRoot r = KitRoot.resolve(null, home, KitJson::read);
        assertThat(new KitRoot(r.dir(), r.kit(), r.userDir())).as("the user's own does not read: no kit, not another's")
                .isEqualTo(new KitRoot(user, Optional.empty(), user));
        assertThat(r.refused()).as("the user's own file is named, with why").contains(".claude/" + KitRoot.FILE + " is not used")
                .endsWith("Correct the file, then restart comando to run on the kit");

        write(user, "{\"kit\": 2, \"name\": \"mine\"}");
        final KitRoot newer = KitRoot.resolve(null, "", home, KitJson::read);
        assertThat(newer.dir()).as("a later version: no kit, not ~/comando-kit").isEqualTo(user);
        assertThat(newer.kit()).isEmpty();
        assertThat(newer.problems()).singleElement().asString().contains(".claude/" + KitRoot.FILE).contains("the kit is newer than comando, upgrade comando");
    }

    /// An explicit configuration directory becomes the resolved root and expands each relative folder path below it.
    @Test
    void comandosOwnConfigDirIsTheRoot(@TempDir final Path home) throws Exception {
        final Path other = home.resolve("other");
        write(other, KIT_JSON);

        final KitRoot r = KitRoot.resolve(other.toString(), home, KitJson::read);
        assertThat(r.dir()).isEqualTo(other);
        assertThat(r.folders()).extracting(FolderRoot::path).containsExactly(other.resolve("BRAIN/SCRATCHPAD").toString(),
                other.resolve("BRAIN/MEMORY").toString());
    }
}
