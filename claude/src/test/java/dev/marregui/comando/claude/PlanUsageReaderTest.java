package dev.marregui.comando.claude;

import dev.marregui.comando.core.model.PlanUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/// The plan-usage reader parses local usage output into `PlanUsage` windows.
///
/// - maps session, overall-week, and model-week lines
/// - preserves parse failures as usage problems
/// - starts the local command with isolated arguments
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

    /// Recognized session and weekly limit lines become ordered windows while surrounding prose is ignored.
    /// The fixture includes the all-model and model-specific weekly forms.
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

    /// A model-only weekly line drops its `only` suffix and rounds a fractional percentage without a reset text.
    /// The input has no session line, so the model-derived name is unambiguous.
    @Test
    void aModelOnlyWeekIsNamedAfterTheModelAndAWindowWithoutAResetKeepsItsShare() {
        PlanUsage u = PlanUsageReader.parse(answer("Current week (Sonnet only): 2.6% used\n", false), 1L, JSON);
        assertThat(u.windows()).containsExactly(new PlanUsage.Window("Sonnet", 3, ""));
    }

    /// Responses with no usable limit line report their first meaningful failure text.
    /// API-key prose, command errors, invalid JSON, and empty output cover distinct failure forms.
    @Test
    void anAnswerWithoutLimitsSaysWhy() {
        assertThat(PlanUsageReader.parse(answer("You are using an API key; plan limits do not apply\n", false), 1L, JSON).problem())
                .isEqualTo("no plan limits in /usage: You are using an API key; plan limits do not apply");
        assertThat(PlanUsageReader.parse(answer("Not logged in", true), 1L, JSON).problem()).isEqualTo("Not logged in");
        assertThat(PlanUsageReader.parse("<html>proxy error</html>", 1L, JSON).problem()).isEqualTo("unreadable answer from 'claude -p /usage'");
        assertThat(PlanUsageReader.parse("", 1L, JSON).windows()).isEmpty();
    }

    /// A local usage read enables safe mode, requests JSON, and disables session persistence.
    /// The script records each argument before returning a valid three-window reply.
    @Test
    void theReadRunsTheLocalCommandInSafeModeWithoutSavingASession() throws Exception {
        Path args = dir.resolve("args.txt");
        Path exe = script("claude", "printf '%s\\n' \"$@\" > '" + args + "'\nprintf '%s' '" + answer(MAX_PLAN, false).replace("'", "'\\''") + "'\n");
        PlanUsage u = PlanUsageReader.read(exe.toString(), RootLaunch.INHERIT, dir, JSON, Duration.ofSeconds(10));
        assertThat(u.windows()).hasSize(3);
        assertThat(Files.readAllLines(args)).containsExactly("--safe-mode", "-p", "/usage", "--no-session-persistence", "--output-format", "json");
    }

    /// A command that remains alive without output is forcibly bounded by the supplied 300-millisecond timeout.
    /// The duration assertion excludes waiting for the script's 30-second sleep.
    @Test
    void aReadThatHangsIsStoppedAndSaysSo() throws Exception {
        Path exe = script("hang", "exec sleep 30\n");
        long start = System.nanoTime();
        PlanUsage u = PlanUsageReader.read(exe.toString(), RootLaunch.INHERIT, dir, JSON, Duration.ofMillis(300));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        assertThat(u.windows()).isEmpty();
        assertThat(u.problem()).isEqualTo("'claude -p /usage' did not answer within 300 ms");
    }

    /// A missing local executable becomes a failed usage result with no thrown process exception.
    /// The temporary directory supplies a path that cannot exist.
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
