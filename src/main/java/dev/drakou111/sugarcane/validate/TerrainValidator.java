package dev.drakou111.sugarcane.validate;

import kaptainwutax.biomeutils.source.OverworldBiomeSource;
import kaptainwutax.mcutils.version.MCVersion;
import kaptainwutax.terrainutils.TerrainGenerator;
import kaptainwutax.terrainutils.terrain.OverworldTerrainGenerator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Checks the reused terrain generator against real generated chunks.
 *
 * <p>The comparison uses the chunk's stored WORLD_SURFACE and OCEAN_FLOOR
 * heightmaps. Both are computed at the 'noise' chunk status — before carvers and
 * before features — so they isolate the noise terrain exactly, with no later
 * stage contaminating the result.
 *
 * <p>Reports agreement separately for ocean chunks, since those are the only
 * ones the search will ever run on.
 */
public final class TerrainValidator {

    public static void main(String[] args) throws IOException {
        Path path = Path.of(args[0]);
        ByteBuffer bb = ByteBuffer.wrap(Files.readAllBytes(path)).order(ByteOrder.LITTLE_ENDIAN);

        byte[] magic = new byte[4];
        bb.get(magic);
        if (!new String(magic).equals("HMAP")) {
            throw new IOException("bad magic");
        }
        long seed = bb.getLong();
        int n = bb.getInt();
        System.out.println("seed " + seed + ", " + n + " chunks");

        OverworldBiomeSource biomes = new OverworldBiomeSource(MCVersion.v1_16_1, seed);
        TerrainGenerator terrain = new OverworldTerrainGenerator(biomes);

        long cells = 0, okGround = 0, okSurface = 0;
        long oceanCells = 0, oceanOkGround = 0, oceanCarved = 0, oceanHigher = 0;
        long plainCells = 0, plainOk = 0, plainHigher = 0;
        long worstDelta = 0;

        for (int i = 0; i < n; i++) {
            int cx = bb.getInt(), cz = bb.getInt();
            short[] oceanFloor = new short[256];
            short[] worldSurface = new short[256];
            for (int k = 0; k < 256; k++) {
                oceanFloor[k] = bb.getShort();
            }
            for (int k = 0; k < 256; k++) {
                worldSurface[k] = bb.getShort();
            }
            int centreBiome = biomes.getBiomeForNoiseGen(cx * 4 + 2, 0, cz * 4 + 2).getId();
            boolean isOcean = BiomeSourceValidator.isOcean(centreBiome);
            // Warm oceans grow coral and frozen oceans grow icebergs; both add
            // motion-blocking blocks above the sea floor and so raise OCEAN_FLOOR.
            boolean plainOcean = centreBiome == 0 || centreBiome == 24
                    || centreBiome == 45 || centreBiome == 46
                    || centreBiome == 48 || centreBiome == 49;

            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    int x = cx * 16 + lx, z = cz * 16 + lz;
                    int expectedFloor = oceanFloor[lz * 16 + lx];
                    int expectedSurface = worldSurface[lz * 16 + lx];

                    int gotGround = terrain.getHeightOnGround(x, z);
                    int gotInGround = terrain.getHeightInGround(x, z);

                    cells++;
                    if (gotGround == expectedFloor) {
                        okGround++;
                    } else {
                        worstDelta = Math.max(worstDelta, Math.abs(gotGround - expectedFloor));
                    }
                    if (gotInGround == expectedSurface) {
                        okSurface++;
                    }
                    if (isOcean) {
                        oceanCells++;
                        if (gotGround == expectedFloor) {
                            oceanOkGround++;
                        } else if (expectedFloor < gotGround) {
                            // Real terrain is LOWER than generated: blocks were
                            // removed after the noise stage, i.e. a carver. This
                            // is the expected kind of disagreement.
                            oceanCarved++;
                        } else {
                            oceanHigher++;
                        }
                    }
                    if (plainOcean) {
                        plainCells++;
                        if (gotGround == expectedFloor) {
                            plainOk++;
                        } else if (expectedFloor > gotGround) {
                            plainHigher++;
                        }
                    }
                }
            }
        }

        System.out.printf("%ngetHeightOnGround vs OCEAN_FLOOR   : %d / %d  (%.4f%%)%n",
                okGround, cells, 100.0 * okGround / cells);
        System.out.printf("getHeightInGround vs WORLD_SURFACE : %d / %d  (%.4f%%)%n",
                okSurface, cells, 100.0 * okSurface / cells);
        System.out.printf("largest disagreement (blocks)         : %d%n", worstDelta);
        System.out.printf("%nOCEAN chunks only (what the search runs on):%n");
        System.out.printf("   exact match      : %d / %d  (%.4f%%)%n",
                oceanOkGround, oceanCells, 100.0 * oceanOkGround / Math.max(1, oceanCells));
        System.out.printf("   real LOWER (carved, expected) : %d  (%.4f%%)%n",
                oceanCarved, 100.0 * oceanCarved / Math.max(1, oceanCells));
        System.out.printf("   real HIGHER (would be a bug)  : %d  (%.4f%%)%n",
                oceanHigher, 100.0 * oceanHigher / Math.max(1, oceanCells));
        System.out.printf("%nPLAIN oceans only (no coral, no icebergs):%n");
        System.out.printf("   exact match : %d / %d  (%.4f%%)%n",
                plainOk, plainCells, 100.0 * plainOk / Math.max(1, plainCells));
        System.out.printf("   real HIGHER : %d  (%.4f%%)%n",
                plainHigher, 100.0 * plainHigher / Math.max(1, plainCells));
    }
}
