package dev.drakou111.sugarcane.gen;

import dev.drakou111.sugarcane.rng.JavaRandom;
import dev.drakou111.sugarcane.rng.Mth;

import java.util.ArrayList;
import java.util.BitSet;

/**
 * 1.16.1 {@code CanyonWorldCarver} and, with {@code underwater} set,
 * {@code UnderwaterCanyonWorldCarver}.
 *
 * <p>Worth having despite the low 2% start probability: a canyon is one long
 * high-walled cut rather than a tube, so it exposes far more vertical face than a
 * cave does, and an underwater canyon crossing an air canyon or cave is exactly
 * the "tall water face beside air" the search needs.
 *
 * <p>Differences from the cave carver that are easy to miss:
 * <ul>
 *   <li>one canyon per start chunk — there is no count loop;</li>
 *   <li>the vertical scale is 3, and each step multiplies both radii by
 *       {@code nextFloat() * 0.25 + 0.75}, so two extra draws per step;</li>
 *   <li>{@code skip} uses a per-canyon width table indexed by <em>absolute</em>
 *       block y minus one, not by the step number;</li>
 *   <li>the table is filled by 256 iterations before the walk starts, each with a
 *       {@code nextInt(3)} and sometimes two {@code nextFloat}s.</li>
 * </ul>
 */
public final class CanyonCarver extends Carver {

    /** {@code rs} in the decompile: the squared half-width per world y. */
    private final float[] widths = new float[256];

    public CanyonCarver(Carver.Target target, int chunkX, int chunkZ, boolean underwater,
                        int seaLevel, BitSet mask) {
        super(target, chunkX, chunkZ, underwater, seaLevel, mask);
    }

    public CanyonCarver(int chunkX, int chunkZ, boolean underwater,
                        int seaLevel) {
        super(new Target() {
            @Override
            public boolean canReplace(int x, int y, int z) {
                return false;
            }

            @Override
            public boolean isWater(int x, int y, int z) {
                return false;
            }

            @Override
            public boolean isAir(int x, int y, int z) {
                return false;
            }

            @Override
            public void setCaveAir(int x, int y, int z) {

            }

            @Override
            public void setWater(int x, int y, int z, boolean scheduleTick) {

            }
        }, chunkX, chunkZ, underwater, seaLevel, new BitSet());
    }

    @Override
    public void carve(JavaRandom random, int startX, int startZ) {
        int length = (RANGE * 2 - 1) * 16;
        double x = startX * 16 + random.nextInt(16);
        double y = random.nextInt(random.nextInt(40) + 8) + 20;
        double z = startZ * 16 + random.nextInt(16);
        float yaw = random.nextFloat() * ((float) Math.PI * 2);
        float pitch = (random.nextFloat() - 0.5f) * 2.0f / 8.0f;
        float thickness = (random.nextFloat() * 2.0f + random.nextFloat()) * 2.0f;
        int steps = length - random.nextInt(length / 4);
        genCanyon(random.nextLong(), x, y, z, thickness, yaw, pitch, 0, steps, 3.0);
    }

    @Override
    protected boolean skip(double dx, double dy, double dz, int y) {
        return (dx * dx + dz * dz) * (double) widths[y - 1] + dy * dy / 6.0 >= 1.0;
    }

    public double checkDist(JavaRandom random, int startX, int startZ, int chunkX, int chunkZ) {
        int length = (RANGE * 2 - 1) * 16;
        double x = startX * 16 + random.nextInt(16);
        double y = random.nextInt(random.nextInt(40) + 8) + 20;
        double z = startZ * 16 + random.nextInt(16);
        float yaw = random.nextFloat() * ((float) Math.PI * 2);
        float pitch = (random.nextFloat() - 0.5f) * 2.0f / 8.0f;
        float thickness = (random.nextFloat() * 2.0f + random.nextFloat()) * 2.0f;
        int steps = length - random.nextInt(length / 4);

        ArrayList<Coord> coords = new ArrayList<Coord>();
        genCanyonRecord(random.nextLong(), x, y, z, thickness, yaw, pitch, 0, steps, 3.0, coords);
        double bestDist = 99999999.0;
        for (Coord c : coords) {
//            System.out.printf("%f %f %f -> %f %f %f\n", c.x, c.y, c.z, (float)chunkX, 20.0, (float)chunkZ);
            double dist = Math.sqrt(Math.pow((c.x - chunkX), 2) + Math.pow((c.y - 20.0), 2) + Math.pow((c.z - chunkZ), 2));
            bestDist = Math.min(dist, bestDist);
        }
        return bestDist;
    }

