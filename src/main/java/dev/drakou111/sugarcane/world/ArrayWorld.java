package dev.drakou111.sugarcane.world;

import java.util.Arrays;

/**
 * A flat block array over a fixed XZ window, used for tests and for scoring
 * hand-built or generator-supplied terrain snapshots.
 *
 * <p>Out-of-window reads return SOLID, which is deliberately inert: it is not
 * air, not water and not cane soil, so a placement that reaches outside the
 * window simply fails rather than silently succeeding on garbage.
 */
public final class ArrayWorld implements BlockView {
    public static final int HEIGHT = 256;

    private int minX, minZ;
    private final int sizeX, sizeZ;
    private final byte[] blocks;
    private final short[] heightmap;
    private final short[] oceanFloor;

    /**
     * Which chunk's decoration placed each cane block, packed x&lt;&lt;32|z.
     *
     * <p>A column built by two different chunks only exists if they decorate in the
     * order we happened to simulate, and the real order depends on how the world was
     * loaded — a pregenerated world and a forceload around the target do not agree.
     * Cane is rare enough (about one column per thousand chunks) that recording every
     * placement costs nothing measurable.
     */
    private final java.util.HashMap<Long, Integer> canePlacer = new java.util.HashMap<>();
    private int decoratingChunk = NO_CHUNK;

    private static final int NO_CHUNK = Integer.MIN_VALUE;

    public ArrayWorld(int minX, int minZ, int sizeX, int sizeZ) {
        this.minX = minX;
        this.minZ = minZ;
        this.sizeX = sizeX;
        this.sizeZ = sizeZ;
        this.blocks = new byte[sizeX * sizeZ * HEIGHT];
        this.heightmap = new short[sizeX * sizeZ];
        this.oceanFloor = new short[sizeX * sizeZ];
    }

    /**
     * Empties the world and moves its window, so one allocation can be reused for
     * every region of a long search.
     */
    public void reset(int minX, int minZ) {
        this.minX = minX;
        this.minZ = minZ;
        Arrays.fill(blocks, Blocks.AIR);
        Arrays.fill(heightmap, (short) 0);
        Arrays.fill(oceanFloor, (short) 0);
        canePlacer.clear();
        decoratingChunk = NO_CHUNK;
    }

    /**
     * Records whose decoration pass subsequent writes belong to. Call before running
     * a chunk's features; pass {@link #NO_CHUNK} equivalent by not calling it at all
     * for terrain, which places no cane.
     */
    public void setDecoratingChunk(int chunkX, int chunkZ) {
        this.decoratingChunk = (chunkX << 16) ^ (chunkZ & 0xFFFF);
    }

    /**
     * How much of the column at {@code x,baseY,z} one single chunk's decoration built.
     *
     * <p>This is the height that survives whatever order the game happens to decorate
     * neighbouring chunks in: a taller column that needed two chunks to cooperate
     * collapses back to this if they run the other way round. Returns the full height
     * when one chunk did all the work.
     */
    public int caneRunFromOneChunk(int x, int baseY, int z) {
        Integer owner = canePlacer.get(key(x, baseY, z));
        if (owner == null) {
            return 0;
        }
        int h = 0;
        while (getBlock(x, baseY + h, z) == Blocks.SUGAR_CANE
                && owner.equals(canePlacer.get(key(x, baseY + h, z)))) {
            h++;
        }
        return h;
    }

    /** One block position packed into a long, for keying a map on a coordinate. */
    public static long key(int x, int y, int z) {
        return ((long) x << 40) ^ ((long) (z & 0xFFFFFF) << 16) ^ (y & 0xFFFF);
    }

    public ArrayWorld copy() {
        ArrayWorld c = new ArrayWorld(minX, minZ, sizeX, sizeZ);
        c.restoreFrom(this);
        return c;
    }

    /** Resets this world to {@code source} without allocating. */
    public void restoreFrom(ArrayWorld source) {
        System.arraycopy(source.blocks, 0, blocks, 0, blocks.length);
        System.arraycopy(source.heightmap, 0, heightmap, 0, heightmap.length);
        canePlacer.clear();
        canePlacer.putAll(source.canePlacer);
        decoratingChunk = source.decoratingChunk;
    }

    private boolean inside(int x, int z) {
        int dx = x - minX, dz = z - minZ;
        return dx >= 0 && dx < sizeX && dz >= 0 && dz < sizeZ;
    }

    private int columnIndex(int x, int z) {
        return (x - minX) * sizeZ + (z - minZ);
    }

    @Override
    public byte getBlock(int x, int y, int z) {
        if (y < 0 || y >= HEIGHT || !inside(x, z)) {
            throw new RuntimeException("bad coords");
            //return Blocks.SOLID;
        }
        return blocks[columnIndex(x, z) * HEIGHT + y];
    }

