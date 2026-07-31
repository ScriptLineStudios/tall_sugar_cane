package dev.drakou111.sugarcane.gen;

import kaptainwutax.noiseutils.noise.Noise;
import kaptainwutax.noiseutils.perlin.PerlinNoiseSampler;
import kaptainwutax.noiseutils.utils.MathHelper;

import java.lang.reflect.Field;

/**
 * One Perlin octave, evaluated down a column.
 *
 * <p>{@code sampleNoiseColumn} asks for 14 cell values at a single (x, z), and the
 * library's {@code sample} redoes the whole lattice setup for each. Half of that
 * setup does not depend on y: the x and z sections, their fractions and fades, and
 * the two permutation lookups taken before the section y is added in.
 * {@link #beginColumn} does that once and {@link #sampleY} reuses it.
 *
 * <p>Transcribed from the bytecode of {@code PerlinNoiseSampler.sample} — same
 * lookups in the same order, same arguments to {@code grad}, same {@code lerp3}. The
 * fade for y comes from the local y <em>before</em> the y-scale term is subtracted,
 * which is easy to get backwards. {@code TruncatedNoiseTest} compares whole columns
 * against TerrainUtils, so a divergence fails the build.
 */
final class ColumnPerlin {

    private static final Field PERMUTATIONS;

    static {
        try {
            PERMUTATIONS = Noise.class.getDeclaredField("permutations");
            PERMUTATIONS.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("NoiseUtils internals changed", e);
        }
    }

    private final byte[] perm;
    private final double originX;
    private final double originY;
    private final double originZ;

    private int sectionZ;
    private int permX;
    private int permX1;
    private double localX;
    private double localZ;
    private double localX1;
    private double localZ1;
    private double fadeX;
    private double fadeZ;

    ColumnPerlin(PerlinNoiseSampler sampler) {
        try {
            this.perm = (byte[]) PERMUTATIONS.get(sampler);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("NoiseUtils internals changed", e);
        }
        this.originX = sampler.originX;
        this.originY = sampler.originY;
        this.originZ = sampler.originZ;
    }

    void beginColumn(double x, double z) {
        double dx = x + originX;
        double dz = z + originZ;
        int sectionX = MathHelper.floor(dx);
        sectionZ = MathHelper.floor(dz);
        localX = dx - sectionX;
        localZ = dz - sectionZ;
        localX1 = localX - 1.0;
        localZ1 = localZ - 1.0;
        fadeX = MathHelper.smoothStep(localX);
        fadeZ = MathHelper.smoothStep(localZ);
        permX = perm[sectionX & 255] & 255;
        permX1 = perm[sectionX + 1 & 255] & 255;
    }

    double sampleY(double y, double yScale, double yMax) {
        final byte[] p = perm;
        final double lx = localX;
        final double lx1 = localX1;
        final double lz = localZ;
        final double lz1 = localZ1;
        final int sz = sectionZ;

        double dy = y + originY;
        int sectionY = MathHelper.floor(dy);
        double local = dy - sectionY;
        double step = 0.0;
        if (yScale != 0.0) {
            double capped = Math.min(yMax, local);
            step = (double) MathHelper.floor(capped / yScale) * yScale;
        }
        double ly = local - step;
        double ly1 = ly - 1.0;

        int i = permX + sectionY;
        int j = permX1 + sectionY;
        int k = (p[i & 255] & 255) + sz;
        int l = (p[j & 255] & 255) + sz;
        int m = (p[i + 1 & 255] & 255) + sz;
        int n = (p[j + 1 & 255] & 255) + sz;

        double d0 = MathHelper.grad(p[k & 255] & 255, lx, ly, lz);
        double d1 = MathHelper.grad(p[l & 255] & 255, lx1, ly, lz);
        double d2 = MathHelper.grad(p[m & 255] & 255, lx, ly1, lz);
        double d3 = MathHelper.grad(p[n & 255] & 255, lx1, ly1, lz);
        double d4 = MathHelper.grad(p[k + 1 & 255] & 255, lx, ly, lz1);
        double d5 = MathHelper.grad(p[l + 1 & 255] & 255, lx1, ly, lz1);
        double d6 = MathHelper.grad(p[m + 1 & 255] & 255, lx, ly1, lz1);
        double d7 = MathHelper.grad(p[n + 1 & 255] & 255, lx1, ly1, lz1);

        return MathHelper.lerp3(fadeX, MathHelper.smoothStep(local), fadeZ,
                d0, d1, d2, d3, d4, d5, d6, d7);
    }
}
