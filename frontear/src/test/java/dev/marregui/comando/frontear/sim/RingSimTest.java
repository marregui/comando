package dev.marregui.comando.frontear.sim;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Three frontear nodes in a ring, a to b to c to a, under seeded schedules of faults: restarts on the same data
/// folder, cut connections, links removed and put back, filters replaced while messages wait, bursts past a link's
/// queue. Seeds in the output; replay one schedule with `COMANDO_SIM_SEED0`, `COMANDO_SIM_SEED1` and
/// `COMANDO_SIM_RUNS=1` (or `-Dfrontear.sim.seed0/1` where the JVM's properties can be set).
class RingSimTest {

    /// After every schedule: each message acknowledged 0x00 for a topic a link covered arrived at every subscriber of
    /// the far node whose filters match it, a filter change notwithstanding; over each link without a counted
    /// refusal, first arrivals keep publish order and none overtakes an earlier one; no message made more hops than
    /// the limit or came from nowhere; the brokers' and links' readings stayed within their bounds after every step.
    @Test
    void theRingKeepsTheContractUnderFaults(@TempDir final Path dir) throws Exception {
        Soak.run(Schedule.Topology.RING, "RingSimTest", dir);
    }
}
