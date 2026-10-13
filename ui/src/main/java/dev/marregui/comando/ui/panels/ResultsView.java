package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.dispatch.Classifier;
import dev.marregui.comando.core.result.Formats;
import dev.marregui.comando.core.result.ResultRegistry;
import dev.marregui.comando.core.result.ResultView;
import dev.marregui.comando.core.state.RunStatus;
import dev.marregui.comando.core.state.SqlRun;
import dev.marregui.comando.ui.kit.format.Labels;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.overlay.Overlay;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.tree.TreeView;
import dev.marregui.comando.ui.widget.capability.Capabilities;
import dev.marregui.comando.ui.widget.capability.Copyable;
import dev.marregui.comando.ui.widgets.table.GridTable;
import dev.marregui.comando.ui.widgets.table.TableSource;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Results tab: the focused panel's last SQL run, its rows and its timings. The tab copies as a whole: the
 * error when the run failed, else the rows; the grid copies its own selection while it has the keyboard.
 */
public final class ResultsView {

    private final ResultRegistry results;
    private final GridTable grid = new GridTable();
    private final Overlay overlay = new Overlay(grid.component());
    private final JLabel status = new JLabel(" ");
    private final JPanel root = new JPanel(new BorderLayout());
    private final CardLayout cards = new CardLayout();
    private final JPanel center = new JPanel(cards);
    private final TreeView plan = new TreeView();
    private final JToggleButton planToggle = new JToggleButton("Plan as tree", Icons.get("tree"));
    private long shownRequest = -1;
    private ResultView shownResult;
    private long planOf = -1;
    private SqlRun shownRun;

    public ResultsView(ResultRegistry results) {
        this.results = results;
        status.setBorder(BorderFactory.createEmptyBorder(3, Theme.PAD, 3, Theme.PAD));
        status.setForeground(Theme.MUTED);
        center.add(overlay, "rows");
        center.add(plan.component(), "plan");
        planToggle.putClientProperty("JButton.buttonType", "toolBarButton");
        planToggle.setToolTipText("Show the EXPLAIN output as a tree or as rows");
        planToggle.setVisible(false);
        planToggle.addActionListener(e -> cards.show(center, planToggle.isSelected() ? "plan" : "rows"));
        JPanel south = new JPanel(new BorderLayout());
        south.add(status, BorderLayout.CENTER);
        south.add(planToggle, BorderLayout.EAST);
        root.add(center, BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);
        overlay.showEmpty("Run SQL from the editor: select it, or put the caret in its block, and press ⌘⏎.");
        Capabilities.mark(root, new Copyable() {
            @Override
            public String copyText() {
                return ResultsView.this.copyText();
            }

            @Override
            public boolean canCopy() {
                return !overlay.errorText().isEmpty() || shownResult != null && shownResult.rowCount() > 0;
            }
        });
    }

    /** The error when the run on show failed, else the grid's copy text. */
    String copyText() {
        String error = overlay.errorText();
        return error.isEmpty() ? grid.copyText() : error;
    }

    public JComponent component() {
        return root;
    }

    public GridTable grid() {
        return grid;
    }

    /** The result on screen, or null. */
    public ResultView shown() {
        return shownResult;
    }

