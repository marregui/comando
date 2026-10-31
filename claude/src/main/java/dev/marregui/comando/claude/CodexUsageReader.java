package dev.marregui.comando.claude;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import dev.marregui.comando.core.model.PlanUsage;
import dev.marregui.comando.core.view.Clip;

/**
 * Reads the Codex plan's usage limits from the Codex CLI itself: {@code codex app-server} speaks JSON-RPC over stdio,
 * and {@code account/rateLimits/read} answers with the 5-hour session window and the week, each with its share used
 * and its reset time. The read sends no prompt and spends nothing; it takes about a second (measured on codex-cli
 * 0.163.0). Without Codex installed or signed in, the read says why and the status bar shows nothing.
 */
final class CodexUsageReader {

    private static final System.Logger LOG = System.getLogger(CodexUsageReader.class.getName());
    static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int WEEK_MINUTES = 10_080;
    private static final int MONTH_MINUTES = 43_200;
    /** Worded as Claude Code words its resets, so the status bar reads both alike: "Oct 27 at 12:06". */
    private static final DateTimeFormatter RESETS = DateTimeFormatter.ofPattern("MMM d 'at' H:mm", Locale.ENGLISH);
    private static final String REQUESTS = """
            {"id":1,"method":"initialize","params":{"clientInfo":{"name":"comando","version":"1"}}}
            {"method":"initialized"}
            {"id":2,"method":"account/rateLimits/read"}
            """;

    private CodexUsageReader() {
    }

    /** {@code COMANDO_CODEX}, else the Codex CLI where its installers put it, else {@code codex} on the PATH. */
    static String findExecutable() {
        final String env = System.getenv("COMANDO_CODEX");
        if (env != null && !env.isBlank()) {
            return env;
        }
        final String home = System.getProperty("user.home");
        for (final String candidate : List.of(home + "/.local/bin/codex", "/opt/homebrew/bin/codex", "/usr/local/bin/codex")) {
            if (Files.isExecutable(Path.of(candidate))) {
                return candidate;
            }
        }
        return "codex";
    }

    private static PlanUsage exitedWithoutAnswering(final Process p) {
        return PlanUsage.failed(PlanUsage.CODEX, "'codex app-server' exited with code " + p.exitValue() + " without answering",
                System.currentTimeMillis());
    }

