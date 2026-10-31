package dev.marregui.comando.ui.shell;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.Timer;

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
import dev.marregui.comando.ui.kit.format.KeyText;

/**
 * Plots that refresh: the panel's query runs again every few seconds when it is not running, and each
 * finished run of that query replaces the plot, without moving the lower panel's tab. Closing the plot's
 * tab stops it, and so does running another statement in the panel: the refresh never replaces the
 * user's newer result. EDT only.
 */
final class LivePlots {

    private final IntentSink intents;
    private final ResultRegistry results;
    /** The longest refresh period: a day, well inside a timer's milliseconds. */
    static final int MAX_SECONDS = 86_400;

    private final Map<PanelId, Live> live = new HashMap<>();

    private final class Live {
        final PanelId panel;
        final String sql;
        final String title;
        final Timer timer;
        long plotted = -1;
        boolean shown;

        Live(final PanelId panel, final String sql, final String title, final int seconds) {
            this.panel = panel;
            this.sql = sql;
            this.title = title;
            this.timer = new Timer(seconds * 1000, e -> tick(this));
        }
    }

    LivePlots(final IntentSink intents, final ResultRegistry results) {
        this.intents = intents;
        this.results = results;
    }

    /** Refreshes {@code panel}'s plot of {@code sql} every {@code seconds} (1 s to a day), in place of a live one. */
    void start(final PanelId panel, final String sql, final String title, final int seconds) {
        stop(panel);
        final Live l = new Live(panel, sql, title, Math.clamp(seconds, 1, MAX_SECONDS));
        live.put(panel, l);
        l.timer.start();
    }

    void stop(final PanelId panel) {
        final Live l = live.remove(panel);
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

    /** A live plot's refresh period in milliseconds; for tests. */
    int periodMillis(final PanelId panel) {
        return live.get(panel).timer.getDelay();
    }

    private AppState last;

    /** Every live plot's tick now, as its timer would; for tests. */
    void tickAll() {
        for (final Live l : List.copyOf(live.values())) {
            tick(l);
        }
    }

    private void tick(final Live l) {
        final AppState s = last;
        final SqlRun run = s == null ? null : s.runOf(l.panel).orElse(null);
        if (run != null && !run.sql().equals(l.sql)) {
            stop(l.panel);
            intents.dispatch(new Intent.Notify(Notice.Level.INFO, "Live plot " + l.title + " stopped: the panel ran another statement", false));
            return;
        }
        // a connection that failed stays failed until the user reconnects: refreshing would reopen it and raise
        // the same error every period
        final String connection = s == null ? "" : s.panel(l.panel).map(p -> p.connection()).orElse("");
        final boolean failed = s != null && s.connection(connection).map(c -> c.status() == Status.FAILED)
                .orElse(false);
        if (failed) {
            stop(l.panel);
            intents.dispatch(new Intent.Notify(Notice.Level.WARN, "Live plot " + l.title + " stopped: connection " + connection
                    + " is down; run the query again once it is back, then " + KeyText.of("⌘⇧J"), false, Notice.connection(connection)));
            return;
        }
        if (run == null || !run.status().isActive()) {
            intents.dispatch(new Intent.RefreshSql(l.panel, l.sql));
        }
    }

    /** Called on each state: plots new results of the live queries, stops plots whose tab was closed. */
    void onState(final AppState s) {
        last = s;
        for (final Live l : Map.copyOf(live).values()) {
            final boolean viewOpen = s.views().stream().anyMatch(v -> v.title().equals(l.title) && v.placement() == Placement.LOWER);
            if (l.shown && !viewOpen || s.panel(l.panel).isEmpty()) {
                stop(l.panel);
                continue;
            }
            final SqlRun run = s.runOf(l.panel).orElse(null);
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
            final ResultView r = results.get(run.requestId());
            if (r == null) {
                continue;
            }
            l.plotted = run.requestId();
            try {
                // the first plot opens its tab; later data replaces it without moving the user
                intents.dispatch(l.shown ? new Intent.RefreshView(l.title, ResultViews.plot(r))
                        : new Intent.ShowView("", l.title, ResultViews.plot(r), Placement.LOWER));
                l.shown = true;
            } catch (final IllegalArgumentException e) {
                stop(l.panel);
            }
        }
    }
}
