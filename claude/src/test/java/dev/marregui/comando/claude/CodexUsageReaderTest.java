package dev.marregui.comando.claude;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.model.PlanUsage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// The usage reader turns account rate-limit replies into `PlanUsage` windows.
///
/// - reads the session and weekly shares
/// - rejects unrelated server messages
/// - reports process failures and timeouts
class CodexUsageReaderTest {

    private static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    /** 2026-10-27 07:00 UTC. */
    private static final long NOW = 1_793_084_400_000L;
    private static final long FIVE_HOURS_ON = NOW / 1000 + 5 * 3600;
    private static final long WEEK_ON = NOW / 1000 + 6 * 86_400;
    /// A reset in 2096: a read through the real executable compares resets with the wall clock, not `NOW`, so a
    /// reset tied to `NOW` passes in real time (`WEEK_ON` did on 2026-11-02 and turned the week's 3 % into 0).
    private static final long FAR_RESET = 4_000_000_000L;

    /** The answer of {@code codex app-server} to {@code account/rateLimits/read} on a Plus plan, codex-cli 0.163.0. */
    private static String answer(final double session, final long sessionResets, final double week) {
        return answer(session, sessionResets, week, WEEK_ON);
    }

    private static String answer(final double session, final long sessionResets, final double week, final long weekResets) {
        return """
                {"id":2,"result":{"ordinaryUsageAllowed":true,"rateLimits":{"limitId":"codex","limitName":null,\
                "primary":{"usedPercent":%s,"windowDurationMins":300,"resetsAt":%d},\
                "secondary":{"usedPercent":%s,"windowDurationMins":10080,"resetsAt":%d},\
                "credits":{"hasCredits":false,"unlimited":false,"balance":"0"},"planType":"plus","rateLimitReachedType":null}}}"""
                .formatted(session, sessionResets, week, weekResets);
    }

    @TempDir
    Path dir;

    /// Account primary and secondary windows use rounded shares and Madrid reset times.
    /// The fixed October timestamps distinguish the five-hour session from the weekly window.
    @Test
    void theSessionWindowAndTheWeekBecomeWindowsWordedAsClaudeCodeWordsItsResets() {
        final PlanUsage u = CodexUsageReader.answer(answer(4, FIVE_HOURS_ON, 3.4), NOW, MADRID);
        assertThat(u.provider()).isEqualTo(PlanUsage.CODEX);
        assertThat(u.problem()).isEmpty();
        assertThat(u.readAtMillis()).isEqualTo(NOW);
        assertThat(u.windows()).containsExactly(
                new PlanUsage.Window("Session", 4, "Oct 27 at 13:00"),
                new PlanUsage.Window("Week", 3, "Nov 2 at 8:00"));
    }

    /// A window reset before the read has zero usage while an unexpired companion window keeps its share.
    /// The two resets straddle `NOW` to exercise both branches in one reply.
    @Test
    void aWindowWhoseResetHasPassedIsEmpty() {
        final PlanUsage u = CodexUsageReader.answer(answer(100, NOW / 1000 - 1, 50), NOW, MADRID);
        assertThat(u.windows()).extracting(PlanUsage.Window::percent).containsExactly(0, 50);
    }

    /// Only response id 2 supplies usage; malformed, notification, and other response lines are ignored.
    /// Error and empty-limit responses instead retain their diagnostic text.
    @Test
    void otherLinesAreNotTheAnswerAndAnErrorAnswerSaysWhy() {
        assertThat(CodexUsageReader.answer("{\"id\":1,\"result\":{}}", NOW, MADRID)).isNull();
        assertThat(CodexUsageReader.answer("{\"method\":\"account/updated\",\"params\":{}}", NOW, MADRID)).isNull();
        assertThat(CodexUsageReader.answer("not json", NOW, MADRID)).isNull();
        assertThat(CodexUsageReader.answer("{\"id\":2,\"error\":{\"code\":-32600,\"message\":\"not signed in\"}}", NOW, MADRID).problem())
                .isEqualTo("not signed in");
        assertThat(CodexUsageReader.answer("{\"id\":2,\"result\":{\"rateLimits\":{}}}", NOW, MADRID).problem())
                .isEqualTo("no rate limits in Codex's answer");
    }

