package dev.marregui.comando.ui.shell;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.KitRoot;
import dev.marregui.comando.core.model.KitVersion;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionState;

import static org.assertj.core.api.Assertions.assertThat;

/// [KitActions] upgrades the kit through the session in its root, offers the restarts it needs, and words the status
/// bar's tip.
/// - The upgrade makes the kit's session in the root and sends it the prompt; a session called `kit` elsewhere is not
///   taken.
/// - The upgrade has reported once the session took the prompt and finished, or stopped; a failed turn is told after
///   the render, and restarts nothing.
/// - A repair of the manifest names the entries it took out, and Repair is not offered again once it rewrote it.
/// - An upgrade the kit does not read afterwards restarts nothing and says what the kit reads.
/// - Sessions on the old kit are restarted once stopped, except the kit's own and busy ones.
/// - A brain folder shown through a link is inside the brain.
class KitActionsTest {

    /// Each backup says, off the EDT, where it ran and what git did: the brain's what it pushed, the kit's why it
    /// failed.
    @Test
    void theBrainAndTheKitAreBackedUpEachSayingWhatHappened(@TempDir final Path tmp) {
        final KitActions kit = actions(new KitRoot(tmp.resolve("kit"), Optional.of(KIT), tmp));
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
    /** What git says of the kit, as the test sets it. */
    private volatile Setup.Status status = Setup.Status.NONE;
    /** What a repair of the manifest answers, or the failure it throws, as the test sets it. */
    private volatile List<String> repairs = List.of();
    private volatile IOException repairFails;

    private KitActions actions(final KitRoot root) {
        final Setup setup = new Setup() {
            @Override
            public KitRoot root() {
                return root;
            }

            @Override
            public Setup.Status status() {
                return status;
            }

            @Override
            public List<String> repairKit() throws IOException {
                if (repairFails != null) {
                    throw repairFails;
                }
                return repairs;
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

    private static SessionState session(final String name, final String workDir, final Status status, final int turns) {
        return SessionState.closed(SessionSpec.fresh(name, workDir)).withStatus(status, "").withTurns(turns, 1L);
    }

    private static AppState with(final SessionState... sessions) {
        return AppState.initial(List.of(new PanelState(P, "p", "", "")), List.of(), List.of(sessions));
    }

    /// With no `kit` session, [KitActions#send] saves one in the kit root, opens it and sends the upgrade prompt naming
    /// the version and `INSTALL.md`.
    @Test
    void theUpgradeMakesTheKitsSessionInTheRootAndSendsItThePrompt(@TempDir final Path tmp) {
        final Path root = tmp.resolve(".claude/comando-kit");
        final KitActions kit = actions(new KitRoot(root, Optional.of(KIT), tmp.resolve(".claude")));

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
    void aSessionCalledKitElsewhereIsNotTakenForTheKits(@TempDir final Path tmp) {
        final Path root = tmp.resolve(".claude/comando-kit");
        final KitRoot r = new KitRoot(root, Optional.of(KIT), tmp.resolve(".claude"));
        final KitActions kit = actions(r);

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
        final KitActions.Upgrade sent = new KitActions.Upgrade("v1.3.0", false);
        final SessionState idle = session("kit", "/r", Status.OPEN, 0);
        final SessionState busy = session("kit", "/r", Status.OPEN, 1);

        assertThat(sent.next(null)).as("a state rendered before the session was made").contains(sent);
        assertThat(sent.next(idle)).as("before it took the prompt").contains(sent);
        final KitActions.Upgrade running = sent.next(busy).orElseThrow();
        assertThat(running.running()).isTrue();
        assertThat(running.next(busy)).contains(running);
        assertThat(running.next(idle)).as("reported").isEmpty();
        assertThat(running.next(session("kit", "/r", Status.CLOSED, 0))).as("stopped").isEmpty();
    }

    /// An upgrade whose turn failed is told as a failure, never as an upgrade, and offers no restart; the report comes
    /// after the render that saw the turn end, not inside it, so no question opens in the middle of a render.
    @Test
    void anUpgradeWhoseTurnFailedIsToldAfterTheRenderAndRestartsNothing(@TempDir final Path tmp) throws Exception {
        final KitActions kit = actions(new KitRoot(tmp, Optional.of(KIT), tmp));
        kit.send(with(session("kit", tmp.toString(), Status.CLOSED, 0)), new KitRoot(tmp, Optional.of(KIT), tmp), "v1.3.0");
        sent.clear();
        final SessionState busy = session("kit", tmp.toString(), Status.OPEN, 1);
        final SessionState failed = session("kit", tmp.toString(), Status.OPEN, 0).withLastTurnError("API Error: 529 overloaded");
        final List<Intent> duringRender = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            kit.render(with(busy, session("a", "/w", Status.OPEN, 0)));
            kit.render(with(failed, session("a", "/w", Status.OPEN, 0)));
            duringRender.addAll(sent);
        });
        assertThat(duringRender).as("nothing told inside the render").isEmpty();
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> !sent.isEmpty());
        assertThat(sent).singleElement().isInstanceOfSatisfying(Intent.Notify.class, n -> {
            assertThat(n.level()).isEqualTo(Notice.Level.WARN);
            assertThat(n.text()).isEqualTo("The kit's session reported an error on the upgrade to v1.3.0: API Error: 529 overloaded; "
                    + "no session was restarted");
        });
    }

    /// A repair says which entries it took out of the manifest, in the words the question named them, and that
    /// nothing was needed when none were.
    @Test
    void aRepairNamesTheEntriesItTookOut() {
        assertThat(KitActions.repaired("~/kit/comando-kit.json", List.of())).isEqualTo("~/kit/comando-kit.json needed no repair");
        assertThat(KitActions.repaired("~/kit/comando-kit.json", List.of("folders[2].path: not inside the root",
                "folders[3].name: used twice"))).isEqualTo("~/kit/comando-kit.json rewritten without these 2 entries: "
                + "folders[2].path: not inside the root; folders[3].name: used twice; restart comando to read it");
    }

    /// Once a repair has rewritten the manifest, Repair is no longer offered, since the entries read at the start are
    /// gone from the file until the next start; a repair that failed offers it again.
    @Test
    void repairIsNotOfferedAgainAfterItRewroteTheManifest(@TempDir final Path tmp) throws Exception {
        final KitRoot.Manifest bad = new KitRoot.Manifest("marregui", "BRAIN", List.of(), List.of(), List.of(), List.of(),
                List.of("folders[2].path: not inside the root"));
        final KitActions kit = actions(new KitRoot(tmp, Optional.of(bad), tmp));
        assertThat(kit.canRepair()).as("an entry left out at the start").isTrue();

        repairFails = new IOException("refused whole");
        SwingUtilities.invokeAndWait(() -> kit.rewrite("~/kit/comando-kit.json"));
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> sent.size() == 1);
        SwingUtilities.invokeAndWait(() -> { });
        assertThat(kit.canRepair()).as("the repair failed: offered again").isTrue();

        repairFails = null;
        repairs = List.of("folders[2].path: not inside the root");
        SwingUtilities.invokeAndWait(() -> kit.rewrite("~/kit/comando-kit.json"));
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> sent.size() == 2);
        SwingUtilities.invokeAndWait(() -> { });
        assertThat(((Intent.Notify) sent.get(1)).text()).startsWith("~/kit/comando-kit.json rewritten without this entry");
        assertThat(kit.canRepair()).as("rewritten: nothing to repair until the next start").isFalse();
    }

