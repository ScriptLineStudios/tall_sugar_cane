package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.gen.BiomeCaneConfig;
import dev.drakou111.sugarcane.gen.BiomeIds;
import dev.drakou111.sugarcane.gen.CanyonCarver;
import dev.drakou111.sugarcane.gen.Carver;
import dev.drakou111.sugarcane.gen.CarverConfig;
import dev.drakou111.sugarcane.gen.Disk;
import dev.drakou111.sugarcane.gen.OreBlob;
import dev.drakou111.sugarcane.gen.SugarCaneFeature;
import dev.drakou111.sugarcane.gen.SurfaceBuilder;
import dev.drakou111.sugarcane.gen.SurfaceConfig;
import dev.drakou111.sugarcane.gen.Terrain;
import dev.drakou111.sugarcane.rng.JavaRandom;
import dev.drakou111.sugarcane.world.ArrayWorld;
import dev.drakou111.sugarcane.world.Blocks;
import kaptainwutax.biomeutils.biome.Biomes;
import kaptainwutax.biomeutils.source.OverworldBiomeSource;
import kaptainwutax.mcutils.version.MCVersion;

import java.util.BitSet;
import java.util.HashMap;

public final class RegionSearcher {
    // world size
    public static final int WORLD_SIZE_X = 1;
    public static final int WORLD_SIZE_Z = 2;

    static final int SEA = 63;
    static final int CHUNK = 16;
    static final int MAX_REGION = 32;

    /**
     * Region side for a search box of the given chunk radius: big enough that the
     * whole box lands in the interior, since border chunks are never searched.
     */
    static int regionFor(int radius) {
        return Math.min(MAX_REGION, Math.max(6, 2 * radius + 3));
    }

    /**
     * Set by {@link Inspect} to dump the placement trace for one chunk. The RNG
     * stream only parts company with the terrain at a successful placement, so the
     * trace is what shows where a simulated chunk diverged from the real one.
     */
    static int traceChunkX = Integer.MIN_VALUE;
    static int traceChunkZ = Integer.MIN_VALUE;

    private RegionSearcher() {
    }


    /** One thread's worth of reusable buffers. */
    static final class Worker {
        private final int report;
        /** Region side for this worker, and the radius that bounds a reportable find. */
        private final int region;
        final ArrayWorld world;
        private final int[] biomeMap;
        private final byte[] column = new byte[ArrayWorld.HEIGHT];
        private final short[] surfaceStart = new short[CHUNK * CHUNK];

        private final JavaRandom random = new JavaRandom();
        private final BitSet airMask = new BitSet(65536);
        private final BitSet liquidMask = new BitSet(65536);

        private OverworldBiomeSource biomes;
        private Terrain terrain;
        private long seed;
        private int regionChunkX;
        private int regionChunkZ;

        Worker(int report, int radius) {
            this.report = report;
            this.region = regionFor(radius);
            this.world = new ArrayWorld(0, 0, WORLD_SIZE_X * CHUNK, WORLD_SIZE_Z * CHUNK);
            this.biomeMap = new int[region * CHUNK * region * CHUNK];
        }

        /** Prepares this worker for a seed without searching anything yet. */
        void prepare(long seed) {
            this.seed = seed;
            this.biomes = new OverworldBiomeSource(MCVersion.v1_16_1, seed);
            dev.drakou111.sugarcane.gen.LayerCaches.enlarge(this.biomes);
            this.terrain = new Terrain(biomes);
        }

        void searchRegion2(int chunkX0, int chunkZ0, int targetChunkX, int targetChunkZ, long worldSeed) {
            regionChunkX = chunkX0;
            regionChunkZ = chunkZ0;

            world.reset(chunkX0 * CHUNK, chunkZ0 * CHUNK);
            terrain.beginRegion(chunkX0 * CHUNK, chunkZ0 * CHUNK, region * CHUNK);

            // Z + 1 is the "base chunk" whereas Z is the 16 tall chunk
//            buildChunk(targetChunkX, targetChunkZ + 1);
//            runCarvers(targetChunkX, targetChunkZ + 1);

//            for (int dx = 0; dx <= 1; dx++) {
//                for (int dz = 0; dz <= 1; dz++) {
//                    buildChunk(targetChunkX + dx, targetChunkZ + dz);
//                }
//            }

            //targetChunkX += 1;

            // 19 chunk
//            buildChunk(targetChunkX, targetChunkZ);
//            runCarvers(targetChunkX, targetChunkZ);
//            decorate(targetChunkX, targetChunkZ, worldSeed, true);

            // dirt chunk
            buildChunk(targetChunkX, targetChunkZ + 1);
            runCarvers(targetChunkX, targetChunkZ + 1);
            decorate(targetChunkX, targetChunkZ + 1, worldSeed, true);

//            HashMap<Byte, String> blockSymbols = new HashMap<>() {{
//                put(Blocks.AIR, " ");
//                put(Blocks.SOLID, "#");
//                put(Blocks.WATER, "~");
//                put(Blocks.GRAVEL, "&");
//                put(Blocks.SAND, "&");
//                put(Blocks.FLOWING_WATER, "~");
//            }};
//
//            int y = 27;
//            for (int z = targetChunkZ * 16; z < targetChunkZ * 16 + 32; z++) {
//                for (int x = targetChunkX * 16; x < targetChunkX * 16 + 16; x++) {
//                    System.out.print(blockSymbols.get(world.getBlock(x, y, z)));
//                }
//                System.out.println();
//            }
//            System.out.printf("/tp %d %d %d\n", targetChunkX * 16, y, targetChunkZ * 16);

            // dirt chunk decorate
        }

