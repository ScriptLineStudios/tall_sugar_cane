package dev.drakou111.sugarcane.gen;

import dev.drakou111.sugarcane.rng.JavaRandom;
import dev.drakou111.sugarcane.rng.Mth;

import java.util.BitSet;

/**
 * 1.16.1 {@code CaveWorldCarver}, transcribed from the official-mapping
 * decompile. This is the AIR-step cave carver; it is what produces the air
 * cavity that an underwater carver later lines with water.
 *
 * <p>Careful with two ranges that look alike: the driver considers start chunks
 * within a radius of <b>8</b>, but {@code getRange()} is <b>4</b>, and the
 * tunnel length is derived from the latter as {@code (4 * 2 - 1) * 16 = 112}.
 *
 * @see Carver for the shared sphere fill and block write
 */
public final class CaveCarver extends Carver {

    /**
     * Kept as an alias so callers and tests do not have to know that the
     * interface moved to {@link Carver}.
     */
    public interface Target extends Carver.Target {
    }

    private static final int CAVE_BOUND = 15;

    public CaveCarver(Carver.Target target, int chunkX, int chunkZ) {
        this(target, chunkX, chunkZ, false, 63);
    }

    /**
     * @param underwater run as {@code UnderwaterCaveWorldCarver}: it extends the
     *                   land carver so the tunnel walk is identical, but it
     *                   overrides hasWater to false, widens replaceableBlocks to
     *                   include air, only carves below sea level, and fills with
     *                   water instead of air
     */
    public CaveCarver(Carver.Target target, int chunkX, int chunkZ, boolean underwater, int seaLevel) {
        this(target, chunkX, chunkZ, underwater, seaLevel, new BitSet(65536));
    }

    /** @param mask the generation step's carving mask, shared with the canyon carver */
    public CaveCarver(Carver.Target target, int chunkX, int chunkZ, boolean underwater,
                      int seaLevel, BitSet mask) {
        super(target, chunkX, chunkZ, underwater, seaLevel, mask);
    }

    @Override
    public void carve(JavaRandom random, int startX, int startZ) {
        int tunnelLength = (RANGE * 2 - 1) * 16;
        int caves = random.nextInt(random.nextInt(random.nextInt(CAVE_BOUND) + 1) + 1);

        for (int i = 0; i < caves; i++) {
            double x = startX * 16 + random.nextInt(16);
            double y = random.nextInt(random.nextInt(120) + 8);   // getCaveY
            double z = startZ * 16 + random.nextInt(16);
            int branches = 1;

            if (random.nextInt(4) == 0) {
                float roomThickness = 1.0f + random.nextFloat() * 6.0f;
                genRoom(random.nextLong(), x, y, z, roomThickness);
                branches += random.nextInt(4);
            }

            for (int j = 0; j < branches; j++) {
                float yaw = random.nextFloat() * ((float) Math.PI * 2);
                float pitch = (random.nextFloat() - 0.5f) / 4.0f;
                float thickness = getThickness(random);
                int length = tunnelLength - random.nextInt(tunnelLength / 4);
                genTunnel(random.nextLong(), x, y, z, thickness, yaw, pitch, 0, length, 1.0);
            }
        }
    }

    /** {@code skip()}: the -0.7 is what gives caves their flat floors. */
    @Override
    protected boolean skip(double dx, double dy, double dz, int y) {
        return dy <= -0.7 || dx * dx + dy * dy + dz * dz >= 1.0;
    }

    private static float getThickness(JavaRandom random) {
        float f = random.nextFloat() * 2.0f + random.nextFloat();
        if (random.nextInt(10) == 0) {
            f *= random.nextFloat() * random.nextFloat() * 3.0f + 1.0f;
        }
        return f;
    }

    private void genRoom(long seed, double x, double y, double z, float thickness) {
        double radius = 1.5 + (double) (Mth.sin(1.5707964f) * thickness);
        carveSphere(seed, x + 1.0, y, z, radius, radius * 0.5);
    }

    private void genTunnel(long seed, double x, double y, double z, float thickness,
                           float yaw, float pitch, int start, int length, double yScale) {
        JavaRandom random = new JavaRandom(seed);
        int branchAt = random.nextInt(length / 2) + length / 4;
        boolean steep = random.nextInt(6) == 0;
        float yawDelta = 0.0f;
        float pitchDelta = 0.0f;

        for (int i = start; i < length; i++) {
            double radius = 1.5 + (double) (Mth.sin((float) Math.PI * (float) i / (float) length) * thickness);
            double vertical = radius * yScale;
            float cosPitch = Mth.cos(pitch);
            x += (double) (Mth.cos(yaw) * cosPitch);
            y += (double) Mth.sin(pitch);
            z += (double) (Mth.sin(yaw) * cosPitch);
            pitch *= steep ? 0.92f : 0.7f;
            pitch += pitchDelta * 0.1f;
            yaw += yawDelta * 0.1f;
            pitchDelta *= 0.9f;
            yawDelta *= 0.75f;
            pitchDelta += (random.nextFloat() - random.nextFloat()) * random.nextFloat() * 2.0f;
            yawDelta += (random.nextFloat() - random.nextFloat()) * random.nextFloat() * 4.0f;

            if (i == branchAt && thickness > 1.0f) {
                genTunnel(random.nextLong(), x, y, z, random.nextFloat() * 0.5f + 0.5f,
                        yaw - 1.5707964f, pitch / 3.0f, i, length, 1.0);
                genTunnel(random.nextLong(), x, y, z, random.nextFloat() * 0.5f + 0.5f,
                        yaw + 1.5707964f, pitch / 3.0f, i, length, 1.0);
                return;
            }
            if (random.nextInt(4) == 0) {
                continue;
            }
            if (!canReach(x, z, i, length, thickness)) {
                return;
            }
            carveSphere(seed, x, y, z, radius, vertical);
        }
    }
}
