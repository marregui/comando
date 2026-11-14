package dev.marregui.comando.app;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.marregui.comando.core.logbook.Logbook;
import dev.marregui.comando.core.logbook.LogbookTask;
import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.ApprovalDecision;
import dev.marregui.comando.core.model.ApprovalKind;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.StartMode;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.BackgroundTask;
import dev.marregui.comando.core.state.ConnectionState;
import dev.marregui.comando.core.state.InstancesState;
import dev.marregui.comando.core.state.LowerTab;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.Reflection;
import dev.marregui.comando.core.state.RunStatus;
import dev.marregui.comando.core.state.SessionInfo;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.state.SessionSubject;
import dev.marregui.comando.core.state.SqlRun;
import dev.marregui.comando.core.state.ViewState;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;

/// The values the JSON goldens under `json-golden/` were written from, by the mappers comando used before its own
/// binder: an [AppState] with every component set, the four store lists with every field set, the two registries'
/// entries, and two records of shapes the files do not hold today (`Optional`, `Path`, a `double[]`, nulls, `NaN`,
/// nested `@type` values). Each map holds one entry: a `Map.copyOf` iterates in an order of its own per JVM.
final class JsonSamples {

    /// The time every golden line of the intent log carries in place of the moment it was written.
    static final String AT = "2026-11-14T00:00:00Z";

    private JsonSamples() {
    }

