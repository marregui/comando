package dev.marregui.comando.frontear.sim;

/// Test helper: a xorshift128+ generator of two seeds, cdb's two-seed generator for the simulation. A run's seeds come
/// from the system properties `frontear.sim.seed0` and `frontear.sim.seed1`, else from the environment variables
/// `COMANDO_SIM_SEED0` and `COMANDO_SIM_SEED1` (the build passes the environment to the test JVM, not `-D`), else
/// from the clock, and are printed so a failing run can be replayed. One instance per thread.
final class SimRandom {

    /// The golden-ratio increment: schedule `k` of a run starts from the run's seeds plus `k` times it.
    static final long STRIDE = 0x9E3779B97F4A7C15L;

    private final long seed0;
    private final long seed1;
    private long s0;
    private long s1;

    private SimRandom(final long seed0, final long seed1) {
        this.seed0 = seed0;
        this.seed1 = seed1;
        // splitmix64 of each seed, so two zero or close seeds still start the generator in a busy state
        s0 = mix(seed0);
        s1 = mix(seed1 ^ STRIDE);
    }

    /// The run's generator for `test`, its seeds printed with the test's name.
    static SimRandom of(final String test) {
        final long seed0 = seed("frontear.sim.seed0", "COMANDO_SIM_SEED0", System.nanoTime());
        final long seed1 = seed("frontear.sim.seed1", "COMANDO_SIM_SEED1",
                System.currentTimeMillis() * 31L + System.nanoTime());
        final SimRandom r = new SimRandom(seed0, seed1);
        System.out.println(test + " seeds " + r.seeds());
        return r;
    }

    /// The generator of schedule `k` of this run: its own seeds, so it replays alone as a run of one schedule.
    SimRandom schedule(final int k) {
        return new SimRandom(seed0 + k * STRIDE, seed1 + k * STRIDE);
    }

    /// The seeds as the properties that replay them.
    String seeds() {
        return "[frontear.sim.seed0=" + seed0 + ", frontear.sim.seed1=" + seed1 + ']';
    }

    /// The environment that replays this generator's schedule alone.
    String replay() {
        return "COMANDO_SIM_SEED0=" + seed0 + " COMANDO_SIM_SEED1=" + seed1 + " COMANDO_SIM_RUNS=1";
    }

    long nextLong() {
        long a = s0;
        final long b = s1;
        s0 = b;
        a ^= a << 23;
        s1 = a ^ b ^ a >>> 17 ^ b >>> 26;
        return s1 + b;
    }

    /// A value in `[0, bound)`, `bound` positive.
    int nextInt(final int bound) {
        return (int) ((nextLong() >>> 1) % bound);
    }

    /// A value in `[from, to]`.
    int between(final int from, final int to) {
        return from + nextInt(to - from + 1);
    }

    /// True in `percent` percent of the calls.
    boolean percent(final int percent) {
        return nextInt(100) < percent;
    }

    private static long seed(final String property, final String variable, final long otherwise) {
        final String set = System.getProperty(property, System.getenv(variable));
        return set == null || set.isBlank() ? otherwise : Long.parseLong(set.strip());
    }

    private static long mix(final long seed) {
        long z = seed + STRIDE;
        z = (z ^ z >>> 30) * 0xBF58476D1CE4E5B9L;
        z = (z ^ z >>> 27) * 0x94D049BB133111EBL;
        return z ^ z >>> 31;
    }
}
