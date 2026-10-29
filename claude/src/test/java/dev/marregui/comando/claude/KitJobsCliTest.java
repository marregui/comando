package dev.marregui.comando.claude;

import dev.marregui.comando.core.model.KitJob;
import dev.marregui.comando.core.model.KitJobs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// [KitJobsCli]: the kit's jobs through the root's `install/kit.py`, here a stand-in that records what it was asked.
///
/// - `jobs --json` is read into [KitJobs], a daily job's empty day list as all seven days, an unknown load as null
/// - the list handed to `jobs set` is the manifest's form, defaults left out, and reaches the program's standard input
/// - `job run` names the job, and every call names the root
/// - a missing `kit.py`, a failing one and one that prints no list each give a [KitJobs] with the reason
@DisabledOnOs(OS.WINDOWS)
class KitJobsCliTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    static final String REPORT = """
            {"root": "/r", "platform": "macos", "scheduler": "launchd", "jobs": [
              {"name": "transcript-sweep", "description": "Old transcripts to the Trash", "schedule": {"daily": "07:15"},
               "days": [], "time": "07:15", "command": ["bin/transcript-sweep"], "env": {}, "log": ".transcript-sweep/log",
               "logPath": "/r/.transcript-sweep/log", "enabled": true, "installed": true, "current": true, "loaded": true,
               "running": false, "pid": null, "last": {"start": "2026-10-28T19:49:59+01:00", "end": "2026-10-28T19:49:59+01:00", "exit": 0},
               "next": "2026-10-29T07:15+01:00", "files": []},
              {"name": "daily-brief", "description": "", "schedule": {"weekly": "MON-FRI 10:15"},
               "days": ["MON", "TUE", "WED", "THU", "FRI"], "time": "10:15", "command": ["bin/daily-brief", "--x"],
               "env": {"T": "{scheduler}"}, "log": "", "logPath": "/r/.kit-jobs/daily-brief.log", "enabled": false,
               "installed": false, "current": false, "loaded": null, "running": false, "pid": null, "last": null,
               "next": null, "files": []}
            ]}
            """;

    /** A stand-in for {@code kit.py}: writes its arguments and standard input beside it, prints {@code out}, exits {@code code}. */
    private static Path fake(Path root, String out, int code) throws IOException {
        Path install = Files.createDirectories(root.resolve("install"));
        Files.writeString(install.resolve("report.json"), out);
        Path script = install.resolve("kit.py");
        Files.writeString(script, """
                import sys, pathlib
                here = pathlib.Path(__file__).parent
                (here / "argv.txt").write_text("\\n".join(sys.argv[1:]))
                (here / "stdin.txt").write_text(sys.stdin.read())
                sys.stdout.write((here / "report.json").read_text())
                sys.stderr.write("said on stderr\\n" if %d else "")
                sys.exit(%d)
                """.formatted(code, code));
        return install;
    }

    /// [KitJobsCli#read] asks `jobs --json --root <root>` and maps every field: days, time, command, env, log, the
    /// scheduler's facts, the last run and the next; a daily job's empty day list is all seven days.
    @Test
    void theReportIsReadIntoTheJobs(@TempDir Path root) throws IOException {
        Path install = fake(root, REPORT, 0);
        KitJobs jobs = new KitJobsCli(root).read();
        assertThat(Files.readString(install.resolve("argv.txt")).lines()).containsExactly("jobs", "--json", "--root", root.toString());
        assertThat(jobs.read()).isTrue();
        assertThat(jobs.error()).isEmpty();
        KitJobs.Entry sweep = jobs.jobs().getFirst();
        assertThat(sweep.job().daily()).isTrue();
        assertThat(sweep.job().when()).isEqualTo("daily 07:15");
        assertThat(sweep.job().log()).isEqualTo(".transcript-sweep/log");
        assertThat(sweep.installed() && sweep.current() && Boolean.TRUE.equals(sweep.loaded())).isTrue();
        assertThat(sweep.lastExit()).isZero();
        assertThat(sweep.next()).isEqualTo("2026-10-29T07:15+01:00");
        KitJobs.Entry brief = jobs.jobs().get(1);
        assertThat(brief.job().when()).isEqualTo("MON-FRI 10:15");
        assertThat(brief.job().command()).containsExactly("bin/daily-brief", "--x");
        assertThat(brief.job().env()).isEqualTo(Map.of("T", "{scheduler}"));
        assertThat(brief.job().enabled()).isFalse();
        assertThat(brief.loaded()).as("not known").isNull();
        assertThat(brief.lastExit()).isNull();
        assertThat(brief.next()).isEmpty();
        assertThat(brief.logPath()).isEqualTo("/r/.kit-jobs/daily-brief.log");
    }

    /// [KitJobsCli#save] hands `jobs set` the manifest's form on standard input: the schedule as `daily` or `weekly`,
    /// the time padded, an empty description, environment and log left out, `enabled` only when false; the answer's exit
    /// code and words come back.
    @Test
    void theListIsHandedOverInTheManifestsForm(@TempDir Path root) throws IOException {
        Path install = fake(root, "manifest: 2 job(s)\n", 1);
        List<KitJob> jobs = List.of(
                new KitJob("a", "", KitJob.WEEKDAYS, "7:15", List.of("bin/a"), Map.of(), "", true),
                new KitJob("b", "Brief", List.of("MON", "TUE", "WED", "THU", "FRI"), "10:15", List.of("bin/b", "--x"),
                        Map.of("T", "{scheduler}"), "logs/b.log", false));
        KitJobsCli.Result r = new KitJobsCli(root).save(jobs);
        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.text()).contains("manifest: 2 job(s)").contains("said on stderr");
        assertThat(Files.readString(install.resolve("argv.txt")).lines()).containsExactly("jobs", "set", "--root", root.toString());
        JsonNode sent = JSON.readTree(Files.readString(install.resolve("stdin.txt")));
        assertThat(sent.get(0).toString()).isEqualTo("{\"name\":\"a\",\"schedule\":{\"daily\":\"07:15\"},\"command\":[\"bin/a\"]}");
        assertThat(sent.get(1).toString()).isEqualTo("{\"name\":\"b\",\"description\":\"Brief\",\"schedule\":{\"weekly\":\"MON-FRI 10:15\"},"
                + "\"command\":[\"bin/b\",\"--x\"],\"env\":{\"T\":\"{scheduler}\"},\"log\":\"logs/b.log\",\"enabled\":false}");
    }

    /// [KitJobsCli#run] asks `job run <name>` and returns its answer.
    @Test
    void runNamesTheJob(@TempDir Path root) throws IOException {
        Path install = fake(root, "job a: started (launchd)\n", 0);
        KitJobsCli.Result r = new KitJobsCli(root).run("a");
        assertThat(r.exit()).isZero();
        assertThat(r.text()).isEqualTo("job a: started (launchd)");
        assertThat(Files.readString(install.resolve("argv.txt")).lines()).containsExactly("job", "run", "a", "--root", root.toString());
    }

    /// A root with no `install/kit.py`, a `kit.py` that fails and one that prints no list each read as no jobs with
    /// the reason: the row then says they cannot be read.
    @Test
    void whatCannotBeReadSaysWhy(@TempDir Path root) throws IOException {
        assertThat(new KitJobsCli(root).read().error()).contains("no install/kit.py");
        fake(root, "", 2);
        assertThat(new KitJobsCli(root).read().error()).isEqualTo("said on stderr");
        fake(root, "{\"nothing\": 1}", 0);
        assertThat(new KitJobsCli(root).read().error()).contains("gave no jobs");
        assertThat(new KitJobsCli(root, List.of("no-such-python-here")).read().error()).isNotEmpty();
    }
}
