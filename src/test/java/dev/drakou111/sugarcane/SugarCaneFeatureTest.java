package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.gen.Scenarios;
import dev.drakou111.sugarcane.gen.SugarCaneFeature;
import dev.drakou111.sugarcane.world.ArrayWorld;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SugarCaneFeatureTest {

    private static int tallestOver(ArrayWorld template, int count, long seeds) {
        int best = 0;
        for (long seed = 0; seed < seeds; seed++) {
            ArrayWorld world = template.copy();
            SugarCaneFeature.place(world, seed, 0, count, 0, 0);
            best = Math.max(best, world.tallestCane());
        }
        return best;
    }

    /**
     * The central negative result. On a flat shore — water surface level with the
     * soil, which is what 1.16.1 worldgen produces essentially everywhere — the
     * tallest possible column is 4, no matter how lucky the chunk RNG is.
     *
     * <p>A second column can land on top of a first (canSurvive passes trivially
     * when the block below is cane), but its needWater check is made against the
     * neighbours of the lower column's <em>top</em> block, which is above the
     * water line. So it never places.
     */
    @Test
    void flatShoreCannotExceedFourEvenInDesertsWithSixtyInvocations() {
        ArrayWorld flat = Scenarios.shore(1);
        assertEquals(4, tallestOver(flat, SugarCaneFeature.COUNT_DESERT, 20_000));
    }

    /**
     * The positive counterpart: give the same soil a vertical water face beside
     * it and columns taller than 4 appear readily. This is the geometry the
     * terrain search has to find.
     */
    @Test
    void exposedWaterWallProducesColumnsTallerThanFour() {
        ArrayWorld wall = Scenarios.exposedWaterWall();
        assertTrue(tallestOver(wall, SugarCaneFeature.COUNT_DESERT, 5_000) > 4,
                "a multi-block water face beside the soil should allow stacking");
    }

    /**
     * The configuration the search is actually looking for: a dry air pocket
     * below sea level beside untouched sea-fill water.
     */
    @Test
    void structureCutAirPocketBelowSeaLevelProducesTallColumns() {
        ArrayWorld pocket = Scenarios.structureAirPocket(57, 62);
        assertTrue(tallestOver(pocket, SugarCaneFeature.COUNT_DESERT, 5_000) > 4);
    }

    /**
     * The base's own needWater check is made at soil level. If the neighbouring
     * water starts above the soil — a sea floor flush with its surroundings —
     * nothing generates at all, not even a normal 2-tall cane.
     */
    @Test
    void waterMustReachDownToSoilLevelOrNothingGenerates() {
        ArrayWorld pocket = Scenarios.structureAirPocket(57, 62);
        for (int z = 4; z <= 12; z++) {
            pocket.fillColumn(7, z, 57, 57, dev.drakou111.sugarcane.world.Blocks.SAND);
        }
        assertEquals(0, tallestOver(pocket, SugarCaneFeature.COUNT_DESERT, 2_000));
    }

    /** Worldgen columns are 2..4 tall, biased low: 11/18, 5/18, 2/18. */
    @Test
    void columnHeightDistributionMatchesColumnPlacer() {
        ArrayWorld flat = Scenarios.shore(1);
        long[] counts = new long[5];
        long total = 0;
        for (long seed = 0; seed < 20_000; seed++) {
            ArrayWorld world = flat.copy();
            for (SugarCaneFeature.Column c : SugarCaneFeature.place(
                    world, seed, 0, SugarCaneFeature.COUNT_DEFAULT, 0, 0)) {
                counts[c.height()]++;
                total++;
            }
        }
        assertTrue(total > 2_000, "expected a decent sample, got " + total);
        assertEquals(0, counts[0] + counts[1]);
        assertEquals(11.0 / 18.0, (double) counts[2] / total, 0.02);
        assertEquals(5.0 / 18.0, (double) counts[3] / total, 0.02);
        assertEquals(2.0 / 18.0, (double) counts[4] / total, 0.02);
    }

    /** A stacked placement needs the block below to be cane; then canSurvive is free. */
    @Test
    void caneBelowSatisfiesCanSurviveWithoutSoilOrWater() {
        ArrayWorld world = Scenarios.shore(1);
        world.setBlock(0, 63, 0, dev.drakou111.sugarcane.world.Blocks.SUGAR_CANE);
        assertTrue(SugarCaneFeature.canSurvive(world, 0, 64, 0));
        // ...but needWater still fails up there, which is the whole problem.
        assertTrue(!SugarCaneFeature.canPlace(world, 0, 64, 0));
    }
}
