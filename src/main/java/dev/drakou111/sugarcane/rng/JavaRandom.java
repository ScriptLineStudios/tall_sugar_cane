package dev.drakou111.sugarcane.rng;

/**
 * Bit-exact reimplementation of {@link java.util.Random}, plus Minecraft's
 * {@code WorldgenRandom} seeding helpers (1.16.1, verified against the
 * official-mapping decompile of {@code net.minecraft.world.level.levelgen.WorldgenRandom}).
 *
 * <p>Every {@code nextInt} call site matters: the whole search depends on
 * consuming the stream in exactly the order the game does. In particular
 * {@code nextInt(1)} still advances the LCG.
 */
public final class JavaRandom {
    private static final long MULTIPLIER = 0x5DEECE66DL;
    private static final long ADDEND = 0xBL;
    private static final long MASK = (1L << 48) - 1;

    private long seed;

    public JavaRandom() {
    }

    public JavaRandom(long seed) {
        setSeed(seed);
    }

    public void setSeed(long seed) {
        this.seed = (seed ^ MULTIPLIER) & MASK;
    }

    public long getRawSeed() {
        return seed;
    }

    private int next(int bits) {
        seed = (seed * MULTIPLIER + ADDEND) & MASK;
        return (int) (seed >>> (48 - bits));
    }

    public int nextInt() {
        return next(32);
    }

    public int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive: " + bound);
        }
        if ((bound & -bound) == bound) { // power of two
            return (int) ((bound * (long) next(31)) >> 31);
        }
        int bits, val;
        do {
            bits = next(31);
            val = bits % bound;
        } while (bits - val + (bound - 1) < 0);
        return val;
    }

    public long nextLong() {
        return ((long) next(32) << 32) + next(32);
    }

    public double nextDouble() {
        return (((long) next(26) << 27) + next(27)) * 0x1.0p-53;
    }

    /**
     * {@code WorldgenRandom.setDecorationSeed(levelSeed, minChunkBlockX, minChunkBlockZ)}.
     * Returns the population seed shared by every feature in the chunk.
     */
    public long setDecorationSeed(long levelSeed, int minChunkBlockX, int minChunkBlockZ) {
        setSeed(levelSeed);
        long a = nextLong() | 1L;
        long b = nextLong() | 1L;
        long populationSeed = (long) minChunkBlockX * a + (long) minChunkBlockZ * b ^ levelSeed;
        setSeed(populationSeed);
        return populationSeed;
    }

    public float nextFloat() {
        return next(24) / ((float) (1 << 24));
    }

    /**
     * {@code WorldgenRandom.setLargeFeatureSeed(levelSeed, chunkX, chunkZ)}.
     * Used to seed carvers: {@code applyCarvers} calls it once per candidate
     * start chunk with the salt {@code levelSeed + carverIndexInBiomeList}.
     */
    /**
     * {@code WorldgenRandom.setLargeFeatureWithSalt}: how structure placement is
     * seeded, on the structure's grid cell rather than the chunk.
     */
    public long setLargeFeatureWithSalt(long levelSeed, int gridX, int gridZ, int salt) {
        long s = (long) gridX * 341873128712L + (long) gridZ * 132897987541L + levelSeed + salt;
        setSeed(s);
        return s;
    }

    public long setLargeFeatureSeed(long levelSeed, int chunkX, int chunkZ) {
        setSeed(levelSeed);
        long a = nextLong();
        long b = nextLong();
        long s = (long) chunkX * a ^ (long) chunkZ * b ^ levelSeed;
        setSeed(s);
        return s;
    }

    /**
     * {@code WorldgenRandom.setBaseChunkSeed(chunkX, chunkZ)}. Seeds the surface
     * builder, which shares one stream across all 256 columns of the chunk.
     */
    public long setBaseChunkSeed(int chunkX, int chunkZ) {
        long s = (long) chunkX * 341873128712L + (long) chunkZ * 132897987541L;
        setSeed(s);
        return s;
    }

    /**
     * {@code WorldgenRandom.setFeatureSeed(decorationSeed, index, step)}.
     *
     * @param index index of the feature within its generation step, for this biome
     * @param step  ordinal of the {@code GenerationStep.Decoration} constant
     */
    public long setFeatureSeed(long decorationSeed, int index, int step) {
        long s = decorationSeed + index + 10000L * step;
        setSeed(s);
        return s;
    }
}
