package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.KitJob;
import dev.marregui.comando.core.model.KitJobs;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.async.OnEdt;
import dev.marregui.comando.ui.kit.theme.Theme;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/// [FilesTab]'s Jobs row: the kit's scheduled jobs at a glance (record 117), first inside the kit's row (record 118).
/// - the row leads the KIT row, is a row of its own after a separator without a kit's folder, and is gone without a kit
/// - its detail says how many and what runs next; its tip, one line, the most pressing first
/// - a failed last run reds its name, a pending change marks it, nothing else does
/// - its menu configures the jobs and applies a pending change, only while nothing runs; a double click opens the window
@ExtendWith(OnEdt.class)
class FilesTabJobsTest {

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    private static List<FolderRoot> folders() {
        return List.of(
                new FolderRoot("Memory", "/b/m", "", List.of(), "memory", false, "", "Brain"),
                new FolderRoot("Kit", "/k", "", List.of(), "folder", false, "", "Kit"),
                new FolderRoot("Notes", "/n", "", List.of()));
    }

    private static KitJobs.Entry entry(String name, String days, boolean current, Integer exit, boolean running, String next) {
        List<String> d = days.equals("daily") ? KitJob.WEEKDAYS : List.of(days.split(","));
        return new KitJobs.Entry(new KitJob(name, "", d, "07:30", List.of("bin/" + name), Map.of(), "", true),
                true, current, true, running, exit == null ? "" : "2026-10-26T07:30+01:00", exit, next, "/k/.kit-jobs/" + name + ".log");
    }

    private static final KitJobs FINE = new KitJobs(true, List.of(
            entry("kb-drift", "MON", true, 0, false, "2026-11-02T07:30+01:00"),
            entry("transcript-sweep", "daily", true, 0, false, "2026-10-29T07:15+01:00")), "");

    private final List<String> did = new ArrayList<>();

    private FilesTab tab() {
        FilesTab tab = new FilesTab(i -> { }, p -> { }, p -> { }, (parent, text, title, options) -> 1);
        tab.render(AppState.initial(List.of(new PanelState(new PanelId("p"), "scratch", "", "")), List.of(), List.of(), folders()));
        tab.onJobs(() -> did.add("configure"), () -> did.add("apply"));
        return tab;
    }

