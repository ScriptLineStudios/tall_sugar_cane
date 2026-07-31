package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.gen.CarverConfig;
import dev.drakou111.sugarcane.rng.JavaRandom;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CarverConfigTest {

    @Test
    void largeFeatureSeedMatchesReferenceImplementation() {
        long levelSeed = 1L;
        int cx = -8781, cz = 6179;

        Random reference = new Random(levelSeed);
        long a = reference.nextLong();
        long b = reference.nextLong();
        long expected = (long) cx * a ^ (long) cz * b ^ levelSeed;

        assertEquals(expected, new JavaRandom().setLargeFeatureSeed(levelSeed, cx, cz));
    }

    @Test
    void nextFloatMatchesJavaUtilRandom() {
        Random reference = new Random(12345L);
        JavaRandom ours = new JavaRandom(12345L);
        for (int i = 0; i < 500; i++) {
            assertEquals(reference.nextFloat(), ours.nextFloat(), 0.0f);
        }
    }

    /**
     * With radius 8 there are 289 candidate start chunks, so the number that
     * actually start a carver should track 289 * probability. This catches a
     * mis-seeded or mis-salted RNG, which would bias the count.
     */
    @Test
    void startChunkCountTracksProbability() {
        long total = 0;
        int samples = 0;
        for (int cx = 0; cx < 40; cx++) {
            for (int cz = 0; cz < 40; cz++) {
                total += CarverConfig.countStartChunks(1L, 0, cx * 7, cz * 7,
                        CarverConfig.CAVE_OCEAN);
                samples++;
            }
        }
        double mean = (double) total / samples;
        double expected = 289 * CarverConfig.CAVE_OCEAN;   // ~19.3
        assertEquals(expected, mean, 1.0,
                "mean start chunks per chunk should track 289 * p");
    }

    @Test
    void onlyOceansRegisterLiquidCarvers() {
        assertEquals(0, CarverConfig.liquidCarvers(false).length);
        assertEquals(2, CarverConfig.liquidCarvers(true).length);
        // Oceans also get a rarer cave carver than land does.
        assertTrue(CarverConfig.airCarvers(true)[0] < CarverConfig.airCarvers(false)[0]);
    }
}
