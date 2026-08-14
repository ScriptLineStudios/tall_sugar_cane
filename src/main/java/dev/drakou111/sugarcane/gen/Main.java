package dev.drakou111.sugarcane.gen;

import dev.drakou111.sugarcane.rng.JavaRandom;
import dev.drakou111.sugarcane.world.Blocks;
import kaptainwutax.mcutils.rand.ChunkRand;
import kaptainwutax.mcutils.util.math.DistanceMetric;
import kaptainwutax.mcutils.util.pos.BPos;
import kaptainwutax.mcutils.version.MCVersion;
import kaptainwutax.seedutils.lcg.LCG;

import java.util.ArrayList;


public class Main {
    public static int caneHeight(ChunkRand rand, BPos base) {
        ChunkRand random = new ChunkRand(rand.getSeed() ^ LCG.JAVA.multiplier);
        int height = 0;
        for (int n = 0; n < 60; n++) {
            int dx = random.nextInt(16);
            int dz = random.nextInt(16);
            int y = random.nextInt(126);

            for (int a = 0; a < 20; a++) {
                int ox = random.nextInt(5) - random.nextInt(5);
                int oy = random.nextInt(1) - random.nextInt(1);
                int oz = random.nextInt(5) - random.nextInt(5);

                BPos pos = new BPos(dx + ox, y + oy, oz + dz);
                if (pos.equals(base)) {
                    int dh = 2 + random.nextInt(random.nextInt(2 + 1) + 1);
                    base = base.add(0, dh, 0);
                    height += dh;
                }
            }
        }
        return height;
    }

    public static ArrayList<BPos> getDirt(long chunkSeed) {
        ChunkRand rand = new ChunkRand();
        long featureSeed = rand.setDecoratorSeed(chunkSeed, 0, 6, MCVersion.v1_16);

        OreBlob blob = new OreBlob(0, 0, new OreBlob.Target() {
            @Override
            public boolean isNaturalStone(int x, int y, int z) {
                return true;
            }

            @Override
            public void setDirt(int x, int y, int z) {}

            @Override
            public int oceanFloorHeight(int x, int z) {
                return 70;
            }
        }, OreBlob.DIRT_SIZE);

        ArrayList<BPos> positions = new ArrayList<BPos>();

        rand.setDecoratorSeed(chunkSeed, 0, 6, MCVersion.v1_16);
        for (int i = 0; i < OreBlob.DIRT_COUNT; i++) {
            int x = rand.nextInt(16);
            int z = rand.nextInt(16);
            int y = rand.nextInt(256);

            positions.add(new BPos(x, y, z));

            JavaRandom r = new JavaRandom(rand.getSeed() ^ LCG.JAVA.multiplier);
            blob.place(r, x, y, z);
            rand.setSeed(r.getRawSeed(), false);
        }

        return positions;
    }

    public static void main(String args[]) {
//        ArrayList<BPos> pos = getDirt(275655882975556L);
//        for (var p : pos) {
//            System.out.println(p);
//        }
//        System.exit(1);

        long decorationSeed = 213689390069527L;
        ChunkRand rand = new ChunkRand();

        int colX = 7;
        int colY = 14;
        int colZ = 0;
        int targetHeight = 19;
        BPos BASE = new BPos(colX, colY, colZ);

        long featureSeed = rand.setDecoratorSeed(decorationSeed, 5, 8, MCVersion.v1_16);

        int height = caneHeight(rand, BASE);
        int count = 0;
        while (height >= targetHeight) {
            long chunkSeed = (rand.getSeed() ^ LCG.JAVA.multiplier) - 80005;
            ArrayList<BPos> blocks = getDirt(chunkSeed);
            for (var block : blocks) {
                double dist = block.distanceTo(BASE, DistanceMetric.EUCLIDEAN);
                if (dist < 5.0f) {
                    System.out.printf("%s\n", block);
                    System.out.printf("%d %f %s\n", chunkSeed, dist, BASE.subtract(block));
                }
            }
            rand.advance(-123);
            height = caneHeight(rand, new BPos(colX, colY, colZ));
            count++;
        }
    }
}
