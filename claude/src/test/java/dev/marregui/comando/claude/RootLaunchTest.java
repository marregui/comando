package dev.marregui.comando.claude;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import dev.marregui.comando.core.model.KitRoot;

import static org.assertj.core.api.Assertions.assertThat;

/// [RootLaunch] supplies kit-root environment and memory settings to child processes.
///
/// - names a non-default root in the environment
/// - merges the memory directory into settings JSON
/// - exempts the upgrade session of a kit inside the user's folder from kit settings
/// - keeps a root elsewhere, another instance's kit, for its upgrade session
class RootLaunchTest {

    private static final KitRoot.Manifest KIT = new KitRoot.Manifest("marregui", "BRAIN", List.of(), List.of(), List.of(), List.of());

    private static Map<String, String> env(final RootLaunch launch, final Map<String, String> before) {
        final Map<String, String> env = new HashMap<>(before);
        launch.environment(env);
        return env;
    }

    /// A non-default root adds its configuration directory and memory directory before remaining command arguments.
    /// The input carries a root distinct from the user's ordinary configuration directory.
    @Test
    void aRootElsewhereIsNamedToEveryProcessAndItsKitsMemoryIsSet(@TempDir final Path tmp) throws Exception {
        final Path user = tmp.resolve(".claude");
        final Path root = user.resolve("kit");
        final RootLaunch launch = RootLaunch.on(new KitRoot(root, Optional.of(KIT), user));

        assertThat(env(launch, Map.of("PATH", "/bin"))).containsEntry("CLAUDE_CONFIG_DIR", root.toString()).containsEntry("PATH", "/bin");
        final List<String> argv = launch.argv(List.of("claude", "-p", "--verbose"));
        assertThat(argv).as("after the executable").containsExactly("claude", "--settings",
                "{\"autoMemoryDirectory\":\"" + root.resolve("BRAIN/MEMORY") + "\"}", "-p", "--verbose");
        assertThat(launch.toString()).isEqualTo("CLAUDE_CONFIG_DIR=" + root);
    }

    /// The ordinary user folder leaves configuration inherited, while a root without a kit adds no settings.
    /// The inherited launch also retains an unrelated environment entry unchanged.
    @Test
    void theUsersOwnFolderNeedsNoConfigDirAndWithoutAKitNoSettings(@TempDir final Path tmp) {
        final Path user = tmp.resolve(".claude");
        final RootLaunch own = RootLaunch.on(new KitRoot(user, Optional.of(KIT), user));
        assertThat(env(own, Map.of())).doesNotContainKey("CLAUDE_CONFIG_DIR");
        assertThat(own.argv(List.of("claude", "-p"))).contains("--settings");

        final RootLaunch none = RootLaunch.on(new KitRoot(user, Optional.empty(), user));
        assertThat(none.argv(List.of("claude", "-p"))).containsExactly("claude", "-p");
        assertThat(env(none, Map.of("CLAUDE_CONFIG_DIR", user.toString()))).as("comando's own, inherited").containsEntry("CLAUDE_CONFIG_DIR", user.toString());
        assertThat(RootLaunch.INHERIT.argv(List.of("claude"))).containsExactly("claude");
        assertThat(env(RootLaunch.INHERIT, Map.of("A", "b"))).isEqualTo(Map.of("A", "b"));
    }

    /// Existing settings preserve their model property while the root's memory directory replaces the prior value.
    /// The supplied JSON contains both a retained key and an obsolete memory path.
    @Test
    void settingsComandoAlreadyPassesAreMergedWithTheKits(@TempDir final Path tmp) throws Exception {
        final Path user = tmp.resolve(".claude");
        final RootLaunch launch = RootLaunch.on(new KitRoot(user.resolve("kit"), Optional.of(KIT), user));

        final List<String> argv = launch.argv(List.of("claude", "-p", "--settings", "{\"model\":\"haiku\",\"autoMemoryDirectory\":\"/old\"}"));
        assertThat(argv).hasSize(4).startsWith("claude", "-p", "--settings");
        assertThat(new ObjectMapper().readTree(argv.get(3))).isEqualTo(new ObjectMapper().readTree(
                "{\"model\":\"haiku\",\"autoMemoryDirectory\":\"" + user.resolve("kit/BRAIN/MEMORY") + "\"}"));
    }

