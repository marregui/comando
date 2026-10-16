package dev.marregui.comando.app;

import dev.marregui.comando.claude.ClaudeSettings;
import dev.marregui.comando.core.dispatch.Target;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.ApprovalDecision;
import dev.marregui.comando.core.model.ApprovalKind;
import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.LowerTab;
import dev.marregui.comando.core.view.Layout;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;

import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
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
        sampleFolders(home);
        AppConfig config = new AppConfig(home, ClaudeSettings.findExecutable(),
                ClaudeSettings.defaultProjectsDir(), true);
        App app = new App(config, "snapshot", () -> {
        });
        app.start();
        StateLoop loop = app.loop();
        Thread.sleep(2500);
        shoot(out.resolve("01-start.png"));
        // a panel's file edited outside comando, as by another editor or Claude's Edit tool, shows in its panel
        Files.writeString(home.resolve("panels").resolve("scratch.md"), "-- Edited outside comando, reloaded within a second.\nSELECT now();\n");
        Thread.sleep(2500);
        shoot(out.resolve("01b-reloaded-from-file.png"));

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
        loop.dispatch(new Intent.Arrange(new Layout(Layout.Toggle.HIDE, Layout.Toggle.KEEP, Double.NaN)));
        Thread.sleep(2800);
        shoot(out.resolve("02b-sidebar-collapsed.png"));
        loop.dispatch(new Intent.Arrange(new Layout(Layout.Toggle.SHOW, Layout.Toggle.KEEP, Double.NaN)));
        Thread.sleep(2800);
        // the focused panel's Markdown preview, switched on with its header's Preview button, and back with Edit; before
        // the Files tab
        click("Preview");
        Thread.sleep(800);
        shoot(out.resolve("02d-markdown-preview.png"));
        click("Edit");
        // the sidebar's Files tab, clicked as a user would: the folders as rows, the first opened as by a click
        selectSidebarTab("Files");
        SwingUtilities.invokeAndWait(() -> {
            JTree files = tree("Files");
            if (files != null) {
                files.expandRow(0);
            }
        });
        // opening the row opens no panel; its tree is read meanwhile
        await(loop, st -> rowShown("Files", "ALPHA.md"), 10_000);
        shoot(out.resolve("02c-sidebar-files.png"));
        // its filter, typed as a user would, then cleared; it applies on the event thread: done when the call returns
        SwingUtilities.invokeAndWait(() -> filesFilter().setText("lane"));
        shoot(out.resolve("02c2-sidebar-files-filter.png"));
        SwingUtilities.invokeAndWait(() -> filesFilter().setText(""));
        // the read-only memory: its index leads it, and a double click opens it rendered with no Edit switch
        SwingUtilities.invokeAndWait(() -> {
            JTree files = tree("Files");
            if (files != null) {
                files.collapseRow(0);
                files.expandRow(1);
                files.setSelectionRow(1);
            }
        });
        await(loop, st -> rowShown("Files", "MEMORY.md"), 10_000);
        SwingUtilities.invokeAndWait(() -> {
            JTree files = tree("Files");
            if (files != null) {
                Rectangle row = files.getRowBounds(2);
                files.dispatchEvent(new MouseEvent(files, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0,
                        row.x + 4, row.y + row.height / 2, 2, false, MouseEvent.BUTTON1));
            }
        });
        await(loop, st -> st.panels().stream().anyMatch(p -> p.title().equals("MEMORY")), 10_000);
        shoot(out.resolve("02c3-sidebar-files-memory.png"));
        selectSidebarTab("Sessions");
        // files opened from the Files tab: a Markdown document opens rendered, its code in the editors' colours,
        // and a source file opens as text coloured as its language
        Path docs = Files.createTempDirectory("comando-snapshot-docs");
        Path plan = Files.writeString(docs.resolve("LANE-PLAN.md"), """
                # Lane plan

                The **writer** owns the file; readers take a *stamp* first.

                | Step | Owner |
                |---|---|
                | read | EDT |
                | write | store |

                ```mermaid
                flowchart LR
                    E[EDT] -->|edit| S[store]
                    S -->|write| F[(file)]
                    F -->|outside change| S
                ```

                ```java
                @Override
                public String write(String id, String text) throws IOException {
                    // the temporary file first, then the move
                    int tries = 3;
                    return "done";
                }
                ```
                """);
        Path source = Files.writeString(docs.resolve("Lane.java"), """
                package dev.marregui.lane;

                /** One lane of the writer. */
                public final class Lane implements AutoCloseable {

                    private static final int SLOTS = 0x40;
                    private final String name;

                    @Override
                    public void close() {
                        if (name.isEmpty()) {
                            throw new IllegalStateException("closed twice: " + SLOTS);
                        }
                    }
                }
                """);
        PanelId first = loop.state().focused();
        SwingUtilities.invokeAndWait(() -> app.ui().openFile(plan));
        Thread.sleep(4000); // mmdc draws the diagram in about 1.5 s, and the preview shows it then
        shoot(out.resolve("02e-markdown-document.png"));
        SwingUtilities.invokeAndWait(() -> app.ui().openFile(source));
        Thread.sleep(1500);
        shoot(out.resolve("02f-source-colours.png"));
        // Help's tutorial and log open as documents from their files: the tutorial rendered, the log read-only at its end
        menuItem("Help", "Tutorial");
        showing(loop, "tutorial");
        shoot(out.resolve("02g-help-tutorial.png"));
        // the snapshot logs to the console only: a log file as comando writes one
        Path log = config.logDir().resolve("comando-0.log");
        Files.createDirectories(log.getParent());
        Files.writeString(log, String.join("\n",
                "2026-10-16 08:40:01.120 INFO  [main] dev.marregui.comando.app.App comando starting [version=snapshot]",
                "2026-10-16 08:40:01.410 INFO  [main] dev.marregui.comando.mcp.McpServer mcp server listening [url=http://127.0.0.1:50200/mcp]",
                "2026-10-16 08:40:02.003 INFO  [edt-watchdog] dev.marregui.comando.ui.shell.ComandoUi the EDT was busy for 310 ms [drawing] in=SunToolkit.awtLock comando=Items.fillRound:77",
                "2026-10-16 08:40:05.871 INFO  [claude-comando] dev.marregui.comando.claude.ClaudeAdapter claude started [session=comando, pid=4242, mode=RESUME]",
                ""));
        menuItem("Help", "Show the log");
        showing(loop, "comando-0.log");
        shoot(out.resolve("02h-help-log.png"));
        loop.dispatch(new Intent.FocusPanel(first));
        Thread.sleep(500);
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

    /**
     * Sample folders for the Files tab, written as {@code folders.json} before the start: a scratchpad of two days
     * whose index is {@code ALPHA.md}, a knowledge base with build output the tab leaves out, a read-only memory whose
     * index leads it, and a folder of skills and one of a plugin, so every folder icon shows.
     */
    private static void sampleFolders(Path home) throws Exception {
        Path root = Files.createTempDirectory("comando-snapshot-files");
        Path pad = root.resolve("SCRATCHPAD");
        for (String day : List.of("2026-10-15", "2026-10-16")) {
            Path d = Files.createDirectories(pad.resolve(day));
            Files.writeString(d.resolve("ALPHA.md"), "# " + day + "\n");
            Files.writeString(d.resolve("READ-01-LANE-REVIEW.md"), "# Lane review\n");
            Files.writeString(Files.createDirectories(d.resolve("work")).resolve("LANE-SPEC.md"), "# spec\n");
        }
        Files.writeString(pad.resolve("ALPHA.md"), "# Scratchpad\n");
        Path kb = root.resolve("KB");
        Files.writeString(Files.createDirectories(kb.resolve("architecture")).resolve("00_START_HERE.md"), "# Architecture\n");
        Files.writeString(Files.createDirectories(kb.resolve("build")).resolve("generated.html"), "x");
        Files.writeString(kb.resolve("ALPHA.md"), "# KB\n");
        Path memory = root.resolve("MEMORY");
        Files.writeString(Files.createDirectories(memory.resolve("archive")).resolve("session_log_2026-09.md"), "# September\n");
        Files.writeString(memory.resolve("agent_ledger.md"), "# Agent ledger\n");
        Files.writeString(memory.resolve("MEMORY.md"), "# Memory\n");
        Files.writeString(Files.createDirectories(root.resolve("skills").resolve("reflect")).resolve("SKILL.md"), "# reflect\n");
        Files.writeString(Files.createDirectories(root.resolve("plugin").resolve("bin")).resolve("gradle-serial"), "#!/bin/sh\n");
        Files.writeString(home.resolve("folders.json"), JsonFile.JSON.writeValueAsString(List.of(
                new FolderRoot("Scratchpad", pad.toString(), "ALPHA.md", List.of(), "scratchpad", false),
                new FolderRoot("Memory", memory.toString(), "MEMORY.md", List.of(), "memory", true),
                new FolderRoot("KB", kb.toString(), "ALPHA.md", List.of(), "knowledge", false),
                new FolderRoot("Skills", root.resolve("skills").toString(), "", List.of(), "skills", false),
                new FolderRoot("Plugin", root.resolve("plugin").toString(), "", List.of(), "plugin", false),
                new FolderRoot("Notes", root.resolve("notes").toString(), "", List.of()))));
    }

    /** Whether the visible window's tree named {@code name} shows a row labelled {@code label}; asked on the event thread. */
    private static boolean rowShown(String name, String label) {
        boolean[] shown = new boolean[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                JTree t = tree(name);
                for (int i = 0; t != null && i < t.getRowCount(); i++) {
                    shown[0] |= t.getPathForRow(i).getLastPathComponent() instanceof DefaultMutableTreeNode n
                            && n.getUserObject() instanceof ViewSpec.TreeNode node && node.label().equals(label);
                }
            });
        } catch (Exception e) {
            return false;
        }
        return shown[0];
    }

    /** The Files tab's filter field in the visible window. */
    private static JTextField filesFilter() {
        for (Frame f : Frame.getFrames()) {
            if (f instanceof JFrame jf && f.isVisible()) {
                JTextField t = find(jf.getContentPane(), JTextField.class, x -> "Filter files".equals(x.getAccessibleContext().getAccessibleName()));
                if (t != null) {
                    return t;
                }
            }
        }
        throw new IllegalStateException("no Files filter");
    }

    /** The visible window's tree named {@code name}, or null. */
    private static JTree tree(String name) {
        for (Frame f : Frame.getFrames()) {
            if (f instanceof JFrame jf && f.isVisible()) {
                JTree t = find(jf.getContentPane(), JTree.class, x -> name.equals(x.getAccessibleContext().getAccessibleName()));
                if (t != null) {
                    return t;
                }
            }
        }
        return null;
    }

    private static <T extends Component> T find(Component c, Class<T> type, Predicate<T> match) {
        if (type.isInstance(c) && match.test(type.cast(c))) {
            return type.cast(c);
        }
        if (c instanceof Container k) {
            for (Component child : k.getComponents()) {
                T found = find(child, type, match);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** A Gradle run, a JFR recording read into a table and plots, and jfrq on it. */
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

    static AppState await(StateLoop loop, Predicate<AppState> done, long millis) throws InterruptedException {
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

    /** Clicks the first visible button with this accessible name, as a user would. */
    private static void click(String name) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (Frame f : Frame.getFrames()) {
                if (f instanceof JFrame jf && f.isVisible()) {
                    AbstractButton b = button(jf.getContentPane(), name);
                    if (b != null) {
                        b.doClick();
                        return;
                    }
                }
            }
        });
    }

    private static AbstractButton button(Component c, String name) {
        if (c instanceof AbstractButton b && b.isShowing() && name.equals(b.getAccessibleContext().getAccessibleName())) {
            return b;
        }
        if (c instanceof Container k) {
            for (Component child : k.getComponents()) {
                AbstractButton b = button(child, name);
                if (b != null) {
                    return b;
                }
            }
        }
        return null;
    }

    /** Waits until the panel titled {@code title} is in front, then for the window to render that state. */
    static void showing(StateLoop loop, String title) throws Exception {
        if (await(loop, st -> st.focusedPanel().title().equals(title), 10_000) == null) {
            throw new IllegalStateException("no panel " + title + " within 10 s");
        }
        SwingUtilities.invokeAndWait(() -> {
        });
        SwingUtilities.invokeAndWait(() -> {
        });
    }

    /** Clicks the item at {@code path} in the window's menu bar ("Help", "Tutorial"), as a user would. */
    static void menuItem(String... path) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (Frame f : Frame.getFrames()) {
                if (f instanceof JFrame jf && f.isVisible() && jf.getJMenuBar() != null) {
                    for (int i = 0; i < jf.getJMenuBar().getMenuCount(); i++) {
                        JMenu m = jf.getJMenuBar().getMenu(i);
                        if (m != null && path[0].equals(m.getText()) && click(m, path, 1)) {
                            return;
                        }
                    }
                }
            }
            throw new IllegalStateException("no menu item " + String.join(" ▸ ", path));
        });
    }

    private static boolean click(JMenu menu, String[] path, int depth) {
        for (Component c : menu.getMenuComponents()) {
            if (c instanceof JMenuItem mi && path[depth].equals(mi.getText())) {
                if (depth == path.length - 1) {
                    mi.doClick();
                    return true;
                }
                return mi instanceof JMenu sub && click(sub, path, depth + 1);
            }
        }
        return false;
    }

    /** Brings a tab of the sidebar to the front by clicking its header, as a user would. */
    private static void selectSidebarTab(String title) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (Frame f : Frame.getFrames()) {
                if (f instanceof JFrame jf && f.isVisible()) {
                    JToggleButton header = header(jf.getContentPane(), title);
                    if (header != null) {
                        header.doClick();
                    }
                }
            }
        });
    }

    private static JToggleButton header(Component c, String title) {
        if (c instanceof JToggleButton b && title.equals(b.getText())) {
            return b;
        }
        if (c instanceof Container k) {
            for (Component child : k.getComponents()) {
                JToggleButton b = header(child, title);
                if (b != null) {
                    return b;
                }
            }
        }
        return null;
    }

    static void shoot(Path file) throws Exception {
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
