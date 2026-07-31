package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.rng.JavaRandom;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaRandomTest {

    @Test
    void matchesJavaUtilRandomBitForBit() {
        Random reference = new Random();
        JavaRandom ours = new JavaRandom();
        Random seeds = new Random(1234);

        for (int t = 0; t < 2000; t++) {
            long seed = seeds.nextLong();
            reference.setSeed(seed);
            ours.setSeed(seed);
            for (int i = 0; i < 40; i++) {
                switch (i % 5) {
                    case 0 -> assertEquals(reference.nextInt(), ours.nextInt());
                    case 1 -> assertEquals(reference.nextInt(16), ours.nextInt(16));
                    case 2 -> assertEquals(reference.nextInt(5), ours.nextInt(5)); // non power of two
                    case 3 -> assertEquals(reference.nextLong(), ours.nextLong());
                    default -> assertEquals(reference.nextDouble(), ours.nextDouble());
                }
            }
        }
    }

    /**
     * yspread is 0, so the feature calls nextInt(1) twice per try. It always
     * returns 0 but still advances the LCG — getting this wrong desynchronises
     * every subsequent draw.
     */
    @Test
    void nextIntOneStillAdvancesTheStream() {
        JavaRandom withCalls = new JavaRandom(42);
        withCalls.nextInt(1);
        withCalls.nextInt(1);
        int after = withCalls.nextInt(1000);

        JavaRandom withoutCalls = new JavaRandom(42);
        int direct = withoutCalls.nextInt(1000);

        assertEquals(0, new JavaRandom(42).nextInt(1));
        org.junit.jupiter.api.Assertions.assertNotEquals(direct, after);
    }

    /** Cross-check against a value produced by java.util.Random for the same construction. */
    @Test
    void decorationSeedMatchesReferenceImplementation() {
        long levelSeed = -4172144997902289642L;
        int blockX = 16 * 37, blockZ = 16 * -12;

        Random reference = new Random(levelSeed);
        long a = reference.nextLong() | 1L;
        long b = reference.nextLong() | 1L;
        long expected = (long) blockX * a + (long) blockZ * b ^ levelSeed;

        JavaRandom ours = new JavaRandom();
        assertEquals(expected, ours.setDecorationSeed(levelSeed, blockX, blockZ));
    }
}
