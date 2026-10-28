package dev.marregui.comando.core.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// [KitJob] and [KitJobs]: a kit's scheduled job as the manifest writes it, checked as `kit.py` checks it.
///
/// - the days and the time are written in the manifest's one form: ranges of three or more days, a zero-padded time
/// - each rule `kit.py` refuses a job by is a problem of the field it concerns
/// - arguments and the environment survive the round trip through one line and `NAME=value` lines
/// - the jobs' summary says what waits, what failed, what runs and what runs next
class KitJobTest {

    private static KitJob job(String name, List<String> days, String time, String... command) {
        return new KitJob(name, "", days, time, List.of(command), Map.of(), "", true);
    }

    /// [KitJob#when] writes a daily job as `daily`, a run of three or more days as a range and two days one by one,
    /// whatever order the days were given in, and pads the hour.
    @Test
    void theScheduleIsWrittenInTheManifestsOneForm() {
        assertThat(job("a", KitJob.WEEKDAYS, "7:15", "bin/a").when()).isEqualTo("daily 07:15");
        assertThat(job("a", List.of("FRI", "MON", "TUE", "WED", "THU"), "10:15", "bin/a").when()).isEqualTo("MON-FRI 10:15");
        assertThat(job("a", List.of("THU", "MON"), "07:30", "bin/a").when()).isEqualTo("MON,THU 07:30");
        assertThat(job("a", List.of("MON", "TUE", "WED", "FRI"), "23:59", "bin/a").when()).isEqualTo("MON-WED,FRI 23:59");
        assertThat(job("a", List.of("MON", "TUE"), "00:00", "bin/a").when()).isEqualTo("MON,TUE 00:00");
        assertThat(job("a", List.of("SUN", "MON"), "09:00", "bin/a").days()).as("week order").containsExactly("MON", "SUN");
    }

    /// [KitJob#problems] names the field of each rule broken: name form and uniqueness, description length, no day,
    /// a time out of range, no program or one outside the root, a lower-case variable, a log outside the root.
    @Test
    void eachRuleKitPyAppliesIsAProblemOfItsField() {
        KitJob good = job("kb-drift", List.of("MON"), "07:30", "bin/kb-drift");
        assertThat(KitJob.problems(List.of(good), 0)).isEmpty();
        assertThat(KitJob.problems(List.of(good, good), 1)).containsOnlyKeys(KitJob.NAME_FIELD);
        assertThat(KitJob.problems(List.of(job("KB drift", List.of("MON"), "07:30", "bin/x")), 0)).containsOnlyKeys(KitJob.NAME_FIELD);
        assertThat(KitJob.problems(List.of(job("", List.of("MON"), "07:30", "bin/x")), 0)).containsOnlyKeys(KitJob.NAME_FIELD);
        assertThat(KitJob.problems(List.of(new KitJob("a", "x".repeat(81), List.of("MON"), "07:30", List.of("bin/a"), Map.of(), "", true)), 0))
                .containsOnlyKeys(KitJob.DESCRIPTION_FIELD);
        assertThat(KitJob.problems(List.of(job("a", List.of(), "07:30", "bin/a")), 0)).containsOnlyKeys(KitJob.DAYS_FIELD);
        for (String bad : List.of("24:00", "7", "07:60", "seven", "")) {
            assertThat(KitJob.problems(List.of(job("a", List.of("MON"), bad, "bin/a")), 0)).as(bad).containsOnlyKeys(KitJob.TIME_FIELD);
        }
        for (String bad : List.of("/usr/bin/true", "../bin/a", "bin//a", "bin\\a", "C:/a")) {
            assertThat(KitJob.problems(List.of(job("a", List.of("MON"), "07:30", bad)), 0)).as(bad).containsOnlyKeys(KitJob.COMMAND_FIELD);
        }
        assertThat(KitJob.problems(List.of(job("a", List.of("MON"), "07:30")), 0)).containsOnlyKeys(KitJob.COMMAND_FIELD);
        assertThat(KitJob.problems(List.of(new KitJob("a", "", List.of("MON"), "07:30", List.of("bin/a"), Map.of("lower", "x"), "", true)), 0))
                .containsOnlyKeys(KitJob.ENV_FIELD);
        assertThat(KitJob.problems(List.of(new KitJob("a", "", List.of("MON"), "07:30", List.of("bin/a"), Map.of(), "../a.log", true)), 0))
                .containsOnlyKeys(KitJob.LOG_FIELD);
        // what kit.py takes, taken here too: 80 code points of emoji, a colon after the first part
        assertThat(KitJob.problems(List.of(new KitJob("a", "😀".repeat(80), List.of("MON"), "07:30", List.of("bin/a:b"), Map.of(), "logs/a:b.log", true)), 0))
                .isEmpty();
    }

