package dev.marregui.comando.cdb.exec;

/// Test helper: a xorshift128+ generator of two seeds, read from the system properties `<prefix>.seed0` and
/// `<prefix>.seed1` when set, else drawn from the clock, and printed so a failing run can be replayed. One instance per
/// thread.
final class TwoSeedRandom {

    private long s0;
    private long s1;

    private TwoSeedRandom(final long seed0, final long seed1) {
        // splitmix64 of each seed, so two zero or close seeds still start the generator in a busy state
        s0 = mix(seed0);
        s1 = mix(seed1 ^ 0x9E3779B97F4A7C15L);
    }

    /// The generator of `test`, its seeds named `<prefix>.seed0` and `<prefix>.seed1`, printed with the test's name.
    static TwoSeedRandom of(final String prefix, final String test) {
        final long seed0 = Long.getLong(prefix + ".seed0", System.nanoTime());
        final long seed1 = Long.getLong(prefix + ".seed1", System.currentTimeMillis() * 31L + System.nanoTime());
        System.out.println(test + " seeds [" + prefix + ".seed0=" + seed0 + ", " + prefix + ".seed1=" + seed1 + ']');
        return new TwoSeedRandom(seed0, seed1);
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

    /// A value in `[0, 1)`.
    double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }

    boolean nextBoolean() {
        return nextLong() < 0;
    }

    /// True in `percent` percent of the calls.
    boolean percent(final int percent) {
        return nextInt(100) < percent;
    }

    private static long mix(final long seed) {
        long z = seed + 0x9E3779B97F4A7C15L;
        z = (z ^ z >>> 30) * 0xBF58476D1CE4E5B9L;
        z = (z ^ z >>> 27) * 0x94D049BB133111EBL;
        return z ^ z >>> 31;
    }
}
