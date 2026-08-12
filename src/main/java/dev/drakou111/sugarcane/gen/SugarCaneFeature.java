package dev.drakou111.sugarcane.gen;

import dev.drakou111.sugarcane.rng.JavaRandom;
import dev.drakou111.sugarcane.world.BlockView;
import dev.drakou111.sugarcane.world.Blocks;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bit-exact simulation of Minecraft 1.16.1's {@code patch_sugar_cane}:
 *
 * <pre>
 * Feature.RANDOM_PATCH
 *     .configured(SUGAR_CANE_CONFIG)              // ColumnPlacer(2,2), tries 20,
 *                                                 // xspread 4, yspread 0, zspread 4,
 *                                                 // noProjection, needWater
 *     .decorated(COUNT_HEIGHTMAP_DOUBLE(count))   // count = 10 / 13 badlands
 *                                                 //       / 20 swamp / 60 desert
 * </pre>
 *
 * placed at {@code GenerationStep.Decoration.VEGETAL_DECORATION}.
 *
 * <p>Two properties of this version make taller-than-4 cane possible at all, and
 * both were removed in 1.18+:
 * <ul>
 *   <li>{@code COUNT_HEIGHTMAP_DOUBLE} runs the placement {@code count} times
 *       <em>independently</em>, each with its own XZ and its own
 *       {@code nextInt(2 * heightmap)} Y — so Y is not pinned to the surface;</li>
 *   <li>the decorator stream is lazy, so invocation <i>n</i> sees the blocks
 *       placed by invocations {@code < n}. That is what lets one column land on
 *       top of another, within a single chunk and therefore deterministically.</li>
 * </ul>
 *
 * <p>The binding constraint is {@code needWater}: it is checked against the four
 * horizontal neighbours of the block <em>below</em> the base. For a column
 * stacking onto another, that is the level of the lower column's top block.
 */
public final class SugarCaneFeature {

    /** Ordinal of GenerationStep.Decoration.VEGETAL_DECORATION in 1.16.1. */
    public static final int VEGETAL_DECORATION = 8;

    public static final int COUNT_DEFAULT = 10;
    public static final int COUNT_BADLANDS = 13;
    public static final int COUNT_SWAMP = 20;
    public static final int COUNT_DESERT = 60;

    private static final int TRIES = 20;
    private static final int XZ_SPREAD = 4;
    private static final int Y_SPREAD = 0;
    private static final int MIN_SIZE = 2;
    private static final int EXTRA_SIZE = 2;

    private SugarCaneFeature() {
    }

    /** A single placed column. */
    public record Column(int x, int y, int z, int height, int invocation) {
    }

    /**
     * Runs the feature for one chunk, mutating {@code world}.
     *
     * @param decorationSeed population seed, from {@link JavaRandom#setDecorationSeed}
     * @param featureIndex   index of patch_sugar_cane within VEGETAL_DECORATION for the biome
     * @param count          invocations per chunk (see COUNT_* constants)
     * @return every column placed, in placement order
     */
    public static List<Column> place(BlockView world, long decorationSeed, int featureIndex,
                                     int count, int chunkX, int chunkZ) {
        return place(world, decorationSeed, featureIndex, count, chunkX, chunkZ, true);
    }

    /**
     * @param requireWater when false, the needWater check and canSurvive's water
     *                     requirement are skipped. Only useful for isolating the
     *                     placement RNG from the terrain condition - real
     *                     worldgen always requires water.
     */
    public static List<Column> place(BlockView world, long decorationSeed, int featureIndex,
                                     int count, int chunkX, int chunkZ, boolean requireWater) {
        return place(world, decorationSeed, featureIndex, count, chunkX, chunkZ, requireWater, null, 0, 0, 0, false);
    }

