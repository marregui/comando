package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.state.ConnectionState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.ui.kit.format.Labels;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What a card says above its summary (record 90): a title, the panel's or the headless session's name, and the facts,
 * one a line, each said once: the state, then for a session its model, context, folder, conversation and Claude Code,
 * then the panel's session when the title does not name it, its connection, and its file unless it is a session's own.
 * Copy details copies more, for a clipboard ({@link SessionRow#details}). A connection's card has facts alone.
 */
record CardFacts(String title, List<Fact> facts) {

    record Fact(String label, String value) {
    }

    /**
     * The facts of {@code panel} (empty for a headless session) and {@code session} (empty for a panel without one);
     * {@code doing} is what the panel's tab says it does, as {@link TabStatus#describe} words it; {@code file} the
     * panel's file, shortened.
     */
    static CardFacts of(Optional<PanelState> panel, Optional<SessionState> session, String doing, String file, long nowMillis,
                        String newestCli) {
        String title = panel.map(PanelState::title).or(() -> session.map(SessionState::name)).orElse("");
        List<Fact> facts = new ArrayList<>();
        String what = doing.startsWith(" · ") ? doing.substring(3) : doing;
        String state = session.map(s -> sessionState(s, what, panel.isEmpty(), nowMillis)).orElse(what);
        add(facts, "State", state);
        session.ifPresent(s -> {
            boolean reported = s.status().isUsable() && !s.info().model().isBlank();
            add(facts, "Model", (reported ? s.info().model() : SessionRow.modelName(s)) + " · effort " + SessionRow.effortName(s));
            String context = SessionRow.contextText(s);
            if (!context.isEmpty()) {
                add(facts, "Context", context + " · " + Labels.tokens(s.contextTokens()) + " of " + Labels.tokens(s.contextWindow())
                        + (s.reflection().due() && EditorPanel.showsCheckpoint(s) ? " · checkpoint due" : ""));
            }
            add(facts, "Folder", Labels.shortPath(s.spec().workDir()) + (s.branch().isEmpty() ? "" : " · " + s.branch()));
            String id = s.spec().claudeSessionId();
            add(facts, "Conversation", id.substring(0, Math.min(8, id.length())));
            if (!SessionRow.cliText(s).isEmpty()) {
                add(facts, "Claude Code", s.info().cliVersion()
                        + (SessionRow.olderCli(s, newestCli) ? " · older than " + newestCli + "; start again to update" : ""));
            }
        });
        panel.ifPresent(p -> {
            if (!p.session().isEmpty() && !p.session().equals(p.title())) {
                add(facts, "Session", p.session());
            }
            add(facts, "Connection", p.connection());
            if (!p.session().equals(p.title())) {
                add(facts, "File", file);
            }
        });
        return new CardFacts(title, List.copyOf(facts));
    }

    /**
     * A connection's card, which has no summary (record 92): its name, then its state, database and server, user, TLS
     * mode, production, and the open panels that use it; never the password.
     */
    static CardFacts of(ConnectionState c, List<String> panels) {
        ConnectionSpec s = c.spec();
        List<Fact> facts = new ArrayList<>();
        add(facts, "State", ConnectionRow.stateText(c));
        add(facts, "Database", s.database() + " on " + s.host() + ':' + s.port());
        add(facts, "User", s.user());
        add(facts, "TLS", s.sslMode());
        add(facts, "Production", s.prod() ? "yes; Claude's writes are refused" : "");
        add(facts, "Panels", String.join(" · ", panels));
        return new CardFacts(s.name(), List.copyOf(facts));
    }

    /** "running · working: Bash 12 s · up 1 h 4 m"; a failed session's error; "no panel" for a headless one. */
    private static String sessionState(SessionState s, String doing, boolean headless, long nowMillis) {
        List<String> parts = new ArrayList<>();
        parts.add(switch (s.status()) {
            case OPEN -> "running";
            case OPENING -> "starting";
            case CLOSING -> "stopping";
            case FAILED -> s.error().isEmpty() ? "failed" : "failed: " + s.error();
            case CLOSED -> "stopped";
        });
        String what = headless ? (s.isBusy() ? TabStatus.working(s) : s.isWaiting() ? TabStatus.waiting(s) : "") : doing;
        if (!what.isEmpty() && s.status() != Status.FAILED) {
            parts.add(what);
        }
        if (s.status().isUsable() && s.startedMillis() > 0L) {
            parts.add("up " + Labels.uptime(nowMillis - s.startedMillis()));
        }
        if (headless) {
            parts.add("no panel");
        }
        return String.join(" · ", parts);
    }

    private static void add(List<Fact> facts, String label, String value) {
        if (!value.isBlank()) {
            facts.add(new Fact(label, value));
        }
    }

    /** The facts as text, "Label: value" a line; for tests. */
    String text() {
        StringBuilder sb = new StringBuilder();
        for (Fact f : facts) {
            sb.append(f.label()).append(": ").append(f.value()).append('\n');
        }
        return sb.toString().strip();
    }
}
