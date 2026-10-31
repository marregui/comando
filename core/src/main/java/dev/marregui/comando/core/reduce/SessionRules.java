package dev.marregui.comando.core.reduce;

import dev.marregui.comando.core.effect.Effect;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.StartMode;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.port.Clock;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.Reflection;
import dev.marregui.comando.core.state.SessionState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static dev.marregui.comando.core.reduce.Shared.clear;
import static dev.marregui.comando.core.reduce.Shared.freshPanel;
import static dev.marregui.comando.core.reduce.Shared.notice;
import static dev.marregui.comando.core.reduce.Shared.savePanels;
import static dev.marregui.comando.core.reduce.Shared.sessionSpecs;

/**
 * The sessions' lifecycle: saving, removing, importing, ordering, starting, stopping and configuring sessions, and the
 * process's start, init and exit.
 */
final class SessionRules {

    private SessionRules() {
    }

    /**
     * The process is up. A start reported after the user asked to stop it (the session closing) leaves it closing, and
     * the stop's exit follows; one reported for a session already open changes nothing. A closed session takes it: the
     * adapter reports a start before the exit of the same process, so a closed session's start is a new one (and the
     * window replays drive sessions this way).
     */
    static Transition sessionStarted(Clock clock, AppState s, String name) {
        return s.session(name)
                .filter(x -> x.status() == Status.OPENING || x.status() == Status.CLOSED)
                .map(x -> Transition.of(clear(s.withSession(x.withStatus(Status.OPEN, "").withoutTasks().withStarted(clock.millis())),
                        Notice.session(name))))
                .orElse(Transition.of(s));
    }

    static Transition saveSession(Clock clock, AppState s, Intent.SaveSession i) {
        SessionSpec spec = i.spec();
        if (i.previousName().isEmpty()) {
            if (s.session(spec.name()).isPresent()) {
                return Transition.of(notice(clock, s, Notice.Level.ERROR, "A session called " + spec.name() + " exists", Notice.session(spec.name())));
            }
            if (s.titleTaken(spec.name()) && Stream.concat(s.panels().stream(), s.closed().stream())
                    .noneMatch(p -> !p.document() && p.session().isEmpty() && p.title().equalsIgnoreCase(spec.name()))) {
                return Transition.of(notice(clock, s, Notice.Level.ERROR, "A panel called " + spec.name()
                        + " exists; a session's name is its own panel's title", Notice.session(spec.name())));
            }
            List<SessionState> sessions = new ArrayList<>(s.sessions());
            sessions.add(SessionState.closed(spec));
            AppState next = s.withSessions(sessions);
            List<Effect> effects = new ArrayList<>();
            effects.add(new Effect.SaveSessions(sessionSpecs(next)));
            if (!spec.claudeSessionId().isEmpty()) {
                effects.add(new Effect.LoadHistory(spec));
            }
            // a session comes with its own panel, titled with its name (record 88)
            Shared.Fresh fresh = freshPanel(next);
            Transition opened = PanelRules.openSession(clock, fresh.state(), new Intent.OpenSession(spec.name(), fresh.state().focused(), fresh.id()));
            effects.addAll(opened.effects());
            return new Transition(opened.state(), effects);
        }
        Optional<SessionState> previous = s.session(i.previousName());
        if (previous.isEmpty()) {
            return Transition.of(notice(clock, s, Notice.Level.ERROR, "No session called " + i.previousName()));
        }
        boolean renamed = !i.previousName().equals(spec.name());
        SessionState old = previous.get();
        if (renamed && old.status() != Status.CLOSED && old.status() != Status.FAILED) {
            return Transition.of(notice(clock, s, Notice.Level.ERROR, "Stop session " + old.name() + " before renaming it", Notice.session(old.name())));
        }
        if (renamed && s.session(spec.name()).isPresent()) {
            return Transition.of(notice(clock, s, Notice.Level.ERROR, "A session called " + spec.name() + " exists"));
        }
        // its own panel takes the new name: no other panel may have it
        if (renamed && Stream.concat(s.panels().stream(), s.closed().stream())
                .anyMatch(p -> p.title().equalsIgnoreCase(spec.name()) && !(!p.document() && p.session().equals(i.previousName())))) {
            return Transition.of(notice(clock, s, Notice.Level.ERROR, "A panel called " + spec.name() + " exists", Notice.session(old.name())));
        }
        List<SessionState> sessions = new ArrayList<>(s.sessions().size());
        for (SessionState x : s.sessions()) {
            // the form has no carried summary nor creator: both stay as they were (record 121)
            sessions.add(x.name().equals(i.previousName())
                    ? x.withSpec(spec.withCarried(x.spec().carried()).withCreatedBy(x.spec().createdBy())) : x);
        }
        AppState next = s.withSessions(sessions);
        List<Effect> effects = new ArrayList<>();
        effects.add(new Effect.SaveSessions(sessionSpecs(next)));
        if (renamed) {
            next = rebindSession(next, i.previousName(), spec.name());
            next = retitleOwnPanel(next, spec.name());
            effects.add(new Effect.ForgetSession(i.previousName()));
            effects.add(savePanels(next));
            if (!spec.claudeSessionId().isEmpty()) {
                effects.add(new Effect.LoadHistory(spec));
            }
        }
        if (old.status().isUsable() && !old.spec().equals(spec)) {
            next = notice(clock, next, Notice.Level.INFO, "Session " + spec.name() + ": changes apply at the next start", Notice.session(spec.name()));
        }
        return new Transition(next, effects);
    }

