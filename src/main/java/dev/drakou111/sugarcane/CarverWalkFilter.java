package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.gen.CanyonCarver;
import dev.drakou111.sugarcane.gen.Carver;
import dev.drakou111.sugarcane.gen.CarverConfig;
import dev.drakou111.sugarcane.gen.CaveCarver;
import dev.drakou111.sugarcane.rng.JavaRandom;

import java.util.BitSet;

/**
 * How selective is a terrain-free test on the carver walks alone?
 *
 * <p>The walks are pure RNG: where a tunnel goes depends only on the seed. Only
 * the <em>decisions</em> (canReplace, hasWater) read the world. So the walk can be
 * run against a permissive stub — everything replaceable, nothing water — which
 * over-approximates what gets carved and therefore can only ever accept a chunk
 * the real carvers would also touch. That makes it a sound prefilter: it never
 * rejects real geometry.
 *
 * <p>The question this answers is whether it is worth anything. The sugar cane
 * geometry needs air from an AIR-step carver against water from a LIQUID-step one,
 * below sea level. If that contact is rare, a terrain-free test is a big win and
 * targeting the carvers is the right strategy. If nearly every ocean chunk has it,
 * the rarity lives in the fine arrangement and the rock, and no amount of carver
 * targeting helps.
 */
public final class CarverWalkFilter {

    private static final int SEA = 63;

    /** Collects carved positions and answers every question permissively. */
    private static final class Stub implements Carver.Target {
        final BitSet mask = new BitSet(65536);

        @Override
        public boolean canReplace(int x, int y, int z) {
            return true;
        }

        @Override
        public boolean isWater(int x, int y, int z) {
            return false;      // never aborts a sphere: over-approximates
        }

        @Override
        public boolean isAir(int x, int y, int z) {
            return false;
        }

        @Override
        public void setCaveAir(int x, int y, int z) {
            mark(x, y, z);
        }

        @Override
        public void setWater(int x, int y, int z, boolean scheduleTick) {
            mark(x, y, z);
        }

        private void mark(int x, int y, int z) {
            if (y >= 0 && y < 256) {
                mask.set((x & 15) | (z & 15) << 4 | y << 8);
            }
        }
    }

    private static boolean get(BitSet mask, int lx, int y, int lz) {
        return lx >= 0 && lx < 16 && lz >= 0 && lz < 16 && y >= 0 && y < 256
                && mask.get(lx | lz << 4 | y << 8);
    }

    public static void main(String[] args) {
        int chunks = args.length > 0 ? Integer.parseInt(args[0]) : 20000;
        long firstSeed = args.length > 1 ? Long.parseLong(args[1]) : 900000001L;

        JavaRandom random = new JavaRandom();
        int touching = 0;
        int bothPresent = 0;
        int faces = 0;
        long start = System.nanoTime();

        for (int i = 0; i < chunks; i++) {
            long seed = firstSeed + i;
            int cx = i % 32 - 16, cz = i / 32 % 32 - 16;

            Stub air = new Stub();
            Stub liquid = new Stub();
            BitSet airMask = new BitSet(65536);
            BitSet liquidMask = new BitSet(65536);
            Carver cave = new CaveCarver(air, cx, cz, false, SEA, airMask);
            Carver canyon = new CanyonCarver(air, cx, cz, false, SEA, airMask);
            Carver uCanyon = new CanyonCarver(liquid, cx, cz, true, SEA, liquidMask);
            Carver uCave = new CaveCarver(liquid, cx, cz, true, SEA, liquidMask);

            int r = CarverConfig.CARVE_RADIUS;
            for (int sx = cx - r; sx <= cx + r; sx++) {
                for (int sz = cz - r; sz <= cz + r; sz++) {
                    if (CarverConfig.isStartChunk(random, seed, 0, sx, sz, CarverConfig.CAVE_OCEAN)) {
                        cave.carve(random, sx, sz);
                    }
                    if (CarverConfig.isStartChunk(random, seed, 1, sx, sz, CarverConfig.CANYON)) {
                        canyon.carve(random, sx, sz);
                    }
                }
            }
            for (int sx = cx - r; sx <= cx + r; sx++) {
                for (int sz = cz - r; sz <= cz + r; sz++) {
                    if (CarverConfig.isStartChunk(random, seed, 0, sx, sz,
                            CarverConfig.UNDERWATER_CANYON)) {
                        uCanyon.carve(random, sx, sz);
                    }
                    if (CarverConfig.isStartChunk(random, seed, 1, sx, sz,
                            CarverConfig.UNDERWATER_CAVE)) {
                        uCave.carve(random, sx, sz);
                    }
                }
            }

            if (!air.mask.isEmpty() && !liquid.mask.isEmpty()) {
                bothPresent++;
            }
            // The geometry needs air from the AIR step with water from the LIQUID
            // step horizontally beside it, below sea level.
            boolean contact = false;
            for (int y = 11; y < SEA && !contact; y++) {
                for (int lx = 0; lx < 16 && !contact; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        if (!get(air.mask, lx, y, lz)) {
                            continue;
                        }
                        if (get(liquid.mask, lx - 1, y, lz) || get(liquid.mask, lx + 1, y, lz)
                                || get(liquid.mask, lx, y, lz - 1) || get(liquid.mask, lx, y, lz + 1)) {
                            contact = true;
                            break;
                        }
                    }
                }
            }
            if (contact) {
                touching++;
            }
            // Tighter: the real geometry needs a face, not a touch — air at three
            // consecutive heights with liquid beside it, which is what lets a
            // second column start on top of the first.
            boolean face = false;
            for (int y = 11; y + 2 < SEA && !face; y++) {
                for (int lx = 0; lx < 16 && !face; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        boolean all = true;
                        for (int d = 0; d < 3 && all; d++) {
                            all = get(air.mask, lx, y + d, lz)
                                    && (get(liquid.mask, lx - 1, y + d, lz)
                                    || get(liquid.mask, lx + 1, y + d, lz)
                                    || get(liquid.mask, lx, y + d, lz - 1)
                                    || get(liquid.mask, lx, y + d, lz + 1));
                        }
                        if (all) {
                            face = true;
                            break;
                        }
                    }
                }
            }
            if (face) {
                faces++;
            }
        }

        double us = (System.nanoTime() - start) / 1000.0 / chunks;
        System.out.printf("%d chunks, %.1f us each (walks only, no terrain)%n", chunks, us);
        System.out.printf("  both carver steps touched the chunk : %d  (%.1f%%)%n",
                bothPresent, 100.0 * bothPresent / chunks);
        System.out.printf("  air carve adjacent to liquid carve  : %d  (%.1f%%)  <- the filter%n",
                touching, 100.0 * touching / chunks);
        System.out.printf("  3-tall air face with liquid beside  : %d  (%.1f%%)  <- tighter%n",
                faces, 100.0 * faces / chunks);
        System.out.printf("%nFor comparison, a stackable spot occurs in about 0.09%% of ocean chunks.%n");
    }
}
