package dev.marregui.comando.core.resource;

import dev.marregui.comando.core.model.HomeDir;
import dev.marregui.comando.core.model.InstanceName;
import dev.marregui.comando.core.model.KitRoot;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.StartMode;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.SessionState;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Claude Code sessions as a resource kind. The dialog asks first the session's name, what it is for, then which
 * conversation it continues and how Claude runs, each default shown in its choice with where it comes from; the folder
 * it runs in, the extra folders and the checkpoint prompt are under More, as most sessions keep the kit's folder. The budget is not asked: it only binds
 * on API billing, and a stored one is kept.
 */
public final class SessionKind implements ResourceKind<SessionSpec> {

    public static final SessionKind INSTANCE = new SessionKind();

    /** The permission modes the CLI takes ({@code --permission-mode}), with what each does. */
    static final Map<String, String> PERMISSION_MODES = permissionModes();

    /**
     * What a session gets when it names no model, effort or permission mode: the kit's {@code settings.json}
     * ({@code model}, {@code effortLevel}, {@code permissions.defaultMode}), as the application read it; empty for
     * what it names none of.
     */
    public record Defaults(String model, String effort, String permissionMode) {

        public static final Defaults NONE = new Defaults("", "", "");

        public Defaults {
            model = model == null ? "" : model.strip();
            effort = effort == null ? "" : effort.strip();
            permissionMode = permissionMode == null ? "" : permissionMode.strip();
        }
    }

    private volatile Defaults defaults = Defaults.NONE;
    /** The model Claude Code chose for a session that named none, as a session's start reported it; empty until then. */
    private volatile String cliModel = "";

    private SessionKind() {
    }

    /** The kit's defaults, which the dialog names beside Model, Effort and Permission mode. */
    public void defaults(Defaults d) {
        defaults = Objects.requireNonNullElse(d, Defaults.NONE);
    }

    /** The model Claude Code runs a session on when neither the session nor the kit names one, once a start said it. */
    public void cliModel(String model) {
        cliModel = model == null ? "" : model.strip();
    }

    static List<Attribute> attributes(Defaults d, String cliModel) {
        String model = !d.model().isEmpty() ? d.model() + " · the kit's default"
                : cliModel.isEmpty() ? "Claude Code's default" : cliModel + " · Claude Code's default";
        String effort = d.effort().isEmpty() ? "Claude Code's default" : d.effort() + " · the kit's default";
        String permission = d.permissionMode().isEmpty() ? "default · asks before each tool"
                : d.permissionMode() + " · the kit's default: " + PERMISSION_MODES.getOrDefault(d.permissionMode(), "as the CLI defines");
        String follows = "the default follows the kit; a choice here holds for this session";
        return List.of(
                Attribute.of("name", "Name", Attribute.Kind.TEXT, true, "what this session is for: it names its panel and its ongoing conversation")
                        .about("A session is one ongoing line of work: you stop it, resume it, checkpoint it. Its name is how you find it again, "
                                + "in its panel, the sidebar and Claude Code. Name it by purpose: edge-release, reviews."),
                Attribute.choice("startMode", "Conversation", List.of("NEW", "RESUME", "FORK"),
                                "NEW starts empty; RESUME continues the stored one; FORK copies it")
                        .about("Which conversation the next start opens. NEW: an empty one. RESUME: the stored one, where it left off. "
                                + "FORK: a copy of it, so the original stays as it was."),
                Attribute.of("claudeSessionId", "Stored conversation", Attribute.Kind.TEXT, false,
                                "set after the first start, or by Sessions ▸ Import…")
                        .about("The Claude Code conversation the session continues. comando sets it at the first start; Sessions ▸ Import… "
                                + "sets it from a stored one."),
                Attribute.openChoice("model", "Model", orDefault(SessionSpec.MODELS), follows)
                        .about("The Claude model the session uses. The default follows the kit's settings.json, so a later change there "
                                + "reaches the session; a model picked here holds for this session.")
                        .whenEmpty(model),
                Attribute.choice("effort", "Effort", orDefault(SessionSpec.EFFORTS), follows)
                        .about("How much the model thinks before answering: higher is slower and more thorough. The default follows the kit's "
                                + "settings.json; the effort chip changes it while the session runs.")
                        .whenEmpty(effort),
                Attribute.choice("permissionMode", "Permission mode", orDefault(List.copyOf(PERMISSION_MODES.keySet())), follows)
                        .about("Whether Claude asks before running a tool. default asks each time, acceptEdits edits without asking, plan "
                                + "only plans, bypassPermissions never asks. The default follows the kit's settings.json.")
                        .whenEmpty(permission),
                Attribute.of("workDir", "Working directory", Attribute.Kind.DIRECTORY, true,
                                "this folder's CLAUDE.md and git branch; the kit's by default")
                        .about("The folder the session starts in: its CLAUDE.md, git branch and stored conversation come from there. "
                                + "Keep the kit's unless the session needs a project's own CLAUDE.md and branch.")
                        .inAdvanced(),
                Attribute.of("addDirs", "Extra folders", Attribute.Kind.DIRECTORIES, false,
                                "other folders Claude may read and change; separated by " + File.pathSeparator)
                        .about("Other folders Claude may read and change without asking path by path.")
                        .inAdvanced(),
                Attribute.of("checkpoint", "Checkpoint prompt", Attribute.Kind.TEXT, false,
                                "what Checkpoint sends before a fresh conversation; empty hides the button")
                        .about("What the Checkpoint button sends, the kit's /reflect by default; then comando carries the summary into a "
                                + "fresh conversation, so a long session starts light again.")
                        .inAdvanced());
    }

