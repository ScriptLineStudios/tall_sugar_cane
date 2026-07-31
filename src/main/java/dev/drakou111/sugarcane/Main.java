package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.gen.Scenarios;
import dev.drakou111.sugarcane.gen.SugarCaneFeature;
import dev.drakou111.sugarcane.world.ArrayWorld;

/**
 * Measures the RNG side of the problem in isolation.
 *
 * <p>For a fixed terrain snapshot, run the 1.16.1 sugar cane feature over many
 * decoration seeds and report the distribution of the tallest column produced.
 * This separates "how lucky does the chunk RNG have to be" from "how rare is the
 * terrain", which are wildly different magnitudes.
 */
public final class Main {

    public static void main(String[] args) {
        long trials = args.length > 0 ? Long.parseLong(args[0]) : 200_000L;

        System.out.println("1.16.1 patch_sugar_cane — tallest column per chunk over " + trials + " decoration seeds");
        System.out.println();

        for (int wallHeight : new int[]{1, 2, 3, 4}) {
            ArrayWorld template = Scenarios.shore(wallHeight);
            System.out.printf("water column beside the soil is %d block(s) tall (Y=%d..%d)%n",
                    wallHeight, Scenarios.SOIL_Y, Scenarios.SOIL_Y + wallHeight - 1);
            for (int[] biome : new int[][]{
                    {SugarCaneFeature.COUNT_DEFAULT, 0},
                    {SugarCaneFeature.COUNT_SWAMP, 0},
                    {SugarCaneFeature.COUNT_DESERT, 0}}) {
                run(template, biome[0], trials);
            }
            System.out.println();
        }

        System.out.println("structure-cut air pocket below sea level (sea floor Y=57, water Y=58..62)");
        ArrayWorld pocket = Scenarios.structureAirPocket(57, 62);
        for (int count : new int[]{SugarCaneFeature.COUNT_DEFAULT, SugarCaneFeature.COUNT_DESERT}) {
            run(pocket, count, trials);
        }
        System.out.println();

        System.out.println("ONE isolated stackable spot in the chunk (the realistic case)");
        for (int[] cfg : new int[][]{{52, 56, 63}, {62, 65, 63}}) {
            ArrayWorld one = Scenarios.isolatedStackableSpot(cfg[0], cfg[1], cfg[2]);
            System.out.printf("  soil Y=%d, water Y=%d..%d, ground Y=%d%n",
                    cfg[0], cfg[0], cfg[1], cfg[2]);
            for (int count : new int[]{SugarCaneFeature.COUNT_DEFAULT, SugarCaneFeature.COUNT_DESERT}) {
                run(one, count, trials);
            }
        }
    }

    private static void run(ArrayWorld template, int count, long trials) {
        long[] histogram = new long[16];
        long over4 = 0;
        ArrayWorld world = template.copy();
        for (long seed = 0; seed < trials; seed++) {
            world.restoreFrom(template);
            int tallest = 0;
            for (SugarCaneFeature.Column c : SugarCaneFeature.place(world, seed, 0, count, 0, 0)) {
                tallest = Math.max(tallest, world.caneRunThrough(c.x(), c.y(), c.z()));
            }
            tallest = Math.min(tallest, histogram.length - 1);
            histogram[tallest]++;
            if (tallest > 4) {
                over4++;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int h = 2; h < histogram.length; h++) {
            if (histogram[h] > 0) {
                sb.append(' ').append(h).append(':').append(histogram[h]);
            }
        }
        System.out.printf("  count=%-3d  >4 in %d/%d (%.4f%%)  heights:%s%n",
                count, over4, trials, 100.0 * over4 / trials, sb);
    }
}
