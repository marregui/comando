package dev.marregui.comando.ui.widgets.transcript.render;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.font.TextLayout;
import java.util.ArrayList;
import java.util.List;
import javax.swing.Icon;

import tools.jackson.databind.JsonNode;

import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.view.Clip;
import dev.marregui.comando.ui.kit.format.Json;
import dev.marregui.comando.ui.kit.format.KeyText;
import dev.marregui.comando.ui.kit.format.Labels;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.text.Diffs;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.widgets.transcript.ToolText;

/**
 * A tool call's card: its header with the status, the input and the result as it folds open, and the calls a
 * sub-agent made nested under it.
 */
class ToolCallItem extends Item {
    static final String WAITING = Items.WAITING;
    static final int LAST_CHARS = 60;
    private static final int IN_X = 8;
    private static final int IN_Y = 5;
    private static final int CHILD_INDENT = 12;
    private final List<Item> children = new ArrayList<>();
    private final Fold fold = new Fold(false);
    private final long order;
    private Style built;
    private TextBox title;
    private TextBox input;
    private TextBox result;
    private Icon statusIcon;
    private int headerHeight;
    private int inputTop;
    private int resultTop;
    private int[] childTops = new int[0];
    /**
     * When the canvas first saw the call running, when it saw it end after that, and when it saw a sub-agent's
     * launch into the background; negative until then. Shared with the canvas, so a rebuilt canvas keeps them.
     */
    private final long[] times;
    /** A sub-agent launched into the background that still works, by the session's open tasks. */
    private boolean background;
    /** The result the launched sub-agent's id was read from, and that id; empty when the result is not a launch. */
    private String agentOf;
    private String agentId = "";
    /** Tool calls nested in this card at any depth, counted as they are added. */
    private int tools;
    /** The most recent nested tool call: its id and its name with its summary, clipped. */
    private String lastId = "";
    private String last = "";
    /** The summary of the input it was read from, kept until the input changes. */
    private String summaryOf;
    private String summary = "";
    private boolean waiting;
    private String statusText = "";
    private Style statusStyle;
    private TextLayout status;

    ToolCallItem(final Block.ToolCall b) {
        this(b, 0, newTimes());
    }

    ToolCallItem(final Block.ToolCall b, final long order, final long[] times) {
        super(b);
        this.order = order;
        this.times = times;
        autoOpen();
    }

    static long[] newTimes() {
        return new long[]{-1, -1, -1};
    }

    long[] times() {
        return times;
    }

    Block.ToolCall call() {
        return (Block.ToolCall) block;
    }

    /** The card's position among the cards appended to the canvas; later cards have larger values. */
    long order() {
        return order;
    }

    void addChild(final Item child) {
        children.add(child);
        invalidate();
    }

    void removeLastChild() {
        children.removeLast();
        invalidate();
    }

    @Override
    List<Item> children() {
        return children;
    }

    boolean isOpen() {
        return fold.isOpen();
    }

    boolean isRunning() {
        return call().status() == Block.ToolStatus.RUNNING;
    }

    /** Running, or a sub-agent working in the background after its call returned. */
    boolean isActive() {
        return isRunning() || background;
    }

    /** Marks the sub-agent as working in the background; true when that changed. */
    boolean setBackground(final boolean b) {
        if (background == b) {
            return false;
        }
        background = b;
        return true;
    }

    /**
     * The id of the sub-agent this call launched into the background, read from its result ("Async agent launched
     * successfully. … agentId: a58…", Claude Code 2.1.302, undocumented); empty for any other call.
     */
    String launchedAgent() {
        final Block.ToolCall c = call();
        if (!c.isSubagent() || c.status() != Block.ToolStatus.DONE) {
            return "";
        }
        if (!c.resultText().equals(agentOf)) {
            agentOf = c.resultText();
            agentId = Block.ToolCall.launchedAgent(agentOf);
        }
        return agentId;
    }

    /**
     * Starts the elapsed time of an active call at {@code nowMillis}, the first time the canvas sees it active, and
     * stops it the first time the canvas sees it ended.
     */
    void seen(final long nowMillis) {
        if (isActive()) {
            if (times[0] < 0) {
                times[0] = nowMillis;
            }
            times[1] = -1; // active again: an end seen early (a task list late or stale) was not the end
        } else if (times[0] >= 0 && times[1] < 0) {
            times[1] = nowMillis;
        }
    }

