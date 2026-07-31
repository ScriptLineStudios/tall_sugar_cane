package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.gen.OreBlob;
import dev.drakou111.sugarcane.rng.JavaRandom;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OreBlobTest {

    private static final class Stone implements OreBlob.Target {
        final Set<Long> dirt = new HashSet<>();
        boolean stone = true;

        @Override
        public boolean isNaturalStone(int x, int y, int z) {
            return stone;
        }

        @Override
        public void setDirt(int x, int y, int z) {
            dirt.add(((long) x << 40) ^ ((long) z << 12) ^ y);
        }

        @Override
        public int oceanFloorHeight(int x, int z) {
            return 80;
        }
    }

    private static Stone place(long seed, boolean stone) {
        Stone target = new Stone();
        target.stone = stone;
        OreBlob blob = new OreBlob(target, OreBlob.DIRT_SIZE);
        blob.place(new JavaRandom(seed), 100, 40, 100);
        return target;
    }

    @Test
    void placesADirtBlobAndIsDeterministic() {
        Stone a = place(42L, true);
        assertTrue(a.dirt.size() > 5, "expected a blob, got " + a.dirt.size());
        assertEquals(a.dirt, place(42L, true).dirt);
    }

    /** Only NATURAL_STONE is replaced; a cave's air must stay air. */
    @Test
    void replacesOnlyNaturalStone() {
        assertEquals(0, place(42L, false).dirt.size());
    }

    /** size 33 keeps the blob compact - it must not smear across chunks. */
    @Test
    void blobStaysNearItsOrigin() {
        for (long k : place(42L, true).dirt) {
            int x = (int) (k >> 40);
            assertTrue(Math.abs(x - 100) <= 12, "blob reached x=" + x);
        }
    }

    /**
     * The reachability test in place() rejects positions whose bounding box
     * starts above the ocean floor everywhere.
     */
    @Test
    void skipsWhenEntirelyAboveTheOceanFloor() {
        Stone target = new Stone();
        OreBlob blob = new OreBlob(new OreBlob.Target() {
            @Override
            public boolean isNaturalStone(int x, int y, int z) {
                return true;
            }

            @Override
            public void setDirt(int x, int y, int z) {
                target.dirt.add(1L);
            }

            @Override
            public int oceanFloorHeight(int x, int z) {
                return 0;   // everything is above the floor
            }
        }, OreBlob.DIRT_SIZE);
        assertTrue(!blob.place(new JavaRandom(42L), 100, 200, 100));
        assertEquals(0, target.dirt.size());
    }
}