        private int mapIndex(int x, int z) {
            int lx = x - regionChunkX * CHUNK;
            int lz = z - regionChunkZ * CHUNK;
            return lx * region * CHUNK + lz;
        }

        private int biomeAt(int x, int z) {
            return biomeMap[mapIndex(x, z)];
        }

        private void buildChunk(int chunkX, int chunkZ) {
            int originX = chunkX * CHUNK;
            int originZ = chunkZ * CHUNK;
            for (int x = 0; x < CHUNK; x++) {
                for (int z = 0; z < CHUNK; z++) {
                    int height = terrain.column(originX + x, originZ + z, column);
                    world.setNoiseColumn(originX + x, originZ + z, column, height);
                    surfaceStart[x * CHUNK + z] = (short) (height + 1);
                }
            }
            SurfaceBuilder.buildChunk(world, chunkX, chunkZ, new SurfaceBuilder.Context() {
                @Override
                public int surfaceStart(int x, int z) {
                    return RegionSearcher.Worker.this.surfaceStart[(x - originX) * CHUNK + (z - originZ)];
                }

                @Override
                public double noise(int x, int z, int localX) {
                    return terrain.surfaceNoise(x, z, localX);
                }

                @Override
                public int biome(int x, int z) {
                    return biomeAt(x, z);
                }
            });
        }