    public record Coord(double x, double y, double z) {};

    public void genCanyonRecord(long seed, double x, double y, double z, float thickness,
                                float yaw, float pitch, int start, int steps, double yScale, ArrayList<Coord> locs) {
        JavaRandom random = new JavaRandom(seed);
        float width = 1.0f;
        for (int i = 0; i < 256; i++) {
            if (i == 0 || random.nextInt(3) == 0) {
                width = 1.0f + random.nextFloat() * random.nextFloat();
            }
            widths[i] = width * width;
        }

        float yawDelta = 0.0f;
        float pitchDelta = 0.0f;
        for (int i = start; i < steps; i++) {
            double radius = 1.5 + (double) (Mth.sin((float) i * (float) Math.PI / (float) steps) * thickness);
            double vertical = radius * yScale;
            radius *= (double) random.nextFloat() * 0.25 + 0.75;
            vertical *= (double) random.nextFloat() * 0.25 + 0.75;
            float cosPitch = Mth.cos(pitch);
            float sinPitch = Mth.sin(pitch);
            x += (double) (Mth.cos(yaw) * cosPitch);
            y += (double) sinPitch;
            z += (double) (Mth.sin(yaw) * cosPitch);
            pitch *= 0.7f;
            pitch += pitchDelta * 0.05f;
            yaw += yawDelta * 0.05f;
            pitchDelta *= 0.8f;
            yawDelta *= 0.5f;
            pitchDelta += (random.nextFloat() - random.nextFloat()) * random.nextFloat() * 2.0f;
            yawDelta += (random.nextFloat() - random.nextFloat()) * random.nextFloat() * 4.0f;

            if (random.nextInt(4) == 0) {
                continue;
            }
            if (!canReach(x, z, i, steps, thickness)) {
                return;
            }

//            double dist = Math.sqrt(Math.pow((x - -24848077), 2) + Math.pow((y - 21), 2) + Math.pow((z - 18720986), 2));
//            if (dist < 10) {
//                System.out.printf("sphere at dist: %f\n", Math.sqrt(dist));
//            }
            locs.add(new Coord(x, y, z));
//            carveSphere(seed, x, y, z, radius, vertical);
        }
    }

    public void genCanyon(long seed, double x, double y, double z, float thickness,
                           float yaw, float pitch, int start, int steps, double yScale) {
        JavaRandom random = new JavaRandom(seed);
        float width = 1.0f;
        for (int i = 0; i < 256; i++) {
            if (i == 0 || random.nextInt(3) == 0) {
                width = 1.0f + random.nextFloat() * random.nextFloat();
            }
            widths[i] = width * width;
        }

        float yawDelta = 0.0f;
        float pitchDelta = 0.0f;
        for (int i = start; i < steps; i++) {
            double radius = 1.5 + (double) (Mth.sin((float) i * (float) Math.PI / (float) steps) * thickness);
            double vertical = radius * yScale;
            radius *= (double) random.nextFloat() * 0.25 + 0.75;
            vertical *= (double) random.nextFloat() * 0.25 + 0.75;
            float cosPitch = Mth.cos(pitch);
            float sinPitch = Mth.sin(pitch);
            x += (double) (Mth.cos(yaw) * cosPitch);
            y += (double) sinPitch;
            z += (double) (Mth.sin(yaw) * cosPitch);
            pitch *= 0.7f;
            pitch += pitchDelta * 0.05f;
            yaw += yawDelta * 0.05f;
            pitchDelta *= 0.8f;
            yawDelta *= 0.5f;
            pitchDelta += (random.nextFloat() - random.nextFloat()) * random.nextFloat() * 2.0f;
            yawDelta += (random.nextFloat() - random.nextFloat()) * random.nextFloat() * 4.0f;

            if (random.nextInt(4) == 0) {
                continue;
            }
            if (!canReach(x, z, i, steps, thickness)) {
                return;
            }

//            double dist = Math.sqrt(Math.pow((x - -24848077), 2) + Math.pow((y - 21), 2) + Math.pow((z - 18720986), 2));
//            if (dist < 10) {
//                System.out.printf("sphere at dist: %f\n", Math.sqrt(dist));
//            }
            carveSphere(seed, x, y, z, radius, vertical);
        }
    }
}
