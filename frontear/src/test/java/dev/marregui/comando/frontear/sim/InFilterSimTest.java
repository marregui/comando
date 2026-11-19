package dev.marregui.comando.frontear.sim;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Frontear nodes in a chain, a ring with a shortcut or a line, every link with OUT and IN filters drawn per
/// schedule, under seeded schedules without faults, once a probe through each IN filter showed it in place. Without
/// faults every walk delivers, IN sides included, so each copy is counted. Seeds in the output; replay one schedule
/// with `COMANDO_SIM_SEED0`, `COMANDO_SIM_SEED1` and `COMANDO_SIM_RUNS=1`.
class InFilterSimTest {

    /// The schedules of a default run: one takes 0.5 to 2 s on an Apple M2 Max, so the sim tests together stay under
    /// a minute.
    private static final int RUNS = 10;
    private static final List<Schedule.Topology> TOPOLOGIES = List.of(Schedule.Topology.CHAIN,
            Schedule.Topology.SHORTCUT, Schedule.Topology.LINE);

    /// After every schedule: each message arrived at every matching subscriber once per walk its links' OUT and IN
    /// filters admit, with the walk's length as its hop count, and never otherwise: not through a filter that does
    /// not match, not back over the link that carried it, not past the hop limit; over each group of walks, first
    /// arrivals keep publish order.
    @Test
    void aMessageArrivesOncePerWalkItsFiltersAdmit(@TempDir final Path dir) throws Exception {
        Soak.run("InFilterSimTest", dir, RUNS, r -> Schedule.exact(r, TOPOLOGIES.get(r.nextInt(TOPOLOGIES.size()))));
    }
}
