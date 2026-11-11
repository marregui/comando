package dev.marregui.comando.core.logbook;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.marregui.comando.core.model.LinearTickets;

/**
 * Reads the scratchpad's {@code INDEX.md} into a {@link Logbook}: the {@code #### N. title} sections under
 * {@code ## Open}, each with the {@code **Key:**} lines below its heading (the scratchpad README §2).
 * <p>
 * The parser is tolerant, since sessions edit the index by hand: a key it does not know is ignored, a key that is
 * missing leaves its field empty, a {@code ####} heading without a number is skipped with its lines, and nothing
 * outside {@code ## Open} is a task ({@code ## Waiting on Miguel}, {@code ## Scheduled reports}). A key line is
 * {@code - **Key:** text} or {@code **Key:** text}; its value goes on over the following lines until the next key line,
 * a heading or a blank line. A key qualified in the heading's style ({@code **State (2026-11-02):**}) fills the field
 * when the plain key is absent.
 * <p>
 * A heading's attribution is its last parenthetical when that closes the heading and names who asked or when the task
 * started: {@code (Miguel, 2026-11-04)}, {@code (Dominic's question, Miguel, 2026-11-02)}. Its parts are split at
 * {@code ,} and {@code ;}; the start date is the first part that begins with a {@code YYYY-MM-DD} date, and who asked
 * is the first part that is a capitalised word ({@code Kenneth}) or a name and its possessive
 * ({@code Martin's ask}). A parenthetical that gives neither ({@code (from task 2, closed 2026-11-03)}) is part of the
 * title. A task's tickets are the Linear ids of its {@code **Ticket:**} line, then those that open its heading
 * ({@code EDG-1212: …}).
 */
public final class IndexParser {

