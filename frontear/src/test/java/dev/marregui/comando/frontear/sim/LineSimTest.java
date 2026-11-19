package dev.marregui.comando.frontear.sim;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Three frontear nodes in a line, a link each way between a and b and between b and c, under seeded schedules of
/// faults, as [RingSimTest]; here messages also come back to the node that published them, so the hop limit is what
/// ends their travel. Seeds in the output; replay one schedule with `COMANDO_SIM_SEED0`, `COMANDO_SIM_SEED1` and
/// `COMANDO_SIM_RUNS=1`.
class LineSimTest {

    /// After every schedule the line keeps the same contract as the ring: nothing acknowledged and covered is lost,
    /// order holds per link without a counted refusal, nothing goes past the hop limit, every bound holds.
    @Test
    void theLineKeepsTheContractUnderFaults(@TempDir final Path dir) throws Exception {
        Soak.run(Schedule.Topology.LINE, "LineSimTest", dir);
    }
}
