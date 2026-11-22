package dev.marregui.comando.app;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// The logging rule (`docs/development.md`'s Apply section, part 11; G-5.3 and G-6.5), checked from the source text of
/// every module's `src/main/java`. A log call is `LOG.log(`, `log.log(`, `logger.log(` or `LOGGER.log(`, or any
/// `.log(` whose first argument is a `Level` constant (`System.getLogger(...).log(Level.DEBUG, ...)`); its message is
/// its second argument, a supplier's `() ->` dropped. The message is split at its top-level `+` into operands, read
/// left to right with the depth of the `[` and `]` its string and char literals hold.
/// - `shape`: an operand that is not a literal (a variable, a call, `e.getMessage()`) at depth 0, a `{0}` placeholder
///   at depth 0, or a `%s`-style specifier at depth 0 in a literal passed to `formatted` or `String.format`. A
///   message that is one non-literal operand (`text`, `e.toString()`) is not read: it may be a message built
///   elsewhere; nor is a leading non-literal operand (`what + " again [n=" + n + ']'`): it is the verb phrase,
///   built elsewhere. A parenthesised choice between two literals counts as text.
/// - `ascii`: a literal of the message holds a character outside ASCII.
/// - `cause`: a call at `WARNING`, `ERROR` or `SEVERE` inside a `catch` block whose parameter is named (not `_`)
///   neither passes the caught exception in an argument after the message (`e`, `e.getCause()`) nor carries it as a
///   value inside `[...]` (`[cause=" + e.getMessage() + "]`), nor says in its text before the tail that the
///   condition is handled, by one of the words `dropped`, `ignored`, `skipped`, `kept`, `closed`, `retrying`,
///   `trying again`, `falls back`, `fall back` or `instead` (`store closed, write dropped`): a handled
///   condition logs its message only. The innermost `catch` is read; a call whose level is not a constant is not.
///
/// Blind spots: a message built in a local or a helper, a value inside a leading phrase, a logger under another name,
/// a level held in a variable, and a failure whose text uses one of the handled words. Every site that broke a check
/// when the test was written is in `logging-baseline.txt`, one `path:check:message` line each, the message's
/// whitespace collapsed and no line number, so an edit elsewhere in the file does not move it. A new site fails its
/// check, and so does a baseline line no site matches any more: a fixed site leaves the baseline in the same change.
class LoggingRulesTest {

