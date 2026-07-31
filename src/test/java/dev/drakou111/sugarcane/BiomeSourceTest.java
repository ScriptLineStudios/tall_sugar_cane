package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.validate.BiomeSourceValidator;
import kaptainwutax.biomeutils.source.OverworldBiomeSource;
import kaptainwutax.mcutils.version.MCVersion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the biome source against ground truth read out of chunks a real 1.16.1
 * server generated on seed 1.
 *
 * <p>The full check (`BiomeSourceValidator`) agrees on 320,000 of 320,000 biome
 * cells over 20,000 chunks. These few values guard that result against silent
 * drift if the JitPack dependency versions are ever changed — the library is
 * pinned to commit hashes whose interdependencies do not all resolve, so this is
 * a real risk.
 *
 * <p>Note the query must be getBiomeForNoiseGen in quart coordinates: a chunk's
 * stored Biomes[] holds noise biomes, not the per-block Voronoi-fuzzed ones.
 * Using getBiome() instead scores 93.5%, failing only at biome boundaries.
 */
class BiomeSourceTest {

    private static final OverworldBiomeSource SOURCE =
            new OverworldBiomeSource(MCVersion.v1_16_1, 1L);

    private static void assertBiome(int quartX, int quartZ, int expected) {
        assertEquals(expected, SOURCE.getBiomeForNoiseGen(quartX, 0, quartZ).getId(),
                "biome at quart " + quartX + "," + quartZ);
    }

    @Test
    void matchesRealChunksOnSeedOne() {
        assertBiome(-8, -8, 0);            // ocean
        assertBiome(-35184, 24264, 0);     // ocean
        assertBiome(-35124, 24716, 24);    // deep_ocean
        assertBiome(-35304, 24188, 12);    // snowy_tundra
        assertBiome(-35228, 24632, 6);     // swamp
        assertBiome(-35408, 24088, 158);   // taiga_mountains
    }

    @Test
    void oceanFilterCoversTheCarverBiomes() {
        // Only these run addOceanCarvers, and only they produce the geometry.
        for (int id : new int[]{0, 10, 24, 44, 45, 46, 47, 48, 49, 50}) {
            assertTrue(BiomeSourceValidator.isOcean(id), "id " + id + " should be ocean");
        }
        for (int id : new int[]{1, 2, 6, 7, 12, 16, 35, 158}) {
            assertFalse(BiomeSourceValidator.isOcean(id), "id " + id + " should not be ocean");
        }
    }
}