    /// The upgrade session of a kit inside the user's folder drops the configuration and memory settings, so it runs on
    /// that folder; another work directory or another session keeps the root's launch.
    @Test
    void theSessionThatUpgradesAKitInsideTheUsersFolderRunsOnTheUsersOwnFolder(@TempDir final Path tmp) {
        final Path user = tmp.resolve(".claude");
        final Path root = user.resolve("kit");
        final RootLaunch launch = RootLaunch.on(new KitRoot(root, Optional.of(KIT), user));

        final RootLaunch upgrade = launch.forSession(KitRoot.UPGRADE_SESSION, root + "/");
        assertThat(env(upgrade, Map.of("CLAUDE_CONFIG_DIR", "/set/by/the/user"))).as("taken out: the user's ~/.claude, which holds the kit").doesNotContainKey("CLAUDE_CONFIG_DIR");
        assertThat(upgrade.argv(List.of("claude", "-p"))).as("nor the kit's memory").containsExactly("claude", "-p");
        assertThat(upgrade.toString()).isEqualTo("-CLAUDE_CONFIG_DIR");
        assertThat(launch.forSession(KitRoot.UPGRADE_SESSION, tmp.toString())).as("elsewhere: a session like any").isSameAs(launch);
        assertThat(launch.forSession("work", root.toString())).isSameAs(launch);
        assertThat(RootLaunch.on(new KitRoot(user, Optional.of(KIT), user)).forSession(KitRoot.UPGRADE_SESSION, user.toString()))
                .as("the kit is ~/.claude itself").hasToString("-CLAUDE_CONFIG_DIR");
    }

    /// The upgrade session of a root beside the user's folder keeps that root's configuration and memory settings.
    /// The root's name extends the user's folder name, so only a whole path component counts as inside it.
    @Test
    void theSessionThatUpgradesAnotherInstancesKitRunsOnThatKit(@TempDir final Path tmp) {
        final Path user = tmp.resolve(".claude");
        final Path root = tmp.resolve(".claude_b");
        final RootLaunch launch = RootLaunch.on(new KitRoot(root, Optional.of(KIT), user));

        final RootLaunch upgrade = launch.forSession(KitRoot.UPGRADE_SESSION, root.toString());
        assertThat(upgrade).isSameAs(launch);
        assertThat(env(upgrade, Map.of("CLAUDE_CONFIG_DIR", "/set/by/the/user"))).as("kept: never ~/.claude")
                .containsEntry("CLAUDE_CONFIG_DIR", root.toString());
        assertThat(upgrade.argv(List.of("claude", "-p"))).as("with the instance's memory").containsExactly("claude", "--settings",
                "{\"autoMemoryDirectory\":\"" + root.resolve("BRAIN/MEMORY") + "\"}", "-p");
        assertThat(upgrade.toString()).isEqualTo("CLAUDE_CONFIG_DIR=" + root);
    }

    /// A session an agent made carries its maker in `COMANDO_CREATED_BY`, for the kit's hooks; a person's session and
    /// every other process have the variable taken out, so none inherits it from the shell that started comando.
    @Test
    void anAgentsSessionCarriesItsMakerAndNoOtherProcessInheritsIt(@TempDir final Path tmp) {
        final Path user = tmp.resolve(".claude");
        final RootLaunch launch = RootLaunch.on(new KitRoot(Path.of("/kits/b"), Optional.of(KIT), user));
        final RootLaunch made = launch.madeBy("consult-main");

        assertThat(env(made, Map.of())).containsEntry("COMANDO_CREATED_BY", "consult-main").containsEntry("CLAUDE_CONFIG_DIR", "/kits/b");
        assertThat(made.toString()).isEqualTo("CLAUDE_CONFIG_DIR=/kits/b COMANDO_CREATED_BY=consult-main");
        assertThat(env(launch, Map.of("COMANDO_CREATED_BY", "inherited"))).as("a person's session").doesNotContainKey("COMANDO_CREATED_BY");
        assertThat(env(RootLaunch.INHERIT, Map.of("COMANDO_CREATED_BY", "inherited"))).as("any other process").doesNotContainKey("COMANDO_CREATED_BY");
        assertThat(launch.madeBy("")).as("no maker: the same launch").isSameAs(launch);
        assertThat(made.madeBy(" consult-main ")).isSameAs(made);
    }

    /// A started session process receives the selected root through its environment.
    /// The child shell prints the configuration variable after the process callback signals exit.
    @Test
    void aSessionsProcessGetsTheRootInItsEnvironment(@TempDir final Path tmp) throws Exception {
        final Path user = tmp.resolve(".claude");
        final Path root = Files.createDirectories(user.resolve("kit"));
        final RootLaunch launch = RootLaunch.on(new KitRoot(root, Optional.of(KIT), user));
        final List<String> lines = new CopyOnWriteArrayList<>();
        final CountDownLatch exited = new CountDownLatch(1);

        ClaudeProcess.start("env", List.of("sh", "-c", "echo \"$CLAUDE_CONFIG_DIR\""), tmp, launch,
                lines::add, c -> exited.countDown());

        assertThat(exited.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(lines).containsExactly(root.toString());
    }
}
