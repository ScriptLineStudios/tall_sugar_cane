package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.gen.StackPrefilter;
import dev.drakou111.sugarcane.rng.JavaRandom;

/**
 * Measures how much a terrain-free prefilter on the cane RNG could actually save.
 *
 * <p>The idea is attractive: a stack needs two tries at the same (x, z) with the
 * second exactly one column-height above the first, and all of that is computable
 * from the decoration seed with no terrain. If such a pair were rare, the search
 * could skip generating terrain for most chunks.
 *
 * <p>It also checks the one chunk known to have produced a stack (seed 119658,
 * chunk -17,-12), which needed one prior placement before the stack's lower
 * column — a reminder that a sound filter has to allow for earlier successes
 * shifting the stream, and that each extra allowance makes it less selective.
 */
public final class StackPrefilterStats {

    private StackPrefilterStats() {
    }

    public static void main(String[] args) {
        int chunks = args.length > 0 ? Integer.parseInt(args[0]) : 200000;
        StackPrefilter filter = new StackPrefilter(10);
        JavaRandom random = new JavaRandom();

        for (int priors = 0; priors <= 2; priors++) {
            int accepted = 0;
            long start = System.nanoTime();
            for (int i = 0; i < chunks; i++) {
                long seed = 1000000L + i;
                int chunkX = i % 64 - 32;
                int chunkZ = i / 64 % 64 - 32;
                long decorationSeed = random.setDecorationSeed(seed, chunkX * 16, chunkZ * 16);
                if (filter.accepts(decorationSeed, 5, 10, chunkX, chunkZ, priors)) {
                    accepted++;
                }
            }
            double us = (System.nanoTime() - start) / 1000.0 / chunks;
            System.out.printf("assuming %d prior placement(s): accepted %d / %d = %.1f%%  (%.1f us/chunk)%n",
                    priors, accepted, chunks, 100.0 * accepted / chunks, us);
        }

        // A sound filter must allow for any number of prior placements, so what
        // matters is the union of the variants.
        int union = 0;
        long start = System.nanoTime();
        for (int i = 0; i < chunks; i++) {
            long seed = 1000000L + i;
            int chunkX = i % 64 - 32;
            int chunkZ = i / 64 % 64 - 32;
            long decorationSeed = random.setDecorationSeed(seed, chunkX * 16, chunkZ * 16);
            if (filter.accepts(decorationSeed, 5, 10, chunkX, chunkZ, 0)
                    || filter.accepts(decorationSeed, 5, 10, chunkX, chunkZ, 1)
                    || filter.accepts(decorationSeed, 5, 10, chunkX, chunkZ, 2)) {
                union++;
            }
        }
        System.out.printf("union of 0..2 priors           : accepted %d / %d = %.1f%%  (%.1f us/chunk)%n",
                union, chunks, 100.0 * union / chunks,
                (System.nanoTime() - start) / 1000.0 / chunks);

        // Any sound filter has to accept a chunk that really did stack.
        long known = random.setDecorationSeed(119658L, -17 * 16, -12 * 16);
        System.out.println();
        for (int priors = 0; priors <= 2; priors++) {
            System.out.printf("seed 119658 chunk -17,-12 with %d prior placement(s): %s%n",
                    priors, filter.accepts(known, 5, 10, -17, -12, priors)
                            ? "ACCEPTED" : "rejected");
        }
        System.out.println();
        System.out.println("The real stack there was: invocation 0 try 5 placed, try 9 placed the");
        System.out.println("lower column (so one prior placement), invocation 8 try 18 placed on top.");
    }
}
