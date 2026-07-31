package dev.drakou111.sugarcane.gen;

import dev.drakou111.sugarcane.rng.JavaRandom;
import dev.drakou111.sugarcane.world.BlockView;
import dev.drakou111.sugarcane.world.Blocks;

/**
 * 1.16.1 {@code DiskReplaceFeature} — the sand, clay and gravel patches on an
 * ocean floor, from {@code BiomeDefaultFeatures.addDefaultSoftDisks}.
 *
 * <p>These matter to the search for a reason that is easy to miss. Two of the
 * three replace dirt with something sugar cane cannot stand on, so leaving them
 * out puts soil where the game has none. But the bigger effect is on the RNG:
 * five disks land on the sea floor of every ocean chunk, and the cane feature
 * tries about 200 positions there. A single position that succeeds in the
 * simulation and fails in the game (or the reverse) shifts every later draw,
 * because a successful placement consumes two extra values for the column height.
 * That desynchronises the whole chunk and is enough to invent a stack that is not
 * there.
 *
 * <p>Configuration, at UNDERGROUND_ORES (step 6), after the ores:
 * <ul>
 *   <li>index 11 — sand, radius 7, ySize 2, replacing dirt and grass_block, 3 tries;</li>
 *   <li>index 12 — clay, radius 4, ySize 1, replacing dirt and clay, 1 try;</li>
 *   <li>index 13 — gravel, radius 6, ySize 2, replacing dirt and grass_block, 1 try.</li>
 * </ul>
 */
public final class Disk {

    /** UNDERGROUND_ORES index of the first disk for every overworld biome. */
    public static final int INDEX_SAND = 11;
    public static final int INDEX_CLAY = 12;
    public static final int INDEX_GRAVEL = 13;

    /** {@code DiskConfiguration}: what to write, how wide, how deep, what to replace. */
    public record Config(byte state, int radius, int ySize, byte[] targets, int tries) {
    }

    public static final Config SAND = new Config(Blocks.SAND, 7, 2,
            new byte[]{Blocks.DIRT, Blocks.GRASS_BLOCK}, 3);
    public static final Config CLAY = new Config(Blocks.CLAY, 4, 1,
            new byte[]{Blocks.DIRT, Blocks.CLAY}, 1);
    public static final Config GRAVEL = new Config(Blocks.GRAVEL, 6, 2,
            new byte[]{Blocks.DIRT, Blocks.GRASS_BLOCK}, 1);

    private Disk() {
    }

    /**
     * Runs one disk feature over a chunk: {@code COUNT_TOP_SOLID(tries)} draws the
     * positions, and the feature itself draws its radius — but only once it has
     * passed the water test, which is why that order matters.
     *
     * @param oceanFloor {@code getHeight(OCEAN_FLOOR_WG, x, z)} as the decorator
     *                   sees it, i.e. one above the highest block that blocks
     *                   motion
     */
    public static void place(BlockView world, JavaRandom random, long decorationSeed,
                             int index, Config config, int chunkX, int chunkZ,
                             OceanFloor oceanFloor) {
        random.setFeatureSeed(decorationSeed, index, 6);
        for (int i = 0; i < config.tries(); i++) {
            int x = random.nextInt(16) + chunkX * 16;
            int z = random.nextInt(16) + chunkZ * 16;
            int y = oceanFloor.height(x, z);
            placeOne(world, random, config, x, y, z);
        }
    }

    /** {@code getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z)}. */
    public interface OceanFloor {
        int height(int x, int z);
    }

    private static void placeOne(BlockView world, JavaRandom random, Config config,
                                 int px, int py, int pz) {
        // The disk only forms underwater, and the check comes before the radius
        // draw — so on land the feature costs nothing from the stream.
        if (!world.isWaterFluid(px, py, pz)) {
            return;
        }
        int radius = random.nextInt(config.radius() - 2) + 2;
        for (int x = px - radius; x <= px + radius; x++) {
            for (int z = pz - radius; z <= pz + radius; z++) {
                int dx = x - px;
                int dz = z - pz;
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                for (int y = py - config.ySize(); y <= py + config.ySize(); y++) {
                    byte block = world.getBlock(x, y, z);
                    for (byte target : config.targets()) {
                        if (target == block) {
                            world.setBlock(x, y, z, config.state());
                            break;
                        }
                    }
                }
            }
        }
    }
}
