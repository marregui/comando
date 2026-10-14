package dev.marregui.comando.ui.widgets.transcript.render;

import com.fasterxml.jackson.databind.JsonNode;
import dev.marregui.comando.core.result.Formats;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.ui.kit.format.Labels;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.text.Diffs;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.widgets.transcript.ToolText;
import dev.marregui.comando.ui.widgets.transcript.doc.MarkdownDoc;
import dev.marregui.comando.ui.widgets.transcript.doc.Para;

import javax.swing.Icon;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.font.TextLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** The item for each block kind. */
final class Items {

    static final int MAX_RESULT_LINES = 400;
    static final int MAX_INPUT_LINES = 300;
    static final int MAX_NOTE_CHARS = 600;

    private Items() {
    }

    static Item of(Block b) {
        return switch (b) {
            case Block.UserPrompt p -> new PromptItem(p);
            case Block.AssistantText t -> new TextItem(t);
            case Block.Thinking t -> new ThinkingItem(t);
            case Block.ToolCall c -> new ToolItem(c);
            case Block.Note n -> new NoteItem(n);
            case Block.TurnEnd t -> new TurnItem(t);
        };
    }

    static String clipLines(String s, int max) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n' && ++count == max) {
                int rest = 1;
                for (int j = i + 1; j < s.length(); j++) {
                    if (s.charAt(j) == '\n') {
                        rest++;
                    }
                }
                return s.substring(0, i) + "\n… " + rest + " more lines";
            }
        }
        return s;
    }

    /** {@code body} in a fenced code block whose fence is longer than any backtick run inside it. */
    static String fence(String language, String body) {
        int longest = 0;
        int run = 0;
        for (int i = 0; i < body.length(); i++) {
            run = body.charAt(i) == '`' ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        String f = "`".repeat(Math.max(3, longest + 1));
        return f + language + "\n" + body + (body.endsWith("\n") ? "" : "\n") + f;
    }

    static void fillRound(Graphics2D g, Color c, int x, int y, int w, int h) {
        g.setColor(c);
        g.fillRoundRect(x, y, w, h, Theme.RADIUS * 2, Theme.RADIUS * 2);
    }

    // ---- prompt

    static final class PromptItem extends Item {
        private static final int TOP = 10;
        private static final int BOTTOM = 4;
        private static final int IN_X = 10;
        private static final int IN_Y = 6;
        private TextBox origin;
        private TextBox text;
        private Style built;

        PromptItem(Block.UserPrompt b) {
            super(b);
        }

        private Block.UserPrompt prompt() {
            return (Block.UserPrompt) block;
        }

        @Override
        void changed(Block previous, Block next) {
            built = null;
        }

        @Override
        int doLayout(int w, Style s) {
            if (built != s) {
                built = s;
                String o = prompt().origin();
                String who = o.isEmpty() ? "you" : o.equals("subagent") ? "task for the subagent" : "you · " + o;
                origin = new TextBox(new Rich(s.small, s.muted).append(who));
                text = new TextBox(new Rich(s.mono, s.text).append(prompt().text()));
            }
            int inner = Math.max(40, w - 2 * IN_X);
            return TOP + IN_Y + origin.layout(inner, s) + 2 + text.layout(inner, s) + IN_Y + BOTTOM;
        }

        @Override
        void paint(Graphics2D g, int x, int y, Rectangle clip, Marks marks) {
            fillRound(g, Theme.USER_BUBBLE, x, y + TOP, width(), height - TOP - BOTTOM);
            Paras.paintBox(g, origin, x + IN_X, y + TOP + IN_Y, clip, marks);
            Paras.paintBox(g, text, x + IN_X, y + TOP + IN_Y + origin.height() + 2, clip, marks);
        }

        @Override
        void collect(int x, int y, List<Placed> out) {
            out.add(new Placed(text, x + IN_X, y + TOP + IN_Y + origin.height() + 2, this));
        }

        @Override
        String text() {
            return prompt().text();
        }

        @Override
        String markdown() {
            return "> " + prompt().text().replace("\n", "\n> ");
        }
    }

    // ---- assistant text

    static final class TextItem extends Item {
        private List<Paras.View> views = List.of();
        private int[] tops = new int[0];
        /** The answer that ends its turn: its text reads in gold. */
        private boolean conclusion;

        boolean isConclusion() {
            return conclusion;
        }

        void conclusion() {
            if (!conclusion) {
                conclusion = true;
                invalidate();
            }
        }

        TextItem(Block.AssistantText b) {
            super(b);
            views = Paras.reuse(MarkdownDoc.parse(b.text()), List.of());
        }

        @Override
        void changed(Block previous, Block next) {
            String before = ((Block.AssistantText) previous).text();
            String after = ((Block.AssistantText) next).text();
            if (!before.equals(after)) {
                views = Paras.reuse(MarkdownDoc.parse(after), views);
            }
        }

        @Override
        int doLayout(int w, Style style) {
            Style s = conclusion ? style.conclusion() : style;
            tops = new int[views.size()];
            int y = 4;
            for (int i = 0; i < views.size(); i++) {
                if (i > 0) {
                    boolean listRun = views.get(i).para() instanceof Para.Text t
                            && t.kind() == Para.Kind.ITEM
                            && views.get(i - 1).para() instanceof Para.Text p
                            && p.kind() == Para.Kind.ITEM;
                    y += listRun ? s.paraGap / 3 : s.paraGap;
                }
                tops[i] = y;
                y += views.get(i).layout(w, s);
            }
            return y + 4;
        }

        @Override
        void paint(Graphics2D g, int x, int y, Rectangle clip, Marks marks) {
            for (int i = 0; i < views.size(); i++) {
                int top = y + tops[i];
                int bottom = i + 1 < views.size() ? y + tops[i + 1] : y + height;
                if (bottom < clip.y || top > clip.y + clip.height) {
                    continue;
                }
                views.get(i).paint(g, x, top, clip, marks);
            }
        }

        @Override
        void collect(int x, int y, List<Placed> out) {
            for (int i = 0; i < views.size(); i++) {
                views.get(i).collect(x, y + tops[i], this, out);
            }
        }

        @Override
        String text() {
            return ((Block.AssistantText) block).text();
        }
    }

    // ---- thinking

    static final class ThinkingItem extends Item {
        private final Fold fold;
        private TextBox header;
        /** One box per line of the text: while it streams, only the growing last line is laid out again. */
        private List<TextBox> body = List.of();
        private List<String> bodyLines = List.of();
        private int[] bodyTops = new int[0];
        private Style built;
        private boolean stale = true;

        ThinkingItem(Block.Thinking b) {
            super(b);
            fold = new Fold(live(b));
        }

        /** Open while it streams, so the reasoning can be followed; closed once the final block arrives. */
        private static boolean live(Block.Thinking t) {
            return t.streaming() && !t.text().isEmpty();
        }

        private Block.Thinking thinking() {
            return (Block.Thinking) block;
        }

        @Override
        void changed(Block previous, Block next) {
            stale = true;
            fold.follow(live((Block.Thinking) next));
        }

        @Override
        Fold fold() {
            return fold;
        }

        @Override
        void foldChanged() {
            stale = true;
            invalidate();
        }

        boolean isOpen() {
            return fold.isOpen();
        }

        @Override
        int doLayout(int w, Style s) {
            if (built != s || stale) {
                Block.Thinking t = thinking();
                String tokens = t.tokens() > 0 ? " · " + Labels.tokens(t.tokens()) + " tokens" : "";
                String arrow = t.text().isEmpty() ? "" : fold.isOpen() ? "  ▾" : "  ▸";
                header = new TextBox(new Rich(s.body, Theme.THINKING).append((t.streaming() ? "Thinking…" : "Thought") + tokens + arrow));
                List<String> lines = List.of(t.text().split("\n", -1));
                List<TextBox> boxes = new ArrayList<>(lines.size());
                for (int i = 0; i < lines.size(); i++) {
                    boolean same = built == s && i < bodyLines.size() && bodyLines.get(i).equals(lines.get(i));
                    boxes.add(same ? body.get(i) : new TextBox(new Rich(s.italic, s.muted).append(lines.get(i))));
                }
                body = boxes;
                bodyLines = lines;
                built = s;
                stale = false;
            }
            int inner = Math.max(40, w - 22);
            int h = 2 + header.layout(inner, s) + 2;
            if (shown()) {
                bodyTops = new int[body.size()];
                for (int i = 0; i < body.size(); i++) {
                    bodyTops[i] = h;
                    h += body.get(i).layout(inner, s);
                }
                h += 4;
            }
            return h;
        }

        private boolean shown() {
            return fold.isOpen() && !thinking().text().isEmpty();
        }

        @Override
        void paint(Graphics2D g, int x, int y, Rectangle clip, Marks marks) {
            Icons.get("thinking").paintIcon(marks.component(), g, x, y + 3);
            Paras.paintBox(g, header, x + 22, y + 2, clip, marks);
            if (!shown()) {
                return;
            }
            for (int i = 0; i < body.size() && i < bodyTops.length; i++) {
                TextBox box = body.get(i);
                int top = y + bodyTops[i];
                if (top + box.height() >= clip.y && top <= clip.y + clip.height) {
                    Paras.paintBox(g, box, x + 22, top, clip, marks);
                }
            }
        }

        @Override
        void collect(int x, int y, List<Placed> out) {
            if (!shown()) {
                return;
            }
            for (int i = 0; i < body.size() && i < bodyTops.length; i++) {
                out.add(new Placed(body.get(i), x + 22, y + bodyTops[i], this));
            }
        }

        @Override
        Item toggleAt(int px, int py) {
            return py < 4 + header.height() ? this : null;
        }

        @Override
        boolean toggle() {
            if (thinking().text().isEmpty()) {
                return false;
            }
            fold.toggle();
            foldChanged();
            return true;
        }

        @Override
        String text() {
            return thinking().text();
        }

        @Override
        String markdown() {
            return "";
        }
    }

    // ---- tool call

    static final class ToolItem extends Item {
        static final String WAITING = "waiting for your approval · ⌃2";
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
        /** When the canvas first saw the call running; negative while it has not. */
        private long runningSince = -1;
        private boolean waiting;
        private String statusText = "";
        private Style statusStyle;
        private TextLayout status;

        ToolItem(Block.ToolCall b) {
            this(b, 0);
        }

        ToolItem(Block.ToolCall b, long order) {
            super(b);
            this.order = order;
            autoOpen();
        }

        Block.ToolCall call() {
            return (Block.ToolCall) block;
        }

        /** The card's position among the cards appended to the canvas; later cards have larger values. */
        long order() {
            return order;
        }

        void addChild(Item child) {
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

        /** Starts the elapsed time of a running call at {@code nowMillis}, the first time the canvas sees it running. */
        void seen(long nowMillis) {
            if (isRunning() && runningSince < 0) {
                runningSince = nowMillis;
            }
        }

        /** Marks the running call as held up by an approval card; true when that changed. */
        boolean setWaiting(boolean w) {
            if (waiting == w) {
                return false;
            }
            waiting = w;
            return true;
        }

        /** The status beside the title while the call runs: the wait for an approval, or the time it has run; empty once done. */
        String statusText(long nowMillis) {
            if (!isRunning()) {
                return "";
            }
            if (waiting) {
                return WAITING;
            }
            long elapsed = runningSince < 0 ? 0 : nowMillis - runningSince;
            return elapsed < 1000 ? "running" : "running · " + Labels.elapsed(elapsed);
        }

        /**
         * Opens edits, errors and running subagents unless the user chose otherwise. It only ever opens: a card
         * that finishes keeps the state it had, so the blocks the user is reading stay where they are.
         */
        private void autoOpen() {
            Block.ToolCall c = call();
            fold.openIf(isEdit(c.name()) || c.status() == Block.ToolStatus.ERROR || (c.isSubagent() && c.status() == Block.ToolStatus.RUNNING));
        }

        private static boolean isEdit(String name) {
            return name.equals("Edit") || name.equals("MultiEdit") || name.equals("Write");
        }

        @Override
        void changed(Block previous, Block next) {
            built = null;
            autoOpen();
        }

        @Override
        Fold fold() {
            return fold;
        }

        @Override
        int doLayout(int w, Style s) {
            Block.ToolCall c = call();
            if (built != s) {
                built = s;
                JsonNode in = ToolText.parse(c.inputJson());
                String summary = ToolText.summary(c.name(), in);
                Rich t = new Rich(s.body, s.text).append(ToolText.displayName(c.name()), s.bold, s.text);
                if (!summary.isEmpty()) {
                    t.append("   " + summary, s.body, s.muted);
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
            int inner = Math.max(40, w - 2 * IN_X);
            int titleWidth = Math.max(40, inner - 22 - 70);
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
                // a closed subagent card still shows its step count in the title row; nothing below
                childTops = new int[0];
            }
            return 3 + y + IN_Y + 3;
        }

        /** The unified diff an edit makes, null for a tool that edits nothing. */
        private static List<String> editDiff(Block.ToolCall c, JsonNode in) {
            return switch (c.name()) {
                case "Edit" -> Diffs.unified(in.path("file_path").asText(""), in.path("old_string").asText(""), in.path("new_string").asText(""));
                case "MultiEdit" -> {
                    StringBuilder before = new StringBuilder();
                    StringBuilder after = new StringBuilder();
                    for (JsonNode e : in.path("edits")) {
                        before.append(e.path("old_string").asText("")).append("\n…\n");
                        after.append(e.path("new_string").asText("")).append("\n…\n");
                    }
                    yield Diffs.unified(in.path("file_path").asText(""), before.toString(), after.toString());
                }
                case "Write" -> Diffs.unified(in.path("file_path").asText(""), "", clipLines(in.path("content").asText(""), MAX_INPUT_LINES));
                default -> null;
            };
        }

        private static Rich inputRich(Block.ToolCall c, JsonNode in, Style s) {
            List<String> diff = editDiff(c, in);
            if (diff != null) {
                return diff(diff, s);
            }
            return switch (c.name()) {
                case "Bash" -> new Rich(s.monoSmall, s.accent).append("$ " + in.path("command").asText(""));
                case "Agent", "Task" -> new Rich(s.monoSmall, s.text).append(in.path("prompt").asText(""));
                default -> in.isObject() && !in.isEmpty()
                        ? new Rich(s.monoSmall, s.text).append(clipLines(ToolText.pretty(in), MAX_INPUT_LINES))
                        : new Rich(s.monoSmall, s.text);
            };
        }

        private static Rich diff(List<String> unified, Style s) {
            List<String> lines = unified.stream().map(Rich::detab).toList();
            StringBuilder text = new StringBuilder();
            List<Color> backgrounds = new ArrayList<>();
            int shown = Math.min(lines.size(), MAX_INPUT_LINES);
            for (int i = 0; i < shown; i++) {
                String l = lines.get(i);
                text.append(i == 0 ? "" : "\n").append(l);
                backgrounds.add(Diffs.isAdded(i, l) ? Theme.DIFF_ADDED : Diffs.isRemoved(i, l) ? Theme.DIFF_REMOVED : null);
            }
            if (lines.isEmpty()) {
                text.append("(no changes)");
            } else if (shown < lines.size()) {
                text.append("\n… ").append(lines.size() - shown).append(" more lines");
            }
            Rich r = new Rich(s.monoSmall, s.text).append(text.toString()).lineBackgrounds(backgrounds);
            int at = 0;
            for (int i = 0; i < shown; i++) {
                String l = lines.get(i);
                if (l.startsWith("@@") || Diffs.isHeader(i, l)) {
                    r.color(at, at + l.length(), l.startsWith("@@") ? s.accent : s.muted);
                }
                at += l.length() + 1;
            }
            return r;
        }

        private static Rich resultRich(Block.ToolCall c, Style s) {
            if (c.resultText().isEmpty()) {
                return new Rich(s.monoSmall, s.muted);
            }
            if (c.isSubagent()) {
                return new Rich(s.monoSmall, s.muted).append("Report: " + clipLines(c.resultText(), 60));
            }
            return new Rich(s.monoSmall, c.status() == Block.ToolStatus.ERROR ? Theme.DANGER : s.muted)
                    .append(clipLines(c.resultText(), MAX_RESULT_LINES));
        }

        /** The status layout for {@code text}, rebuilt only when the text or the style changed. */
        private TextLayout status(String text, Style s) {
            if (!text.equals(statusText) || statusStyle != s) {
                statusText = text;
                statusStyle = s;
                status = new TextLayout(text, s.small, Style.FRC);
            }
            return status;
        }

        @Override
        void paint(Graphics2D g, int x, int y, Rectangle clip, Marks marks) {
            Style s = style();
            int cy = y + 3;
            int ch = height - 6;
            fillRound(g, Theme.CARD, x, cy, width(), ch);
            Icon icon = Icons.get(call().isSubagent() ? "session" : "tool");
            icon.paintIcon(marks.component(), g, x + IN_X, cy + IN_Y + 1);
            int right = x + width() - IN_X;
            String text = statusText(marks.nowMillis());
            TextLayout st = text.isEmpty() ? null : status(text, s);
            int used = st != null ? Math.round(st.getAdvance()) : statusIcon != null ? 16 : 0;
            if (!children.isEmpty() && !fold.isOpen()) {
                TextLayout steps = new TextLayout(children.size() + " steps", s.small, Style.FRC);
                g.setColor(s.muted);
                steps.draw(g, right - used - 6 - steps.getAdvance(), cy + IN_Y + steps.getAscent() + 1);
                used += 6 + Math.round(steps.getAdvance());
            }
            if (st != null) {
                g.setColor(Theme.WARN);
                st.draw(g, right - st.getAdvance(), cy + IN_Y + st.getAscent() + 1);
            } else if (statusIcon != null) {
                statusIcon.paintIcon(marks.component(), g, right - 16, cy + IN_Y + 1);
            }
            Shape old = g.getClip();
            int titleRight = right - Math.max(70, used + 8);
            g.clipRect(x + IN_X + 22, cy, Math.max(0, titleRight - x - IN_X - 22), headerHeight + IN_Y);
            Paras.paintBox(g, title, x + IN_X + 22, cy + IN_Y, clip, marks);
            g.setClip(old);
            if (!fold.isOpen()) {
                return;
            }
            int bx = x + IN_X;
            if (input.length() > 0) {
                Paras.paintBox(g, input, bx, cy + inputTop, clip, marks);
            }
            if (result.length() > 0) {
                Paras.paintBox(g, result, bx, cy + resultTop, clip, marks);
            }
            for (int i = 0; i < children.size() && i < childTops.length; i++) {
                Item child = children.get(i);
                int top = cy + childTops[i];
                g.setColor(Theme.CLAUDE);
                g.fillRect(bx, top, 2, child.height);
                if (top + child.height >= clip.y && top <= clip.y + clip.height) {
                    child.paint(g, bx + CHILD_INDENT, top, clip, marks);
                }
            }
        }

        @Override
        void collect(int x, int y, List<Placed> out) {
            if (!fold.isOpen()) {
                return;
            }
            int cy = y + 3;
            int bx = x + IN_X;
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
        Item toggleAt(int px, int py) {
            if (py < 3 + IN_Y + headerHeight) {
                return this;
            }
            if (fold.isOpen()) {
                for (int i = 0; i < children.size() && i < childTops.length; i++) {
                    Item child = children.get(i);
                    int top = 3 + childTops[i];
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
            Block.ToolCall c = call();
            return c.name() + '\n' + ToolText.values(ToolText.parse(c.inputJson())) + c.resultText();
        }

        /** The summary line, the input fenced (a shell command, a diff, or JSON) and the result fenced and clipped. */
        @Override
        String markdown() {
            Block.ToolCall c = call();
            JsonNode in = ToolText.parse(c.inputJson());
            StringBuilder sb = new StringBuilder("**").append(ToolText.displayName(c.name())).append("**");
            String summary = ToolText.summary(c.name(), in);
            if (!summary.isEmpty()) {
                sb.append(" · ").append(summary);
            }
            switch (c.status()) {
                case RUNNING -> sb.append(" · running");
                case ERROR -> sb.append(" · failed");
                case DONE -> {
                }
            }
            List<String> diff = editDiff(c, in);
            if (diff != null) {
                List<String> shown = diff.size() > MAX_INPUT_LINES ? diff.subList(0, MAX_INPUT_LINES) : diff;
                String more = diff.size() > MAX_INPUT_LINES ? "\n… " + (diff.size() - MAX_INPUT_LINES) + " more lines" : "";
                sb.append("\n\n").append(fence("diff", diff.isEmpty() ? "(no changes)" : String.join("\n", shown) + more));
            } else if (c.name().equals("Bash")) {
                sb.append("\n\n").append(fence("bash", in.path("command").asText("")));
            } else if (in.isObject() && !in.isEmpty()) {
                sb.append("\n\n").append(fence("json", clipLines(ToolText.pretty(in), MAX_INPUT_LINES)));
            }
            if (!c.resultText().isEmpty()) {
                sb.append("\n\n").append(fence("", clipLines(c.resultText(), MAX_RESULT_LINES)));
            }
            return sb.toString();
        }
    }

    // ---- notes and turn ends

    static final class NoteItem extends Item {
        private static final int TEXT_X = 18;
        private static final int ARROW = 16;
        /** A hook inside a group shows its whole text and has no fold of its own. */
        private final boolean member;
        private final Fold fold;
        private TextBox line;
        private TextBox full;
        private TextLayout arrowOpen;
        private TextLayout arrowClosed;
        private float lineWidth;
        private boolean more;
        private boolean expandable;
        private Style built;

        NoteItem(Block.Note b) {
            this(b, false);
        }

        NoteItem(Block.Note b, boolean member) {
            super(b);
            this.member = member;
            fold = new Fold(member || b.kind() == Block.NoteKind.ERROR);
        }

        private Block.Note note() {
            return (Block.Note) block;
        }

        /** A hook's output, shown inside a hook group. */
        boolean isHook() {
            return member;
        }

        /** The hook's name as the stream gave it ({@code SessionStart:startup}); empty for other notes. */
        String hookName() {
            String t = note().text();
            int sep = t.indexOf(": ");
            return note().kind() != Block.NoteKind.HOOK || sep < 0 ? "" : t.substring(0, sep);
        }

        /** The hook event: the hook's name without its matcher. */
        String hookEvent() {
            String name = hookName();
            int colon = name.indexOf(':');
            return colon < 0 ? name : name.substring(0, colon);
        }

        /** The text as shown, found and copied: a hook's JSON reply reads as the context it adds. */
        String shown() {
            Block.Note n = note();
            if (n.kind() != Block.NoteKind.HOOK) {
                return n.text();
            }
            String name = hookName();
            String out = name.isEmpty() ? n.text() : n.text().substring(name.length() + 2);
            String context = additionalContext(out);
            return context == null ? n.text() : name + ": " + context;
        }

        /**
         * The {@code hookSpecificOutput.additionalContext} of a hook's JSON reply when that is all the reply
         * says; null otherwise, so a reply with anything more is shown whole.
         */
        static String additionalContext(String out) {
            String t = out.strip();
            if (!t.startsWith("{")) {
                return null;
            }
            JsonNode root = ToolText.parse(t);
            JsonNode specific = root.path("hookSpecificOutput");
            if (root.size() != 1 || !specific.isObject() || !specific.path("additionalContext").isTextual()) {
                return null;
            }
            for (var field : specific.properties()) {
                if (!field.getKey().equals("additionalContext") && !field.getKey().equals("hookEventName")) {
                    return null;
                }
            }
            return specific.path("additionalContext").asText();
        }

        @Override
        void changed(Block previous, Block next) {
            built = null;
        }

        @Override
        Fold fold() {
            return member ? null : fold;
        }

        boolean isOpen() {
            return fold.isOpen();
        }

        @Override
        int doLayout(int w, Style s) {
            if (built != s) {
                built = s;
                Block.Note n = note();
                Color color = n.kind() == Block.NoteKind.ERROR ? Theme.DANGER : s.muted;
                String t = shown();
                int eol = t.indexOf('\n');
                more = eol >= 0;
                line = new TextBox(new Rich(s.small, color).append(more ? t.substring(0, eol) : t)).noWrap();
                full = new TextBox(new Rich(s.small, color).append(clipLines(t, MAX_RESULT_LINES)));
                lineWidth = line.naturalWidth();
                arrowOpen = new TextLayout("▾", s.small, Style.FRC);
                arrowClosed = new TextLayout("▸", s.small, Style.FRC);
            }
            int avail = textWidth(w);
            expandable = !member && (more || lineWidth > avail);
            int h = line.layout(avail, s);
            if (fold.isOpen()) {
                h = full.layout(avail, s);
            }
            return 2 + Math.max(14, h) + 2;
        }

        /** A hook inside a group sits under the group's icon and draws none of its own. */
        private int textX() {
            return member ? 0 : TEXT_X;
        }

        private int textWidth(int w) {
            return Math.max(40, w - textX() - (member ? 0 : ARROW));
        }

        private int arrowX() {
            return textX() + Math.round(Math.min(lineWidth, textWidth(width()))) + 4;
        }

        @Override
        void paint(Graphics2D g, int x, int y, Rectangle clip, Marks marks) {
            String icon = switch (note().kind()) {
                case ERROR -> "error";
                case DENIED, RETRY -> "warning";
                case INTERRUPT -> "interrupt";
                case COMMAND -> "palette";
                case HOOK -> "settings";
                default -> "info";
            };
            if (!member) {
                Icons.get(icon, 12).paintIcon(marks.component(), g, x, y + 3);
            }
            if (fold.isOpen()) {
                Paras.paintBox(g, full, x + textX(), y + 2, clip, marks);
            } else {
                Shape old = g.getClip();
                g.clipRect(x + textX(), y, textWidth(width()), height);
                Paras.paintBox(g, line, x + textX(), y + 2, clip, marks);
                g.setClip(old);
            }
            if (expandable) {
                TextLayout arrow = fold.isOpen() ? arrowOpen : arrowClosed;
                g.setColor(style().muted);
                arrow.draw(g, x + arrowX(), y + 2 + arrow.getAscent());
            }
        }

        @Override
        void collect(int x, int y, List<Placed> out) {
            out.add(new Placed(fold.isOpen() ? full : line, x + textX(), y + 2, this));
        }

        /** Closed, the whole line opens it; open, the icon and the arrow close it and the text stays selectable. */
        @Override
        Item toggleAt(int px, int py) {
            if (!expandable || py >= 2 + line.height() + 2) {
                return null;
            }
            return !fold.isOpen() || px < textX() || px >= arrowX() ? this : null;
        }

        @Override
        boolean toggle() {
            if (!expandable) {
                return false;
            }
            fold.toggle();
            invalidate();
            return true;
        }

        @Override
        String text() {
            return shown();
        }

        @Override
        String markdown() {
            String t = shown();
            return t.indexOf('\n') < 0 ? "_" + t + "_" : fence("", t);
        }
    }

    /**
     * Hook notes that arrived one after another, as one closed line naming their events; open, each hook's text.
     * Hooks run on every prompt, so their output stays out of the way until asked for.
     */
    static final class HookGroupItem extends Item {
        private static final int TEXT_X = 18;
        private final List<NoteItem> hooks = new ArrayList<>();
        private final Fold fold = new Fold(false);
        private TextBox header;
        private int[] tops = new int[0];

        HookGroupItem(NoteItem first) {
            super(first.block);
            hooks.add(first);
        }

        void add(NoteItem hook) {
            hooks.add(hook);
            invalidate();
        }

        int size() {
            return hooks.size();
        }

        boolean isOpen() {
            return fold.isOpen();
        }

        @Override
        Fold fold() {
            return fold;
        }

        @Override
        List<Item> children() {
            return Collections.unmodifiableList(hooks);
        }

        /** {@code hook · SessionStart}, or {@code hooks · UserPromptSubmit, SessionStart (3)}: the events in order of arrival, and the count. */
        String title() {
            Set<String> events = new LinkedHashSet<>();
            for (NoteItem h : hooks) {
                events.add(h.hookEvent().isEmpty() ? "hook" : h.hookEvent());
            }
            String names = String.join(", ", events);
            return hooks.size() == 1 ? "hook · " + names : "hooks · " + names + " (" + hooks.size() + ")";
        }

        @Override
        int doLayout(int w, Style s) {
            header = new TextBox(new Rich(s.small, s.muted).append(title() + (fold.isOpen() ? "  ▾" : "  ▸"))).noWrap();
            int inner = Math.max(40, w - TEXT_X);
            int h = 2 + Math.max(14, header.layout(inner, s)) + 2;
            if (fold.isOpen()) {
                tops = new int[hooks.size()];
                for (int i = 0; i < hooks.size(); i++) {
                    tops[i] = h;
                    h += hooks.get(i).layout(inner, s);
                }
            }
            return h;
        }

        @Override
        void paint(Graphics2D g, int x, int y, Rectangle clip, Marks marks) {
            Icons.get("settings", 12).paintIcon(marks.component(), g, x, y + 3);
            Paras.paintBox(g, header, x + TEXT_X, y + 2, clip, marks);
            if (!fold.isOpen()) {
                return;
            }
            for (int i = 0; i < hooks.size() && i < tops.length; i++) {
                NoteItem hook = hooks.get(i);
                int top = y + tops[i];
                if (top + hook.height >= clip.y && top <= clip.y + clip.height) {
                    hook.paint(g, x + TEXT_X, top, clip, marks);
                }
            }
        }

        @Override
        void collect(int x, int y, List<Placed> out) {
            if (!fold.isOpen()) {
                return;
            }
            for (int i = 0; i < hooks.size() && i < tops.length; i++) {
                hooks.get(i).collect(x + TEXT_X, y + tops[i], out);
            }
        }

        @Override
        Item toggleAt(int px, int py) {
            return py < 4 + header.height() ? this : null;
        }

        @Override
        boolean toggle() {
            fold.toggle();
            invalidate();
            return true;
        }

        @Override
        String text() {
            return title();
        }

        @Override
        String markdown() {
            StringBuilder sb = new StringBuilder();
            for (NoteItem h : hooks) {
                sb.append(sb.isEmpty() ? "" : "\n\n").append(h.markdown());
            }
            return sb.toString();
        }
    }

    static final class TurnItem extends Item {
        private TextBox label;
        private float labelWidth;

        TurnItem(Block.TurnEnd b) {
            super(b);
        }

        private Block.TurnEnd turn() {
            return (Block.TurnEnd) block;
        }

        /** The user stopped the turn: not a failure, so it reads in the muted colour. */
        boolean interrupted() {
            return turn().error() && turn().text().equals("interrupted");
        }

        /** {@code 12.4 s · $0.120 total}, after the error text when the turn failed or was interrupted. */
        String summary() {
            Block.TurnEnd t = turn();
            StringBuilder sb = new StringBuilder();
            if (t.error()) {
                sb.append(t.text()).append(" · ");
            }
            Formats.appendMillis(sb, t.durationMillis());
            sb.append(" · ").append(Labels.usd(t.costUsd())).append(" total");
            return sb.toString();
        }

        Color color(Style s) {
            return turn().error() && !interrupted() ? Theme.DANGER : s.muted;
        }

        @Override
        int doLayout(int w, Style s) {
            label = new TextBox(new Rich(s.small, color(s)).append(summary())).noWrap();
            labelWidth = label.naturalWidth();
            label.layout(Math.max(1, (int) Math.ceil(labelWidth)), s);
            return 6 + label.height() + 8;
        }

        private int labelX(int x) {
            return x + width() - Math.round(labelWidth);
        }

        @Override
        void paint(Graphics2D g, int x, int y, Rectangle clip, Marks marks) {
            Paras.paintBox(g, label, labelX(x), y + 6, clip, marks);
            g.setColor(Theme.CARD_BORDER);
            g.fillRect(x, y + height - 1, width(), 1);
        }

        @Override
        void collect(int x, int y, List<Placed> out) {
            out.add(new Placed(label, labelX(x), y + 6, this));
        }

        @Override
        String text() {
            return summary();
        }

        @Override
        String markdown() {
            return "_" + summary() + "_";
        }
    }
}
