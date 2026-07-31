package dev.drakou111.sugarcane.validate;

import dev.drakou111.sugarcane.gen.SugarCaneFeature;
import dev.drakou111.sugarcane.rng.JavaRandom;
import dev.drakou111.sugarcane.world.ArrayWorld;
import dev.drakou111.sugarcane.world.Blocks;

import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Checks the feature simulation against chunks a real 1.16.1 server generated.
 *
 * <p>Everything else in this project is inference from decompiled source. This
 * is the only thing that closes the loop: for each exported chunk it replays
 * patch_sugar_cane over the real terrain and asks whether the cane it places is
 * exactly the cane the game placed.
 *
 * <p>The biome's invocation count and the feature's index within
 * VEGETAL_DECORATION are not hard-coded — they are recovered by trying every
 * plausible pair and seeing which reproduces the observed cane. A pair that
 * reproduces multi-column chunks across many samples is not a coincidence, so
 * this validates the RNG order and the placement rules at the same time as it
 * determines the indices.
 *
 * <p>Only the chunk interior (local x,z in [4,11]) is compared: cane nearer the
 * border can have been placed by a neighbouring chunk's invocation, whose ±4
 * offset reaches 4 blocks in.
 */
public final class RealWorldValidator {

    private static final int MARGIN = 4;
    private static final int WIN = 16 + 2 * MARGIN;
    private static final int[] COUNTS = {
            SugarCaneFeature.COUNT_DEFAULT,
            SugarCaneFeature.COUNT_BADLANDS,
            SugarCaneFeature.COUNT_SWAMP,
            SugarCaneFeature.COUNT_DESERT};
    private static final int MAX_INDEX = 40;

    // The exporter writes its own category codes; map them onto Blocks.
    // (scan_world.py: OTHER=0, AIR=1, FLOWING=2, SOIL=3, CANE=4, SOURCE=5)
    private static final byte[] FROM_EXPORT = {
            Blocks.SOLID,          // 0 OTHER — anything opaque
            Blocks.AIR,            // 1 AIR
            Blocks.FLOWING_WATER,  // 2 flowing water
            Blocks.DIRT,           // 3 any of the six cane soils
            Blocks.SUGAR_CANE,     // 4
            Blocks.WATER,          // 5 source water
            Blocks.PLANT,          // 6 grass/flowers — block cane, but not motion-blocking
    };

    private record Column(int x, int y, int z, int height) {
    }

    private record Chunk(int cx, int cz, int biome, List<Column> cane, byte[] win) {
    }

    public static void main(String[] args) throws IOException {
        Path path = Path.of(args.length > 0 ? args[0]
                : "../../scratchpad/srv/chunks.bin");
        byte[] all = Files.readAllBytes(path);
        ByteBuffer bb = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);

        byte[] magic = new byte[4];
        bb.get(magic);
        if (!new String(magic).equals("SCEX")) {
            throw new IOException("bad magic");
        }
        long seed = bb.getLong();
        int n = bb.getInt();
        System.out.println("seed " + seed + ", " + n + " chunks with cane");