    /**
     * @param trace if non-null, receives one line per invocation origin and per
     *              successful placement. The RNG stream only diverges from the
     *              terrain at a <em>success</em> (ColumnPlacer draws two values
     *              then), so a trace is what identifies where a simulated chunk
     *              parted company with the real one.
     */
    public static List<Column> place(BlockView world, long decorationSeed, int featureIndex,
                                     int count, int chunkX, int chunkZ, boolean requireWater,
                                     List<String> trace, int targetChunkX, int targetChunkZ, long worldSeed, boolean flag) {
        List<Column> placed = new ArrayList<>();
        JavaRandom random = new JavaRandom();
        random.setFeatureSeed(decorationSeed, featureIndex, VEGETAL_DECORATION);
        int originX = chunkX << 4;
        int originZ = chunkZ << 4;

//        System.out.printf("CHUNK START: %d %d featureIndex = %d\n", chunkX, chunkZ, featureIndex);

        boolean target = chunkX == targetChunkX && chunkZ == targetChunkZ;
//        target = false;

//        if (target) {
//            System.out.printf("SUGAR START: %d %d\n", chunkX, chunkZ);
//        }
        for (int n = 0; n < count; n++) {
            // CountHeighmapDoubleDecorator: x is drawn before z, then Y from the
            // doubled heightmap. A zero-height column yields null and is filtered
            // out without drawing Y.
            int dx = random.nextInt(16);
            int dz = random.nextInt(16);
            int x = dx + originX;
            int z = dz + originZ;
//            if (target) {
//                System.out.printf("invocation: %d %d\n", x, z);
//            }
            // WorldGenRegion.getHeight adds one back to ChunkAccess.getHeight, so
            // the decorator sees the heightmap's firstAvailable value - 63 over open
            // ocean, not 62. Checked against the real game: this convention
            // reproduces 54 of 105 chunks, the off-by-one reproduces 6.
            int doubled = world.getHeightMotionBlocking(x, z) * 2;
//            System.out.println(doubled);
            if (doubled <= 0) {
                continue;
            }

//            System.out.print("BOUND: %d\n", doubled);
            int y = random.nextInt(doubled);
//            System.out.printf("dx: %d dy: %d dz: %d bound: %d\n", dx, y, dz, doubled);
//            System.out.printf("height = %d\n", doubled / 2);
            if (trace != null) {
                trace.add(String.format("invocation %d: origin %d,%d y=%d (heightmap %d)",
                        n, x, z, y, doubled / 2));
            }
            // RandomPatchFeature.place
            for (int i = 0; i < TRIES; i++) {
                int ox = random.nextInt(XZ_SPREAD + 1) - random.nextInt(XZ_SPREAD + 1);
                int oy = random.nextInt(Y_SPREAD + 1) - random.nextInt(Y_SPREAD + 1);
                int oz = random.nextInt(XZ_SPREAD + 1) - random.nextInt(XZ_SPREAD + 1);

                int px = x + ox;
                int py = y + oy;
                int pz = z + oz;

//                if (n == 2 && i == 0) {
//                    System.out.printf("%d %d %d\n", px, py, pz);
//                }
//                if (i != 1 || !(n <= 2)) {

//                boolean place = (n == 3 && i == 11) || (n == 4 && i == 5) || (n == 6 && i == 14) || (n == 0 && i == 0);
//                boolean place = (n == 0 && i == 1) || (n == 4 && i == 4) || (n == 5 && i == 10) || (n == 8 && i == 4);
//                boolean place = (n == 0 && i == 2) || (n == 2 && i == 11) || (n == 3 && i == 10) || (n == 7 && i == 19);
//                if (!place) {
//                boolean place = (n == 7 && i == 17);
//                if (!place) {
//                    continue;
//                }

                int modX = px - (chunkX << 4);
                int modZ = pz - (chunkZ << 4);

                if (!canPlace(world, px, py, pz, requireWater, i, n)) {
                    continue;
                }

//                if (flag) {
//                    if (px == -15513917 && pz == 16281294) {
//                        System.out.printf("attemping to place at %d %d %d\n", px, py, pz);
//                    }
//                    if (!canPlace(world, px, py, pz, requireWater, i, n)) {
//                        continue;
//                    }
//                }
//                else {
//                    if (!(modX == 3 && modZ == -2)) {
//                        continue;
//                    }
//                }

//                byte below = world.getBlock(px, py - 1, pz);
//                if (Blocks.isCaneSoil(below) && hasWaterBeside(world, px, py - 1, pz)) {
//                    System.out.printf("%d %d %d %d\n", worldSeed, px, py, pz);
//                }
//                if (below != Blocks.SUGAR_CANE) {
//                    if (!(Blocks.isCaneSoil(below)

//                if (target && i == 0 && n == 0) {
//                }

                // ColumnPlacer.place — note it writes upward unconditionally,
                // overwriting whatever is there.
                int height = MIN_SIZE + random.nextInt(random.nextInt(EXTRA_SIZE + 1) + 1);
                if (modX < 0 || modZ < 0) {
//                    System.out.printf("placing sugarcane at: %d %d %d height: %d\n", px, py, pz, height);
                }
//                System.out.printf("%d %d -> %d %d %d %d %d\n", n, i, height, dx, dz, ox, oz);
//                System.out.printf("px: %d py: %d pz: %d height: %d\n", px, py, pz, height);
                for (int k = 0; k < height; k++) {
                    world.setBlock(px, py + k, pz, Blocks.SUGAR_CANE);
                }
                if (trace != null) {
                    trace.add(String.format("    try %d PLACED %d,%d,%d height %d", i, px, py, pz, height));
                }
                placed.add(new Column(px, py, pz, height, n));
            }
//            System.out.println("================");
        }
        if (target) {
//            System.out.printf("SUGAR END: %d %d\n", chunkX, chunkZ);
        }
//        System.out.println("CHUNK END");
        return placed;
    }