    private static Map<String, String> permissionModes() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("default", "asks before each tool");
        m.put("acceptEdits", "edits files without asking and asks for the rest");
        m.put("plan", "plans only and changes nothing");
        m.put("auto", "decides when to ask");
        m.put("dontAsk", "refuses what the settings do not allow, without asking");
        m.put("bypassPermissions", "runs every tool unasked");
        return Collections.unmodifiableMap(m);
    }

    /** The choices with the empty one, the CLI's default, first. */
    private static List<String> orDefault(List<String> choices) {
        List<String> all = new ArrayList<>(choices.size() + 1);
        all.add("");
        all.addAll(choices);
        return List.copyOf(all);
    }

    @Override
    public String noun() {
        return "session";
    }

    @Override
    public List<Attribute> attributes() {
        return attributes(defaults, cliModel);
    }

    /** The section More's name: what it holds is set once and seldom changed, not advanced. */
    @Override
    public String advancedLabel() {
        return "More";
    }

    /** Where every session of this comando runs: its kit, brain and instance, which a session does not choose. */
    @Override
    public String context() {
        KitRoot root = KitRoot.current();
        String brain = root.brain().map(b -> " · brain " + HomeDir.shorten(b.toString())).orElse("");
        return "Runs on " + (root.kit().isPresent() ? "kit " : "folder ") + HomeDir.shorten(root.dir().toString()) + brain
                + " · instance " + InstanceName.display(InstanceName.current());
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
        m.put("checkpoint", s.checkpoint());
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
                dirs.add(HomeDir.expand(d.strip()));
            }
        }
        String id = a.getOrDefault("claudeSessionId", "").strip();
        StartMode startMode = startMode(a.getOrDefault("startMode", ""), id);
        return new SessionSpec(
                a.getOrDefault("name", "").strip(),
                HomeDir.expand(a.getOrDefault("workDir", "").strip()),
                a.getOrDefault("model", "").strip(),
                a.getOrDefault("effort", "").strip(),
                a.getOrDefault("permissionMode", "").strip(),
                dirs,
                budget,
                id,
                startMode,
                a.getOrDefault("checkpoint", ""),
                "");
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
            throw new IllegalArgumentException("Conversation must be NEW, RESUME or FORK", e);
        }
        if (m != StartMode.NEW && id.isEmpty()) {
            throw new IllegalArgumentException("Conversation " + m + " needs a stored conversation; choose NEW for an empty one");
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
                s.claudeSessionId(), StartMode.FORK, s.checkpoint(), s.carried());
    }

    /** A new session runs in the kit's folder, as the user's sessions do; the user's home without a root. */
    @Override
    public SessionSpec template(String name) {
        return SessionSpec.fresh(name, KitRoot.current().dir().toString());
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
        // its own panel goes with it (record 88); documents attached to it stay, detached
        s.homePanel(name).or(() -> s.closedPanelOfSession(name)).ifPresent(p -> parts.add("removes its panel " + p.title() + " with its text"));
        long documents = s.panelsBoundToSession(name).stream().filter(p -> p.document()).count();
        if (documents > 0) {
            parts.add("detaches " + ResourceKind.count((int) documents, "document"));
        }
        StringBuilder q = new StringBuilder("Remove session ").append(name).append('?');
        if (!parts.isEmpty()) {
            q.append(" This ").append(ResourceKind.sentence(parts)).append('.');
        }
        if (session != null && !session.spec().claudeSessionId().isEmpty()) {
            q.append(" The conversation stays in ").append(HomeDir.shorten(HomeDir.claudeDir().resolve("projects").toString()))
                    .append(" and can be imported again.");
        }
        return q.toString();
    }

    @Override
    public boolean showAdvanced(Map<String, String> values) {
        String dir = values.getOrDefault("workDir", "").strip();
        return !dir.isEmpty() && !KitRoot.current().isRoot(HomeDir.expand(dir)) || !values.getOrDefault("addDirs", "").isBlank()
                || !values.getOrDefault("checkpoint", SessionSpec.DEFAULT_CHECKPOINT).equals(SessionSpec.DEFAULT_CHECKPOINT);
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