    private static List<String> rows(JTree tree) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < tree.getRowCount(); i++) {
            out.add(((ViewSpec.TreeNode) ((DefaultMutableTreeNode) tree.getPathForRow(i).getLastPathComponent()).getUserObject()).label());
        }
        return out;
    }

    private static DefaultMutableTreeNode node(JTree tree, String label) {
        tree.expandRow(rows(tree).indexOf("KIT")); // the Jobs row is inside the kit's
        for (int i = 0; i < tree.getRowCount(); i++) {
            DefaultMutableTreeNode n = (DefaultMutableTreeNode) tree.getPathForRow(i).getLastPathComponent();
            if (((ViewSpec.TreeNode) n.getUserObject()).label().equals(label)) {
                return n;
            }
        }
        throw new AssertionError("no row " + label);
    }

    /// The Jobs row is the first inside the KIT row, which is the Kit group's one folder named in capitals; with no Kit
    /// folder it is a top-level row of its own, last, after a separator; it has the clock icon and goes when the jobs are
    /// [KitJobs#NONE].
    @Test
    void theRowLeadsTheKitsRowAndGoesWithoutAKit() {
        FilesTab tab = tab();
        JTree tree = tab.tree().tree();
        assertThat(rows(tree)).containsExactly("BRAIN", "KIT", "Notes");
        tab.showJobs(FINE);
        assertThat(rows(tree)).as("inside the kit's row, not beside it").containsExactly("BRAIN", "KIT", "Notes");
        DefaultMutableTreeNode jobs = node(tree, FilesTab.JOBS);
        assertThat(((ViewSpec.TreeNode) ((DefaultMutableTreeNode) jobs.getParent()).getUserObject()).label()).isEqualTo("KIT");
        assertThat(jobs.getParent().getIndex(jobs)).as("first inside it").isZero();
        assertThat(tab.header(jobs)).isNull();
        assertThat(tab.rowIcon((ViewSpec.TreeNode) jobs.getUserObject())).contains(FilesTab.JOBS_ICON);

        tab.render(AppState.initial(List.of(new PanelState(new PanelId("p"), "scratch", "", "")), List.of(), List.of(),
                List.of(new FolderRoot("Notes", "/n", "", List.of()))));
        assertThat(rows(tree)).as("no Kit folder: last, a row of its own").containsExactly("Notes", FilesTab.JOBS);
        assertThat(tab.header((DefaultMutableTreeNode) tree.getPathForRow(1).getLastPathComponent())).as("after a separator").isEmpty();

        tab.showJobs(KitJobs.NONE);
        assertThat(rows(tree)).containsExactly("Notes");
    }

    /// The detail counts the jobs and names when the next one runs; the tip is one line of at most 80 characters, in
    /// order: unreadable, running, failed, pending, next.
    @Test
    void theDetailAndTipSayTheMostPressingFirst() {
        assertThat(FilesTab.jobsDetail(FINE)).isEqualTo("2 jobs · next Thu 07:15");
        assertThat(FilesTab.jobsTip(FINE)).isEqualTo("Next: transcript-sweep Thu 07:15; double-click to configure the jobs");
        KitJobs pending = new KitJobs(true, List.of(entry("a", "MON", false, 0, false, "2026-11-02T07:30+01:00")), "");
        assertThat(FilesTab.jobsTip(pending)).startsWith("The scheduler lags the manifest");
        KitJobs failed = new KitJobs(true, List.of(entry("a", "MON", false, 2, false, "2026-11-02T07:30+01:00")), "");
        assertThat(FilesTab.jobsTip(failed)).startsWith("Last run failed: a;");
        KitJobs running = new KitJobs(true, List.of(entry("a", "MON", false, 2, true, "2026-11-02T07:30+01:00")), "");
        assertThat(FilesTab.jobsTip(running)).isEqualTo("Running now: a");
        KitJobs broken = new KitJobs(true, List.of(), "python3 not found ".repeat(10));
        assertThat(FilesTab.jobsTip(broken)).startsWith("Kit jobs cannot be read: python3").hasSizeLessThanOrEqualTo(80);
        assertThat(FilesTab.jobsDetail(broken)).isEqualTo("cannot be read");
        assertThat(FilesTab.jobsDetail(running)).isEqualTo("1 job · running a");
        KitJob off = new KitJob("b", "", List.of("TUE"), "08:00", List.of("bin/b"), Map.of(), "", false);
        KitJobs withPaused = new KitJobs(true, List.of(FINE.jobs().getFirst(), new KitJobs.Entry(off, false, false, null, false, "", null, "", "")), "");
        assertThat(FilesTab.jobsDetail(withPaused)).isEqualTo("2 jobs · 1 paused · next Mon 07:30");
        for (KitJobs j : List.of(FINE, pending, failed, running, broken)) {
            assertThat(FilesTab.jobsTip(j)).doesNotContain("\n").hasSizeLessThanOrEqualTo(80);
        }
    }

    /// A failed last run or an unreadable list reds the row's name; a pending change marks it; a quiet list does neither.
    @Test
    void failureRedsTheNameAndAPendingChangeMarksTheRow() {
        FilesTab tab = tab();
        JTree tree = tab.tree().tree();
        tab.showJobs(FINE);
        assertThat(tab.colorOf(node(tree, FilesTab.JOBS))).isNull();
        tab.showJobs(new KitJobs(true, List.of(entry("a", "MON", true, 1, false, "2026-11-02T07:30+01:00")), ""));
        assertThat(tab.colorOf(node(tree, FilesTab.JOBS))).isEqualTo(Theme.DANGER);
        tab.showJobs(new KitJobs(true, List.of(), "no python3"));
        assertThat(tab.colorOf(node(tree, FilesTab.JOBS))).isEqualTo(Theme.DANGER);
    }

    /// The row's menu configures the jobs and applies a pending change; Apply is off with nothing pending or while a
    /// job runs. Activating the row (double click, Enter) opens the window.
    @Test
    void theMenuConfiguresAndAppliesAndActivatingOpensTheWindow() {
        FilesTab tab = tab();
        JTree tree = tab.tree().tree();
        tab.showJobs(FINE);
        ViewSpec.TreeNode row = (ViewSpec.TreeNode) node(tree, FilesTab.JOBS).getUserObject();
        JPopupMenu menu = tab.menuFor(row);
        assertThat(items(menu)).containsExactly(FilesTab.CONFIGURE_JOBS, FilesTab.APPLY_JOBS);
        assertThat(((JMenuItem) menu.getComponent(1)).isEnabled()).as("nothing pending").isFalse();
        ((JMenuItem) menu.getComponent(0)).doClick();
        assertThat(did).containsExactly("configure");

        tab.showJobs(new KitJobs(true, List.of(entry("a", "MON", false, 0, false, "2026-11-02T07:30+01:00")), ""));
        menu = tab.menuFor(row);
        assertThat(((JMenuItem) menu.getComponent(1)).isEnabled()).as("pending").isTrue();
        ((JMenuItem) menu.getComponent(1)).doClick();
        assertThat(did).containsExactly("configure", "apply");
        tab.showJobs(new KitJobs(true, List.of(entry("a", "MON", false, 0, true, "2026-11-02T07:30+01:00")), ""));
        assertThat(((JMenuItem) tab.menuFor(row).getComponent(1)).isEnabled()).as("a job runs").isFalse();

        AtomicInteger before = new AtomicInteger(did.size());
        tree.setSelectionPath(tree.getPathForRow(rows(tree).indexOf(FilesTab.JOBS)));
        tree.getActionMap().get("activate").actionPerformed(null);
        assertThat(did).hasSize(before.get() + 1).last().isEqualTo("configure");
    }

    private static List<String> items(JPopupMenu menu) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < menu.getComponentCount(); i++) {
            if (menu.getComponent(i) instanceof JMenuItem m) {
                out.add(m.getText());
            }
        }
        return out;
    }
}
