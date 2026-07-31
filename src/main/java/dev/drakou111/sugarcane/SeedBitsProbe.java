package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.gen.CarverConfig;
import dev.drakou111.sugarcane.gen.StackPrefilter;
import dev.drakou111.sugarcane.gen.SugarCaneFeature;
import dev.drakou111.sugarcane.rng.JavaRandom;
import kaptainwutax.biomeutils.source.OverworldBiomeSource;
import kaptainwutax.mcutils.version.MCVersion;

/**
 * Tests the claim the whole "roll the upper 16 bits" idea rests on: that the low
 * 48 bits of the world seed fix the carvers and all decoration, while the biomes
 * depend on all 64.
 *
 * <p>The reason to expect it is that every worldgen RNG goes through
 * {@code Random.setSeed}, which masks to 48 bits. {@code setDecorationSeed} does
 * XOR in the full 64-bit seed —
 * {@code (x * a + z * b) ^ levelSeed} — but the result is immediately fed back
 * through {@code setSeed}, so the top 16 bits are masked away again. Biomes take
 * a different route: the layer salts mix the full seed, and the Voronoi zoomer
 * uses {@code WorldSeed.toHash(worldSeed)}.
 *
 * <p>Chunk (0,0) makes it easy to see: both {@code setDecorationSeed} and
 * {@code setLargeFeatureSeed} collapse to the level seed itself there, since the
 * chunk coordinates they multiply in are zero.
 */
public final class SeedBitsProbe {

    private SeedBitsProbe() {
    }

    public static void main(String[] args) {
        long low48 = (args.length > 0 ? Long.parseLong(args[0]) : 1500050556L)
                & 0xFFFFFFFFFFFFL;
        System.out.printf("low 48 bits fixed at %d%n%n", low48);
        System.out.printf("%-6s %-22s %-26s %-10s %s%n",
                "upper", "decoration seed(0,0)", "first cane draw (x,z,y)",
                "cave@0,0", "biome@0,0");

        StackPrefilter filter = new StackPrefilter(10);
        JavaRandom random = new JavaRandom();
        for (int upper = 0; upper < 6; upper++) {
            long seed = ((long) upper << 48) | low48;

            long decorationSeed = random.setDecorationSeed(seed, 0, 0);

            // The first three draws of the cane feature: the invocation origin.
            JavaRandom cane = new JavaRandom();
            cane.setFeatureSeed(decorationSeed, 5, SugarCaneFeature.VEGETAL_DECORATION);
            int x = cane.nextInt(16), z = cane.nextInt(16), y = cane.nextInt(126);

            // Whether chunk 0,0 starts the ocean cave carver.
            boolean caveStart = CarverConfig.isStartChunk(random, seed, 0, 0, 0,
                    CarverConfig.CAVE_OCEAN);

            int biome = new OverworldBiomeSource(MCVersion.v1_16_1, seed)
                    .getBiomeForNoiseGen(2, 0, 2).getId();

            System.out.printf("%-6d %-22d (%2d,%2d,%3d)%14s %-10s %d%n",
                    upper, decorationSeed, x, z, y, "", caveStart, biome);
        }

        System.out.println();
        System.out.println("If the decoration seed and the cane draws are identical down the");
        System.out.println("column while the biome changes, the premise holds: the geometry RNG");
        System.out.println("and the cane RNG are properties of the low 48 bits alone, and the");
        System.out.println("upper 16 are a free 65,536-way re-roll of the biome map.");

        // And the stacking-pair test, which is also a low-48 property.
        long decorationSeed = random.setDecorationSeed(low48, 0, 0);
        System.out.printf("%nstacking-compatible cane pair at chunk 0,0 for this low 48: %s%n",
                filter.accepts(decorationSeed, 5, 10, 0, 0, 0) ? "yes" : "no");
    }
}
