package dev.drakou111.sugarcane.world;

/**
 * The terrain oracle the feature simulation runs against.
 *
 * <p>This is the seam where a real 1.16.1 chunk generator gets plugged in later.
 * The feature only ever needs: read a block, write a block, and query the
 * MOTION_BLOCKING heightmap.
 */
public interface BlockView {

    byte getBlock(int x, int y, int z);

    void setBlock(int x, int y, int z, byte block);

    /**
     * {@code LevelAccessor.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z)} —
     * the Y of the first free block above the topmost motion-blocking-or-fluid block.
     *
     * <p>Must reflect blocks placed by features earlier in the same generation
     * step (trees and leaves raise it; sugar cane does not).
     */
    int getHeightMotionBlocking(int x, int z);

    default boolean isAir(int x, int y, int z) {
        return Blocks.isAir(getBlock(x, y, z));
    }

    default boolean isWaterFluid(int x, int y, int z) {
        return Blocks.isWaterFluid(getBlock(x, y, z));
    }
}
