package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.gen.BiomeCaneConfig;
import dev.drakou111.sugarcane.gen.SugarCaneFeature;
import dev.drakou111.sugarcane.rng.JavaRandom;
import dev.drakou111.sugarcane.world.ArrayWorld;
import dev.drakou111.sugarcane.world.Blocks;
import kaptainwutax.biomeutils.source.OverworldBiomeSource;
import kaptainwutax.mcutils.block.Block;
import kaptainwutax.mcutils.version.MCVersion;
import kaptainwutax.terrainutils.TerrainGenerator;
import kaptainwutax.terrainutils.terrain.OverworldTerrainGenerator;

/**
 * Finds a chunk whose placement RNG stacks sugar cane to a target height when
 * the water requirement is ignored.
 *
 * <p>This isolates the RNG half of the problem: real worldgen also needs water
 * beside the soil and again beside the lower column's top, so cane will NOT
 * actually be this tall at the reported spot. It is a way to see the stacking
 * machinery fire on real terrain, and a concrete case to check the simulator
 * against.
 *
 * <p>Terrain comes from the noise generator only - no carvers, lakes or ores -
 * so the surface is right but anything underground is not.
 */
public final class SevenTallFinder {

    private static final int MARGIN = 4;
    private static final int WIN = 16 + 2 * MARGIN;

    public static void main(String[] args) {
        int target = args.length > 0 ? Integer.parseInt(args[0]) : 7;
        int radius = args.length > 1 ? Integer.parseInt(args[1]) : 12;
        long firstSeed = args.length > 2 ? Long.parseLong(args[2]) : 1L;
        long seeds = args.length > 3 ? Long.parseLong(args[3]) : 40L;

        for (long seed = firstSeed; seed < firstSeed + seeds; seed++) {
            OverworldBiomeSource biomes = new OverworldBiomeSource(MCVersion.v1_16_1, seed);
            TerrainGenerator terrain = new OverworldTerrainGenerator(biomes);

            for (int cx = -radius; cx <= radius; cx++) {
                for (int cz = -radius; cz <= radius; cz++) {
                    int biome = biomes.getBiomeForNoiseGen(cx * 4 + 2, 0, cz * 4 + 2).getId();
                    int count = BiomeCaneConfig.count(biome);
                    if (count == 0) {
                        continue;
                    }
                    ArrayWorld world = build(terrain, cx, cz);
                    JavaRandom random = new JavaRandom();
                    long decorationSeed = random.setDecorationSeed(seed, cx * 16, cz * 16);

                    int best = 0;
                    int bx = 0, by = 0, bz = 0;
                    for (SugarCaneFeature.Column c : SugarCaneFeature.place(
                            world, decorationSeed, BiomeCaneConfig.index(biome),
                            count, cx, cz, false)) {
                        int h = world.caneRunThrough(c.x(), c.y(), c.z());
                        if (h > best) {
                            best = h;
                            bx = c.x();
                            bz = c.z();
                            by = c.y();
                            while (world.getBlock(bx, by - 1, bz) == Blocks.SUGAR_CANE) {
                                by--;
                            }
                        }
                    }
                    if (best >= target) {
                        System.out.printf("seed %d   chunk %d,%d   biome %d (count %d)%n",
                                seed, cx, cz, biome, count);
                        System.out.printf("  %d-tall stack with water ignored at  X=%d Y=%d Z=%d%n",
                                best, bx, by, bz);
                        return;
                    }
                }
            }
            System.out.println("seed " + seed + ": nothing >= " + target);
        }
    }

    private static ArrayWorld build(TerrainGenerator terrain, int cx, int cz) {
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
}
