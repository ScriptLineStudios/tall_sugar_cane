package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.gen.SugarCaneFeature;
import dev.drakou111.sugarcane.rng.JavaRandom;
import dev.drakou111.sugarcane.world.ArrayWorld;
import dev.drakou111.sugarcane.world.Blocks;

/**
 * Measures P: given terrain that offers a stackable spot, how often does the
 * cane RNG actually build a column taller than 4?
 *
 * <p>The search cost is R x P per chunk, and R is now measured (the stackable
 * spot rate), but P was only ever measured on hand-built terrain — FINDINGS
 * section 4, where an isolated spot gave about 1.1e-5. Terrain the generator
 * actually produces is not one isolated spot: a carved cave wall offers a whole
 * run of adjacent positions, and P scales with that. The difference decides
 * whether the search takes hours or weeks, so it is worth measuring on real
 * geometry rather than assuming.
 *
 * <p>Method: take the 24x24 window around a chunk that has at least one
 * stackable spot, then replay {@code patch_sugar_cane} over it with many
 * synthetic decoration seeds, restoring the window each time. The decoration
 * seed is the only thing that varies, which is exactly the quantity a search
 * over seeds and chunk positions samples.
 */
public final class ProbabilityProbe {

    public static final int MARGIN = 4;
    public static final int WINDOW = 16 + 2 * MARGIN;

    private final ArrayWorld window;
    private final ArrayWorld pristine;
    private final JavaRandom random = new JavaRandom();

    public ProbabilityProbe() {
        this.window = new ArrayWorld(0, 0, WINDOW, WINDOW);
        this.pristine = new ArrayWorld(0, 0, WINDOW, WINDOW);
    }

    /**
     * Copies the window around (chunkX, chunkZ) out of {@code source} and returns
     * how many of {@code trials} decoration seeds produce a column taller than
     * {@code minHeight}.
     *
     * <p>The chunk keeps its real coordinates so the placement draws land in the
     * same place; only the seed changes.
     */
    public int measure(ArrayWorld source, int chunkX, int chunkZ, int count, int index,
                       int trials, int minHeight, long probeSeed) {
        int originX = chunkX * 16 - MARGIN;
        int originZ = chunkZ * 16 - MARGIN;
        pristine.reset(originX, originZ);
        for (int x = originX; x < originX + WINDOW; x++) {
            for (int z = originZ; z < originZ + WINDOW; z++) {
                for (int y = 0; y < ArrayWorld.HEIGHT; y++) {
                    byte b = source.getBlock(x, y, z);
                    if (b != Blocks.AIR) {
                        pristine.setBlock(x, y, z, b);
                    }
                }
            }
        }
        window.reset(originX, originZ);

        JavaRandom seeds = new JavaRandom(probeSeed);
        int hits = 0;
        for (int t = 0; t < trials; t++) {
            window.restoreFrom(pristine);
            long decorationSeed = seeds.nextLong();
            int tallest = 0;
            for (SugarCaneFeature.Column c : SugarCaneFeature.place(
                    window, decorationSeed, index, count, chunkX, chunkZ)) {
                tallest = Math.max(tallest, window.caneRunThrough(c.x(), c.y(), c.z()));
            }
            if (tallest >= minHeight) {
                hits++;
            }
        }
        return hits;
    }

    public JavaRandom random() {
        return random;
    }
}
