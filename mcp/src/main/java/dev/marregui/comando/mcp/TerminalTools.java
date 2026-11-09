package dev.marregui.comando.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import dev.marregui.comando.core.model.ApprovalDecision;
import dev.marregui.comando.core.model.ApprovalKind;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.port.TerminalPort;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.PanelState;

/**
 * The terminal tools (record 148): each editor panel's Terminal, reached through the {@link TerminalPort} the
 * application sets once its window is built; until then every tool answers {@link #NOT_READY}. What a send types runs
 * with the user's rights, so it puts a {@link ApprovalKind#TERMINAL} card before the user, which only the user answers,
 * unless {@code agents.json} grants the caller {@link ActionTools#TERMINAL}. A read that waits polls the port on the
 * calling request thread, never on the event dispatch thread.
 */
final class TerminalTools {

    static final String NOT_READY = "refused: terminals are not ready";
    /** The longest text one send types (record 148). */
    static final int MAX_TEXT_CHARS = 4096;
    /** The most characters of text one read answers, its closing line aside (record 148). */
    static final int MAX_PAGE_CHARS = 65_536;
    /** Without {@code since}, a read answers this many of the last lines, at most {@link #MAX_LINES} (record 148). */
    private static final int DEFAULT_LINES = 200;
    private static final int MAX_LINES = 2000;
    /** The bounds of a wait on quiet output (record 148). */
    private static final int MIN_QUIET_MILLIS = 100;
    private static final int MAX_QUIET_MILLIS = 10_000;
    /** The longest wait one read makes (record 148). */
    private static final int MAX_WAIT_SECONDS = 120;
    /** How often a waiting read reads the port again (record 148). */
    private static final long POLL_MILLIS = 100;
    /**
     * A waiting read asks whether the shell still runs once in this many polls, once a second: the port's list reads
     * every panel's foreground job, which costs more than a page.
     */
    private static final int RUNNING_EVERY_POLLS = 10;
    /**
     * How long after the shell's last output a page can still lag the emulator: the quiet the terminal's input gate
     * waits for a starting shell, 200 ms.
     */
    private static final long SETTLE_NANOS = TimeUnit.MILLISECONDS.toNanos(200);
    private static final String CLOSING = "[first=";

    private final ActionTools.Asker asker;
    private final Predicate<String> mayType;
    private final ObjectMapper json;
    /** Written once by the application's thread through {@link #port}; read by every MCP request thread. */
    private volatile TerminalPort port;

    /**
     * @param mayType whether {@code agents.json} grants a caller {@code "terminal"}: its sends raise no card
     */
    TerminalTools(final ActionTools.Asker asker, final Predicate<String> mayType, final ObjectMapper json) {
        this.asker = asker;
        this.mayType = mayType;
        this.json = json;
    }

    void port(final TerminalPort port) {
        this.port = port;
    }

    /**
     * Whether {@code answer} is a page of {@link #read}: a page ends with its closing line, which no refusal has. A
     * page's text may itself begin with {@code refused}.
     */
    static boolean isPage(final String answer) {
        return answer.startsWith(CLOSING, answer.lastIndexOf('\n') + 1) && answer.endsWith("]");
    }

    String list() {
        final TerminalPort p = port;
        if (p == null) {
            return NOT_READY;
        }
        final ArrayNode out = json.createArrayNode();
        for (final TerminalPort.Terminal t : p.list()) {
            out.addObject()
                    .put("title", t.title())
                    .put("id", t.panel().value())
                    .put("started", t.started())
                    .put("running", t.running())
                    .put("pid", t.pid())
                    .put("job", t.job())
                    .put("directory", Objects.toString(t.directory(), ""))
                    .put("next", t.next())
                    .put("alternate", t.alternate());
        }
        return out.toString();
    }

    String start(final AppState s, final JsonNode args) {
        final TerminalPort p = port;
        if (p == null) {
            return NOT_READY;
        }
        final PanelState panel = ActionTools.panel(s, args, false);
        final String d = args.path("directory").asString("").strip();
        if (d.isEmpty()) {
            return p.start(panel.id(), Optional.empty());
        }
        final Path dir = Path.of(d);
        if (!dir.isAbsolute() || !Files.isDirectory(dir)) {
            throw new IllegalArgumentException("directory is not an absolute folder that exists [directory=" + d + ']');
        }
        return p.start(panel.id(), Optional.of(dir.normalize()));
    }

