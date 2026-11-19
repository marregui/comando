package dev.marregui.comando.frontear.sim;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/// Test helper: runs a test's schedules, one [SimCluster] each, and fails with every schedule that showed a
/// violation: its seeds, the line that replays it alone, its events and its violations. A test runs
/// `COMANDO_SIM_RUNS` schedules when that is set, else its default, ten times as many under `COMANDO_SOAK=true`; the
/// line and ring tests share theirs, half to each, at least one each.
final class Soak {

    /// The schedules of a default run, half for each of the line and the ring: one schedule takes 0.1 to 3 s on an
    /// Apple M2 Max, one whose link waits the longest resend backoff about 30 s more, so a run stays under a minute.
    private static final int RUNS = 20;
    /// How many times its default a test runs under `COMANDO_SOAK=true`: about ten minutes for the line and ring.
    private static final int SOAK = 10;

    private Soak() {
    }

    /// Runs `topology`'s share of the line's and ring's schedules under `dir`, `test` naming the test class that
    /// replays them.
    static void run(final Schedule.Topology topology, final String test, final Path dir) throws Exception {
        final int runs = runs(RUNS);
        play(test, dir, Math.max(1, topology == Schedule.Topology.RING ? (runs + 1) / 2 : runs / 2),
                r -> Schedule.generate(r, topology));
    }

    /// Runs schedules `draw` makes under `dir`, `defaultRuns` of them unless the environment sizes the run, `test`
    /// naming the test class that replays them.
    static void run(final String test, final Path dir, final int defaultRuns, final Function<SimRandom, Schedule> draw)
            throws Exception {
        play(test, dir, Math.max(1, runs(defaultRuns)), draw);
    }

    private static void play(final String test, final Path dir, final int schedules,
            final Function<SimRandom, Schedule> draw) throws Exception {
        final SimRandom run = SimRandom.of(test);
        final List<String> failures = new ArrayList<>();
        final long startNanos = System.nanoTime();
        for (int k = 0; k < schedules; k++) {
            final SimRandom r = run.schedule(k);
            final Schedule s = draw.apply(r);
            final long scheduleNanos = System.nanoTime();
            final List<String> violations = new ArrayList<>();
            List<String> events = List.of();
            String summary = "";
            final SimCluster cluster = new SimCluster(dir.resolve("s" + k), s);
            try {
                violations.addAll(cluster.run());
            } catch (final Exception e) {
                violations.add("failed: " + e);
            } finally {
                try {
                    cluster.close();
                } catch (final RuntimeException e) {
                    violations.add("close failed: " + e);
                }
                events = cluster.events();
                summary = "healMillis=" + cluster.healMillis() + " published " + cluster.published() + " reordered="
                        + cluster.reordered();
            }
            System.out.println(test + " schedule " + k + " seeds " + r.seeds() + " steps=" + s.steps().size()
                    + " topology=" + s.topology().name() + " check=" + s.check() + " hopLimit=" + s.hopLimit()
                    + " millis="
                    + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - scheduleNanos) + ' ' + summary
                    + " violations=" + violations.size());
            if (!violations.isEmpty()) {
                final String report = test + " schedule " + k + " seeds " + r.seeds() + "\n  replay: " + r.replay()
                        + " with --tests '*." + test + "'\n  events:\n    " + String.join("\n    ", events)
                        + "\n  violations:\n    " + String.join("\n    ", violations);
                System.out.println(report);
                failures.add(report);
            }
        }
        System.out.println(test + " ran " + schedules + " schedules in "
                + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos) + " ms, " + failures.size()
                + " with violations");
        assertThat(failures).as("schedules with violations").isEmpty();
    }

    /// `COMANDO_SIM_RUNS` when set, else `defaultRuns`, times [#SOAK] under `COMANDO_SOAK=true`.
    private static int runs(final int defaultRuns) {
        final String set = System.getenv("COMANDO_SIM_RUNS");
        if (set != null && !set.isBlank()) {
            return Integer.parseInt(set.strip());
        }
        return Boolean.parseBoolean(System.getenv("COMANDO_SOAK")) ? defaultRuns * SOAK : defaultRuns;
    }
}
