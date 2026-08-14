package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.gen.CanyonCarver;
import dev.drakou111.sugarcane.gen.Carver;
import dev.drakou111.sugarcane.gen.CarverConfig;
import dev.drakou111.sugarcane.gen.CaveCarver;
import dev.drakou111.sugarcane.rng.JavaRandom;
import dev.drakou111.sugarcane.world.ArrayWorld;
import dev.drakou111.sugarcane.world.Blocks;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.drakou111.sugarcane.RegionSearcher.SEA;
import static dev.drakou111.sugarcane.Searcher.runDirtBlobs;
import static dev.drakou111.sugarcane.Searcher.runDirtBlobsTest;

/**
 * Regenerates one region and prints what the simulator thinks is at a position:
 * a vertical slice, every cane column nearby, and the water face the placement
 * depended on.
 *
 * <pre>
 * java ... Inspect &lt;seed&gt; &lt;x&gt; &lt;y&gt; &lt;z&gt; [searchRadiusChunks]
 * </pre>
 *
 * <p>Two uses. Before spending two minutes on a real server, it confirms the hit
 * is what it looks like. Afterwards, if the server disagrees, the slice says
 * which block the simulator got wrong — the usual suspects being the
 * unimplemented lakes and disks, or a neighbour chunk that the real world
 * decorated in a different order.
 */
public final class Inspect {

    private static final char[] GLYPH = new char[16];

    static {
        GLYPH[Blocks.AIR] = '.';
        GLYPH[Blocks.SOLID] = '#';
        GLYPH[Blocks.WATER] = '~';
        GLYPH[Blocks.FLOWING_WATER] = '~';
        GLYPH[Blocks.SAND] = 's';
        GLYPH[Blocks.RED_SAND] = 'S';
        GLYPH[Blocks.DIRT] = 'd';
        GLYPH[Blocks.COARSE_DIRT] = 'c';
        GLYPH[Blocks.PODZOL] = 'p';
        GLYPH[Blocks.GRASS_BLOCK] = 'g';
        GLYPH[Blocks.SUGAR_CANE] = 'C';
        GLYPH[Blocks.FROSTED_ICE] = 'i';
        GLYPH[Blocks.PLANT] = ',';
        GLYPH[Blocks.GRAVEL] = 'v';
        GLYPH[Blocks.ICE] = 'I';
    }

    private Inspect() {
    }

    public static void search2(long worldSeed, int tx, int tz) {
        long seed = worldSeed;
        tx = tx >> 4 << 4;
        int ty = 20;
        tz = tz >> 4 << 4;
        int chunkX = tx >> 4, chunkZ = tz >> 4;

        int radius = 1;
        RegionSearcher.Stats stats = new RegionSearcher.Stats();
        RegionSearcher.Worker worker = new RegionSearcher.Worker(5, false, 0, stats, radius);
        worker.prepare(seed);
        RegionSearcher.traceChunkX = chunkX;
        RegionSearcher.traceChunkZ = chunkZ;
        // Regions are aligned to multiples of REGION starting from -radius, and the
        // searcher uses radius 32, so the grid is offset by -32.
        int originX = alignRegion(chunkX, radius);
        int originZ = alignRegion(chunkZ, radius);
//        System.out.printf("seed %d, target %d,%d,%d (chunk %d,%d) in region %d,%d%n",
//                seed, tx, ty, tz, chunkX, chunkZ, originX, originZ);
        worker.searchRegion2(originX, originZ, chunkX, chunkZ, worldSeed);

        ArrayWorld world = worker.world;
//        System.out.printf("%ncane columns within 6 blocks:%n");
        boolean any = false;
        for (int x = tx; x <= tx + 16; x++) {
            for (int z = tz; z <= tz + 32; z++) {
                for (int y = Math.max(1, ty - 8); y <= ty + 8; y++) {
                    int height = world.caneHeightAt(x, y, z);
                    if (height == 0) {
                        continue;
                    }
                    any = true;
                    if (height >= 4) {
                        int modX = x - (chunkX << 4);
                        int modZ = z - ((chunkZ + 1) << 4);
                        if (modX == 3 && modZ == -2) {
                            System.out.printf("  \n worldSeed: %d height %d at %d,%d,%d standing on %s%s%n",
                                    worldSeed, height, x, y, z, name(world.getBlock(x, y - 1, z)),
                                    x == tx && y == ty && z == tz ? "   <== target" : "");

                            JavaRandom random = new JavaRandom();
                            System.out.printf("        top decor: %d\n", random.setDecorationSeed(worldSeed, chunkX << 4, chunkZ << 4) & ((1L << 48)-1));
                            System.out.printf("        bottom decor: %d\n", random.setDecorationSeed(worldSeed, chunkX << 4, (chunkZ + 1) << 4) & ((1L << 48) - 1));
                            System.out.printf("        dx: %d dz: %d\n", modX, modZ);
                        }
                    }
                }
            }
        }
        if (!any) {
//            System.out.println("  none - the simulator does not reproduce this by itself");
        }
//
//        System.out.printf("%nwater beside the soil at %d,%d,%d: %s%n", tx, ty - 1, tz,
//                describeNeighbours(world, tx, ty - 1, tz));
//        System.out.printf("water beside the block above:      %s%n",
//                describeNeighbours(world, tx, ty + 1, tz));
//
//        System.out.printf("%nslice at z=%d, x from %d to %d (y downwards)%n",
//                tz, tx - 8, tx + 8);
//        System.out.print("      ");
//        for (int x = tx - 8; x <= tx + 8; x++) {
//            System.out.print(Math.abs(x) % 10);
//        }
//        System.out.println();
//        for (int y = Math.min(ArrayWorld.HEIGHT - 1, ty + 10); y >= Math.max(0, ty - 10); y--) {
//            System.out.printf("y=%3d ", y);
//            for (int x = tx - 8; x <= tx + 8; x++) {
//                System.out.print(GLYPH[world.getBlock(x, y, tz)]);
//            }
//            System.out.println(y == ty ? "  <== target y" : "");
//        }
//        System.out.println("      . air  # stone  ~ water  v gravel  d dirt  s sand  "
//                + "g grass  C cane");
    }