    /// [KitJob#line] quotes what [KitJob#words] would split or lose, and the two read each other back; the environment
    /// does the same through `NAME=value` lines, a blank line skipped.
    @Test
    void argumentsAndTheEnvironmentRoundTrip() {
        List<String> args = List.of("--trigger", "{scheduler}", "a b", "it's", "say \"hi\"", "", "back\\slash");
        assertThat(KitJob.words(KitJob.line(args))).isEqualTo(args);
        assertThat(KitJob.line(List.of("--days", "7"))).isEqualTo("--days 7");
        assertThat(KitJob.words("  --a  'b c'  \"d\\\"e\" ")).containsExactly("--a", "b c", "d\"e");
        KitJob j = new KitJob("a", "", List.of("MON"), "07:30", List.of("bin/a"), Map.of("B", "2", "A", "x=y"), "", true);
        assertThat(j.envLines()).isEqualTo("A=x=y\nB=2");
        assertThat(KitJob.envOf(j.envLines() + "\n\n")).isEqualTo(j.env());
        assertThat(KitJob.envOf(" A = x \n")).as("the value as written").isEqualTo(Map.of("A", " x "));
    }

    /// [KitJobs] says a job waits when it runs on its schedule but its files are missing, out of date or not loaded,
    /// never when paused; names failed and running jobs; and picks the enabled job that runs next.
    @Test
    void theJobsSummarySaysWhatWaitsFailsRunsAndComesNext() {
        KitJob a = job("a", List.of("MON"), "07:30", "bin/a");
        KitJob b = new KitJob("b", "", List.of("TUE"), "08:00", List.of("bin/b"), Map.of(), "", false);
        KitJobs.Entry ok = new KitJobs.Entry(a, true, true, true, false, "2026-10-26T07:30+01:00", 0, "2026-11-02T07:30+01:00", "/l/a.log");
        KitJobs.Entry paused = new KitJobs.Entry(b, false, false, false, false, "", null, "", "/l/b.log");
        assertThat(new KitJobs(true, List.of(ok, paused), "").pending()).as("a paused job waits for nothing").isFalse();
        assertThat(new KitJobs.Entry(a, true, true, false, false, "", null, "", "").pending()).as("not loaded").isTrue();
        assertThat(new KitJobs.Entry(a, true, false, true, false, "", null, "", "").pending()).as("out of date").isTrue();
        assertThat(new KitJobs.Entry(a, true, true, null, false, "", null, "", "").pending()).as("unknown is not pending").isFalse();
        KitJobs.Entry failedRunning = new KitJobs.Entry(job("c", List.of("WED"), "06:00", "bin/c"), true, true, true, true,
                "2026-10-28T06:00+01:00", 2, "2026-10-29T06:00+01:00", "/l/c.log");
        KitJobs all = new KitJobs(true, List.of(ok, paused, failedRunning), "");
        assertThat(all.failed()).containsExactly("c");
        assertThat(all.running()).containsExactly("c");
        assertThat(all.paused()).containsExactly("b");
        assertThat(all.nextUp()).map(e -> e.job().name()).contains("c");
        assertThat(all.definitions()).containsExactly(a, b, failedRunning.job());
        assertThat(new KitJobs.Entry(b, true, false, null, false, "", null, "", "").pending()).as("paused, still installed").isTrue();
        assertThat(new KitJobs.Entry(b, false, false, true, false, "", null, "", "").pending()).as("paused, still loaded").isTrue();
        assertThat(new KitJobs.Entry(b, false, false, null, false, "", 3, "", "").failed()).as("a paused job fails nothing").isFalse();
    }
}
