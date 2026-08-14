package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.gen.BiomeCaneConfig;
import dev.drakou111.sugarcane.gen.CarverConfig;
import dev.drakou111.sugarcane.gen.CaveCarver;
import dev.drakou111.sugarcane.gen.OreBlob;
import dev.drakou111.sugarcane.gen.SugarCaneFeature;
import dev.drakou111.sugarcane.rng.JavaRandom;
import dev.drakou111.sugarcane.validate.BiomeSourceValidator;
import dev.drakou111.sugarcane.world.ArrayWorld;
import dev.drakou111.sugarcane.world.Blocks;
import kaptainwutax.biomeutils.source.OverworldBiomeSource;
import kaptainwutax.mcutils.block.Block;
import kaptainwutax.mcutils.version.MCVersion;
import kaptainwutax.terrainutils.TerrainGenerator;
import kaptainwutax.terrainutils.terrain.OverworldTerrainGenerator;

/**
 * End-to-end search: ocean prefilter, terrain, carvers, dirt blobs, sugar cane,
 * report anything taller than 4.
 *
 * <p>Pipeline order matches the game: noise and surface, then AIR carvers, then
 * LIQUID carvers, then UNDERGROUND_ORES (dirt blobs, step 6), then
 * VEGETAL_DECORATION (sugar cane, step 8).
 *
 * <p>Known gaps, all recall-only - they can lose finds but not invent them:
 * <ul>
 *   <li>canyon and underwater-canyon carvers are not implemented;</li>
 *   <li>lakes are not implemented;</li>
 *   <li>the dirt blob's feature index within UNDERGROUND_ORES is not yet
 *       extracted per biome, so blob <em>positions</em> are approximate. The
 *       cane RNG itself is exact.</li>
 * </ul>
 * Any hit must therefore be verified in a real client before being trusted.
 *
 * <p><b>Superseded by {@link RegionSearcher}.</b> This class predates the surface
 * builder, so its terrain has no cane soil in it and it finds almost nothing; it
 * also carves only the centre chunk, leaving the ±4 margin as raw noise, and it
 * runs one carver at a time over all start chunks rather than vanilla's
 * start-chunk-outer order. Kept only as the smaller, single-chunk reference
 * implementation of the pipeline.
 */
public final class Searcher {

    private static final int MARGIN = 4;
    private static final int WIN = 16 + 2 * MARGIN;
    private static final int SEA = 63;
    private static final int UNDERGROUND_ORES = 6;