    public static void search(long worldSeed, int tx, int tz) {
        long seed = worldSeed;
        tx = tx >> 4 << 4;
        int ty = 20;
        tz = tz >> 4 << 4;
        int chunkX = tx >> 4, chunkZ = tz >> 4;

        int radius = 3;
        RegionSearcher.Stats stats = new RegionSearcher.Stats();
        RegionSearcher.Worker worker = new RegionSearcher.Worker(5, false, 0, stats, radius);
        worker.prepare(seed);
        RegionSearcher.traceChunkX = chunkX;
        RegionSearcher.traceChunkZ = chunkZ;
        // Regions are aligned to multiples of REGION starting from -radius, and the
        // searcher uses radius 32, so the grid is offset by -32.
        int originX = alignRegion(chunkX, radius);
        int originZ = alignRegion(chunkZ, radius);
//        System.out.printf("seed %d, target %d,%d,%d (chunk %d,%d) in region %d,%d%n",
//                seed, tx, ty, tz, chunkX, chunkZ, originX, originZ);
        worker.searchRegion(originX, originZ, chunkX, chunkZ, worldSeed);

        ArrayWorld world = worker.world;
//        System.out.printf("%ncane columns within 6 blocks:%n");
        boolean any = false;
        for (int x = tx - 16; x <= tx + 16; x++) {
            for (int z = tz - 16; z <= tz + 16; z++) {
                for (int y = Math.max(1, ty - 8); y <= ty + 8; y++) {
                    int height = world.caneHeightAt(x, y, z);
                    if (height == 0) {
                        continue;
                    }
                    any = true;
                    if (height >= 14) {
                        System.out.printf("  height %d at %d,%d,%d standing on %s%s%n",
                                height, x, y, z, name(world.getBlock(x, y - 1, z)),
                                x == tx && y == ty && z == tz ? "   <== target" : "");
                    }
                }
            }
        }
        if (!any) {
//            System.out.println("  none - the simulator does not reproduce this by itself");
        }
//
//        System.out.printf("%nwater beside the soil at %d,%d,%d: %s%n", tx, ty - 1, tz,
//                describeNeighbours(world, tx, ty - 1, tz));
//        System.out.printf("water beside the block above:      %s%n",
//                describeNeighbours(world, tx, ty + 1, tz));
//
//        System.out.printf("%nslice at z=%d, x from %d to %d (y downwards)%n",
//                tz, tx - 8, tx + 8);
//        System.out.print("      ");
//        for (int x = tx - 8; x <= tx + 8; x++) {
//            System.out.print(Math.abs(x) % 10);
//        }
//        System.out.println();
//        for (int y = Math.min(ArrayWorld.HEIGHT - 1, ty + 10); y >= Math.max(0, ty - 10); y--) {
//            System.out.printf("y=%3d ", y);
//            for (int x = tx - 8; x <= tx + 8; x++) {
//                System.out.print(GLYPH[world.getBlock(x, y, tz)]);
//            }
//            System.out.println(y == ty ? "  <== target y" : "");
//        }
//        System.out.println("      . air  # stone  ~ water  v gravel  d dirt  s sand  "
//                + "g grass  C cane");
    }