    /**
     * The full 1.16.1 predicate: air at the target, {@code SugarCaneBlock.canSurvive},
     * and {@code needWater}. Whitelist and blacklist are empty for this config and
     * {@code canReplace} is false.
     */
    public static boolean canPlace(BlockView world, int x, int y, int z) {
        return canPlace(world, x, y, z, true, 0, 0);
    }

    public static boolean canPlace(BlockView world, int x, int y, int z, boolean requireWater, int i, int n) {
        if (!world.isAir(x, y, z)) {
//            if (n == 1 && i == 5) {
//                byte below = world.getBlock(x, y - 1, z);
//                return below == Blocks.SUGAR_CANE;
//            }
//            if (n == 1 && i == 5) {
//                System.out.printf("failed because air => %s\n", world.getBlock(x, y, z));
//            }

            return false;
        }
        byte below = world.getBlock(x, y - 1, z);
        if (below != Blocks.SUGAR_CANE) {
            if (!(Blocks.isCaneSoil(below))) {
                return false;
            }
            if (requireWater && !canSurvive(world, x, y, z)) {
                return false;
            }
        }
//        return true;
        return !requireWater || hasWaterBeside(world, x, y - 1, z);
    }

    /** {@code SugarCaneBlock.canSurvive} */
    public static boolean canSurvive(BlockView world, int x, int y, int z) {
        byte below = world.getBlock(x, y - 1, z);
        if (below == Blocks.SUGAR_CANE) {
            return true;
        }
        if (!Blocks.isCaneSoil(below)) {
            return false;
        }
        // Water or frosted ice beside the soil. (needWater, checked separately,
        // accepts only water — so frosted ice alone is never enough here.)
        return hasWaterBeside(world, x, y - 1, z)
                || world.getBlock(x + 1, y - 1, z) == Blocks.FROSTED_ICE
                || world.getBlock(x - 1, y - 1, z) == Blocks.FROSTED_ICE
                || world.getBlock(x, y - 1, z + 1) == Blocks.FROSTED_ICE
                || world.getBlock(x, y - 1, z - 1) == Blocks.FROSTED_ICE;
    }

    /** Water fluid in any of the four horizontal neighbours of (x, y, z). */
    public static boolean hasWaterBeside(BlockView world, int x, int y, int z) {
        return world.isWaterFluid(x - 1, y, z)
                || world.isWaterFluid(x + 1, y, z)
                || world.isWaterFluid(x, y, z - 1)
                || world.isWaterFluid(x, y, z + 1);
    }
}