    /// An upgrade the kit's session reported on, while the kit reads another version than the one asked for, says so
    /// and restarts nothing: no question opens.
    @Test
    void anUpgradeTheKitDoesNotReadRestartsNothing(@TempDir final Path tmp) throws Exception {
        final KitActions kit = actions(new KitRoot(tmp, Optional.of(KIT), tmp));
        status = new Setup.Status(new KitVersion("v1.2.0", List.of(), ""), false, false);
        state.set(with(session("kit", tmp.toString(), Status.CLOSED, 0), session("a", "/w", Status.OPEN, 0)));
        kit.send(with(session("kit", tmp.toString(), Status.CLOSED, 0)), new KitRoot(tmp, Optional.of(KIT), tmp), "v1.3.0");
        sent.clear();
        SwingUtilities.invokeAndWait(() -> {
            kit.render(with(session("kit", tmp.toString(), Status.OPEN, 1), session("a", "/w", Status.OPEN, 0)));
            kit.render(with(session("kit", tmp.toString(), Status.OPEN, 0), session("a", "/w", Status.OPEN, 0)));
        });
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> !sent.isEmpty());
        assertThat(sent).singleElement().isInstanceOfSatisfying(Intent.Notify.class, n -> {
            assertThat(n.level()).isEqualTo(Notice.Level.WARN);
            assertThat(n.text()).isEqualTo("The kit's session has reported on the upgrade to v1.3.0, but the kit reads v1.2.0; "
                    + "no session was restarted");
        });
    }

    /// [KitActions#toRestart] picks only an open, idle session that is not the kit's; a restart stops it, waits through
    /// closing, and starts it once it is closed, not twice.
    @Test
    void theSessionsOnTheOldKitAreRestartedOnceStoppedButTheKitsAndBusyOnes(@TempDir final Path tmp) {
        final KitActions kit = actions(new KitRoot(tmp, Optional.of(KIT), tmp));
        final AppState s = with(session("a", "/w", Status.OPEN, 0), session("busy", "/w", Status.OPEN, 1), session("kit", "/r", Status.OPEN, 0),
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
    void aBrainFolderShownThroughALinkIsInsideTheBrain(@TempDir final Path tmp) throws Exception {
        final Path memory = Files.createDirectories(tmp.resolve("claude/BRAIN/MEMORY"));
        final Path link = Files.createSymbolicLink(tmp.resolve("claude/MEMORY"), memory);

        assertThat(ComandoUi.inside(link, tmp.resolve("claude/BRAIN"))).isTrue();
        assertThat(ComandoUi.inside(memory, tmp.resolve("claude/BRAIN"))).isTrue();
        assertThat(ComandoUi.inside(tmp.resolve("claude/skills"), tmp.resolve("claude/BRAIN"))).isFalse();
    }
}
