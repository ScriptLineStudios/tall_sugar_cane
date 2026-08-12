package dev.drakou111.sugarcane.gen;

import kaptainwutax.biomeutils.source.OverworldBiomeSource;
import kaptainwutax.noiseutils.perlin.OctavePerlinNoiseSampler;
import kaptainwutax.noiseutils.utils.MathHelper;
import kaptainwutax.terrainutils.terrain.SurfaceGenerator;
import kaptainwutax.terrainutils.utils.NoiseSettings;

import java.lang.reflect.Field;

/**
 * The density field of {@code OverworldTerrainGenerator}, evaluated only up to a
 * cut-off height.
 *
 * <p>Profiling says the search spends about 78% of its time in this one
 * computation: the noise column is 33 cell values tall and each costs 40 octaves
 * of Perlin noise. Everything the search looks at is below y=104 — ocean floors,
 * caves, canyons and dirt blobs — so evaluating cells above that is pure waste.
 * Cutting there leaves 13 of 33 cells.
 *
 * <p>This is a transcription of {@code SurfaceGenerator.sampleNoiseColumn} and
 * {@code generateColumn} with the y loops bounded. It reaches into the generator
 * for the samplers rather than reseeding its own, so there is nothing to get
 * wrong about the RNG chain, and {@code TruncatedNoiseTest} asserts that every
 * block below the cut matches what TerrainUtils itself produces.
 *
 * <p>A column whose terrain reaches the cut is not usable — there may be more
 * above it, and the surface builder and the placement heightmap both need the
 * real top. {@link #column} reports that so the caller can fall back to the full
 * generator.
 */
public final class TruncatedNoise {

    /** Cells are 8 blocks tall here, so 13 cells is everything below y=104. */
    public static final int CELLS = 13;
    /**
     * Tried and reverted: stopping at y=63 for ocean columns (the heightmap over
     * open water is always 63) measured only 1.075x and silently changed results,
     * because an overhang can put stone above an air block at 63. The early exit
     * assumed nothing above, which is exactly the class of heightmap error that
     * desynchronises a chunk's whole cane stream. Not worth 7%.
     */
    public static final int CELL_HEIGHT = 8;
    public static final int CUT = CELLS * CELL_HEIGHT;

    private static final float[] BIOME_WEIGHT = new float[25];

    static {
        for (int rx = -2; rx <= 2; rx++) {
            for (int rz = -2; rz <= 2; rz++) {
                BIOME_WEIGHT[rx + 2 + (rz + 2) * 5] =
                        (float) (10.0f / Math.sqrt((float) (rx * rx + rz * rz) + 0.2f));
            }
        }
    }

    private final OverworldBiomeSource biomeSource;
    private final OctavePerlinNoiseSampler minLimit;
    private final OctavePerlinNoiseSampler maxLimit;
    private final OctavePerlinNoiseSampler main;
    private final OctavePerlinNoiseSampler depth;
    private final NoiseSettings settings;
    private final double densityFactor;
    private final double densityOffset;
    private final int noiseSizeY;
    /**
     * Sampling constants and the per-octave samplers, hoisted out of
     * {@link #sampleNoise}. That method runs once per cell value — a few million
     * times a second — and was re-reading the settings and re-indexing the octave
     * arrays on every call.
     */
    private final double xzScale;
    private final double yScale;
    private final double xzStep;
    private final double yStep;
    private final ColumnPerlin[] mainColumn = new ColumnPerlin[8];
    private final ColumnPerlin[] minColumn = new ColumnPerlin[16];
    private final ColumnPerlin[] maxColumn = new ColumnPerlin[16];
    private final kaptainwutax.noiseutils.perlin.PerlinNoiseSampler[] mainOctaves;
    private final kaptainwutax.noiseutils.perlin.PerlinNoiseSampler[] minOctaves;
    private final kaptainwutax.noiseutils.perlin.PerlinNoiseSampler[] maxOctaves;
    /**
     * Noise columns and biome depth/scale per cell, over the region being generated.
     *
     * <p>These were {@code HashMap<Long, ...>}. The hit rate was already good — the
     * caches live for a whole region — but every probe boxed a long and walked a
     * bucket, and a profile put that at 5% of the search on its own. The access
     * pattern is a known rectangle, so a flat array indexed by cell offset does the
     * same job with an array read. Anything outside the window still works, it just
     * recomputes; that keeps callers that do not declare a region correct.
     */
    private int cellOriginX;
    private int cellOriginZ;
    private int cellSpanX;
    private int cellSpanZ;
    private double[][] columns = new double[0][];
    private float[] cellDepth = new float[0];
    private float[] cellScale = new float[0];
    private boolean[] cellKnown = new boolean[0];

