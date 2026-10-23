package com.fulcrologic.jxpilot.common.math;

/**
 * Small deterministic RNG (xorshift32 with a SplitMix32-scrambled seed). Identical sequences on every
 * JVM, so the server and clients can expand the same {@link DebrisBurst} from a transmitted seed.
 * Allocation free; reseed with {@link #seed(int)}.
 */
public final class Rng {
    private int state;

    public Rng(int seed) {
        seed(seed);
    }

    /**
     * Restart the sequence.
     * <p>
     * BEHAVIOR: equal seeds give equal sequences; a zero state is never used (xorshift would stick at 0).
     */
    public void seed(int seed) {
        int z = seed + 0x9E3779B9;
        z = (z ^ (z >>> 16)) * 0x85EBCA6B;
        z = (z ^ (z >>> 13)) * 0xC2B2AE35;
        z ^= z >>> 16;
        state = z == 0 ? 0x6D2B79F5 : z;
    }

    /** Next 32 random bits (xorshift32 13/17/5). */
    public int nextInt() {
        int x = state;
        x ^= x << 13;
        x ^= x >>> 17;
        x ^= x << 5;
        state = x;
        return x;
    }

    /**
     * Uniform in [0, 1) like C {@code rfrac()}.
     * <p>
     * BEHAVIOR: uses the top 24 bits, so the result is exactly representable and never 1.
     */
    public float nextFloat() {
        return (nextInt() >>> 8) * (1.0f / (1 << 24));
    }
}