    /** Notes the first time the canvas sees the result launch the sub-agent, if it saw the call run before it. */
    void launchSeen(final long nowMillis) {
        if (times[2] < 0 && times[0] >= 0 && !launchedAgent().isEmpty()) {
            times[2] = nowMillis;
        }
    }

    /** When the canvas first saw the call running; negative while it has not. */
    long runningSince() {
        return times[0];
    }

    /** When the canvas first saw the call's result launch a sub-agent into the background; negative when it has not. */
    long launchedAt() {
        return times[2];
    }

    /** A tool call was nested in this card, at any depth; it is now the most recent one. */
    void toolAdded(final String toolUseId, final String summary) {
        tools++;
        lastId = toolUseId;
        last = summary;
    }

    /** The nested call {@code toolUseId} changed; true when it is the most recent one, whose summary is shown. */
    boolean isLastTool(final String toolUseId) {
        return lastId.equals(toolUseId);
    }

    void lastToolChanged(final String summary) {
        last = summary;
    }

    int toolCount() {
        return tools;
    }

    /** The call's one-line summary (a sub-agent's description), parsed again only when the input changed. */
    String summary() {
        final String in = call().inputJson();
        if (!in.equals(summaryOf)) {
            summaryOf = in;
            summary = ToolText.summary(call().name(), Json.parse(in));
        }
        return summary;
    }

    String lastTool() {
        return last;
    }

    /**
     * The name and summary of a nested call as a sub-agent card's status shows it, at most {@link #LAST_CHARS}
     * characters: a path keeps its end, which names it.
     */
    static String lastLabel(final Block.ToolCall c) {
        final String name = ToolText.displayName(c.name());
        final String summary = ToolText.summary(c.name(), Json.parse(c.inputJson()));
        if (summary.isEmpty()) {
            return Clip.keepStart(name, LAST_CHARS);
        }
        final int room = LAST_CHARS - name.length() - 1;
        if (room < 8) {
            return Clip.keepStart(name + " " + summary, LAST_CHARS);
        }
        return name + " " + (isPath(c.name()) ? Clip.keepEnd(summary, room) : Clip.keepStart(summary, room));
    }

    /** Marks the running call as held up by an approval card; true when that changed. */
    boolean setWaiting(final boolean w) {
        if (waiting == w) {
            return false;
        }
        waiting = w;
        return true;
    }

    /**
     * The status beside the title while the call runs: the wait for an approval, or the time it has run; a sub-agent
     * adds the tool calls it made and its latest one. Once done, empty, except for a sub-agent that did not fail:
     * how long it ran and the tool calls it made.
     */
    String statusText(final long nowMillis) {
        return statusText(nowMillis, true);
    }

    /** The status, without the latest tool call when not {@code withLast}: for a card too narrow to show it. */
    String statusText(final long nowMillis, final boolean withLast) {
        final boolean subagent = call().isSubagent();
        if (!isActive()) {
            if (!subagent || call().status() == Block.ToolStatus.ERROR) {
                return "";
            }
            // a stored conversation keeps a sub-agent's blocks apart: no blocks means no count, not none
            final String took = times[0] >= 0 && times[1] >= 0 ? " · " + Labels.elapsed(times[1] - times[0]) : "";
            return "done" + took + toolsLabel();
        }
        if (waiting && isRunning()) {
            return KeyText.of(WAITING);
        }
        final long elapsed = times[0] < 0 ? 0 : nowMillis - times[0];
        final String running = background ? "running in background" : "running";
        String status = elapsed < 1000 ? running : running + " · " + Labels.elapsed(elapsed);
        if (subagent && tools > 0) {
            status += toolsLabel() + (withLast && !last.isEmpty() ? " · " + last : "");
        }
        return status;
    }

    /** " · n tools", or nothing before the first. */
    private String toolsLabel() {
        return tools == 0 ? "" : tools == 1 ? " · 1 tool" : " · " + tools + " tools";
    }

    /**
     * Opens edits, errors and running sub-agents unless the user chose otherwise. It only ever opens: a card
     * that finishes keeps the state it had, so the blocks the user is reading stay where they are.
     */
    private void autoOpen() {
        final Block.ToolCall c = call();
        fold.openIf(isEdit(c.name()) || c.status() == Block.ToolStatus.ERROR || (c.isSubagent() && c.status() == Block.ToolStatus.RUNNING));
    }

    /** A tool whose summary is a file's path. */
    private static boolean isPath(final String name) {
        return isEdit(name) || name.equals("Read") || name.equals("NotebookEdit");
    }

