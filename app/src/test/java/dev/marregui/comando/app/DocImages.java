package dev.marregui.comando.app;

import dev.marregui.comando.claude.ClaudeSettings;
import dev.marregui.comando.core.dispatch.Target;
import dev.marregui.comando.core.intent.EditMode;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.ApprovalDecision;
import dev.marregui.comando.core.model.ApprovalKind;
import dev.marregui.comando.core.model.ChangeAsk;
import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.KitRoot;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.PlanUsage;
import dev.marregui.comando.core.model.RepoStatus;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.LowerTab;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Paints the pictures of the README and the docs: comando at work on a scratch data root, with neutral names, a sample
 * repository, the embedded QuestDB and one real Claude Code turn (haiku). The status bar shows fixed plan usage, so no
 * account's figures are published. Each picture is painted at twice its size, for high-density screens.
 *
 * <pre>./gradlew :app:docImages -Pout=$PWD/docs/images</pre> (a relative path is under {@code app/})
 */
public final class DocImages {

    private static final String CONNECTION = "local QuestDB";
    private static final String SESSION = "analysis";
    private static final int WIDTH = 1600;
    private static final int HEIGHT = 1000;
    private static final long CARD_TOOL = 9_000_001L;
    private static final long CARD_QUESTION = 9_000_002L;

    private static final String QUERY = """
            -- a minute of a synthetic price, one row a second
            SELECT x second,
                   round(100 + 5 * sin(x / 6.0) + rnd_double(), 2) price
            FROM long_sequence(60);
            """;

    private static final String PROMPT = """
            Run the query above with db_run_sql on 'local QuestDB', plot
            price against second with ui_plot (title 'price', placement
            'side'), and say in one sentence what the price does.
            """;

    private DocImages() {
    }

    public static void main(String[] args) throws Exception {
        Path out = Files.createDirectories(Path.of(args.length > 0 ? args[0] : "build/doc-images"));
        // Claude Code and its folder are found in the painter's home; the window then runs in a scratch home, so every
        // path it shows reads ~/.claude/lane, the same on every machine
        String claude = ClaudeSettings.findExecutable();
        KitRoot root = KitRoot.current();
        Path userHome = Files.createTempDirectory("comando-docs-home");
        System.setProperty("user.home", userHome.toString());
        Path home = Files.createDirectories(userHome.resolve(".comando"));
        Path repo = Snapshot.sampleRepo(userHome.resolve(".claude").resolve("lane"));
        Files.writeString(home.resolve("folders.json"), JsonFile.JSON.writeValueAsString(List.of(
                new FolderRoot("lane", repo.toString(), "README.md", List.of()))));
        AppConfig config = new AppConfig(home, claude, root, true);
        App app = new App(config, "docs", () -> {
        });
        app.start();
        StateLoop loop = app.loop();
        SwingUtilities.invokeAndWait(() -> {
            for (Frame f : Frame.getFrames()) {
                if (f instanceof JFrame jf && f.isVisible()) {
                    jf.setSize(WIDTH, HEIGHT);
                    jf.validate();
                }
            }
        });
        // every approval of the live turn is allowed once, as the user would; the pictures' own cards are left waiting
        loop.addListener((prev, next) -> {
            for (Approval a : next.approvals()) {
                if (prev.approval(a.id()).isEmpty() && a.id() != CARD_TOOL && a.id() != CARD_QUESTION) {
                    loop.dispatch(new Intent.ApprovalAnswered(a.id(), ApprovalDecision.allowOnce()));
                }
            }
        });

        Snapshot.menuItem("Actions", "Start embedded QuestDB");
        require(Snapshot.await(loop, s -> s.notice() != null && s.notice().text().startsWith("QuestDB is up"), 60_000), "QuestDB did not start");
        loop.dispatch(new Intent.OpenConnection(CONNECTION));
        require(Snapshot.await(loop, s -> s.connection(CONNECTION).map(c -> c.status() == Status.OPEN).orElse(false), 30_000),
                "no connection to " + CONNECTION);

        hero(loop, repo, out);
        approvals(loop, out);
        sql(loop, out);
        changes(loop, repo, out);

        loop.dispatch(new Intent.StopSession(SESSION));
        Snapshot.await(loop, s -> s.session(SESSION).map(x -> x.status() == Status.CLOSED).orElse(true), 15_000);
        app.close();
        System.out.println("pictures in " + out.toAbsolutePath());
        System.exit(0);
    }

