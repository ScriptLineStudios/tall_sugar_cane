package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.gen.CaveCarver;
import dev.drakou111.sugarcane.rng.JavaRandom;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaveCarverTest {

    /** Solid stone everywhere, recording what gets carved. */
    private static final class Stone implements CaveCarver.Target {
        final Set<Long> carved = new HashSet<>();
        boolean waterEverywhere = false;

        @Override
        public boolean canReplace(int x, int y, int z) {
            return true;
        }

        @Override
        public boolean isWater(int x, int y, int z) {
            return waterEverywhere;
        }

        @Override
        public boolean isAir(int x, int y, int z) {
            return carved.contains(key(x, y, z));
        }

        @Override
        public void setCaveAir(int x, int y, int z) {
            carved.add(key(x, y, z));
        }

        final Set<Long> placedWater = new HashSet<>();
        int ticked = 0;

        @Override
        public void setWater(int x, int y, int z, boolean scheduleTick) {
            placedWater.add(key(x, y, z));
            if (scheduleTick) {
                ticked++;
            }
        }

        static long key(int x, int y, int z) {
            return ((long) x << 40) ^ ((long) z << 12) ^ y;
        }
    }

    private static Stone run(long levelSeed, int chunkX, int chunkZ, boolean water) {
        Stone stone = new Stone();
        stone.waterEverywhere = water;
        CaveCarver carver = new CaveCarver(stone, chunkX, chunkZ);
        JavaRandom random = new JavaRandom();
        for (int sx = chunkX - 8; sx <= chunkX + 8; sx++) {
            for (int sz = chunkZ - 8; sz <= chunkZ + 8; sz++) {
                random.setLargeFeatureSeed(levelSeed, sx, sz);
                if (random.nextFloat() <= 0.14285715f) {
                    carver.carve(random, sx, sz);
                }
            }
        }
        return stone;
    }

    /**
     * The underwater carver only works below sea level, has no water guard, and
     * fills with water rather than air. It is the step that creates the tall
     * water face the whole search depends on.
     */
    @Test
    void underwaterCarverFillsWithWaterOnlyBelowSeaLevel() {
        Stone stone = new Stone();
        stone.waterEverywhere = true;   // would abort the land carver entirely
        CaveCarver carver = new CaveCarver(stone, 10, 10, true, 63);
        JavaRandom random = new JavaRandom();
        for (int sx = 2; sx <= 18; sx++) {
            for (int sz = 2; sz <= 18; sz++) {
                random.setLargeFeatureSeed(1L, sx, sz);
                if (random.nextFloat() <= 0.06666667f) {
                    carver.carve(random, sx, sz);
                }
            }
        }
        assertTrue(stone.placedWater.size() > 50,
                "expected an underwater cave, got " + stone.placedWater.size());
        assertEquals(0, stone.carved.size(), "underwater carver must not place air");
        for (long k : stone.placedWater) {
            int by = (int) (k & 0xFFF);
            assertTrue(by > 10 && by < 63, "water carved at y=" + by);
        }
    }

    @Test
    void carvesSomethingAndIsDeterministic() {
        Stone a = run(1L, 10, 10, false);
        Stone b = run(1L, 10, 10, false);
        assertTrue(a.carved.size() > 100, "expected a cave system, got " + a.carved.size());
        assertEquals(a.carved, b.carved, "carving must be deterministic for a seed");
    }

    @Test
    void differentSeedsCarveDifferently() {
        assertTrue(!run(1L, 10, 10, false).carved.equals(run(2L, 10, 10, false).carved));
    }

    /** The water guard aborts every sphere, so nothing is carved. */
    @Test
    void refusesToCarveIntoWater() {
        assertEquals(0, run(1L, 10, 10, true).carved.size());
    }

    /** Lava fills below y=11, so no cave air may appear there. */
    @Test
    void neverCarvesAirBelowElevenOrAboveGenHeight() {
        for (long y : run(1L, 10, 10, false).carved) {
            int by = (int) (y & 0xFFF);
            assertTrue(by >= 11 && by <= 248, "carved air at y=" + by);
        }
    }
}
