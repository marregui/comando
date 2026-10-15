package dev.marregui.comando.core.resource;

import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.StartMode;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.SessionState;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Claude Code sessions as a resource kind. */
public final class SessionKind implements ResourceKind<SessionSpec> {

    public static final SessionKind INSTANCE = new SessionKind();

    private static final List<Attribute> ATTRIBUTES = List.of(
            Attribute.of("name", "Name", Attribute.Kind.TEXT, true, "unique; also the CLI's --name"),
            Attribute.of("workDir", "Working directory", Attribute.Kind.DIRECTORY, true, "the CLI runs here"),
            Attribute.openChoice("model", "Model", List.of("", "fable", "opus", "sonnet", "haiku"), "empty: the CLI's default; a full model id also works"),
            Attribute.choice("effort", "Effort", List.of("", "low", "medium", "high", "xhigh", "max"), "empty: the CLI's default"),
            Attribute.choice("permissionMode", "Permission mode",
                    List.of("", "default", "acceptEdits", "plan", "auto", "dontAsk"), "empty: the CLI's default"),
            Attribute.of("addDirs", "Extra directories", Attribute.Kind.DIRECTORIES, false, "--add-dir, separated by " + File.pathSeparator),
            Attribute.of("budgetUsd", "Budget (USD)", Attribute.Kind.DECIMAL, false, "--max-budget-usd per process; 0 for none"),
            Attribute.of("claudeSessionId", "Claude session id", Attribute.Kind.TEXT, false, "set after the first start; empty for a new session")
                    .inAdvanced(),
            Attribute.choice("startMode", "Next start", List.of("NEW", "RESUME", "FORK"),
                    "RESUME continues the stored conversation; FORK copies it").inAdvanced());

    private SessionKind() {
    }

    @Override
    public String noun() {
        return "session";
    }

    @Override
    public List<Attribute> attributes() {
        return ATTRIBUTES;
    }

    @Override
    public Map<String, String> toAttributes(SessionSpec s) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("name", s.name());
        m.put("workDir", s.workDir());
        m.put("model", s.model());
        m.put("effort", s.effort());
        m.put("permissionMode", s.permissionMode());
        m.put("addDirs", String.join(File.pathSeparator, s.addDirs()));
        m.put("budgetUsd", s.budgetUsd() == 0.0 ? "0" : Double.toString(s.budgetUsd()));
        m.put("claudeSessionId", s.claudeSessionId());
        m.put("startMode", s.startMode().name());
        return m;
    }

    @Override
    public SessionSpec fromAttributes(Map<String, String> a) {
        double budget;
        String b = a.getOrDefault("budgetUsd", "").strip();
        try {
            budget = b.isEmpty() ? 0.0 : Double.parseDouble(b);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("budget is not a number: " + b);
        }
        List<String> dirs = new ArrayList<>();
        for (String d : a.getOrDefault("addDirs", "").split(File.pathSeparator)) {
            if (!d.isBlank()) {
                dirs.add(d.strip());
            }
        }
        String id = a.getOrDefault("claudeSessionId", "").strip();
        StartMode startMode = startMode(a.getOrDefault("startMode", ""), id);
        return new SessionSpec(
                a.getOrDefault("name", "").strip(),
                a.getOrDefault("workDir", "").strip(),
                a.getOrDefault("model", "").strip(),
                a.getOrDefault("effort", "").strip(),
                a.getOrDefault("permissionMode", "").strip(),
                dirs,
                budget,
                id,
                startMode);
    }

    private static StartMode startMode(String value, String id) {
        String mode = value.strip();
        if (mode.isEmpty()) {
            return id.isEmpty() ? StartMode.NEW : StartMode.RESUME;
        }
        StartMode m;
        try {
            m = StartMode.valueOf(mode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Next start must be NEW, RESUME or FORK", e);
        }
        if (m != StartMode.NEW && id.isEmpty()) {
            throw new IllegalArgumentException("Next start " + m + " needs a Claude session id; choose NEW for a new conversation");
        }
        return m;
    }

    @Override
    public String nameOf(SessionSpec spec) {
        return spec.name();
    }

    @Override
    public SessionSpec withName(SessionSpec spec, String name) {
        return spec.withName(name);
    }

    /** A clone of a session that has a conversation forks it; a clone of a new session is new. */
    @Override
    public SessionSpec cloneOf(SessionSpec spec, String name) {
        SessionSpec s = spec.withName(name);
        if (s.claudeSessionId().isEmpty()) {
            return s;
        }
        return new SessionSpec(name, s.workDir(), s.model(), s.effort(), s.permissionMode(), s.addDirs(), s.budgetUsd(),
                s.claudeSessionId(), StartMode.FORK);
    }

    @Override
    public SessionSpec template(String name) {
        return SessionSpec.fresh(name, System.getProperty("user.home"));
    }

    @Override
    public String noticeSubject(String name) {
        return Notice.session(name);
    }

    @Override
    public String removalQuestion(AppState s, String name) {
        List<String> parts = new ArrayList<>();
        SessionState session = s.session(name).orElse(null);
        if (session != null && session.status() != Status.CLOSED && session.status() != Status.FAILED) {
            parts.add("stops it");
        }
        int approvals = s.pendingApprovals(name);
        if (approvals > 0) {
            parts.add("denies " + ResourceKind.count(approvals, "waiting approval"));
        }
        int panels = s.panelsBoundToSession(name).size() + (s.closedPanelOfSession(name).isPresent() ? 1 : 0);
        if (panels > 0) {
            parts.add("unbinds " + ResourceKind.count(panels, "panel"));
        }
        StringBuilder q = new StringBuilder("Remove session ").append(name).append('?');
        if (!parts.isEmpty()) {
            q.append(" This ").append(ResourceKind.sentence(parts)).append('.');
        }
        if (session != null && !session.spec().claudeSessionId().isEmpty()) {
            q.append(" The conversation stays in ~/.claude/projects and can be imported again.");
        }
        return q.toString();
    }

    @Override
    public boolean showAdvanced(Map<String, String> values) {
        return !values.getOrDefault("claudeSessionId", "").isBlank();
    }

    @Override
    public String advice(Map<String, String> values) {
        String id = values.getOrDefault("claudeSessionId", "").strip();
        if (!id.isEmpty() && values.getOrDefault("startMode", "").strip().equalsIgnoreCase(StartMode.NEW.name())) {
            return "The stored conversation " + id + " will not be continued";
        }
        return "";
    }
}
