package dev.drakou111.sugarcane.gen;

import kaptainwutax.biomeutils.biome.Biome;
import kaptainwutax.biomeutils.biome.Biomes;
import kaptainwutax.biomeutils.source.OverworldBiomeSource;

/**
 * Biome lookups that stop at the id.
 *
 * <p>{@code OverworldBiomeSource.getBiome} and {@code getBiomeForNoiseGen} both end
 * with {@code Biomes.REGISTRY.get(Integer.valueOf(id))} — a boxed HashMap lookup —
 * and every caller here immediately takes {@code .getId()} back off the result, or
 * wants only depth and scale. The layers underneath are public and return the id
 * directly, so going straight to them removes a box and a map probe from a call
 * made 256 times per chunk. Profiling put biome lookups at 20% of the search.
 *
 * <p>Both accessors ignore the y they are given — the library passes a constant 0
 * to the layer — so the y argument is dropped here rather than carried around.
 */
public final class BiomeIds {

    private static final float[] DEPTH;
    private static final float[] SCALE;

    static {
        int max = 0;
        for (Integer id : Biomes.REGISTRY.keySet()) {
            max = Math.max(max, id);
        }
        DEPTH = new float[max + 1];
        SCALE = new float[max + 1];
        for (java.util.Map.Entry<Integer, Biome> e : Biomes.REGISTRY.entrySet()) {
            DEPTH[e.getKey()] = e.getValue().getDepth();
            SCALE[e.getKey()] = e.getValue().getScale();
        }
    }

    private BiomeIds() {
    }

    /** {@code getBiomeForNoiseGen(x, *, z).getId()} — quart resolution, no Voronoi. */
    public static int noiseGen(OverworldBiomeSource source, int quartX, int quartZ) {
        return source.full.get(quartX, 0, quartZ);
    }

    /** {@code getBiome(x, *, z).getId()} — block resolution, Voronoi applied. */
    public static int voronoi(OverworldBiomeSource source, int blockX, int blockZ) {
        return source.voronoi.get(blockX, 0, blockZ);
    }

    public static float depth(int biomeId) {
        return DEPTH[biomeId];
    }

    public static float scale(int biomeId) {
        return SCALE[biomeId];
    }
}
