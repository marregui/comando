package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.ui.kit.format.Labels;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.text.MarkdownHtml;
import dev.marregui.comando.ui.kit.theme.Theme;

import javax.swing.Icon;
import java.awt.Color;
import java.awt.Point;
import java.awt.Rectangle;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * One session in the sidebar's list, one line (record 90): its state icon, which a click on connects or disconnects
 * (Claude's orange while it runs, grey while stopped, faint while it starts or stops, red when it failed, one glyph at
 * one size; it spins while the session works and shows the hourglass while it waits on background work); its name,
 * dimmed while its own panel is not open, with an amber ↑ when it runs an older Claude Code than another running
 * session, the context in purple once its checkpoint is due, and the approvals waiting. Everything else a session does
 * is in its menu. Its folder, branch, context and the rest are on the card the row shows (record 87). The list paints it
 * as a cell renderer, so its icon is not live: the list finds it under a click with {@link #hit}. A connection's row
 * is the same row ({@link ConnectionRow}).
 */
final class SessionRow extends IconRow {

    /** The controls of a row: its state icon. */
    enum Control {
        POWER,
        /** The ⓘ, which shows the row's card. */
        INFO
    }

    SessionRow() {
        // one line (record 90): the state icon, which connects and disconnects, and the name; the rest is in the menu
    }

    /** The icon a working session shows in place of its state, turned by the list's timer. */
    private Icon working = Icons.get("claude");

    void working(Icon spinner) {
        working = spinner;
    }

    /** The state icon shown; for tests. */
    Icon stateIcon() {
        return power.getIcon();
    }

    /**
     * Shows {@code s}: the name dimmed when its own panel is not {@code open}, in the accent colour when {@code focused}
     * (one of its panels is the focused panel); {@code approvals} counts the waiting approvals; {@code newestCli} is
     * the newest Claude Code version a running session reported.
     */
    SessionRow show(SessionState s, boolean open, boolean focused, int approvals, boolean selected,
                    Color background, Color foreground, String newestCli) {
        setOpaque(true);
        setBackground(background);
        title.setForeground(foreground);
        title.setText(titleHtml(s, open, focused, approvals, newestCli));
        // its state is its icon: working spins, waiting on background work shows the hourglass
        power.setIcon(s.isBusy() ? working : s.isWaiting() ? Icons.get("hourglass") : Icons.get(SessionIcons.of(s.status())));
        power.setToolTipText(powerTip(s));
        return this;
    }

    /**
     * The Claude Code version the session reported when it started, "claude 2.1.303", while it runs; empty before it
     * reports one. Each start runs the CLI installed then, so sessions started before an update run the older one.
     */
    static String cliText(SessionState s) {
        String v = s.info().cliVersion();
        return v.isEmpty() || !s.status().isUsable() ? "" : "claude " + v;
    }

    /** The newest Claude Code version among the running sessions; empty when none has reported one. */
    static String newestCli(List<SessionState> sessions) {
        String newest = "";
        for (SessionState s : sessions) {
            if (!cliText(s).isEmpty() && compareVersions(s.info().cliVersion(), newest) > 0) {
                newest = s.info().cliVersion();
            }
        }
        return newest;
    }

    /** Whether {@code s} runs an older Claude Code than {@code newestCli}: it started before the CLI was updated. */
    static boolean olderCli(SessionState s, String newestCli) {
        return !cliText(s).isEmpty() && compareVersions(s.info().cliVersion(), newestCli) < 0;
    }

    /** Compares "2.1.302" and "2.1.303" by their numbers; a part that is not a number counts as 0, an empty text is oldest. */
    static int compareVersions(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return Boolean.compare(!a.isEmpty(), !b.isEmpty());
        }
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int c = Integer.compare(number(x, i), number(y, i));
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    private static int number(String[] parts, int i) {
        try {
            return i < parts.length ? Integer.parseInt(parts[i].replaceAll("\\D.*", "")) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** How full the context is, "42%", once a turn has reported it; empty before. */
    static String contextText(SessionState s) {
        return s.contextWindow() <= 0L || s.contextTokens() <= 0L ? ""
                : Math.min(100L, s.contextTokens() * 100L / s.contextWindow()) + "%";
    }

    /**
     * What the menu's Copy details and ⌘C put on the clipboard: what a decision on the session (go on, compact, stop)
     * needs, one fact a line; {@code newestCli} as in {@link #show}.
     */
    static String details(SessionState s, long nowMillis, ZoneId zone, String newestCli) {
        StringBuilder sb = new StringBuilder();
        sb.append("Session: ").append(s.name()).append('\n');
        sb.append("Path: ").append(s.spec().workDir()).append('\n');
        if (!s.branch().isEmpty()) {
            sb.append("Branch: ").append(s.branch()).append('\n');
        }
        sb.append("Session id: ").append(s.spec().claudeSessionId().isEmpty() ? "none yet" : s.spec().claudeSessionId()).append('\n');
        sb.append("Status: ").append(s.status().isUsable() ? "running" : s.status().name().toLowerCase(Locale.ROOT)).append('\n');
        if (s.status().isUsable() && s.startedMillis() > 0L) {
            sb.append("Uptime: ").append(Labels.uptime(nowMillis - s.startedMillis())).append(", since ")
                    .append(Labels.startedAt(s.startedMillis(), nowMillis, zone)).append('\n');
        }
        String context = contextText(s);
        sb.append("Context: ").append(context.isEmpty() ? "unknown until a turn ends"
                : context + " (" + Labels.tokens(s.contextTokens()) + " of " + Labels.tokens(s.contextWindow()) + " tokens)").append('\n');
        sb.append("Model: ").append(modelName(s)).append(", effort ").append(effortName(s)).append('\n');
        if (!cliText(s).isEmpty()) {
            sb.append("Claude Code: ").append(s.info().cliVersion());
            if (olderCli(s, newestCli)) {
                sb.append(", older than the ").append(newestCli).append(" another session runs; start this one again to use it");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** The control at {@code p}, a point in the cell whose bounds this row was laid out to. */
    Optional<Control> hit(Rectangle cell, Point p) {
        if (onInfo(cell, p)) {
            return Optional.of(Control.INFO);
        }
        return onIcon(cell, p) ? Optional.of(Control.POWER) : Optional.empty();
    }

    /** Where {@code control} is, in the list's coordinates, for a menu to drop from it; empty while it is hidden. */
    Rectangle bounds(Rectangle cell, Control control) {
        return control == Control.INFO ? infoBounds(cell) : iconBounds(cell);
    }

    /** The tooltip of the control at {@code p}, or empty over the text. */
    Optional<String> tip(Rectangle cell, Point p) {
        return iconTip(cell, p);
    }

    static final String COMPACT_TIP = "Compact the conversation to free context (sends " + SessionSpec.COMPACT_COMMAND + ")";

    static String freshTip(SessionState s) {
        return "Start a new conversation" + keeps(s);
    }

    /** Where the current conversation goes: the start of its id, to find it among the importable ones. */
    private static String keeps(SessionState s) {
        String id = s.spec().claudeSessionId();
        return id.isEmpty() ? "" : "; this one, " + id.substring(0, Math.min(8, id.length())) + ", stays importable";
    }

    /**
     * The model the session runs, by family: while it runs, the one its latest message ran at (a skill's {@code model}
     * shows for the rest of its turn), else the one its first message or {@code system/init} reported; else the one it
     * is set to start with, else "default" (Claude Code's).
     */
    static String modelName(SessionState s) {
        String m = reportedModel(s);
        if (m.isBlank()) {
            m = s.spec().model();
        }
        if (m.isBlank()) {
            return "default";
        }
        String lower = m.toLowerCase(Locale.ROOT);
        for (String family : SessionSpec.MODELS) {
            if (lower.contains(family)) {
                return family;
            }
        }
        return m;
    }

    /** The model the running session reported, its running turn's first; empty when it has reported none. */
    private static String reportedModel(SessionState s) {
        if (!s.status().isUsable()) {
            return "";
        }
        return !s.info().turnModel().isBlank() ? s.info().turnModel() : s.info().model();
    }

    static String modelTip(SessionState s) {
        String reported = reportedModel(s);
        String turn = s.status().isUsable() ? s.info().turnModel() : "";
        if (!turn.isBlank() && !turn.equals(s.info().model())) {
            return "Model: " + turn + " for the rest of this turn, the session's own " + s.info().model() + " after it; click to change";
        }
        return "Model: " + (!reported.isBlank() ? reported + ", as the session reported it" : modelName(s)
                + (s.spec().model().isBlank() ? ", Claude Code's" : ", set for the next start")) + "; click to change";
    }

    /**
     * The effort the session runs at: while a turn runs, the one its latest message ran at (a skill's {@code effort},
     * as the {@code deep} skill's {@code high}, lasts the rest of its turn); else the one its latest turn started at,
     * the session's own (so a {@code /effort} typed in the prompt shows); else the one it is set to; else, while it
     * runs, the one Claude Code's settings give its model; else "default".
     */
    static String effortName(SessionState s) {
        boolean usable = s.status().isUsable();
        if (usable && !s.info().turnEffort().isBlank()) {
            return s.info().turnEffort();
        }
        if (usable && !s.info().reportedEffort().isBlank()) {
            return s.info().reportedEffort();
        }
        if (!s.spec().effort().isBlank()) {
            return s.spec().effort();
        }
        return usable && !s.info().effort().isBlank() ? s.info().effort() : "default";
    }

    static String effortTip(SessionState s) {
        boolean usable = s.status().isUsable();
        String turn = usable ? s.info().turnEffort() : "";
        String own = usable && !s.info().reportedEffort().isBlank() ? s.info().reportedEffort() : "";
        if (!turn.isBlank() && !own.isBlank() && !turn.equals(own)) {
            return "Effort: " + turn + " for the rest of this turn, raised from the session's own " + own + "; click to change";
        }
        String from = !turn.isBlank() || !own.isBlank() ? "as the session runs it"
                : !s.spec().effort().isBlank() ? "set for this session"
                : usable && !s.info().effort().isBlank() ? "Claude Code's setting for this model" : "Claude Code's";
        return "Effort: " + effortName(s) + ", " + from + "; a turn may raise it; click to change";
    }

    /** What the state icon says and does. */
    static String powerTip(SessionState s) {
        String doing = s.isBusy() ? "Working: " + TabStatus.working(s) + "; " : s.isWaiting() ? "Waiting on " + s.waitingFor() + "; " : "";
        return switch (s.status()) {
            case OPEN -> (doing.isEmpty() ? "Connected" : doing + "connected") + "; click to disconnect (stops the session)";
            case OPENING -> "Starting";
            case CLOSING -> "Stopping";
            case FAILED -> "Failed" + (s.error().isEmpty() ? "" : ": " + s.error()) + "; click to connect (starts the session)";
            case CLOSED -> "Disconnected; click to connect (starts the session)";
        };
    }

    /**
     * The name, dimmed when its own panel is not open, in the accent colour while one of its panels has the focus; an
     * amber ↑ when it runs an older Claude Code than another running session ({@code newestCli} as in {@link #show}); the
     * context in purple once the checkpoint is due; the approvals waiting. Never wraps, clipped at the edge.
     */
    static String titleHtml(SessionState s, boolean open, boolean focused, int approvals, String newestCli) {
        String older = olderCli(s, newestCli) ? "&nbsp;<span style='color:" + Theme.hex(Theme.ATTENTION) + "'>↑</span>" : "";
        String due = s.reflection().due() && SessionBar.showsCheckpoint(s)
                ? "&nbsp;<span style='color:" + Theme.hex(Theme.THINKING) + "'>" + (contextText(s).isEmpty() ? "◆" : contextText(s)) + "</span>" : "";
        String badge = approvals == 0 ? "" : "&nbsp;<span style='color:" + Theme.hex(Theme.ATTENTION) + "'>⚑" + approvals + "</span>";
        String name = focused ? "<b style='color:" + Theme.hex(Theme.tint().body) + "'>" : open ? "<b>" : "<b style='color:" + Theme.hex(Theme.MUTED) + "'>";
        return "<html><nobr>" + name + MarkdownHtml.escape(s.name()) + "</b>" + older + due + badge + "</nobr></html>";
    }
}