    /**
     * Removes the session and its own panel, open or closed, whose file is deleted; documents attached to it stay,
     * detached. The conversation stays in Claude Code's files.
     */
    static Transition removeSession(Clock clock, AppState s, String name) {
        Optional<SessionState> session = s.session(name);
        if (session.isEmpty()) {
            return Transition.of(s);
        }
        List<Effect> effects = new ArrayList<>();
        Optional<PanelState> own = s.homePanel(name);
        if (own.isPresent()) {
            Transition dropped = PanelRules.dropPanel(clock, s, own.get().id(), true);
            s = dropped.state();
            effects.addAll(dropped.effects().stream().filter(e -> !(e instanceof Effect.SavePanels)).toList());
        }
        Optional<PanelState> closedOwn = s.closedPanelOfSession(name);
        if (closedOwn.isPresent()) {
            PanelId gone = closedOwn.get().id();
            s = s.withClosed(s.closed().stream().filter(p -> !p.id().equals(gone)).toList());
            effects.add(new Effect.DeletePanel(gone));
        }
        if (session.get().status() != Status.CLOSED) {
            effects.add(new Effect.StopClaude(name));
        }
        effects.add(new Effect.ForgetSession(name));
        AppState next = clear(ApprovalRules.withdrawApprovals(s, name, "session removed", effects), Notice.session(name));
        next = next.withSessions(next.sessions().stream().filter(x -> !x.name().equals(name)).toList());
        next = rebindSession(next, name, "");
        if (own.isPresent() || closedOwn.isPresent()) {
            next = notice(clock, next, Notice.Level.INFO, "Removed session " + name + " and its panel");
        }
        effects.add(new Effect.SaveSessions(sessionSpecs(next)));
        effects.add(savePanels(next));
        return new Transition(next, effects);
    }

    static Transition importSessions(Clock clock, AppState s, List<SessionSpec> specs) {
        Set<String> names = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (SessionState x : s.sessions()) {
            names.add(x.name());
            ids.add(x.spec().claudeSessionId());
        }
        List<SessionState> sessions = new ArrayList<>(s.sessions());
        List<Effect> effects = new ArrayList<>();
        int added = 0;
        for (SessionSpec spec : specs) {
            if (names.contains(spec.name()) || (!spec.claudeSessionId().isEmpty() && ids.contains(spec.claudeSessionId()))) {
                continue;
            }
            names.add(spec.name());
            ids.add(spec.claudeSessionId());
            sessions.add(SessionState.closed(spec));
            effects.add(new Effect.LoadHistory(spec));
            added++;
        }
        AppState next = s.withSessions(sessions);
        if (added > 0) {
            effects.add(new Effect.SaveSessions(sessionSpecs(next)));
        }
        if (added == 1) {
            // the usual "import, then talk": one conversation, opened in its own panel
            String name = next.sessions().getLast().name();
            Shared.Fresh fresh = freshPanel(next);
            Transition opened = PanelRules.openSession(clock, fresh.state(), new Intent.OpenSession(name, fresh.state().focused(), fresh.id()));
            effects.addAll(opened.effects());
            return new Transition(notice(clock, opened.state(), Notice.Level.INFO, "Imported " + name + " and opened it in its own panel"), effects);
        }
        return new Transition(notice(clock, next, Notice.Level.INFO, "Imported " + added + " of " + specs.size() + " sessions"), effects);
    }

