package dev.marregui.comando.frontear.sim;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Four frontear nodes in a ring, a to b to c to d to a, with a shortcut from a to c, every link with OUT and IN
/// filters, under seeded schedules of faults as [ChainSimTest]. A message reaches c over two walks of different
/// lengths and comes back to the node that published it, so the hop limit and No Local are what end its travel.
/// Seeds in the output; replay one schedule with `COMANDO_SIM_SEED0`, `COMANDO_SIM_SEED1` and `COMANDO_SIM_RUNS=1`.
class ShortcutSimTest {

    /// The schedules of a default run, as [ChainSimTest]'s.
    private static final int RUNS = 6;

    /// After every schedule the ring with a shortcut keeps the contract [ChainSimTest] checks, on each walk on its
    /// own: the shortcut's copy and the long way's copy are owed apart, and neither orders the other.
    @Test
    void theRingWithAShortcutKeepsTheContractOnEveryWalkUnderFaults(@TempDir final Path dir) throws Exception {
        Soak.run("ShortcutSimTest", dir, RUNS, r -> Schedule.paths(r, Schedule.Topology.SHORTCUT));
    }
}