    private static boolean isEdit(final String name) {
        return name.equals("Edit") || name.equals("MultiEdit") || name.equals("Write");
    }

    @Override
    void changed(final Block previous, final Block next) {
        built = null;
        autoOpen();
    }

    @Override
    Fold fold() {
        return fold;
    }

    @Override
    int doLayout(final int w, final Style s) {
        final Block.ToolCall c = call();
        if (built != s) {
            built = s;
            final JsonNode in = Json.parse(c.inputJson());
            final String summary = ToolText.summary(c.name(), in);
            final Rich t = new Rich(s.body, s.text).append(ToolText.displayName(c.name()), s.bold, s.text);
            if (c.isSubagent()) {
                t.append("  ", s.body, s.text);
                final int at = t.length();
                t.append("transcript", s.small, s.link, null, true, false).link(at, t.length(), TranscriptCanvas.SUBAGENT_SCHEME + c.toolUseId());
            }
            if (!summary.isEmpty()) {
                final int at = t.length() + 3;
                t.append("   " + summary, s.body, s.muted);
                // the summary names what the call works on: the file or address, whole even when the summary is
                // clipped; a skill, its SKILL.md where it is found (record 108)
                final String target = ToolText.target(c.name(), in);
                if (!target.isEmpty()) {
                    t.link(at, t.length(), target, s.link);
                }
            }
            title = new TextBox(t).noWrap();
            input = new TextBox(inputRich(c, in, s));
            result = new TextBox(resultRich(c, s));
            statusIcon = switch (c.status()) {
                case RUNNING -> null;
                case DONE -> Icons.get("check");
                case ERROR -> Icons.get("deny");
            };
        }
        final int inner = Math.max(40, w - 2 * IN_X);
        final int titleWidth = Math.max(40, inner - 22 - 70);
        headerHeight = Math.max(18, title.layout(titleWidth, s));
        int y = IN_Y + headerHeight;
        if (fold.isOpen()) {
            y += 6;
            inputTop = y;
            if (input.length() > 0) {
                y += input.layout(inner, s) + 6;
            }
            resultTop = y;
            if (result.length() > 0) {
                y += result.layout(inner, s) + 4;
            }
            childTops = new int[children.size()];
            for (int i = 0; i < children.size(); i++) {
                childTops[i] = y;
                y += children.get(i).layout(Math.max(40, inner - CHILD_INDENT), s);
            }
        } else if (!children.isEmpty()) {
            // a closed sub-agent card still shows its step count in the title row; nothing below
            childTops = new int[0];
        }
        return 3 + y + IN_Y + 3;
    }

    /** The unified diff an edit makes, null for a tool that edits nothing; text written over nothing is clipped. */
    private static List<String> editDiff(final Block.ToolCall c, final JsonNode in) {
        final Diffs.Edit e = Diffs.edit(c.name(), in);
        if (e == null) {
            return null;
        }
        return Diffs.unified(e.path(), e.before(), e.before().isEmpty() ? Items.clipLines(e.after(), Items.MAX_INPUT_LINES) : e.after());
    }

    private static Rich inputRich(final Block.ToolCall c, final JsonNode in, final Style s) {
        final List<String> diff = editDiff(c, in);
        if (diff != null) {
            return diff(diff, s);
        }
        return switch (c.name()) {
            // what the call is given names files and addresses as its result does: links (record 108)
            case "Bash" -> new Rich(s.monoSmall, s.accent).append("$ " + in.path("command").asString("")).linked(s.link);
            case "Agent", "Task" -> new Rich(s.monoSmall, s.text).append(in.path("prompt").asString("")).linked(s.link);
            default -> in.isObject() && !in.isEmpty()
                    ? new Rich(s.monoSmall, s.text).append(Items.clipLines(Json.pretty(in), Items.MAX_INPUT_LINES)).linked(s.link)
                    : new Rich(s.monoSmall, s.text);
        };
    }

