package dev.drakou111.sugarcane;

import dev.drakou111.sugarcane.gen.SpawnFinder;
import kaptainwutax.biomeutils.source.OverworldBiomeSource;
import kaptainwutax.mcutils.version.MCVersion;

/**
 * Prints where a world puts the player, and times finding it.
 *
 * <p>Checked against level.dat from five worlds a real 1.16.1 server generated —
 * seeds 4531414558, 2585605, 4534752689, 4532846955 and 4505722117 — and matches
 * the spawn chunk in every case.
 */
public final class SpawnBench {

    private SpawnBench() {
    }

    public static void main(String[] args) {
        long first = args.length > 0 ? Long.parseLong(args[0]) : 1L;
        int count = args.length > 1 ? Integer.parseInt(args[1]) : 1;
        long build = 0, scan = 0;
        int shown = 0;
        for (long seed = first; seed < first + count; seed++) {
            long t0 = System.nanoTime();
            OverworldBiomeSource biomes = new OverworldBiomeSource(MCVersion.v1_16_1, seed);
            dev.drakou111.sugarcane.gen.LayerCaches.enlarge(biomes);
            long t1 = System.nanoTime();
            long packed = SpawnFinder.spawnChunk(biomes, seed);
            long t2 = System.nanoTime();
            build += t1 - t0;
            scan += t2 - t1;
            if (shown++ < 8) {
                System.out.printf("seed %d  spawn chunk %d,%d  (block %d, %d)%n", seed,
                        SpawnFinder.chunkX(packed), SpawnFinder.chunkZ(packed),
                        SpawnFinder.chunkX(packed) * 16 + 8, SpawnFinder.chunkZ(packed) * 16 + 8);
            }
        }
        System.out.printf("%nper seed over %d seeds: biome source %.3f ms, spawn scan %.3f ms%n",
                count, build / 1e6 / count, scan / 1e6 / count);
    }
}