    public static void main(String[] args) {
        long firstSeed = args.length > 0 ? Long.parseLong(args[0]) : 1L;
        long seeds = args.length > 1 ? Long.parseLong(args[1]) : 5L;
        int radius = args.length > 2 ? Integer.parseInt(args[2]) : 6;
        int report = args.length > 3 ? Integer.parseInt(args[3]) : 5;
        // Diagnostic: with the prefilter off, land chunks must produce cane at
        // roughly the rate real worlds do (~0.02 columns/chunk). If they do not,
        // the fault is in this pipeline, not in the ocean geometry being rare.
        boolean oceanOnly = args.length <= 4 || !args[4].equals("all");

        long chunks = 0, oceanChunks = 0, caneColumns = 0, dirtBlocks = 0, validSpots = 0;
        int tallest = 0;
        long start = System.currentTimeMillis();

        for (long seed = firstSeed; seed < firstSeed + seeds; seed++) {
            OverworldBiomeSource biomes = new OverworldBiomeSource(MCVersion.v1_16_1, seed);
            TerrainGenerator terrain = new OverworldTerrainGenerator(biomes);

            for (int cx = -radius; cx <= radius; cx++) {
                for (int cz = -radius; cz <= radius; cz++) {
                    chunks++;
                    int biome = biomes.getBiomeForNoiseGen(cx * 4 + 2, 0, cz * 4 + 2).getId();
                    // Ocean prefilter: only ocean biomes register liquid carvers,
                    // and every stackable spot ever observed was in one.
                    if (oceanOnly && !BiomeSourceValidator.isOcean(biome)) {
                        continue;
                    }
                    int count = BiomeCaneConfig.count(biome);
                    if (count == 0) {
                        continue;
                    }
                    oceanChunks++;
                    

                    ArrayWorld world = buildTerrain(terrain, cx, cz);
                    runCarvers(world, seed, cx, cz);
                    runDirtBlobs(world, terrain, seed, cx, cz);

                    // Diagnostic: is there any soil at all, and any position the
                    // cane feature could legally accept? Without these a search
                    // run cannot possibly hit.
                    for (int x = cx * 16; x < cx * 16 + 16; x++) {
                        for (int z = cz * 16; z < cz * 16 + 16; z++) {
                            for (int y = 1; y < SEA + 8; y++) {
                                byte b = world.getBlock(x, y, z);
                                if (b == Blocks.DIRT || b == Blocks.SAND) {
                                    dirtBlocks++;
                                }
                                if (SugarCaneFeature.canPlace(world, x, y, z)) {
                                    validSpots++;
                                }
                            }
                        }
                    }

                    JavaRandom random = new JavaRandom();
                    long decorationSeed = random.setDecorationSeed(seed, cx * 16, cz * 16);
                    for (SugarCaneFeature.Column c : SugarCaneFeature.place(
                            world, decorationSeed, BiomeCaneConfig.index(biome), count, cx, cz)) {
                        caneColumns++;
                        int h = world.caneRunThrough(c.x(), c.y(), c.z());
                        if (h > tallest) {
                            tallest = h;
                        }
                        if (h >= report) {
                            int by = c.y();
                            while (world.getBlock(c.x(), by - 1, c.z()) == Blocks.SUGAR_CANE) {
                                by--;
                            }
                            System.out.printf("HIT  seed %d  X=%d Y=%d Z=%d  height %d  biome %d%n",
                                    seed, c.x(), by, c.z(), h, biome);
                        }
                    }
                }
            }
        }

        long ms = System.currentTimeMillis() - start;
        System.out.printf("%nsoil blocks %d, legal cane spots %d%n", dirtBlocks, validSpots);
        System.out.printf("chunks %d, ocean %d, cane columns %d, tallest %d%n",
                chunks, oceanChunks, caneColumns, tallest);
        System.out.printf("%.0f chunks/s (%.0f ocean chunks/s)%n",
                chunks * 1000.0 / Math.max(1, ms), oceanChunks * 1000.0 / Math.max(1, ms));
    }

    private static ArrayWorld buildTerrain(TerrainGenerator terrain, int cx, int cz) {
        ArrayWorld world = new ArrayWorld(cx * 16 - MARGIN, cz * 16 - MARGIN, WIN, WIN);
        for (int wx = 0; wx < WIN; wx++) {
            for (int wz = 0; wz < WIN; wz++) {
                int x = cx * 16 - MARGIN + wx;
                int z = cz * 16 - MARGIN + wz;
                Block[] column = terrain.getColumnAt(x, z);
                for (int y = 0; y < 256 && y < column.length; y++) {
                    String name = column[y] == null ? "air" : column[y].getName();
                    byte b;
                    if (name.contains("water")) {
                        b = Blocks.WATER;
                    } else if (name.contains("air")) {
                        b = Blocks.AIR;
                    } else if (name.equals("sand")) {
                        b = Blocks.SAND;
                    } else if (name.contains("grass_block") || name.contains("dirt")) {
                        b = Blocks.DIRT;
                    } else {
                        b = Blocks.SOLID;
                    }
                    world.setBlock(x, y, z, b);
                }
            }
        }
        return world;
    }