    private static Rich diff(final List<String> unified, final Style s) {
        final List<String> lines = unified.stream().map(Rich::detab).toList();
        final StringBuilder text = new StringBuilder();
        final List<Color> backgrounds = new ArrayList<>();
        final int shown = Math.min(lines.size(), Items.MAX_INPUT_LINES);
        for (int i = 0; i < shown; i++) {
            final String l = lines.get(i);
            text.append(i == 0 ? "" : "\n").append(l);
            backgrounds.add(Diffs.isAdded(i, l) ? Theme.content().added : Diffs.isRemoved(i, l) ? Theme.content().removed : null);
        }
        if (lines.isEmpty()) {
            text.append("(no changes)");
        } else if (shown < lines.size()) {
            text.append("\n… ").append(lines.size() - shown).append(" more lines");
        }
        final Rich r = new Rich(s.monoSmall, s.text).append(text.toString()).lineBackgrounds(backgrounds);
        int at = 0;
        for (int i = 0; i < shown; i++) {
            final String l = lines.get(i);
            if (l.startsWith("@@") || Diffs.isHeader(i, l)) {
                r.color(at, at + l.length(), l.startsWith("@@") ? s.accent : s.muted);
            }
            at += l.length() + 1;
        }
        return r;
    }

    private static Rich resultRich(final Block.ToolCall c, final Style s) {
        if (c.resultText().isEmpty()) {
            return new Rich(s.monoSmall, s.muted);
        }
        if (c.isSubagent()) {
            return new Rich(s.monoSmall, s.muted).append("Report: " + Items.clipLines(c.resultText(), 60)).linked(s.link);
        }
        return new Rich(s.monoSmall, c.status() == Block.ToolStatus.ERROR ? Theme.DANGER : s.muted)
                .append(Items.clipLines(c.resultText(), Items.MAX_RESULT_LINES)).linked(s.link);
    }

    /** The status layout for {@code text}, rebuilt only when the text or the style changed. */
    private TextLayout status(final String text, final Style s) {
        if (!text.equals(statusText) || statusStyle != s) {
            statusText = text;
            statusStyle = s;
            status = new TextLayout(text, s.small, Style.FRC);
        }
        return status;
    }

    @Override
    void paint(final Graphics2D g, final int x, final int y, final Rectangle clip, final Marks marks) {
        final Style s = style();
        final int cy = y + 3;
        final int ch = height - 6;
        Items.fillRound(g, Theme.CARD, x, cy, width(), ch);
        final Icon icon = Icons.get(call().isSubagent() ? "session" : "tool");
        icon.paintIcon(marks.component(), g, x + IN_X, cy + IN_Y + 1);
        final int right = x + width() - IN_X;
        String text = statusText(marks.nowMillis());
        TextLayout st = text.isEmpty() ? null : status(text, s);
        if (st != null && st.getAdvance() > width() / 2f) {
            text = statusText(marks.nowMillis(), false); // the latest tool call gives way to the title
            st = status(text, s);
        }
        final Icon mark = background ? null : statusIcon; // a launch's result is not the sub-agent's end
        final int iconWidth = mark != null ? 16 : 0;
        int used = iconWidth + (st != null ? (iconWidth > 0 ? 6 : 0) + Math.round(st.getAdvance()) : 0);
        if (!children.isEmpty() && !fold.isOpen()) {
            final TextLayout steps = new TextLayout(children.size() + " steps", s.small, Style.FRC);
            g.setColor(s.muted);
            steps.draw(g, right - used - 6 - steps.getAdvance(), cy + IN_Y + steps.getAscent() + 1);
            used += 6 + Math.round(steps.getAdvance());
        }
        if (mark != null) {
            mark.paintIcon(marks.component(), g, right - 16, cy + IN_Y + 1);
        }
        if (st != null) {
            g.setColor(isActive() ? Theme.ATTENTION : s.muted);
            st.draw(g, right - (iconWidth > 0 ? iconWidth + 6 : 0) - st.getAdvance(), cy + IN_Y + st.getAscent() + 1);
        }
        final Shape old = g.getClip();
        final int titleRight = right - Math.max(70, used + 8);
        g.clipRect(x + IN_X + 22, cy, Math.max(0, titleRight - x - IN_X - 22), headerHeight + IN_Y);
        Paras.paintBox(g, title, x + IN_X + 22, cy + IN_Y, clip, marks);
        g.setClip(old);
        if (!fold.isOpen()) {
            return;
        }
        final int bx = x + IN_X;
        if (input.length() > 0) {
            Paras.paintBox(g, input, bx, cy + inputTop, clip, marks);
        }
        if (result.length() > 0) {
            Paras.paintBox(g, result, bx, cy + resultTop, clip, marks);
        }
        for (int i = 0; i < children.size() && i < childTops.length; i++) {
            final Item child = children.get(i);
            final int top = cy + childTops[i];
            g.setColor(Theme.CLAUDE);
            g.fillRect(bx, top, 2, child.height);
            if (top + child.height >= clip.y && top <= clip.y + clip.height) {
                child.paint(g, bx + CHILD_INDENT, top, clip, marks);
            }
        }
    }

