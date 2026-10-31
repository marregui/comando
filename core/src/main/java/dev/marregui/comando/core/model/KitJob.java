package dev.marregui.comando.core.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One scheduled job of the kit, as {@code comando-kit.json} holds it and {@code install/kit.py} runs it (record 117):
 * its name, a one-line description, the days of the week it runs on (all seven: daily) at a time, the command (a path
 * inside the root, then its arguments), the environment it adds, the file its output goes to (empty:
 * {@code .kit-jobs/<name>.log}) and whether it runs on its schedule or is paused. {@code kit.py} is the judge of a job;
 * {@link #problems} checks the same rules as they are typed.
 *
 * @param days    the weekdays, {@code MON} … {@code SUN}, in week order; all seven for a daily job
 * @param time    {@code HH:MM}, 24 hours
 * @param command the program, relative to the root with {@code /}, then its arguments; {@code {scheduler}} in any of
 *                them becomes the scheduler that started it
 */
public record KitJob(String name, String description, List<String> days, String time, List<String> command,
                     Map<String, String> env, String log, boolean enabled) {

    /** The weekdays in week order, as the manifest writes them. */
    public static final List<String> WEEKDAYS = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");

    static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,31}");
    static final Pattern ENV = Pattern.compile("[A-Z_][A-Z0-9_]*");
    static final Pattern TIME = Pattern.compile("([01]?[0-9]|2[0-3]):([0-5][0-9])");

    public static final String NAME_FIELD = "name";
    public static final String DESCRIPTION_FIELD = "description";
    public static final String DAYS_FIELD = "days";
    public static final String TIME_FIELD = "time";
    public static final String COMMAND_FIELD = "command";
    public static final String ENV_FIELD = "env";
    public static final String LOG_FIELD = "log";

    public KitJob {
        Objects.requireNonNull(name, "name");
        description = description == null ? "" : description;
        days = WEEKDAYS.stream().filter(List.copyOf(days)::contains).toList();
        Objects.requireNonNull(time, "time");
        command = List.copyOf(command);
        env = Map.copyOf(env);
        log = log == null ? "" : log;
    }

    /** Whether it runs every day. */
    public boolean daily() {
        return days.size() == WEEKDAYS.size();
    }

    /** When it runs, as the manifest writes it: {@code daily 07:15}, {@code MON-FRI 10:15}, {@code MON,THU 07:30}. */
    public String when() {
        return (daily() ? "daily" : daysText(days)) + " " + paddedTime();
    }

    /** The time zero-padded, {@code 07:15}; as typed when it is no time. */
    public String paddedTime() {
        final var m = TIME.matcher(time.strip());
        return m.matches() ? String.format(Locale.ROOT, "%02d:%s", Integer.parseInt(m.group(1)), m.group(2)) : time.strip();
    }

    /**
     * {@code days} as the manifest writes them: a run of three or more as a range ({@code MON-FRI}), else one by one.
     */
    public static String daysText(final List<String> days) {
        final List<Integer> at = WEEKDAYS.stream().filter(days::contains).map(WEEKDAYS::indexOf).toList();
        final List<String> out = new ArrayList<>();
        int i = 0;
        while (i < at.size()) {
            int j = i;
            while (j + 1 < at.size() && at.get(j + 1) == at.get(j) + 1) {
                j++;
            }
            if (j - i >= 2) {
                out.add(WEEKDAYS.get(at.get(i)) + "-" + WEEKDAYS.get(at.get(j)));
            } else {
                for (int k = i; k <= j; k++) {
                    out.add(WEEKDAYS.get(at.get(k)));
                }
            }
            i = j + 1;
        }
        return String.join(",", out);
    }

    /** The program, the command's first word; empty when there is none. */
    public String program() {
        return command.isEmpty() ? "" : command.getFirst();
    }

    /** The arguments after the program. */
    public List<String> arguments() {
        return command.size() <= 1 ? List.of() : command.subList(1, command.size());
    }

    /**
     * The problems of job {@code self} of {@code all} by field, as {@code kit.py} would refuse it: a name not in its
     * form or used twice, a description longer than one line of 80, no day, a time that is no {@code HH:MM}, no program
     * or one outside the root, an environment name not in capitals, a log outside the root. Whether the program is an
     * executable file is {@code kit.py}'s to say.
     */
    public static Map<String, String> problems(final List<KitJob> all, final int self) {
        final KitJob j = all.get(self);
        final Map<String, String> out = new LinkedHashMap<>();
        if (j.name.isBlank()) {
            out.put(NAME_FIELD, "a name is needed");
        } else if (!NAME.matcher(j.name).matches()) {
            out.put(NAME_FIELD, "lower-case letters, digits and -, from a letter, at most 32");
        } else {
            for (int k = 0; k < all.size(); k++) {
                if (k != self && all.get(k).name.equals(j.name)) {
                    out.put(NAME_FIELD, "another job has this name");
                    break;
                }
            }
        }
        if (j.description.contains("\n") || j.description.codePointCount(0, j.description.length()) > 80) {
            out.put(DESCRIPTION_FIELD, "one line of at most 80 characters");
        }
        if (j.days.isEmpty()) {
            out.put(DAYS_FIELD, "pick at least one day");
        }
        if (!TIME.matcher(j.time.strip()).matches()) {
            out.put(TIME_FIELD, "HH:MM, from 00:00 to 23:59");
        }
        if (j.program().isBlank()) {
            out.put(COMMAND_FIELD, "choose the program, in the kit's bin/");
        } else if (!insideRoot(j.program())) {
            out.put(COMMAND_FIELD, "a path inside the root, with / and no ..");
        }
        for (final String k : j.env.keySet()) {
            if (!ENV.matcher(k).matches()) {
                out.put(ENV_FIELD, k + ": names in capitals, digits and _");
                break;
            }
        }
        if (!j.log.isEmpty() && !insideRoot(j.log)) {
            out.put(LOG_FIELD, "a path inside the root, with / and no ..");
        }
        return out;
    }

    /**
     * As {@code kit.py}'s {@code inside}: relative, with {@code /}, no empty part, no {@code ..}, no drive in the first
     * part.
     */
    private static boolean insideRoot(final String rel) {
        final String[] parts = rel.split("/", -1);
        if (rel.startsWith("/") || rel.contains("\\") || parts[0].contains(":")) {
            return false;
        }
        for (final String part : parts) {
            if (part.isEmpty() || part.equals("..")) {
                return false;
            }
        }
        return true;
    }

    /**
     * The words of {@code line}, split at spaces; '…' and "…" keep spaces inside a word, and a backslash escapes in
     * "…".
     */
    public static List<String> words(final String line) {
        final List<String> out = new ArrayList<>();
        final StringBuilder w = new StringBuilder();
        boolean in = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            final char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else if (c == '\\' && quote == '"' && i + 1 < line.length()) {
                    w.append(line.charAt(++i));
                } else {
                    w.append(c);
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
                in = true;
            } else if (Character.isWhitespace(c)) {
                if (in) {
                    out.add(w.toString());
                    w.setLength(0);
                    in = false;
                }
            } else {
                w.append(c);
                in = true;
            }
        }
        if (in) {
            out.add(w.toString());
        }
        return out;
    }

    /** {@code args} as one line {@link #words} reads back: a word with a space, a quote or nothing in it is quoted. */
    public static String line(final List<String> args) {
        final List<String> out = new ArrayList<>(args.size());
        for (final String a : args) {
            final boolean plain = !a.isEmpty() && a.chars().noneMatch(c -> Character.isWhitespace(c) || c == '\'' || c == '"' || c == '\\');
            out.add(plain ? a : a.indexOf('\'') < 0 ? "'" + a + "'" : "\"" + a.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
        }
        return String.join(" ", out);
    }

    /** The environment as {@code NAME=value} lines, in name order. */
    public String envLines() {
        return String.join("\n", env.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e -> e.getKey() + "=" + e.getValue()).toList());
    }

    /**
     * {@code NAME=value} lines as an environment: the name stripped, the value kept as written, spaces included; a line
     * with no {@code =} names a variable set to nothing; blank lines are skipped.
     */
    public static Map<String, String> envOf(final String lines) {
        final Map<String, String> out = new LinkedHashMap<>();
        for (final String l : lines.split("\n")) {
            if (l.isBlank()) {
                continue;
            }
            final int eq = l.indexOf('=');
            out.put(eq < 0 ? l.strip() : l.substring(0, eq).strip(), eq < 0 ? "" : l.substring(eq + 1));
        }
        return out;
    }
}
