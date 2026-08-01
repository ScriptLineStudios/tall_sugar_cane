package dev.drakou111.sugarcane.gen;

import dev.drakou111.sugarcane.rng.JavaRandom;
import kaptainwutax.biomeutils.source.OverworldBiomeSource;

/**
 * Where a fresh world puts the player, from {@code MinecraftServer.setInitialSpawn}.
 *
 * <p>Vanilla calls {@code findBiomeHorizontal(0, 63, 0, 256, PLAYER_SPAWN_BIOMES,
 * new Random(seed))}, which despite appearances is not a widening spiral: with
 * {@code findClosest} false the outer loop starts at the full radius, so it runs
 * once and sweeps a single 129x129 square of quart cells, reservoir-sampling every
 * match with {@code random.nextInt(count + 1)}. The chunk containing the winner
 * becomes the spawn chunk.
 *
 * <p>Vanilla then refines within that chunk via {@code PlayerRespawnLogic}, spiralling
 * outward up to 16 chunks if it finds nowhere to stand. That refinement needs real
 * terrain, so it is not done here — the first chunk it tries is this one, and for a
 * biome picked from the spawn list it almost always succeeds. So this is the spawn
 * chunk, not the spawn block, which is all a search centre needs.
 */
public final class SpawnFinder {

    /** {@code BiomeSource.PLAYER_SPAWN_BIOMES}: forest, plains, taiga, taiga_hills,
     *  wooded_hills, jungle, jungle_hills. */
    private static final boolean[] SPAWN_BIOME = new boolean[256];

    static {
        for (int id : new int[]{4, 1, 5, 19, 18, 21, 22}) {
            SPAWN_BIOME[id] = true;
        }
    }

    private static final int QUART_RADIUS = 256 >> 2;
    private static final int QUART_Y = 63 >> 2;

    private SpawnFinder() {
    }

    /**
     * The spawn chunk, packed as {@code (long) chunkX << 32 | chunkZ & 0xFFFFFFFFL}.
     * Falls back to chunk 0,0 when no spawn biome is in range, as vanilla does.
     */
    public static long spawnChunk(OverworldBiomeSource biomes, long worldSeed) {
        JavaRandom random = new JavaRandom();
        random.setSeed(worldSeed);
        int foundX = 0;
        int foundZ = 0;
        boolean any = false;
        int seen = 0;
        // Point queries, not the bulk area sampler: the stack's sample(x,y,z,dx,dy,dz)
        // measured 331 ms per seed against 18.3 ms here, because it bypasses the
        // per-layer caches that the 16,641 overlapping lookups otherwise share.
        // j is z and k is x, in that nesting order, because the reservoir draw
        // consumes RNG and a different order picks a different winner.
        for (int j = -QUART_RADIUS; j <= QUART_RADIUS; j++) {
            for (int k = -QUART_RADIUS; k <= QUART_RADIUS; k++) {
                int id = BiomeIds.noiseGen(biomes, k, j);
                if (id < 0 || id >= SPAWN_BIOME.length || !SPAWN_BIOME[id]) {
                    continue;
                }
                if (!any || random.nextInt(seen + 1) == 0) {
                    foundX = k << 2;
                    foundZ = j << 2;
                    any = true;
                }
                seen++;
            }
        }
        if (!any) {
            return 0L;
        }
        return ((long) (foundX >> 4) << 32) | ((foundZ >> 4) & 0xFFFFFFFFL);
    }

    public static int chunkX(long packed) {
        return (int) (packed >> 32);
    }

    public static int chunkZ(long packed) {
        return (int) packed;
    }
}
