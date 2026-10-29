package dev.marregui.comando.claude;

import dev.marregui.comando.core.model.PlanUsage;
import dev.marregui.comando.core.view.Clip;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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

/**
 * Reads the Codex plan's usage limits from the Codex CLI itself: {@code codex app-server} speaks JSON-RPC over stdio,
 * and {@code account/rateLimits/read} answers with the 5-hour session window and the week, each with its share used
 * and its reset time. The read sends no prompt and spends nothing; it takes about a second (measured 2026-10-27,
 * codex-cli 0.163.0). Without Codex installed or signed in, the read says why and the status bar shows nothing.
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

    /** The Codex CLI where its installers put it, else {@code codex} on the PATH. */
    static String findExecutable() {
        String env = System.getenv("COMANDO_CODEX");
        if (env != null && !env.isBlank()) {
            return env;
        }
        String home = System.getProperty("user.home");
        for (String candidate : List.of(home + "/.local/bin/codex", "/opt/homebrew/bin/codex", "/usr/local/bin/codex")) {
            if (Files.isExecutable(Path.of(candidate))) {
                return candidate;
            }
        }
        return "codex";
    }

    private static PlanUsage exitedWithoutAnswering(Process p) {
        return PlanUsage.failed(PlanUsage.CODEX, "'codex app-server' exited with code " + p.exitValue() + " without answering",
                System.currentTimeMillis());
    }

    /** Closes the server's input; a server that already exited makes the close fail, which changes nothing. */
    private static void closeQuietly(OutputStream in) {
        try {
            in.close();
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "closing 'codex app-server' input: " + e.getMessage());
        }
    }

    /** Runs the read; blocks up to {@code timeout}. Call it off the EDT and the reducer. */
    static PlanUsage read(String executable, ObjectMapper json, Duration timeout, ZoneId zone) {
        Process p = null;
        try {
            p = new ProcessBuilder(executable, "app-server").redirectError(ProcessBuilder.Redirect.DISCARD).start();
            OutputStream in = p.getOutputStream();
            try {
                in.write(REQUESTS.getBytes(StandardCharsets.UTF_8));
                in.flush();
            } catch (IOException e) {
                // a server that exits at once can close its end before the requests are written (a broken pipe on
                // Linux): say how it exited, as when it exits after reading them
                if (p.waitFor(1, TimeUnit.SECONDS)) {
                    return exitedWithoutAnswering(p);
                }
                throw e;
            }
            try {
                long deadline = System.nanoTime() + timeout.toNanos();
                InputStream out = p.getInputStream();
                ByteArrayOutputStream line = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                // polled, not a blocking read: a read blocked on the pipe would outlive the timeout
                while (System.nanoTime() < deadline) {
                    int ready = out.available();
                    if (ready == 0) {
                        if (!p.isAlive() && out.available() == 0) {
                            break;
                        }
                        Thread.sleep(20);
                        continue;
                    }
                    int n = out.read(buf, 0, Math.min(ready, buf.length));
                    for (int i = 0; i < n; i++) {
                        if (buf[i] != '\n') {
                            line.write(buf[i]);
                            continue;
                        }
                        String text = line.toString(StandardCharsets.UTF_8);
                        line.reset();
                        PlanUsage u = answer(text, System.currentTimeMillis(), json, zone);
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
        } catch (IOException e) {
            return PlanUsage.failed(PlanUsage.CODEX, "cannot run '" + executable + "': " + e.getMessage(), System.currentTimeMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PlanUsage.failed(PlanUsage.CODEX, "interrupted", System.currentTimeMillis());
        } finally {
            if (p != null) {
                stop(p);
            }
        }
    }

    /** Our own child: its stdin is closed, so it exits on its own; else terminate, then kill. */
    private static void stop(Process p) {
        try {
            if (!p.waitFor(1, TimeUnit.SECONDS)) {
                p.destroy();
                if (!p.waitFor(2, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * The read from one line of the server's output: the windows when it is the answer to the read, why there are
     * none when it is an error answer, and null for any other line.
     */
    static PlanUsage answer(String line, long readAtMillis, ObjectMapper json, ZoneId zone) {
        JsonNode msg;
        try {
            msg = json.readTree(line.strip());
        } catch (JacksonException e) {
            return null;
        }
        if (msg == null || !msg.isObject() || msg.path("id").asInt(-1) != 2) {
            return null;
        }
        if (msg.has("error")) {
            String why = msg.path("error").path("message").asString("");
            return PlanUsage.failed(PlanUsage.CODEX, Clip.keepStart(why.isBlank() ? "the rate-limit read failed" : why, 200), readAtMillis);
        }
        JsonNode limits = msg.path("result").path("rateLimits");
        // the account's bucket: limit id absent or "codex"; a model bucket does not describe the session or the week
        if (!isAccount(limits)) {
            limits = msg.path("result").path("rateLimitsByLimitId").path("codex");
        }
        List<PlanUsage.Window> windows = new ArrayList<>();
        for (String position : List.of("primary", "secondary")) {
            JsonNode w = limits.path(position);
            if (!w.path("usedPercent").isNumber()) {
                continue;
            }
            long resetsAt = w.path("resetsAt").asLong(0);
            boolean reset = resetsAt > 0 && resetsAt * 1000 <= readAtMillis;
            int percent = reset ? 0 : (int) Math.round(w.path("usedPercent").asDouble(0));
            windows.add(new PlanUsage.Window(name(w.path("windowDurationMins").asInt(0)), percent,
                    resetsAt > 0 ? RESETS.format(Instant.ofEpochSecond(resetsAt).atZone(zone)) : ""));
        }
        return windows.isEmpty()
                ? PlanUsage.failed(PlanUsage.CODEX, "no rate limits in Codex's answer", readAtMillis)
                : new PlanUsage(PlanUsage.CODEX, windows, readAtMillis, "");
    }

    private static boolean isAccount(JsonNode limits) {
        JsonNode id = limits.path("limitId");
        return limits.isObject() && (id.isMissingNode() || id.isNull() || id.asString("").equals("codex"));
    }

    /** "Session" for the 5-hour window, as Claude Code names its own; "Week"; "Month" on the monthly plans. */
    private static String name(int minutes) {
        return minutes >= MONTH_MINUTES ? "Month" : minutes >= WEEK_MINUTES ? "Week" : "Session";
    }
}
