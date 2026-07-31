package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.gen.TruncatedNoise;
import dev.drakou111.sugarcane.world.Blocks;
import kaptainwutax.biomeutils.source.OverworldBiomeSource;
import kaptainwutax.mcutils.block.Block;
import kaptainwutax.mcutils.version.MCVersion;
import kaptainwutax.terrainutils.terrain.OverworldTerrainGenerator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The truncated density field must agree with TerrainUtils block for block below
 * the cut-off. This is what makes the optimisation safe: the fast path is only
 * ever a bounded copy of the reference implementation, never an approximation of
 * it.
 */
class TruncatedNoiseTest {

    private static byte classify(Block block) {
        if (block == kaptainwutax.mcutils.block.Blocks.STONE) {
            return Blocks.SOLID;
        }
        if (block == kaptainwutax.mcutils.block.Blocks.WATER) {
            return Blocks.WATER;
        }
        return Blocks.AIR;
    }

    @Test
    void matchesTerrainUtilsBelowTheCut() {
        byte[] mine = new byte[256];
        long compared = 0;
        long fellBack = 0;
        for (long seed = 1; seed <= 3; seed++) {
            OverworldBiomeSource biomes = new OverworldBiomeSource(MCVersion.v1_16_1, seed);
            OverworldTerrainGenerator generator = new OverworldTerrainGenerator(biomes);
            TruncatedNoise truncated = new TruncatedNoise(generator, biomes);
            // A spread of positions: ocean, land, and negative coordinates.
            for (int x = -300; x <= 300; x += 37) {
                for (int z = -300; z <= 300; z += 41) {
                    int height = truncated.column(x, z, mine,
                            Blocks.SOLID, Blocks.WATER, Blocks.AIR, 63);
                    Block[] reference = generator.getColumnAt(x, z);
                    if (height < 0) {
                        fellBack++;
                        continue;
                    }
                    int expectedHeight = 0;
                    for (int y = 0; y < TruncatedNoise.CUT; y++) {
                        assertEquals(classify(reference[y]), mine[y],
                                "seed " + seed + " column " + x + "," + z + " y=" + y);
                        if (classify(reference[y]) != Blocks.AIR) {
                            expectedHeight = y + 1;
                        }
                    }
                    assertEquals(expectedHeight, height,
                            "WORLD_SURFACE_WG at " + x + "," + z);
                    compared++;
                }
            }
        }
        assertTrue(compared > 500, "not enough columns compared: " + compared);
        System.out.printf("TruncatedNoise: %d columns exact, %d over the cut%n", compared, fellBack);
    }

    /** Columns whose terrain reaches the cut have to be reported, not guessed at. */
    @Test
    void reportsColumnsThatReachTheCut() {
        byte[] mine = new byte[256];
        OverworldBiomeSource biomes = new OverworldBiomeSource(MCVersion.v1_16_1, 1L);
        OverworldTerrainGenerator generator = new OverworldTerrainGenerator(biomes);
        TruncatedNoise truncated = new TruncatedNoise(generator, biomes);
        int reached = 0;
        for (int x = 0; x < 2000 && reached == 0; x += 8) {
            for (int z = 0; z < 2000; z += 8) {
                if (truncated.column(x, z, mine, Blocks.SOLID, Blocks.WATER, Blocks.AIR, 63) < 0) {
                    Block[] reference = generator.getColumnAt(x, z);
                    assertEquals(kaptainwutax.mcutils.block.Blocks.STONE,
                            reference[TruncatedNoise.CUT - 1],
                            "reported a cut-off column that is not solid at the cut");
                    reached++;
                    break;
                }
            }
        }
        assertTrue(reached > 0, "no column reached the cut in the sampled area");
    }
}