    private static void runCarvers(ArrayWorld world, long seed, int cx, int cz) {
        CaveCarver.Target target = new CaveCarver.Target() {
            @Override
            public boolean canReplace(int x, int y, int z) {
                byte b = world.getBlock(x, y, z);
                return b == Blocks.SOLID || b == Blocks.DIRT || b == Blocks.SAND
                        || b == Blocks.GRASS_BLOCK || b == Blocks.COARSE_DIRT
                        || b == Blocks.PODZOL || b == Blocks.RED_SAND;
            }

            @Override
            public boolean isWater(int x, int y, int z) {
                return world.isWaterFluid(x, y, z);
            }

            @Override
            public boolean isAir(int x, int y, int z) {
                return world.isAir(x, y, z);
            }

            @Override
            public void setCaveAir(int x, int y, int z) {
                world.setBlock(x, y, z, Blocks.AIR);
            }

            @Override
            public void setWater(int x, int y, int z, boolean scheduleTick) {
                world.setBlock(x, y, z, Blocks.WATER);
            }
        };

        JavaRandom random = new JavaRandom();
        // AIR step: carver index 0 is the cave carver (index 1 is the canyon,
        // which is not implemented).
        CaveCarver air = new CaveCarver(target, cx, cz);
        for (int sx = cx - CarverConfig.CARVE_RADIUS; sx <= cx + CarverConfig.CARVE_RADIUS; sx++) {
            for (int sz = cz - CarverConfig.CARVE_RADIUS; sz <= cz + CarverConfig.CARVE_RADIUS; sz++) {
                if (CarverConfig.isStartChunk(random, seed, 0, sx, sz, CarverConfig.CAVE_OCEAN)) {
                    air.carve(random, sx, sz);
                }
            }
        }
        // LIQUID step: index 1 is the underwater cave (index 0 is its canyon).
        // UnderwaterCaveWorldCarver widens replaceableBlocks to include AIR,
        // CAVE_AIR, WATER, LAVA, OBSIDIAN and PACKED_ICE, so unlike the land
        // carver it tunnels straight THROUGH an existing cave. Reusing the land
        // carver's target here makes the water stop at cave boundaries and land
        // a block or two short of where vanilla puts it.
        CaveCarver.Target liquidTarget = new CaveCarver.Target() {
            @Override
            public boolean canReplace(int x, int y, int z) {
                byte b = world.getBlock(x, y, z);
                return b != Blocks.SUGAR_CANE;   // everything else is replaceable
            }

            @Override
            public boolean isWater(int x, int y, int z) {
                return world.isWaterFluid(x, y, z);
            }

            @Override
            public boolean isAir(int x, int y, int z) {
                return world.isAir(x, y, z);
            }

            @Override
            public void setCaveAir(int x, int y, int z) {
                world.setBlock(x, y, z, Blocks.AIR);
            }

            @Override
            public void setWater(int x, int y, int z, boolean scheduleTick) {
                world.setBlock(x, y, z, Blocks.WATER);
            }
        };
        CaveCarver liquid = new CaveCarver(liquidTarget, cx, cz, true, SEA);
        for (int sx = cx - CarverConfig.CARVE_RADIUS; sx <= cx + CarverConfig.CARVE_RADIUS; sx++) {
            for (int sz = cz - CarverConfig.CARVE_RADIUS; sz <= cz + CarverConfig.CARVE_RADIUS; sz++) {
                if (CarverConfig.isStartChunk(random, seed, 1, sx, sz, CarverConfig.UNDERWATER_CAVE)) {
                    liquid.carve(random, sx, sz);
                }
            }
        }
    }

    static class Point {
        int x;
        int y;
        int z;

        public Point(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    static Point runDirtBlobsTest(long decorationSeed) {
        JavaRandom random = new JavaRandom();
        random.setFeatureSeed(decorationSeed, 0, UNDERGROUND_ORES);
        int x = random.nextInt(16);
        int z = random.nextInt(16);
        int y = random.nextInt(256);
        return new Point(x, y, z);
    }

    static void runDirtBlobs(ArrayWorld world, TerrainGenerator terrain,
                             long seed, int cx, int cz) {
        OreBlob blob = new OreBlob(new OreBlob.Target() {
            @Override
            public boolean isNaturalStone(int x, int y, int z) {
                return world.getBlock(x, y, z) == Blocks.SOLID;
            }

            @Override
            public void setDirt(int x, int y, int z) {
                world.setBlock(x, y, z, Blocks.DIRT);
            }

            @Override
            public int oceanFloorHeight(int x, int z) {
                return terrain.getHeightOnGround(x, z);
            }
        }, OreBlob.DIRT_SIZE);

        JavaRandom random = new JavaRandom();
        long decorationSeed = random.setDecorationSeed(seed, cx * 16, cz * 16);
        // TODO: the dirt ore's index within UNDERGROUND_ORES varies by biome and
        // has not been extracted yet; 0 is a placeholder, so blob positions are
        // approximate. This affects recall only.
        random.setFeatureSeed(decorationSeed, 0, UNDERGROUND_ORES);
        for (int i = 0; i < OreBlob.DIRT_COUNT; i++) {
            int x = cx * 16 + random.nextInt(16);
            int z = cz * 16 + random.nextInt(16);
            int y = random.nextInt(256);
            blob.place(random, x, y, z);
        }
    }
}