    public void render(Optional<SqlRun> maybeRun) {
        if (maybeRun.isEmpty()) {
            showPlan(null, null);
            shownRun = null; // the tick must not paint another panel's run here
            shownRequest = -1;
            shownResult = null;
            grid.setSource(null, "result.csv");
            status.setText(" ");
            overlay.showEmpty("Run SQL from the editor: select it, or put the caret in its block, and press ⌘⏎.");
            return;
        }
        SqlRun run = maybeRun.get();
        shownRun = run;
        ResultView r = results.get(run.requestId());
        if (run.requestId() != shownRequest || r != shownResult) {
            shownRequest = run.requestId();
            shownResult = r;
            grid.setSource(r == null ? null : TableSource.of(r), "result-" + run.requestId() + ".csv");
        } else if (r != null) {
            grid.refreshRows();
        }
        showPlan(run, r);
        status.setText(statusLine(run));
        status.setForeground(run.status() == RunStatus.FAILED || run.noteFailed() ? Theme.DANGER : Theme.MUTED);
        switch (run.status()) {
            case QUEUED -> overlay.showLoading("Queued on " + run.connection());
            case RUNNING -> {
                if (r == null) {
                    overlay.showLoading(runningText(run, System.currentTimeMillis()));
                } else {
                    overlay.showContent();
                }
            }
            case DONE -> {
                if (r == null) {
                    overlay.showEmpty(doneText(run));
                } else if (r.rowCount() == 0) {
                    overlay.showEmpty("No rows.");
                } else {
                    overlay.showContent();
                }
            }
            case FAILED -> overlay.showError(run.error());
            case CANCELLED -> {
                if (r != null && r.rowCount() > 0) {
                    overlay.showContent();
                } else {
                    overlay.showEmpty("Cancelled.");
                }
            }
        }
    }

    /** Once a second while a statement runs without rows yet: the elapsed time on the overlay. */
    public void tick() {
        SqlRun run = shownRun;
        if (run != null && run.status() == RunStatus.RUNNING && shownResult == null) {
            overlay.showLoading(runningText(run, System.currentTimeMillis()));
        }
    }

    static String runningText(SqlRun run, long nowMillis) {
        return "Running on " + run.connection() + (run.startedMillis() > 0 ? " · " + Labels.elapsed(nowMillis - run.startedMillis()) : "");
    }

    /** What a statement without rows did: rows changed for DML; "Done" for DDL, which changes no rows. */
    static String doneText(SqlRun run) {
        if (run.updateCount() > 0 || (run.updateCount() == 0 && !Classifier.isDdl(run.sql()))) {
            return Labels.count(run.updateCount()) + (run.updateCount() == 1 ? " row changed" : " rows changed");
        }
        return "Done.";
    }

    /** A finished EXPLAIN with one column shows as a plan tree by default; anything else as rows. */
    private void showPlan(SqlRun run, ResultView r) {
        boolean isPlan = run != null && r != null && run.status() == RunStatus.DONE && r.columnCount() == 1 && PlanTree.isExplain(run.sql());
        planToggle.setVisible(isPlan);
        if (!isPlan) {
            planOf = -1;
            cards.show(center, "rows");
            return;
        }
        if (planOf != run.requestId()) {
            planOf = run.requestId();
            List<String> lines = new ArrayList<>(r.rowCount());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < r.rowCount(); i++) {
                sb.setLength(0);
                r.format(i, 0, sb);
                lines.add(sb.toString());
            }
            plan.setRoot(PlanTree.parse(lines), 99);
            planToggle.setSelected(true);
            cards.show(center, "plan");
        }
    }

    static String statusLine(SqlRun r) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.connection()).append(" · ").append(r.status().name().toLowerCase());
        if (r.status() == RunStatus.DONE && r.rowCount() == 0 && r.updateCount() >= 0) {
            // a statement without rows: the same words as the overlay, not "0 rows"
            String done = doneText(r);
            if (!done.equals("Done.")) {
                sb.append(" · ").append(done);
            }
        } else if (r.rowCount() > 0 || r.status() == RunStatus.DONE) {
            sb.append(" · ").append(Labels.count(r.rowCount())).append(r.rowCount() == 1 ? " row" : " rows");
        }
        if (r.truncated()) {
            sb.append(" (cap reached)");
        }
        if (r.totalMillis() > 0) {
            sb.append(" · exec ");
            Formats.appendMillis(sb, r.execMillis());
            sb.append(" · fetch ");
            Formats.appendMillis(sb, r.fetchMillis());
            sb.append(" · total ");
            Formats.appendMillis(sb, r.totalMillis());
        }
        if (!r.note().isEmpty()) {
            sb.append(" · ").append(r.note());
        }
        return sb.toString();
    }
}