    @Override
    void collect(final int x, final int y, final List<Placed> out) {
        if (!fold.isOpen()) {
            return;
        }
        final int cy = y + 3;
        final int bx = x + IN_X;
        if (input.length() > 0) {
            out.add(new Placed(input, bx, cy + inputTop, this));
        }
        if (result.length() > 0) {
            out.add(new Placed(result, bx, cy + resultTop, this));
        }
        for (int i = 0; i < children.size() && i < childTops.length; i++) {
            children.get(i).collect(bx + CHILD_INDENT, cy + childTops[i], out);
        }
    }

    @Override
    String headerLinkAt(final int px, final int py) {
        if (py < 3 + IN_Y + headerHeight) {
            return title == null ? null : title.linkAt(px - IN_X - 22, py - 3 - IN_Y);
        }
        if (fold.isOpen()) {
            for (int i = 0; i < children.size() && i < childTops.length; i++) {
                final Item child = children.get(i);
                final int top = 3 + childTops[i];
                if (py >= top && py < top + child.height) {
                    return child.headerLinkAt(px - IN_X - CHILD_INDENT, py - top);
                }
            }
        }
        return null;
    }

    @Override
    Item toggleAt(final int px, final int py) {
        if (py < 3 + IN_Y + headerHeight) {
            return this;
        }
        if (fold.isOpen()) {
            for (int i = 0; i < children.size() && i < childTops.length; i++) {
                final Item child = children.get(i);
                final int top = 3 + childTops[i];
                if (py >= top && py < top + child.height) {
                    return child.toggleAt(px - IN_X - CHILD_INDENT, py - top);
                }
            }
        }
        return null;
    }

    @Override
    boolean toggle() {
        fold.toggle();
        invalidate();
        return true;
    }

    @Override
    String text() {
        // decoded input values, so find matches text with quotes, backslashes or newlines as shown
        final Block.ToolCall c = call();
        return c.name() + '\n' + ToolText.values(Json.parse(c.inputJson())) + c.resultText();
    }

    @Override
    int guess(final int w, final Style s) {
        final int closed = 3 + IN_Y + 18 + IN_Y + 3; // the title row of a closed card, as doLayout measures it
        return fold.isOpen() ? closed + guessText(text(), w - 2 * IN_X, s) : closed;
    }

    /**
     * The summary line, the input fenced (a shell command, a diff, or JSON), the result fenced and clipped, then a
     * sub-agent's steps, each quoted.
     */
    @Override
    String markdown() {
        final Block.ToolCall c = call();
        final JsonNode in = Json.parse(c.inputJson());
        final StringBuilder sb = new StringBuilder("**").append(ToolText.displayName(c.name())).append("**");
        final String summary = ToolText.summary(c.name(), in);
        if (!summary.isEmpty()) {
            sb.append(" · ").append(summary);
        }
        switch (c.status()) {
            case RUNNING -> sb.append(" · running");
            case ERROR -> sb.append(" · failed");
            case DONE -> {
            }
        }
        final List<String> diff = editDiff(c, in);
        if (diff != null) {
            final List<String> shown = diff.size() > Items.MAX_INPUT_LINES ? diff.subList(0, Items.MAX_INPUT_LINES) : diff;
            final String more = diff.size() > Items.MAX_INPUT_LINES ? "\n… " + (diff.size() - Items.MAX_INPUT_LINES) + " more lines" : "";
            sb.append("\n\n").append(Items.fence("diff", diff.isEmpty() ? "(no changes)" : String.join("\n", shown) + more));
        } else if (c.name().equals("Bash")) {
            sb.append("\n\n").append(Items.fence("bash", in.path("command").asString("")));
        } else if (in.isObject() && !in.isEmpty()) {
            sb.append("\n\n").append(Items.fence("json", Items.clipLines(Json.pretty(in), Items.MAX_INPUT_LINES)));
        }
        if (!c.resultText().isEmpty()) {
            sb.append("\n\n").append(Items.fence("", Items.clipLines(c.resultText(), Items.MAX_RESULT_LINES)));
        }
        for (final Item child : children) {
            final String m = child.markdown();
            if (!m.isEmpty()) {
                sb.append("\n\n> ").append(m.replace("\n", "\n> "));
            }
        }
        return sb.toString();
    }
}
