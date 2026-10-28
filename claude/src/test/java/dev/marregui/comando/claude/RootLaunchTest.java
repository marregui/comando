package dev.marregui.comando.claude;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.marregui.comando.core.model.KitRoot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/// [RootLaunch] supplies kit-root environment and memory settings to child processes.
///
/// - names a non-default root in the environment
/// - merges the memory directory into settings JSON
/// - exempts the root-upgrade session from kit settings
class RootLaunchTest {

    private static final KitRoot.Manifest KIT = new KitRoot.Manifest("marregui", "BRAIN", List.of(), List.of(), List.of(), List.of());

    private static Map<String, String> env(RootLaunch launch, Map<String, String> before) {
        Map<String, String> env = new HashMap<>(before);
        launch.environment(env);
        return env;
    }

    /// A non-default root adds its configuration directory and memory directory before remaining command arguments.
    /// The input carries a root distinct from the user's ordinary configuration directory.
    @Test
    void aRootElsewhereIsNamedToEveryProcessAndItsKitsMemoryIsSet(@TempDir Path tmp) throws Exception {
        Path user = tmp.resolve(".claude");
        Path root = user.resolve("kit");
        RootLaunch launch = RootLaunch.on(new KitRoot(root, Optional.of(KIT), user));

        assertThat(env(launch, Map.of("PATH", "/bin"))).containsEntry("CLAUDE_CONFIG_DIR", root.toString()).containsEntry("PATH", "/bin");
        List<String> argv = launch.argv(List.of("claude", "-p", "--verbose"));
        assertThat(argv).as("after the executable").containsExactly("claude", "--settings",
                "{\"autoMemoryDirectory\":\"" + root.resolve("BRAIN/MEMORY") + "\"}", "-p", "--verbose");
        assertThat(launch.toString()).isEqualTo("CLAUDE_CONFIG_DIR=" + root);
    }

    /// The ordinary user folder leaves configuration inherited, while a root without a kit adds no settings.
    /// The inherited launch also retains an unrelated environment entry unchanged.
    @Test
    void theUsersOwnFolderNeedsNoConfigDirAndWithoutAKitNoSettings(@TempDir Path tmp) {
        Path user = tmp.resolve(".claude");
        RootLaunch own = RootLaunch.on(new KitRoot(user, Optional.of(KIT), user));
        assertThat(env(own, Map.of())).doesNotContainKey("CLAUDE_CONFIG_DIR");
        assertThat(own.argv(List.of("claude", "-p"))).contains("--settings");

        RootLaunch none = RootLaunch.on(new KitRoot(user, Optional.empty(), user));
        assertThat(none.argv(List.of("claude", "-p"))).containsExactly("claude", "-p");
        assertThat(env(none, Map.of("CLAUDE_CONFIG_DIR", user.toString()))).as("comando's own, inherited").containsEntry("CLAUDE_CONFIG_DIR", user.toString());
        assertThat(RootLaunch.INHERIT.argv(List.of("claude"))).containsExactly("claude");
        assertThat(env(RootLaunch.INHERIT, Map.of("A", "b"))).isEqualTo(Map.of("A", "b"));
    }

    /// Existing settings preserve their model property while the root's memory directory replaces the prior value.
    /// The supplied JSON contains both a retained key and an obsolete memory path.
    @Test
    void settingsComandoAlreadyPassesAreMergedWithTheKits(@TempDir Path tmp) throws Exception {
        Path user = tmp.resolve(".claude");
        RootLaunch launch = RootLaunch.on(new KitRoot(user.resolve("kit"), Optional.of(KIT), user));

        List<String> argv = launch.argv(List.of("claude", "-p", "--settings", "{\"model\":\"haiku\",\"autoMemoryDirectory\":\"/old\"}"));
        assertThat(argv).hasSize(4).startsWith("claude", "-p", "--settings");
        assertThat(new ObjectMapper().readTree(argv.get(3))).isEqualTo(new ObjectMapper().readTree(
                "{\"model\":\"haiku\",\"autoMemoryDirectory\":\"" + user.resolve("kit/BRAIN/MEMORY") + "\"}"));
    }

    /// The upgrade session at the root removes configuration and memory settings so it uses the user's folder.
    /// A different work directory and ordinary session retain the original launch unchanged.
    @Test
    void theSessionThatUpgradesTheKitRunsOnTheUsersOwnFolder(@TempDir Path tmp) {
        Path user = tmp.resolve(".claude");
        Path root = user.resolve("kit");
        RootLaunch launch = RootLaunch.on(new KitRoot(root, Optional.of(KIT), user));

        RootLaunch upgrade = launch.forSession(KitRoot.UPGRADE_SESSION, root + "/");
        assertThat(env(upgrade, Map.of("CLAUDE_CONFIG_DIR", "/set/by/the/user"))).as("taken out: ~/.claude").doesNotContainKey("CLAUDE_CONFIG_DIR");
        assertThat(upgrade.argv(List.of("claude", "-p"))).as("nor the kit's memory").containsExactly("claude", "-p");
        assertThat(upgrade.toString()).isEqualTo("-CLAUDE_CONFIG_DIR");
        assertThat(launch.forSession(KitRoot.UPGRADE_SESSION, tmp.toString())).as("elsewhere: a session like any").isSameAs(launch);
        assertThat(launch.forSession("work", root.toString())).isSameAs(launch);
    }

    /// A started session process receives the selected root through its environment.
    /// The child shell prints the configuration variable after the process callback signals exit.
    @Test
    void aSessionsProcessGetsTheRootInItsEnvironment(@TempDir Path tmp) throws Exception {
        Path user = tmp.resolve(".claude");
        Path root = Files.createDirectories(user.resolve("kit"));
        RootLaunch launch = RootLaunch.on(new KitRoot(root, Optional.of(KIT), user));
        List<String> lines = new CopyOnWriteArrayList<>();
        CountDownLatch exited = new CountDownLatch(1);

        ClaudeProcess.start("env", List.of("sh", "-c", "echo \"$CLAUDE_CONFIG_DIR\""), tmp, launch,
                lines::add, c -> exited.countDown());

        assertThat(exited.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(lines).containsExactly(root.toString());
    }
}
