package dev.marregui.comando.core.reduce;

import dev.marregui.comando.core.effect.Effect;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.port.Clock;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.SessionState;

import java.util.ArrayList;
import java.util.List;

/**
 * Helpers the rules of more than one domain use: notices, the panels' and sessions' saved lists, fresh panel ids.
 */
final class Shared {

    /** Queries and commands running this long or longer notify the desktop when they end. */
    static final long LONG_RUN_MILLIS = 10_000L;

    private Shared() {
    }

    static Effect savePanels(AppState s) {
        return new Effect.SavePanels(s.panels(), s.closed());
    }

    static List<SessionSpec> sessionSpecs(AppState s) {
        return s.sessions().stream().map(SessionState::spec).toList();
    }

    /** A panel id no panel holds, open or closed, and the state that took it: the reducer makes ids deterministically. */
    record Fresh(AppState state, PanelId id) {
    }

    static Fresh freshPanel(AppState s) {
        PanelId fresh = new PanelId("p-" + s.nextId());
        s = s.withIdTaken();
        while (s.panel(fresh).isPresent() || s.closedPanel(fresh).isPresent()) {
            fresh = new PanelId("p-" + s.nextId());
            s = s.withIdTaken();
        }
        return new Fresh(s, fresh);
    }

    static AppState notice(Clock clock, AppState s, Notice.Level level, String text) {
        return notice(clock, s, level, text, "");
    }

    /**
     * Records a notice in the history and shows it unless an error stands: an error stays on the status bar until
     * the user dismisses it, a newer error replaces it, or a notice about the same subject replaces it.
     */
    static AppState notice(Clock clock, AppState s, Notice.Level level, String text, String subject) {
        Notice n = new Notice(level, text, clock.millis(), subject);
        List<Notice> history = new ArrayList<>(s.notices().size() + 1);
        int from = Math.max(0, s.notices().size() + 1 - AppState.NOTICE_HISTORY);
        history.addAll(s.notices().subList(from, s.notices().size()));
        history.add(n);
        Notice standing = s.notice();
        boolean replaces = standing.isEmpty() || standing.level() != Notice.Level.ERROR || level == Notice.Level.ERROR
                || !subject.isEmpty() && subject.equals(standing.subject());
        return s.withNotices(replaces ? n : standing, history);
    }

    /** Clears the status bar's notice when it is about {@code subject}: the thing it reported on has moved on. */
    static AppState clear(AppState s, String subject) {
        Notice n = s.notice();
        return !n.isEmpty() && !subject.isEmpty() && subject.equals(n.subject()) ? s.withNotice(Notice.NONE) : s;
    }
}
