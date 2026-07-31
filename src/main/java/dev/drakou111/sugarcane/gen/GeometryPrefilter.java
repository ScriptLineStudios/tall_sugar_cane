package dev.drakou111.sugarcane.gen;

import dev.drakou111.sugarcane.rng.JavaRandom;

import java.util.BitSet;

/**
 * Rejects chunks whose carvers could not possibly put air against water below sea
 * level, using the walks alone — no terrain.
 *
 * <p>The walks are pure RNG: where a tunnel goes depends only on the seed, and only
 * the decisions (hasWater, canReplaceBlock) read the world. Running them in
 * collect-only mode gives the envelope of what they <em>could</em> cut, which is a
 * strict over-approximation of what they do cut. So a chunk this rejects can never
 * hold the geometry, and the filter is sound.
 *
 * <p>The test itself is deliberately generous: two spheres count as touching if
 * their bounding boxes come within a block and a half, which admits far more than
 * real adjacency. Tightening it would reject more but risks unsoundness, and the
 * expensive stage it protects is cheap enough that generosity costs little.
 */
public final class GeometryPrefilter {

    private static final int SEA = 63;
    private static final int MAX_SPHERES = 4096;

    /** Flat arrays rather than objects: this runs on every chunk. */
    private final double[] airX = new double[MAX_SPHERES];
    private final double[] airY = new double[MAX_SPHERES];
    private final double[] airZ = new double[MAX_SPHERES];
    private final double[] airR = new double[MAX_SPHERES];
    private final double[] airV = new double[MAX_SPHERES];
    private int airCount;

    private final double[] liqX = new double[MAX_SPHERES];
    private final double[] liqY = new double[MAX_SPHERES];
    private final double[] liqZ = new double[MAX_SPHERES];
    private final double[] liqR = new double[MAX_SPHERES];
    private final double[] liqV = new double[MAX_SPHERES];
    private int liqCount;

    private final BitSet scratch = new BitSet(65536);
    private final JavaRandom random = new JavaRandom();

    /** A target that answers permissively; nothing is read, since collect mode short-circuits. */
    private static final Carver.Target STUB = new Carver.Target() {
        @Override
        public boolean canReplace(int x, int y, int z) {
            return true;
        }

        @Override
        public boolean isWater(int x, int y, int z) {
            return false;
        }

        @Override
        public boolean isAir(int x, int y, int z) {
            return false;
        }

        @Override
        public void setCaveAir(int x, int y, int z) {
        }

        @Override
        public void setWater(int x, int y, int z, boolean scheduleTick) {
        }
    };

    /**
     * @param ocean whether the chunk's corner biome registers the ocean carver set;
     *              only oceans have LIQUID carvers, and without them there is no
     *              water face and nothing to accept
     * @return true if some air sphere and some liquid sphere could touch below sea
     *         level, so the chunk is worth generating terrain for
     */
    public boolean accepts(long seed, int chunkX, int chunkZ, boolean ocean) {
        if (!ocean) {
            return false;
        }
        airCount = 0;
        liqCount = 0;

        Carver.SphereSink airSink = (x, y, z, r, v) -> {
            if (airCount < MAX_SPHERES && y - v < SEA) {
                airX[airCount] = x;
                airY[airCount] = y;
                airZ[airCount] = z;
                airR[airCount] = r;
                airV[airCount] = v;
                airCount++;
            }
        };
        Carver.SphereSink liqSink = (x, y, z, r, v) -> {
            if (liqCount < MAX_SPHERES) {
                liqX[liqCount] = x;
                liqY[liqCount] = y;
                liqZ[liqCount] = z;
                liqR[liqCount] = r;
                liqV[liqCount] = v;
                liqCount++;
            }
        };

        walk(seed, chunkX, chunkZ, airSink, liqSink);
        if (airCount == 0 || liqCount == 0) {
            return false;
        }

        for (int a = 0; a < airCount; a++) {
            double ax = airX[a], ay = airY[a], az = airZ[a];
            double ar = airR[a] + 1.5, av = airV[a] + 1.5;
            for (int l = 0; l < liqCount; l++) {
                // The liquid carver never touches anything at or above sea level,
                // so the contact has to be below it.
                if (Math.min(ay + airV[a], liqY[l] + liqV[l]) >= SEA) {
                    continue;
                }
                double dx = ax - liqX[l];
                double dz = az - liqZ[l];
                double dy = ay - liqY[l];
                double reach = ar + liqR[l];
                if (dx * dx + dz * dz <= reach * reach
                        && Math.abs(dy) <= av + liqV[l]) {
                    return true;
                }
            }
        }
        return false;
    }

    private void walk(long seed, int chunkX, int chunkZ,
                      Carver.SphereSink airSink, Carver.SphereSink liqSink) {
        scratch.clear();
        Carver cave = new CaveCarver(STUB, chunkX, chunkZ, false, SEA, scratch);
        Carver canyon = new CanyonCarver(STUB, chunkX, chunkZ, false, SEA, scratch);
        Carver uCanyon = new CanyonCarver(STUB, chunkX, chunkZ, true, SEA, scratch);
        Carver uCave = new CaveCarver(STUB, chunkX, chunkZ, true, SEA, scratch);
        cave.collectInto(airSink);
        canyon.collectInto(airSink);
        uCanyon.collectInto(liqSink);
        uCave.collectInto(liqSink);

        int r = CarverConfig.CARVE_RADIUS;
        for (int sx = chunkX - r; sx <= chunkX + r; sx++) {
            for (int sz = chunkZ - r; sz <= chunkZ + r; sz++) {
                if (CarverConfig.isStartChunk(random, seed, 0, sx, sz, CarverConfig.CAVE_OCEAN)) {
                    cave.carve(random, sx, sz);
                }
                if (CarverConfig.isStartChunk(random, seed, 1, sx, sz, CarverConfig.CANYON)) {
                    canyon.carve(random, sx, sz);
                }
            }
        }
        for (int sx = chunkX - r; sx <= chunkX + r; sx++) {
            for (int sz = chunkZ - r; sz <= chunkZ + r; sz++) {
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
    }
}
