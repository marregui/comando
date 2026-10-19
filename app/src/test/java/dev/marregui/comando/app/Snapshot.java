package dev.marregui.comando.app;

import dev.marregui.comando.claude.ClaudeSettings;
import dev.marregui.comando.core.dispatch.Target;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.ApprovalDecision;
import dev.marregui.comando.core.model.ApprovalKind;
import dev.marregui.comando.core.model.ChangeAsk;
import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.RepoStatus;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.LowerTab;
import dev.marregui.comando.core.state.SessionInfo;
import dev.marregui.comando.core.view.Layout;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.dialogs.folders.FoldersDialog;
import dev.marregui.comando.ui.kit.text.HtmlPane;
import dev.marregui.comando.ui.kit.text.LocalImages;

import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.BadLocationException;
import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
        Path sampleRepo = sampleFolders(home);
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
        // the Sessions tab: a running session with its context past the checkpoint's threshold, the mouse over its row
        loop.dispatch(new Intent.SaveSession("", SessionSpec.fresh("edge", repo).withModel("opus").withEffort("high")));
        loop.dispatch(new Intent.SessionStarted("edge"));
        loop.dispatch(new Intent.SessionInit("edge", new SessionInfo("e41164bc-160c-4e3e-b43b-3218489da4e1", "claude-opus-5-5", repo,
                "2.1.302", "default", List.of())));
        loop.dispatch(new Intent.SessionTurnEnded("edge", 0, 0.0, 128_000L, 200_000L, false, ""));
        loop.dispatch(new Intent.SessionBranch("edge", "main"));
        selectSidebarTab("Sessions");
        await(loop, st -> st.session("edge").map(e -> e.contextWindow() > 0).orElse(false), 5_000);
        SwingUtilities.invokeAndWait(() -> {
            JList<?> sessions = list("Sessions");
            if (sessions != null) {
                Rectangle row = sessions.getCellBounds(0, 0);
                sessions.dispatchEvent(new MouseEvent(sessions, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0,
                        row.x + 80, row.y + 8, 0, false));
            }
        });
        // the shot paints on the event thread, after the hover: nothing to wait for
        shoot(out.resolve("02a-sidebar-sessions.png"));
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
        // the light editors: the preview, then the text, then back to dark for the shots that follow
        // the switch redraws on the event thread: done when the click returns
        click("Light editors");
        shoot(out.resolve("02d2-light-preview.png"));
        click("Edit");
        shoot(out.resolve("02d3-light-editor.png"));
        click("Dark editors");
        // the sidebar's Files tab, clicked as a user would: the folders as rows, the first opened as by a click
        selectSidebarTab("Files");
        SwingUtilities.invokeAndWait(() -> {
            JTree files = tree("Files");
            if (files != null) {
                files.expandRow(0);
            }
        });
        // opening the row opens no panel; its tree is read meanwhile
        await(loop, st -> rowShown("Files", "INDEX.md"), 10_000);
        shoot(out.resolve("02c-sidebar-files.png"));
        // its filter, typed as a user would, then cleared; it applies on the event thread: done when the call returns
        SwingUtilities.invokeAndWait(() -> filesFilter().setText("lane"));
        shoot(out.resolve("02c2-sidebar-files-filter.png"));
        SwingUtilities.invokeAndWait(() -> filesFilter().setText(""));
        // the memory: its index leads it, and a double click opens it rendered, to edit like any file
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
        // a report with the pictures beside it, a PNG in images/ and an SVG in img/, dark and then light
        Path report = reportWithPictures(docs);
        SwingUtilities.invokeAndWait(() -> app.ui().openFile(report));
        picturesRead(docs.resolve("images/p99-latency.png"), docs.resolve("img/write-path.svg"));
        shoot(out.resolve("02e2-markdown-pictures.png"));
        click("Light editors");
        shoot(out.resolve("02e3-markdown-pictures-light.png"));
        click("Dark editors");
        // a link to a heading of another document, double-clicked in the report: the document opens at that heading
        StringBuilder rollout = new StringBuilder("# Rollout\n\n");
        for (int i = 1; i <= 6; i++) {
            rollout.append("## ").append(i).append(". Stage ").append(i).append("\n\n");
            for (int j = 0; j < 8; j++) {
                rollout.append("Stage ").append(i).append(", check ").append(j).append(": the lane holds its p99 under 100 ms.\n\n");
            }
        }
        rollout.append("## 7. Rollback\n\nThe old writer comes back with one flag; nothing in the file changes.\n\n");
        for (int j = 0; j < 30; j++) {
            rollout.append("Rollback check ").append(j).append(".\n\n");
        }
        Files.writeString(docs.resolve("ROLLOUT.md"), rollout);
        followLink("Lane report", "ROLLOUT.md#7-rollback");
        showing(loop, "ROLLOUT");
        shoot(out.resolve("02e4-link-to-a-heading.png"));
        SwingUtilities.invokeAndWait(() -> app.ui().openFile(source));
        Thread.sleep(1500);
        shoot(out.resolve("02f-source-colours.png"));
        // Help's tutorial and log open as documents from their files: the tutorial rendered, the log read-only at its end
        menuItem("Tools", "Help", "Tutorial");
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
        menuItem("Tools", "Help", "Show the log");
        showing(loop, "comando-0.log");
        shoot(out.resolve("02h-help-log.png"));
        // Help's two sheets, each in the side pane: the keys, then Markdown
        for (String[] sheet : new String[][]{{"Keyboard shortcuts", "Keyboard shortcuts", "02i-help-keys.png"},
                {"Markdown sheet", "Markdown", "02j-help-markdown.png"}}) {
            menuItem("Tools", "Help", sheet[0]);
            await(loop, st -> st.views().stream().anyMatch(v -> v.title().equals(sheet[1])), 5_000);
            // the view is drawn on the event thread after the state that holds it: done once the queue is through
            SwingUtilities.invokeAndWait(() -> { });
            shoot(out.resolve(sheet[2]));
        }
        // About comando, as the application menu opens it: the card alone, at the Retina scale it is seen at
        SwingUtilities.invokeAndWait(() -> app.ui().showAbout());
        shootDialog("About comando", out.resolve("02k-about.png"));
        // a git repository among the folders: the Files tab in git's colours, the Changes view beside the editor, and a
        // changed file compared side by side in the window that floats over comando's
        selectSidebarTab("Files");
        SwingUtilities.invokeAndWait(() -> {
            JTree files = tree("Files");
            for (int i = 0; files != null && i < files.getRowCount(); i++) {
                if (files.getPathForRow(i).getLastPathComponent() instanceof DefaultMutableTreeNode n
                        && n.getUserObject() instanceof ViewSpec.TreeNode node && node.label().equals("Repo")) {
                    files.expandRow(i);
                }
            }
        });
        await(loop, st -> rowShown("Files", "src"), 10_000);
        SwingUtilities.invokeAndWait(() -> {
            JTree files = tree("Files");
            for (int i = 0; files != null && i < files.getRowCount() && i < 60; i++) {
                if (files.getPathForRow(i).getLastPathComponent() instanceof DefaultMutableTreeNode n
                        && n.getUserObject() instanceof ViewSpec.TreeNode node && !node.children().isEmpty()) {
                    files.expandRow(i);
                }
            }
        });
        // the repositories window: the list, the selected repository's fields, its facts
        SwingUtilities.invokeLater(() -> findCommand("files.repositories"));
        await(loop, st -> dialogShown("Repositories"), 10_000);
        SwingUtilities.invokeAndWait(() -> selectInDialogList("Repositories", "Repo"));
        flushEdt();
        flushEdt();
        shootDialog("Repositories", out.resolve("02k2-folders-window.png"));
        // the repository's row: git icon and flag; a double click opens the Changes view and leaves the row open
        await(loop, st -> rowExpanded("Files", "Repo") && repoMarked(), 15_000);
        SwingUtilities.invokeAndWait(() -> doubleClickRow(tree("Files"), "Repo"));
        await(loop, st -> st.views().stream().anyMatch(v -> v.spec() instanceof ViewSpec.Changes), 10_000);
        if (!rowExpanded("Files", "Repo")) {
            throw new IllegalStateException("the double click closed the repository's row");
        }
        await(loop, st -> rowShown(ViewSpec.Changes.TITLE, "Lane.java"), 15_000);
        flushEdt();
        shoot(out.resolve("02l-changes.png"));
        loop.dispatch(new Intent.OpenChange(sampleRepo.toString(),
                new RepoStatus.FileChange("src/main/java/demo/Lane.java", "", RepoStatus.Kind.MODIFIED), ChangeAsk.OPEN));
        String diffTitle = "src/main/java/demo/Lane.java — compared with HEAD";
        await(loop, st -> dialogShown(diffTitle), 10_000);
        // a read again of a file the window no longer shows never takes it back
        loop.dispatch(new Intent.OpenChange(sampleRepo.toString(),
                new RepoStatus.FileChange("src/main/java/demo/Ring.java", "", RepoStatus.Kind.ADDED), ChangeAsk.REFRESH));
        loop.dispatch(new Intent.OpenChange(sampleRepo.toString(),
                new RepoStatus.FileChange("src/main/java/demo/Lane.java", "", RepoStatus.Kind.MODIFIED), ChangeAsk.REFRESH));
        await(loop, st -> dialogShown(diffTitle), 10_000);
        // the diff scrolls to its first difference in a task it posts after the layout that sizes the texts
        flushEdt();
        flushEdt();
        shootDialog(diffTitle, out.resolve("02m-diff-window.png"));
        // the comparison's theme button switches every editor to the light theme, and the comparison with them
        loop.dispatch(new Intent.OpenChange(sampleRepo.toString(),
                new RepoStatus.FileChange("src/main/java/demo/Lane.java", "", RepoStatus.Kind.MODIFIED), ChangeAsk.OPEN));
        await(loop, st -> dialogShown(diffTitle), 10_000);
        clickInDialog(diffTitle, "Light editors");
        flushEdt();
        flushEdt();
        shootDialog(diffTitle, out.resolve("02m2-diff-window-light.png"));
        loop.dispatch(new Intent.OpenChange(sampleRepo.toString(),
                new RepoStatus.FileChange("src/main/java/demo/Lane.java", "", RepoStatus.Kind.MODIFIED), ChangeAsk.OPEN));
        await(loop, st -> dialogShown(diffTitle), 10_000);
        clickInDialog(diffTitle, "Dark editors");
        flushEdt();
        // a Markdown file's two sides rendered, what changed shaded as in the texts
        loop.dispatch(new Intent.OpenChange(sampleRepo.toString(), new RepoStatus.FileChange("README.md", "", RepoStatus.Kind.MODIFIED), ChangeAsk.OPEN));
        String readmeTitle = "README.md — compared with HEAD";
        if (await(loop, st -> dialogShown(readmeTitle), 15_000) == null) {
            List<String> titles = new ArrayList<>();
            SwingUtilities.invokeAndWait(() -> {
                for (Window w : Window.getWindows()) {
                    if (w instanceof JDialog d && d.isVisible()) {
                        titles.add(d.getTitle());
                    }
                }
            });
            throw new IllegalStateException("no README comparison; dialogs shown: " + titles);
        }
        flushEdt();
        clickInDialog(readmeTitle, "Show rendered");
        flushEdt();
        flushEdt();
        shootDialog(readmeTitle, out.resolve("02m3-diff-rendered.png"));
        loop.state().views().stream().filter(v -> v.spec() instanceof ViewSpec.Changes).findFirst()
                .ifPresent(v -> loop.dispatch(new Intent.CloseView(v.id())));
        selectSidebarTab("Sessions");
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
     * Waits until the pictures are read and the preview that asked first has been redrawn with them: its redraw is
     * queued on the event thread before these callbacks run.
     */
    private static void picturesRead(Path... files) throws Exception {
        for (Path file : files) {
            CountDownLatch read = new CountDownLatch(1);
            if (LocalImages.current().picture(file, read::countDown) instanceof LocalImages.Picture.Pending
                    && !read.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("not read in 10 s: " + file);
            }
        }
        SwingUtilities.invokeAndWait(() -> {
        });
        SwingUtilities.invokeAndWait(() -> {
        });
    }

    /** A Markdown report as Claude writes one, beside a PNG chart in images/ and an SVG diagram in img/. */
    private static Path reportWithPictures(Path docs) throws IOException {
        Path images = Files.createDirectories(docs.resolve("images"));
        Path img = Files.createDirectories(docs.resolve("img"));
        BufferedImage chart = new BufferedImage(520, 170, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = chart.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x2B2D30));
        g.fillRect(0, 0, 520, 170);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        int[] p99 = {123, 114, 36, 29, 27};
        String[] runs = {"base", "lock", "lane", "lane+", "final"};
        for (int i = 0; i < p99.length; i++) {
            g.setColor(i < 2 ? new Color(0xE06C75) : new Color(0x56B6C2));
            g.fillRoundRect(40 + i * 95, 140 - p99[i], 60, p99[i], 6, 6);
            g.setColor(new Color(0xBCBEC4));
            g.drawString(runs[i], 45 + i * 95, 160);
        }
        g.dispose();
        ImageIO.write(chart, "png", images.resolve("p99-latency.png").toFile());
        Files.writeString(img.resolve("write-path.svg"), """
                <svg xmlns="http://www.w3.org/2000/svg" width="460" height="120" viewBox="0 0 460 120" font-family="Helvetica" font-size="15">
                  <defs><linearGradient id="g" x1="0" x2="1"><stop offset="0" stop-color="#4f7cff"/><stop offset="1" stop-color="#9b5de5"/></linearGradient>
                    <marker id="a" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto"><path d="M0,0 L10,5 L0,10 z" fill="#888"/></marker></defs>
                  <rect x="10" y="35" width="110" height="50" rx="10" fill="url(#g)"/><text x="65" y="65" fill="white" text-anchor="middle">EDT</text>
                  <rect x="175" y="35" width="110" height="50" rx="10" fill="url(#g)"/><text x="230" y="65" fill="white" text-anchor="middle">store</text>
                  <rect x="340" y="35" width="110" height="50" rx="10" fill="url(#g)"/><text x="395" y="65" fill="white" text-anchor="middle">file</text>
                  <line x1="120" y1="60" x2="172" y2="60" stroke="#888" stroke-width="2" marker-end="url(#a)"/>
                  <line x1="285" y1="60" x2="337" y2="60" stroke="#888" stroke-width="2" marker-end="url(#a)"/>
                  <text x="146" y="28" fill="#888" text-anchor="middle" font-size="12">edit</text><text x="311" y="28" fill="#888" text-anchor="middle" font-size="12">write</text>
                </svg>
                """);
        return Files.writeString(docs.resolve("LANE-REPORT.md"), """
                # Lane report

                The lane took the p99 from 410 ms to 90 ms.

                ![p99 latency per run](images/p99-latency.png)

                The write path, as the store sees it:

                ![write path](img/write-path.svg)

                A picture that is not there: ![the old chart](images/old.png)
                """);
    }

    /**
     * Sample folders for the Files tab, written as {@code folders.json} before the start: a scratchpad of two days
     * whose index is {@code INDEX.md}, a knowledge base with build output the tab leaves out, a memory whose
     * index leads it, a folder of skills and one of a plugin, so every folder icon shows, and a git repository with
     * changes; answers the repository.
     */
    private static Path sampleFolders(Path home) throws Exception {
        Path root = Files.createTempDirectory("comando-snapshot-files");
        Path repo = sampleRepo(root.resolve("repo"));
        Path pad = root.resolve("SCRATCHPAD");
        for (String day : List.of("2026-10-15", "2026-10-16")) {
            Path d = Files.createDirectories(pad.resolve(day));
            Files.writeString(d.resolve("INDEX.md"), "# " + day + "\n");
            Files.writeString(d.resolve("READ-01-LANE-REVIEW.md"), "# Lane review\n");
            Files.writeString(Files.createDirectories(d.resolve("work")).resolve("LANE-SPEC.md"), "# spec\n");
        }
        Files.writeString(pad.resolve("INDEX.md"), "# Scratchpad\n");
        Path kb = root.resolve("KB");
        Files.writeString(Files.createDirectories(kb.resolve("architecture")).resolve("00_START_HERE.md"), "# Architecture\n");
        Files.writeString(Files.createDirectories(kb.resolve("build")).resolve("generated.html"), "x");
        Files.writeString(kb.resolve("00_START_HERE.md"), "# KB\n");
        Path memory = root.resolve("MEMORY");
        Files.writeString(Files.createDirectories(memory.resolve("archive")).resolve("session_log_2026-09.md"), "# September\n");
        Files.writeString(memory.resolve("agent_ledger.md"), "# Agent ledger\n");
        Files.writeString(memory.resolve("MEMORY.md"), "# Memory\n");
        Files.writeString(Files.createDirectories(root.resolve("skills").resolve("reflect")).resolve("SKILL.md"), "# reflect\n");
        Files.writeString(Files.createDirectories(root.resolve("plugin").resolve("bin")).resolve("gradle-serial"), "#!/bin/sh\n");
        Files.writeString(home.resolve("folders.json"), JsonFile.JSON.writeValueAsString(List.of(
                new FolderRoot("Scratchpad", pad.toString(), "INDEX.md", List.of(), "scratchpad"),
                new FolderRoot("Memory", memory.toString(), "MEMORY.md", List.of(), "memory"),
                new FolderRoot("KB", kb.toString(), "00_START_HERE.md", List.of(), "knowledge"),
                new FolderRoot("Skills", root.resolve("skills").toString(), "", List.of(), "skills"),
                new FolderRoot("Plugin", root.resolve("plugin").toString(), "", List.of(), "plugin"),
                new FolderRoot("Notes", root.resolve("notes").toString(), "", List.of()),
                new FolderRoot("Repo", repo.toString(), "", List.of()).asRepository())));
        return repo;
    }

    /** A repository whose working tree has a file changed, one added, one deleted and one untracked, and an ignored folder. */
    private static Path sampleRepo(Path repo) throws Exception {
        Path src = Files.createDirectories(repo.resolve("src/main/java/demo"));
        StringBuilder lane = new StringBuilder("package demo;\n\n/** A lane of work: one writer, many readers. */\nfinal class Lane {\n\n");
        for (int i = 1; i <= 12; i++) {
            lane.append("    private long stamp").append(i).append(";\n");
        }
        lane.append("\n    long read() {\n        long s = stamp1;\n        return s + stamp2;\n    }\n\n    void write(long v) {\n")
                .append("        stamp1 = v;\n        stamp2 = v * 2;\n    }\n}\n");
        Files.writeString(src.resolve("Lane.java"), lane);
        Files.writeString(src.resolve("Old.java"), "package demo;\n\nfinal class Old {\n}\n");
        Files.writeString(repo.resolve(".gitignore"), "dist/\n");
        Files.writeString(repo.resolve("README.md"), "# Demo\n\nA small lane of work.\n\n- one writer\n- many readers\n\n"
                + "| part | owner |\n|---|---|\n| lane | demo |\n");
        git(repo, "init", "-q");
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m", "first");
        String changed = lane.toString()
                .replace("    private long stamp7;\n", "")
                .replace("        return s + stamp2;", "        // the reader takes its stamp before the data\n        return s + stamp2 + stamp3;")
                .replace("        stamp2 = v * 2;", "        stamp2 = v << 1;");
        Files.writeString(src.resolve("Lane.java"), changed);
        Files.writeString(src.resolve("Ring.java"), "package demo;\n\nfinal class Ring {\n}\n");
        Files.writeString(repo.resolve("README.md"), "# Demo\n\nA small lane of work, read by many.\n\n- one writer\n- many readers\n"
                + "- a stamp per read\n\n| part | owner |\n|---|---|\n| lane | demo team |\n");
        git(repo, "add", "src/main/java/demo/Ring.java");
        Files.delete(src.resolve("Old.java"));
        Files.writeString(Files.createDirectories(repo.resolve("notes")).resolve("todo.md"), "# To do\n");
        Files.writeString(Files.createDirectories(repo.resolve("dist")).resolve("bundle.js"), "x");
        return repo;
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "user.name=snapshot", "-c", "user.email=s@s", "-c", "commit.gpgsign=false",
                "-c", "init.defaultBranch=main", "-C", dir.toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed");
        }
    }

    /** Whether the row labelled {@code label} of the tree named {@code name} is open; asked on the event thread. */
    private static boolean rowExpanded(String name, String label) {
        boolean[] open = new boolean[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                JTree t = tree(name);
                for (int i = 0; t != null && i < t.getRowCount(); i++) {
                    if (t.getPathForRow(i).getLastPathComponent() instanceof DefaultMutableTreeNode n
                            && n.getUserObject() instanceof ViewSpec.TreeNode node && node.label().equals(label)) {
                        open[0] = t.isExpanded(i);
                    }
                }
            });
        } catch (Exception e) {
            return false;
        }
        return open[0];
    }

    /** Whether the Files tab knows the sample repository, so its row has the git icon and the flag. */
    private static boolean repoMarked() {
        boolean[] marked = new boolean[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                JTree t = tree("Files");
                for (int i = 0; t != null && i < t.getRowCount(); i++) {
                    if (t.getPathForRow(i).getLastPathComponent() instanceof DefaultMutableTreeNode n
                            && n.getUserObject() instanceof ViewSpec.TreeNode node && node.label().equals("Repo")) {
                        Rectangle r = t.getRowBounds(i);
                        Component c = t.getCellRenderer().getTreeCellRendererComponent(t, n, false, true, false, i, false);
                        // the flag rides in a row of its own after the name
                        marked[0] = r != null && c instanceof JPanel;
                    }
                }
            });
        } catch (Exception e) {
            return false;
        }
        return marked[0];
    }

    /** A user's double click on the row labelled {@code label}: press, release and click, twice. */
    private static void doubleClickRow(JTree t, String label) {
        for (int i = 0; i < t.getRowCount(); i++) {
            if (t.getPathForRow(i).getLastPathComponent() instanceof DefaultMutableTreeNode n
                    && n.getUserObject() instanceof ViewSpec.TreeNode node && node.label().equals(label)) {
                Rectangle r = t.getRowBounds(i);
                int x = r.x + 30;
                int y = r.y + r.height / 2;
                for (int count = 1; count <= 2; count++) {
                    long now = System.currentTimeMillis();
                    t.dispatchEvent(new MouseEvent(t, MouseEvent.MOUSE_PRESSED, now, InputEvent.BUTTON1_DOWN_MASK, x, y, count, false, MouseEvent.BUTTON1));
                    t.dispatchEvent(new MouseEvent(t, MouseEvent.MOUSE_RELEASED, now, 0, x, y, count, false, MouseEvent.BUTTON1));
                    t.dispatchEvent(new MouseEvent(t, MouseEvent.MOUSE_CLICKED, now, 0, x, y, count, false, MouseEvent.BUTTON1));
                }
                return;
            }
        }
        throw new IllegalStateException("no row " + label);
    }

    /** Clicks the button whose accessible name is {@code name} in the open dialog titled {@code title}. */
    private static void clickInDialog(String title, String name) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (Window w : Window.getWindows()) {
                if (w instanceof JDialog d && d.isVisible() && title.equals(d.getTitle())) {
                    AbstractButton b = find(d.getContentPane(), AbstractButton.class, x -> name.equals(x.getAccessibleContext().getAccessibleName()));
                    if (b == null) {
                        throw new IllegalStateException("no button " + name + " in " + title);
                    }
                    b.doClick();
                    return;
                }
            }
            throw new IllegalStateException("no dialog " + title);
        });
    }

    private static void closeDialog(String title) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (Window w : Window.getWindows()) {
                if (w instanceof JDialog d && d.isVisible() && title.equals(d.getTitle())) {
                    d.setVisible(false);
                }
            }
        });
    }

    /** Runs the menu bar's command {@code id}, clicking its item as the user would. */
    private static void findCommand(String id) {
        String text = switch (id) {
            case "files.settings" -> "Configure " + FoldersDialog.title(false);
            case "files.repositories" -> "Configure repositories";
            default -> throw new IllegalArgumentException(id);
        };
        for (Frame f : Frame.getFrames()) {
            if (f instanceof JFrame jf && f.isVisible() && jf.getJMenuBar() != null) {
                for (int m = 0; m < jf.getJMenuBar().getMenuCount(); m++) {
                    JMenu menu = jf.getJMenuBar().getMenu(m);
                    for (int i = 0; i < menu.getItemCount(); i++) {
                        JMenuItem item = menu.getItem(i);
                        if (item != null && item.getText() != null && item.getText().startsWith(text)) {
                            item.doClick();
                            return;
                        }
                    }
                }
            }
        }
        throw new IllegalStateException("no menu item for " + id);
    }

    /** Selects the entry labelled {@code label} in the list of the open dialog titled {@code title}. */
    private static void selectInDialogList(String title, String label) {
        for (Window w : Window.getWindows()) {
            if (w instanceof JDialog d && d.isVisible() && title.equals(d.getTitle())) {
                JList<?> l = find(d.getContentPane(), JList.class, x -> true);
                for (int i = 0; l != null && i < l.getModel().getSize(); i++) {
                    if (String.valueOf(l.getModel().getElementAt(i)).equals(label)) {
                        l.setSelectedIndex(i);
                    }
                }
            }
        }
    }

    /** Runs every task posted to the event thread before this call. */
    private static void flushEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
        });
    }

    /** Whether a dialog titled {@code title} is on screen; asked on the event thread. */
    private static boolean dialogShown(String title) {
        boolean[] shown = new boolean[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                for (Window w : Window.getWindows()) {
                    shown[0] |= w instanceof JDialog d && d.isVisible() && title.equals(d.getTitle());
                }
            });
        } catch (Exception e) {
            return false;
        }
        return shown[0];
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

    /** The visible window's list named {@code name}, or null. */
    private static JList<?> list(String name) {
        for (Frame f : Frame.getFrames()) {
            if (f instanceof JFrame jf && f.isVisible()) {
                JList<?> l = find(jf.getContentPane(), JList.class, x -> name.equals(x.getAccessibleContext().getAccessibleName()));
                if (l != null) {
                    return l;
                }
            }
        }
        return null;
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

    /** Double-clicks a link to {@code href} in the shown preview whose text contains {@code shows}, as a user would. */
    private static void followLink(String shows, String href) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (Frame f : Frame.getFrames()) {
                if (f instanceof JFrame jf && f.isVisible()) {
                    HtmlPane pane = find(jf.getContentPane(), HtmlPane.class, x -> x.isShowing() && text(x).contains(shows));
                    if (pane != null) {
                        MouseEvent click = new MouseEvent(pane, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 10, 10, 2, false,
                                MouseEvent.BUTTON1);
                        pane.fireHyperlinkUpdate(new HyperlinkEvent(pane, HyperlinkEvent.EventType.ACTIVATED, null, href, null, click));
                        return;
                    }
                }
            }
            throw new IllegalStateException("no preview showing " + shows);
        });
    }

    private static String text(HtmlPane pane) {
        try {
            return pane.getDocument().getText(0, pane.getDocument().getLength());
        } catch (BadLocationException e) {
            return "";
        }
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

    /** A Gradle run in its console, in a build of its own whose wrapper only prints, so the shot needs no project. */
    private static void tools(StateLoop loop, Path out) throws Exception {
        Path build = Files.createTempDirectory("comando-snapshot-build");
        Path gradlew = Files.writeString(build.resolve("gradlew"), "#!/bin/sh\necho \"> Task :help\"\necho\necho \"BUILD SUCCESSFUL in 1s\"\n");
        Files.setPosixFilePermissions(gradlew, PosixFilePermissions.fromString("rwxr-xr-x"));
        loop.dispatch(new Intent.SaveSession("", SessionSpec.fresh("build", build.toString())));
        loop.dispatch(new Intent.BindSession(loop.state().focused(), "build"));
        loop.dispatch(new Intent.RunGradle(null, "help --quiet"));
        await(loop, st -> st.views().stream().anyMatch(v -> v.spec() instanceof ViewSpec.Console c
                && c.command().startsWith("gradle") && c.state() != ViewSpec.RunState.RUNNING), 120_000);
        Thread.sleep(800);
        shoot(out.resolve("08-gradle.png"));
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

    /** Clicks the item at {@code path} in the window's menu bar ("Tools", "Help", "Tutorial"), as a user would. */
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
        if (c instanceof JToggleButton b && (title.equals(b.getText()) || title.equals(b.getAccessibleContext().getAccessibleName()))) {
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

    /** Paints the open dialog titled {@code title} at twice its size on a dark ground, then closes it. */
    static void shootDialog(String title, Path file) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (Window w : Window.getWindows()) {
                if (w instanceof JDialog d && d.isVisible() && title.equals(d.getTitle())) {
                    int margin = 40;
                    Container content = d.getContentPane();
                    BufferedImage img = new BufferedImage(2 * (content.getWidth() + 2 * margin), 2 * (content.getHeight() + 2 * margin),
                            BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = img.createGraphics();
                    g.setColor(new Color(0x111214));
                    g.fillRect(0, 0, img.getWidth(), img.getHeight());
                    g.scale(2, 2);
                    g.translate(margin, margin);
                    content.paint(g);
                    g.dispose();
                    d.dispose();
                    try {
                        ImageIO.write(img, "png", file.toFile());
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    return;
                }
            }
            throw new IllegalStateException("no dialog " + title);
        });
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
