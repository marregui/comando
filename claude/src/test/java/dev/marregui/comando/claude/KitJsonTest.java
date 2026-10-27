package dev.marregui.comando.claude;

import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.KitRoot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code comando-kit.json} as the kit's installer writes it (schema version 1), and the root it makes. */
class KitJsonTest {

    static final String KIT_JSON = """
            {
              "kit": 1,
              "name": "marregui",
              "brain": "BRAIN",
              "folders": [
                {"name": "Scratchpad", "path": "BRAIN/SCRATCHPAD", "index": "", "excludes": [], "icon": "scratchpad",
                 "deletable": true, "listedIn": "INDEX.md", "repository": false},
                {"name": "Memory", "path": "BRAIN/MEMORY", "index": "MEMORY.md", "excludes": ["old"], "icon": "memory",
                 "deletable": false, "listedIn": "", "repository": false}
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

    private static Path write(Path dir, String text) throws IOException {
        return Files.writeString(Files.createDirectories(dir).resolve(KitRoot.FILE), text);
    }

    @Test
    void theKitsFileReadsWhole(@TempDir Path tmp) throws Exception {
        KitRoot.Manifest kit = KitJson.read(write(tmp, KIT_JSON));

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

    @Test
    void aFileThatBreaksTheSchemaIsRefused(@TempDir Path tmp) {
        assertThatThrownBy(() -> KitJson.read(write(tmp, "{\"kit\": 1, \"name\": \"x\""))).as("cut short").isInstanceOf(IOException.class);
        assertThatThrownBy(() -> KitJson.read(write(tmp, "[]"))).hasMessage("not a JSON object");
        assertThatThrownBy(() -> KitJson.read(write(tmp, KIT_JSON.replace("\"kit\": 1", "\"kit\": 2")))).hasMessageContaining("version 1");
        assertThatThrownBy(() -> KitJson.read(write(tmp, KIT_JSON.replace("\"name\": \"marregui\",", "")))).hasMessage("\"name\" is missing");
        assertThatThrownBy(() -> KitJson.read(write(tmp, KIT_JSON.replace("\"brain\": \"BRAIN\"", "\"brain\": \"/abs\""))))
                .hasMessageContaining("brain is not a folder inside the root");
        assertThatThrownBy(() -> KitJson.read(write(tmp, KIT_JSON.replace("BRAIN/MEMORY", "../MEMORY"))))
                .hasMessageContaining("folder Memory is not a folder inside the root");
        assertThatThrownBy(() -> KitJson.read(write(tmp, KIT_JSON.replace("BRAIN/MEMORY", "~/MEMORY")))).isInstanceOf(IOException.class);
    }

    @Test
    void aMalformedKitJsonLeavesTheRootWithoutAKit(@TempDir Path home) throws Exception {
        Path user = home.resolve(".claude");
        write(user, "{ not json");
        write(user.resolve(KitRoot.CLONE), KIT_JSON);

        KitRoot r = KitRoot.resolve(null, home, KitJson::read);
        assertThat(r.dir()).as("the clone's file reads; the user's own does not").isEqualTo(user.resolve(KitRoot.CLONE));
        assertThat(r.name()).isEqualTo("marregui");
        assertThat(r.memory()).contains(user.resolve(KitRoot.CLONE + "/BRAIN/MEMORY"));

        write(user.resolve(KitRoot.CLONE), "{\"kit\": 1}");
        assertThat(KitRoot.resolve(null, home, KitJson::read)).as("neither reads: no kit, the user's own folder")
                .isEqualTo(new KitRoot(user, Optional.empty(), user));
    }

    @Test
    void comandosOwnConfigDirIsTheRoot(@TempDir Path home) throws Exception {
        Path other = home.resolve("other");
        write(other, KIT_JSON);

        KitRoot r = KitRoot.resolve(other.toString(), home, KitJson::read);
        assertThat(r.dir()).isEqualTo(other);
        assertThat(r.folders()).extracting(FolderRoot::path).containsExactly(other.resolve("BRAIN/SCRATCHPAD").toString(),
                other.resolve("BRAIN/MEMORY").toString());
    }
}
