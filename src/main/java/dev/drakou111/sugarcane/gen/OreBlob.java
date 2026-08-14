package dev.drakou111.sugarcane.gen;

import dev.drakou111.sugarcane.rng.JavaRandom;
import dev.drakou111.sugarcane.rng.Mth;

import java.util.BitSet;

/**
 * 1.16.1 {@code OreFeature}, used here for {@code ORE_DIRT} - the dirt blobs
 * that supply the soil for deep sugar cane spots. The confirmed stackable spot
 * at 499163/24/518311 sits on exactly this: dirt at y=23, well below any
 * surface.
 *
 * <p>Configuration: {@code OreConfiguration(NATURAL_STONE, DIRT, 33)} placed
 * with {@code range(256).squared().count(10)} at UNDERGROUND_ORES, which is
 * step 6 - after the carvers and before sugar cane at step 8. So a blob can
 * turn the stone floor of an already-carved cave into dirt, which is what makes
 * the deep spots possible at all.
 */
public final class OreBlob {

    public static final int DIRT_SIZE = 33;
    public static final int DIRT_COUNT = 10;

    public interface Target {
        /** NATURAL_STONE: stone, granite, diorite, andesite. */
        boolean isNaturalStone(int x, int y, int z);

        void setDirt(int x, int y, int z);

        /** OCEAN_FLOOR_WG height, used by the reachability check in place(). */
        int oceanFloorHeight(int x, int z);
    }

    private final Target target;
    private final int size;

    int chunkX, chunkZ;

    public OreBlob(Target target, int size) {
        this.target = target;
        this.size = size;
    }

    public OreBlob(int chunkX, int chunkZ, Target target, int size) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.target = target;
        this.size = size;
    }

    /**
     * One placement attempt at (x, y, z). The decorator supplies the position:
     * {@code count(10)} then {@code squared()} then {@code range(256)}, i.e.
     * per attempt x = chunkX*16 + nextInt(16), y = nextInt(256), z likewise.
     */
    public boolean place(JavaRandom random, int x, int y, int z) {
        float angle = random.nextFloat() * (float) Math.PI;
        float spread = (float) size / 8.0f;
        int pad = ceil(((float) size / 16.0f * 2.0f + 1.0f) / 2.0f);

        // Vanilla computes these endpoints entirely in float and only then widens:
        // (float)pos.getX() + Mth.sin(f) * f2. Doing the addition in double instead
        // is exact near the origin but not far from it — at x around 3 million a
        // float step is 0.25 blocks, which moves the whole blob.
        double x0 = (float) x + Mth.sin(angle) * spread;
        double x1 = (float) x - Mth.sin(angle) * spread;
        double z0 = (float) z + Mth.cos(angle) * spread;
        double z1 = (float) z - Mth.cos(angle) * spread;
        double y0 = y + random.nextInt(3) - 2;
        double y1 = y + random.nextInt(3) - 2;

        int minX = x - ceil(spread) - pad;
        int minY = y - 2 - pad;
        int minZ = z - ceil(spread) - pad;
        int spanXZ = 2 * (ceil(spread) + pad);
        int spanY = 2 * (2 + pad);

        for (int px = minX; px <= minX + spanXZ; px++) {
            for (int pz = minZ; pz <= minZ + spanXZ; pz++) {
                if (minY <= target.oceanFloorHeight(px, pz)) {
                    return doPlace(random, x0, x1, z0, z1, y0, y1,
                            minX, minY, minZ, spanXZ, spanY);
                }
            }
        }
        return false;
    }

    private boolean doPlace(JavaRandom random, double x0, double x1, double z0, double z1,
                            double y0, double y1, int minX, int minY, int minZ,
                            int spanXZ, int spanY) {
        int placed = 0;
        BitSet visited = new BitSet(spanXZ * spanY * spanXZ);
        double[] blobs = new double[size * 4];

        for (int i = 0; i < size; i++) {
            float t = (float) i / (float) size;
            double cx = lerp(t, x0, x1);
            double cy = lerp(t, y0, y1);
            double cz = lerp(t, z0, z1);
            double scale = random.nextDouble() * (double) size / 16.0;
            double radius = ((double) (Mth.sin((float) Math.PI * t) + 1.0f) * scale + 1.0) / 2.0;
            blobs[i * 4] = cx;
            blobs[i * 4 + 1] = cy;
            blobs[i * 4 + 2] = cz;
            blobs[i * 4 + 3] = radius;
        }

        // Drop any sphere fully contained in an earlier one.
        for (int i = 0; i < size - 1; i++) {
            if (blobs[i * 4 + 3] <= 0.0) {
                continue;
            }
            for (int j = i + 1; j < size; j++) {
                if (blobs[j * 4 + 3] <= 0.0) {
                    continue;
                }
                double dr = blobs[i * 4 + 3] - blobs[j * 4 + 3];
                double dx = blobs[i * 4] - blobs[j * 4];
                double dy = blobs[i * 4 + 1] - blobs[j * 4 + 1];
                double dz = blobs[i * 4 + 2] - blobs[j * 4 + 2];
                if (dr * dr > dx * dx + dy * dy + dz * dz) {
                    blobs[(dr > 0.0 ? j : i) * 4 + 3] = -1.0;
                }
            }
        }

        for (int i = 0; i < size; i++) {
            double radius = blobs[i * 4 + 3];
            if (radius < 0.0) {
                continue;
            }
            double cx = blobs[i * 4];
            double cy = blobs[i * 4 + 1];
            double cz = blobs[i * 4 + 2];
            int lx0 = Math.max(floor(cx - radius), minX);
            int ly0 = Math.max(floor(cy - radius), minY);
            int lz0 = Math.max(floor(cz - radius), minZ);
            int lx1 = Math.max(floor(cx + radius), lx0);
            int ly1 = Math.max(floor(cy + radius), ly0);
            int lz1 = Math.max(floor(cz + radius), lz0);

            for (int px = lx0; px <= lx1; px++) {
                double dx = ((double) px + 0.5 - cx) / radius;
                if (dx * dx >= 1.0) {
                    continue;
                }
                for (int py = ly0; py <= ly1; py++) {
                    double dy = ((double) py + 0.5 - cy) / radius;
                    if (dx * dx + dy * dy >= 1.0) {
                        continue;
                    }
                    for (int pz = lz0; pz <= lz1; pz++) {
                        double dz = ((double) pz + 0.5 - cz) / radius;
                        if (dx * dx + dy * dy + dz * dz >= 1.0) {
                            continue;
                        }
                        int index = px - minX + (py - minY) * spanXZ
                                + (pz - minZ) * spanXZ * spanY;
                        if (index < 0 || index >= visited.size() || visited.get(index)) {
                            continue;
                        }
                        visited.set(index);
                        if (target.isNaturalStone(px, py, pz)) {
                            target.setDirt(px, py, pz);
                            placed++;
                        }
                    }
                }
            }
        }
        return placed > 0;
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    private static int ceil(double d) {
        return Mth.ceil(d);
    }

    private static int floor(double d) {
        return Mth.floor(d);
    }
}
