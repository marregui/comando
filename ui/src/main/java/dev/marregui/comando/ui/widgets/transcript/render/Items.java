package dev.marregui.comando.ui.widgets.transcript.render;

import dev.marregui.comando.core.result.Formats;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.ui.kit.format.Json;
import dev.marregui.comando.ui.kit.format.Labels;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.widgets.transcript.doc.MarkdownDoc;
import dev.marregui.comando.ui.widgets.transcript.doc.Para;
import tools.jackson.databind.JsonNode;

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

    /** A tool card's status while its call waits for the user; read through {@code KeyText}. */
    static final String WAITING = "waiting for your approval · ⌃2";
    static final int MAX_RESULT_LINES = 400;
    static final int MAX_INPUT_LINES = 300;

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
                text = new TextBox(new Rich(s.mono, s.text).append(prompt().text()).linked(s.link));
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
        int guess(int w, Style s) {
            return TOP + BOTTOM + 2 * IN_Y + guessText(text(), w - 2 * IN_X, s);
        }

        @Override
        String markdown() {
            return "> " + prompt().text().replace("\n", "\n> ");
        }
    }

    // ---- assistant text

    static final class TextItem extends Item {
        /** A report's text starts this far right of its frame's edge, past the bar. */
        private static final int GUTTER = 14;
        /** A report's frame reaches this far above, below and right of its text. */
        private static final int FRAME = 10;
        private List<Paras.View> views = List.of();
        private int[] tops = new int[0];
        /**
         * True when this text ends its turn: the report, on the transcript's ground with a bar of the tint down its left
         * edge, so it is found again among the turn's cards.
         */
        private boolean report;

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

        /** Frames this text as its turn's report, or not; the next layout makes room for the frame. */
        void report(boolean on) {
            if (report != on) {
                report = on;
                invalidate();
            }
        }

        boolean isReport() {
            return report;
        }

        private int inset() {
            return report ? GUTTER : 0;
        }

        @Override
        int doLayout(int w, Style style) {
            Style s = style.outside();
            tops = new int[views.size()];
            int inner = report ? w - GUTTER - FRAME : w;
            int y = report ? FRAME : 4;
            for (int i = 0; i < views.size(); i++) {
                if (i > 0) {
                    boolean listRun = views.get(i).para() instanceof Para.Text t
                            && t.kind() == Para.Kind.ITEM
                            && views.get(i - 1).para() instanceof Para.Text p
                            && p.kind() == Para.Kind.ITEM;
                    y += listRun ? s.paraGap / 3 : s.paraGap;
                }
                tops[i] = y;
                y += views.get(i).layout(inner, s);
            }
            return y + (report ? FRAME : 4);
        }

        @Override
        void paint(Graphics2D g, int x, int y, Rectangle clip, Marks marks) {
            if (report) {
                // on the transcript's own ground, framed by the tint's bar alone (record 96)
                g.setPaint(style().tint.ramp(y, y + height, false));
                g.fillRect(x, y, 3, height);
            }
            x += inset();
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
            x += inset();
            for (int i = 0; i < views.size(); i++) {
                views.get(i).collect(x, y + tops[i], this, out);
            }
        }

        @Override
        String text() {
            return ((Block.AssistantText) block).text();
        }

        @Override
        int guess(int w, Style s) {
            return guessText(text(), w, s);
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
                header = new TextBox(new Rich(s.body, s.outside).append((t.streaming() ? "Thinking…" : "Thought") + tokens + arrow));
                List<String> lines = List.of(t.text().split("\n", -1));
                List<TextBox> boxes = new ArrayList<>(lines.size());
                for (int i = 0; i < lines.size(); i++) {
                    boolean same = built == s && i < bodyLines.size() && bodyLines.get(i).equals(lines.get(i));
                    boxes.add(same ? body.get(i) : new TextBox(new Rich(s.italic, s.outside).append(lines.get(i)).linked(s.link)));
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
            // no mark before it: the line itself says Thought, in the theme's colours (record 119); kept in the tools' column
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

    static final class ToolItem extends ToolCallItem {
        ToolItem(Block.ToolCall b) {
            super(b);
        }

        ToolItem(Block.ToolCall b, long order, long[] times) {
            super(b, order, times);
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
            JsonNode root = Json.parse(t);
            JsonNode specific = root.path("hookSpecificOutput");
            if (root.size() != 1 || !specific.isObject() || !specific.path("additionalContext").isString()) {
                return null;
            }
            for (var field : specific.properties()) {
                if (!field.getKey().equals("additionalContext") && !field.getKey().equals("hookEventName")) {
                    return null;
                }
            }
            return specific.path("additionalContext").asString("");
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
                Color color = n.kind() == Block.NoteKind.ERROR ? Theme.DANGER : s.outside;
                String t = shown();
                int eol = t.indexOf('\n');
                more = eol >= 0;
                line = new TextBox(new Rich(s.small, color).append(more ? t.substring(0, eol) : t).linked(s.link)).noWrap();
                full = new TextBox(new Rich(s.small, color).append(clipLines(t, MAX_RESULT_LINES)).linked(s.link));
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
                g.setColor(style().outside);
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
            header = new TextBox(new Rich(s.small, s.outside).append(title() + (fold.isOpen() ? "  ▾" : "  ▸"))).noWrap();
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

        /** The user stopped the turn: not a failure, so it reads as any text outside a card. */
        boolean interrupted() {
            return turn().error() && turn().text().equals("interrupted");
        }

        /**
         * {@code 12.4 s}, after the error text when the turn failed or was interrupted; nothing for a turn of a stored
         * conversation, which records no time. No spend: a subscription's limits are the plan usage the status bar shows,
         * not money per turn.
         */
        String summary() {
            Block.TurnEnd t = turn();
            if (!t.timed()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            if (t.error()) {
                sb.append(t.text()).append(" · ");
            }
            Formats.appendMillis(sb, t.durationMillis());
            return sb.toString();
        }

        Color color(Style s) {
            return turn().error() && !interrupted() ? Theme.DANGER : s.outside;
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
            String summary = summary();
            return summary.isEmpty() ? "" : "_" + summary + "_";
        }
    }
}
