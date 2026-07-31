package dev.drakou111.sugarcane.validate;

import kaptainwutax.biomeutils.source.OverworldBiomeSource;
import kaptainwutax.mcutils.version.MCVersion;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Checks KaptainWutax's biome generator against the biomes a real 1.16.1 server
 * wrote into its chunks.
 *
 * <p>The library is the community's validated implementation of the ~20-layer
 * biome stack, reused rather than rewritten. But the ocean prefilter is only as
 * trustworthy as this agreement: if the biome source is wrong, the searcher
 * skips the very chunks that can produce a result. So it is verified the same
 * way the cane feature was — against ground truth from generated worlds.
 *
 * <p>Input is the biome dump written by {@code export_biomes.py}.
 */
public final class BiomeSourceValidator {

    public static void main(String[] args) throws IOException {
        Path path = Path.of(args[0]);
        ByteBuffer bb = ByteBuffer.wrap(Files.readAllBytes(path)).order(ByteOrder.LITTLE_ENDIAN);

        byte[] magic = new byte[4];
        bb.get(magic);
        if (!new String(magic).equals("BIOM")) {
            throw new IOException("bad magic");
        }
        long seed = bb.getLong();
        int n = bb.getInt();
        System.out.println("seed " + seed + ", " + n + " chunks");

        OverworldBiomeSource source = new OverworldBiomeSource(MCVersion.v1_16_1, seed);

        long total = 0, agree = 0;
        Map<String, Integer> mismatches = new HashMap<>();
        Map<Integer, long[]> perBiome = new TreeMap<>();

        for (int i = 0; i < n; i++) {
            int cx = bb.getInt(), cz = bb.getInt();
            for (int q = 0; q < 16; q++) {
                int expected = bb.getInt();
                int qx = q & 3, qz = q >> 2;
                // The chunk's Biomes[] stores the NOISE biome per 4x4x4 cell, in
                // quart coordinates. getBiome() would apply the Voronoi fuzzing
                // the game only does for per-block runtime queries, which shifts
                // biomes at boundaries — hence getBiomeForNoiseGen here.
                int actual = source.getBiomeForNoiseGen(cx * 4 + qx, 0, cz * 4 + qz).getId();
                total++;
                long[] pb = perBiome.computeIfAbsent(expected, k -> new long[2]);
                pb[0]++;
                if (actual == expected) {
                    agree++;
                    pb[1]++;
                } else {
                    mismatches.merge(expected + " -> " + actual, 1, Integer::sum);
                }
            }
        }

        System.out.printf("%nbiome cells agreeing : %d / %d  (%.4f%%)%n",
                agree, total, 100.0 * agree / total);
        if (!mismatches.isEmpty()) {
            System.out.println("top mismatches (expected -> got):");
            mismatches.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .limit(10)
                    .forEach(e -> System.out.printf("   %-16s %d%n", e.getKey(), e.getValue()));
        }
        System.out.println();
        System.out.println("ocean prefilter accuracy (the number that matters):");
        long oceanCells = 0, oceanAgree = 0;
        for (var e : perBiome.entrySet()) {
            if (isOcean(e.getKey())) {
                oceanCells += e.getValue()[0];
                oceanAgree += e.getValue()[1];
            }
        }
        System.out.printf("   ocean cells %d, correctly identified %d (%.4f%%)%n",
                oceanCells, oceanAgree, 100.0 * oceanAgree / Math.max(1, oceanCells));
    }

    /** The seven ocean biomes that carry underwater carvers, plus deep variants. */
    public static boolean isOcean(int id) {
        return id == 0 || id == 10 || id == 24 || id == 44 || id == 45
                || id == 46 || id == 47 || id == 48 || id == 49 || id == 50;
    }
}
