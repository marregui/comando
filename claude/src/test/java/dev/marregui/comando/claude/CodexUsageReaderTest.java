package dev.marregui.comando.claude;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.marregui.comando.core.model.PlanUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class CodexUsageReaderTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    /** 2026-10-27 07:00 UTC. */
    private static final long NOW = 1_793_084_400_000L;
    private static final long FIVE_HOURS_ON = NOW / 1000 + 5 * 3600;
    private static final long WEEK_ON = NOW / 1000 + 6 * 86_400;

    /** The answer of {@code codex app-server} to {@code account/rateLimits/read} on a Plus plan, codex-cli 0.163.0, cut. */
    private static String answer(double session, long sessionResets, double week) {
        return """
                {"id":2,"result":{"ordinaryUsageAllowed":true,"rateLimits":{"limitId":"codex","limitName":null,\
                "primary":{"usedPercent":%s,"windowDurationMins":300,"resetsAt":%d},\
                "secondary":{"usedPercent":%s,"windowDurationMins":10080,"resetsAt":%d},\
                "credits":{"hasCredits":false,"unlimited":false,"balance":"0"},"planType":"plus","rateLimitReachedType":null}}}"""
                .formatted(session, sessionResets, week, WEEK_ON);
    }

    @TempDir
    Path dir;

    @Test
    void theSessionWindowAndTheWeekBecomeWindowsWordedAsClaudeCodeWordsItsResets() {
        PlanUsage u = CodexUsageReader.answer(answer(4, FIVE_HOURS_ON, 3.4), NOW, JSON, MADRID);
        assertThat(u.provider()).isEqualTo(PlanUsage.CODEX);
        assertThat(u.problem()).isEmpty();
        assertThat(u.readAtMillis()).isEqualTo(NOW);
        assertThat(u.windows()).containsExactly(
                new PlanUsage.Window("Session", 4, "Oct 27 at 13:00"),
                new PlanUsage.Window("Week", 3, "Nov 2 at 8:00"));
    }

    @Test
    void aWindowWhoseResetHasPassedIsEmpty() {
        PlanUsage u = CodexUsageReader.answer(answer(100, NOW / 1000 - 1, 50), NOW, JSON, MADRID);
        assertThat(u.windows()).extracting(PlanUsage.Window::percent).containsExactly(0, 50);
    }

    @Test
    void otherLinesAreNotTheAnswerAndAnErrorAnswerSaysWhy() {
        assertThat(CodexUsageReader.answer("{\"id\":1,\"result\":{}}", NOW, JSON, MADRID)).isNull();
        assertThat(CodexUsageReader.answer("{\"method\":\"account/updated\",\"params\":{}}", NOW, JSON, MADRID)).isNull();
        assertThat(CodexUsageReader.answer("not json", NOW, JSON, MADRID)).isNull();
        assertThat(CodexUsageReader.answer("{\"id\":2,\"error\":{\"code\":-32600,\"message\":\"not signed in\"}}", NOW, JSON, MADRID).problem())
                .isEqualTo("not signed in");
        assertThat(CodexUsageReader.answer("{\"id\":2,\"result\":{\"rateLimits\":{}}}", NOW, JSON, MADRID).problem())
                .isEqualTo("no rate limits in Codex's answer");
    }

    @Test
    void theReadFindsTheAnswerAmongLinesThatArriveTogether() throws Exception {
        Path requests = dir.resolve("requests.txt");
        // the answers to initialize and the read, and a notification, in one write: one chunk on the pipe
        Path exe = script("codex", """
                [ "$1" = app-server ] || exit 9
                while IFS= read -r line; do
                  printf '%%s\\n' "$line" >> '%s'
                  case "$line" in
                    *'"id":2'*) printf '%%s\\n%%s\\n%%s\\n' '{"id":1,"result":{}}' '{"method":"account/updated","params":{"planType":"plus"}}' '%s'; exit 0 ;;
                  esac
                done
                """.formatted(requests, answer(4, 4_000_000_000L, 3).replace("'", "'\\''")));
        PlanUsage u = CodexUsageReader.read(exe.toString(), JSON, Duration.ofSeconds(10), MADRID);
        assertThat(u.problem()).isEmpty();
        assertThat(u.windows()).extracting(PlanUsage.Window::percent).containsExactly(4, 3);
        assertThat(Files.readAllLines(requests)).hasSize(3).last().asString().contains("account/rateLimits/read");
    }

    @Test
    void anAnswerInAModelBucketFallsBackToTheAccountBucket() {
        String account = answer(80, FIVE_HOURS_ON, 3);
        String accountLimits = account.substring(account.indexOf("\"rateLimits\":") + "\"rateLimits\":".length(), account.length() - 2);
        String line = "{\"id\":2,\"result\":{\"rateLimits\":{\"limitId\":\"premium\",\"primary\":null,\"secondary\":null},"
                + "\"rateLimitsByLimitId\":{\"codex\":" + accountLimits + "}}}";
        assertThat(CodexUsageReader.answer(line, NOW, JSON, MADRID).windows()).extracting(PlanUsage.Window::percent).containsExactly(80, 3);
    }

    @Test
    void aServerThatExitsAtOnceSaysSoNotATimeout() throws Exception {
        // review F4, 2026-10-27: an exit was reported as "did not answer within 10000 ms"
        PlanUsage u = CodexUsageReader.read(script("dead", "exit 7\n").toString(), JSON, Duration.ofSeconds(10), MADRID);
        assertThat(u.problem()).isEqualTo("'codex app-server' exited with code 7 without answering");
    }

    @Test
    void aReadThatHangsIsStoppedAndSaysSo() throws Exception {
        Path exe = script("hang", "exec sleep 30\n");
        long start = System.nanoTime();
        PlanUsage u = CodexUsageReader.read(exe.toString(), JSON, Duration.ofMillis(300), MADRID);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        assertThat(u.windows()).isEmpty();
        assertThat(u.problem()).isEqualTo("'codex app-server' did not answer within 300 ms");
    }

    @Test
    void aMissingExecutableIsAProblemNotAnException() {
        PlanUsage u = CodexUsageReader.read(dir.resolve("absent").toString(), JSON, Duration.ofSeconds(1), MADRID);
        assertThat(u.provider()).isEqualTo(PlanUsage.CODEX);
        assertThat(u.windows()).isEmpty();
        assertThat(u.problem()).startsWith("cannot run '");
    }

    private Path script(String name, String body) throws Exception {
        Path exe = dir.resolve(name);
        Files.writeString(exe, "#!/bin/sh\n" + body);
        exe.toFile().setExecutable(true);
        return exe;
    }
}