    public static boolean isValid(long worldSeed, int chunkX, int chunkZ) {
        CanyonCarver canyon = new CanyonCarver(chunkX, chunkZ, false, SEA);
        CanyonCarver underwaterCanyon = new CanyonCarver(chunkX, chunkZ, true, SEA);

        JavaRandom random = new JavaRandom();

        double min = 99999999999999.0f;
        for (int sx = chunkX - CarverConfig.CARVE_RADIUS; sx <= chunkX + CarverConfig.CARVE_RADIUS; sx++) {
            for (int sz = chunkZ - CarverConfig.CARVE_RADIUS; sz <= chunkZ + CarverConfig.CARVE_RADIUS; sz++) {
                if (CarverConfig.isStartChunk(random, worldSeed, 1, sx, sz, CarverConfig.CANYON)) {
                    double dist = canyon.checkDist(random, sx, sz, chunkX << 4, chunkZ << 4);
                    min = Math.min(min, dist);
                }
            }
        }
        if (min > 25.0f) {
            return false;
        }

        min = 99999999999999.0f;
        for (int sx = chunkX - CarverConfig.CARVE_RADIUS; sx <= chunkX + CarverConfig.CARVE_RADIUS; sx++) {
            for (int sz = chunkZ - CarverConfig.CARVE_RADIUS; sz <= chunkZ + CarverConfig.CARVE_RADIUS; sz++) {
                if (CarverConfig.isStartChunk(random, worldSeed, 0, sx, sz, CarverConfig.UNDERWATER_CANYON)) {
                    double dist = underwaterCanyon.checkDist(random, sx, sz, chunkX << 4, chunkZ << 4);
                    min = Math.min(min, dist);
                }
            }
        }
        return !(min > 25.0f);
    }

    private static String formatDuration(long seconds) {
        if (seconds < 60) {
            return seconds + "s";
        }

        long minutes = seconds / 60;
        seconds %= 60;

        if (minutes < 60) {
            return String.format("%dm %02ds", minutes, seconds);
        }

        long hours = minutes / 60;
        minutes %= 60;

        return String.format("%dh %02dm", hours, minutes);
    }

    record TestCase(long seed, Searcher.Point expected) {}