    /// A read finds the rate-limit answer when the `initialize` answer and a notification share one pipe chunk; the
    /// rate-limit request follows `initialize`.
    @Test
    void theReadFindsTheAnswerAmongLinesThatArriveTogether() throws Exception {
        final Path requests = dir.resolve("requests.txt");
        // the answers to initialize and the read, and a notification, in one write: one chunk on the pipe
        final Path exe = script("codex", """
                [ "$1" = app-server ] || exit 9
                while IFS= read -r line; do
                  printf '%%s\\n' "$line" >> '%s'
                  case "$line" in
                    *'"id":2'*) printf '%%s\\n%%s\\n%%s\\n' '{"id":1,"result":{}}' '{"method":"account/updated","params":{"planType":"plus"}}' '%s'; exit 0 ;;
                  esac
                done
                """.formatted(requests, answer(4, FAR_RESET, 3, FAR_RESET).replace("'", "'\\''")));
        final PlanUsage u = CodexUsageReader.read(exe.toString(), Duration.ofSeconds(10), MADRID);
        assertThat(u.problem()).isEmpty();
        assertThat(u.windows()).extracting(PlanUsage.Window::percent).containsExactly(4, 3);
        assertThat(Files.readAllLines(requests)).hasSize(3).last().asString().contains("account/rateLimits/read");
    }

    /// A model-specific top-level bucket falls back to the account bucket for session and weekly limits.
    /// The top-level bucket has no primary or secondary windows, making the fallback necessary.
    @Test
    void anAnswerInAModelBucketFallsBackToTheAccountBucket() {
        final String account = answer(80, FIVE_HOURS_ON, 3);
        final String accountLimits = account.substring(account.indexOf("\"rateLimits\":") + "\"rateLimits\":".length(), account.length() - 2);
        final String line = "{\"id\":2,\"result\":{\"rateLimits\":{\"limitId\":\"premium\",\"primary\":null,\"secondary\":null},"
                + "\"rateLimitsByLimitId\":{\"codex\":" + accountLimits + "}}}";
        assertThat(CodexUsageReader.answer(line, NOW, MADRID).windows()).extracting(PlanUsage.Window::percent).containsExactly(80, 3);
    }

    /// A server that exits before answering reports its exit code rather than waiting for the timeout.
    /// The script exits with code 7 without reading stdin.
    @Test
    void aServerThatExitsAtOnceSaysSoNotATimeout() throws Exception {
        final PlanUsage u = CodexUsageReader.read(script("dead", "exit 7\n").toString(), Duration.ofSeconds(10), MADRID);
        assertThat(u.problem()).isEqualTo("'codex app-server' exited with code 7 without answering");
    }

    /// A live server that produces no output is stopped at the supplied 300-millisecond deadline.
    /// The elapsed-time bound proves the read does not wait for the sleeping child.
    @Test
    void aReadThatHangsIsStoppedAndSaysSo() throws Exception {
        final Path exe = script("hang", "exec sleep 30\n");
        final long start = System.nanoTime();
        final PlanUsage u = CodexUsageReader.read(exe.toString(), Duration.ofMillis(300), MADRID);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        assertThat(u.windows()).isEmpty();
        assertThat(u.problem()).isEqualTo("'codex app-server' did not answer within 300 ms");
    }

    /// A server stopped past its deadline takes the processes it started with it: a child that would outlive it (here
    /// a `sleep` the script waits on) ends too.
    @Test
    void aStoppedServerTakesItsChildrenWithIt() throws Exception {
        final Path pid = dir.resolve("child.pid");
        final Path exe = script("parent", "sleep 30 &\necho $! > '" + pid + "'\nwait\n");
        final PlanUsage u = CodexUsageReader.read(exe.toString(), Duration.ofMillis(300), MADRID);
        assertThat(u.problem()).isEqualTo("'codex app-server' did not answer within 300 ms");
        final long child = Long.parseLong(Files.readString(pid).strip());
        await().atMost(Duration.ofSeconds(5)).until(() -> ProcessHandle.of(child).map(h -> !h.isAlive()).orElse(true));
    }

    /// A missing executable gives a failed read, not an exception.
    @Test
    void aMissingExecutableIsAProblemNotAnException() {
        final PlanUsage u = CodexUsageReader.read(dir.resolve("absent").toString(), Duration.ofSeconds(1), MADRID);
        assertThat(u.provider()).isEqualTo(PlanUsage.CODEX);
        assertThat(u.windows()).isEmpty();
        assertThat(u.problem()).startsWith("cannot run '");
    }

    private Path script(final String name, final String body) throws Exception {
        final Path exe = dir.resolve(name);
        Files.writeString(exe, "#!/bin/sh\n" + body);
        exe.toFile().setExecutable(true);
        return exe;
    }
}
