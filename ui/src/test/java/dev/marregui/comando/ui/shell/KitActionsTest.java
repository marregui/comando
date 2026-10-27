package dev.marregui.comando.ui.shell;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.KitRoot;
import dev.marregui.comando.core.model.KitVersion;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** The kit's upgrade through the session in its root, the restarts it offers, and what the status bar says. */
class KitActionsTest {

    private static final PanelId P = new PanelId("p");
    private static final KitRoot.Manifest KIT = new KitRoot.Manifest("marregui", "BRAIN", List.of(), List.of(), List.of(), List.of());

    private final List<Intent> sent = new CopyOnWriteArrayList<>();
    private final AtomicReference<AppState> state = new AtomicReference<>();

    private KitActions actions(KitRoot root) {
        Setup setup = new Setup() {
            @Override
            public KitRoot root() {
                return root;
            }

            @Override
            public Setup.Status status() {
                return Setup.Status.NONE;
            }

            @Override
            public String backUpBrain() throws IOException {
                return "nothing to commit; pushed abc1234";
            }
        };
        return new KitActions(new ComandoUi.Dependencies(sent::add, state::get, null, null, null, null, null, null, List::of, null, setup,
                "test", null, null, () -> {
        }), () -> null);
    }

    private static SessionState session(String name, String workDir, Status status, int turns) {
        return SessionState.closed(SessionSpec.fresh(name, workDir)).withStatus(status, "").withTurns(turns, 1L);
    }

    private static AppState with(SessionState... sessions) {
        return AppState.initial(List.of(new PanelState(P, "p", "", "")), List.of(), List.of(sessions));
    }

    @Test
    void theUpgradeMakesTheKitsSessionInTheRootAndSendsItThePrompt(@TempDir Path tmp) {
        Path root = tmp.resolve(".claude/comando-kit");
        KitActions kit = actions(new KitRoot(root, Optional.of(KIT), tmp.resolve(".claude")));

        kit.send(with(), new KitRoot(root, Optional.of(KIT), tmp.resolve(".claude")), "v1.3.0");

        assertThat(sent).hasSize(3);
        assertThat(sent.get(0)).isEqualTo(new Intent.SaveSession("", SessionSpec.fresh("kit", root.toString())));
        assertThat(sent.get(1)).isInstanceOfSatisfying(Intent.OpenSession.class, o -> assertThat(o.session()).isEqualTo("kit"));
        assertThat(sent.get(2)).isEqualTo(new Intent.SessionCommand("kit", "Upgrade the kit at " + root + " to v1.3.0, following "
                + root.resolve("INSTALL.md") + " section Upgrade. Report what changed."));
    }

    @Test
    void aSessionCalledKitElsewhereIsNotTakenForTheKits(@TempDir Path tmp) {
        Path root = tmp.resolve(".claude/comando-kit");
        KitRoot r = new KitRoot(root, Optional.of(KIT), tmp.resolve(".claude"));
        KitActions kit = actions(r);

        kit.send(with(session("kit", tmp.toString(), Status.CLOSED, 0)), r, "v1.3.0");
        assertThat(sent).singleElement().isInstanceOfSatisfying(Intent.Notify.class, n ->
                assertThat(n.text()).startsWith("The session kit works in " + tmp).endsWith(": rename or remove that session"));

        sent.clear();
        kit.send(with(session("kit", root.toString(), Status.CLOSED, 0)), r, "v1.3.0");
        assertThat(sent).as("its own: reused").hasSize(2).noneMatch(i -> i instanceof Intent.SaveSession);
    }

    @Test
    void theUpgradeHasReportedOnceTheSessionTookThePromptAndFinished() {
        KitActions.Upgrade sent = new KitActions.Upgrade("v1.3.0", false);
        SessionState idle = session("kit", "/r", Status.OPEN, 0);
        SessionState busy = session("kit", "/r", Status.OPEN, 1);

        assertThat(sent.next(null)).as("a state rendered before the session was made").contains(sent);
        assertThat(sent.next(idle)).as("before it took the prompt").contains(sent);
        KitActions.Upgrade running = sent.next(busy).orElseThrow();
        assertThat(running.running()).isTrue();
        assertThat(running.next(busy)).contains(running);
        assertThat(running.next(idle)).as("reported").isEmpty();
        assertThat(running.next(session("kit", "/r", Status.CLOSED, 0))).as("stopped").isEmpty();
    }

    @Test
    void theSessionsOnTheOldKitAreRestartedOnceStoppedButTheKitsAndBusyOnes(@TempDir Path tmp) {
        KitActions kit = actions(new KitRoot(tmp, Optional.of(KIT), tmp));
        AppState s = with(session("a", "/w", Status.OPEN, 0), session("busy", "/w", Status.OPEN, 1), session("kit", "/r", Status.OPEN, 0),
                session("off", "/w", Status.CLOSED, 0));

        assertThat(KitActions.toRestart(s)).containsExactly("a");
        kit.restart(List.of("a"));
        assertThat(sent).containsExactly(new Intent.StopSession("a"));
        kit.render(with(session("a", "/w", Status.CLOSING, 0)));
        assertThat(sent).as("still stopping").hasSize(1);
        kit.render(with(session("a", "/w", Status.CLOSED, 0)));
        kit.render(with(session("a", "/w", Status.CLOSED, 0)));
        assertThat(sent).as("started once").containsExactly(new Intent.StopSession("a"), new Intent.StartSession("a"));
    }

    @Test
    void theStatusBarsTipSaysWhereTheKitAndItsBrainAreAndWhatIsUnknown(@TempDir Path tmp) {
        KitRoot root = new KitRoot(tmp.resolve("kit"), Optional.of(KIT), tmp);

        assertThat(KitActions.tip(root, new Setup.Status(KitVersion.of("v1.0.0", ""), true)))
                .isEqualTo("The kit marregui at " + tmp.resolve("kit") + ", its brain at " + tmp.resolve("kit/BRAIN") + ". Click for its menu.");
        assertThat(KitActions.tip(root, new Setup.Status(KitVersion.of("v1.0.0", "").failedRemote("git ls-remote failed: offline"), true)))
                .endsWith("\nIts newer releases are unknown: git ls-remote failed: offline");
        assertThat(KitActions.tip(root, Setup.Status.NONE)).endsWith("\nIts version is unknown: the root is no git checkout.");
    }

    @Test
    void aBrainFolderShownThroughALinkIsInsideTheBrain(@TempDir Path tmp) throws Exception {
        Path memory = Files.createDirectories(tmp.resolve("claude/BRAIN/MEMORY"));
        Path link = Files.createSymbolicLink(tmp.resolve("claude/MEMORY"), memory);

        assertThat(ComandoUi.inside(link, tmp.resolve("claude/BRAIN"))).isTrue();
        assertThat(ComandoUi.inside(memory, tmp.resolve("claude/BRAIN"))).isTrue();
        assertThat(ComandoUi.inside(tmp.resolve("claude/skills"), tmp.resolve("claude/BRAIN"))).isFalse();
    }
}