    static TestCase[] tests = {
            new TestCase(139085984632088L, new Searcher.Point(3, 18, 13)),
            new TestCase(139085984632088L, new Searcher.Point(3, 18, 13)),
            new TestCase(48084157353526L, new Searcher.Point(1, 17, 4)),
            new TestCase(47870588363913L, new Searcher.Point(1, 17, 4)),
            new TestCase(191099825957652L, new Searcher.Point(1, 17, 4)),
            new TestCase(33029914101426L, new Searcher.Point(7, 25, 14)),
            new TestCase(12688162769877L, new Searcher.Point(7, 25, 14)),
            new TestCase(52430632941232L, new Searcher.Point(7, 25, 14)),
            new TestCase(48084157353526L, new Searcher.Point(1, 17, 4)),
            new TestCase(47870588363913L, new Searcher.Point(1, 17, 4)),
            new TestCase(191099825957652L, new Searcher.Point(1, 17, 4)),
            new TestCase(102436839828115L, new Searcher.Point(2, 34, 3)),
            new TestCase(154772930895857L, new Searcher.Point(3, 24, 14)),
            new TestCase(154772930895857L, new Searcher.Point(3, 24, 14)),
            new TestCase(71794945855396L, new Searcher.Point(13, 24, 13)),
            new TestCase(36549428759679L, new Searcher.Point(13, 24, 13)),
            new TestCase(92900304525722L, new Searcher.Point(13, 24, 13)),
            new TestCase(224900775823927L, new Searcher.Point(4, 20, 2)),
            new TestCase(246621747357458L, new Searcher.Point(4, 20, 2)),
            new TestCase(244464738914485L, new Searcher.Point(4, 20, 2)),
            new TestCase(224099667894800L, new Searcher.Point(4, 20, 2)),
            new TestCase(136000215216571L, new Searcher.Point(4, 20, 2)),
            new TestCase(71794945855396L, new Searcher.Point(13, 24, 13)),
            new TestCase(36549428759679L, new Searcher.Point(13, 24, 13)),
            new TestCase(92900304525722L, new Searcher.Point(13, 24, 13)),
            new TestCase(84266500021597L, new Searcher.Point(4, 17, 3)),
            new TestCase(180466033996664L, new Searcher.Point(4, 17, 3)),
            new TestCase(87305171594819L, new Searcher.Point(4, 17, 3)),
            new TestCase(27291829008302L, new Searcher.Point(4, 17, 3)),
            new TestCase(257335651092833L, new Searcher.Point(4, 17, 3)),
            new TestCase(109233106211261L, new Searcher.Point(4, 17, 3)),
            new TestCase(132009577757784L, new Searcher.Point(7, 27, 13)),
            new TestCase(256319899267491L, new Searcher.Point(7, 27, 13)),
            new TestCase(84519979013006L, new Searcher.Point(7, 27, 13)),
            new TestCase(59916038374310L, new Searcher.Point(7, 27, 13)),
            new TestCase(83253967530315L, new Searcher.Point(3, 33, 10)),
            new TestCase(27562867256502L, new Searcher.Point(12, 33, 5)),
            new TestCase(143116035223817L, new Searcher.Point(12, 33, 5)),
            new TestCase(174237072833879L, new Searcher.Point(12, 33, 5)),
            new TestCase(185439715941298L, new Searcher.Point(3, 29, 6)),
            new TestCase(183468884224668L, new Searcher.Point(3, 29, 6)),
            new TestCase(227674892660983L, new Searcher.Point(13, 21, 6)),
            new TestCase(82314596958041L, new Searcher.Point(13, 21, 6)),
            new TestCase(71222455162245L, new Searcher.Point(3, 22, 6)),
            new TestCase(65508995851424L, new Searcher.Point(6, 17, 3)),
            new TestCase(198401809419976L, new Searcher.Point(6, 17, 3)),
            new TestCase(109126945428627L, new Searcher.Point(12, 9, 5)),
            new TestCase(38501139390519L, new Searcher.Point(12, 9, 5)),
            new TestCase(24779057486785L, new Searcher.Point(4, 22, 3)),
            new TestCase(13867601609268L, new Searcher.Point(8, 10, 3)),
            new TestCase(139354953263823L, new Searcher.Point(1, 28, 9)),
            new TestCase(250573657863274L, new Searcher.Point(1, 28, 9)),
            new TestCase(138704512176091L, new Searcher.Point(1, 28, 9)),
            new TestCase(264398348638470L, new Searcher.Point(12, 29, 5)),
            new TestCase(154772930895857L, new Searcher.Point(12, 29, 5)),
            new TestCase(60979909433358L, new Searcher.Point(3, 24, 14)),
            new TestCase(247652606761537L, new Searcher.Point(11, 18, 3)),
            new TestCase(171830845845036L, new Searcher.Point(11, 18, 3)),
            new TestCase(6802643023237L, new Searcher.Point(11, 18, 3)),
            new TestCase(226253129974944L, new Searcher.Point(9, 31, 3)),
            new TestCase(103063413032523L, new Searcher.Point(9, 31, 3)),
            new TestCase(256404005526461L, new Searcher.Point(9, 31, 3)),
            new TestCase(224182730391128L, new Searcher.Point(3, 30, 8)),
            new TestCase(57199771089419L, new Searcher.Point(3, 30, 8)),
            new TestCase(154772930895857L, new Searcher.Point(2, 22, 12)),
            new TestCase(183468884224668L, new Searcher.Point(3, 24, 14)),
            new TestCase(227674892660983L, new Searcher.Point(13, 21, 6)),
            new TestCase(223192501093162L, new Searcher.Point(13, 21, 6)),
            new TestCase(29775300298829L, new Searcher.Point(12, 14, 13)),
            new TestCase(38501139390519L, new Searcher.Point(12, 14, 13)),
            new TestCase(84266500021597L, new Searcher.Point(4, 22, 3)),
            new TestCase(180466033996664L, new Searcher.Point(4, 17, 3)),
            new TestCase(87305171594819L, new Searcher.Point(4, 17, 3)),
            new TestCase(27291829008302L, new Searcher.Point(4, 17, 3)),
            new TestCase(257335651092833L, new Searcher.Point(4, 17, 3)),
            new TestCase(224900775823927L, new Searcher.Point(4, 17, 3)),
            new TestCase(246621747357458L, new Searcher.Point(4, 20, 2)),
            new TestCase(244464738914485L, new Searcher.Point(4, 20, 2)),
            new TestCase(224099667894800L, new Searcher.Point(4, 20, 2)),
            new TestCase(136000215216571L, new Searcher.Point(4, 20, 2)),
            new TestCase(57199771089419L, new Searcher.Point(4, 20, 2)),
            new TestCase(84266500021597L, new Searcher.Point(2, 22, 12)),
            new TestCase(180466033996664L, new Searcher.Point(4, 17, 3)),
            new TestCase(87305171594819L, new Searcher.Point(4, 17, 3)),
            new TestCase(27291829008302L, new Searcher.Point(4, 17, 3)),
            new TestCase(257335651092833L, new Searcher.Point(4, 17, 3)),
            new TestCase(60979909433358L, new Searcher.Point(4, 17, 3)),
            new TestCase(247652606761537L, new Searcher.Point(11, 18, 3)),
            new TestCase(171830845845036L, new Searcher.Point(11, 18, 3)),
            new TestCase(71222455162245L, new Searcher.Point(11, 18, 3)),
            new TestCase(65508995851424L, new Searcher.Point(6, 17, 3)),
            new TestCase(139085984632088L, new Searcher.Point(3, 18, 13)),
            new TestCase(261866202537415L, new Searcher.Point(3, 18, 13)),
            new TestCase(27418593171810L, new Searcher.Point(13, 6, 4)),
            new TestCase(208488772694469L, new Searcher.Point(13, 6, 4)),
            new TestCase(87583257322508L, new Searcher.Point(13, 6, 4)),
            new TestCase(28242491721831L, new Searcher.Point(8, 34, 12)),
            new TestCase(231260033888898L, new Searcher.Point(8, 34, 12)),
            new TestCase(276809256282522L, new Searcher.Point(8, 34, 12)),
            new TestCase(29771798785213L, new Searcher.Point(12, 32, 5)),
            new TestCase(38931394541442L, new Searcher.Point(12, 32, 5)),
            new TestCase(223192501093162L, new Searcher.Point(2, 35, 11)),
            new TestCase(29775300298829L, new Searcher.Point(12, 14, 13)),
            new TestCase(71222455162245L, new Searcher.Point(12, 14, 13)),
            new TestCase(65508995851424L, new Searcher.Point(6, 17, 3)),
            new TestCase(174237072833879L, new Searcher.Point(6, 17, 3)),
            new TestCase(185439715941298L, new Searcher.Point(3, 29, 6)),
            new TestCase(109233106211261L, new Searcher.Point(3, 29, 6)),
            new TestCase(132009577757784L, new Searcher.Point(7, 27, 13)),
            new TestCase(256319899267491L, new Searcher.Point(7, 27, 13)),
            new TestCase(84519979013006L, new Searcher.Point(7, 27, 13)),
            new TestCase(183468884224668L, new Searcher.Point(7, 27, 13)),
            new TestCase(227674892660983L, new Searcher.Point(13, 21, 6)),
            new TestCase(13867601609268L, new Searcher.Point(13, 21, 6)),
            new TestCase(139354953263823L, new Searcher.Point(1, 28, 9)),
            new TestCase(250573657863274L, new Searcher.Point(1, 28, 9)),
            new TestCase(82314596958041L, new Searcher.Point(1, 28, 9)),
            new TestCase(109233106211261L, new Searcher.Point(3, 22, 6)),
            new TestCase(132009577757784L, new Searcher.Point(7, 27, 13)),
            new TestCase(256319899267491L, new Searcher.Point(7, 27, 13)),
            new TestCase(84519979013006L, new Searcher.Point(7, 27, 13))
    };

