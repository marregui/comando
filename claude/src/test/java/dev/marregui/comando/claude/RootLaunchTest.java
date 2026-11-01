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

import dev.marregui.comando.core.loop.Errands;
import dev.marregui.comando.core.model.KitRoot;

import static org.assertj.core.api.Assertions.assertThat;

/// [RootLaunch] supplies kit-root environment and memory settings to child processes.
///
/// - names a non-default root in the environment
/// - merges the memory directory into settings JSON
/// - exempts the upgrade session of a kit inside the user's folder from kit settings
/// - keeps a root elsewhere, another instance's kit, for its upgrade session
/// - names the instance's data root to every process it starts
/// - names the instance's brain to every process it starts, and its memory to Claude Code
class RootLaunchTest {

    /** The errands a started process runs its stdin writer and exit waiter on. */
    private static final Errands HELPERS = new Errands("root-launch-test");

    private static final KitRoot.Manifest KIT = new KitRoot.Manifest("marregui", "BRAIN", List.of(), List.of(), List.of(), List.of());
    /** The instance's data root every launch here names. */
    private static final Path DATA = Path.of("/data/root");

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
        final RootLaunch launch = RootLaunch.on(new KitRoot(root, Optional.of(KIT), user), DATA);

        assertThat(env(launch, Map.of("PATH", "/bin"))).containsEntry("CLAUDE_CONFIG_DIR", root.toString()).containsEntry("PATH", "/bin");
        final List<String> argv = launch.argv(List.of("claude", "-p", "--verbose"));
        assertThat(argv).as("after the executable").containsExactly("claude", "--settings",
                "{\"autoMemoryDirectory\":\"" + root.resolve("BRAIN/MEMORY") + "\"}", "-p", "--verbose");
        assertThat(launch.toString()).isEqualTo("CLAUDE_CONFIG_DIR=" + root + " COMANDO_HOME=/data/root COMANDO_BRAIN=" + root.resolve("BRAIN"));
    }

    /// The ordinary user folder leaves configuration inherited, while a root without a kit adds no settings.
    /// The inherited launch also retains an unrelated environment entry unchanged.
    @Test
    void theUsersOwnFolderNeedsNoConfigDirAndWithoutAKitNoSettings(@TempDir final Path tmp) {
        final Path user = tmp.resolve(".claude");
        final RootLaunch own = RootLaunch.on(new KitRoot(user, Optional.of(KIT), user), DATA);
        assertThat(env(own, Map.of())).doesNotContainKey("CLAUDE_CONFIG_DIR");
        assertThat(own.argv(List.of("claude", "-p"))).contains("--settings");

        final RootLaunch none = RootLaunch.on(new KitRoot(user, Optional.empty(), user), DATA);
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
        final RootLaunch launch = RootLaunch.on(new KitRoot(user.resolve("kit"), Optional.of(KIT), user), DATA);

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
        final RootLaunch launch = RootLaunch.on(new KitRoot(root, Optional.of(KIT), user), DATA);

        final RootLaunch upgrade = launch.forSession(KitRoot.UPGRADE_SESSION, root + "/");
        assertThat(env(upgrade, Map.of("CLAUDE_CONFIG_DIR", "/set/by/the/user"))).as("taken out: the user's ~/.claude, which holds the kit").doesNotContainKey("CLAUDE_CONFIG_DIR");
        assertThat(upgrade.argv(List.of("claude", "-p"))).as("nor the kit's memory").containsExactly("claude", "-p");
        assertThat(upgrade.toString()).isEqualTo("-CLAUDE_CONFIG_DIR COMANDO_HOME=/data/root COMANDO_BRAIN=" + root.resolve("BRAIN"));
        assertThat(launch.forSession(KitRoot.UPGRADE_SESSION, tmp.toString())).as("elsewhere: a session like any").isSameAs(launch);
        assertThat(launch.forSession("work", root.toString())).isSameAs(launch);
        assertThat(RootLaunch.on(new KitRoot(user, Optional.of(KIT), user), DATA).forSession(KitRoot.UPGRADE_SESSION, user.toString()))
                .as("the kit is ~/.claude itself").hasToString("-CLAUDE_CONFIG_DIR COMANDO_HOME=/data/root COMANDO_BRAIN=" + user.resolve("BRAIN"));
    }

    /// The upgrade session of a root beside the user's folder keeps that root's configuration and memory settings.
    /// The root's name extends the user's folder name, so only a whole path component counts as inside it.
    @Test
    void theSessionThatUpgradesAnotherInstancesKitRunsOnThatKit(@TempDir final Path tmp) {
        final Path user = tmp.resolve(".claude");
        final Path root = tmp.resolve(".claude_b");
        final RootLaunch launch = RootLaunch.on(new KitRoot(root, Optional.of(KIT), user), DATA);

        final RootLaunch upgrade = launch.forSession(KitRoot.UPGRADE_SESSION, root.toString());
        assertThat(upgrade).isSameAs(launch);
        assertThat(env(upgrade, Map.of("CLAUDE_CONFIG_DIR", "/set/by/the/user"))).as("kept: never ~/.claude")
                .containsEntry("CLAUDE_CONFIG_DIR", root.toString());
        assertThat(upgrade.argv(List.of("claude", "-p"))).as("with the instance's memory").containsExactly("claude", "--settings",
                "{\"autoMemoryDirectory\":\"" + root.resolve("BRAIN/MEMORY") + "\"}", "-p");
        assertThat(upgrade.toString()).isEqualTo("CLAUDE_CONFIG_DIR=" + root + " COMANDO_HOME=/data/root COMANDO_BRAIN=" + root.resolve("BRAIN"));
    }

    /// A session an agent made carries its maker in `COMANDO_CREATED_BY`, for the kit's hooks; a person's session and
    /// every other process have the variable taken out, so none inherits it from the shell that started comando.
    @Test
    void anAgentsSessionCarriesItsMakerAndNoOtherProcessInheritsIt(@TempDir final Path tmp) {
        final Path user = tmp.resolve(".claude");
        final RootLaunch launch = RootLaunch.on(new KitRoot(Path.of("/kits/b"), Optional.of(KIT), user), DATA);
        final RootLaunch made = launch.madeBy("consult-main");

        assertThat(env(made, Map.of())).containsEntry("COMANDO_CREATED_BY", "consult-main").containsEntry("CLAUDE_CONFIG_DIR", "/kits/b");
        assertThat(made.toString()).isEqualTo("CLAUDE_CONFIG_DIR=/kits/b COMANDO_HOME=/data/root COMANDO_BRAIN=/kits/b/BRAIN COMANDO_CREATED_BY=consult-main");
        assertThat(env(launch, Map.of("COMANDO_CREATED_BY", "inherited"))).as("a person's session").doesNotContainKey("COMANDO_CREATED_BY");
        assertThat(env(RootLaunch.INHERIT, Map.of("COMANDO_CREATED_BY", "inherited"))).as("any other process").doesNotContainKey("COMANDO_CREATED_BY");
        assertThat(launch.madeBy("")).as("no maker: the same launch").isSameAs(launch);
        assertThat(made.madeBy(" consult-main ")).isSameAs(made);
    }

    /// A started session process receives the selected root, the instance's data root and its brain through its
    /// environment, the brain as the instance resolved it (here a `COMANDO_BRAIN` that wins over the manifest's).
    /// The child shell prints the three variables after the process callback signals exit.
    @Test
    void aSessionsProcessGetsTheRootTheDataRootAndTheBrainInItsEnvironment(@TempDir final Path tmp) throws Exception {
        final Path user = tmp.resolve(".claude");
        final Path root = Files.createDirectories(user.resolve("kit"));
        final Path data = Files.createDirectories(tmp.resolve("data"));
        final Path brain = tmp.resolve("brain-elsewhere");
        final RootLaunch launch = RootLaunch.on(new KitRoot(root, Optional.of(KIT), user, "", brain.toString()), data);
        final List<String> lines = new CopyOnWriteArrayList<>();
        final CountDownLatch exited = new CountDownLatch(1);

        ClaudeProcess.start("env", List.of("sh", "-c", "echo \"$CLAUDE_CONFIG_DIR\"; echo \"$COMANDO_HOME\"; echo \"$COMANDO_BRAIN\""), tmp, launch, HELPERS,
                lines::add, c -> exited.countDown());

        assertThat(exited.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(lines).containsExactly(root.toString(), data.toString(), brain.toString());
        assertThat(launch.argv(List.of("claude"))).as("the brain's memory").containsExactly("claude", "--settings",
                "{\"autoMemoryDirectory\":\"" + brain.resolve("MEMORY") + "\"}");
    }

    /// Without a brain (a root with no kit) the variable is taken out, so none is inherited from the shell; a launch
    /// with no instance leaves it as inherited.
    @Test
    void aRootWithoutABrainTakesTheVariableOut(@TempDir final Path tmp) {
        final Path user = tmp.resolve(".claude");
        final Map<String, String> shell = Map.of("COMANDO_BRAIN", "/another/brain");
        assertThat(env(RootLaunch.on(new KitRoot(user, Optional.empty(), user), DATA), shell)).doesNotContainKey("COMANDO_BRAIN");
        assertThat(env(RootLaunch.INHERIT, shell)).containsEntry("COMANDO_BRAIN", "/another/brain");
    }

    /// A root without a kit, comando started with `COMANDO_BRAIN`, gives a session neither the variable, taken out even
    /// when inherited, nor the brain's memory as `autoMemoryDirectory`: the brain is a kit's.
    @Test
    void withoutAKitNoBrainVariableNorMemorySettingEvenWithComandoBrain(@TempDir final Path tmp) {
        final Path user = tmp.resolve(".claude");
        final String brain = tmp.resolve("brain").toString();
        final RootLaunch launch = RootLaunch.on(new KitRoot(user, Optional.empty(), user, "", brain), tmp.resolve("data"));

        assertThat(env(launch, Map.of(KitRoot.BRAIN_ENV, brain))).as("taken out without a kit").doesNotContainKey(KitRoot.BRAIN_ENV);
        assertThat(launch.argv(List.of("claude", "-p"))).as("no autoMemoryDirectory without a kit").containsExactly("claude", "-p");
    }

    /// Every process an instance starts names that instance's data root in `COMANDO_HOME`, over one inherited from the
    /// shell, beside `CLAUDE_CONFIG_DIR`: the upgrade session and an agent's session too, so a kit's hook reads this
    /// instance's files; a launch with no instance (tests, tools) leaves the variable as inherited.
    @Test
    void everyProcessGetsItsInstancesDataRootOverAnInheritedOne(@TempDir final Path tmp) {
        final Path user = tmp.resolve(".claude");
        final RootLaunch launch = RootLaunch.on(new KitRoot(user, Optional.of(KIT), user), DATA);
        final Map<String, String> shell = Map.of("COMANDO_HOME", "/another/instance");

        assertThat(env(launch, shell)).containsEntry("COMANDO_HOME", "/data/root");
        assertThat(env(launch.forSession(KitRoot.UPGRADE_SESSION, user.toString()), shell)).as("the upgrade session").containsEntry("COMANDO_HOME", "/data/root");
        assertThat(env(launch.madeBy("foreman").checkpointBy(true), shell)).as("an agent's session").containsEntry("COMANDO_HOME", "/data/root");
        assertThat(env(RootLaunch.INHERIT, shell)).as("no instance").containsEntry("COMANDO_HOME", "/another/instance");
    }
}