    /** Closes the server's input; a server that already exited makes the close fail, which changes nothing. */
    private static void closeQuietly(final OutputStream in) {
        try {
            in.close();
        } catch (final IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "closing the 'codex app-server' input failed [cause=" + e.getMessage() + ']');
        }
    }

    /** Runs the read; blocks up to {@code timeout}. Call it off the EDT and the reducer. */
    static PlanUsage read(final String executable, final ObjectMapper json, final Duration timeout, final ZoneId zone) {
        Process p = null;
        try {
            p = new ProcessBuilder(executable, "app-server").redirectError(ProcessBuilder.Redirect.DISCARD).start();
            final OutputStream in = p.getOutputStream();
            try {
                in.write(REQUESTS.getBytes(StandardCharsets.UTF_8));
                in.flush();
            } catch (final IOException e) {
                // a server that exits at once can close its end before the requests are written (a broken pipe on
                // Linux): say how it exited, as when it exits after reading them
                if (p.waitFor(1, TimeUnit.SECONDS)) {
                    return exitedWithoutAnswering(p);
                }
                throw e;
            }
            try {
                final long deadline = System.nanoTime() + timeout.toNanos();
                final InputStream out = p.getInputStream();
                final ByteArrayOutputStream line = new ByteArrayOutputStream();
                final byte[] buf = new byte[8192];
                // polled, not a blocking read: a read blocked on the pipe would outlive the timeout
                while (System.nanoTime() < deadline) {
                    final int ready = out.available();
                    if (ready == 0) {
                        if (!p.isAlive() && out.available() == 0) {
                            break;
                        }
                        Thread.sleep(20);
                        continue;
                    }
                    final int n = out.read(buf, 0, Math.min(ready, buf.length));
                    for (int i = 0; i < n; i++) {
                        if (buf[i] != '\n') {
                            line.write(buf[i]);
                            continue;
                        }
                        final String text = line.toString(StandardCharsets.UTF_8);
                        line.reset();
                        final PlanUsage u = answer(text, System.currentTimeMillis(), json, zone);
                        if (u != null) {
                            return u;
                        }
                    }
                }
            } finally {
                closeQuietly(in);
            }
            return p.isAlive()
                    ? PlanUsage.failed(PlanUsage.CODEX, "'codex app-server' did not answer within " + timeout.toMillis() + " ms",
                    System.currentTimeMillis())
                    : exitedWithoutAnswering(p);
        } catch (final IOException e) {
            return PlanUsage.failed(PlanUsage.CODEX, "cannot run '" + executable + "': " + e.getMessage(), System.currentTimeMillis());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return PlanUsage.failed(PlanUsage.CODEX, "interrupted", System.currentTimeMillis());
        } finally {
            if (p != null) {
                stop(p);
            }
        }
    }

    /** Gives the server 1 s to exit on its closed input, then SIGTERM, then SIGKILL 2 s later. */
    private static void stop(final Process p) {
        try {
            if (!p.waitFor(1, TimeUnit.SECONDS)) {
                p.destroy();
                if (!p.waitFor(2, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            }
        } catch (final InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * The read from one line of the server's output: the windows when it is the answer to the read, why there are
     * none when it is an error answer, and null for any other line.
     */
    static PlanUsage answer(final String line, final long readAtMillis, final ObjectMapper json, final ZoneId zone) {
        JsonNode msg;
        try {
            msg = json.readTree(line.strip());
        } catch (final JacksonException e) {
            return null;
        }
        if (msg == null || !msg.isObject() || msg.path("id").asInt(-1) != 2) {
            return null;
        }
        if (msg.has("error")) {
            final String why = msg.path("error").path("message").asString("");
            return PlanUsage.failed(PlanUsage.CODEX, Clip.keepStart(why.isBlank() ? "the rate-limit read failed" : why, 200), readAtMillis);
        }
        JsonNode limits = msg.path("result").path("rateLimits");
        // the account's bucket: limit id absent or "codex"; a model bucket does not describe the session or the week
        if (!isAccount(limits)) {
            limits = msg.path("result").path("rateLimitsByLimitId").path("codex");
        }
        final List<PlanUsage.Window> windows = new ArrayList<>();
        for (final String position : List.of("primary", "secondary")) {
            final JsonNode w = limits.path(position);
            if (!w.path("usedPercent").isNumber()) {
                continue;
            }
            final long resetsAt = w.path("resetsAt").asLong(0);
            final boolean reset = resetsAt > 0 && resetsAt * 1000 <= readAtMillis;
            final int percent = reset ? 0 : (int) Math.round(w.path("usedPercent").asDouble(0));
            windows.add(new PlanUsage.Window(name(w.path("windowDurationMins").asInt(0)), percent,
                    resetsAt > 0 ? RESETS.format(Instant.ofEpochSecond(resetsAt).atZone(zone)) : ""));
        }
        return windows.isEmpty()
                ? PlanUsage.failed(PlanUsage.CODEX, "no rate limits in Codex's answer", readAtMillis)
                : new PlanUsage(PlanUsage.CODEX, windows, readAtMillis, "");
    }

    private static boolean isAccount(final JsonNode limits) {
        final JsonNode id = limits.path("limitId");
        return limits.isObject() && (id.isMissingNode() || id.isNull() || id.asString("").equals("codex"));
    }

    /** "Session" for the 5-hour window, as Claude Code names its own; "Week"; "Month" on the monthly plans. */
    private static String name(final int minutes) {
        return minutes >= MONTH_MINUTES ? "Month" : minutes >= WEEK_MINUTES ? "Week" : "Session";
    }
}
