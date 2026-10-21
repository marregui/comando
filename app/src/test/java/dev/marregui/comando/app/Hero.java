package dev.marregui.comando.app;

import dev.marregui.comando.claude.ClaudeSettings;
import dev.marregui.comando.core.dispatch.Target;
import dev.marregui.comando.core.intent.EditMode;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.ApprovalDecision;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.LowerTab;
import dev.marregui.comando.core.view.Placement;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.Frame;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Paints the README's picture of comando at work: the embedded QuestDB started from the menu and connected, so the
 * panel's SQL reads in colour; a real Claude Code turn (haiku) that runs the panel's query through comando's tools and
 * plots it in the side pane; the Session tab showing that turn. Every approval is allowed.
 *
 * <pre>./gradlew :app:hero -Pout=$PWD/docs/images</pre> (a relative path is under {@code app/})
 */
public final class Hero {

    private static final String CONNECTION = "local QuestDB";

    private static final String TEXT = """
            -- a minute of a synthetic price, one row a second
            SELECT x second,
                   round(100 + 5 * sin(x / 6.0) + rnd_double(), 2) price
            FROM long_sequence(60);

            Run the query above with db_run_sql on 'local QuestDB', plot
            price against second with ui_plot (title 'price', placement
            'side'), and say in one sentence what the price does.

            /compact
            """;

    private Hero() {
    }

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "build/snapshots");
        Files.createDirectories(out);
        Path home = Files.createTempDirectory("comando-hero");
        AppConfig config = new AppConfig(home, ClaudeSettings.findExecutable(), ClaudeSettings.defaultProjectsDir(), true);
        App app = new App(config, "hero", () -> {
        });
        app.start();
        StateLoop loop = app.loop();
        SwingUtilities.invokeAndWait(() -> {
            for (Frame f : Frame.getFrames()) {
                if (f instanceof JFrame jf && f.isVisible()) {
                    jf.setSize(1600, 1000);
                    jf.validate();
                }
            }
        });

        Snapshot.menuItem("Actions", "Start embedded QuestDB");
        require(Snapshot.await(loop, s -> s.notice() != null && s.notice().text().startsWith("QuestDB is up"), 60_000), "QuestDB did not start");
        loop.dispatch(new Intent.OpenConnection(CONNECTION));
        require(Snapshot.await(loop, s -> s.connection(CONNECTION).map(c -> c.status() == Status.OPEN).orElse(false), 30_000),
                "no connection to " + CONNECTION);

        String repo = Path.of(System.getProperty("user.dir")).getParent().toString();
        // the default permission mode, whatever the painter's own Claude Code sets: the turn's approvals are allowed below
        SessionSpec spec = SessionSpec.fresh("comando", repo).withModel("haiku");
        loop.dispatch(new Intent.SaveSession("", new SessionSpec(spec.name(), spec.workDir(), spec.model(), spec.effort(), "default",
                spec.addDirs(), spec.budgetUsd(), spec.claudeSessionId(), spec.startMode(), spec.checkpoint())));
        loop.dispatch(new Intent.BindSession(loop.state().focused(), "comando"));
        loop.dispatch(new Intent.EditPanel(loop.state().focused(), TEXT, EditMode.REPLACE, ""));

        loop.addListener((prev, next) -> {
            for (Approval a : next.approvals()) {
                if (prev.approval(a.id()).isEmpty()) {
                    loop.dispatch(new Intent.ApprovalAnswered(a.id(), ApprovalDecision.allowOnce()));
                }
            }
        });
        String prompt = TEXT.substring(TEXT.indexOf("Run the query"), TEXT.indexOf("/compact")).strip()
                + "\n\nThe query:\n" + TEXT.substring(0, TEXT.indexOf("\n\nRun the query"));
        loop.dispatch(new Intent.RunText(loop.state().focused(), prompt, Target.CLAUDE));
        require(Snapshot.await(loop, s -> s.views().stream().anyMatch(v -> v.title().equals("price")), 180_000), "no plot within 180 s");
        if (loop.state().views().stream().noneMatch(v -> v.title().equals("price") && v.placement() == Placement.SIDE)) {
            System.err.println("the plot is not in the side pane; run again");
        }
        require(Snapshot.await(loop, s -> s.session("comando").map(x -> !x.isBusy()).orElse(false), 120_000), "the turn did not end");
        loop.dispatch(new Intent.SelectLowerTab(LowerTab.SESSION));
        require(Snapshot.await(loop, s -> s.lowerTab().kind() == LowerTab.Kind.SESSION, 5_000), "the Session tab did not come forward");
        // the render of that state, then the transcript's scroll to its end, which the render posts
        for (int i = 0; i < 3; i++) {
            SwingUtilities.invokeAndWait(() -> {
            });
        }
        Snapshot.shoot(out.resolve("hero.png"));

        loop.dispatch(new Intent.StopSession("comando"));
        Snapshot.await(loop, s -> s.session("comando").map(x -> x.status() == Status.CLOSED).orElse(true), 15_000);
        app.close();
        System.out.println("hero in " + out.toAbsolutePath().resolve("hero.png"));
        System.exit(0);
    }

    private static void require(AppState s, String failure) {
        if (s == null) {
            System.err.println(failure);
            System.exit(1);
        }
    }
}
