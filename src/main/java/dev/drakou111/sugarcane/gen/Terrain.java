package dev.drakou111.sugarcane.gen;

import dev.drakou111.sugarcane.world.Blocks;
import kaptainwutax.biomeutils.source.OverworldBiomeSource;
import kaptainwutax.mcutils.block.Block;
import kaptainwutax.noiseutils.noise.NoiseSampler;
import kaptainwutax.terrainutils.terrain.OverworldTerrainGenerator;
import kaptainwutax.terrainutils.terrain.SurfaceGenerator;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * The noise terrain, wrapped so that a search can use it column by column
 * without leaking memory and with access to the surface noise.
 *
 * <p>Two things TerrainUtils does not expose:
 * <ul>
 *   <li>{@code surfaceDepthNoise}, the sampler the surface builder needs. It is
 *       built <em>between</em> the main noise and the depth noise from the same
 *       RNG, so reconstructing it separately would mean redoing that whole chain;
 *       it is read out by reflection instead;</li>
 *   <li>the two column caches, which are unbounded {@link Map}s. Left alone they
 *       grow to gigabytes over a long search, so they are cleared per region.</li>
 * </ul>
 *
 * <p>Not thread safe — the underlying generator caches. One instance per thread.
 */
public final class Terrain {

    private static final int SEA_LEVEL = 63;

    private static final Field NOISE_FIELD;
    private static final Field COLUMN_CACHE;
    private static final Field NOISE_COLUMN_CACHE;

    static {
        try {
            NOISE_FIELD = SurfaceGenerator.class.getDeclaredField("surfaceDepthNoise");
            COLUMN_CACHE = SurfaceGenerator.class.getDeclaredField("columnCache");
            NOISE_COLUMN_CACHE = SurfaceGenerator.class.getDeclaredField("noiseColumnCache");
            NOISE_FIELD.setAccessible(true);
            COLUMN_CACHE.setAccessible(true);
            NOISE_COLUMN_CACHE.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final OverworldBiomeSource biomes;
    private final OverworldTerrainGenerator generator;
    private final NoiseSampler surfaceDepthNoise;
    private final Map<?, ?> columnCache;
    private final Map<?, ?> noiseColumnCache;
    private final Block[] buffer = new Block[256];
    private final TruncatedNoise truncated;
    private long fallbacks;

    public Terrain(OverworldBiomeSource biomes) {
        this.biomes = biomes;
        this.generator = new OverworldTerrainGenerator(biomes);
        try {
            this.surfaceDepthNoise = (NoiseSampler) NOISE_FIELD.get(generator);
            this.columnCache = (Map<?, ?>) COLUMN_CACHE.get(generator);
            this.noiseColumnCache = (Map<?, ?>) NOISE_COLUMN_CACHE.get(generator);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("TerrainUtils internals changed", e);
        }
        this.truncated = new TruncatedNoise(generator, biomes);
    }

    public OverworldBiomeSource biomes() {
        return biomes;
    }

    /**
     * Fills {@code out} with the raw noise column in the reduced palette and
     * returns {@code WORLD_SURFACE_WG}: one past the highest non-air block,
     * counting water.
     *
     * <p>Uses the truncated density field, which is where nearly all of the
     * search's time goes, and falls back to the full generator for the rare
     * column whose terrain reaches the cut-off.
     */
    public int column(int x, int z, byte[] out) {
        int height = truncated.column(x, z, out, Blocks.SOLID, Blocks.WATER, Blocks.AIR, SEA_LEVEL);
        if (height >= 0) {
            return height;
        }
        fallbacks++;
        return fullColumn(x, z, out);
    }

    /** How often a column was too tall for the truncated path. Diagnostic. */
    public long fallbacks() {
        return fallbacks;
    }

    private int fullColumn(int x, int z, byte[] out) {
        generator.generateColumn(buffer, x, z, null);
        int height = 0;
        for (int y = 0; y < 256; y++) {
            Block block = buffer[y];
            byte b;
            if (block == kaptainwutax.mcutils.block.Blocks.STONE) {
                b = Blocks.SOLID;
            } else if (block == kaptainwutax.mcutils.block.Blocks.WATER) {
                b = Blocks.WATER;
            } else {
                b = Blocks.AIR;
            }
            out[y] = b;
            if (b != Blocks.AIR) {
                height = y + 1;
            }
        }
        return height;
    }

    /**
     * {@code surfaceNoise.getSurfaceNoiseValue(x/16, z/16, 1/16, localX/16) * 15},
     * the value {@code buildSurfaceAndBedrock} hands to the surface builder.
     */
    public double surfaceNoise(int x, int z, int localX) {
        return surfaceDepthNoise.sample(x * 0.0625, z * 0.0625, 0.0625, localX * 0.0625) * 15.0;
    }

    /**
     * Points the truncated-noise caches at a region, in block coordinates, so they
     * can be flat arrays rather than hash maps. Call once per region, before
     * generating it.
     */
    public void beginRegion(int originBlockX, int originBlockZ, int spanBlocks) {
        truncated.beginRegion(originBlockX, originBlockZ, spanBlocks);
    }

    /** Frees the column caches. Call once per region. */
    public void clearCaches() {
        columnCache.clear();
        noiseColumnCache.clear();
        truncated.clearCache();
    }
}
