package dev.marregui.comando.app;

import dev.marregui.comando.claude.ClaudeSettings;
import dev.marregui.comando.core.dispatch.Target;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.ApprovalDecision;
import dev.marregui.comando.core.model.ApprovalKind;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.LowerTab;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

/**
 * Starts comando on a scratch data root, drives it with intents and paints the window to PNG files:
 * a visual check that needs no screen-recording permission.
 *
 * <pre>./gradlew :app:snapshot -Pout=/tmp/shots [-Plive=true]</pre>
 * With {@code live}, a real Claude Code session (haiku) is started, sent a prompt that uses comando's
 * tools, and its approvals are allowed, end to end.
 */
public final class Snapshot {

    private Snapshot() {
    }

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "build/snapshots");
        boolean live = args.length > 1 && Boolean.parseBoolean(args[1]);
        Files.createDirectories(out);
        Path home = Files.createTempDirectory("comando-snapshot");
        AppConfig config = new AppConfig(home, ClaudeSettings.findExecutable(),
                ClaudeSettings.defaultProjectsDir(), true);
        App app = new App(config, "snapshot", () -> {
        });
        app.start();
        StateLoop loop = app.loop();
        Thread.sleep(2500);
        shoot(out.resolve("01-start.png"));

        String repo = Path.of(System.getProperty("user.dir")).getParent().toString();
        loop.dispatch(new Intent.SaveSession("", SessionSpec.fresh("comando", repo).withModel("haiku")));
        loop.dispatch(new Intent.BindSession(loop.state().focused(), "comando"));
        loop.dispatch(new Intent.ShowView("comando", "primes", new ViewSpec.Table(List.of("n", "prime", "gap"),
                List.of(List.of("1", "2", "null"), List.of("2", "3", "1"), List.of("3", "5", "2"), List.of("4", "7", "2"))), Placement.LOWER));
        double[] x = new double[2000];
        double[] y = new double[2000];
        double[] z = new double[2000];
        long t0 = 1_760_000_000_000L;
        for (int i = 0; i < x.length; i++) {
            x[i] = t0 + i * 60_000L;
            y[i] = 40 + 15 * Math.sin(i / 40.0) + (i % 300 == 0 ? 45 : 0);
            z[i] = 30 + 5 * Math.cos(i / 25.0);
        }
        loop.dispatch(new Intent.ShowView("comando", "delay", new ViewSpec.Plot("time", "delay (s)", true,
                List.of(new ViewSpec.Series("line-3", x, y), new ViewSpec.Series("line-4", x, z))), Placement.LOWER));
        loop.dispatch(new Intent.ShowView("comando", "Lane.java", new ViewSpec.Diff("db/Lane.java",
                "void cancel(long id) {\n    r.statement().cancel();\n}\n",
                "void cancel(long id) {\n    cancellers.execute(() -> r.statement().cancel());\n    timer.schedule(abort, 2, SECONDS);\n}\n"),
                Placement.SIDE));
        loop.dispatch(new Intent.ApprovalRequested(new Approval(1L, "comando", ApprovalKind.TOOL, "Bash",
                "{\"command\":\"./gradlew test\",\"description\":\"Run the tests\"}", "./gradlew test", System.currentTimeMillis())));
        loop.dispatch(new Intent.ApprovalRequested(new Approval(2L, "comando", ApprovalKind.QUESTION, "AskUserQuestion",
                "{\"questions\":[{\"question\":\"Which toolkit?\",\"options\":[{\"label\":\"Swing\"},{\"label\":\"JavaFX\"}]}]}",
                "Which toolkit?", System.currentTimeMillis())));
        loop.dispatch(new Intent.SelectLowerTab(LowerTab.APPROVALS));
        Thread.sleep(1500);
        shoot(out.resolve("02-approvals-and-side-diff.png"));
        long plotId = loop.state().views().stream().filter(v -> v.title().equals("delay")).findFirst().orElseThrow().id();
        loop.dispatch(new Intent.SelectLowerTab(LowerTab.view(plotId)));
        Thread.sleep(800);
        shoot(out.resolve("03-plot.png"));
        loop.dispatch(new Intent.SelectLowerTab(LowerTab.TERMINAL));
        Thread.sleep(3000);
        shoot(out.resolve("03b-terminal.png"));
        loop.dispatch(new Intent.ApprovalAnswered(1L, new ApprovalDecision.Deny("snapshot")));
        loop.dispatch(new Intent.ApprovalAnswered(2L, new ApprovalDecision.Deny("snapshot")));

        if (args.length > 2 && !args[2].isEmpty()) {
            storedConversation(loop, out, args[2]);
        }
        if (args.length > 3 && Boolean.parseBoolean(args[3])) {
            tools(loop, out);
        }
        if (live) {
            liveSession(loop, out);
        }
        app.close();
        System.out.println("intent log in " + config.logDir());
        System.out.println("snapshots in " + out.toAbsolutePath());
        System.exit(0);
    }

    /** A Gradle run, a JFR recording read into a table and plots, jfrq on it, and the scratchpad tree. */
    private static void tools(StateLoop loop, Path out) throws Exception {
        String jfr = System.getProperty("user.home") + "/workspace/jfr";
        loop.dispatch(new Intent.SaveSession("", SessionSpec.fresh("jfr", jfr)));
        loop.dispatch(new Intent.BindSession(loop.state().focused(), "jfr"));
        loop.dispatch(new Intent.RunGradle(null, "help --quiet"));
        await(loop, st -> st.views().stream().anyMatch(v -> v.spec() instanceof ViewSpec.Console c
                && c.command().startsWith("gradle") && c.state() != ViewSpec.RunState.RUNNING), 120_000);
        Thread.sleep(800);
        shoot(out.resolve("08-gradle.png"));
        String recording = System.getProperty("user.home") + "/jfr-round2/idle0.jfr";
        loop.dispatch(new Intent.OpenRecording(recording));
        await(loop, st -> st.views().stream().anyMatch(v -> v.title().startsWith("cpu · ")), 60_000);
        Thread.sleep(800);
        shoot(out.resolve("09-recording-cpu.png"));
        loop.dispatch(new Intent.RunJfrq(recording, "health --top 5"));
        await(loop, st -> st.views().stream().anyMatch(v -> v.spec() instanceof ViewSpec.Console c
                && c.command().startsWith("jfrq") && c.state() != ViewSpec.RunState.RUNNING), 120_000);
        Thread.sleep(800);
        shoot(out.resolve("10-jfrq.png"));
        loop.dispatch(new Intent.BrowseScratchpad());
        await(loop, st -> st.views().stream().anyMatch(v -> v.title().equals("scratchpad")), 20_000);
        Thread.sleep(800);
        shoot(out.resolve("11-scratchpad.png"));
    }

    /** A stored conversation shown in the transcript, scrolled to its end, then a find. */
    private static void storedConversation(StateLoop loop, Path out, String claudeSessionId) throws Exception {
        loop.dispatch(new Intent.SaveSession("", SessionSpec.imported("history", System.getProperty("user.home"), claudeSessionId)));
        loop.dispatch(new Intent.BindSession(loop.state().focused(), "history"));
        loop.dispatch(new Intent.SelectLowerTab(LowerTab.SESSION));
        Thread.sleep(4000);
        shoot(out.resolve("05-stored-conversation.png"));
    }

    /** A real turn: Claude places a table through comando's MCP tool; every approval is allowed. */
    private static void liveSession(StateLoop loop, Path out) throws Exception {
        loop.addListener((prev, next) -> {
            for (Approval a : next.approvals()) {
                if (prev.approval(a.id()).isEmpty()) {
                    System.out.println("approving " + a.toolName() + ": " + a.summary());
                    loop.dispatch(new Intent.ApprovalAnswered(a.id(), ApprovalDecision.allowOnce()));
                }
            }
        });
        loop.dispatch(new Intent.RunText(loop.state().focused(),
                "Call the mcp__comando__ui_show_table tool once to show a table titled 'squares' with columns n and square for n = 1..5. "
                        + "Then reply with the single word DONE.", Target.CLAUDE));
        AppState s = await(loop, st -> st.views().stream().anyMatch(v -> v.title().equals("squares")), 120_000);
        System.out.println(s == null ? "no squares view within 120 s" : "squares view placed by Claude");
        await(loop, st -> st.session("comando").map(x -> !x.isBusy()).orElse(false), 120_000);
        loop.dispatch(new Intent.SelectLowerTab(LowerTab.SESSION));
        Thread.sleep(1500);
        shoot(out.resolve("04-live-session.png"));
        loop.dispatch(new Intent.StopSession("comando"));
        await(loop, st -> st.session("comando").map(x -> x.status() == Status.CLOSED).orElse(true), 15_000);
    }

    private static AppState await(StateLoop loop, Predicate<AppState> done, long millis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            AppState s = loop.state();
            if (done.test(s)) {
                return s;
            }
            Thread.sleep(200);
        }
        return null;
    }

    private static void shoot(Path file) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (Frame f : Frame.getFrames()) {
                if (f instanceof JFrame jf && f.isVisible()) {
                    BufferedImage img = new BufferedImage(jf.getRootPane().getWidth(), jf.getRootPane().getHeight(), BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = img.createGraphics();
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
}
