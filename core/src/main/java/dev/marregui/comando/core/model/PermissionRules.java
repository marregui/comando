package dev.marregui.comando.core.model;

import java.net.URI;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The allow rules that "For session" and "Always" add for a tool call, derived the way Claude Code matches them:
 * a Bash command allows its command prefix (one rule per subcommand of a compound command, after the wrappers
 * Claude Code strips), an edit allows the file's directory, a read allows the directory it reads, a fetch allows
 * its host, and any other tool allows the tool. Only Bash and WebFetch rules may be saved beyond the session.
 * The MCP server sends the rules with the answer; the approval card shows them before the user commits.
 */
public final class PermissionRules {

    /** Commands whose second word is a subcommand: the rule keeps both words. */
    private static final Set<String> WITH_SUBCOMMANDS = Set.of("git", "npm", "pnpm", "yarn", "gradle", "./gradlew", "gradlew", "mvn",
            "docker", "kubectl", "cargo", "go", "gh", "brew", "make", "uv", "pip");
    /** Subcommands that run another program, named by the third word: the rule keeps three words. */
    private static final Set<String> RUNNERS = Set.of("run", "exec", "x", "dlx");
    /** Subcommands that run whatever follows inside a container: never a prefix. */
    private static final Set<String> CONTAINER_EXEC = Set.of("docker exec", "docker run", "kubectl exec");
    /** Python interpreters, whose {@code -m <module>} names the program: the rule keeps all three words. */
    private static final Pattern PYTHON = Pattern.compile("python(\\d+(\\.\\d+)?)?");
    /**
     * Commands that run arbitrary code or other commands: a prefix would allow anything after them, so they get
     * the exact command.
     */
    private static final Set<String> NEVER_PREFIX = Set.of("sudo", "su", "doas", "env", "xargs", "exec", "eval", "source", ".",
            "command", "sh", "bash", "zsh", "fish", "dash", "ksh", "tcsh", "csh", "ssh", "rm", "python", "python3", "node", "ruby",
            "perl", "php", "lua", "java", "jshell", "groovy", "Rscript", "julia", "swift", "osascript", "awk", "gawk", "mawk",
            "nawk", "deno", "bun", "npx", "pnpx", "bunx", "uvx",
            // exec wrappers Claude Code never approves by a prefix rule
            "watch", "setsid", "ionice", "flock");
    /** Shell constructs that nest commands; a command holding one gets its exact text as the only rule. */
    private static final String[] NESTED = {"$(", "`", "<(", ">("};
    private static final Pattern WORD = Pattern.compile("\\S+");
    private static final Pattern DURATION = Pattern.compile("\\d+(\\.\\d+)?[smhd]?");
    private static final Pattern NUMBER = Pattern.compile("[+-]?\\d+");

    private PermissionRules() {
    }

    /**
     * A rule: the tool and, when the rule is narrower than the tool, its content.
     *
     * @param toolName    the tool the rule names
     * @param ruleContent the pattern inside the parentheses, empty for the whole tool
     */
    public record Rule(String toolName, String ruleContent) {
        public Rule {
            Objects.requireNonNull(toolName, "toolName");
            ruleContent = ruleContent == null ? "" : ruleContent;
        }

        /** The rule as settings files and the CLI write it: {@code Bash(git commit:*)}, {@code mcp__x__y}. */
        public String text() {
            return ruleContent.isEmpty() ? toolName : toolName + '(' + ruleContent + ')';
        }
    }

    /**
     * The rules "For session" and "Always" add for a call, and whether they may be saved beyond the session.
     *
     * @param rules       one rule, or one per subcommand of a compound command
     * @param persistable whether "Always" may save them in local settings; true for Bash and WebFetch only, every
     *                    other tool is allowed for the session only
     */
    public record Rules(List<Rule> rules, boolean persistable) {
        public Rules {
            rules = List.copyOf(rules);
        }

        /** The rules as the card shows them, joined with ", ". */
        public String text() {
            return String.join(", ", rules.stream().map(Rule::text).toList());
        }
    }

