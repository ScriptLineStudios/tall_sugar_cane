package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.gen.CanyonCarver;
import dev.drakou111.sugarcane.gen.Carver;
import dev.drakou111.sugarcane.gen.CarverConfig;
import dev.drakou111.sugarcane.rng.JavaRandom;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Self-consistency checks for the canyon carver. Nothing here proves the carved
 * shape matches the real game — that needs a comparison against generated
 * chunks — but it does pin the properties the transcription is supposed to have.
 */
class CanyonCarverTest {

    private static final class Stone implements Carver.Target {
        final Set<Long> carved = new HashSet<>();
        final Set<Long> water = new HashSet<>();
        boolean waterEverywhere = false;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

        private void bounds(int x, int z) {
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z);
        }

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
            bounds(x, z);
        }

        @Override
        public void setWater(int x, int y, int z, boolean scheduleTick) {
            water.add(key(x, y, z));
            bounds(x, z);
        }

        static long key(int x, int y, int z) {
            return ((long) x << 40) ^ ((long) z << 12) ^ y;
        }
    }

    private static Stone run(long levelSeed, int chunkX, int chunkZ, boolean underwater) {
        Stone stone = new Stone();
        CanyonCarver carver = new CanyonCarver(stone, chunkX, chunkZ, underwater, 63,
                new BitSet(65536));
        JavaRandom random = new JavaRandom();
        float probability = underwater ? CarverConfig.UNDERWATER_CANYON : CarverConfig.CANYON;
        for (int sx = chunkX - CarverConfig.CARVE_RADIUS; sx <= chunkX + CarverConfig.CARVE_RADIUS; sx++) {
            for (int sz = chunkZ - CarverConfig.CARVE_RADIUS; sz <= chunkZ + CarverConfig.CARVE_RADIUS; sz++) {
                if (CarverConfig.isStartChunk(random, levelSeed, underwater ? 0 : 1,
                        sx, sz, probability)) {
                    carver.carve(random, sx, sz);
                }
            }
        }
        return stone;
    }

    @Test
    void carvesSomethingAndIsDeterministic() {
        int carved = 0;
        for (long seed = 1; seed <= 40 && carved == 0; seed++) {
            carved = run(seed, 0, 0, false).carved.size();
        }
        assertTrue(carved > 0, "no canyon carved in 40 seeds");
        Stone a = run(7, 3, -5, false);
        Stone b = run(7, 3, -5, false);
        assertEquals(a.carved, b.carved);
    }

    /** Canyons start between y=20 and y=67, so nothing should reach the lava layer. */
    @Test
    void neverCarvesBelowEleven() {
        for (long seed = 1; seed <= 60; seed++) {
            for (long k : run(seed, 0, 0, false).carved) {
                int y = (int) (k & 0xFFF);
                assertTrue(y >= 11, "carved air at y=" + y);
            }
        }
    }

    /** Everything written stays inside the chunk being generated. */
    @Test
    void staysInsideTheChunk() {
        int chunkX = 2, chunkZ = -3;
        for (long seed = 1; seed <= 40; seed++) {
            Stone stone = run(seed, chunkX, chunkZ, false);
            if (stone.carved.isEmpty()) {
                continue;
            }
            assertTrue(stone.minX >= chunkX * 16 && stone.maxX < chunkX * 16 + 16,
                    "carved x outside the chunk: " + stone.minX + ".." + stone.maxX);
            assertTrue(stone.minZ >= chunkZ * 16 && stone.maxZ < chunkZ * 16 + 16,
                    "carved z outside the chunk: " + stone.minZ + ".." + stone.maxZ);
        }
    }

    /** The water guard is disabled underwater, and the fill is water below sea level only. */
    @Test
    void underwaterFillsWaterBelowSeaLevelOnly() {
        int found = 0;
        for (long seed = 1; seed <= 200 && found == 0; seed++) {
            Stone stone = run(seed, 0, 0, true);
            stone.waterEverywhere = true;
            found = stone.water.size();
            assertTrue(stone.carved.isEmpty(), "underwater carver must not place air");
            for (long k : stone.water) {
                int y = (int) (k & 0xFFF);
                assertTrue(y < 63, "water above sea level at y=" + y);
                assertTrue(y > 10, "water in the lava layer at y=" + y);
            }
        }
        assertTrue(found > 0, "no underwater canyon in 200 seeds");
    }
}