    @Override
    public void setBlock(int x, int y, int z, byte block) {
        if (y < 0 || y >= HEIGHT || !inside(x, z)) {
            return;
        }
        int col = columnIndex(x, z);
        blocks[col * HEIGHT + y] = block;
        if (block == Blocks.SUGAR_CANE && decoratingChunk != NO_CHUNK) {
            canePlacer.put(key(x, y, z), decoratingChunk);
        }
        // Keep both worldgen heightmaps consistent. Cane is not motion-blocking,
        // so the common case costs nothing.
        if (Blocks.isMotionBlocking(block)) {
            if (y + 1 > heightmap[col]) {
                heightmap[col] = (short) (y + 1);
            }
        } else if (heightmap[col] == y + 1) {
            recomputeColumn(col, y);
        }
        if (Blocks.blocksMotion(block)) {
            if (y + 1 > oceanFloor[col]) {
                oceanFloor[col] = (short) (y + 1);
            }
        } else if (oceanFloor[col] == y + 1) {
            recomputeFloorColumn(col, y);
        }
    }

    private void recomputeColumn(int col, int from) {
        int base = col * HEIGHT;
        int h = 0;
        for (int y = from; y >= 0; y--) {
            if (Blocks.isMotionBlocking(blocks[base + y])) {
                h = y + 1;
                break;
            }
        }
        heightmap[col] = (short) h;
    }

    private void recomputeFloorColumn(int col, int from) {
        int base = col * HEIGHT;
        int h = 0;
        for (int y = from; y >= 0; y--) {
            if (Blocks.blocksMotion(blocks[base + y])) {
                h = y + 1;
                break;
            }
        }
        oceanFloor[col] = (short) h;
    }

    /**
     * {@code Heightmap.Types.OCEAN_FLOOR_WG}: one past the highest block that
     * blocks motion, so water does not count. {@code OreFeature} tests its blob
     * against this before deciding to place, which means it has to be tracked as
     * the world changes rather than read off the pure noise terrain.
     */
    public int getHeightOceanFloor(int x, int z) {
        if (!inside(x, z)) {
            return 0;
        }
        return oceanFloor[columnIndex(x, z)];
    }

    @Override
    public int getHeightMotionBlocking(int x, int z) {
        if (!inside(x, z)) {
            return 0;
        }
        return heightmap[columnIndex(x, z)];
    }

    /**
     * Writes a whole freshly generated noise column at once, bypassing the
     * per-block heightmap bookkeeping. Only valid on an empty column: it assumes
     * nothing above {@code height} and takes MOTION_BLOCKING straight from the
     * caller, which is what the terrain generator already computed.
     */
    public void setNoiseColumn(int x, int z, byte[] column, int height) {
        if (!inside(x, z)) {
            return;
        }
        int col = columnIndex(x, z);
        System.arraycopy(column, 0, blocks, col * HEIGHT, HEIGHT);
        heightmap[col] = (short) height;
        int floor = 0;
        for (int y = height - 1; y >= 0; y--) {
            if (Blocks.blocksMotion(column[y])) {
                floor = y + 1;
                break;
            }
        }
        oceanFloor[col] = (short) floor;
    }

    /** Fills [y0, y1] inclusive across the whole window. */
    public void fillLayers(int y0, int y1, byte block) {
        for (int x = minX; x < minX + sizeX; x++) {
            for (int z = minZ; z < minZ + sizeZ; z++) {
                for (int y = y0; y <= y1; y++) {
                    setBlock(x, y, z, block);
                }
            }
        }
    }

    /** Fills a single column over [y0, y1] inclusive. */
    public void fillColumn(int x, int z, int y0, int y1, byte block) {
        for (int y = y0; y <= y1; y++) {
            setBlock(x, y, z, block);
        }
    }

    /** Height of the contiguous sugar cane run whose bottom block is (x, y, z); 0 if none. */
    public int caneHeightAt(int x, int y, int z) {
        if (getBlock(x, y, z) != Blocks.SUGAR_CANE) {
            return 0;
        }
        if (getBlock(x, y - 1, z) == Blocks.SUGAR_CANE) {
            return 0; // not the bottom of the stack
        }
        int h = 0;
        while (getBlock(x, y + h, z) == Blocks.SUGAR_CANE) {
            h++;
        }
        return h;
    }

    /**
     * Height of the whole contiguous cane run that passes through (x, y, z),
     * found by walking down to its base first. Cheap alternative to scanning the
     * window when you already know a block that belongs to the run.
     */
    public int caneRunThrough(int x, int y, int z) {
        if (getBlock(x, y, z) != Blocks.SUGAR_CANE) {
            return 0;
        }
        int bottom = y;
        while (getBlock(x, bottom - 1, z) == Blocks.SUGAR_CANE) {
            bottom--;
        }
        return caneHeightAt(x, bottom, z);
    }
}
