package dev.marregui.comando.claude;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.marregui.comando.core.model.PlanUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class PlanUsageReaderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The {@code result} text of {@code claude -p /usage} on a Max plan, Claude Code 2.1.299, cut after the limits. */
    private static final String MAX_PLAN = """
            You are currently using your subscription to power your Claude Code usage

            Current session: 5% used · resets Oct 14 at 7:19pm (Europe/Madrid)
            Current week (all models): 43% used · resets Oct 15 at 1:59pm (Europe/Madrid)
            Current week (Fable): 17% used · resets Oct 15 at 1:59pm (Europe/Madrid)

            What's contributing to your limits usage?
            Last 24h · 2421 requests · 12 sessions
              87% of your usage was at >150k context
            """;

    @TempDir
    Path dir;

    private static String answer(String result, boolean error) {
        ObjectNode n = JSON.createObjectNode();
        n.put("type", "result");
        n.put("subtype", "success");
        n.put("is_error", error);
        n.put("local_command", "usage");
        n.put("result", result);
        return n.toString();
    }

    @Test
    void eachLimitLineBecomesAWindowAndTheRestIsIgnored() {
        PlanUsage u = PlanUsageReader.parse(answer(MAX_PLAN, false), 7L, JSON);
        assertThat(u.problem()).isEmpty();
        assertThat(u.readAtMillis()).isEqualTo(7L);
        assertThat(u.windows()).containsExactly(
                new PlanUsage.Window("Session", 5, "Oct 14 at 7:19pm (Europe/Madrid)"),
                new PlanUsage.Window("Week", 43, "Oct 15 at 1:59pm (Europe/Madrid)"),
                new PlanUsage.Window("Fable", 17, "Oct 15 at 1:59pm (Europe/Madrid)"));
    }

    @Test
    void aModelOnlyWeekIsNamedAfterTheModelAndAWindowWithoutAResetKeepsItsShare() {
        PlanUsage u = PlanUsageReader.parse(answer("Current week (Sonnet only): 2.6% used\n", false), 1L, JSON);
        assertThat(u.windows()).containsExactly(new PlanUsage.Window("Sonnet", 3, ""));
    }

    @Test
    void anAnswerWithoutLimitsSaysWhy() {
        assertThat(PlanUsageReader.parse(answer("You are using an API key; plan limits do not apply\n", false), 1L, JSON).problem())
                .isEqualTo("no plan limits in /usage: You are using an API key; plan limits do not apply");
        assertThat(PlanUsageReader.parse(answer("Not logged in", true), 1L, JSON).problem()).isEqualTo("Not logged in");
        assertThat(PlanUsageReader.parse("<html>proxy error</html>", 1L, JSON).problem()).isEqualTo("unreadable answer from 'claude -p /usage'");
        assertThat(PlanUsageReader.parse("", 1L, JSON).windows()).isEmpty();
    }

    @Test
    void theReadRunsTheLocalCommandInSafeModeWithoutSavingASession() throws Exception {
        Path args = dir.resolve("args.txt");
        Path exe = script("claude", "printf '%s\\n' \"$@\" > '" + args + "'\nprintf '%s' '" + answer(MAX_PLAN, false).replace("'", "'\\''") + "'\n");
        PlanUsage u = PlanUsageReader.read(exe.toString(), RootLaunch.INHERIT, dir, JSON, Duration.ofSeconds(10));
        assertThat(u.windows()).hasSize(3);
        assertThat(Files.readAllLines(args)).containsExactly("--safe-mode", "-p", "/usage", "--no-session-persistence", "--output-format", "json");
    }

    @Test
    void aReadThatHangsIsStoppedAndSaysSo() throws Exception {
        Path exe = script("hang", "exec sleep 30\n");
        long start = System.nanoTime();
        PlanUsage u = PlanUsageReader.read(exe.toString(), RootLaunch.INHERIT, dir, JSON, Duration.ofMillis(300));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        assertThat(u.windows()).isEmpty();
        assertThat(u.problem()).isEqualTo("'claude -p /usage' did not answer within 300 ms");
    }

    @Test
    void aMissingExecutableIsAProblemNotAnException() {
        PlanUsage u = PlanUsageReader.read(dir.resolve("absent").toString(), RootLaunch.INHERIT, dir, JSON, Duration.ofSeconds(1));
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