        private void runCarvers(int chunkX, int chunkZ) {
            boolean ocean = true;

            Carver.Target airTarget = new Carver.Target() {
                @Override
                public boolean canReplace(int x, int y, int z) {
                    return Blocks.isCarverReplaceable(world.getBlock(x, y, z),
                            world.getBlock(x, y + 1, z));
                }

                @Override
                public boolean isGrassLike(int x, int y, int z) {
                    // Mycelium also counts in vanilla, but the reduced palette
                    // folds it into SOLID; mushroom islands are not the target.
                    return world.getBlock(x, y, z) == Blocks.GRASS_BLOCK;
                }

                @Override
                public void convertDirtToTopMaterial(int x, int y, int z) {
                    if (world.getBlock(x, y, z) == Blocks.DIRT) {
                        world.setBlock(x, y, z, SurfaceConfig.config(biomeAt(x, z)).top());
                    }
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
            // The underwater carvers widen replaceableBlocks to almost everything
            // - air and water included - so they tunnel straight through an
            // existing cave, and they are what leaves a tall water face beside
            // one. Plain ice is not in either set.
            Carver.Target liquidTarget = new Carver.Target() {
                @Override
                public boolean canReplace(int x, int y, int z) {
                    byte b = world.getBlock(x, y, z);
                    return b != Blocks.SUGAR_CANE && b != Blocks.ICE;
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

            // One carving mask per generation step, shared by both carvers of that
            // step, so whichever reaches a block first owns it. That makes the
            // iteration order matter: vanilla walks start chunks on the outside
            // and the biome's carver list on the inside.
            airMask.clear();
            liquidMask.clear();
            Carver canyon = new CanyonCarver(airTarget, chunkX, chunkZ, false, SEA, airMask);
            Carver underwaterCanyon =
                    new CanyonCarver(liquidTarget, chunkX, chunkZ, true, SEA, liquidMask);

            for (int sx = chunkX - CarverConfig.CARVE_RADIUS; sx <= chunkX + CarverConfig.CARVE_RADIUS; sx++) {
                for (int sz = chunkZ - CarverConfig.CARVE_RADIUS; sz <= chunkZ + CarverConfig.CARVE_RADIUS; sz++) {
                    if (CarverConfig.isStartChunk(random, seed, 1, sx, sz, CarverConfig.CANYON)) {
                        canyon.carve(random, sx, sz);
                    }
                }
            }
            if (!ocean) {
                return;
            }
//            System.out.println("==================");
            for (int sx = chunkX - CarverConfig.CARVE_RADIUS; sx <= chunkX + CarverConfig.CARVE_RADIUS; sx++) {
                for (int sz = chunkZ - CarverConfig.CARVE_RADIUS; sz <= chunkZ + CarverConfig.CARVE_RADIUS; sz++) {
                    if (CarverConfig.isStartChunk(random, seed, 0, sx, sz, CarverConfig.UNDERWATER_CANYON)) {
                        underwaterCanyon.carve(random, sx, sz);
                    }
                }
            }
        }

        private void decorate(int chunkX, int chunkZ, long worldSeed, boolean lazy) {
            world.setDecoratingChunk(chunkX, chunkZ);
            long decorationSeed = random.setDecorationSeed(seed, chunkX * CHUNK, chunkZ * CHUNK);
            if (lazy) {
//                System.out.printf("%d %d -> %d\n", chunkX, chunkZ, decorationSeed & ((1L << 48)-1));
            }
            runDirtBlobs(decorationSeed, chunkX, chunkZ);
            //runDisks(decorationSeed, chunkX, chunkZ);

            /*
            chunk: 143, 30 -> (2288, 480)
             */

            int biome = BiomeIds.noiseGen(biomes, chunkX * 4 + 2, chunkZ * 4 + 2);
            int count = BiomeCaneConfig.count(biome);
            int index = BiomeCaneConfig.index(biome);
            java.util.List<String> trace =
                    chunkX == traceChunkX && chunkZ == traceChunkZ
                            ? new java.util.ArrayList<>() : null;

            for (SugarCaneFeature.Column c : SugarCaneFeature.place(world, decorationSeed, index, count, chunkX, chunkZ, true, trace, chunkX, chunkZ, worldSeed, lazy)) {
                int height = world.caneRunThrough(c.x(), c.y(), c.z());

                if (height >= report) {
                    int base = c.y();
                    while (world.getBlock(c.x(), base - 1, c.z()) == Blocks.SUGAR_CANE) {
                        base--;
                    }
                }
            }
        }

        private void runDisks(long decorationSeed, int chunkX, int chunkZ) {
            Disk.OceanFloor floor = world::getHeightOceanFloor;
            Disk.place(world, random, decorationSeed, Disk.INDEX_SAND, Disk.SAND,
                    chunkX, chunkZ, floor);
            Disk.place(world, random, decorationSeed, Disk.INDEX_CLAY, Disk.CLAY,
                    chunkX, chunkZ, floor);
            Disk.place(world, random, decorationSeed, Disk.INDEX_GRAVEL, Disk.GRAVEL,
                    chunkX, chunkZ, floor);
        }

        private void runDirtBlobs(long decorationSeed, int chunkX, int chunkZ) {
            boolean target = chunkX == 1562154 && chunkZ == -477569;
            target = false;

            if (target) {
                System.out.printf("==== %d %d ====\n", chunkX, chunkZ);
            }

            OreBlob blob = new OreBlob(chunkX, chunkZ, new OreBlob.Target() {
                @Override
                public boolean isNaturalStone(int x, int y, int z) {
                    // NATURAL_STONE is stone, granite, diorite and andesite; the
                    // reduced palette has them all as SOLID, along with a few
                    // blocks that are not (sandstone, terracotta). Those only
                    // occur at the surface, where blobs rarely land.
                    return world.getBlock(x, y, z) == Blocks.SOLID;
                }

                @Override
                public void setDirt(int x, int y, int z) {
                    world.setBlock(x, y, z, Blocks.DIRT);
                }

                @Override
                public int oceanFloorHeight(int x, int z) {
                    return world.getHeightOceanFloor(x, z);
                }
            }, OreBlob.DIRT_SIZE);

            // ORE_DIRT is the first feature of UNDERGROUND_ORES for every
            // overworld biome (addDefaultUndergroundVariety runs before
            // addDefaultOres), and no structure generates in that step, so the
            // feature index is 0.
            random.setFeatureSeed(decorationSeed, 0, 6);
            for (int i = 0; i < OreBlob.DIRT_COUNT; i++) {
                int x = chunkX * CHUNK + random.nextInt(CHUNK);
                int z = chunkZ * CHUNK + random.nextInt(CHUNK);
                int y = random.nextInt(256);
//                if (target) {
//                System.out.printf("location: %d %d %d\n", x, y, z);
//                }
                blob.place(random, x, y, z);
            }
            if (target) {
                System.out.printf("END ==== %d %d ====\n", chunkX, chunkZ);
            }
        }
    }
}