        List<Chunk> chunks = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int cx = bb.getInt(), cz = bb.getInt(), biome = bb.getInt(), nc = bb.getInt();
            List<Column> cane = new ArrayList<>(nc);
            for (int c = 0; c < nc; c++) {
                cane.add(new Column(bb.getInt(), bb.getInt(), bb.getInt(), bb.getInt()));
            }
            byte[] win = new byte[WIN * WIN * 256];
            bb.get(win);
            chunks.add(new Chunk(cx, cz, biome, cane, win));
        }

        // biome -> (count,index) -> how many chunks that pair reproduced exactly
        Map<Integer, Map<String, Integer>> votes = new TreeMap<>();
        Map<Integer, Integer> perBiome = new TreeMap<>();
        int matched = 0, unmatched = 0, cleanMatched = 0, cleanUnmatched = 0;
        Map<Integer, Integer> matchedByBlocks = new TreeMap<>();

        int skippedNoInterior = 0;
        for (Chunk chunk : chunks) {
            Set<Long> observed = interior(chunk);
            if (observed.isEmpty()) {
                // All this chunk's cane sits near a border, where a neighbouring
                // chunk's invocation could have placed it. Nothing to check.
                skippedNoInterior++;
                continue;
            }
            perBiome.merge(chunk.biome, 1, Integer::sum);
            String best = null;
            for (int count : COUNTS) {
                for (int index = 0; index <= MAX_INDEX; index++) {
                    if (reproduces(chunk, seed, count, index, observed)) {
                        best = count + "/" + index;
                        votes.computeIfAbsent(chunk.biome, k -> new HashMap<>())
                                .merge(best, 1, Integer::sum);
                    }
                }
            }
            // A chunk containing flowing water has had a spring run AFTER the
            // cane feature, moving blocks the simulation has to guess at. Those
            // are expected to disagree; clean chunks are the real test.
            boolean clean = true;
            for (byte b : chunk.win) {
                if (b == 2) { // exporter's flowing-water code
                    clean = false;
                    break;
                }
            }
            if (best != null) {
                matched++;
                matchedByBlocks.merge(Math.min(observed.size(), 12), 1, Integer::sum);
                if (clean) {
                    cleanMatched++;
                }
            } else {
                unmatched++;
                if (clean) {
                    cleanUnmatched++;
                }
            }
        }

        System.out.println();
        System.out.printf("chunks reproduced exactly : %d / %d  (%.1f%%)%n",
                matched, matched + unmatched, 100.0 * matched / Math.max(1, matched + unmatched));
        System.out.println("unreproduced              : " + unmatched);
        System.out.println("skipped (no interior cane): " + skippedNoInterior);
        System.out.printf("no-flowing-water chunks   : %d / %d  (%.1f%%)%n", cleanMatched, cleanMatched + cleanUnmatched, 100.0 * cleanMatched / Math.max(1, cleanMatched + cleanUnmatched));
        System.out.println();
        System.out.println("exactly-reproduced chunks by number of interior cane blocks:");
        System.out.println("  (a wrong RNG model cannot reproduce even one multi-block chunk)");
        for (var e : matchedByBlocks.entrySet()) {
            System.out.printf("   %2d blocks : %d chunks%n", e.getKey(), e.getValue());
        }
        System.out.println();
        System.out.println("biome -> best (count/index) pairs by agreement:");
        for (var e : votes.entrySet()) {
            String top = e.getValue().entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .limit(3)
                    .map(x -> x.getKey() + "x" + x.getValue())
                    .reduce((a, b) -> a + "  " + b).orElse("");
            System.out.printf("  biome %-4d (n=%-4d)  %s%n",
                    e.getKey(), perBiome.get(e.getKey()), top);
        }
    }

    /** Observed cane blocks in the chunk interior, packed as a position key. */
    private static Set<Long> interior(Chunk chunk) {
        Set<Long> set = new HashSet<>();
        for (Column c : chunk.cane) {
            int lx = c.x - chunk.cx * 16;
            int lz = c.z - chunk.cz * 16;
            if (lx < MARGIN || lx > 15 - MARGIN || lz < MARGIN || lz > 15 - MARGIN) {
                continue;
            }
            for (int k = 0; k < c.height; k++) {
                set.add(key(c.x, c.y + k, c.z));
            }
        }
        return set;
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (y & 0xFFF);
    }

    private static boolean reproduces(Chunk chunk, long seed, int count, int index,
                                      Set<Long> observed) {
        ArrayWorld world = new ArrayWorld(chunk.cx * 16 - MARGIN,
                chunk.cz * 16 - MARGIN, WIN, WIN);
        for (int wx = 0; wx < WIN; wx++) {
            for (int wz = 0; wz < WIN; wz++) {
                int base = wx * WIN * 256 + wz * 256;
                for (int y = 0; y < 256; y++) {
                    byte b = FROM_EXPORT[chunk.win[base + y]];
                    // Strip the cane the game placed; the simulation re-places it.
                    world.setBlock(chunk.cx * 16 - MARGIN + wx, y,
                            chunk.cz * 16 - MARGIN + wz,
                            b == Blocks.SUGAR_CANE ? Blocks.AIR : b);
                }
            }
        }

        JavaRandom random = new JavaRandom();
        long decorationSeed = random.setDecorationSeed(seed, chunk.cx * 16, chunk.cz * 16);
        SugarCaneFeature.place(world, decorationSeed, index, count, chunk.cx, chunk.cz);

        Set<Long> produced = new HashSet<>();
        for (int lx = MARGIN; lx <= 15 - MARGIN; lx++) {
            for (int lz = MARGIN; lz <= 15 - MARGIN; lz++) {
                int x = chunk.cx * 16 + lx, z = chunk.cz * 16 + lz;
                for (int y = 0; y < 256; y++) {
                    if (world.getBlock(x, y, z) == Blocks.SUGAR_CANE) {
                        produced.add(key(x, y, z));
                    }
                }
            }
        }
        return !produced.isEmpty() && produced.equals(observed);
    }
}