    static Transition startSession(AppState s, String name) {
        return s.session(name)
                .filter(x -> !x.status().isUsable() && x.status() != Status.CLOSING)
                .map(x -> new Transition(s.withSession(x.withStatus(Status.OPENING, "")), List.of(new Effect.StartClaude(x.spec()))))
                .orElse(Transition.of(s));
    }

    /**
     * A stopped session starts on a new conversation; the spec is saved first, so a crash mid-start resumes nothing
     * old.
     */
    static Transition startFresh(Clock clock, AppState s, String name) {
        Optional<SessionState> found = s.session(name).filter(x -> !x.status().isUsable() && x.status() != Status.CLOSING);
        if (found.isEmpty()) {
            return Transition.of(s);
        }
        SessionState x = found.get();
        String previous = x.spec().claudeSessionId();
        // a new conversation has nothing to reflect on yet
        SessionState fresh = x.withSpec(x.spec().restarted()).withStatus(Status.OPENING, "").withReflection(Reflection.FRESH);
        AppState next = s.withSession(fresh);
        if (!previous.isEmpty()) {
            next = notice(clock, next, Notice.Level.INFO, "Session " + name + ": new conversation; the previous one (" + previous
                    + ") can be imported again", Notice.session(name));
        }
        return new Transition(next, List.of(new Effect.SaveSessions(sessionSpecs(next)), new Effect.StartClaude(fresh.spec())));
    }

    /** The named sessions first, in the order given; the rest after them as they were. Saved, so a restart keeps it. */
    static Transition orderSessions(AppState s, List<String> names) {
        List<SessionState> ordered = new ArrayList<>(s.sessions().size());
        for (String name : names) {
            s.session(name).filter(x -> !ordered.contains(x)).ifPresent(ordered::add);
        }
        for (SessionState x : s.sessions()) {
            if (!ordered.contains(x)) {
                ordered.add(x);
            }
        }
        if (ordered.equals(s.sessions())) {
            return Transition.of(s);
        }
        AppState next = s.withSessions(ordered);
        return new Transition(next, List.of(new Effect.SaveSessions(sessionSpecs(next))));
    }

    static Transition stopSession(AppState s, String name) {
        return s.session(name)
                .filter(x -> x.status().isUsable())
                .map(x -> new Transition(s.withSession(x.withStatus(Status.CLOSING, "")), List.<Effect>of(new Effect.StopClaude(name))))
                .orElse(Transition.of(s));
    }

    static Transition sessionSetting(Clock clock, AppState s, String name, String value, boolean model) {
        Optional<SessionState> found = s.session(name);
        if (found.isEmpty() || value.isBlank()) {
            return Transition.of(s);
        }
        SessionState x = found.get();
        SessionState updated = x.withSpec(model ? x.spec().withModel(value) : x.spec().withEffort(value));
        List<Effect> effects = new ArrayList<>();
        if (x.status() == Status.OPEN) {
            effects.add(new Effect.SendToClaude(name, (model ? "/model " : "/effort ") + value, "settings"));
            updated = updated.withTurns(updated.pendingTurns() + 1, clock.millis());
        }
        AppState next = s.withSession(updated);
        effects.add(new Effect.SaveSessions(sessionSpecs(next)));
        return new Transition(next, effects);
    }

    static Transition sessionInit(Clock clock, AppState s, Intent.SessionInit i) {
        Optional<SessionState> found = s.session(i.name());
        if (found.isEmpty()) {
            return Transition.of(s);
        }
        SessionState x = found.get().withInfo(i.info(), clock.millis());
        if (x.status() == Status.OPENING) {
            x = x.withStatus(Status.OPEN, "");
        }
        List<Effect> effects = new ArrayList<>();
        String id = i.info().claudeSessionId();
        String previous = x.spec().claudeSessionId();
        // a resumed conversation that reports another id was left for a new one: /clear, under -p too
        boolean cleared = !id.isEmpty() && !previous.isEmpty() && !id.equals(previous) && x.spec().startMode() == StartMode.RESUME;
        if (!id.isEmpty() && (!id.equals(previous) || x.spec().startMode() != StartMode.RESUME)) {
            x = x.withSpec(x.spec().resumable(id));
        }
        AppState next = s.withSession(cleared ? x.withReflection(Reflection.FRESH) : x);
        if (cleared) {
            String carried = x.spec().carried().isEmpty() ? "" : "; the checkpoint's summary goes in front of the next prompt";
            next = notice(clock, next, Notice.Level.INFO, "Session " + x.name() + ": new conversation; the previous one (" + previous
                    + ") can be imported again" + carried, Notice.session(x.name()));
        }
        if (!x.spec().equals(found.get().spec())) {
            effects.add(new Effect.SaveSessions(sessionSpecs(next)));
        }
        return new Transition(next, effects);
    }