    public TruncatedNoise(SurfaceGenerator generator, OverworldBiomeSource biomeSource) {
        this.biomeSource = biomeSource;
        this.minLimit = field(generator, "minLimitPerlinNoise");
        this.maxLimit = field(generator, "maxLimitPerlinNoise");
        this.main = field(generator, "mainPerlinNoise");
        this.depth = field(generator, "noiseSampler");
        try {
            Field f = SurfaceGenerator.class.getDeclaredField("noiseSettings");
            f.setAccessible(true);
            this.settings = (NoiseSettings) f.get(generator);
            this.densityFactor = (double) read(generator, "densityFactor");
            this.densityOffset = (double) read(generator, "densityOffset");
            this.noiseSizeY = (int) read(generator, "noiseSizeY");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("TerrainUtils internals changed", e);
        }
        this.xzScale = 684.412 * settings.samplingSettings.xzScale;
        this.yScale = 684.412 * settings.samplingSettings.yScale;
        this.xzStep = xzScale / settings.samplingSettings.xzFactor;
        this.yStep = yScale / settings.samplingSettings.yFactor;
        this.mainOctaves = new kaptainwutax.noiseutils.perlin.PerlinNoiseSampler[8];
        for (int i = 0; i < 8; i++) {
            mainOctaves[i] = main.getOctave(i);
            mainColumn[i] = new ColumnPerlin(mainOctaves[i]);
        }
        this.minOctaves = new kaptainwutax.noiseutils.perlin.PerlinNoiseSampler[16];
        this.maxOctaves = new kaptainwutax.noiseutils.perlin.PerlinNoiseSampler[16];
        for (int i = 0; i < 16; i++) {
            minOctaves[i] = minLimit.getOctave(i);
            maxOctaves[i] = maxLimit.getOctave(i);
            minColumn[i] = new ColumnPerlin(minOctaves[i]);
            maxColumn[i] = new ColumnPerlin(maxOctaves[i]);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(SurfaceGenerator generator, String name) {
        return (T) read(generator, name);
    }

    private static Object read(SurfaceGenerator generator, String name) {
        try {
            Field f = SurfaceGenerator.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(generator);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("TerrainUtils internals changed: " + name, e);
        }
    }

    /**
     * Points the caches at the region about to be generated, in block coordinates.
     * The margin covers the extra cell {@link #column} interpolates towards and the
     * 5x5 biome neighbourhood {@code getDepthAndScale} averages over.
     */
    public void beginRegion(int originBlockX, int originBlockZ, int spanBlocks) {
        cellOriginX = Math.floorDiv(originBlockX, 4) - 3;
        cellOriginZ = Math.floorDiv(originBlockZ, 4) - 3;
        int span = spanBlocks / 4 + 7;
        if (span != cellSpanX) {
            cellSpanX = span;
            cellSpanZ = span;
            columns = new double[span * span][];
            cellDepth = new float[span * span];
            cellScale = new float[span * span];
            cellKnown = new boolean[span * span];
        } else {
            java.util.Arrays.fill(columns, null);
            java.util.Arrays.fill(cellKnown, false);
        }
    }

    public void clearCache() {
        java.util.Arrays.fill(columns, null);
        java.util.Arrays.fill(cellKnown, false);
    }

    /** Flat index of a cell, or -1 when it falls outside the declared region. */
    private int slot(int cellX, int cellZ) {
        int dx = cellX - cellOriginX;
        int dz = cellZ - cellOriginZ;
        if (dx < 0 || dz < 0 || dx >= cellSpanX || dz >= cellSpanZ) {
            return -1;
        }
        return dx * cellSpanZ + dz;
    }

    private float depthAt(int cellX, int cellZ) {
        int slot = slot(cellX, cellZ);
        if (slot < 0) {
            return BiomeIds.depth(BiomeIds.noiseGen(biomeSource, cellX, cellZ));
        }
        if (!cellKnown[slot]) {
            fill(slot, cellX, cellZ);
        }
        return cellDepth[slot];
    }

    private float scaleAt(int cellX, int cellZ) {
        int slot = slot(cellX, cellZ);
        if (slot < 0) {
            return BiomeIds.scale(BiomeIds.noiseGen(biomeSource, cellX, cellZ));
        }
        if (!cellKnown[slot]) {
            fill(slot, cellX, cellZ);
        }
        return cellScale[slot];
    }

    private void fill(int slot, int cellX, int cellZ) {
        int id = BiomeIds.noiseGen(biomeSource, cellX, cellZ);
        cellDepth[slot] = BiomeIds.depth(id);
        cellScale[slot] = BiomeIds.scale(id);
        cellKnown[slot] = true;
    }

    /**
     * Fills {@code out} with the column at (x, z) below {@link #CUT}, in the byte
     * palette given by the three block codes, and everything above with
     * {@code air}.
     *
     * @return the WORLD_SURFACE_WG height (one past the highest non-air block), or
     *         -1 if the terrain reaches the cut-off and the caller must fall back
     */
    public int column(int x, int z, byte[] out, byte stone, byte water, byte air, int seaLevel) {
        int cellX = Math.floorDiv(x, 4);
        int cellZ = Math.floorDiv(z, 4);
        double percentX = (double) Math.floorMod(x, 4) / 4.0;
        double percentZ = (double) Math.floorMod(z, 4) / 4.0;
        double[] c00 = noiseColumn(cellX, cellZ);
        double[] c01 = noiseColumn(cellX, cellZ + 1);
        double[] c10 = noiseColumn(cellX + 1, cellZ);
        double[] c11 = noiseColumn(cellX + 1, cellZ + 1);

        java.util.Arrays.fill(out, CUT, out.length, air);
        int height = 0;
        for (int cellY = CELLS - 1; cellY >= 0; cellY--) {
            double xyz = c00[cellY];
            double xyz1 = c01[cellY];
            double x1yz = c10[cellY];
            double x1yz1 = c11[cellY];
            double xy1z = c00[cellY + 1];
            double xy1z1 = c01[cellY + 1];
            double x1y1z = c10[cellY + 1];
            double x1y1z1 = c11[cellY + 1];
            for (int posY = CELL_HEIGHT - 1; posY >= 0; posY--) {
                double percentY = (double) posY / (double) CELL_HEIGHT;
                double noise = MathHelper.lerp3(percentY, percentX, percentZ,
                        xyz, xy1z, x1yz, x1y1z, xyz1, xy1z1, x1yz1, x1y1z1);
                int y = cellY * CELL_HEIGHT + posY;
                byte block = noise > 0.0 ? stone : (y < seaLevel ? water : air);
                out[y] = block;
                if (block != air && height == 0) {
                    height = y + 1;
                }
            }
        }
        return height == CUT ? -1 : height;
    }

    private double[] noiseColumn(int cellX, int cellZ) {
        int slot = slot(cellX, cellZ);
        if (slot >= 0) {
            double[] cached = columns[slot];
            if (cached != null) {
                return cached;
            }
        }
        double[] buffer = new double[CELLS + 1];
        sampleNoiseColumn(buffer, cellX, cellZ, 0, CELLS);
        if (slot >= 0) {
            columns[slot] = buffer;
        }
        return buffer;
    }

    /**
     * {@code SurfaceGenerator.sampleNoiseColumn} for cells [from, to]. Each y is
     * independent of the others, so computing a prefix now and the rest later is
     * exactly equivalent to computing them all at once.
     */
    private void sampleNoiseColumn(double[] buffer, int x, int z, int from, int to) {
        double[] depthAndScale = depthAndScale(x, z);
        double biomeDepth = depthAndScale[0];
        double biomeScale = depthAndScale[1];
        double randomOffset = randomDensityOffset(x, z);

        beginColumns(x, z);

        for (int y = from; y <= to; y++) {
            double noise = sampleNoise(y);
            double fallOff = 1.0 - (double) y * 2.0 / (double) noiseSizeY + randomOffset;
            fallOff = fallOff * densityFactor + densityOffset;
            fallOff = (fallOff + biomeDepth) * biomeScale;
            noise = fallOff > 0.0 ? noise + fallOff * 4.0 : noise + fallOff;
            if ((double) settings.topSlideSettings.size > 0.0) {
                noise = clampedLerp(settings.topSlideSettings.target, noise,
                        ((double) (noiseSizeY - y) - (double) settings.topSlideSettings.offset)
                                / (double) settings.topSlideSettings.size);
            }
            if ((double) settings.bottomSlideSettings.size > 0.0) {
                noise = clampedLerp(settings.bottomSlideSettings.target, noise,
                        ((double) y - (double) settings.bottomSlideSettings.offset)
                                / (double) settings.bottomSlideSettings.size);
            }
            buffer[y] = noise;
        }
    }

    /**
     * The density at one noise cell point.
     *
     * <p>Vanilla accumulates all three noises in one loop and then does
     * {@code clampedLerp(min/512, max/512, (main/10 + 1)/2)}. But clampedLerp
     * returns {@code min} outright when the selector is <= 0 and {@code max} when
     * it is >= 1, and the selector saturates **83.8% of the time** — so most of the
     * time one of the two sixteen-octave limit noises is computed and thrown away.
     *
     * <p>Computing the eight-octave selector first and then only the limit noise
     * that can matter is numerically identical — the discarded value never reaches
     * the result — and cuts the average from 40 octave evaluations to about 26.
     * {@code TruncatedNoiseTest} still checks every block against TerrainUtils.
     */
    /** Every octave's x/z lattice state for one column, shared by its 14 y values. */
    private void beginColumns(int x, int z) {
        double persistence = 1.0;
        for (int octave = 0; octave < 8; octave++) {
            mainColumn[octave].beginColumn(
                    MathHelper.maintainPrecision((double) x * xzStep * persistence),
                    MathHelper.maintainPrecision((double) z * xzStep * persistence));
            persistence /= 2.0;
        }
        persistence = 1.0;
        for (int octave = 0; octave < 16; octave++) {
            double cellX = MathHelper.maintainPrecision((double) x * xzScale * persistence);
            double cellZ = MathHelper.maintainPrecision((double) z * xzScale * persistence);
            minColumn[octave].beginColumn(cellX, cellZ);
            maxColumn[octave].beginColumn(cellX, cellZ);
            persistence /= 2.0;
        }
    }

    private double sampleNoise(int y) {
        // The selector, eight octaves.
        double mainNoise = 0.0;
        double persistence = 1.0;
        for (int octave = 0; octave < 8; octave++) {
            mainNoise += mainColumn[octave].sampleY(
                    MathHelper.maintainPrecision((double) y * yStep * persistence),
                    yStep * persistence, (double) y * yStep * persistence) / persistence;
            persistence /= 2.0;
        }
        double t = (mainNoise / 10.0 + 1.0) / 2.0;

        // Only the limit noise the lerp can actually return.
        boolean needMin = t < 1.0;
        boolean needMax = t > 0.0;
        double minNoise = 0.0;
        double maxNoise = 0.0;
        persistence = 1.0;
        for (int octave = 0; octave < 16; octave++) {
            double cellY = MathHelper.maintainPrecision((double) y * yScale * persistence);
            double sy = yScale * persistence;
            if (needMin) {
                minNoise += minColumn[octave].sampleY(cellY, sy, (double) y * sy) / persistence;
            }
            if (needMax) {
                maxNoise += maxColumn[octave].sampleY(cellY, sy, (double) y * sy) / persistence;
            }
            persistence /= 2.0;
        }
        return clampedLerp(minNoise / 512.0, maxNoise / 512.0, t);
    }

    /** {@code getDepthAndScale}: the 5x5 weighted average of biome depth and scale. */
    private double[] depthAndScale(int x, int z) {
        float weightedScale = 0.0f;
        float weightedDepth = 0.0f;
        float totalWeight = 0.0f;
        float depthAtCentre = depthAt(x, z);
        for (int rx = -2; rx <= 2; rx++) {
            for (int rz = -2; rz <= 2; rz++) {
                float biomeDepth = depthAt(x + rx, z + rz);
                float biomeScale = scaleAt(x + rx, z + rz);
                float weight = BIOME_WEIGHT[rx + 2 + (rz + 2) * 5] / (biomeDepth + 2.0f);
                if (biomeDepth > depthAtCentre) {
                    weight /= 2.0f;
                }
                weightedScale += biomeScale * weight;
                weightedDepth += biomeDepth * weight;
                totalWeight += weight;
            }
        }
        weightedScale /= totalWeight;
        weightedDepth /= totalWeight;
        weightedScale = weightedScale * 0.9f + 0.1f;
        weightedDepth = (weightedDepth * 4.0f - 1.0f) / 8.0f;
        return new double[]{(double) weightedDepth * 17.0 / 64.0, 96.0 / (double) weightedScale};
    }

    /** The 1.16 {@code random_density_offset} term. */
    private double randomDensityOffset(int x, int z) {
        double noise = depth.sample(x * 200, 10.0, z * 200, 1.0, 0.0, true);
        noise = noise < 0.0 ? -noise * 0.3 : noise;
        noise = noise * 24.575625 - 2.0;
        if (noise < 0.0) {
            return 17.0 * noise / 28.0 / 64.0;
        }
        return Math.min(noise, 1.0) * 17.0 / 40.0 / 64.0;
    }

    private static double clampedLerp(double a, double b, double t) {
        return kaptainwutax.terrainutils.utils.MathHelper.clampedLerp(a, b, t);
    }
}
