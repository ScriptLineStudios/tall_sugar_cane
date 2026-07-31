package dev.drakou111.sugarcane.gen;

import dev.drakou111.sugarcane.rng.JavaRandom;
import dev.drakou111.sugarcane.world.BlockView;
import dev.drakou111.sugarcane.world.Blocks;

/**
 * 1.16.1 {@code ChunkGenerator.buildSurfaceAndBedrock} plus
 * {@code DefaultSurfaceBuilder}, which is what turns the raw noise terrain
 * (stone, water, air) into blocks that sugar cane can stand on.
 *
 * <p>This is the piece TerrainUtils does not provide: its
 * {@code OverworldTerrainGenerator} returns the noise density field only, so
 * every solid block comes back as stone and no cane can ever be placed.
 *
 * <p>Three things have to be right:
 * <ul>
 *   <li>the RNG is seeded once per chunk with
 *       {@code setBaseChunkSeed(chunkX, chunkZ)} and then shared by all 256
 *       columns, which are visited x-outer, z-inner. One column consuming the
 *       wrong number of draws corrupts every column after it;</li>
 *   <li>the surface noise is {@code getSurfaceNoiseValue(x/16, z/16, ...) * 15},
 *       sampled from the generator's own {@code surfaceDepthNoise} — the
 *       simplex sampler built between the main and the depth noise, so it cannot
 *       be reconstructed independently without redoing that RNG chain;</li>
 *   <li>the column is walked from {@code WORLD_SURFACE_WG + 1} downwards, and
 *       water neither resets the run nor is written over: only blocks equal to
 *       the default block (stone) are touched.</li>
 * </ul>
 */
public final class SurfaceBuilder {

    /** Per-column inputs the builder cannot compute itself. */
    public interface Context {
        /**
         * {@code chunkAccess.getHeight(WORLD_SURFACE_WG, x, z) + 1}: one above the
         * highest non-air block of the noise terrain, water included.
         */
        int surfaceStart(int x, int z);

        /** {@code surfaceNoise.getSurfaceNoiseValue(x/16, z/16, 1/16, localX/16) * 15}. */
        double noise(int x, int z, int localX);

        /**
         * The per-block biome id, {@code FuzzyOffsetConstantColumnBiomeZoomer} —
         * i.e. the Voronoi-fuzzed biome at y=0, not the quart-resolution noise
         * biome. This is what {@code WorldGenRegion.getBiome} returns.
         */
        int biome(int x, int z);
    }

    private static final int SEA_LEVEL = 63;

    private SurfaceBuilder() {
    }

    /** Runs the surface builder over one whole chunk, in vanilla's column order. */
    public static void buildChunk(BlockView world, int chunkX, int chunkZ, Context context) {
        JavaRandom random = new JavaRandom();
        random.setBaseChunkSeed(chunkX, chunkZ);
        int originX = chunkX << 4;
        int originZ = chunkZ << 4;

        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int x = originX + localX;
                int z = originZ + localZ;
                int start = context.surfaceStart(x, z);
                double noise = context.noise(x, z, localX);
                apply(world, random, context.biome(x, z), x, z, start, noise);
            }
        }
    }

    /**
     * Dispatches to the biome's surface builder. Every supported one funnels into
     * {@link #applyDefault}; they differ only in which configuration they pick
     * from the noise, so they all consume the RNG identically.
     */
    public static void apply(BlockView world, JavaRandom random, int biome,
                             int x, int z, int start, double noise) {
        SurfaceConfig.Config config = SurfaceConfig.config(biome);
        switch (SurfaceConfig.kind(biome)) {
            case DEFAULT -> {
            }
            case MOUNTAIN -> config = noise > 1.0 ? SurfaceConfig.STONE : SurfaceConfig.GRASS;
            case GRAVELLY_MOUNTAIN -> config = noise < -1.0 || noise > 2.0 ? SurfaceConfig.GRAVEL_CFG
                    : noise > 1.0 ? SurfaceConfig.STONE : SurfaceConfig.GRASS;
            case GIANT_TREE_TAIGA -> config = noise > 1.75 ? SurfaceConfig.COARSE_DIRT
                    : noise > -0.95 ? SurfaceConfig.PODZOL : SurfaceConfig.GRASS;
            case SHATTERED_SAVANNA -> config = noise > 1.75 ? SurfaceConfig.STONE
                    : noise > -0.5 ? SurfaceConfig.COARSE_DIRT : SurfaceConfig.GRASS;
            case UNSUPPORTED -> throw new IllegalStateException(
                    "surface builder for biome " + biome + " is not implemented");
        }
        applyDefault(world, random, biome, x, z, start, noise, config);
    }

    /**
     * {@code DefaultSurfaceBuilder.apply}. {@code top} and {@code under} are
     * carried down the column and mutate as the walk crosses sea level and the
     * bottom of the surface band, which is what gives one gravel block on a deep
     * sea floor but a dirt band on a shallow one.
     */
    private static void applyDefault(BlockView world, JavaRandom random, int biome,
                                     int x, int z, int start, double noise,
                                     SurfaceConfig.Config config) {
        byte top = config.top();
        byte under = config.under();
        int surfaceDepth = -1;
        int depth = (int) (noise / 3.0 + 3.0 + random.nextDouble() * 0.25);

        for (int y = start; y >= 0; y--) {
            byte block = world.getBlock(x, y, z);
            if (block == Blocks.AIR) {
                surfaceDepth = -1;
                continue;
            }
            // Only the default block is surfaced over. Water is left alone and,
            // crucially, does not reset the run.
            if (block != Blocks.SOLID) {
                continue;
            }
            if (surfaceDepth == -1) {
                if (depth <= 0) {
                    top = Blocks.AIR;
                    under = Blocks.SOLID;
                } else if (y >= SEA_LEVEL - 4 && y <= SEA_LEVEL + 1) {
                    top = config.top();
                    under = config.under();
                }
                if (y < SEA_LEVEL && top == Blocks.AIR) {
                    top = SurfaceConfig.temperature(biome) < 0.15f ? Blocks.ICE : Blocks.WATER;
                }
                surfaceDepth = depth;
                if (y >= SEA_LEVEL - 1) {
                    world.setBlock(x, y, z, top);
                } else if (y < SEA_LEVEL - 7 - depth) {
                    top = Blocks.AIR;
                    under = Blocks.SOLID;
                    world.setBlock(x, y, z, config.underwater());
                } else {
                    world.setBlock(x, y, z, under);
                }
                continue;
            }
            if (surfaceDepth <= 0) {
                continue;
            }
            world.setBlock(x, y, z, under);
            if (--surfaceDepth == 0 && (under == Blocks.SAND || under == Blocks.RED_SAND) && depth > 1) {
                // Sand turns to sandstone below the band, and the extra thickness
                // costs a draw from the shared chunk RNG.
                surfaceDepth = random.nextInt(4) + Math.max(0, y - 63);
                under = Blocks.SANDSTONE;
            }
        }
    }
}