    /** The front page: a query in the session's panel, a real turn that runs it and plots it beside the editor. */
    private static void hero(StateLoop loop, Path repo, Path out) throws Exception {
        // the default permission mode, whatever the painter's own Claude Code sets: the turn's approvals are allowed above
        SessionSpec spec = SessionSpec.fresh(SESSION, repo.toString()).withModel("haiku");
        loop.dispatch(new Intent.SaveSession("", new SessionSpec(spec.name(), spec.workDir(), spec.model(), spec.effort(), "default",
                spec.addDirs(), spec.budgetUsd(), spec.claudeSessionId(), spec.startMode(), spec.checkpoint(), spec.carried())));
        AppState opened = Snapshot.await(loop, s -> ownPanel(s, SESSION) != null, 10_000);
        require(opened, "the session has no panel");
        PanelId panel = ownPanel(opened, SESSION);
        loop.dispatch(new Intent.FocusPanel(panel));
        loop.dispatch(new Intent.BindConnection(panel, CONNECTION));
        loop.dispatch(new Intent.EditPanel(panel, QUERY + "\n" + PROMPT, EditMode.REPLACE, ""));
        loop.dispatch(new Intent.RunText(panel, PROMPT.strip() + "\n\nThe query:\n" + QUERY.strip(), Target.CLAUDE));
        if (Snapshot.await(loop, s -> s.views().stream().anyMatch(v -> v.title().equals("price")), 180_000) == null) {
            // what the session did instead, for the painter to read
            loop.dispatch(new Intent.SelectLowerTab(LowerTab.SESSION));
            settle();
            shoot(loop, out.resolve("hero-failed.png"));
            require(null, "no plot within 180 s; the session's transcript is in hero-failed.png");
        }
        require(Snapshot.await(loop, s -> s.session(SESSION).map(x -> !x.isBusy()).orElse(false), 120_000), "the turn did not end");
        loop.dispatch(new Intent.SelectLowerTab(LowerTab.SESSION));
        settle();
        shoot(loop, out.resolve("hero.png"));
    }

    /** Approval cards, a tool's and a question's, beside a diff Claude placed in the side pane. */
    private static void approvals(StateLoop loop, Path out) throws Exception {
        loop.dispatch(new Intent.ShowView(SESSION, "Lane.java", new ViewSpec.Diff("src/main/java/demo/Lane.java",
                "    long read() {\n        long s = stamp1;\n        return s + stamp2;\n    }\n",
                "    long read() {\n        long s = stamp1;\n        // the reader takes its stamp before the data\n        return s + stamp2 + stamp3;\n    }\n"),
                Placement.SIDE));
        long now = System.currentTimeMillis();
        loop.dispatch(new Intent.ApprovalRequested(new Approval(CARD_TOOL, SESSION, ApprovalKind.TOOL, "Bash",
                "{\"command\":\"./gradlew test\",\"description\":\"Run the tests\"}", "./gradlew test", now)));
        loop.dispatch(new Intent.ApprovalRequested(new Approval(CARD_QUESTION, SESSION, ApprovalKind.QUESTION, "AskUserQuestion",
                "{\"questions\":[{\"question\":\"Run the full suite or the lane tests only?\",\"options\":[{\"label\":\"Full suite\"},"
                        + "{\"label\":\"Lane tests only\"}]}]}",
                "Run the full suite or the lane tests only?", now)));
        loop.dispatch(new Intent.SelectLowerTab(LowerTab.APPROVALS));
        settle();
        shoot(loop, out.resolve("approvals.png"));
        for (long id : new long[]{CARD_TOOL, CARD_QUESTION}) {
            loop.dispatch(new Intent.ApprovalAnswered(id, ApprovalDecision.allowOnce()));
        }
    }