    /// A state with every component other than its default, as the intent log's first line holds it; no password,
    /// which the log blanks.
    static AppState state() {
        final PanelId one = new PanelId("p1");
        final PanelId two = new PanelId("p2");
        final ConnectionSpec lab = new ConnectionSpec("lab", "db.lab", 8813, "qdb", "admin", "", true, "verify-full");
        final SessionSpec edge = new SessionSpec("edge", "/w/edge", "opus", "high", 2.5, "abc", StartMode.RESUME,
                "/reflect --auto", "carried text", "lead", "family-1", 1_700_000_000_123L);
        final SessionInfo info = new SessionInfo("abc", "opus", "/w/edge", "2.1.296", "default",
                List.of("interrupt", "rewind"), "high", "xhigh", "opus-4", "medium");
        final SessionState session = new SessionState(edge, Status.OPEN, "no error", info, 2, 0.75, 120_000L,
                1_000_000L, 1_700_000_000_500L, "running Bash", "main", 1_700_000_000_400L, "last failed",
                List.of(new BackgroundTask("t1", "bash", "gradle build", 1_700_000_000_300L)), 1_700_000_000_200L, true,
                1_700_000_000_100L, new Reflection(true, false, true), true, false, 3, true, true,
                new SessionSubject("comando", "main", "EDG-1", List.of("other")));
        final SqlRun run = new SqlRun(7L, one, "lab", "SELECT 1", RunStatus.RUNNING, 10, 2L, true, 3L, 4L, 5L, "boom",
                6L, "a note", true, true);
        final Approval approval = new Approval(9L, "edge", ApprovalKind.TOOL, "Bash", "{\"command\":\"ls\"}", "ls", 11L,
                "toolu_1");
        final Logbook logbook = new Logbook("/brain/INDEX.md", List.of(new LogbookTask(137, "JSON", "harness",
                "replace Jackson", "open", "write the binder", List.of("plan.md"), List.of("Miguel"), "#### 137. JSON",
                "2026-11-14", "Claude", "Miguel", List.of("EDG-2"))), "no problem");
        final List<ViewState> views = List.of(
                new ViewState(1L, "table", new ViewSpec.Table(List.of("a", "b"), List.of(Arrays.asList("1", null))),
                        Placement.LOWER, true, "edge", 2L, Placement.SIDE),
                new ViewState(2L, "plot", new ViewSpec.Plot("x", "y", true, List.of(new ViewSpec.Series("s",
                        new double[]{1, 2.5}, new double[]{-3, Double.NaN}))), Placement.SIDE, false, "", 1L),
                new ViewState(3L, "diff", new ViewSpec.Diff("a.txt", "before", "after"), Placement.LOWER, false, "edge",
                        1L),
                new ViewState(4L, "md", new ViewSpec.Markdown("# title"), Placement.LOWER, false, "edge", 1L),
                new ViewState(5L, "text", new ViewSpec.Text("a.java", "class A {}", "java", 3), Placement.LOWER, false,
                        "edge", 1L),
                new ViewState(6L, "tree", new ViewSpec.Tree(new ViewSpec.TreeNode("r", "d", "comando-file:/a#1",
                        List.of(new ViewSpec.TreeNode("c", "", List.of())))), Placement.SIDE, false, "", 1L),
                new ViewState(7L, "changes", new ViewSpec.Changes("/w", "sub", "edge"), Placement.SIDE, false, "", 1L),
                new ViewState(8L, "search", new ViewSpec.Search(), Placement.SIDE, false, "", 1L),
                new ViewState(9L, "files", new ViewSpec.Deliverables(), Placement.SIDE, false, "", 1L),
                new ViewState(10L, "console", new ViewSpec.Console(4L, "gradle build", ViewSpec.RunState.FAILED, 1, 99L,
                        new ViewSpec.TreeNode("tests", "1 failed", List.of()), Map.of("A.b", "boom"), "p1"),
                        Placement.LOWER, false, "edge", 1L),
                new ViewState(11L, "agent", new ViewSpec.Agent("edge", "toolu_2", "explore", "running"),
                        Placement.LOWER, false, "edge", 1L));
        final InstancesState instances = new InstancesState(true,
                new InstancesState.Instance("me", "Comando", "1.0", "lead", "/kit", true, "2026-11-14T08:00:00Z",
                        List.of(new InstancesState.Member("edge", true, "OPEN", "thinking", 5L, "opus", "high", 40,
                                "~/w", "main", List.of("build"), "do it", "done")), true, true, 0L),
                List.of(new InstancesState.Instance("peer", "Peer", "0.9", "", "", false, "",
                        List.of(new InstancesState.Member("s", false)), false, false, 12L)),
                List.of(new InstancesState.Consult("c1", InstancesState.Direction.ASKED, "peer", "Peer", "edge", "why?",
                        InstancesState.Status.OK, 1L, 2L, "because")),
                List.of("intruder"));
        return new AppState(
                List.of(new PanelState(one, "one", "lab", "edge", false, false, false),
                        new PanelState(two, "two", "", "", true, true, true)),
                List.of(new PanelState(new PanelId("p3"), "closed", "lab", "edge", false, false, false)),
                two,
                List.of(new ConnectionState(lab, Status.FAILED, "refused")),
                List.of(session),
                List.of(new FolderRoot("Kit", "/kit", "INDEX.md", List.of("tmp"), "skills", true, "INDEX.md", "Brain")),
                List.of(run),
                List.of(approval),
                logbook,
                views,
                LowerTab.view(10L),
                new Notice(Notice.Level.ERROR, "it failed", 13L, "edge"),
                List.of(new Notice(Notice.Level.WARN, "careful", 14L),
                        new Notice(Notice.Level.INFO, "fine", 15L, "lab")),
                instances,
                42L);
    }

    /// Two connections, every field set, one with the default TLS mode and one with text that needs escapes.
    static List<ConnectionSpec> connections() {
        return List.of(new ConnectionSpec("QuestDB", "localhost", 8812, "qdb", "admin", "quest", false, "prefer"),
                new ConnectionSpec("prod \"eu\"", "db.example.com", 5432, "postgres", "ops", "pä$$\n\t\u0001", true,
                        "verify-full"));
    }

    /// Two sessions, every field set on the first, the second fresh, with non-ASCII text in its name.
    static List<SessionSpec> sessions() {
        return List.of(new SessionSpec("edge", "/Users/me/edge", "opus", "xhigh", 12.5, "0e952c0a-3aa6-4026",
                        StartMode.RESUME, "/reflect", "the summary\nof two lines", "lead", "family",
                        1_763_100_000_000L),
                SessionSpec.fresh("Ünïcode ✓ 😀", "/tmp/a b"));
    }

    /// Two folders, every field set on the first.
    static List<FolderRoot> folders() {
        return List.of(new FolderRoot("Brain", "/Users/me/.claude/BRAIN", "00_START_HERE.md", List.of("archive", "tmp"),
                        "memory", true, "INDEX.md", "Brain"),
                new FolderRoot("Claude", "/Users/me/.claude", "CLAUDE.md", List.of()));
    }