    static Transition sessionExited(Clock clock, AppState s, Intent.SessionExited i) {
        Optional<SessionState> found = s.session(i.name());
        if (found.isEmpty()) {
            return Transition.of(s);
        }
        SessionState x = found.get();
        boolean expected = x.status() == Status.CLOSING || i.exitCode() == 0;
        long now = clock.millis();
        x = x.withStatus(expected ? Status.CLOSED : Status.FAILED, expected ? "" : i.error())
                .withTurns(0, now)
                .withActivity("", now)
                .withoutTasks()
                // the checkpoint's turn ended with the process: its conversation is not compacted, nor the session
                // locked
                .withCheckpointCompacts(false)
                .withCheckpointTurns(0);
        List<Effect> effects = new ArrayList<>();
        AppState next = ApprovalRules.withdrawApprovals(s.withSession(x), i.name(), "session ended", effects);
        if (!expected) {
            // -1: the process never started; the transcript holds the same text, so the notice says only what happened
            String text = i.exitCode() == -1 ? "Session " + i.name() + " could not start: " + i.error()
                    : "Session " + i.name() + " exited [code=" + i.exitCode() + "] " + i.error();
            next = notice(clock, next, Notice.Level.ERROR, text, Notice.session(i.name()));
        }
        return new Transition(next, effects);
    }

    /** The session's own panel, open or closed, titled with its name. */
    private static AppState retitleOwnPanel(AppState s, String name) {
        List<PanelState> panels = s.panels().stream().map(p -> !p.document() && p.session().equals(name) ? p.withTitle(name) : p).toList();
        List<PanelState> closed = s.closed().stream().map(p -> !p.document() && p.session().equals(name) ? p.withTitle(name) : p).toList();
        return s.withPanels(panels, s.focused()).withClosed(closed);
    }

    private static AppState rebindSession(AppState s, String from, String to) {
        List<PanelState> panels = new ArrayList<>(s.panels().size());
        for (PanelState p : s.panels()) {
            panels.add(p.session().equals(from) ? p.withSession(to) : p);
        }
        // closed panels keep their bindings, so they follow renames and removals too
        List<PanelState> closed = s.closed().stream().map(p -> p.session().equals(from) ? p.withSession(to) : p).toList();
        return s.withPanels(panels, s.focused()).withClosed(closed);
    }

    /** A sub-agent's conversation sits beside its session's, so a session without one has none to show. */
    static Transition openSubagent(Clock clock, AppState s, Intent.OpenSubagent i) {
        String conversation = s.session(i.session()).map(x -> x.spec().claudeSessionId()).orElse("");
        if (conversation.isEmpty()) {
            return Transition.of(notice(clock, s, Notice.Level.INFO, "Session " + i.session() + ": the session has no conversation yet",
                    Notice.session(i.session())));
        }
        return new Transition(s, List.of(new Effect.ShowSubagent(i.session(), conversation, i.toolUseId())));
    }

    static Transition sessionRanAt(AppState s, Intent.SessionRanAt i) {
        return s.session(i.name())
                .map(x -> Transition.of(s.withSession(x.withInfo(x.info().ranAt(i.model(), i.effort(), i.firstOfTurn())))))
                .orElse(Transition.of(s));
    }

    static Transition sessionBranch(AppState s, Intent.SessionBranch i) {
        return Transition.of(s.withSession(i.name(), x -> x.withBranch(i.branch())));
    }

    static Transition sessionContext(AppState s, Intent.SessionContext i) {
        return Transition.of(s.withSession(i.name(), x -> x.withContext(i.tokens(), i.window())));
    }
}
