package dev.drakou111.sugarcane.gen;

import dev.drakou111.sugarcane.world.ArrayWorld;
import dev.drakou111.sugarcane.world.Blocks;

/**
 * Hand-built terrain snapshots used to isolate what the feature needs.
 *
 * <p>These are not "found" terrain — they are the geometry the search has to
 * look for. Building them explicitly lets us measure the RNG side of the
 * problem separately from the terrain side.
 */
public final class Scenarios {

    /** Ground surface: soil at Y=62, first air block at Y=63, as at 1.16 sea level. */
    public static final int SOIL_Y = 62;

    private Scenarios() {
    }

    /**
     * An ordinary shore: flat soil with a water body whose surface is level with
     * the soil. This is what essentially every naturally generated cane spot in
     * 1.16.1 looks like.
     *
     * @param wallHeight number of water blocks stacked in the neighbouring column,
     *                   starting at {@link #SOIL_Y}. 1 = a normal flat shore.
     *                   Anything &gt; 1 is a vertical water face, which is what a
     *                   stacked column needs and what ordinary terrain does not
     *                   supply.
     */
    public static ArrayWorld shore(int wallHeight) {
        // Chunk 0 plus the 4-block margin the ±4 offsets can reach.
        ArrayWorld world = new ArrayWorld(-8, -8, 32, 32);
        world.fillLayers(0, SOIL_Y - 1, Blocks.SOLID);
        world.fillLayers(SOIL_Y, SOIL_Y, Blocks.SAND);

        // A north-south water body through the middle of chunk 0.
        int waterX = 8;
        for (int z = -8; z < 24; z++) {
            world.fillColumn(waterX, z, SOIL_Y, SOIL_Y + wallHeight - 1, Blocks.WATER);
        }
        return world;
    }

    /**
     * The minimum geometry that yields a column taller than 4: soil at Y=62 with
     * water beside it, and that same water body continuing up past the top of a
     * first column so the second column's {@code needWater} check also passes.
     *
     * <p>A 4-high wall (Y=62..65) covers first columns of height 2 and 3.
     */
    public static ArrayWorld exposedWaterWall() {
        return shore(4);
    }

    /**
     * Exactly one stackable spot in the whole chunk, which is what real terrain
     * actually offers. The scenarios above line a whole water body with valid
     * spots and so overstate the odds by more than an order of magnitude.
     *
     * @param soilY      Y of the soil block; the cane base sits at soilY+1
     * @param waterTopY  topmost water block of the column beside the spot
     * @param groundY    surface height of the surrounding terrain
     */
    public static ArrayWorld isolatedStackableSpot(int soilY, int waterTopY, int groundY) {
        ArrayWorld world = new ArrayWorld(-8, -8, 32, 32);
        world.fillLayers(0, groundY - 1, Blocks.SOLID);

        int x = 8, z = 8;
        world.fillColumn(x, z, soilY + 1, ArrayWorld.HEIGHT - 1, Blocks.AIR);
        world.fillColumn(x, z, soilY, soilY, Blocks.SAND);
        world.fillColumn(x + 1, z, soilY, waterTopY, Blocks.WATER);
        world.fillColumn(x + 1, z, waterTopY + 1, ArrayWorld.HEIGHT - 1, Blocks.AIR);
        return world;
    }

    /**
     * The realistic target: a dry air pocket below sea level, cut by a structure
     * that writes air blocks (jigsaw villages, igloos and woodland mansions use
     * {@code BlockIgnoreProcessor.STRUCTURE_BLOCK}, which keeps air), leaving the
     * natural sea floor as its base and a tall column of untouched sea-fill water
     * beside it.
     *
     * <p>The water here is noise-generated, so nothing ever scheduled a fluid
     * tick on it — neither {@code ProtoChunk.setBlockState} nor
     * {@code WorldGenRegion.setBlock} does. The pocket therefore stays dry
     * through chunk load, unlike anything an underwater carver produces.
     *
     * @param floorY    Y of the sea floor block; cane bases sit at floorY+1
     * @param waterTopY topmost water block of the sea fill (62 at 1.16 sea level)
     */
    public static ArrayWorld structureAirPocket(int floorY, int waterTopY) {
        ArrayWorld world = new ArrayWorld(-8, -8, 32, 32);
        world.fillLayers(0, floorY - 1, Blocks.SOLID);
        world.fillLayers(floorY, floorY, Blocks.SAND);
        world.fillLayers(floorY + 1, waterTopY, Blocks.WATER);

        // A one-block-wide dry slot through the water, as a structure wall would
        // leave, keeping the natural sea floor as its base.
        int slotX = 8;
        for (int z = 4; z <= 12; z++) {
            world.fillColumn(slotX, z, floorY + 1, waterTopY, Blocks.AIR);
            // The sea floor beside the slot sits one block lower, so the water
            // column reaches down to the soil level. Without this the base's own
            // needWater check fails and nothing generates at all.
            world.fillColumn(slotX - 1, z, floorY, floorY, Blocks.WATER);
        }
        return world;
    }
}
