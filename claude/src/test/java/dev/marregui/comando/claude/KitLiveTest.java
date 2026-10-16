package dev.marregui.comando.claude;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The kit installed by the real Claude Code into a throwaway {@code CLAUDE_CONFIG_DIR}: the commands, their JSON and
 * the plugin's layout as the installed CLI has them. Runs only with {@code COMANDO_KIT_LIVE=true}.
 */
@EnabledIfEnvironmentVariable(named = "COMANDO_KIT_LIVE", matches = "true")
class KitLiveTest {

    @Test
    void theInstalledClaudeCodeInstallsTheKitAndListsItsSkillsAndHooks(@TempDir Path tmp) throws Exception {
        Path config = Files.createDirectories(tmp.resolve("claude"));
        // the production runner, on a claude that sees only the throwaway config
        Path wrapper = Files.writeString(tmp.resolve("claude-in-config"),
                "#!/bin/sh\nCLAUDE_CONFIG_DIR='" + config + "' exec '" + ClaudeSettings.findExecutable() + "' \"$@\"\n");
        Files.setPosixFilePermissions(wrapper, PosixFilePermissions.fromString("rwx------"));
        Kit.Cli cli = Kit.Cli.process(wrapper.toString());
        Kit kit = new Kit(Kit.Bundle.resources(), cli, tmp.resolve("kit"), config);

        assertThat(kit.status().installed()).isFalse();
        kit.install();

        assertThat(kit.status().installedVersion()).isEqualTo(kit.version());
        assertThat(kit.status().changes()).isEmpty();
        String details = cli.run(List.of("plugin", "details", Kit.PLUGIN)).text();
        assertThat(details).contains("Skills (2)", "deep-review", "reflect", "Hooks (2)  SessionStart, PostToolUse");
        assertThat(config.resolve("KB/README.md")).exists();
        assertThat(Files.readString(config.resolve("settings.json"))).contains("\"autoMemoryDirectory\": \"" + config.resolve("MEMORY") + "\"")
                .as("Claude Code's own entries beside it").contains("\"enabledPlugins\"");
        assertThat(config.resolve("MEMORY/MEMORY.md")).exists();
        assertThat(kit.install()).as("a second install has nothing to do").isEmpty();

        cli.run(List.of("plugin", "disable", Kit.PLUGIN, "--json"));
        assertThat(kit.status().changes()).containsExactly("Enable the plugin comando@comando for your user");
        assertThat(kit.install()).containsExactly("Enabled comando@comando");
        assertThat(kit.status().enabled()).isTrue();
        assertThat(Kit.samePath(kit.status().loadsFrom(), kit.dir())).as("loads from comando's copy").isTrue();
    }
}