    private static final String ASK = "ask";
    private static final Pattern BACKTICKED = Pattern.compile("`([^`]+)`");
    /** What may stand between the ticket ids that open a heading. */
    private static final Pattern BETWEEN_TICKETS = Pattern.compile("[\\s,;/&+]*");
    private static final Pattern BULLET = Pattern.compile("^\\s*[-*]\\s+(.*)$");
    /** A part of an attribution that begins with a date. */
    private static final Pattern DATED = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})(?!\\d)");
    private static final String DOCUMENTS = "documents";
    private static final Pattern KEY_LINE = Pattern.compile("^(?:[-*]\\s+)?\\*\\*([^*]+?)(?::\\*\\*|\\*\\*:)\\s*(.*)$");
    /** A part of an attribution that names who asked: a capitalised word, or that word and its possessive. */
    private static final Pattern NAMED = Pattern.compile("(\\p{Lu}\\p{Ll}+)(?:['\u2019]s\\s+\\p{L}+)?");
    private static final String NEEDS_YOU = "needs you";
    private static final String NEXT = "next";
    private static final String OPEN_HEADING = "## Open";
    private static final Pattern PARTS = Pattern.compile("[,;]");
    private static final String STATE = "state";
    /** At most nine digits, so the number always fits an {@code int}. */
    private static final Pattern TASK_HEADING = Pattern.compile("^#### (\\d{1,9})\\.\\s+(.+?)\\s*$");
    private static final String TICKET = "ticket";
    private static final List<String> FIELDS = List.of(ASK, DOCUMENTS, NEEDS_YOU, NEXT, STATE, TICKET);

    private IndexParser() {
    }

    /**
     * The open tasks of {@code text}, in the order the index lists them, as the Logbook of {@code index} (the file's
     * absolute path).
     */
    public static Logbook parse(final String index, final CharSequence text) {
        final List<LogbookTask> tasks = new ArrayList<>();
        String topic = "";
        boolean open = false;
        boolean fenced = false;
        Section current = null;
        for (final String line : text.toString().lines().toList()) {
            final boolean fence = line.stripLeading().startsWith("```");
            final boolean plain = !fenced && !fence;
            if (fence) {
                fenced = !fenced;
            }
            if (plain && (line.startsWith("## ") || line.equals("##"))) {
                finish(current, tasks);
                current = null;
                topic = "";
                open = line.equals(OPEN_HEADING) || line.startsWith(OPEN_HEADING + ' ');
            } else if (plain && open && line.startsWith("### ")) {
                finish(current, tasks);
                current = null;
                topic = topicOf(line);
            } else if (plain && open && line.startsWith("#### ")) {
                finish(current, tasks);
                final Matcher heading = TASK_HEADING.matcher(line);
                current = heading.matches()
                        ? new Section(Integer.parseInt(heading.group(1)), heading.group(2), topic, line)
                        : null;
            } else if (open && current != null) {
                current.lines.add(line);
            }
        }
        finish(current, tasks);
        return new Logbook(index, tasks, "");
    }

    /**
     * Where the last parenthetical of {@code title} opens when it closes the title: the index of its {@code (}; -1
     * when the title does not end with one. Parentheses inside backticks do not count.
     */
    static int attributionStart(final String title) {
        if (!title.endsWith(")")) {
            return -1;
        }
        int depth = 0;
        boolean code = false;
        for (int i = title.length() - 1; i >= 0; i--) {
            final char c = title.charAt(i);
            if (c == '`') {
                code = !code;
            } else if (!code && c == ')') {
                depth++;
            } else if (!code && c == '(' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static void finish(final Section section, final List<LogbookTask> tasks) {
        if (section != null) {
            tasks.add(section.task());
        }
    }

    /**
     * The folder name of a {@code ### `topic/`: what it holds} heading; the text before the colon when it has no
     * backticks.
     */
    private static String topicOf(final String heading) {
        final Matcher m = BACKTICKED.matcher(heading);
        if (m.find()) {
            return m.group(1);
        }
        final String text = heading.substring("### ".length());
        final int colon = text.indexOf(':');
        return (colon < 0 ? text : text.substring(0, colon)).strip();
    }

    /** The field a key fills: the known field the key names, or begins with then a space; null for any other key. */
    private static String fieldOf(final String key) {
        final String lower = key.strip().toLowerCase(Locale.ROOT);
        for (final String field : FIELDS) {
            if (lower.equals(field) || lower.startsWith(field + ' ')) {
                return field;
            }
        }
        return null;
    }

    /** The attribution of a task's title; {@link Attribution#NONE} when its last parenthetical is none. */
    private static Attribution attribution(final String title) {
        final int open = attributionStart(title);
        if (open < 0) {
            return Attribution.NONE;
        }
        final String from = title.substring(open + 1, title.length() - 1).strip();
        String started = "";
        String by = "";
        for (final String raw : PARTS.split(from)) {
            final String part = raw.strip();
            final Matcher date = DATED.matcher(part);
            if (started.isEmpty() && date.lookingAt()) {
                started = date.group(1);
            }
            final Matcher name = NAMED.matcher(part);
            if (by.isEmpty() && name.matches()) {
                by = name.group(1);
            }
        }
        return started.isEmpty() && by.isEmpty() ? Attribution.NONE : new Attribution(started, by, from);
    }

    /** The ticket ids of a {@code Ticket} value, then those that open the title; each once, in that order. */
    private static List<String> tickets(final Found ticket, final String title) {
        final Set<String> ids = new LinkedHashSet<>();
        if (ticket != null) {
            for (final String line : ticket.lines()) {
                final Matcher m = LinearTickets.ID.matcher(line);
                while (m.find()) {
                    ids.add(m.group());
                }
            }
        }
        final Matcher id = LinearTickets.ID.matcher(title);
        final Matcher between = BETWEEN_TICKETS.matcher(title);
        int at = 0;
        while (at < title.length() && id.region(at, title.length()).lookingAt()) {
            ids.add(id.group());
            at = between.region(id.end(), title.length()).lookingAt() ? between.end() : id.end();
        }
        return List.copyOf(ids);
    }

    /** The lines of a value as one text: wrapped lines run on with a space, a bullet starts a line. */
    private static String joined(final List<String> lines) {
        final StringBuilder text = new StringBuilder();
        for (final String raw : lines) {
            final String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (!text.isEmpty()) {
                text.append(BULLET.matcher(line).matches() ? '\n' : ' ');
            }
            text.append(line);
        }
        return text.toString();
    }

    /** The decisions of a {@code Needs you} value: its bullets, else its text unless that says there are none. */
    private static List<String> decisions(final List<String> lines) {
        final List<String> items = new ArrayList<>();
        for (final String line : lines.stream().skip(1).toList()) {
            final Matcher bullet = BULLET.matcher(line);
            if (bullet.matches()) {
                items.add(bullet.group(1).strip());
            } else if (!line.isBlank() && !items.isEmpty()) {
                items.set(items.size() - 1, items.getLast() + ' ' + line.strip());
            }
        }
        if (items.isEmpty()) {
            final String text = joined(lines);
            if (!text.isEmpty() && !text.equalsIgnoreCase("none")) {
                items.add(text);
            }
        }
        return items;
    }

    /** Who asked for a task and when it started, from its heading, and the attribution as written. */
    private record Attribution(String started, String by, String from) {

        static final Attribution NONE = new Attribution("", "", "");
    }

    /**
     * A value found for a field: its lines, the first being what follows the key on its line, and whether the key was
     * plain.
     */
    private record Found(boolean plain, List<String> lines) {
    }

    /** The lines of one task's section, heading first, taken apart when the section ends. */
    private static final class Section {

        private final int number;
        private final String title;
        private final String topic;
        private final List<String> lines = new ArrayList<>();

        Section(final int number, final String title, final String topic, final String heading) {
            this.number = number;
            this.title = title;
            this.topic = topic;
            lines.add(heading);
        }

        LogbookTask task() {
            final Map<String, Found> found = new HashMap<>();
            String key = null;
            List<String> value = null;
            for (final String line : lines.subList(1, lines.size())) {
                final Matcher m = KEY_LINE.matcher(line);
                if (m.matches()) {
                    keep(found, key, value);
                    key = m.group(1);
                    value = new ArrayList<>(List.of(m.group(2)));
                } else if (line.isBlank()) {
                    keep(found, key, value);
                    key = null;
                    value = null;
                } else if (value != null) {
                    value.add(line);
                }
            }
            keep(found, key, value);
            final List<String> documents = new ArrayList<>();
            final Found docs = found.get(DOCUMENTS);
            if (docs != null) {
                for (final String line : docs.lines()) {
                    final Matcher m = BACKTICKED.matcher(line);
                    while (m.find()) {
                        documents.add(m.group(1));
                    }
                }
            }
            final Found needs = found.get(NEEDS_YOU);
            final Attribution attribution = attribution(title);
            return new LogbookTask(number, title, topic, text(found, ASK), text(found, STATE), text(found, NEXT),
                    documents, needs == null ? List.of() : decisions(needs.lines()), markdown(), attribution.started(),
                    attribution.by(), attribution.from(), tickets(found.get(TICKET), title));
        }

        /**
         * Records the value of {@code key}; the first plain key of a field wins, a qualified one only while none is
         * plain.
         */
        private static void keep(final Map<String, Found> found, final String key, final List<String> value) {
            final String field = key == null ? null : fieldOf(key);
            if (field == null) {
                return;
            }
            final boolean plain = key.strip().equalsIgnoreCase(field);
            final Found before = found.get(field);
            if (before == null || plain && !before.plain()) {
                found.put(field, new Found(plain, value));
            }
        }

        private static String text(final Map<String, Found> found, final String field) {
            final Found f = found.get(field);
            return f == null ? "" : joined(f.lines());
        }

        private String markdown() {
            int end = lines.size();
            while (end > 1 && lines.get(end - 1).isBlank()) {
                end--;
            }
            return String.join("\n", lines.subList(0, end));
        }
    }
}