    /// Three panels: every field set, a document with what it leaves out, and one with no field but its id.
    static List<Store.StoredPanel> panels() {
        return List.of(new Store.StoredPanel("p-1", "Claude", "QuestDB", "Claude", "Claude.md", "inline text", true,
                        true),
                new Store.StoredPanel("p-2", "notes", null, null, "notes.md", null, null, false),
                new Store.StoredPanel("p-3", null, null, null, null, null, null, null));
    }

    /// The machine's QuestDB registry entry, every field set.
    static MachineQuestDb.Entry questDb() {
        return new MachineQuestDb.Entry(1, 4242L, "Comando", "Comando Expert", "2026-11-14T08:00:00Z",
                new MachineQuestDb.Ports(9000, 9003, 8812, 9009), "tok-123", "9.3.2");
    }

    /// The comando Edge's registry entry, every field set.
    static EmbeddedEdge.Entry edge() {
        return new EmbeddedEdge.Entry(1, 4343L, "Comando", "Comando Expert",
                new EmbeddedEdge.Ports(18883, 18800, 18080, 18010), "2026-11-14T08:00:00Z");
    }

    /// Shapes no store file holds today: a present and an empty `Optional`, a `Path`, enums, a `double[]` with `NaN`,
    /// a null text and box, a float, a nested record, a list with a null, a map of lists.
    record Mixed(Optional<String> some, Optional<String> none, Optional<Integer> number, Path path, Notice.Level level,
                 List<StartMode> modes, double[] values, String nothing, Integer box, double nan, float ratio, long big,
                 PanelId panel, List<String> withNull, Map<String, List<Integer>> map) {

        /// Equal when every component is, the array's elements too.
        @Override
        public boolean equals(final Object o) {
            return o instanceof final Mixed m && toString().equals(m.toString());
        }

        @Override
        public int hashCode() {
            return toString().hashCode();
        }

        /// The components, the array's elements too: a record's own `toString` writes an array's identity.
        @Override
        public String toString() {
            return "Mixed[some=" + some + ", none=" + none + ", number=" + number + ", path=" + path + ", level="
                    + level + ", modes=" + modes + ", values=" + Arrays.toString(values) + ", nothing=" + nothing
                    + ", box=" + box + ", nan=" + nan + ", ratio=" + ratio + ", big=" + big + ", panel=" + panel
                    + ", withNull=" + withNull + ", map=" + map + ']';
        }
    }

    /// One [Mixed] with every component set where it can be, empty or null where that is the case it covers.
    static Mixed mixed() {
        final Map<String, List<Integer>> map = new LinkedHashMap<>();
        map.put("k", List.of(1, 2));
        return new Mixed(Optional.of("x"), Optional.empty(), Optional.of(3), Path.of("/tmp/a b/c.txt"),
                Notice.Level.WARN, List.of(StartMode.NEW, StartMode.FORK), new double[]{1.5, Double.NaN, -0.0, 1e20},
                null, null, Double.NaN, 0.25f, Long.MAX_VALUE, new PanelId("p1"), Arrays.asList("a", null), map);
    }

    /// Values of the interfaces whose implementations carry `@type`: as a field of the interface, of an implementation,
    /// in a list, in a map, absent, and inside a nested record.
    record Typed(ViewSpec view, ViewSpec.Table table, List<ViewSpec> views, Map<String, ApprovalDecision> decisions,
                 ApprovalDecision none, ViewState state) {
    }

    /// One [Typed] with each kind of place a typed value can sit.
    static Typed typed() {
        return new Typed(new ViewSpec.Markdown("m"), new ViewSpec.Table(List.of("c"), List.of(List.of("v"))),
                List.of(new ViewSpec.Search(), new ViewSpec.Changes("/r", "", "n")),
                Map.of("one", new ApprovalDecision.Allow(ApprovalDecision.Scope.SESSION)), null,
                new ViewState(1L, "t", new ViewSpec.Deliverables(), Placement.SIDE, true, "s", 3L));
    }
}