    /**
     * Types into a panel's shell. Without the caller's {@code "terminal"} grant, the user sees the text on a card
     * first, and nothing reaches the shell unless the user allows it.
     */
    String send(final String caller, final AppState s, final JsonNode args) {
        final TerminalPort p = port;
        if (p == null) {
            return NOT_READY;
        }
        final PanelState panel = ActionTools.panel(s, args, false);
        final String text = args.path("text").asString("");
        if (text.length() > MAX_TEXT_CHARS) {
            throw new IllegalArgumentException("text is too long for one send [length=" + text.length()
                    + ", max=" + MAX_TEXT_CHARS + ']');
        }
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            // a control character steers the terminal or the line discipline (Ctrl-C, escapes), not the command line
            if (Character.isISOControl(c) && c != '\t' && c != '\n') {
                throw new IllegalArgumentException("text holds a control character; only tab and newline pass [index="
                        + i + ", code=" + String.format(Locale.ROOT, "U+%04X", (int) c) + ']');
            }
        }
        final boolean enter = args.path("enter").asBoolean(true);
        if (!mayType.test(caller)) {
            final ObjectNode input = json.createObjectNode();
            input.put("panel", panel.title());
            input.put("text", text);
            input.put("enter", enter);
            final ApprovalDecision d = asker.ask(caller, ApprovalKind.TERMINAL, "terminal_send", input.toString(),
                    panel.title());
            if (!(d instanceof ApprovalDecision.Allow)) {
                return "refused: the user did not allow " + caller + " to type into the Terminal of " + panel.title()
                        + ": " + (d instanceof ApprovalDecision.Deny(final String message) && !message.isBlank()
                        ? message : "denied");
            }
        }
        return p.send(panel.id(), text, enter);
    }

    String interrupt(final AppState s, final JsonNode args) {
        final TerminalPort p = port;
        return p == null ? NOT_READY : p.interrupt(ActionTools.panel(s, args, false).id());
    }

    String stop(final AppState s, final JsonNode args) {
        final TerminalPort p = port;
        return p == null ? NOT_READY : p.stop(ActionTools.panel(s, args, false).id());
    }

    /**
     * A page of a panel's Terminal, then its closing line. With {@code wait_seconds}, looks every
     * {@link #POLL_MILLIS} and reads again while the page may miss output ({@link #stale}), until the first of: {@code until} matches a line of the page, no output for
     * {@code quiet_ms} since the later of the last output and the read's start, the shell no longer runs, the page is
     * full (what it leaves out cannot enter it), or the wait's end.
     */
    String read(final AppState s, final JsonNode args) {
        final TerminalPort p = port;
        if (p == null) {
            return NOT_READY;
        }
        final PanelState panel = ActionTools.panel(s, args, false);
        final long since = args.has("since") && !args.get("since").isNull()
                ? Math.max(-1, args.path("since").asLong(-1)) : -1;
        final int lines = bounded(args, "lines", DEFAULT_LINES, 1, MAX_LINES);
        final Optional<Pattern> until = until(args);
        final int quietMillis = args.has("quiet_ms") && !args.get("quiet_ms").isNull()
                ? bounded(args, "quiet_ms", MIN_QUIET_MILLIS, MIN_QUIET_MILLIS, MAX_QUIET_MILLIS) : 0;
        final int waitSeconds = bounded(args, "wait_seconds", 0, 0, MAX_WAIT_SECONDS);
        final long startNanos = System.nanoTime();
        final long waitNanos = TimeUnit.SECONDS.toNanos(waitSeconds);
        long readNanos = System.nanoTime();
        final Optional<TerminalPort.Page> first = p.read(panel.id(), since, lines, MAX_PAGE_CHARS);
        if (first.isEmpty()) {
            return "refused: panel " + panel.title() + " has no terminal yet; start its shell with terminal_start";
        }
        TerminalPort.Page page = first.get();
        boolean matched = until.isPresent() && matches(until.get(), page);
        for (int poll = 0; waitSeconds > 0 && !matched && !page.more(); poll++) {
            final long elapsedNanos = System.nanoTime() - startNanos;
            if (elapsedNanos - waitNanos >= 0 || quietMillis > 0 && quiet(p, panel.id(), quietMillis, startNanos)
                    || poll % RUNNING_EVERY_POLLS == 0 && !running(p, panel.id())) {
                break;
            }
            if (!pause(Math.min(POLL_MILLIS, TimeUnit.NANOSECONDS.toMillis(waitNanos - elapsedNanos) + 1))) {
                break;
            }
            if (!stale(p, panel.id(), readNanos)) {
                continue;
            }
            readNanos = System.nanoTime();
            final Optional<TerminalPort.Page> again = p.read(panel.id(), since, lines, MAX_PAGE_CHARS);
            if (again.isEmpty()) {
                break;
            }
            page = again.get();
            matched = until.isPresent() && matches(until.get(), page);
        }
        // a wait that ended on quiet output, the shell's end or its time answers the screen as it stands then
        if (waitSeconds > 0 && !matched && stale(p, panel.id(), readNanos)) {
            final Optional<TerminalPort.Page> last = p.read(panel.id(), since, lines, MAX_PAGE_CHARS);
            if (last.isPresent()) {
                page = last.get();
                matched = until.isPresent() && matches(until.get(), page);
            }
        }
        return answer(page, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos), matched);
    }

    private static Optional<Pattern> until(final JsonNode args) {
        final String u = args.path("until").asString("");
        if (u.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Pattern.compile(u));
        } catch (final PatternSyntaxException e) {
            throw new IllegalArgumentException("until is not a Java regular expression [until=" + u
                    + ", error=" + e.getDescription() + ']', e);
        }
    }

    private static int bounded(
            final JsonNode args,
            final String field,
            final int otherwise,
            final int min,
            final int max) {
        final JsonNode v = args.get(field);
        if (v == null || v.isNull()) {
            return otherwise;
        }
        final long n = v.asLong(otherwise);
        if (n < min || n > max) {
            throw new IllegalArgumentException(field + " is out of range [" + field + '=' + n + ", min=" + min
                    + ", max=" + max + ']');
        }
        return (int) n;
    }

    /** Whether a line of the page matches: every line of a page is at or after its {@code since}. */
    private static boolean matches(final Pattern until, final TerminalPort.Page page) {
        return page.text().lines().anyMatch(l -> until.matcher(l).find());
    }

    /** No output for {@code quietMillis}, counted from the later of the shell's last output and the read's start. */
    private static boolean quiet(
            final TerminalPort p,
            final PanelId panel,
            final int quietMillis,
            final long startNanos) {
        final long last = p.lastOutputNanos(panel).orElse(startNanos);
        // nanoTime values compare by their difference: they may wrap
        final long from = last - startNanos > 0 ? last : startNanos;
        return System.nanoTime() - from - TimeUnit.MILLISECONDS.toNanos(quietMillis) >= 0;
    }

    /**
     * Whether a page read at {@code readNanos} may miss output: the shell wrote after it, or less than
     * {@link #SETTLE_NANOS} before it. The pty stamps its output before the emulator has put it in the buffer, so a
     * read just after the stamp can still show the screen before it. A read runs {@code ps} for the job: a page that
     * is not stale is not read again.
     */
    private static boolean stale(final TerminalPort p, final PanelId panel, final long readNanos) {
        final Optional<Long> last = p.lastOutputNanos(panel);
        // nanoTime values compare by their difference: they may wrap
        return last.isPresent() && readNanos - last.get() - SETTLE_NANOS < 0;
    }

    private static boolean running(final TerminalPort p, final PanelId panel) {
        return p.list().stream().anyMatch(t -> t.panel().equals(panel) && t.running());
    }

    /** Sleeps {@code millis}; false when interrupted, with the interrupt kept: the wait ends with what it read. */
    private static boolean pause(final long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * The page's text, cut at {@link #MAX_PAGE_CHARS} if the port gave more, then its closing line. A cut page's
     * {@code next} is its first line the cut left incomplete, so the following read starts with it.
     */
    private static String answer(final TerminalPort.Page page, final long waitedMillis, final boolean matched) {
        String text = page.text();
        long next = page.next();
        boolean more = page.more();
        if (text.length() > MAX_PAGE_CHARS) {
            final int cut = Character.isHighSurrogate(text.charAt(MAX_PAGE_CHARS - 1))
                    ? MAX_PAGE_CHARS - 1 : MAX_PAGE_CHARS;
            text = text.substring(0, cut);
            more = true;
            if (!page.alternate()) {
                final long whole = text.chars().filter(c -> c == '\n').count();
                // a single line longer than a page moves on, so reading on never answers the same page
                next = page.first() + Math.max(1, whole);
            }
        }
        final StringBuilder out = new StringBuilder(text.length() + 160).append(text);
        if (!text.isEmpty() && !text.endsWith("\n")) {
            out.append('\n');
        }
        return out.append(CLOSING).append(page.first())
                .append(", next=").append(next)
                .append(", oldest=").append(page.oldest())
                .append(", skipped=").append(page.skipped())
                .append(", more=").append(more)
                .append(", job=").append(page.job())
                .append(", alternate=").append(page.alternate())
                .append(", waited=").append(waitedMillis).append("ms")
                .append(", matched=").append(matched)
                .append(']').toString();
    }
}