    /** A query on the panel's database: the result grid in the lower panel, its run's figures in the tab. */
    private static void sql(StateLoop loop, Path out) throws Exception {
        PanelId scratch = loop.state().panels().stream().filter(p -> p.title().equals("scratch")).map(PanelState::id).findFirst()
                .orElseThrow();
        loop.dispatch(new Intent.FocusPanel(scratch));
        loop.dispatch(new Intent.BindConnection(scratch, CONNECTION));
        String query = """
                -- a synthetic day of 100 000 trades on a 0.25 tick, by hour and symbol
                SELECT ts, symbol, count() trades,
                       first(price) open, max(price) high,
                       min(price) low, last(price) close
                FROM (
                    SELECT timestamp_sequence(to_timestamp('2026-10-27', 'yyyy-MM-dd'), 864000L) ts,
                           rnd_symbol('ALPHA', 'BRAVO', 'DELTA') symbol,
                           100 + rnd_int(0, 80, 0) * 0.25 price
                    FROM long_sequence(100000)
                ) timestamp(ts)
                SAMPLE BY 1h;
                """;
        loop.dispatch(new Intent.EditPanel(scratch, query, EditMode.REPLACE, ""));
        loop.dispatch(new Intent.RunText(scratch, query.strip(), Target.SQL));
        Thread.sleep(3_000);
        loop.dispatch(new Intent.SelectLowerTab(LowerTab.RESULTS));
        settle();
        shoot(loop, out.resolve("sql.png"));
    }

    /** The Files tab with the sample repository, its uncommitted changes, and one file compared with HEAD. */
    private static void changes(StateLoop loop, Path repo, Path out) throws Exception {
        Snapshot.selectSidebarTab("Files");
        Snapshot.await(loop, s -> Snapshot.rowShown("Files", "lane"), 10_000);
        SwingUtilities.invokeAndWait(() -> {
            var files = Snapshot.tree("Files");
            for (int i = 0; files != null && i < files.getRowCount() && i < 40; i++) {
                files.expandRow(i);
            }
        });
        Snapshot.await(loop, s -> Snapshot.rowShown("Files", "Lane.java"), 10_000);
        SwingUtilities.invokeAndWait(() -> Snapshot.doubleClickRow(Snapshot.tree("Files"), "lane"));
        Snapshot.await(loop, s -> s.views().stream().anyMatch(v -> v.spec() instanceof ViewSpec.Changes), 10_000);
        Snapshot.await(loop, s -> Snapshot.rowShown(ViewSpec.Changes.TITLE, "Lane.java"), 15_000);
        settle();
        shoot(loop, out.resolve("changes.png"));
        loop.dispatch(new Intent.OpenChange(repo.toString(),
                new RepoStatus.FileChange("src/main/java/demo/Lane.java", "", RepoStatus.Kind.MODIFIED), ChangeAsk.OPEN));
        String title = "src/main/java/demo/Lane.java — compared with HEAD";
        require(Snapshot.await(loop, s -> Snapshot.dialogShown(title), 10_000), "no comparison window");
        Snapshot.flushEdt();
        Snapshot.flushEdt();
        Snapshot.shootDialog(title, out.resolve("diff.png"));
    }

    private static PanelId ownPanel(AppState s, String session) {
        return s.panels().stream().filter(p -> session.equals(p.session()) && !p.document()).map(PanelState::id).findFirst().orElse(null);
    }

    /** Fixed plan usage, shown just before a picture, so the status bar carries no account's figures. */
    private static void demoUsage(StateLoop loop) {
        long now = System.currentTimeMillis();
        loop.dispatch(new Intent.PlanUsageRead(new PlanUsage(List.of(new PlanUsage.Window("Session", 23, "4:10pm"),
                new PlanUsage.Window("Week", 31, "Nov 2 at 9am")), now, "")));
        loop.dispatch(new Intent.PlanUsageRead(new PlanUsage(PlanUsage.CODEX, List.of(new PlanUsage.Window("Session", 8, "5:40pm"),
                new PlanUsage.Window("Week", 12, "Nov 3")), now, "")));
    }

    private static void settle() throws Exception {
        Thread.sleep(1_200);
        for (int i = 0; i < 3; i++) {
            SwingUtilities.invokeAndWait(() -> {
            });
        }
    }

    /** The window painted at twice its size. */
    private static void shoot(StateLoop loop, Path file) throws Exception {
        demoUsage(loop);
        settle();
        SwingUtilities.invokeAndWait(() -> {
            for (Frame f : Frame.getFrames()) {
                if (f instanceof JFrame jf && f.isVisible()) {
                    int w = jf.getRootPane().getWidth();
                    int h = jf.getRootPane().getHeight();
                    BufferedImage img = new BufferedImage(2 * w, 2 * h, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = img.createGraphics();
                    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.scale(2, 2);
                    jf.getRootPane().paint(g);
                    g.dispose();
                    try {
                        ImageIO.write(img, "png", file.toFile());
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            }
        });
    }

    private static void require(Object condition, String message) {
        if (condition == null || Boolean.FALSE.equals(condition)) {
            System.err.println(message);
            System.exit(1);
        }
    }
}
