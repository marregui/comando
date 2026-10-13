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
import java.util.List;

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
        int doLayout(int w, Style s) {
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
        private TextBox header;
        private TextBox body;
        private Style built;
        private boolean open;

        ThinkingItem(Block.Thinking b) {
            super(b);
        }

        private Block.Thinking thinking() {
            return (Block.Thinking) block;
        }

        @Override
        void changed(Block previous, Block next) {
            built = null;
        }

        @Override
        int doLayout(int w, Style s) {
            if (built != s) {
                built = s;
                Block.Thinking t = thinking();
                String tokens = t.tokens() > 0 ? " · " + Labels.tokens(t.tokens()) + " tokens" : "";
                String arrow = t.text().isEmpty() ? "" : open ? "  ▾" : "  ▸";
                header = new TextBox(new Rich(s.body, Theme.THINKING).append((t.streaming() ? "Thinking…" : "Thought") + tokens + arrow));
                body = new TextBox(new Rich(s.italic, s.muted).append(t.text()));
            }
            int h = 2 + header.layout(Math.max(40, w - 22), s) + 2;
            if (open) {
                h += body.layout(Math.max(40, w - 22), s) + 4;
            }
            return h;
        }

        @Override
        void paint(Graphics2D g, int x, int y, Rectangle clip, Marks marks) {
            Icons.get("thinking").paintIcon(marks.component(), g, x, y + 3);
            Paras.paintBox(g, header, x + 22, y + 2, clip, marks);
            if (open) {
                Paras.paintBox(g, body, x + 22, y + 4 + header.height(), clip, marks);
            }
        }

        @Override
        void collect(int x, int y, List<Placed> out) {
            if (open) {
                out.add(new Placed(body, x + 22, y + 4 + header.height(), this));
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
            open = !open;
            built = null;
            invalidate();
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
        private static final int IN_X = 8;
        private static final int IN_Y = 5;
        private static final int CHILD_INDENT = 12;
        private final List<Item> children = new ArrayList<>();
        private Style built;
        private TextBox title;
        private TextBox input;
        private TextBox result;
        private TextLayout status;
        private Icon statusIcon;
        private boolean open;
        private boolean userToggled;
        private int headerHeight;
        private int inputTop;
        private int resultTop;
        private int[] childTops = new int[0];

        ToolItem(Block.ToolCall b) {
            super(b);
            autoOpen();
        }

        Block.ToolCall call() {
            return (Block.ToolCall) block;
        }

        void addChild(Item child) {
            children.add(child);
            invalidate();
        }

        @Override
        List<Item> children() {
            return children;
        }

        boolean isOpen() {
            return open;
        }

        /** Opens edits, errors and running subagents unless the user chose otherwise. */
        private void autoOpen() {
            if (!userToggled) {
                Block.ToolCall c = call();
                boolean edits = c.name().equals("Edit") || c.name().equals("MultiEdit") || c.name().equals("Write");
                open = edits || c.status() == Block.ToolStatus.ERROR || (c.isSubagent() && c.status() == Block.ToolStatus.RUNNING);
            }
        }

        @Override
        void changed(Block previous, Block next) {
            built = null;
            autoOpen();
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
                status = c.status() == Block.ToolStatus.RUNNING ? new TextLayout("running", s.small, Style.FRC) : null;
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
            if (open) {
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

        private static Rich inputRich(Block.ToolCall c, JsonNode in, Style s) {
            return switch (c.name()) {
                case "Bash" -> new Rich(s.monoSmall, s.accent).append("$ " + in.path("command").asText(""));
                case "Edit" -> diff(in.path("file_path").asText(""), in.path("old_string").asText(""), in.path("new_string").asText(""), s);
                case "MultiEdit" -> {
                    StringBuilder before = new StringBuilder();
                    StringBuilder after = new StringBuilder();
                    for (JsonNode e : in.path("edits")) {
                        before.append(e.path("old_string").asText("")).append("\n…\n");
                        after.append(e.path("new_string").asText("")).append("\n…\n");
                    }
                    yield diff(in.path("file_path").asText(""), before.toString(), after.toString(), s);
                }
                case "Write" -> diff(in.path("file_path").asText(""), "", clipLines(in.path("content").asText(""), MAX_INPUT_LINES), s);
                case "Agent", "Task" -> new Rich(s.monoSmall, s.text).append(in.path("prompt").asText(""));
                default -> in.isObject() && !in.isEmpty()
                        ? new Rich(s.monoSmall, s.text).append(clipLines(ToolText.pretty(in), MAX_INPUT_LINES))
                        : new Rich(s.monoSmall, s.text);
            };
        }

        private static Rich diff(String path, String before, String after, Style s) {
            List<String> lines = Diffs.unified(path, before, after).stream().map(Rich::detab).toList();
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

        @Override
        void paint(Graphics2D g, int x, int y, Rectangle clip, Marks marks) {
            Style s = style();
            int cy = y + 3;
            int ch = height - 6;
            fillRound(g, Theme.CARD, x, cy, width(), ch);
            Icon icon = Icons.get(call().isSubagent() ? "session" : "tool");
            icon.paintIcon(marks.component(), g, x + IN_X, cy + IN_Y + 1);
            Shape old = g.getClip();
            int titleRight = x + width() - IN_X - 70;
            g.clipRect(x + IN_X + 22, cy, Math.max(0, titleRight - x - IN_X - 22), headerHeight + IN_Y);
            Paras.paintBox(g, title, x + IN_X + 22, cy + IN_Y, clip, marks);
            g.setClip(old);
            int right = x + width() - IN_X;
            if (!children.isEmpty() && !open) {
                TextLayout steps = new TextLayout(children.size() + " steps", s.small, Style.FRC);
                g.setColor(s.muted);
                steps.draw(g, right - 22 - steps.getAdvance(), cy + IN_Y + steps.getAscent() + 1);
            }
            if (status != null) {
                g.setColor(Theme.WARN);
                status.draw(g, right - status.getAdvance(), cy + IN_Y + status.getAscent() + 1);
            } else if (statusIcon != null) {
                statusIcon.paintIcon(marks.component(), g, right - 16, cy + IN_Y + 1);
            }
            if (!open) {
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
            if (!open) {
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
            if (open) {
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
            userToggled = true;
            open = !open;
            invalidate();
            return true;
        }

        /** Opens the card, for a find match inside it. */
        void open() {
            if (!open) {
                userToggled = true;
                open = true;
                invalidate();
            }
        }

        @Override
        String text() {
            // decoded input values, so find matches text with quotes, backslashes or newlines as shown
            Block.ToolCall c = call();
            return c.name() + '\n' + ToolText.values(ToolText.parse(c.inputJson())) + c.resultText();
        }

        @Override
        String markdown() {
            Block.ToolCall c = call();
            return "`" + c.name() + "` " + c.inputJson();
        }
    }

    // ---- notes and turn ends

    static final class NoteItem extends Item {
        private TextBox text;
        private Style built;

        NoteItem(Block.Note b) {
            super(b);
        }

        private Block.Note note() {
            return (Block.Note) block;
        }

        @Override
        void changed(Block previous, Block next) {
            built = null;
        }

        @Override
        int doLayout(int w, Style s) {
            if (built != s) {
                built = s;
                Block.Note n = note();
                String t = n.text().length() > MAX_NOTE_CHARS ? n.text().substring(0, MAX_NOTE_CHARS) + "…" : n.text();
                text = new TextBox(new Rich(s.small, n.kind() == Block.NoteKind.ERROR ? Theme.DANGER : s.muted).append(t));
            }
            return 2 + Math.max(14, text.layout(Math.max(40, w - 18), s)) + 2;
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
            Icons.get(icon, 12).paintIcon(marks.component(), g, x, y + 3);
            Paras.paintBox(g, text, x + 18, y + 2, clip, marks);
        }

        @Override
        void collect(int x, int y, List<Placed> out) {
            out.add(new Placed(text, x + 18, y + 2, this));
        }

        @Override
        String text() {
            return note().text();
        }

        @Override
        String markdown() {
            return "_" + note().text() + "_";
        }
    }

    static final class TurnItem extends Item {
        private TextLayout label;

        TurnItem(Block.TurnEnd b) {
            super(b);
        }

        @Override
        int doLayout(int w, Style s) {
            Block.TurnEnd t = (Block.TurnEnd) block;
            StringBuilder sb = new StringBuilder();
            if (t.error()) {
                sb.append(t.text()).append(" · ");
            }
            Formats.appendMillis(sb, t.durationMillis());
            sb.append(" · ").append(Labels.usd(t.costUsd())).append(" total");
            label = new TextLayout(sb.toString(), s.small, Style.FRC);
            return 6 + Math.round(label.getAscent() + label.getDescent()) + 8;
        }

        @Override
        void paint(Graphics2D g, int x, int y, Rectangle clip, Marks marks) {
            Block.TurnEnd t = (Block.TurnEnd) block;
            g.setColor(t.error() ? Theme.DANGER : style().muted);
            label.draw(g, x + width() - label.getAdvance(), y + 6 + label.getAscent());
            g.setColor(Theme.CARD_BORDER);
            g.fillRect(x, y + height - 1, width(), 1);
        }

        @Override
        void collect(int x, int y, List<Placed> out) {
            // the turn summary is not selectable
        }

        @Override
        String text() {
            return "";
        }

        @Override
        String markdown() {
            return "";
        }
    }
}
