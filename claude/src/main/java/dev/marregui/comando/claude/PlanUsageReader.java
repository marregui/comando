package dev.marregui.comando.claude;

import dev.marregui.comando.core.loop.Processes;
import dev.marregui.comando.core.model.PlanUsage;
import dev.marregui.comando.core.view.Clip;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the plan's usage limits the way {@code /usage} shows them, from Claude Code itself: {@code claude
 * --safe-mode -p /usage --no-session-persistence --output-format json}. {@code /usage} is a local command, so the
 * read sends no prompt and spends nothing; safe mode loads no hooks, plugins or MCP servers, so it starts in about a
 * second; no session is saved. The windows come from the command's text, one line each ("Current week (Fable): 17%
 * used · resets Oct 15 at 1:59pm (Europe/Madrid)"); a line in another form is skipped, and a read with none says why.
 */
final class PlanUsageReader {

    private static final Pattern WINDOW = Pattern.compile(
            "^Current (session|week)(?: \\(([^)]*)\\))?: (\\d+(?:\\.\\d+)?)% used(?: · resets (.+))?$");
    static final Duration TIMEOUT = Duration.ofSeconds(30);
    /** The most of the command's answer kept: its JSON is a few kilobytes. */
    static final int MAX_OUT = 1 << 20;

    private PlanUsageReader() {
    }

    /** Runs the read; blocks up to {@link #TIMEOUT}. Call it off the EDT and the reducer. */
    static PlanUsage read(String executable, RootLaunch launch, Path workDir, ObjectMapper json) {
        return read(executable, launch, workDir, json, TIMEOUT);
    }

    static PlanUsage read(String executable, RootLaunch launch, Path workDir, ObjectMapper json, Duration timeout) {
        try {
            Processes.Result r = Processes.run(launch.argv(List.of(executable, "--safe-mode", "-p", "/usage", "--no-session-persistence",
                    "--output-format", "json")), launch::environment, workDir != null && Files.isDirectory(workDir) ? workDir : null, null,
                    timeout, MAX_OUT);
            if (r.timedOut()) {
                return PlanUsage.failed("'claude -p /usage' did not answer within " + timeout.toMillis() + " ms", System.currentTimeMillis());
            }
            return parse(r.out(), System.currentTimeMillis(), json);
        } catch (IOException e) {
            return PlanUsage.failed("cannot run '" + executable + "': " + e.getMessage(), System.currentTimeMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PlanUsage.failed("interrupted", System.currentTimeMillis());
        }
    }

    /** The windows of the command's JSON result, or why there are none. */
    static PlanUsage parse(String output, long readAtMillis, ObjectMapper json) {
        JsonNode root;
        try {
            root = json.readTree(output == null ? "" : output.strip());
        } catch (JacksonException e) {
            return PlanUsage.failed("unreadable answer from 'claude -p /usage'", readAtMillis);
        }
        if (root == null || !root.isObject()) {
            return PlanUsage.failed("no answer from 'claude -p /usage'", readAtMillis);
        }
        String text = root.path("result").asString("");
        if (root.path("is_error").asBoolean(false)) {
            return PlanUsage.failed(firstLine(text, "'claude -p /usage' failed"), readAtMillis);
        }
        List<PlanUsage.Window> windows = new ArrayList<>();
        for (String line : text.split("\\R")) {
            Matcher m = WINDOW.matcher(line.strip());
            if (m.matches()) {
                windows.add(new PlanUsage.Window(name(m.group(1), m.group(2)), (int) Math.round(Double.parseDouble(m.group(3))),
                        m.group(4) == null ? "" : m.group(4).strip()));
            }
        }
        return windows.isEmpty()
                ? PlanUsage.failed("no plan limits in /usage: " + firstLine(text, "empty answer"), readAtMillis)
                : new PlanUsage(windows, readAtMillis, "");
    }

    /** "Session", "Week" for the week across all models, or the model a weekly window is for. */
    private static String name(String kind, String qualifier) {
        if (kind.equals("session")) {
            return "Session";
        }
        if (qualifier == null || qualifier.isBlank() || qualifier.toLowerCase(Locale.ROOT).startsWith("all")) {
            return "Week";
        }
        String q = qualifier.strip();
        return q.toLowerCase(Locale.ROOT).endsWith(" only") ? q.substring(0, q.length() - " only".length()) : q;
    }

    private static String firstLine(String text, String otherwise) {
        String s = text == null ? "" : text.strip();
        int nl = s.indexOf('\n');
        s = nl < 0 ? s : s.substring(0, nl);
        return s.isEmpty() ? otherwise : Clip.keepStart(s, 200);
    }
}