    /**
     * The rules for a call of {@code tool}; see {@link Rules}.
     *
     * @param field the call's input field by name, empty when absent
     */
    public static Rules rules(final String tool, final Function<String, String> field) {
        return switch (tool) {
            case "Bash" -> {
                final String command = field.apply("command").strip();
                // no command to name: the tool itself, for the session only; saved, it would allow every command
                yield command.isEmpty() ? session(new Rule(tool, ""))
                        : new Rules(bash(command).stream().map(c -> new Rule(tool, c)).toList(), true);
            }
            case "WebFetch" -> {
                final String host = host(field.apply("url"));
                // no host to name (no scheme, a file: URL): the tool itself, for the session only
                yield host == null ? session(new Rule(tool, "")) : new Rules(List.of(new Rule(tool, "domain:" + host)), true);
            }
            // file permissions are checked against Edit rules only: a path rule naming Write or NotebookEdit is unused
            case "Edit", "Write", "MultiEdit" -> session(file("Edit", field.apply("file_path")));
            case "NotebookEdit" -> session(file("Edit", field.apply("notebook_path")));
            // Read rules cover every tool that reads files: Grep and Glob search the directory they are given
            case "Read" -> session(file("Read", field.apply("file_path")));
            case "Glob", "Grep" -> {
                final String dir = field.apply("path").strip();
                yield session(dir.isEmpty() ? new Rule(tool, "") : directory("Read", dir));
            }
            default -> session(new Rule(tool, ""));
        };
    }

    private static Rules session(final Rule rule) {
        return new Rules(List.of(rule), false);
    }

    // ---- Bash

    /**
     * The rule contents for a command: one per subcommand of {@code &&}, {@code ||}, {@code ;}, {@code |},
     * {@code |&}, {@code &} and newlines, as Claude Code matches them, each {@code prefix:*} or the subcommand's exact
     * text; the whole command when it nests commands ({@code $(…)}, backticks, process substitution) or feeds a
     * here-document, whose lines are data and never subcommands.
     */
    static List<String> bash(final String command) {
        for (final String op : NESTED) {
            if (command.contains(op)) {
                return List.of(command);
            }
        }
        final List<String> parts = split(command);
        if (parts == null) {
            return List.of(command);
        }
        final Set<String> out = new LinkedHashSet<>();
        for (final String part : parts) {
            out.add(simple(part));
        }
        return List.copyOf(out);
    }

