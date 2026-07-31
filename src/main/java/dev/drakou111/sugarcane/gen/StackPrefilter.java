package dev.drakou111.sugarcane.gen;

import dev.drakou111.sugarcane.rng.JavaRandom;

/**
 * Asks, from the decoration seed alone and with no terrain at all, whether a
 * chunk's cane RNG could ever stack one column on another.
 *
 * <p>The stream is almost entirely terrain-independent: every {@code nextInt} is
 * one LCG step, the offsets are fixed (3 draws per invocation for the origin, then
 * 6 per try), and the only thing terrain changes is that a <em>successful</em>
 * placement consumes 2 extra draws for the column height. So the whole sequence
 * can be laid out flat and indexed, with a shift of 2 per success that happened
 * earlier.
 *
 * <p>Two structural facts make the test cheap:
 * <ul>
 *   <li>all 20 tries of one invocation share its y (the y-spread is 0), so the two
 *       columns of a stack must come from <em>different</em> invocations;</li>
 *   <li>the second column must start exactly at {@code y1 + height1}, and
 *       {@code height1} is itself two known draws — so the later invocation's y is
 *       pinned to a single value rather than a range.</li>
 * </ul>
 *
 * <p>Written to answer whether this makes a useful search prefilter. It does not:
 * see {@code StackPrefilterStats}. A compatible pair turns out to be common, so
 * the filter rejects too little to pay for the machinery — what is rare is the
 * terrain, not the RNG.
 */
public final class StackPrefilter {

    private static final int TRIES = 20;
    private static final int DRAWS_PER_TRY = 6;
    private static final int DRAWS_PER_INVOCATION = 3 + TRIES * DRAWS_PER_TRY;
    /** Draws ColumnPlacer consumes on a success. */
    private static final int SUCCESS_DRAWS = 2;

    /** The heightmap over open ocean, which is what the search restricts itself to. */
    public static final int OCEAN_HEIGHTMAP = 63;

    /** Cane can only stand between the lava layer and just above the water surface. */
    private static final int MIN_Y = 11;
    private static final int MAX_Y = 64;

    private final int[] draws;
    private final int capacity;

    public StackPrefilter(int maxCount) {
        this.capacity = maxCount * DRAWS_PER_INVOCATION + 4 * SUCCESS_DRAWS + 16;
        this.draws = new int[capacity];
    }

    /**
     * @param priorSuccesses how many placements are assumed to have happened before
     *                       the stack's lower column, each shifting the stream by
     *                       two draws. The real hit on seed 119658 needed one.
     * @return true if some pair of tries could stack
     */
    public boolean accepts(long decorationSeed, int featureIndex, int count,
                           int chunkX, int chunkZ, int priorSuccesses) {
        return accepts(decorationSeed, featureIndex, count, chunkX, chunkZ,
                priorSuccesses * SUCCESS_DRAWS, priorSuccesses * SUCCESS_DRAWS + SUCCESS_DRAWS);
    }

    /**
     * The two shifts independently. They are not tied together: successes between
     * the two columns shift the upper one further, and the confirmed find on seed
     * 1500050556 needs base shift 0 with top shift 4 — its chunk placed a second,
     * unrelated column in between.
     */
    public boolean accepts(long decorationSeed, int featureIndex, int count,
                           int chunkX, int chunkZ, int shiftLow, int shiftHigh) {
        JavaRandom random = new JavaRandom();
        random.setFeatureSeed(decorationSeed, featureIndex, SugarCaneFeature.VEGETAL_DECORATION);
        for (int i = 0; i < capacity; i++) {
            draws[i] = random.nextInt();
        }

        int originX = chunkX << 4;
        int originZ = chunkZ << 4;
        int doubled = OCEAN_HEIGHTMAP * 2;
        for (int n1 = 0; n1 < count; n1++) {
            int base1 = n1 * DRAWS_PER_INVOCATION + shiftLow;
            if (base1 + 2 >= capacity) {
                return false;
            }
            int y1 = bounded(draws[base1 + 2], doubled);
            if (y1 < MIN_Y || y1 > MAX_Y) {
                continue;
            }
            int x1o = bounded(draws[base1], 16) + originX;
            int z1o = bounded(draws[base1 + 1], 16) + originZ;

            for (int i1 = 0; i1 < TRIES; i1++) {
                int off1 = base1 + 3 + i1 * DRAWS_PER_TRY;
                if (off1 + DRAWS_PER_TRY + 1 >= capacity) {
                    break;
                }
                int px1 = x1o + bounded(draws[off1], 5) - bounded(draws[off1 + 1], 5);
                int pz1 = z1o + bounded(draws[off1 + 4], 5) - bounded(draws[off1 + 5], 5);
                // The height is drawn immediately after the try that succeeded.
                int after = off1 + DRAWS_PER_TRY;
                int height1 = 2 + bounded(draws[after + 1], bounded(draws[after], 3) + 1);
                int wantedY = y1 + height1;

                // Only a later invocation can supply the upper column, and its y
                // has to equal wantedY exactly.
                for (int n2 = n1 + 1; n2 < count; n2++) {
                    int base2 = n2 * DRAWS_PER_INVOCATION + shiftHigh;
                    if (base2 + 2 >= capacity) {
                        break;
                    }
                    if (bounded(draws[base2 + 2], doubled) != wantedY) {
                        continue;
                    }
                    int x2o = bounded(draws[base2], 16) + originX;
                    int z2o = bounded(draws[base2 + 1], 16) + originZ;
                    for (int i2 = 0; i2 < TRIES; i2++) {
                        int off2 = base2 + 3 + i2 * DRAWS_PER_TRY;
                        if (off2 + 5 >= capacity) {
                            break;
                        }
                        int px2 = x2o + bounded(draws[off2], 5) - bounded(draws[off2 + 1], 5);
                        int pz2 = z2o + bounded(draws[off2 + 4], 5) - bounded(draws[off2 + 5], 5);
                        if (px2 == px1 && pz2 == pz1) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * {@code Random.nextInt(bound)} recovered from a raw {@code next(32)} value:
     * {@code next(31)} is the same LCG step shifted one bit further. The rejection
     * retry is ignored, which can only cause a false accept.
     */
    private static int bounded(int raw, int bound) {
        int bits = raw >>> 1;
        if ((bound & -bound) == bound) {
            return (int) ((bound * (long) bits) >> 31);
        }
        return bits % bound;
    }
}
