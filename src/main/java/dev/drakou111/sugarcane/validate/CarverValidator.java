package dev.drakou111.sugarcane.validate;

import dev.drakou111.sugarcane.gen.CarverConfig;
import dev.drakou111.sugarcane.gen.CanyonCarver;
import dev.drakou111.sugarcane.gen.Carver;
import dev.drakou111.sugarcane.gen.CaveCarver;
import dev.drakou111.sugarcane.rng.JavaRandom;
import kaptainwutax.biomeutils.source.OverworldBiomeSource;
import kaptainwutax.mcutils.block.Block;
import kaptainwutax.mcutils.version.MCVersion;
import kaptainwutax.terrainutils.TerrainGenerator;
import kaptainwutax.terrainutils.terrain.OverworldTerrainGenerator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Checks the cave carver against real generated chunks.
 *
 * <p>Measures <b>precision</b>, not recall: the canyon carver is not
 * implemented, so the carved set is necessarily a subset of the real sub-sea
 * air. But nearly every block this carver cuts should genuinely be air in the
 * real chunk. A low precision means the tunnel walk is wrong.
 *
 * <p>Only ocean chunks are scored. Below sea level, noise fills everything with
 * water and the underwater carver places water rather than air, so sub-sea air
 * there comes almost entirely from the AIR-step carvers.
 */
public final class CarverValidator {

    private static final int SEA = 63;

    public static void main(String[] args) throws IOException {
        Path path = Path.of(args[0]);
        ByteBuffer bb = ByteBuffer.wrap(Files.readAllBytes(path)).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[4];
        bb.get(magic);
        if (!new String(magic).equals("AIRS")) {
            throw new IOException("bad magic");
        }
        long seed = bb.getLong();
        int n = bb.getInt();
        System.out.println("seed " + seed + ", " + n + " chunks");

        OverworldBiomeSource biomes = new OverworldBiomeSource(MCVersion.v1_16_1, seed);
        TerrainGenerator terrain = new OverworldTerrainGenerator(biomes);

        long carvedTotal = 0, carvedHit = 0, oceanChunks = 0;
        long caveTotal = 0, caveHit = 0, canyonTotal = 0, canyonHit = 0;
        // Which carver claimed each block, so the two can be scored apart. The
        // canyon carver has never been checked against a real world, and if what it
        // cuts is not air in reality then the geometry it adds is fiction.
        boolean[] fromCanyon = new boolean[SEA * 256];

        for (int i = 0; i < n; i++) {
            int cx = bb.getInt(), cz = bb.getInt();
            byte[] realAir = new byte[SEA * 256];
            bb.get(realAir);

            if (!BiomeSourceValidator.isOcean(
                    biomes.getBiomeForNoiseGen(cx * 4 + 2, 0, cz * 4 + 2).getId())) {
                continue;
            }
            oceanChunks++;

            // Base terrain for this chunk, straight from the noise generator.
            final boolean[][] water = new boolean[256][];
            final boolean[][] solid = new boolean[256][];
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    Block[] col = terrain.getColumnAt(cx * 16 + lx, cz * 16 + lz);
                    boolean[] w = new boolean[256];
                    boolean[] s = new boolean[256];
                    for (int y = 0; y < 256 && y < col.length; y++) {
                        String name = col[y] == null ? "air" : col[y].getName();
                        w[y] = name.contains("water");
                        s[y] = !w[y] && !name.contains("air");
                    }
                    water[lx * 16 + lz] = w;
                    solid[lx * 16 + lz] = s;
                }
            }

            boolean[] carved = new boolean[SEA * 256];
            java.util.Arrays.fill(fromCanyon, false);
            final boolean[] canyonRunning = {false};
            Carver.Target target = new Carver.Target() {
                private int idx(int x, int z) {
                    return ((x - cx * 16) & 15) * 16 + ((z - cz * 16) & 15);
                }

                @Override
                public boolean canReplace(int x, int y, int z) {
                    if (y < 0 || y > 255) {
                        return false;
                    }
                    // The land carver's replaceableBlocks are stone-likes and
                    // dirt-likes; notably NOT water and NOT air.
                    return solid[idx(x, z)][y];
                }

                @Override
                public boolean isWater(int x, int y, int z) {
                    return y >= 0 && y <= 255 && water[idx(x, z)][y];
                }

                @Override
                public boolean isAir(int x, int y, int z) {
                    return y >= 0 && y < SEA && carved[(y * 16 + ((z - cz * 16) & 15)) * 16
                            + ((x - cx * 16) & 15)];
                }

                @Override
                public void setCaveAir(int x, int y, int z) {
                    if (y < SEA) {
                        int k = (y * 16 + ((z - cz * 16) & 15)) * 16 + ((x - cx * 16) & 15);
                        carved[k] = true;
                        if (canyonRunning[0]) {
                            fromCanyon[k] = true;
                        }
                    }
                }

                @Override
                public void setWater(int x, int y, int z, boolean scheduleTick) {
                }
            };

            // One carving mask for the AIR step, shared by both carvers, and
            // vanilla's order: start chunks outside, the biome's carver list inside.
            java.util.BitSet airMask = new java.util.BitSet(65536);
            Carver cave = new CaveCarver(target, cx, cz, false, SEA, airMask);
            Carver canyon = new CanyonCarver(target, cx, cz, false, SEA, airMask);
            JavaRandom random = new JavaRandom();
            for (int sx = cx - CarverConfig.CARVE_RADIUS; sx <= cx + CarverConfig.CARVE_RADIUS; sx++) {
                for (int sz = cz - CarverConfig.CARVE_RADIUS; sz <= cz + CarverConfig.CARVE_RADIUS; sz++) {
                    // carver index 0 = cave, at the ocean probability
                    if (CarverConfig.isStartChunk(random, seed, 0, sx, sz, CarverConfig.CAVE_OCEAN)) {
                        canyonRunning[0] = false;
                        cave.carve(random, sx, sz);
                    }
                    // index 1 = canyon
                    if (CarverConfig.isStartChunk(random, seed, 1, sx, sz, CarverConfig.CANYON)) {
                        canyonRunning[0] = true;
                        canyon.carve(random, sx, sz);
                        canyonRunning[0] = false;
                    }
                }
            }

            for (int k = 0; k < carved.length; k++) {
                if (!carved[k]) {
                    continue;
                }
                carvedTotal++;
                boolean air = realAir[k] != 0;
                if (air) {
                    carvedHit++;
                }
                if (fromCanyon[k]) {
                    canyonTotal++;
                    if (air) {
                        canyonHit++;
                    }
                } else {
                    caveTotal++;
                    if (air) {
                        caveHit++;
                    }
                }
            }
        }

        System.out.printf("%nocean chunks scored : %d%n", oceanChunks);
        System.out.printf("blocks carved       : %d%n", carvedTotal);
        System.out.printf("also air in reality : %d%n", carvedHit);
        System.out.printf("PRECISION           : %.4f%%%n",
                100.0 * carvedHit / Math.max(1, carvedTotal));
        System.out.printf("%n  cave carver only  : %d carved, %d air, %.4f%%%n",
                caveTotal, caveHit, 100.0 * caveHit / Math.max(1, caveTotal));
        System.out.printf("  canyon carver only: %d carved, %d air, %.4f%%%n",
                canyonTotal, canyonHit, 100.0 * canyonHit / Math.max(1, canyonTotal));
    }
}