    /**
     * The subcommands of {@code command} at {@code &&}, {@code ||}, {@code ;}, {@code |}, {@code |&}, {@code &} and
     * newlines outside quotes and escapes; null for an unbalanced quote or a here-document.
     */
    private static List<String> split(final String command) {
        final List<String> parts = new ArrayList<>();
        final int n = command.length();
        int start = 0;
        int i = 0;
        while (i < n) {
            final char c = command.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == '\'') {
                final int close = command.indexOf('\'', i + 1);
                if (close < 0) {
                    return null;
                }
                i = close + 1;
            } else if (c == '"') {
                int j = i + 1;
                while (j < n && command.charAt(j) != '"') {
                    j += command.charAt(j) == '\\' ? 2 : 1;
                }
                if (j >= n) {
                    return null;
                }
                i = j + 1;
            } else if (c == '<' && i + 1 < n && command.charAt(i + 1) == '<') {
                if (i + 2 < n && command.charAt(i + 2) == '<') {
                    i += 3; // a here-string: one word of data on its own line
                } else {
                    return null; // a here-document: the lines up to its delimiter are data, not commands
                }
            } else if (c == '\r') {
                i++;
            } else if (c == ';' || c == '|' || c == '&' || c == '\n') {
                int width;
                if (c == '&') {
                    final char prev = i > 0 ? command.charAt(i - 1) : ' ';
                    final char next = i + 1 < n ? command.charAt(i + 1) : ' ';
                    if (next == '&') {
                        width = 2;
                    } else if (prev == '>' || prev == '<' || next == '>') {
                        i++; // a redirection: 2>&1, &>file
                        continue;
                    } else {
                        width = 1; // a background job: Claude Code matches each side on its own
                    }
                } else if (c == '|') {
                    final char next = i + 1 < n ? command.charAt(i + 1) : ' ';
                    width = next == '|' || next == '&' ? 2 : 1;
                } else {
                    width = 1;
                }
                if (!add(parts, command.substring(start, i))) {
                    return null;
                }
                i += width;
                start = i;
            } else {
                i++;
            }
        }
        return add(parts, command.substring(start)) ? parts : null;
    }

    /** Adds a subcommand; an empty one (a blank line, a trailing {@code &}) adds nothing. */
    private static boolean add(final List<String> parts, final String part) {
        final String p = part.strip();
        if (!p.isEmpty()) {
            parts.add(p);
        }
        return true;
    }

    /** {@code prefix:*} for one simple command, else its exact text; the wrappers Claude Code strips come off first. */
    private static String simple(final String command) {
        final List<String> words = new ArrayList<>();
        final List<Integer> starts = new ArrayList<>();
        final Matcher m = WORD.matcher(command);
        while (m.find()) {
            words.add(m.group());
            starts.add(m.start());
        }
        final int k = stripWrappers(words);
        if (k < 0 || k >= words.size()) {
            return command; // a wrapper with nothing to run, or options it does not take
        }
        final String exact = command.substring(starts.get(k));
        final List<String> w = words.subList(k, words.size());
        final String name = baseName(w.get(0));
        int n;
        final boolean module = PYTHON.matcher(name).matches() && w.size() > 2 && w.get(1).equals("-m");
        if (module) {
            n = 3;
        } else if (NEVER_PREFIX.contains(name) || PYTHON.matcher(name).matches()) {
            return exact;
        } else if (name.equals("find") && w.stream().anyMatch(x -> x.equals("-exec") || x.equals("-execdir") || x.equals("-ok")
                || x.equals("-delete"))) {
            return exact; // find runs or deletes what it finds: `find:*` would allow any command through it
        } else if (name.equals("docker") && w.size() > 2 && w.get(1).equals("compose") && (w.get(2).equals("exec") || w.get(2).equals("run"))) {
            return exact; // runs a command in a container, like docker exec
        } else if ((WITH_SUBCOMMANDS.contains(w.get(0)) || WITH_SUBCOMMANDS.contains(name)) && w.size() > 1) {
            // by its base name too: /usr/bin/git commit is git commit, and its rule keeps the path as typed
            final String sub = w.get(1);
            if (CONTAINER_EXEC.contains(name + ' ' + sub)) {
                return exact;
            }
            if (RUNNERS.contains(sub)) {
                if (w.size() < 3 || NEVER_PREFIX.contains(baseName(w.get(2))) || PYTHON.matcher(baseName(w.get(2))).matches()) {
                    return exact; // the program run is unnamed, or runs anything itself
                }
                n = 3;
            } else {
                n = 2;
            }
        } else {
            n = 1;
        }
        for (int i = 0; i < n; i++) {
            if (!plainWord(w.get(i)) || i > 0 && w.get(i).startsWith("-") && !(module && i == 1)) {
                // a quote, a variable, an assignment or an option before the subcommand (git -C dir): no safe prefix
                return exact;
            }
        }
        return String.join(" ", w.subList(0, n)) + ":*";
    }

    /**
     * The index of the first word after the wrappers Claude Code strips before matching ({@code timeout} with its
     * duration, {@code time}, {@code nice} with its adjustment, {@code nohup}, {@code stdbuf} with its options,
     * {@code command}, {@code builtin}, {@code noglob}); -1 when a wrapper's arguments are not understood.
     */
    private static int stripWrappers(final List<String> words) {
        int i = 0;
        while (i < words.size()) {
            final String w = words.get(i);
            switch (w) {
                case "timeout" -> {
                    i++;
                    while (i < words.size() && words.get(i).startsWith("-")) {
                        final String o = words.get(i);
                        i += o.equals("-s") || o.equals("-k") || o.equals("--signal") || o.equals("--kill-after") ? 2 : 1;
                    }
                    if (i >= words.size() || !DURATION.matcher(words.get(i)).matches()) {
                        return -1;
                    }
                    i++;
                }
                case "time" -> {
                    i++;
                    if (i < words.size() && words.get(i).equals("-p")) {
                        i++;
                    }
                }
                case "nice" -> {
                    i++;
                    if (i < words.size()) {
                        final String o = words.get(i);
                        if (o.equals("-n") || o.equals("--adjustment")) {
                            if (i + 1 >= words.size() || !NUMBER.matcher(words.get(i + 1)).matches()) {
                                return -1;
                            }
                            i += 2;
                        } else if (o.startsWith("-n") && NUMBER.matcher(o.substring(2)).matches()
                                || o.startsWith("--adjustment=") || o.startsWith("-") && NUMBER.matcher(o).matches()) {
                            i++;
                        }
                    }
                }
                case "nohup", "builtin", "noglob" -> i++;
                case "command" -> {
                    if (i + 1 < words.size() && (words.get(i + 1).equals("-v") || words.get(i + 1).equals("-V"))) {
                        return i; // the query form, which Claude Code does not strip: the rule names `command` itself
                    }
                    i++;
                    if (i < words.size() && words.get(i).equals("-p")) {
                        i++;
                    }
                }
                case "stdbuf" -> {
                    i++;
                    while (i < words.size() && words.get(i).startsWith("-")) {
                        final String o = words.get(i);
                        i += o.equals("-i") || o.equals("-o") || o.equals("-e") ? 2 : 1;
                    }
                }
                default -> {
                    return i;
                }
            }
        }
        return i;
    }

    private static String baseName(final String word) {
        final int slash = word.lastIndexOf('/');
        return slash < 0 ? word : word.substring(slash + 1);
    }

    private static boolean plainWord(final String w) {
        if (w.isEmpty()) {
            return false;
        }
        for (int i = 0; i < w.length(); i++) {
            final char c = w.charAt(i);
            final boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || "._/:@+-".indexOf(c) >= 0;
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    // ---- paths

    /**
     * {@code Tool(//abs/dir/**)} for an absolute file, {@code Tool(./dir/**)} for a relative one, the whole tool for
     * none.
     */
    static Rule file(final String tool, final String file) {
        final String f = file == null ? "" : file.strip();
        if (f.isEmpty()) {
            return new Rule(tool, "");
        }
        Path p;
        try {
            p = Path.of(f).normalize();
        } catch (final InvalidPathException e) {
            return new Rule(tool, "");
        }
        final Path dir = p.getParent();
        if (dir == null) {
            return p.isAbsolute() ? new Rule(tool, "//**") : new Rule(tool, "./" + p.toString().replace('\\', '/'));
        }
        return directory(tool, dir.toString());
    }

    /** {@code Tool(//abs/dir/**)} for an absolute directory, {@code Tool(./dir/**)} for a relative one. */
    static Rule directory(final String tool, final String directory) {
        Path p;
        try {
            p = Path.of(directory.strip()).normalize();
        } catch (final InvalidPathException e) {
            return new Rule(tool, "");
        }
        final String s = p.toString().replace('\\', '/');
        if (p.isAbsolute()) {
            // an absolute path takes "//": a single leading slash anchors at the settings file's project instead
            return new Rule(tool, "/" + (s.equals("/") ? "" : s) + "/**");
        }
        return new Rule(tool, s.isEmpty() || s.equals(".") ? "./**" : "./" + s + "/**");
    }

    /** The host of {@code url}, lower case, or null when it has none. */
    static String host(final String url) {
        try {
            final String host = URI.create(url == null ? "" : url.strip()).getHost();
            return host == null || host.isBlank() ? null : host.toLowerCase(Locale.ROOT);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }
}
