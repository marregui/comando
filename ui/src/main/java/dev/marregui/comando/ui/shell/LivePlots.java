package dev.marregui.comando.ui.shell;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.result.ResultRegistry;
import dev.marregui.comando.core.result.ResultView;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.RunStatus;
import dev.marregui.comando.core.state.SqlRun;
import dev.marregui.comando.core.view.Placement;

import javax.swing.Timer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Plots that refresh: the panel's query runs again every few seconds when it is not running, and each
 * finished run of that query replaces the plot, without moving the lower panel's tab. Closing the plot's
 * tab stops it, and so does running another statement in the panel: the refresh never replaces the
 * user's newer result. EDT only.
 */
final class LivePlots {

    private final IntentSink intents;
    private final ResultRegistry results;
    private final Map<PanelId, Live> live = new HashMap<>();

    private final class Live {
        final PanelId panel;
        final String sql;
        final String title;
        final Timer timer;
        long plotted = -1;
        boolean shown;

        Live(PanelId panel, String sql, String title, int seconds) {
            this.panel = panel;
            this.sql = sql;
            this.title = title;
            this.timer = new Timer(seconds * 1000, e -> tick(this));
        }
    }

    LivePlots(IntentSink intents, ResultRegistry results) {
        this.intents = intents;
        this.results = results;
    }

    void start(PanelId panel, String sql, String title, int seconds) {
        stop(panel);
        Live l = new Live(panel, sql, title, Math.max(1, seconds));
        live.put(panel, l);
        l.timer.start();
    }

    void stop(PanelId panel) {
        Live l = live.remove(panel);
        if (l != null) {
            l.timer.stop();
        }
    }

    void stopAll() {
        live.values().forEach(l -> l.timer.stop());
        live.clear();
    }

    int size() {
        return live.size();
    }

    private AppState last;

    /** Every live plot's tick now, as its timer would; for tests. */
    void tickAll() {
        for (Live l : List.copyOf(live.values())) {
            tick(l);
        }
    }

    private void tick(Live l) {
        AppState s = last;
        SqlRun run = s == null ? null : s.runOf(l.panel).orElse(null);
        if (run != null && !run.sql().equals(l.sql)) {
            stop(l.panel);
            intents.dispatch(new Intent.Notify(Notice.Level.INFO, "Live plot " + l.title + " stopped: the panel ran another statement", false));
            return;
        }
        // a connection that failed stays failed until the user reconnects: refreshing would reopen it and raise
        // the same error every period
        String connection = s == null ? "" : s.panel(l.panel).map(p -> p.connection()).orElse("");
        boolean failed = s != null && s.connection(connection).map(c -> c.status() == Status.FAILED)
                .orElse(false);
        if (failed) {
            stop(l.panel);
            intents.dispatch(new Intent.Notify(Notice.Level.WARN, "Live plot " + l.title + " stopped: connection " + connection
                    + " is down; run the query again once it is back, then ⌘⇧J", false, Notice.connection(connection)));
            return;
        }
        if (run == null || !run.status().isActive()) {
            intents.dispatch(new Intent.RefreshSql(l.panel, l.sql));
        }
    }

    /** Called on each state: plots new results of the live queries, stops plots whose tab was closed. */
    void onState(AppState s) {
        last = s;
        for (Live l : Map.copyOf(live).values()) {
            boolean viewOpen = s.views().stream().anyMatch(v -> v.title().equals(l.title) && v.placement() == Placement.LOWER);
            if (l.shown && !viewOpen || s.panel(l.panel).isEmpty()) {
                stop(l.panel);
                continue;
            }
            SqlRun run = s.runOf(l.panel).orElse(null);
            if (run != null && run.status() == RunStatus.FAILED && run.sql().equals(l.sql)) {
                // a query that failed once fails every period: stop rather than repeat the error
                stop(l.panel);
                intents.dispatch(new Intent.Notify(Notice.Level.WARN, "Live plot " + l.title + " stopped: its query failed; the error is in Results",
                        false, Notice.panel(l.panel.value())));
                continue;
            }
            if (run == null || run.requestId() == l.plotted || run.status() != RunStatus.DONE || !run.sql().equals(l.sql)) {
                continue;
            }
            ResultView r = results.get(run.requestId());
            if (r == null) {
                continue;
            }
            l.plotted = run.requestId();
            try {
                // the first plot opens its tab; later data replaces it without moving the user
                intents.dispatch(l.shown ? new Intent.RefreshView(l.title, ResultViews.plot(r))
                        : new Intent.ShowView("", l.title, ResultViews.plot(r), Placement.LOWER));
                l.shown = true;
            } catch (IllegalArgumentException e) {
                stop(l.panel);
            }
        }
    }
}
