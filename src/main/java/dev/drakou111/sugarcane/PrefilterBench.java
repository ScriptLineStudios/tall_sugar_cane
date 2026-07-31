package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.gen.BiomeCaneConfig;
import dev.drakou111.sugarcane.gen.GeometryPrefilter;
import dev.drakou111.sugarcane.gen.StackPrefilter;
import dev.drakou111.sugarcane.rng.JavaRandom;
import dev.drakou111.sugarcane.validate.BiomeSourceValidator;
import kaptainwutax.biomeutils.source.OverworldBiomeSource;
import kaptainwutax.mcutils.version.MCVersion;

/**
 * Measures the two seed-only prefilters: how much they reject, what they cost, and
 * whether they keep the one confirmed find.
 *
 * <p>Both are over-approximations by construction, so neither should ever reject a
 * chunk that really holds a stack. The confirmed find on seed 1500050556 at chunk
 * 5,4 is the test that matters — a filter that drops it is worthless however fast
 * it is.
 */
public final class PrefilterBench {

    private PrefilterBench() {
    }

    public static void main(String[] args) {
        int seeds = args.length > 0 ? Integer.parseInt(args[0]) : 40;
        int radius = args.length > 1 ? Integer.parseInt(args[1]) : 6;

        // The confirmed find first: both filters must accept it.
        checkKnown();

        StackPrefilter cane = new StackPrefilter(10);
        GeometryPrefilter geometry = new GeometryPrefilter();
        JavaRandom random = new JavaRandom();

        long ocean = 0, canePass = 0, geomPass = 0, bothPass = 0;
        long caneNanos = 0, geomNanos = 0;

        for (int s = 0; s < seeds; s++) {
            long seed = 4000000001L + s;
            OverworldBiomeSource biomes = new OverworldBiomeSource(MCVersion.v1_16_1, seed);
            for (int cx = -radius; cx <= radius; cx++) {
                for (int cz = -radius; cz <= radius; cz++) {
                    int biome = biomes.getBiomeForNoiseGen(cx * 4 + 2, 0, cz * 4 + 2).getId();
                    if (!BiomeSourceValidator.isOcean(biome) || !BiomeCaneConfig.hasSugarCane(biome)) {
                        continue;
                    }
                    ocean++;

                    long decorationSeed = random.setDecorationSeed(seed, cx * 16, cz * 16);
                    long t0 = System.nanoTime();
                    boolean caneOk = cane.accepts(decorationSeed, 5, 10, cx, cz, 0)
                            || cane.accepts(decorationSeed, 5, 10, cx, cz, 1)
                            || cane.accepts(decorationSeed, 5, 10, cx, cz, 2);
                    long t1 = System.nanoTime();
                    boolean geomOk = geometry.accepts(seed, cx, cz, true);
                    long t2 = System.nanoTime();
                    caneNanos += t1 - t0;
                    geomNanos += t2 - t1;

                    if (caneOk) {
                        canePass++;
                    }
                    if (geomOk) {
                        geomPass++;
                    }
                    if (caneOk && geomOk) {
                        bothPass++;
                    }
                }
            }
        }

        System.out.printf("%n%d ocean chunks%n", ocean);
        System.out.printf("  cane RNG pair      : %6.2f%%   %6.1f us/chunk%n",
                100.0 * canePass / ocean, caneNanos / 1000.0 / ocean);
        System.out.printf("  carver envelope    : %6.2f%%   %6.1f us/chunk%n",
                100.0 * geomPass / ocean, geomNanos / 1000.0 / ocean);
        System.out.printf("  both              : %6.2f%%   %6.1f us/chunk total%n",
                100.0 * bothPass / ocean, (caneNanos + geomNanos) / 1000.0 / ocean);

        double filterCost = (caneNanos + geomNanos) / 1000.0 / ocean;
        double accept = (double) bothPass / ocean;
        // A searched chunk currently costs about 110 us, of which the terrain is
        // most; with a filter, only the accepted chunks and their neighbourhoods
        // pay it, and the neighbourhood dilation is what decides how much is saved.
        double dilated = 1.0 - Math.pow(1.0 - accept, 9);
        System.out.printf("%nfilter accepts %.1f%% of chunks; their 3x3 neighbourhoods cover %.1f%%%n",
                100.0 * accept, 100.0 * dilated);
        System.out.printf("projected cost/chunk: %.1f us filter + %.1f us terrain = %.1f us "
                        + "against 110 us now  ->  %.2fx%n",
                filterCost, dilated * 110.0, filterCost + dilated * 110.0,
                110.0 / (filterCost + dilated * 110.0));
    }

    private static void checkKnown() {
        long seed = 1500050556L;
        int cx = 5, cz = 4;
        JavaRandom random = new JavaRandom();
        long decorationSeed = random.setDecorationSeed(seed, cx * 16, cz * 16);
        StackPrefilter cane = new StackPrefilter(10);
        boolean caneOk = cane.accepts(decorationSeed, 5, 10, cx, cz, 0)
                || cane.accepts(decorationSeed, 5, 10, cx, cz, 1)
                || cane.accepts(decorationSeed, 5, 10, cx, cz, 2);
        boolean geomOk = new GeometryPrefilter().accepts(seed, cx, cz, true);
        System.out.printf("confirmed find (seed %d chunk %d,%d, 5-tall at 91,16,65):%n", seed, cx, cz);
        System.out.printf("  cane RNG filter (tied shifts) : %s%n",
                caneOk ? "ACCEPTED" : "REJECTED");
        System.out.printf("  carver envelope               : %s%n",
                geomOk ? "ACCEPTED" : "REJECTED");
        System.out.println("  which (baseShift, topShift) pairs accept it:");
        for (int lo = 0; lo <= 6; lo += 2) {
            for (int hi = lo + 2; hi <= 10; hi += 2) {
                if (cane.accepts(decorationSeed, 5, 10, cx, cz, lo, hi)) {
                    System.out.printf("      base shift %d, top shift %d  -> ACCEPTED%n", lo, hi);
                }
            }
        }
    }
}
