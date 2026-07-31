package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.gen.Terrain;
import dev.drakou111.sugarcane.world.Blocks;
import kaptainwutax.biomeutils.source.OverworldBiomeSource;
import kaptainwutax.mcutils.version.MCVersion;

/**
 * Prints the raw noise terrain for a slice, before any surface builder, carver or
 * feature runs.
 *
 * <pre>
 * java ... ProbeColumns &lt;seed&gt; &lt;x0&gt; &lt;x1&gt; &lt;z&gt; &lt;y0&gt; &lt;y1&gt;
 * </pre>
 *
 * <p>The point is to separate two very different failures when the simulation
 * disagrees with a real world: noise terrain that is the wrong height, versus a
 * carver that removed the wrong blocks. Everything downstream depends on which one
 * it is.
 */
public final class ProbeColumns {

    private ProbeColumns() {
    }

    public static void main(String[] args) {
        long seed = Long.parseLong(args[0]);
        int x0 = Integer.parseInt(args[1]);
        int x1 = Integer.parseInt(args[2]);
        int z = Integer.parseInt(args[3]);
        int y0 = Integer.parseInt(args[4]);
        int y1 = Integer.parseInt(args[5]);

        OverworldBiomeSource biomes = new OverworldBiomeSource(MCVersion.v1_16_1, seed);
        Terrain terrain = new Terrain(biomes);
        byte[] column = new byte[256];

        System.out.printf("seed %d, raw noise terrain at z=%d (no surface, no carvers)%n", seed, z);
        System.out.print("      ");
        for (int x = x0; x <= x1; x++) {
            System.out.print(Math.abs(x) % 10);
        }
        System.out.println();

        byte[][] slice = new byte[x1 - x0 + 1][];
        int[] heights = new int[x1 - x0 + 1];
        for (int x = x0; x <= x1; x++) {
            byte[] copy = new byte[256];
            heights[x - x0] = terrain.column(x, z, column);
            System.arraycopy(column, 0, copy, 0, 256);
            slice[x - x0] = copy;
        }
        for (int y = y1; y >= y0; y--) {
            System.out.printf("y=%3d ", y);
            for (int x = x0; x <= x1; x++) {
                byte b = slice[x - x0][y];
                System.out.print(b == Blocks.SOLID ? '#' : b == Blocks.WATER ? '~' : '.');
            }
            System.out.println();
        }
        System.out.print("WORLD_SURFACE_WG: ");
        for (int x = x0; x <= x1; x++) {
            System.out.printf("%d=%d ", x, heights[x - x0]);
        }
        System.out.println();
        System.out.print("noise biome at quart: ");
        for (int x = x0; x <= x1; x += 4) {
            System.out.printf("%d=%d ", x,
                    biomes.getBiomeForNoiseGen(x >> 2, 0, z >> 2).getId());
        }
        System.out.println();
        System.out.println("      # stone  ~ water  . air");
    }
}
