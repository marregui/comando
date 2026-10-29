package dev.marregui.comando.ui.shell;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.KitRoot;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.state.Notice;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/// [KitActions] upgrades the kit through the session in its root, offers the restarts it needs, and words the status
/// bar's tip.
/// - The upgrade makes the kit's session in the root and sends it the prompt; a session called `kit` elsewhere is not
///   taken.
/// - The upgrade has reported once the session took the prompt and finished, or stopped.
/// - Sessions on the old kit are restarted once stopped, except the kit's own and busy ones.
/// - A brain folder shown through a link is inside the brain.
class KitActionsTest {

    /// Each backup says, off the EDT, where it ran and what git did: the brain's what it pushed, the kit's why it
    /// failed.
    @Test
    void theBrainAndTheKitAreBackedUpEachSayingWhatHappened(@TempDir Path tmp) {
        KitActions kit = actions(new KitRoot(tmp.resolve("kit"), Optional.of(KIT), tmp));
        kit.backUp();
        kit.backUpKit();
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> sent.size() == 2);
        assertThat(sent).extracting(i -> ((Intent.Notify) i).text()).anyMatch(t -> t.startsWith("Brain at ") && t.endsWith(": nothing to commit; pushed abc1234"))
                .anyMatch(t -> t.startsWith("The kit at ") && t.endsWith(" was not backed up: git push failed: rejected"));
        assertThat(sent).extracting(i -> ((Intent.Notify) i).level()).containsExactlyInAnyOrder(Notice.Level.INFO, Notice.Level.ERROR);
    }

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

            @Override
            public String backUpKit() throws IOException {
                throw new IOException("git push failed: rejected");
            }
        };
        return new KitActions(new ComandoUi.Dependencies(sent::add, state::get, null, null, null, null, null, null, List::of, null, null, setup,
                "test", null, null, () -> {
        }), () -> null);
    }

    private static SessionState session(String name, String workDir, Status status, int turns) {
        return SessionState.closed(SessionSpec.fresh(name, workDir)).withStatus(status, "").withTurns(turns, 1L);
    }

    private static AppState with(SessionState... sessions) {
        return AppState.initial(List.of(new PanelState(P, "p", "", "")), List.of(), List.of(sessions));
    }

    /// With no `kit` session, [KitActions#send] saves one in the kit root, opens it and sends the upgrade prompt naming
    /// the version and `INSTALL.md`.
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

    /// A `kit` session working in another folder gets a notice to rename or remove it and nothing else; one in the kit
    /// root is reused without being saved again.
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

    /// [KitActions.Upgrade#next] stays sent with no session or an idle one before it took a turn, becomes running on a
    /// busy session, and ends when the session is idle again or closed.
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

    /// [KitActions#toRestart] picks only an open, idle session that is not the kit's; a restart stops it, waits through
    /// closing, and starts it once it is closed, not twice.
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

    /// [ComandoUi#inside] resolves a symbolic link into the brain as inside it, a real folder in it too, and a sibling
    /// not.
    @Test
    void aBrainFolderShownThroughALinkIsInsideTheBrain(@TempDir Path tmp) throws Exception {
        Path memory = Files.createDirectories(tmp.resolve("claude/BRAIN/MEMORY"));
        Path link = Files.createSymbolicLink(tmp.resolve("claude/MEMORY"), memory);

        assertThat(ComandoUi.inside(link, tmp.resolve("claude/BRAIN"))).isTrue();
        assertThat(ComandoUi.inside(memory, tmp.resolve("claude/BRAIN"))).isTrue();
        assertThat(ComandoUi.inside(tmp.resolve("claude/skills"), tmp.resolve("claude/BRAIN"))).isFalse();
    }
}
