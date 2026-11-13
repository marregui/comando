package dev.marregui.comando.ui.widgets.transcript.render;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.font.TextLayout;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import tools.jackson.databind.JsonNode;

import dev.marregui.comando.core.result.Formats;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.ui.kit.format.Json;
import dev.marregui.comando.ui.kit.format.Labels;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.widgets.transcript.doc.MarkdownDoc;
import dev.marregui.comando.ui.widgets.transcript.doc.Para;

/** The item for each block kind. */
final class Items {

    /** A tool card's status while its call waits for the user; read through {@code KeyText}. */
    static final String WAITING = "waiting for your approval · ⌃2";
    static final int MAX_RESULT_LINES = 400;
    static final int MAX_INPUT_LINES = 300;

    private Items() {
    }

    static Item of(final Block b) {
        return switch (b) {
            case Block.UserPrompt p -> new PromptItem(p);
            case Block.AssistantText t -> new TextItem(t);
            case Block.Thinking t -> new ThinkingItem(t);
            case Block.ToolCall c -> new ToolItem(c);
            case Block.Note n -> new NoteItem(n);
            case Block.TurnEnd t -> new TurnItem(t);
        };
    }

    static String clipLines(final String s, final int max) {
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

    /**
     * When something in the transcript happened, by this machine's clock and zone: "13:25" today, "Nov 12 13:25" on an
     * earlier day ({@link Labels#startedAt}); empty when it is not known ({@link Block#UNKNOWN_TIME}).
     */
    static String clock(final long atMillis) {
        return atMillis == Block.UNKNOWN_TIME ? ""
                : Labels.startedAt(atMillis, System.currentTimeMillis(), ZoneId.systemDefault());
    }

    /** {@code body} in a fenced code block whose fence is longer than any backtick run inside it. */
    static String fence(final String language, final String body) {
        int longest = 0;
        int run = 0;
        for (int i = 0; i < body.length(); i++) {
            run = body.charAt(i) == '`' ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        final String f = "`".repeat(Math.max(3, longest + 1));
        return f + language + "\n" + body + (body.endsWith("\n") ? "" : "\n") + f;
    }

    static void fillRound(final Graphics2D g, final Color c, final int x, final int y, final int w, final int h) {
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

        PromptItem(final Block.UserPrompt b) {
            super(b);
        }

        private Block.UserPrompt prompt() {
            return (Block.UserPrompt) block;
        }

        /** The line above the prompt: who sent it, from which panel, and when ({@link #clock}), when that is known. */
        String label() {
            final String o = prompt().origin();
            final String who = o.isEmpty() ? "you" : o.equals("subagent") ? "task for the subagent" : "you · " + o;
            final String at = clock(prompt().atMillis());
            return at.isEmpty() ? who : who + " · " + at;
        }

        @Override
        void changed(final Block previous, final Block next) {
            built = null;
        }

        @Override
        void dropLayout() {
            built = null;
            origin = null;
            text = null;
        }

        @Override
        int doLayout(final int w, final Style s) {
            if (built != s) {
                built = s;
                origin = new TextBox(new Rich(s.small, s.muted).append(label()));
                text = new TextBox(new Rich(s.mono, s.text).append(prompt().text()).linked(s.link));
            }
            final int inner = Math.max(40, w - 2 * IN_X);
            return TOP + IN_Y + origin.layout(inner, s) + 2 + text.layout(inner, s) + IN_Y + BOTTOM;
        }

        @Override
        void paint(final Graphics2D g, final int x, final int y, final Rectangle clip, final Marks marks) {
            fillRound(g, Theme.USER_BUBBLE, x, y + TOP, width(), height - TOP - BOTTOM);
            Paras.paintBox(g, origin, x + IN_X, y + TOP + IN_Y, clip, marks);
            Paras.paintBox(g, text, x + IN_X, y + TOP + IN_Y + origin.height() + 2, clip, marks);
        }

        @Override
        void collect(final int x, final int y, final List<Placed> out) {
            out.add(new Placed(text, x + IN_X, y + TOP + IN_Y + origin.height() + 2, this));
        }

        @Override
        void findTexts(final List<String> out) {
            out.add(Rich.detab(prompt().text()));
        }

        @Override
        String text() {
            return prompt().text();
        }

        @Override
        int guess(final int w, final Style s) {
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
        /** Null until the first layout: a block far from the view is never parsed. */
        private List<Paras.View> views;
        private int[] tops = new int[0];
        /**
         * True when this text ends its turn: the report, on the transcript's ground with a bar of the tint down its
         * left edge, so it is found again among the turn's cards.
         */
        private boolean report;

        TextItem(final Block.AssistantText b) {
            super(b);
        }

        /** Streamed text reuses the paragraphs it had; a text never parsed waits for its first layout. */
        @Override
        void changed(final Block previous, final Block next) {
            final String before = ((Block.AssistantText) previous).text();
            final String after = ((Block.AssistantText) next).text();
            if (views != null && !before.equals(after)) {
                views = Paras.reuse(MarkdownDoc.parse(after), views);
            }
        }

        /** Whether the text has been parsed into paragraphs: at its first layout, until it lets its layout go; for tests. */
        boolean parsed() {
            return views != null;
        }

        private List<Paras.View> views() {
            if (views == null) {
                views = Paras.reuse(MarkdownDoc.parse(text()), List.of());
            }
            return views;
        }

        /** Frames this text as its turn's report, or not; the next layout makes room for the frame. */
        void report(final boolean on) {
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

        /** The paragraphs go too: parsed again from the block at the next layout. */
        @Override
        void dropLayout() {
            views = null;
            tops = new int[0];
        }

        @Override
        int doLayout(final int w, final Style style) {
            final Style s = style.outside();
            final List<Paras.View> paras = views();
            tops = new int[paras.size()];
            final int inner = report ? w - GUTTER - FRAME : w;
            int y = report ? FRAME : 4;
            for (int i = 0; i < paras.size(); i++) {
                if (i > 0) {
                    final boolean listRun = paras.get(i).para() instanceof Para.Text t
                            && t.kind() == Para.Kind.ITEM
                            && paras.get(i - 1).para() instanceof Para.Text p
                            && p.kind() == Para.Kind.ITEM;
                    y += listRun ? s.paraGap / 3 : s.paraGap;
                }
                tops[i] = y;
                y += paras.get(i).layout(inner, s);
            }
            return y + (report ? FRAME : 4);
        }

        @Override
        void paint(final Graphics2D g, int x, final int y, final Rectangle clip, final Marks marks) {
            if (report) {
                // on the transcript's own ground, framed by the tint's bar alone (record 96)
                g.setPaint(style().tint.ramp(y, y + height, false));
                g.fillRect(x, y, 3, height);
            }
            x += inset();
            final List<Paras.View> paras = views();
            for (int i = 0; i < paras.size(); i++) {
                final int top = y + tops[i];
                final int bottom = i + 1 < paras.size() ? y + tops[i + 1] : y + height;
                if (bottom < clip.y || top > clip.y + clip.height) {
                    continue;
                }
                paras.get(i).paint(g, x, top, clip, marks);
            }
        }

        @Override
        void collect(int x, final int y, final List<Placed> out) {
            x += inset();
            final List<Paras.View> paras = views();
            for (int i = 0; i < paras.size(); i++) {
                paras.get(i).collect(x, y + tops[i], this, out);
            }
        }

        /** The paragraphs the views hold when laid out; else parsed for this find and not kept. */
        @Override
        void findTexts(final List<String> out) {
            if (views != null) {
                for (final Paras.View v : views) {
                    Paras.findTexts(v.para(), out);
                }
            } else {
                for (final Para p : MarkdownDoc.parse(text(), false)) {
                    Paras.findTexts(p, out);
                }
            }
        }

        @Override
        String text() {
            return ((Block.AssistantText) block).text();
        }

        @Override
        int guess(final int w, final Style s) {
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

        ThinkingItem(final Block.Thinking b) {
            super(b);
            fold = new Fold(live(b));
        }

        /** Open while it streams, so the reasoning can be followed; closed once the final block arrives. */
        private static boolean live(final Block.Thinking t) {
            return t.streaming() && !t.text().isEmpty();
        }

        private Block.Thinking thinking() {
            return (Block.Thinking) block;
        }

        @Override
        void changed(final Block previous, final Block next) {
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
        void dropLayout() {
            built = null;
            header = null;
            body = List.of();
            bodyLines = List.of();
        }

        @Override
        int doLayout(final int w, final Style s) {
            if (built != s || stale) {
                final Block.Thinking t = thinking();
                final String tokens = t.tokens() > 0 ? " · " + Labels.tokens(t.tokens()) + " tokens" : "";
                final String arrow = t.text().isEmpty() ? "" : fold.isOpen() ? "  ▾" : "  ▸";
                header = new TextBox(new Rich(s.body, s.outside).append((t.streaming() ? "Thinking…" : "Thought") + tokens + arrow));
                final List<String> lines = List.of(t.text().split("\n", -1));
                final List<TextBox> boxes = new ArrayList<>(lines.size());
                for (int i = 0; i < lines.size(); i++) {
                    final boolean same = built == s && i < bodyLines.size() && bodyLines.get(i).equals(lines.get(i));
                    boxes.add(same ? body.get(i) : new TextBox(new Rich(s.italic, s.outside).append(lines.get(i)).linked(s.link)));
                }
                body = boxes;
                bodyLines = lines;
                built = s;
                stale = false;
            }
            final int inner = Math.max(40, w - 22);
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
        void paint(final Graphics2D g, final int x, final int y, final Rectangle clip, final Marks marks) {
            // no mark before it: the line itself says Thought, in the theme's colours (record 119); kept in the tools'
            // column
            Paras.paintBox(g, header, x + 22, y + 2, clip, marks);
            if (!shown()) {
                return;
            }
            for (int i = 0; i < body.size() && i < bodyTops.length; i++) {
                final TextBox box = body.get(i);
                final int top = y + bodyTops[i];
                if (top + box.height() >= clip.y && top <= clip.y + clip.height) {
                    Paras.paintBox(g, box, x + 22, top, clip, marks);
                }
            }
        }

        @Override
        void collect(final int x, final int y, final List<Placed> out) {
            if (!shown()) {
                return;
            }
            for (int i = 0; i < body.size() && i < bodyTops.length; i++) {
                out.add(new Placed(body.get(i), x + 22, y + bodyTops[i], this));
            }
        }

        @Override
        void findTexts(final List<String> out) {
            if (shown()) {
                for (final String line : thinking().text().split("\n", -1)) {
                    out.add(Rich.detab(line));
                }
            }
        }

        @Override
        Item toggleAt(final int px, final int py) {
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
        ToolItem(final Block.ToolCall b) {
            super(b);
        }

        ToolItem(final Block.ToolCall b, final long order, final long[] times) {
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

        NoteItem(final Block.Note b) {
            this(b, false);
        }

        NoteItem(final Block.Note b, final boolean member) {
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
            final String t = note().text();
            final int sep = t.indexOf(": ");
            return note().kind() != Block.NoteKind.HOOK || sep < 0 ? "" : t.substring(0, sep);
        }

        /** The hook event: the hook's name without its matcher. */
        String hookEvent() {
            final String name = hookName();
            final int colon = name.indexOf(':');
            return colon < 0 ? name : name.substring(0, colon);
        }

        /** The text as shown, found and copied: a hook's JSON reply reads as the context it adds. */
        String shown() {
            final Block.Note n = note();
            if (n.kind() != Block.NoteKind.HOOK) {
                return n.text();
            }
            final String name = hookName();
            final String out = name.isEmpty() ? n.text() : n.text().substring(name.length() + 2);
            final String context = additionalContext(out);
            return context == null ? n.text() : name + ": " + context;
        }

        /**
         * The {@code hookSpecificOutput.additionalContext} of a hook's JSON reply when that is all the reply
         * says; null otherwise, so a reply with anything more is shown whole.
         */
        static String additionalContext(final String out) {
            final String t = out.strip();
            if (!t.startsWith("{")) {
                return null;
            }
            final JsonNode root = Json.parse(t);
            final JsonNode specific = root.path("hookSpecificOutput");
            if (root.size() != 1 || !specific.isObject() || !specific.path("additionalContext").isString()) {
                return null;
            }
            for (final var field : specific.properties()) {
                if (!field.getKey().equals("additionalContext") && !field.getKey().equals("hookEventName")) {
                    return null;
                }
            }
            return specific.path("additionalContext").asString("");
        }

        @Override
        void changed(final Block previous, final Block next) {
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
        void dropLayout() {
            built = null;
            line = null;
            full = null;
        }

        @Override
        int doLayout(final int w, final Style s) {
            if (built != s) {
                built = s;
                final Block.Note n = note();
                final Color color = n.kind() == Block.NoteKind.ERROR ? Theme.DANGER : s.outside;
                final String t = shown();
                final int eol = t.indexOf('\n');
                more = eol >= 0;
                line = new TextBox(new Rich(s.small, color).append(more ? t.substring(0, eol) : t).linked(s.link)).noWrap();
                full = new TextBox(new Rich(s.small, color).append(clipLines(t, MAX_RESULT_LINES)).linked(s.link));
                lineWidth = line.naturalWidth();
                arrowOpen = new TextLayout("▾", s.small, Style.FRC);
                arrowClosed = new TextLayout("▸", s.small, Style.FRC);
            }
            final int avail = textWidth(w);
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

        private int textWidth(final int w) {
            return Math.max(40, w - textX() - (member ? 0 : ARROW));
        }

        private int arrowX() {
            return textX() + Math.round(Math.min(lineWidth, textWidth(width()))) + 4;
        }

        @Override
        void paint(final Graphics2D g, final int x, final int y, final Rectangle clip, final Marks marks) {
            final String icon = switch (note().kind()) {
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
                final Shape old = g.getClip();
                g.clipRect(x + textX(), y, textWidth(width()), height);
                Paras.paintBox(g, line, x + textX(), y + 2, clip, marks);
                g.setClip(old);
            }
            if (expandable) {
                final TextLayout arrow = fold.isOpen() ? arrowOpen : arrowClosed;
                g.setColor(style().outside);
                arrow.draw(g, x + arrowX(), y + 2 + arrow.getAscent());
            }
        }

        @Override
        void collect(final int x, final int y, final List<Placed> out) {
            out.add(new Placed(fold.isOpen() ? full : line, x + textX(), y + 2, this));
        }

        @Override
        void findTexts(final List<String> out) {
            final String t = shown();
            final int eol = t.indexOf('\n');
            out.add(Rich.detab(fold.isOpen() ? clipLines(t, MAX_RESULT_LINES) : eol >= 0 ? t.substring(0, eol) : t));
        }

        /** Closed, the whole line opens it; open, the icon and the arrow close it and the text stays selectable. */
        @Override
        Item toggleAt(final int px, final int py) {
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
            final String t = shown();
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

        HookGroupItem(final NoteItem first) {
            super(first.block);
            hooks.add(first);
        }

        void add(final NoteItem hook) {
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

        /**
         * {@code hook · SessionStart}, or {@code hooks · UserPromptSubmit, SessionStart (3)}: the events in order of
         * arrival, and the count.
         */
        String title() {
            final Set<String> events = new LinkedHashSet<>();
            for (final NoteItem h : hooks) {
                events.add(h.hookEvent().isEmpty() ? "hook" : h.hookEvent());
            }
            final String names = String.join(", ", events);
            return hooks.size() == 1 ? "hook · " + names : "hooks · " + names + " (" + hooks.size() + ")";
        }

        @Override
        void dropLayout() {
            header = null;
        }

        @Override
        int doLayout(final int w, final Style s) {
            header = new TextBox(new Rich(s.small, s.outside).append(title() + (fold.isOpen() ? "  ▾" : "  ▸"))).noWrap();
            final int inner = Math.max(40, w - TEXT_X);
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
        void paint(final Graphics2D g, final int x, final int y, final Rectangle clip, final Marks marks) {
            Icons.get("settings", 12).paintIcon(marks.component(), g, x, y + 3);
            Paras.paintBox(g, header, x + TEXT_X, y + 2, clip, marks);
            if (!fold.isOpen()) {
                return;
            }
            for (int i = 0; i < hooks.size() && i < tops.length; i++) {
                final NoteItem hook = hooks.get(i);
                final int top = y + tops[i];
                if (top + hook.height >= clip.y && top <= clip.y + clip.height) {
                    hook.paint(g, x + TEXT_X, top, clip, marks);
                }
            }
        }

        @Override
        void collect(final int x, final int y, final List<Placed> out) {
            if (!fold.isOpen()) {
                return;
            }
            for (int i = 0; i < hooks.size() && i < tops.length; i++) {
                hooks.get(i).collect(x + TEXT_X, y + tops[i], out);
            }
        }

        @Override
        void findTexts(final List<String> out) {
            if (fold.isOpen()) {
                for (final NoteItem hook : hooks) {
                    hook.findTexts(out);
                }
            }
        }

        @Override
        Item toggleAt(final int px, final int py) {
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
            final StringBuilder sb = new StringBuilder();
            for (final NoteItem h : hooks) {
                sb.append(sb.isEmpty() ? "" : "\n\n").append(h.markdown());
            }
            return sb.toString();
        }
    }

    /**
     * What a turn did before its report, on one row: {@code 44 tools · 6 min}, and {@code · 1 failed} in the danger
     * colour when calls failed. The steps are the top-level items the turn showed, at the width and style they had, so
     * opening shows them as they were and a layout they hold stands. The row is no text of the conversation: find reads
     * the steps, and no word of the row opens every turn.
     */
    static final class StepsItem extends Item {
        private static final int TEXT_X = 18;
        private final List<Item> steps;
        private final long tookMillis;
        private final Fold fold = new Fold(false);
        private TextBox header;
        private int row;
        private int[] tops = new int[0];
        /** The layout stopped at {@link #laidTo}: every step above it is laid out, the rest hold estimates. */
        private boolean partial;
        private int laidTo;
        /** It stopped on {@link #deadlineNanos}, short of {@link #shownTo}. */
        private boolean outOfTime;
        /** The layout under way reaches {@link #shownTo} by {@link #deadlineNanos}, not every step. */
        private boolean bounded;
        private int shownTo;
        private long deadlineNanos;

        /**
         * The closed row of {@code steps}; {@code tookMillis} is {@link Block.TurnEnd#UNTIMED} when the turn's time is
         * unknown.
         */
        StepsItem(final List<Item> steps, final long tookMillis) {
            super(steps.getFirst().block);
            this.steps = steps;
            this.tookMillis = tookMillis;
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
            return steps;
        }

        /** True while a call of the turn still works: a sub-agent in the background, a call with no result yet. */
        boolean working() {
            for (int k = 0, n = steps.size(); k < n; k++) {
                final Item step = steps.get(k);
                if (step instanceof ToolItem card && card.isActive()) {
                    return true;
                }
            }
            return false;
        }

        /** The steps let their layout go: the row hides them, and they lay out again when it opens. */
        void dropSteps() {
            for (int k = 0, n = steps.size(); k < n; k++) {
                final Item step = steps.get(k);
                step.discard();
            }
            dropPositions();
        }

        private void dropPositions() {
            tops = new int[0];
            partial = false;
            outOfTime = false;
        }

        /**
         * Lays out at {@code w} the steps from the first down to {@code to} below this item's top, until {@code
         * deadlineNanos}: opening a row costs what the view shows, not the turn's length, and the steps below hold
         * estimates until the idle pass reaches them. Steps are laid from the first, so what lies above the view is
         * exact and a step in view stays where it is.
         */
        int layoutTo(final int w, final Style s, final int to, final long deadlineNanos) {
            bounded = true;
            shownTo = to;
            this.deadlineNanos = deadlineNanos;
            final int h = layout(w, s);
            bounded = false;
            return h;
        }

        /** True when the last layout ran out of time before it reached what it was asked to. */
        boolean outOfTime() {
            return outOfTime;
        }

        @Override
        boolean needsLayoutTo(final int w, final Style s, final int y) {
            return needsLayout(w, s) || partial && laidTo <= y;
        }

        @Override
        boolean wantsMore() {
            return partial && (!bounded || laidTo <= shownTo);
        }

        /** The height of the row above the steps, as laid out. */
        int rowHeight() {
            return row;
        }

        /** The top of {@code step} within this item, as laid out; 0 when the steps are not shown. */
        int topOf(final Item step) {
            for (int i = 0; i < tops.length; i++) {
                if (steps.get(i) == step) {
                    return tops[i];
                }
            }
            return 0;
        }

        /** The top within this item of the step at {@code k}, as laid out; -1 when the steps are not placed. */
        int topAt(final int k) {
            return k < tops.length ? tops[k] : -1;
        }

        /**
         * The step placed at {@code y} within this item, laid out or still estimated, the last that starts at or
         * above it; -1 when {@code y} is in the row above the steps, below them, or the steps are not placed. Unlike a
         * click's {@code stepAt}, an estimated step counts: the view keeps its place by it.
         */
        int stepPlacedAt(final int y) {
            if (tops.length == 0 || y < tops[0] || y >= height) {
                return -1;
            }
            int lo = 0;
            int hi = tops.length - 1;
            while (lo < hi) {
                final int mid = (lo + hi + 1) >>> 1;
                if (tops[mid] <= y) {
                    lo = mid;
                } else {
                    hi = mid - 1;
                }
            }
            return lo;
        }

        /** {@code 44 tools · 6 min}: the calls at any depth, and the turn's time when it is known. */
        String title() {
            final int n = calls(steps, false);
            final String tools = n == 0 ? "no tools" : n == 1 ? "1 tool" : n + " tools";
            return tookMillis == Block.TurnEnd.UNTIMED ? tools : tools + " · " + took(tookMillis);
        }

        /** The calls at any depth that failed. */
        int failed() {
            return calls(steps, true);
        }

        /** {@code 40 s}, {@code 6 min}, {@code 1 h 05 min}: a row gives the size of a turn, not its seconds. */
        private static String took(final long millis) {
            return millis < 60_000 ? Labels.elapsed(millis) : Labels.uptime(millis);
        }

        private static int calls(final List<Item> items, final boolean failed) {
            int n = 0;
            for (int k = 0, size = items.size(); k < size; k++) {
                final Item item = items.get(k);
                if (item instanceof ToolItem card && (!failed || card.call().status() == Block.ToolStatus.ERROR)) {
                    n++;
                }
                n += calls(item.children(), failed);
            }
            return n;
        }

        @Override
        void dropLayout() {
            header = null;
            dropPositions();
        }

        /** The row as it reads: the title, the failures in the danger colour, the open or closed mark. */
        Rich heading(final Style s) {
            final Rich text = new Rich(s.small, s.outside).append(title());
            final int failed = failed();
            if (failed > 0) {
                text.append(" · " + failed + " failed", s.small, Theme.DANGER);
            }
            return text.append(fold.isOpen() ? "  ▾" : "  ▸");
        }

        @Override
        int doLayout(final int w, final Style s) {
            header = new TextBox(heading(s)).noWrap();
            row = 2 + Math.max(14, header.layout(Math.max(40, w - TEXT_X), s)) + 2;
            int h = row;
            dropPositions();
            if (fold.isOpen()) {
                tops = new int[steps.size()];
                for (int i = 0; i < tops.length; i++) {
                    tops[i] = h;
                    if (!partial && bounded && (h > shownTo || System.nanoTime() - deadlineNanos > 0)) {
                        partial = true;
                        outOfTime = h <= shownTo;
                        laidTo = h;
                    }
                    final Item step = steps.get(i);
                    h += partial && step.needsLayout(w, s) ? step.estimate(w, s) : step.layout(w, s);
                }
            }
            return h;
        }

        @Override
        int guess(final int w, final Style s) {
            int h = 20;
            if (fold.isOpen()) {
                for (int k = 0, n = steps.size(); k < n; k++) {
                    final Item step = steps.get(k);
                    h += step.estimate(w, s);
                }
            }
            return h;
        }

        @Override
        void paint(final Graphics2D g, final int x, final int y, final Rectangle clip, final Marks marks) {
            Icons.get("tool", 12).paintIcon(marks.component(), g, x, y + 3);
            Paras.paintBox(g, header, x + TEXT_X, y + 2, clip, marks);
            for (int i = 0; i < tops.length; i++) {
                final Item step = steps.get(i);
                final int top = y + tops[i];
                if (step.laidOut() && top + step.height >= clip.y && top <= clip.y + clip.height) {
                    step.paint(g, x, top, clip, marks);
                }
            }
        }

        @Override
        void collect(final int x, final int y, final List<Placed> out) {
            for (int i = 0; i < tops.length; i++) {
                if (steps.get(i).laidOut()) {
                    steps.get(i).collect(x, y + tops[i], out);
                }
            }
        }

        @Override
        void findTexts(final List<String> out) {
            if (fold.isOpen()) {
                for (int k = 0, n = steps.size(); k < n; k++) {
                    final Item step = steps.get(k);
                    step.findTexts(out);
                }
            }
        }

        /** The step shown at {@code py} (relative), -1 when none. */
        private int stepAt(final int py) {
            for (int i = 0; i < tops.length; i++) {
                if (steps.get(i).laidOut() && py >= tops[i] && py < tops[i] + steps.get(i).height) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        Item toggleAt(final int px, final int py) {
            if (py < row) {
                return this;
            }
            final int i = stepAt(py);
            return i < 0 ? null : steps.get(i).toggleAt(px, py - tops[i]);
        }

        @Override
        String headerLinkAt(final int px, final int py) {
            final int i = stepAt(py);
            return i < 0 ? null : steps.get(i).headerLinkAt(px, py - tops[i]);
        }

        @Override
        boolean toggle() {
            fold.toggle();
            invalidate();
            return true;
        }

        @Override
        String text() {
            return "";
        }

        /** The steps as they read unfolded: an export does not depend on what is folded. */
        @Override
        String markdown() {
            final StringBuilder sb = new StringBuilder();
            for (int k = 0, n = steps.size(); k < n; k++) {
                final Item step = steps.get(k);
                final String m = step.markdown();
                if (!m.isEmpty()) {
                    sb.append(sb.isEmpty() ? "" : "\n\n").append(m);
                }
            }
            return sb.toString();
        }
    }

    static final class TurnItem extends Item {
        private TextBox label;
        private float labelWidth;

        TurnItem(final Block.TurnEnd b) {
            super(b);
        }

        private Block.TurnEnd turn() {
            return (Block.TurnEnd) block;
        }

        /** The turn failed or the user stopped it: it got as far as it did, and its last text is no conclusion. */
        boolean cutShort() {
            return turn().error();
        }

        /** The user stopped the turn: not a failure, so it reads as any text outside a card. */
        boolean interrupted() {
            return turn().error() && turn().text().equals("interrupted");
        }

        /**
         * {@code 12.4 s · 13:27}: how long the turn took, after the error text when it failed or was interrupted, then
         * when it ended ({@link #clock}). A turn of a stored conversation records no duration, so it shows only when it
         * ended; nothing when that is unknown too. No spend: a subscription's limits are the plan usage the status bar
         * shows, not money per turn.
         */
        String summary() {
            final Block.TurnEnd t = turn();
            final StringBuilder sb = new StringBuilder();
            if (t.timed()) {
                if (t.error()) {
                    sb.append(t.text()).append(" · ");
                }
                Formats.appendMillis(sb, t.durationMillis());
            }
            final String at = clock(t.atMillis());
            if (!at.isEmpty()) {
                sb.append(sb.isEmpty() ? "" : " · ").append(at);
            }
            return sb.toString();
        }

        /**
         * How long the turn took, in its own numbers or by its stored lines; {@link Block.TurnEnd#UNTIMED} if unknown.
         */
        long tookMillis() {
            return turn().tookMillis();
        }

        Color color(final Style s) {
            return turn().error() && !interrupted() ? Theme.DANGER : s.outside;
        }

        @Override
        void dropLayout() {
            label = null;
        }

        @Override
        int doLayout(final int w, final Style s) {
            label = new TextBox(new Rich(s.small, color(s)).append(summary())).noWrap();
            labelWidth = label.naturalWidth();
            label.layout(Math.max(1, (int) Math.ceil(labelWidth)), s);
            return 6 + label.height() + 8;
        }

        private int labelX(final int x) {
            return x + width() - Math.round(labelWidth);
        }

        @Override
        void paint(final Graphics2D g, final int x, final int y, final Rectangle clip, final Marks marks) {
            Paras.paintBox(g, label, labelX(x), y + 6, clip, marks);
            g.setColor(Theme.CARD_BORDER);
            g.fillRect(x, y + height - 1, width(), 1);
        }

        @Override
        void collect(final int x, final int y, final List<Placed> out) {
            out.add(new Placed(label, labelX(x), y + 6, this));
        }

        @Override
        void findTexts(final List<String> out) {
            out.add(Rich.detab(summary()));
        }

        @Override
        String text() {
            return summary();
        }

        @Override
        String markdown() {
            final String summary = summary();
            return summary.isEmpty() ? "" : "_" + summary + "_";
        }
    }
}