    public static void main(String[] args) throws IOException, InterruptedException {
        // 88249931
//        try (BufferedReader br = new BufferedReader(new FileReader("/home/scriptline/programming/seedfinding/tallsugarcane/seeds.txt"))) {
//            while (true) {
//                String line = br.readLine();
//                Long seed = Long.parseLong(line);
//                search2(seed, -6334957,-23472658);
////                System.out.println(line);
//                if (line == null) {
//                    break;
//                }
//            }
//        }
//        System.exit(1);
        search2(4731426242008324673L, -6334957,-23472658);
//        System.exit(1);

        int threads = 28;
//        String inputFile = "/home/scriptline/gaming/newnew/seeds_out.txt";
        String inputFile = "/home/scriptline/programming/seedfinding/tallsugarcane/19.txt";

        System.out.printf("Running on %d worker threads%n", threads);

        long startTime = System.nanoTime();

        /*
         * Count the input first so that we can display percentage/ETA.
         */
        int total = 0;

        try (BufferedReader br = new BufferedReader(new FileReader(inputFile))) {
            while (br.readLine() != null) {
                total++;
            }
        }
//        total -= 88249931;

        System.out.printf("Total seeds: %,d%n", total);

        /*
         * A bounded queue prevents us from putting millions of tasks
         * into the ExecutorService at once.
         */
        BlockingQueue<String> queue = new ArrayBlockingQueue<>(10_000);

        AtomicInteger completed = new AtomicInteger(0);
        AtomicBoolean producerFinished = new AtomicBoolean(false);

        /*
         * Producer thread.
         *
         * Reads the file and continuously feeds the workers.
         */
        Thread producer = new Thread(() -> {
            int count = 0;
            try (BufferedReader br = new BufferedReader(new FileReader(inputFile))) {
                String line;

                while ((line = br.readLine()) != null) {
//                    count++;
//                    if (count >= 88249931) {
                    queue.put(line);
//                    }
                }

                producerFinished.set(true);

            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        producer.start();

        /*
         * Worker threads.
         */
        Thread[] workers = new Thread[threads];

        for (int i = 0; i < threads; i++) {
            workers[i] = new Thread(() -> {
                try {
                    while (true) {
                        String line = queue.poll(100, TimeUnit.MILLISECONDS);

                        if (line == null) {
                            if (producerFinished.get() && queue.isEmpty()) {
                                break;
                            }

                            continue;
                        }

                        try {
                            String[] parts = line.trim().split("\\s+");

                            long seed = Long.parseLong(parts[0]);
                            int x = Integer.parseInt(parts[1]);
                            int z = Integer.parseInt(parts[2]);

                            if (isValid(seed, x >> 4, z >> 4)) {
                                search2(seed, (x >> 4 << 4), (z >> 4 << 4));
//                                search2(seed, x, z);
                            }

                        } catch (Exception e) {
                            e.printStackTrace();

                        } finally {
                            completed.incrementAndGet();
                        }
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            workers[i].start();
        }

        /*
         * Progress display.
         *
         * Keep this in the main thread so workers never fight over stdout.
         */
        while (completed.get() < total) {
            int done = completed.get();

            long elapsedNanos = System.nanoTime() - startTime;
            double elapsedSeconds = elapsedNanos / 1_000_000_000.0;

            double rate = elapsedSeconds > 0
                    ? done / elapsedSeconds
                    : 0.0;

            double percent = total > 0
                    ? 100.0 * done / total
                    : 100.0;

            long remaining = total - done;

            double etaSeconds = rate > 0
                    ? remaining / rate
                    : 0.0;

            int barWidth = 40;
            int filled = total > 0
                    ? (int) (barWidth * done / (double) total)
                    : 0;

            StringBuilder bar = new StringBuilder(barWidth);

            for (int i = 0; i < barWidth; i++) {
                bar.append(i < filled ? '=' : ' ');
            }

            System.out.printf(
                    "\r[%s] %6.2f%% | %,d/%,d | %,d seeds/s | ETA %s",
                    bar,
                    percent,
                    done,
                    total,
                    (long) rate,
                    formatDuration((long) etaSeconds)
            );

            System.out.flush();

            Thread.sleep(250);
        }

        /*
         * Wait for producer and workers.
         */
        producer.join();

        for (Thread worker : workers) {
            worker.join();
        }

        long endTime = System.nanoTime();

        double durationSeconds =
                (endTime - startTime) / 1_000_000_000.0;

        double finalRate =
                total / durationSeconds;

        /*
         * Finish the progress bar cleanly.
         */
        System.out.printf(
                "\r[%s] 100.00%% | %,d/%,d | %,d seeds/s | ETA 0s%n",
                "=".repeat(40),
                total,
                total,
                (long) finalRate
        );

        System.out.printf(
                "Execution time: %.2f seconds%n",
                durationSeconds
        );

        System.out.printf(
                "Average rate: %,d seeds/sec%n",
                (long) finalRate
        );
    }

//    public static void main(String[] args) throws IOException, InterruptedException {
//        int threads = 1;
//        ExecutorService pool = Executors.newFixedThreadPool(threads);
//        int caneX = 13918211;
//        int caneZ = -27747050;
//
////        System.out.printf("%d %d %d\n", (caneX >> 4 << 4) - 16, caneZ >> 4 << 4, 0);
//
//        search(41273705236158L, -3323517, 18778246);
////        System.out.printf("%d\n", isValid(-8186692483145482572L, -11172704 >> 4, -23767600 >> 4) ? 1 : 0);
//        System.exit(1);
//
//        System.out.printf("running on %d threads\n", threads);
//        int count = 0;
//        long startTime = System.nanoTime();
//        try (BufferedReader br = new BufferedReader(
//                new FileReader("/home/scriptline/gaming/seeds_out.txt"))) {
//
//            String line;
//            while ((line = br.readLine()) != null) {
//                count ++;
////                if (count > 1000) {
////                    break;
////                }
//                final String currentLine = line;
//
//                pool.submit(() -> {
//                    try {
//                        String[] parts = currentLine.trim().split("\\s+");
//
//                        try {
//                            long seed = Long.parseLong(parts[0]);
//                            int x = Integer.parseInt(parts[1]);
//                            int z = Integer.parseInt(parts[2]);
//
//                            if (isValid(seed, x >> 4, z >> 4)) {
////                            System.out.printf("got valid: %d\n", seed);
////                            for (long upper16 = 0; upper16 < (1L << 16); upper16++) {
////                                long newWorldSeed = seed | (upper16 << 48);
//                                search(seed, x, z);
////                            }
//                            }
//                        }
//                        catch (Exception e) {
//                            e.printStackTrace();
//                        }
//
//
//                    } catch (Exception e) {
//                        e.printStackTrace();
//                    }
//                });
//            }
//        }
//
//        pool.shutdown();
//        pool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
//
//        long endTime = System.nanoTime();
//        long durationInNanoseconds = endTime - startTime;
//        long durationInMilliseconds = durationInNanoseconds / 1_000_000;
//
//        System.out.println("Execution time: " + durationInMilliseconds + " ms");
//
//    }

    /** The region origin the searcher would have used for this chunk. */
    private static int alignRegion(int chunk, int radius) {
        int region = RegionSearcher.regionFor(radius);
        int step = region - 2;
        int first = -radius - 1;
        return first + Math.floorDiv(chunk - first, step) * step;
    }

    private static String describeNeighbours(ArrayWorld world, int x, int y, int z) {
        StringBuilder sb = new StringBuilder();
        int[][] dirs = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        String[] labels = {"-x", "+x", "-z", "+z"};
        for (int i = 0; i < 4; i++) {
            byte b = world.getBlock(x + dirs[i][0], y, z + dirs[i][1]);
            sb.append(labels[i]).append('=').append(name(b)).append(' ');
        }
        return sb.toString();
    }

    private static String name(byte b) {
        return switch (b) {
            case Blocks.AIR -> "air";
            case Blocks.SOLID -> "stone";
            case Blocks.WATER -> "water";
            case Blocks.FLOWING_WATER -> "flowing_water";
            case Blocks.SAND -> "sand";
            case Blocks.RED_SAND -> "red_sand";
            case Blocks.DIRT -> "dirt";
            case Blocks.COARSE_DIRT -> "coarse_dirt";
            case Blocks.PODZOL -> "podzol";
            case Blocks.GRASS_BLOCK -> "grass_block";
            case Blocks.SUGAR_CANE -> "sugar_cane";
            case Blocks.GRAVEL -> "gravel";
            case Blocks.ICE -> "ice";
            case Blocks.PLANT -> "plant";
            default -> "?" + b;
        };
    }
}
