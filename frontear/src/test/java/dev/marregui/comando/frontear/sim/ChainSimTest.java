package dev.marregui.comando.frontear.sim;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Four frontear nodes in a chain, a to b to c to d, every link with OUT and IN filters, under seeded schedules of
/// faults: restarts on the same data folder, cut connections, bursts past a link's queue. Messages travel up to four
/// hops, so a node in the middle receives, logs and forwards what it did not publish. Seeds in the output; replay one
/// schedule with `COMANDO_SIM_SEED0`, `COMANDO_SIM_SEED1` and `COMANDO_SIM_RUNS=1`.
class ChainSimTest {

    /// The schedules of a default run: one takes 1 to 2 s on an Apple M2 Max, one whose link waits the longest
    /// resend backoff about 30 s more, so the sim tests together stay under a minute.
    private static final int RUNS = 6;

    /// After every schedule: each message acknowledged 0x00 reached every matching subscriber at the end of each
    /// walk of OUT sides its filters admit, once at least per hop count; no copy arrived with a hop count no walk
    /// gives it, nor past the hop limit; over each walk of OUT sides without a counted refusal, first arrivals keep
    /// publish order; the gateways' and links' readings stayed within their bounds after every step.
    @Test
    void theChainKeepsTheContractOnEveryHopUnderFaults(@TempDir final Path dir) throws Exception {
        Soak.run("ChainSimTest", dir, RUNS, r -> Schedule.paths(r, Schedule.Topology.CHAIN));
    }
}