    // Gradle runs a module's tests in the module's folder
    private static final Path ROOT = Path.of("..");
    private static final String BASELINE = "logging-baseline.txt";
    private static final String BASELINE_SOURCE = "app/src/test/resources/dev/marregui/comando/app/" + BASELINE;
    private static final Pattern LOG_CALL = Pattern.compile(
            "(?<![\\w$])(?:LOG|log|logger|LOGGER)\\s*\\.\\s*log\\s*\\("
                    + "|\\.\\s*log\\s*\\((?=\\s*(?:System\\.Logger\\.|java\\.util\\.logging\\.)?Level\\.[A-Z]+)");
    private static final Pattern CATCH = Pattern.compile("(?<![\\w$])catch\\s*\\(");
    private static final Pattern LOUD = Pattern.compile("(?:^|\\.)(?:WARNING|ERROR|SEVERE)$");
    private static final Pattern LEVEL_CONSTANT = Pattern.compile(
            "(?:System\\.Logger\\.|java\\.util\\.logging\\.)?Level\\.[A-Z]+");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d+}");
    private static final Pattern SPECIFIER = Pattern.compile("%[-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z]");
    // a masked literal operand passed to formatted: "xxx".formatted(...)
    private static final Pattern FORMATTED = Pattern.compile("^(\"(?:\"\")?)x*\\1\\s*\\.\\s*formatted\\s*\\(.*",
            Pattern.DOTALL);
    private static final Pattern CHOICE = Pattern.compile("^\\(.*\\?\\s*\"x*\"\\s*:\\s*\"x*\"\\s*\\)$", Pattern.DOTALL);
    private static final Pattern SUPPLIER = Pattern.compile("^\\s*\\(\\s*\\)\\s*->");
    // a Unicode escape in a literal's source text: a backslash after an even run of them, one or more u, four digits
    private static final Pattern ESCAPE = Pattern.compile("(?<!\\\\)(?:\\\\\\\\)*\\\\u+([0-9a-fA-F]{4})");
    private static final Pattern HANDLED = Pattern.compile(
            "(?<!\\w)(?:dropped|ignored|skipped|kept|closed|retrying|trying again|falls? back|instead)(?!\\w)");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final char DASH = (char) 0x2014;

    /// No log message carries a value outside its `[...]` tail, beyond the sites the baseline holds.
    @Test
    void everyLogMessageCarriesItsValuesInBrackets() throws IOException {
        assertMatchesBaseline("shape", "put each value inside the message's tail as `[key=value, key=value]`");
    }

    /// No log message holds a character outside ASCII, beyond the sites the baseline holds.
    @Test
    void everyLogMessageIsAscii() throws IOException {
        assertMatchesBaseline("ascii", "write the message in ASCII");
    }

    /// No warning or error logged in a `catch` block drops the caught exception, beyond the sites the baseline holds.
    @Test
    void everyFailureLoggedInACatchKeepsItsException() throws IOException {
        assertMatchesBaseline("cause", "pass the exception as the last argument, or carry a handled condition's "
                + "message as `[cause=...]`");
    }

    /// The checks read the shapes the class comment names: a bracketed tail, a plain message, a leading phrase, a
    /// passed or bracketed exception, a handled condition and a choice of two literals pass; a value outside the tail,
    /// a placeholder, a format specifier, a non-ASCII literal and a dropped exception are each reported once, under
    /// their check.
    @Test
    void theChecksTellAShapedMessageFromAnUnshapedOne() {
        final String source = String.join("\n",
                "class Sample {",
                "    void run(final Path path, final int n) {",
                "        LOG.log(Level.INFO, \"opened [file=\" + path + \", n=\" + n + ']');",
                "        LOG.log(Level.INFO, \"store closed, write dropped\"); // \"cannot open \" + path",
                "        LOG.log(Level.INFO, \"closed \" + (n > 0 ? \"cleanly\" : \"early\"));",
                "        LOG.log(Level.INFO, () -> \"read [rows=\" + n + \"]\");",
                "        System.getLogger(\"x\").log(System.Logger.Level.DEBUG, \"cannot open \" + path);",
                "        LOG.log(Level.INFO, \"failed for {0}\", path);",
                "        LOG.log(Level.INFO, \"failed for %s\".formatted(path));",
                "        LOG.log(Level.INFO, \"read [file=%s]\".formatted(path));",
                "        LOG.log(Level.INFO, String.format(Locale.ROOT, \"failed for %s\", path));",
                "        LOG.log(Level.INFO, \"read \\u2014 done\");",
                "        LOG.log(Level.INFO, \"read " + DASH + " done\");",
                "        LOG.log(Level.INFO, text);",
                "        LOG.log(Level.INFO, what + \" again [n=\" + n + ']');",
                "        try {",
                "            open(path);",
                "        } catch (final IOException e) {",
                "            LOG.log(Level.WARNING, \"cannot open [file=\" + path + ']', e);",
                "            LOG.log(Level.WARNING, \"cannot open, kept [file=\" + path + \", cause=\"",
                "                    + e.getMessage() + ']');",
                "            LOG.log(Level.WARNING, \"cannot open, dropped [file=\" + path + ']');",
                "            LOG.log(Level.ERROR, \"cannot open [file=\" + path + ']');",
                "            LOG.log(Level.WARNING, \"cannot open\", e.getMessage());",
                "            LOG.log(Level.DEBUG, \"cannot open [file=\" + path + ']');",
                "        } catch (final RuntimeException _) {",
                "            LOG.log(Level.ERROR, \"cannot open\");",
                "        }",
                "    }",
                "}");
        final List<Site> sites = sites("Sample.java", source);
        assertThat(sites).extracting(Site::key).containsExactly(
                "Sample.java:shape:\"cannot open \" + path",
                "Sample.java:shape:\"failed for {0}\"",
                "Sample.java:shape:\"failed for %s\".formatted(path)",
                "Sample.java:shape:String.format(Locale.ROOT, \"failed for %s\", path)",
                "Sample.java:ascii:\"read \\u2014 done\"",
                "Sample.java:ascii:\"read " + DASH + " done\"",
                "Sample.java:cause:\"cannot open [file=\" + path + ']'",
                "Sample.java:cause:\"cannot open\"");
        assertThat(sites).extracting(Site::line).containsExactly(7, 8, 9, 11, 12, 13, 23, 24);
    }

    private static void assertMatchesBaseline(final String check, final String fix) throws IOException {
        final List<Site> found = new ArrayList<>();
        for (final Path file : mainSources()) {
            final String rel = file.toString().replace('\\', '/');
            for (final Site site : sites(rel, Files.readString(ROOT.resolve(file), StandardCharsets.UTF_8))) {
                if (site.check().equals(check)) {
                    found.add(site);
                }
            }
        }
        final Map<String, Integer> expected = new TreeMap<>();
        for (final String line : baseline()) {
            if (line.split(":", 3)[1].equals(check)) {
                expected.merge(line, 1, Integer::sum);
            }
        }
        final List<String> fresh = new ArrayList<>();
        for (final Site site : found) {
            final Integer left = expected.get(site.key());
            if (left == null) {
                fresh.add(site.rel() + ":" + site.line() + ": " + site.message() + " -- " + fix + "; baseline line: "
                        + site.key());
            } else if (left == 1) {
                expected.remove(site.key());
            } else {
                expected.put(site.key(), left - 1);
            }
        }
        final List<String> stale = new ArrayList<>();
        expected.forEach((line, times) -> {
            for (int i = 0; i < times; i++) {
                stale.add(line + " -- no site matches it any more; remove the line from " + BASELINE_SOURCE);
            }
        });
        assertThat(fresh).withFailMessage("%d new log calls break the %s check, not in %s:%n%s", fresh.size(), check,
                BASELINE_SOURCE, String.join(System.lineSeparator(), fresh)).isEmpty();
        assertThat(stale).withFailMessage("%d stale lines of the %s check in the baseline:%n%s", stale.size(), check,
                String.join(System.lineSeparator(), stale)).isEmpty();
    }

    private static List<String> baseline() throws IOException {
        try (final InputStream in = LoggingRulesTest.class.getResourceAsStream(BASELINE)) {
            assertThat(in).as(BASELINE_SOURCE).isNotNull();
            final List<String> lines = new ArrayList<>();
            for (final String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    final String[] parts = line.split(":", 3);
                    assertThat(parts.length == 3 && List.of("shape", "ascii", "cause").contains(parts[1]))
                            .as("a baseline line is path:shape|ascii|cause:message, in %s: %s", BASELINE_SOURCE, line)
                            .isTrue();
                    lines.add(line);
                }
            }
            return lines;
        }
    }

    private static List<Path> mainSources() throws IOException {
        final List<Path> files = new ArrayList<>();
        try (final Stream<Path> modules = Files.list(ROOT)) {
            for (final Path module : modules.sorted().toList()) {
                final Path main = module.resolve("src/main/java");
                if (!Files.isDirectory(main)) {
                    continue;
                }
                try (final Stream<Path> walk = Files.walk(main)) {
                    walk.filter(p -> p.toString().endsWith(".java")).sorted()
                            .forEach(p -> files.add(ROOT.relativize(p)));
                }
            }
        }
        assertThat(files).as("this module's own sources, read from the repository's root")
                .contains(Path.of("app/src/main/java/dev/marregui/comando/app/App.java"));
        return files;
    }

    /// The breaches of every check in one file's source text, in the order of their calls.
    private static List<Site> sites(final String rel, final String text) {
        final String masked = mask(text);
        final List<Catch> catches = catches(masked);
        final List<Site> sites = new ArrayList<>();
        final Matcher m = LOG_CALL.matcher(masked);
        while (m.find()) {
            final List<int[]> args = arguments(masked, m.end() - 1);
            if (args.size() < 2) {
                continue;
            }
            final int line = 1 + (int) text.substring(0, m.start()).chars().filter(c -> c == '\n').count();
            int from = args.get(1)[0];
            final int to = args.get(1)[1];
            final Matcher supplier = SUPPLIER.matcher(masked.substring(from, to));
            if (supplier.find()) {
                from += supplier.end();
            }
            final String message = WHITESPACE.matcher(text.substring(from, to).strip()).replaceAll(" ");
            final List<int[]> operands = operands(masked, from, to);
            if (operands.size() > 1 || literal(masked, operands.getFirst())) {
                if (!shaped(text, masked, operands)) {
                    sites.add(new Site(rel, line, "shape", message));
                }
            }
            if (!ascii(text, masked, operands)) {
                sites.add(new Site(rel, line, "ascii", message));
            }
            final Catch within = innermost(catches, m.start());
            final String level = text.substring(args.getFirst()[0], args.getFirst()[1]).strip();
            if (within != null && LEVEL_CONSTANT.matcher(level).matches() && LOUD.matcher(level).find()
                    && !keepsCause(text, masked, args, operands, within.name())) {
                sites.add(new Site(rel, line, "cause", message));
            }
        }
        return sites;
    }

    private static boolean shaped(final String text, final String masked, final List<int[]> operands) {
        int depth = 0;
        for (final int[] op : operands) {
            if (op == operands.getFirst() && content(text, masked, op) == null) {
                continue;
            }
            final String code = masked.substring(op[0], op[1]).strip();
            final String content = content(text, masked, op);
            if (content != null) {
                for (int i = 0; i < content.length(); i++) {
                    final char c = content.charAt(i);
                    if (c == '[') {
                        depth++;
                    } else if (c == ']') {
                        depth = Math.max(0, depth - 1);
                    } else if (depth == 0 && c == '{' && PLACEHOLDER.matcher(content).region(i, content.length())
                            .lookingAt()) {
                        return false;
                    } else if (depth == 0 && c == '%' && formats(code)
                            && SPECIFIER.matcher(content).region(i, content.length()).lookingAt()) {
                        return false;
                    }
                }
            } else if (depth == 0 && !CHOICE.matcher(code).matches()) {
                return false;
            }
        }
        return true;
    }

    private static boolean formats(final String code) {
        return FORMATTED.matcher(code).matches() || code.startsWith("String.format(");
    }

    private static boolean ascii(final String text, final String masked, final List<int[]> operands) {
        final StringBuilder literals = new StringBuilder();
        for (final int[] op : operands) {
            for (int i = op[0]; i < op[1]; i++) {
                if (masked.charAt(i) == 'x' && text.charAt(i) != 'x') {
                    literals.append(text.charAt(i));
                } else {
                    literals.append(' ');
                }
            }
        }
        if (literals.chars().anyMatch(c -> c > 127)) {
            return false;
        }
        final Matcher escape = ESCAPE.matcher(literals);
        while (escape.find()) {
            if (Integer.parseInt(escape.group(1), 16) > 127) {
                return false;
            }
        }
        return true;
    }

    private static boolean keepsCause(
            final String text,
            final String masked,
            final List<int[]> args,
            final List<int[]> operands,
            final String name
    ) {
        final Pattern use = Pattern.compile("(?<![\\w$.])" + Pattern.quote(name) + "(?![\\w$])");
        // the thrown overload, log(level, message, thrown): the exception itself, not a part of it such as its
        // message, which a message without a placeholder never prints
        for (int i = 2; i < args.size(); i++) {
            if (masked.substring(args.get(i)[0], args.get(i)[1]).strip().equals(name)) {
                return true;
            }
        }
        final StringBuilder before = new StringBuilder();
        int depth = 0;
        for (final int[] op : operands) {
            final String content = content(text, masked, op);
            if (content != null) {
                for (int i = 0; i < content.length(); i++) {
                    if (depth == 0 && content.charAt(i) != '[') {
                        before.append(content.charAt(i));
                    }
                    if (content.charAt(i) == '[') {
                        depth++;
                    } else if (content.charAt(i) == ']') {
                        depth = Math.max(0, depth - 1);
                    }
                }
            } else if (depth > 0 && use.matcher(masked.substring(op[0], op[1])).find()) {
                return true;
            }
        }
        return HANDLED.matcher(before).find();
    }

    /// The characters of a literal operand (a string, a text block, a char, or a string passed to `formatted` or
    /// `String.format`), or `null` for any other operand.
    private static String content(final String text, final String masked, final int[] op) {
        int from = op[0];
        while (from < op[1] && Character.isWhitespace(masked.charAt(from))) {
            from++;
        }
        final String code = masked.substring(from, op[1]).strip();
        if (code.startsWith("String.format(")) {
            final int open = masked.indexOf('(', from);
            final List<int[]> args = arguments(masked, open);
            if (args.isEmpty()) {
                return null;
            }
            // String.format(Locale, format, args...) puts the format second
            final int[] format = args.size() > 1 && !literal(masked, args.getFirst()) ? args.get(1) : args.getFirst();
            return literal(masked, format) ? content(text, masked, format) : null;
        }
        if (code.isEmpty() || code.charAt(0) != '"' && code.charAt(0) != '\'') {
            return null;
        }
        final char quote = code.charAt(0);
        final int width = code.startsWith("\"\"\"") ? 3 : 1;
        int end = from + width;
        while (end < op[1] && masked.charAt(end) == 'x') {
            end++;
        }
        final boolean whole = masked.substring(end + width, op[1]).isBlank();
        if (!whole && !(quote == '"' && FORMATTED.matcher(code).matches())) {
            return null;
        }
        return text.substring(from + width, end);
    }

    private static boolean literal(final String masked, final int[] op) {
        final String code = masked.substring(op[0], op[1]).strip();
        return code.matches("(?s)(\"\"\"x*\"\"\"|\"x*\"|'x*')") || FORMATTED.matcher(code).matches()
                || code.startsWith("String.format(");
    }

    /// The source with comments blanked and every character inside a string, text block or char literal replaced by
    /// `x`, offsets kept, so brackets, commas and operators are read from code only.
    private static String mask(final String text) {
        final StringBuilder out = new StringBuilder(text);
        int i = 0;
        while (i < text.length()) {
            final char c = text.charAt(i);
            if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                while (i < text.length() && text.charAt(i) != '\n') {
                    out.setCharAt(i++, ' ');
                }
            } else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                final int end = text.indexOf("*/", i + 2);
                final int stop = end < 0 ? text.length() : end + 2;
                for (; i < stop; i++) {
                    if (text.charAt(i) != '\n') {
                        out.setCharAt(i, ' ');
                    }
                }
            } else if (text.startsWith("\"\"\"", i)) {
                final int end = text.indexOf("\"\"\"", i + 3);
                final int stop = end < 0 ? text.length() : end;
                for (i += 3; i < stop; i++) {
                    out.setCharAt(i, 'x');
                }
                i += 3;
            } else if (c == '"' || c == '\'') {
                i++;
                while (i < text.length() && text.charAt(i) != c && text.charAt(i) != '\n') {
                    out.setCharAt(i, 'x');
                    if (text.charAt(i) == '\\' && i + 1 < text.length()) {
                        out.setCharAt(++i, 'x');
                    }
                    i++;
                }
                i++;
            } else {
                i++;
            }
        }
        return out.toString();
    }

    /// The arguments of the call whose `(` is at `open`, each as `{from, to}` offsets.
    private static List<int[]> arguments(final String masked, final int open) {
        final List<int[]> args = new ArrayList<>();
        int depth = 0;
        int start = open + 1;
        for (int i = open; i < masked.length(); i++) {
            final char c = masked.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
                if (depth == 0) {
                    if (!masked.substring(start, i).isBlank()) {
                        args.add(new int[]{start, i});
                    }
                    return args;
                }
            } else if (c == ',' && depth == 1) {
                args.add(new int[]{start, i});
                start = i + 1;
            }
        }
        return args;
    }

    /// The operands of the expression between `from` and `to`, split at its top-level `+`.
    private static List<int[]> operands(final String masked, final int from, final int to) {
        final List<int[]> ops = new ArrayList<>();
        int depth = 0;
        int start = from;
        for (int i = from; i < to; i++) {
            final char c = masked.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (c == '+' && depth == 0) {
                ops.add(new int[]{start, i});
                start = i + 1;
            }
        }
        ops.add(new int[]{start, to});
        return ops;
    }

    private static List<Catch> catches(final String masked) {
        final List<Catch> catches = new ArrayList<>();
        final Matcher m = CATCH.matcher(masked);
        while (m.find()) {
            final List<int[]> header = arguments(masked, m.end() - 1);
            if (header.isEmpty()) {
                continue;
            }
            final String[] words = masked.substring(header.getFirst()[0], header.getFirst()[1]).strip().split("\\s+");
            final int close = header.getFirst()[1];
            final int open = masked.indexOf('{', close);
            if (open < 0) {
                continue;
            }
            int depth = 0;
            for (int i = open; i < masked.length(); i++) {
                if (masked.charAt(i) == '{') {
                    depth++;
                } else if (masked.charAt(i) == '}' && --depth == 0) {
                    final String name = words[words.length - 1];
                    if (!name.equals("_")) {
                        catches.add(new Catch(open, i, name));
                    }
                    break;
                }
            }
        }
        return catches;
    }

    private static Catch innermost(final List<Catch> catches, final int at) {
        Catch best = null;
        for (final Catch c : catches) {
            if (c.from() < at && at < c.to() && (best == null || c.from() > best.from())) {
                best = c;
            }
        }
        return best;
    }

    /// A log call that breaks `check`, at `line` of the file at `rel`.
    private record Site(String rel, int line, String check, String message) {

        String key() {
            return rel + ":" + check + ":" + message;
        }
    }

    private record Catch(int from, int to, String name) {
    }
}
