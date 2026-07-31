# Sugar cane taller than 4: mechanics, version choice, and what to search for

## FOUND: seed 1500050556, 5 tall at 91,16,65 (1.16.1)

Verified on a real 1.16.1 server, and reproducible: a **five-tall** sugar cane
about 112 blocks from spawn, standing on dirt at y=16..20, deep underwater in a
flooded cave in a deep lukewarm ocean (biome 48). A three-tall column sits beside
it at 90,16,65.

It formed exactly the way section 2 predicts — two invocations of the same chunk's
`patch_sugar_cane`, the second landing on top of the first:

```
invocation 1: origin 91,64 y=16
    try 6 PLACED 91,16,65 height 3     <- first column, y=16..18
invocation 4: origin 91,68 y=19
    try 15 PLACED 91,19,65 height 2    <- lands on the first column's top
```

Growth alone caps at 3 and worldgen at 4, so this is only reachable by stacking.

The find came immediately after the underwater carver's cursor bug (section 6r)
was fixed. Every hit reported before that fix was false, and all of them were
checked and failed; the first hit after it verified.


Everything below is read off the **1.16.1 server decompiled with official Mojang
mappings**, not from memory or the wiki. Class names are Mojang mappings.

## 1. Why 4 is the natural ceiling

`SugarCaneBlock.randomTick` counts the canes below and gates growth on `n < 3`,
so growth alone tops out at 3. Worldgen places columns of `2 + nextInt(nextInt(3) + 1)`
(`ColumnPlacer`, min 2, extra 2) → heights 2/3/4 at 11/18, 5/18, 2/18.

Anything above 4 requires **two worldgen columns stacked**.

## 2. The check that actually blocks stacking

`RandomPatchFeature.place` accepts a position when all of:

1. the target block is air (`canReplace` is false for this config);
2. `SugarCaneBlock.canSurvive` — **free for a stacked column**, since it returns
   `true` immediately when the block below is sugar cane;
3. `needWater` — water fluid in one of the four horizontal neighbours of
   `pos.below()`.

For a column stacking onto another, `pos.below()` is the **top block of the lower
column**. So the whole problem reduces to:

> water 2–4 blocks above the soil, horizontally adjacent, at the moment the
> sugar cane feature runs.

`FluidTags.WATER` matches both `water` and `flowing_water`.

Valid soil is an explicit list, not a tag: `grass_block, dirt, coarse_dirt,
podzol, sand, red_sand`. Mycelium is not included.

## 3. Version choice: 1.16.1, not 1.21+

| version | placement | can a chunk stack onto itself? |
|---|---|---|
| 1.16.1 / 1.17.1 | `COUNT_HEIGHTMAP_DOUBLE(n)`: **n independent invocations**, each drawing its own XZ and `nextInt(2 × heightmap)` for Y | **yes** |
| 1.18.2 / 1.21.x / current | `rarity_filter(6) → in_square → heightmap`: **one invocation**, Y pinned to the surface | no |

In 1.18+ all 20 tries share one surface-locked Y, so a second column can never
start on top of a first. It would take a neighbouring chunk's feature bleeding
over, and inter-chunk decoration order depends on how the world is explored, so
such a find would not be reproducible. **1.21+ is not worth searching.**

Two 1.16.1 details make it work:

- `CountHeighmapDoubleDecorator` draws `nextInt(16)` for X, `nextInt(16)` for Z,
  then `nextInt(2 * heightmap)` for Y — Y is *not* tied to the surface, so cane
  is attempted underground and high in the air too;
- the decorator returns a **lazy** `IntStream`, so invocation *n* sees blocks
  placed by invocations `< n`. Stacking happens within one chunk, deterministically.

Invocations per chunk (`BiomeDefaultFeatures`): default **10**, badlands **13**,
swamp **20**, desert **60**. Oceans and rivers are included via
`addDefaultExtraVegetation`. The feature list comes from the biome sampled at the
**chunk centre** (`ChunkGenerator.applyBiomeDecoration`).

## 4. The RNG is not the bottleneck — measured

`Main` runs the exact feature over N decoration seeds against fixed terrain.
Tallest column produced, 200k seeds:

| water column beside the soil | count=10 | count=20 | count=60 (desert) |
|---|---|---|---|
| 1 tall (ordinary shore) | **0** | **0** | **0** |
| 2 tall | **0** | **0** | **0** |
| 3 tall | 0.009% | 0.035% | 0.28% |
| 4 tall | 0.016% | 0.074% | 0.61% |
| below-sea-level pocket (6 tall face) | 0.008% | — | 0.27%, up to **8 tall** |

A 2-tall water column is not enough: the shortest first column is 2, so the face
must reach soil+2, i.e. **3 blocks minimum**.

Those rows line a whole water body with valid spots. Real terrain offers **one**
spot, which is roughly 10× worse (2M seeds each):

| single isolated spot | count=10 | count=60 (desert) |
|---|---|---|
| soil Y=52, water Y=52..56 | 0.0011% | 0.0325% |
| soil Y=62, water Y=62..65 | 0.0005% | 0.0238% |

So, per chunk that contains one stackable spot: **~1e-5** in an ordinary biome,
**~3e-4** in desert. Call this P.

## 5. Where elevated water can and cannot come from

Step order (`GenerationStep.Decoration`, and `Biome.generate` places structures
of a step before that step's features):

```
noise → surface → CARVERS(air) → LIQUID_CARVERS
  → 1 LAKES → 3 UNDERGROUND_STRUCTURES(mineshafts, dungeons)
  → 4 SURFACE_STRUCTURES(villages, ocean ruins, shipwrecks) → 5 STRONGHOLDS
  → 6 UNDERGROUND_ORES(dirt blobs) → 8 VEGETAL_DECORATION(… sugar cane … springs)
```

| source | verdict |
|---|---|
| **Noise sea fill** | Below y=63 every non-solid block is water, flat top at y=62. No vertical face on its own — but it is the only water that **never gets a scheduled fluid tick**. |
| **Water springs** | Dead. `addDefaultSprings` is registered *after* `addDesertExtraVegetation` in the same `VEGETAL_DECORATION` step, so springs do not exist yet when cane generates. |
| **Lakes** | Dead. `LakeFeature` has an explicit boundary pass: it aborts if any block bordering the water half is not solid, or if anything bordering the air half is liquid. Fully sealed, flat surface. |
| **Ocean ruins / shipwrecks** | Dead. Both use `BlockIgnoreProcessor.STRUCTURE_AND_AIR`, which strips air from the template, so they stay water-filled. |
| **Underwater carvers** | Produces the geometry but **self-destructs** — see below. |
| **Structures that keep air** | **The viable path.** |

### Underwater carvers flood the cave — but the cane survives

`UnderwaterCaveWorldCarver.carveBlock` places water and, when a horizontal
neighbour is air or outside the chunk, also calls
`chunkAccess.getLiquidTicks().scheduleTick(pos, WATER, 0)`. Those scheduled ticks
are carried into the `LevelChunk` and run when the chunk goes full, i.e. **after**
features, so the cave does flood.

**Correction.** An earlier version of this file said the carver "stops at an
existing air cave" because `replaceableBlocks` excludes air. That is true of the
base `WorldCarver`, but `UnderwaterCaveWorldCarver` overrides
`replaceableBlocks` to *include* `AIR` and `CAVE_AIR` (and `WATER`), and also
overrides `hasWater` to return `false`. So it happily overwrites air with water.
The water face beside air arises from the **carve volume boundary** — blocks
inside the underwater tunnel become water, blocks outside it stay air — not from
the carver refusing to enter air. The observed geometry and every rate in this
document are unaffected; only the stated reason was wrong.

**The cane is not destroyed.** `FlowingFluid.canHoldFluid` refuses to spread into
sugar cane explicitly:

```java
if (block instanceof DoorBlock || block.is(BlockTags.SIGNS) || block == Blocks.LADDER
    || block == Blocks.SUGAR_CANE || block == Blocks.BUBBLE_COLUMN) {
    return false;
}
```

`canSurvive` also still holds after flooding — the soil is untouched and water is
adjacent. So a column placed at generation time stands in the flooded cave and is
fully visible. Underwater carvers are therefore a **live mechanism**, and by far
the most common source of tall water faces.

### Consequence: never measure the geometry on fully generated chunks

Because the flood happens after features, a saved **full** chunk no longer shows
the water/air boundary the cane feature saw. Chunks saved at status `features` /
`light` / `spawn` / `heightmaps` have had all features applied but have **not**
run their fluid ticks, because those only execute on promotion to a
`LevelChunk`. Those proto-chunks are the correct sample.

Measured on seed 1:

| sample | chunks | stackable spots | rate |
|---|---|---|---|
| full (post-flood) | 83,718 | 24 | 2.9e-4 |
| **proto (feature-time)** | 35,490 | **14** | **3.9e-4** |

**R ≈ 4e-4 per chunk**, measured on proto-chunks.

### Only proto-chunks give a trustworthy rate

Measured across two independently generated worlds:

| world | proto chunks | hits | rate | full chunks | hits | rate |
|---|---|---|---|---|---|---|
| srv | 1,643 | 1 | 6.1e-4 | 80,614 | **0** | 0 |
| srv2 | 35,490 | 14 | 3.9e-4 | 83,718 | **24** | 2.9e-4 |

The proto rates agree (6.1e-4 vs 3.9e-4). The full rates do not agree at all —
zero versus 24 on samples of the same size. That is not Poisson noise.

The likely cause is that flooding depends on how the world was pregenerated.
Fluid spreading across a chunk border needs the neighbour loaded, so a world
built from large contiguous forceload batches (srv) floods thoroughly, while one
built from small isolated loads (srv2) floods only partially and leaves
carver-cut water faces intact in chunks that are nonetheless marked `full`.

**Consequence: never measure R on full chunks.** An earlier claim in this file
that full chunks are "a usable proxy within ~1.4x" was wrong — the full-chunk
figure is an artefact of the pregeneration pattern. Earlier claims of a 30x and
then a 2x proto/full gap were also wrong, both computed from single-digit event
counts. Use proto-chunks, and quote no ratio from fewer than ~10 events.

To mass-produce proto-chunks, forceload *isolated* single chunks with a stride of
16+ chunks. At stride 8 with 40 forceloads in flight the halos merge and every
would-be `features` chunk gets promoted to `full` — a 3000-chunk run at stride 8
produced **zero** proto-chunks.

### Structures that keep air are the target

`ProtoChunk.setBlockState` and `WorldGenRegion.setBlock` schedule **no** fluid
ticks and perform no neighbour updates. So an air block written next to
noise-generated water leaves a **permanently static** water face — it survives
chunk load and stays dry.

Structures using `BlockIgnoreProcessor.STRUCTURE_BLOCK` (keeps air):

- `SinglePoolElement` / `LegacySinglePoolElement` → **jigsaw villages**, pillager outposts
- `IglooPieces`
- `WoodlandMansionPieces`

**Search target:** a village (or igloo / mansion) piece writing air below sea
level beside a body of water at least 3 blocks deep, with natural sand or dirt
left as the pocket's floor and water reaching down to that soil level.

Best case is a **desert village on a river or ocean shore** — desert gives 60
invocations per chunk, and a deep face allows columns up to 8.

## 5b. Two mechanisms that survive chunk load

Both need a structure that writes air (`STRUCTURE_BLOCK` processor) to cut into
water that was placed without a fluid tick:

- **structure air ∩ sea fill** — an air pocket below y=63 beside ocean or river
  water. The face is as tall as the water is deep.
- **structure air ∩ lake** — `LakeFeature` fills its bottom **4 layers** with
  water via `setBlock`, so a breached lake gives a 4-tall static face, and the
  lake's own post-pass converts the rim dirt to grass_block, supplying the soil.

Neither is tick-scheduled, so unlike the carver case the pocket stays dry.

Ruled out along the way: village well and fountain templates keep their water
fully sealed in cobblestone (all 10 water-bearing templates in the game show
zero water blocks adjacent to template air). `plains_fountain_01` does have a
3-tall water column whose top two blocks border unwritten positions, but its
pool is ringed by cobblestone at every level, so no soil can ever sit beside it.

## 5c. Feasibility with the measured rate

**Search oceans, not deserts.** Tallying stackable spots by the chunk's centre
biome (38 hits over seed 1):

| biome | chunks | hits | R |
|---|---|---|---|
| frozen_ocean (10) | 447 | 1 | 2.2e-3 |
| cold_ocean (46) | 4,494 | 9 | 2.0e-3 |
| lukewarm_ocean (45) | 4,971 | 7 | 1.4e-3 |
| deep_ocean (24) | 7,174 | 8 | 1.1e-3 |
| deep_cold_ocean (49) | 4,019 | 4 | 1.0e-3 |
| ocean (0) | 8,516 | 7 | 8.2e-4 |
| warm_ocean (44) | 3,349 | 2 | 6.0e-4 |
| **every land biome combined** | ~25,000 | **0** | **< 1.2e-4** |

Every hit is in an ocean. This is the underwater carver mechanism confirmed from
the other direction: `addOceanCarvers` registers `UNDERWATER_CAVE` and
`UNDERWATER_CANYON` only for ocean biomes, and they are what cuts a tall water
face against an air cave. Land biomes have no such carver, so the geometry
essentially never forms there.

Consequently **desert targeting is worthless**: desert's 60 invocations buy a 30x
better P, but R there is ~0, so the product is ~0. The right target is ocean,
where R ~ 1.1e-3 is about 3x the global average and P is the ordinary 1.1e-5.

| target | R | P | rate per chunk | ocean chunks needed |
|---|---|---|---|---|
| ocean (count=10), projected here | ~1.1e-3 | ~1.1e-5 | ~1.2e-8 | ~8e7 |
| ocean (count=10), **measured in 6i** | 1.32e-3 | 5.40e-6 | 7.1e-9 | 1.4e8 |

Oceans are roughly a third of the overworld, so ~2.5e8 chunks generated overall —
except that a biome-only prefilter (no terrain needed) can skip non-ocean chunks
cheaply, which is a large constant-factor win for the searcher.

### Constraining the find to near spawn is nearly free (measured)

Total chunks examined is what costs; *where* they come from does not. Requiring
the result near (0,0) just restructures the loop from "one seed, 1e8 chunks" to
"1e5 seeds x 1024 chunks each" (a 32x32 chunk box around spawn). Same total work,
and seeds are embarrassingly parallel, so it is if anything easier to distribute.

This only hurts with the vanilla-server prototype, where each seed needs a fresh
world and a ~10s restart. With a custom generator, per-seed cost is just noise
initialisation, amortised over the 1024 chunks.

Measured with `RegionSearcher` on 24 threads, all on an otherwise idle machine:

| chunk radius | distance from 0,0 | searched chunks/s | expected time to a find |
|---|---|---|---|
| 32 | +/-512 blocks | 10,650 | 3.6 h |
| 6 | +/-96 blocks | 10,979 | 3.6 h |
| 4 | +/-64 blocks | 9,805 | 4.0 h |
| 2 | +/-32 blocks | 7,986 | 4.9 h |
| 1 | +/-16 blocks | 5,714 | 6.8 h |
| 0 | chunk 0,0 only | 1,684 | 23 h |

So it is free down to about +/-96 blocks and cheap down to +/-32, then falls off a
cliff. The cliff is the eight-neighbour requirement: one searched chunk needs its
3x3 neighbourhood generated, so when the box is only a few chunks across almost
everything generated is neighbourhood rather than result. At radius 0 that is nine
generated chunks for one searched.

(An earlier reading of 78% was taken while another 24-thread search was still
running and is wrong; measure throughput on an idle machine.)

Two small costs do exist and are evidently absorbed:

- **the neighbourhood overhead is worse in a small box.** A searched chunk needs
  its eight neighbours generated; in a 32-chunk box that costs 1.43 generated
  chunks per searched chunk, in a 6-chunk box 1.58;
- **per-seed setup stops being amortised.** Each seed builds a biome source and
  four octave noise samplers, spread over ~38 searchable chunks instead of ~1400.

The region side adapts to the box (`regionFor`): it has to be at least
`2 * radius + 3` so the whole box lands in the region interior, since border
chunks are never searched. Chunks outside the radius are skipped even when the
region covers them, so the reported find is genuinely bounded.

## 6. The open question, now answered

The remaining unknown was purely empirical: **how often does that pocket geometry
occur?** Call it R per chunk.

Both R and P are now measured on generated terrain rather than estimated — see
section 6i. R = 1.32e-3 per ocean chunk, P = 5.40e-6, so a >4 column costs about
**1.4e8 ocean chunks**, or four hours at 10,700 chunks/s.

The geometry test used throughout is: soil at y-1, air at y and y+1 and y+2,
water beside (x, y-1, z) **and** beside (x, y+1, z).

## 6b. The simulation is validated against the real game

`RealWorldValidator` replays `patch_sugar_cane` over terrain exported from
chunks a real 1.16.1 server generated, and asks whether it places exactly the
cane the game placed. The biome's invocation count and the feature's index are
**not** hard-coded — they are recovered by trying every plausible pair.

Result on seed 1, 105 testable chunks: **54 reproduced exactly (51.4%)**,
including 4 chunks with 9 interior cane blocks and one with 11. Reproducing the
exact x/y/z of 11 blocks by luck is impossible; a wrong RNG order scores zero.

The recovered parameters match the decompiled source independently:

| biome | recovered | source |
|---|---|---|
| 2, 17 (desert) | count 60, index 5 | `addDesertExtraVegetation` count 60 |
| 6 (swamp) | count 20, index 9 | `addSwampExtraVegetation` count 20 |

**Why the other ~49% cannot match.** Placements use a ±4 offset, so ~44% of the
placement window lies outside the chunk, in neighbours that had not been
decorated yet when this chunk's cane ran. A saved world shows those neighbours
*fully* decorated. One differing success/failure anywhere in the chunk desyncs
the RNG (`ColumnPlacer` only draws its 2 values on success) and every later
placement diverges. This is inherent to validating against saved worlds, not a
model defect — and it does not affect the searcher, which generates the world in
the correct order itself.

Two hypotheses tested and rejected along the way: spring contamination (clean
chunks scored 54.9% vs 51.4% overall — no real difference) and the heightmap
plant bug (49.5% -> 51.4%). The plant fix was still correct and is kept:
`grass`, `tall_grass` and flowers are not motion-blocking, so they must not
raise the MOTION_BLOCKING heightmap that `nextInt(2 * height)` samples. See
`Blocks.PLANT`.

## 6c. Biome source: reused and verified (the ocean prefilter)

Since only ocean chunks can produce the geometry, the searcher can reject ~2/3 of
chunks using biomes alone, before touching any terrain code. That prefilter uses
KaptainWutax's `BiomeUtils` rather than a fresh implementation of the ~20-layer
1.16.1 stack.

`BiomeSourceValidator` checks it against the `Biomes` arrays of real generated
chunks: **320,000 / 320,000 cells agree over 20,000 chunks on seed 1**.

Two traps worth recording:

- Query `getBiomeForNoiseGen(quartX, 0, quartZ)`, **not** `getBiome(blockX, ...)`.
  A chunk's stored `Biomes[]` holds noise biomes at quart resolution; `getBiome`
  additionally applies the Voronoi fuzzing the game only uses for per-block
  runtime queries. Using it scores 93.5%, failing exactly at biome boundaries
  (deep_ocean<->ocean, beach<->river, frozen_river<->snowy_tundra).
- The JitPack dependency versions are fragile. `BiomeUtils:1.0.0` declares
  `MCUtils:11e3c708...`, which does not build on JitPack; the working
  combination is `MCUtils:1e5785a6...` with `NoiseUtils:288e1b60...` (the
  NoiseUtils that 1.0.0 was compiled against — pairing it with the version from
  the master branch throws `NoSuchMethodError` at runtime). `BiomeSourceTest`
  pins known-good values so a version change cannot break this silently.

## 6d. Terrain generator: reused, NOT yet fully verified

`TerrainUtils` (`OverworldTerrainGenerator`) supplies the noise terrain and
surface builder. `TerrainValidator` compares `getHeightOnGround` against the
`OCEAN_FLOOR` heightmap of real chunks, over 3,000 chunks on seed 1:

| ocean chunks | share |
|---|---|
| exact match | 96.02% |
| real lower than generated (carved - expected) | 2.78% |
| real higher than generated (**unexplained**) | 1.21% |

Carving explains the "lower" side: underwater carvers cut the sea floor after
the noise stage. The "higher" side does not yet have a confirmed cause. Coral
and icebergs were the obvious candidates, but restricting to plain oceans
(no warm, no frozen) leaves the number completely unchanged, so that is ruled
out. Ocean ruins, shipwrecks and monuments add blocks above the floor and remain
plausible, but 1.2% of all columns feels high for structures alone.

**Do not treat the terrain layer as verified until this is closed.** A 1%
systematic height error would shift the `nextInt(2 * heightmap)` draw and
desynchronise the cane RNG exactly as the grass/heightmap bug did.

The clean way to settle it: the `_WG` heightmaps are computed at the `noise`
status, before carvers and features, and would isolate the noise terrain
perfectly - but they are **not saved for chunks written as `full`**. Only
proto-chunks from `noise` onward carry them, and the pregen used so far forces
almost everything to `full`. A pregen tuned to leave many chunks at `noise` or
`surface` status would give a contamination-free comparison.

Note also `getHeightInGround` is not the counterpart of `WORLD_SURFACE`
(0.9% agreement); over ocean `WORLD_SURFACE` is just sea level, since it counts
fluids.

## 6e. Carvers: seeding and start-chunk selection (implemented)

No community library covers carvers, so this one is written here.
`ChunkGenerator.applyCarvers`, for the chunk being generated:

```
for startX in chunkX-8 .. chunkX+8:
  for startZ in chunkZ-8 .. chunkZ+8:
    for carverIndex, carver in biome.getCarvers(step):
      random.setLargeFeatureSeed(levelSeed + carverIndex, startX, startZ)
      if random.nextFloat() <= carver.probability:
          carver.carve(...)
```

Three things to get right:

- the carver list comes from the biome at the **generating** chunk's corner,
  `getNoiseBiome(chunkX << 2, 0, chunkZ << 2)` — not the start chunk's biome;
- the salt is the carver's index in that biome's list **for that step**, so AIR
  carvers use 0 and 1 and LIQUID carvers restart at 0;
- 289 candidate start chunks per chunk, so expected starts = 289 * probability.

Probabilities (`BiomeDefaultFeatures`): cave 0.14285715 on land but 0.06666667
in ocean, canyon 0.02, underwater canyon 0.02, underwater cave 0.06666667.
`CarverConfig` holds these; `CarverConfigTest` checks the seeding against
`java.util.Random` and that observed start-chunk counts track 289 * p.

### Cave carver geometry (implemented, not yet validated)

`CaveCarver` transcribes `CaveWorldCarver`: cave count
`nextInt(nextInt(nextInt(15)+1)+1)`, a 1-in-4 chance of a room plus
`nextInt(4)` extra branches, then per branch a tunnel of
`112 - nextInt(112/4)` steps of `carveSphere`.

Traps found while transcribing:

- `getRange()` is **4**, not 8. The driver's start-chunk radius is 8, but the
  tunnel length comes from the range: `(4 * 2 - 1) * 16 = 112`. The two look
  interchangeable and are not.
- `carveSphere` seeds its RNG with `tunnelSeed + chunkX + chunkZ`, i.e. per
  chunk, not per tunnel step.
- `skip()` is `dy <= -0.7 || dx^2 + dy^2 + dz^2 >= 1.0` — the -0.7 gives caves
  their flat floors.
- `hasWater` is a **shell** test, not a volume test: interior columns only check
  floor and ceiling (the `y = y1` jump at the end of the loop).
- The y loop runs downward, `for (y = y1; y > y0; y--)`, and the block sampled is
  `y - 0.5`, not `y + 0.5` as for x and z.

`CaveCarverTest` covers determinism, the water guard aborting every sphere, and
that no air is cut below y=11 (lava) or above genHeight-8.

**These are self-consistency tests only.** Nothing yet checks that the carved
*shape* matches the real game. Float-vs-double and `Mth.sin/cos` table lookups
(float-precision in vanilla, `Math` here) can move a block boundary. Validate by
comparing carved air below sea level in ocean chunks against real chunks before
the search depends on it.

**Since done:** the underwater cave carver, both canyon carvers and the dirt
blobs. Lakes remain unimplemented, and section 5 argues they are dead for this
purpose anyway — a lake aborts if anything bordering its water half is not solid,
so it can never form beside a cave.

## 6f. Where the build stands

| subsystem | state |
|---|---|
| biome source / ocean prefilter | **verified exact** — 320,000/320,000 cells |
| terrain noise (`TerrainUtils`) | 96.0% on ocean floors; 1.2% unexplained, accepted |
| terrain noise, truncated fast path (`TruncatedNoise`) | **verified exact** below y=104 against TerrainUtils itself |
| surface builder (`SurfaceBuilder`, `SurfaceConfig`) | implemented; confirmed end-to-end (cane standing on generated sand and grass_block at the exact predicted blocks) |
| carver driver + start chunks (`CarverConfig`) | implemented, seeding tested |
| cave carver (`CaveCarver`) | **validated** - 90.4% precision vs real ocean chunks |
| underwater cave carver | implemented; the water face it leaves is confirmed against a real chunk (6k) |
| canyon + underwater canyon (`CanyonCarver`) | implemented; raises the spot rate 4.4x; the void it cuts is confirmed against a real chunk (6k) |
| dirt blobs (`OreBlob`) | implemented; feature index confirmed to be 0, and a predicted blob found at the exact block in a real world (6k) |
| sugar cane feature | **verified** against the real game |
| lakes, sand/clay/gravel disks, structures | not implemented |
| **end-to-end search** (`RegionSearcher`) | 11,400 searched chunks/s on 24 threads |

### Carver validation result

`CarverValidator` runs the cave carver over real ocean chunks and asks how much
of what it carves is genuinely air in the saved world:

```
ocean chunks scored : 74
blocks carved       : 15384
also air in reality : 13912
PRECISION           : 90.43%
```

Sub-sea air is a small fraction of an ocean chunk's volume, so a wrong tunnel
walk would score near zero. 90.4% says the walk, the RNG order and the sphere
geometry are substantially right.

The missing ~10% has three known causes, none of which imply a bug:

- the **underwater carver runs after** the air carvers and its
  `replaceableBlocks` includes `AIR`, so some genuinely-carved air is water in
  the final chunk;
- **flooding**: fluid ticks from the underwater carver flood adjoining caves
  once the chunk goes full, turning carved air into water in the saved data;
- lakes, disks and ocean structures fill or replace blocks later.

Measured as precision, not recall, because the canyon carver is not implemented
and so the carved set is necessarily a subset of the real air.

### FIXED: TerrainUtils supplies no surface blocks

`OverworldTerrainGenerator.getColumnAt` returns **raw noise terrain only** -
every solid block comes back as `stone`. There is no grass_block, dirt, sand or
gravel anywhere. Despite the class extending `SurfaceGenerator`, that name refers
to the noise *surface* (the density field), not Minecraft's block-palette surface
builder, and the library has no equivalent of it — checked by decompiling the
jar, there is no `buildSurface` pass to call.

That is why the first integrated search produced almost nothing: over 72,000
ocean chunks it found 401 legal cane positions and one column. Cane needs
`grass_block/dirt/coarse_dirt/podzol/sand/red_sand` beneath it, and none of those
existed.

`SurfaceBuilder` now implements `ChunkGenerator.buildSurfaceAndBedrock` plus
`DefaultSurfaceBuilder`. Legal positions went from 0.0055 to **0.19 per ocean
chunk** (35x), and stackable spots appeared at all.

Three things had to be right:

- the RNG is seeded once per chunk with `setBaseChunkSeed(chunkX, chunkZ)` and
  then **shared by all 256 columns**, visited x-outer, z-inner. One column
  drawing the wrong number of values corrupts every column after it. The only
  draws are one `nextDouble` per column for `depth`, plus a `nextInt(4)` when a
  sand band turns to sandstone;
- the surface noise is
  `surfaceDepthNoise.getSurfaceNoiseValue(x/16, z/16, ...) * 15`. It is an
  *octave simplex* sampler for the overworld (`simplex_surface_noise = true` in
  the overworld preset) built between the main noise and the depth noise from the
  same RNG, so it cannot be reconstructed independently — `Terrain` reads it out
  of the generator by reflection;
- water neither resets the descent nor gets written over: only blocks equal to
  the default block (stone) are touched, and `blockState.isAir()` is what resets
  the run.

The result on an ocean floor: **one gravel block** over stone where
`y < 63 - 7 - depth`, and a **dirt band** of `depth+1` blocks where the floor is
shallower than that. In warm and lukewarm oceans the deep floor is sand instead
(`CONFIG_FULL_SAND` / `CONFIG_OCEAN_SAND`), which *is* cane soil.

### Surface builders that are deliberately not implemented

The chunk RNG is shared across columns, so a biome whose builder consumes a
different number of draws cannot be approximated — it would desynchronise the
whole chunk. `SurfaceConfig.supported()` marks those, and the searcher skips any
chunk whose 3x3 neighbourhood contains one:

| biome | why |
|---|---|
| frozen_ocean, deep_frozen_ocean | `FrozenOceanSurfaceBuilder`: iceberg noise plus `nextInt(4)`, `nextInt(10)` and a `nextDouble` per iterated block |
| badlands, wooded/eroded/modified variants | clay bands |
| swamp, swamp_hills | consumes the RNG *identically*, but writes a water block at y=62 from `Biome.BIOME_INFO_NOISE`, which is not implemented. Approximating it would leave soil where the game has water, i.e. invent placements |

Everything else funnels into `DefaultSurfaceBuilder`: `MOUNTAIN`,
`GRAVELLY_MOUNTAIN`, `GIANT_TREE_TAIGA` and `SHATTERED_SAVANNA` only pick a
different configuration from the noise value and draw exactly the same values.

Temperature matters only for the ice-instead-of-water branch, which fires below
sea level, and `getHeightAdjustedTemperature` only perturbs the value above y=64
— so the flat base temperature is the whole story. No 1.16.1 biome uses a
`TemperatureModifier`.

## 6g. Carver corrections found while wiring the surface in

Once the terrain had real blocks instead of stone, three things in the carvers
turned out to matter:

- **`canReplaceBlock` takes two blocks, not one.** The AIR-step carvers use
  `canReplaceBlock(state, above)`, and sand and gravel are only replaceable when
  the block above holds no water. So the single gravel block on an ocean floor is
  **never carved** by the cave or canyon carver — it is protected by the water
  sitting on it. Treating it as ordinary stone opened caves the game does not
  have. The underwater carvers use the one-argument form and their own much wider
  set, so they cut straight through it;
- **grass follows the cave.** `carveBlock` remembers whether it has passed a
  grass_block or mycelium while descending a column, and if so converts dirt
  under the carved block to the biome's top material. That is a source of cane
  soil, so leaving it out only loses finds — but it is cheap and now implemented;
- **the underwater carver draws a `nextFloat` at y=10** to choose between magma
  and obsidian. Skipping it desynchronises the rest of that sphere.

Two driver details were also wrong:

- both carvers of a generation step **share one carving mask**
  (`getOrCreateCarvingMask(step)`), so whichever reaches a block first owns it;
- and the iteration order is **start chunks outer, the biome's carver list
  inner** — not one carver at a time over all start chunks. With a shared mask
  the order changes the result.

## 6h. Canyons are worth 4.4x

`CanyonWorldCarver` has only a 2% start probability against the cave carver's
6.7%, but a canyon is a long high-walled cut rather than a tube, so it exposes
far more vertical face. Adding it and `UnderwaterCanyonWorldCarver`:

| | stackable spots per ocean chunk |
|---|---|
| caves only | 4.4e-4 |
| caves + canyons | **2.1e-3** |

Differences from the cave carver that are easy to miss: one canyon per start
chunk with no count loop; vertical scale 3; two extra `nextFloat` draws per step
scaling both radii by `0.75..1.0`; and `skip` uses a per-canyon width table
indexed by **absolute block y minus one**, filled by 256 iterations of RNG before
the walk starts.

## 6i. The cost of a find, measured rather than estimated

`RegionSearcher probe:<n>` replays `patch_sugar_cane` over many synthetic
decoration seeds on every chunk that offers a stackable spot. That measures P on
terrain the generator actually produces, instead of on a hand-built pocket.

Over 563,626 searched ocean chunks (seeds 700001..700400):

| quantity | value |
|---|---|
| R, chunks with at least one stackable spot | **1.32e-3** |
| stackable spots per chunk | 2.10e-3 |
| P, per decoration seed, given a spot (743 chunks x 100,000 seeds, 401 hits) | **5.40e-6** |
| product | **7.1e-9 per chunk** |
| chunks per expected find | **1.4e8** |

Two things to note. R now agrees with the 1.1e-3 measured directly on real ocean
chunks (section 5c), which is the strongest evidence yet that the pipeline
produces the same geometry the game does — before the canyons it was 3.5x low.
And the earlier P estimate of 1.1e-5, taken from a hand-built isolated spot, was
**2x optimistic**; the real number is 5.4e-6.

The spots are deep: mean soil y **23.3**, and 98% of them are inside the terrain
rather than on the sea floor surface. That matches the one confirmed real-world
spot at 499163/24/518311, which sits on ore-blob dirt at y=23 — and it means the
unimplemented sand/clay/gravel disks barely matter, since a disk only ever
touches the top solid block of a column.

## 6j. Making it fast enough

Two changes took the search from 5,500 to **10,700 searched chunks/s** on 24
threads, i.e. an expected find in under four hours:

- **regions instead of per-chunk windows.** A chunk can only be judged once its
  eight neighbours are surfaced and carved, so the old code rebuilt a 24x24
  window per chunk — 2.25 columns of work per column of result, and the margin
  was never carved at all. `RegionSearcher` generates a 32x32-chunk region, runs
  the carvers per chunk inside it (each writes only into its own chunk, so this
  is exact), then decorates the interior chunks in raster order;
- **biome depth and scale are memoised per noise cell.** `getDepthAndScale`
  queries the 5x5 cell neighbourhood, so neighbouring cells re-ask the biome
  layer stack for the same values; that alone was 15% of runtime after the noise
  cut;
- **the density noise stops at y=104.** Profiling put 78% of all time in
  `sampleNoiseColumn`: 33 cell values per column, 40 octaves of Perlin each.
  Nothing the search looks at is above y=104, so `TruncatedNoise` evaluates 13 of
  33 cells — a 2.2x end-to-end speedup. It is a bounded transcription of
  TerrainUtils' own code using the generator's own samplers, `TruncatedNoiseTest`
  asserts every block below the cut is identical, and a column whose terrain
  reaches the cut falls back to the full generator.

A third change bought 33% more searched chunks out of the same generated
regions: **every ocean biome except frozen and deep frozen is searched**, not
just the four plain ones.

The worry about warm and lukewarm oceans was coral — it is motion-blocking, so it
would perturb the MOTION_BLOCKING heightmap that `nextInt(2 * height)` samples.
Reading the biome constructors settles it: the coral and seagrass features are
registered *after* `addDefaultExtraVegetation`, so they run after the sugar cane
and cannot touch its RNG. Nothing registered before the cane in any ocean biome
places a motion-blocking block above sea level, so the heightmap is exactly 63
and the draws are reproducible. Measured spot rate in the wider set is 2.15e-3
against 2.10e-3 for the plain four — the same geometry, just more of it.

Frozen oceans stay out because their surface builder is not implemented.

Warm and lukewarm ocean floors are also *safer* than plain ones: their deep floor
is sand rather than gravel, and the unimplemented disks only replace dirt and
clay, so they cannot degrade a sand floor.

## 6k. End-to-end confirmation against the real game

`verify_hit.py` builds a throwaway 1.16.1 server on a reported seed, forceloads a
5x5 chunk block around the target so the chunk actually runs its features, then
reads the saved region file. Run against ordinary height-4 predictions:

| seed | predicted | found in the real world |
|---|---|---|
| 900009 | 4-tall at 196,63,-360 | 4-tall at 196,63,-360, on sand |
| 900009 | 4-tall at 200,63,-357 | 4-tall at 200,63,-357, on sand |
| 900017 | 4-tall at 484,63,425 | 4-tall at 484,63,425, on grass_block |

Exact x/y/z agreement, and the columns stand on blocks that only exist because of
the new surface builder. Chains together the biome source, the noise terrain, the
surface builder, the carvers and the placement RNG in one test.

All three were then **confirmed in a real client** as well, which rules out the
one thing the server-side check could not: a mistake in this project's own
region-file reading.

All three were then **confirmed in a real client** as well, which rules out the
one thing the server-side check could not: a mistake in this project's own
region-file reading.

Note the cane survives the post-generation flood, so a candidate can be checked
in a fully generated world even though the air pocket around it has become water.

### The deep geometry checks out too

`verify_geom.py` does the same for a predicted stackable spot rather than a cane
column, which is the only check there has ever been on the canyon carvers and on
the dirt blobs at depth. On seed 900002 the simulator predicted dirt soil at
894,29,-87 with a 4-tall pocket above it. The real world has:

```
real block there             : dirt
real block above (the pocket): water
neighbours of the block above: -x=water +x=water -z=water +z=cave_air
```

and the slice shows a tall void spanning roughly y=21..39 around it, flooded
exactly as section 5 predicts, with one cave_air block that the flood did not
reach. Dirt at the exact predicted x/y/z is not something a wrong blob decorator
or a wrong carver walk would produce.

Four such spots (seeds 900002, 900004, 900009) were also **checked in a client**
and all four were as predicted. Between this and 6k, every stage of the pipeline
has now been confirmed against the real game at block precision.

### A disagreement that turned out not to be one

The simulator said one of the verified height-4 columns stood on `grass_block`
where the real world had `sand`. That is `DISK_SAND`, whose configuration is
`DiskConfiguration(SAND, radius 7, halfHeight 2, targets {DIRT, GRASS_BLOCK})`
placed by `COUNT_TOP_SOLID(3)` at UNDERGROUND_ORES — it converts the surface
builder's grass to sand at the top solid block, after the surface stage and before
the cane. Soil to soil, so the placement is unaffected.

The other two disks are the ones that could matter, and both are small:
`DISK_CLAY` (clay, radius 4, halfHeight 1, targets dirt and clay) and
`DISK_GRAVEL` (gravel, radius 6, halfHeight 2, targets dirt and grass_block) turn
soil into something cane cannot stand on. All three only ever touch the top solid
block of a column, which is why it matters that 98% of the stackable spots are
inside the terrain rather than on the sea floor surface.

### What could still make a reported hit fail to reproduce

- **margin placements.** A column whose base is outside the chunk interior
  (local x,z in 4..11) can depend on whether a neighbour was decorated first,
  which depends on how the world is explored. `RegionSearcher` decorates in
  raster order and labels every hit `interior` or `margin` for exactly this
  reason;
- **ocean structures.** Ocean ruins and shipwrecks are placed before
  VEGETAL_DECORATION and can raise the heightmap the placement samples. Roughly
  1 chunk in 200-400 is affected and none of it is simulated;
- **lakes and disks**, neither implemented.

## 6l. Where the reference material lives

Not in this repository, and worth knowing before starting again from scratch:

- 1.16.1 server decompiled with official mappings, plus the two pregenerated
  worlds and the python region-file readers, are in the earlier session's
  scratchpad: `%TEMP%\claude\D--code-java-sugarcane\6ee42b05-.../scratchpad`
  (`mc/DecompilerMC/src/1.16.1/server`, `srv`, `srv2`). It is a temp directory —
  copy it somewhere durable before relying on it;
- `verify_hit.py` lives in this session's scratchpad `verify/` and takes
  `SRV_SRC` to point at that server.jar.

## 6m. How to run it

Build the classpath once, then run the searcher directly — `mvn exec:java` adds
startup cost and swallows output:

```
mvn -o -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
mvn -o -q compile
CP="target/classes;$(cat target/cp.txt)"
java -Xmx12g -cp "$CP" dev.drakou111.sugarcane.RegionSearcher      <firstSeed> <seeds> <chunkRadius> <threads> <reportHeight> [diag|probe:N]
```

So the actual search is:

```
java -Xmx12g -cp "$CP" dev.drakou111.sugarcane.RegionSearcher 1 1000000 32 24 5
```

Each seed covers a 128x128-chunk box (the region grid rounds the radius up), of
which roughly 1,400 chunks are searchable ocean. Progress prints once a minute; a
find prints a line starting `HIT` with the seed, the base block of the column, its
height, and whether it is `interior` or `margin (order-dependent)`.

- `diag` adds the geometry counters — legal positions, stackable spots, how deep
  they are. Costs about 25% throughput.
- `probe:N` additionally replays the cane feature over N synthetic decoration
  seeds on every chunk that has a spot, which is how R and P were measured. Use
  a few hundred seeds only; it prints the implied chunks-per-find.

Memory is about 70 MB per thread (a 32x32-chunk region at full height), so 24
threads want roughly 2 GB of heap plus headroom.

### What to do when a HIT appears

1. **Look at what the simulator thinks it found:**

   ```
   java -cp "$CP" dev.drakou111.sugarcane.Inspect <seed> <x> <y> <z>
   ```

   It regenerates that one region and prints the cane columns nearby, the water
   beside the soil and beside the block above, and a vertical slice. If the column
   is not there, the hit came from a different region alignment — `Inspect`
   assumes the searcher's radius of 32.

2. **Confirm it in the real game** with `verify_hit.py` below. A `margin` hit that
   fails to reproduce is expected occasionally (decoration order); an `interior`
   one that fails means a real gap — check the slice against `verify_geom.py`
   output for lakes, disks or a structure the simulator does not know about.

3. The find is a stack of two columns, so in-game it looks like a single cane
   5 to 8 blocks tall, standing in what is now water (the pocket floods on chunk
   load; the cane itself survives).

To check a hit against the real game:

```
cd <scratchpad>/verify
python verify_hit.py <seed> <x> <y> <z> [radiusChunks]
```

It builds a throwaway server on that seed, forceloads a 5x5 chunk block so the
target chunk actually runs its features, then prints every cane column in the 3x3
chunks around the target. `SRV_SRC` points it at a 1.16.1 `server.jar` (see 6l).

`verify_geom.py` takes the same arguments and dumps the blocks around a predicted
stackable spot instead, which is the check to run when a hit does not reproduce.

## 6n. The first hit, and why it was not real

Seed 119658 produced a 5-tall column at -267,26,-190 (deep ocean, chunk -17,-12,
labelled `margin`). It does not exist in the real world: the server has a 2-tall
column at -268,26,-190 and nothing at -267. Confirmed both server-side and in a
client.

`Inspect` prints the placement trace, which localises the divergence exactly:

```
invocation 0: origin -268,-190 y=26 (heightmap 63)
    try 5 PLACED -268,26,-190 height 2      <- the real world has this one
    try 9 PLACED -267,26,-190 height 3      <- the real world does not
invocation 8: origin -266,-191 y=29
    try 18 PLACED -267,29,-190 height 2     <- this is what made it 5 tall
```

So the stream agreed through try 5 and parted company at try 9, one block over.
The soil (dirt at -267,25) and the water beside it are in the real world too, and
the real cane at -268,26 proves the pocket was air at feature time. The only
remaining explanation is that (-267,26) itself was **water** at feature time —
the underwater carver reached one block further in +z than the simulation has it —
so the try failed there.

Three candidate causes were checked and eliminated:

- **the heightmap convention.** `ChunkAccess.getHeight` is
  `getFirstAvailable() - 1`, which would make the decorator draw `nextInt(124)`
  over ocean rather than `nextInt(126)`. But `WorldGenRegion.getHeight` adds the
  one back, so the feature really does see 63. Tested against the 105 real chunks:
  the current convention reproduces 54, the off-by-one reproduces 6;
- **`Mth.sin`/`Mth.cos`.** The game uses a 65536-entry float lookup table, not
  `Math.sin`; this project used `Math`. Now fixed (`rng/Mth`), and it is the right
  thing to do, but it moved the cave carver's precision only from 90.43% to
  90.48% — about one block per 74 chunks — so it is not the explanation;
- **the disks.** Now implemented (`gen/Disk`), because they land on the sea floor
  exactly where the cane feature does its tries and can flip a try's outcome. They
  did not change this chunk.

Also verified against the source and found already correct: the carver
probabilities and registration order (`addOceanCarvers`: AIR cave 0.0667 then
canyon 0.02; LIQUID underwater canyon then underwater cave), the shared carving
mask per step, the start-chunk-outer iteration order, the ore-blob feature index,
and the `nextInt(16), nextInt(16), nextInt(256)` draw order of `COUNT_RANGE`.

**What this means for the search.** A stack needs two placements to land exactly
right, so any single block that differs kills it — a hit is inherently fragile.
The land-chunk validator says about half of all chunks reproduce exactly
(54/105), so **expect roughly half of the hits to be false** and verify every one.
It is not a reason to distrust the geometry: the spot rate still agrees with real
chunks, and this failure was one block at a carver boundary, not a systematic
error.

### A note on why the pocket height is not the problem

It is tempting to explain a missing tall column by the pocket being too short.
It is not that: `ColumnPlacer.place` loops `setBlock` upward with no checks at
all, so a 4-tall pocket still yields a 5-tall cane, with the top block replacing
the ceiling. Only the *base* position is tested.

### Getting the pre-flood state out of a real world

`verify_proto.py` tries to read a chunk at `features` status, which is the only
way to see the geometry the cane feature saw. It does not work near spawn: the
spawn area is always loaded to `full`, and a forceload ticket drags a radius of
chunks up with it. The prior session got its 35,490 proto-chunks by pregenerating
at an origin of 900,000. Since the search box is 32 chunks around the origin,
every hit lands inside the spawn area, so this diagnostic is unavailable for
hits — which is why the trace and the block dumps had to carry the argument.

## 6o. A terrain-free prefilter on the cane RNG: measured, not adopted

Worth knowing about, because it looks like an obvious win and mostly is not.

The cane stream is almost entirely terrain-independent. Every {@code nextInt} is
one LCG step, the offsets are fixed (3 draws per invocation for the origin, then 6
per try), and the only thing the terrain changes is that a *successful* placement
consumes 2 extra draws for the column height. So the whole sequence can be laid
out flat and indexed, and two structural facts make a stack cheap to test for:

- all 20 tries of an invocation share its y, because the y-spread is 0 — so the
  two columns of a stack must come from **different invocations**;
- the upper column must start at exactly `y1 + height1`, and `height1` is two
  known draws — so the later invocation's y is pinned to one value out of 126.

`StackPrefilter` does this in about 4 microseconds a chunk, against roughly 85 for
terrain. Measured over 200,000 chunks:

| variant | accepts |
|---|---|
| assuming no earlier placement | 8.0% |
| union over 0, 1 and 2 earlier placements | 21.6% |

So the RNG really is a 5x filter. It still does not pay off here:

- **in the region design it is worth about 1.1x.** A searched chunk needs its eight
  neighbours surfaced and carved, so accepting 21.6% of chunks scattered at random
  still needs `1 - (1 - 0.216)^9` = 89% of the region's terrain;
- **it would be worth about 2.9x with per-chunk windows**, the design this replaced:
  0.216 x 2.25 columns of work per searched chunk against 1.43 now. That is the
  version to build if the search ever needs to be faster.

One trap found while measuring it. The stack on seed 119658 had an *extra*
placement before its lower column — invocation 0 placed at try 5 and again at try
9 — so the stream was already shifted by 2 draws part-way through that invocation.
A filter that shifts whole invocations uniformly, as this one does, accepts that
chunk only by coincidence. A sound version has to track the shift per draw, which
multiplies the variants and pushes acceptance higher again.

The useful conclusion is what it says about where the rarity lives: the RNG
supplies a stackable pair in one chunk in five, so essentially all of the 7.1e-9
is the terrain having to cooperate at both positions.

## 6p. The second hit, and the validation it forced

Seed 113305583 produced a 5-tall column at 23,35,-19 — 23 blocks from spawn, in a
deep cold ocean. The real world has **no cane at all** in the 3x3 chunks around it,
confirmed server-side and in a client. Worse than the first failure, which at
least reproduced the lower column.

The trace showed it was a **cross-chunk stack**: chunk 1,-2 placed a 2-tall column
at 23,35,-19 (invocation 8, tries 0 and 13), and then chunk 1,-1's own run placed a
column on top of it, reaching 3 blocks into its neighbour. The block dumps then
showed why it cannot happen: the real world keeps **stone at x=19..22, y=33..36**,
so its dirt blob turned that into a dirt shelf, whereas the simulation had already
carved the same volume to water and so had dirt only at x=23. Different soil,
different tries succeed, different stream.

`ProbeColumns` ruled out the noise as the cause — the raw density field is solid
stone through that whole slice in both — which left the carvers, and led to the
validation below.

### Feature index ruled out first

Worth recording as a dead end: the obvious explanation for "my cane exists and the
game's does not" is a wrong feature index, since it salts `setFeatureSeed` and
would make every placement fiction. It is not that. Expanding every
`BiomeDefaultFeatures` helper in call order for all eight searched ocean biomes
gives an identical VEGETAL_DECORATION list — water trees, flowers, grass, two
mushrooms, then sugar cane — so the index is **5 for every one of them**, as
`BiomeCaneConfig` has it.

## 6q. Validating against pre-flood chunks: `ProtoValidator`

Every earlier check of the carvers compared against `full` chunks, where the
underwater carver's scheduled fluid ticks have already flooded the caves. Carved
air legitimately reads as water there, which is why `CarverValidator` tops out
around 90% and why that number cannot distinguish a good carver from a bad one.

`export_proto.py` + `ProtoValidator` fix that by comparing against chunks saved at
`features` status: noise, surface, both carving steps and the chunk's own
decoration have run, and nothing since. That is exactly the world the cane feature
saw. The pregenerated `srv2` world has 774 such chunks in only 25 region files.

Result over 281 ocean proto chunks, scoring each chunk's interior only (a
neighbour's blobs and disks reach about six blocks in, and in a proto chunk the
neighbours may be undecorated):

| quantity | value |
|---|---|
| exact block-category match | **98.68%** of 314,720 cells |
| simulated air that is really solid | 14 / 38,993 = **0.036%** |
| simulated water that is really solid | 63 / 89,650 = **0.070%** |
| simulated soil that is really not | 202 / 4,938 = **4.09%** |
| real air simulated as solid (loses finds) | 250 |
| real soil simulated as something else | 18 |

**So the carvers are right to about 0.05%, not 10%.** The earlier inference that
they over-carve by 10% was the flooding confound all over again — the same trap
section 5 warns about, walked into from a different direction.

The real weak spot is **soil at 4%**, which is the dirt blobs, and that is the one
thing both false hits turned on.

### The bug it found: OreFeature endpoints are float arithmetic

`OreFeature.place` computes the blob's endpoints as
`(float)blockPos.getX() + Mth.sin(f) * f2` — the whole expression in **float**,
widened to double only on assignment. This project did the addition in double.

Near the origin that is exact, which is why nothing caught it. At the proto
chunks' x of about 3 million a float step is **0.25 blocks**, so the entire blob
shifts. Fixing it dropped missed soil from 51 cells to 18 and false soil from
4.74% to 4.09%.

The lesson generalises: worldgen mixes float and double deliberately, and a
transcription that "simplifies" to double is exact near spawn and wrong far from
it. Anything validated only near the origin has not been validated for this.

### What is left, and what it means for the search

- **4% of simulated soil is not soil in the real world.** Since the deep spots sit
  on blob dirt (mean soil y 23), this is the dominant reason a hit can be false.
- **The air/water boundary is off by a block sometimes** (0.036% and 0.070% above,
  plus 131 cells of simulated air that is really water). That is enough to break a
  spot, and it also costs recall: at the one real stackable spot in the sample —
  2999957,14,3011194 on seed 1 — the simulation has the air and the dirt right but
  puts the water one block further away, so it would never have reported it.
- `ORE_GRAVEL` (count 8, size 33, index 1) is **not** implemented, which explains
  9,324 cells of simulated stone that is really gravel. It does not affect the
  search: gravel blobs run after the carvers and replace stone, which is not cane
  soil either way.

Expect roughly half of hits to be false, matching the 54/105 of section 6b, and
verify every one.

## 6r. THE BUG: the underwater carver writes its water through a moved cursor

Eight consecutive hits were reported, verified against a real server, and every
one of them was false. The cause was one line of vanilla that this project
transcribed as if it did the obvious thing.

`UnderwaterCaveWorldCarver.carveBlock`:

```java
for (Direction direction : Direction.Plane.HORIZONTAL) {
    int n10 = n4 + direction.getStepX();
    int n11 = n5 + direction.getStepZ();
    if (n10 >> 4 == n2 && n11 >> 4 == n3
        && !chunkAccess.getBlockState(mutableBlockPos.set(n10, n7, n11)).isAir()) continue;
    chunkAccess.setBlockState(mutableBlockPos, WATER.createLegacyBlock(), false);
    ...
    bl = true;
    break;
}
mutableBlockPos.set(n4, n7, n5);
if (!bl) { chunkAccess.setBlockState(mutableBlockPos, WATER.createLegacyBlock(), false); }
```

The air test is written as `getBlockState(cursor.set(nx, y, nz))`, so testing an
in-chunk neighbour **moves the cursor onto that neighbour** — and the very next
line writes the water through the same cursor. So:

| neighbour | where the water actually goes |
|---|---|
| outside the chunk | the carved block (the `set` was short-circuited away) |
| in-chunk and air | **the neighbouring air block**, and the carved block is left alone |
| all in-chunk and non-air | the carved block |

The middle case is the one that matters: an underwater tunnel touching an air cave
floods **the cave**, one block in, rather than itself. This project wrote the water
at the carved block in every case, which left dry air exactly where the game has
water — and the air/water boundary is where every piece of sugar cane geometry
lives.

Measured against 4,741 pre-flood ocean chunks:

| | before | after |
|---|---|---|
| simulated water that is really solid | 1,086 | **397** |
| **stackable spot precision** | **25%** | **67%** |
| stackable spots per chunk (the search rate) | 2.1e-3 | 1.1e-3 |

So three quarters of the spots this project used to report did not exist, the
search rate was correspondingly inflated, and every hit built on one was
guaranteed to fail. The rate is now half what it was and the hits are real.

**The general lesson.** Decompiled code that reuses a mutable cursor is a trap:
`pos.set(...)` inside a condition is an argument *and* a side effect. Anywhere a
`MutableBlockPos` appears in a test, check what it points at by the time the next
write happens.

## 6s2. Three more accuracy fixes, and what the error budget looks like now

With the cursor bug fixed, `ProtoValidator` was pointed at the remaining error and
found three things. Restricting the comparison to y >= 8 matters throughout: below
that is the bedrock layer, which this project does not simulate, so its blobs fill
in where the game has bedrock. That artefact alone accounted for four fifths of
the apparent soil error and none of it can carry sugar cane.

| error class (y >= 8, 4,741 ocean proto chunks) | rate |
|---|---|
| simulated air that is really solid | 0.026% |
| simulated water that is really solid | 0.024% |
| simulated soil that is really not | **0.59%** (was 4.59% including bedrock) |
| real air simulated as solid | 10,342 cells |

### Sandstone is not natural stone

`OreConfiguration.Predicates.NATURAL_STONE` is only stone, granite, diorite and
andesite. The reduced palette folded sandstone into SOLID, so dirt blobs replaced
it — which the game never does. It matters on warm and lukewarm ocean floors,
where the sand band turns to sandstone underneath. Fixed with a distinct
`Blocks.SANDSTONE`; worth only a handful of cells here, but it is the kind of
error that is invisible until it is not.

### The missing air is mineshafts

Dumping the cells where the game has air and this project has solid shows the
shape immediately: three-tall corridors with regular vertical supports and
unmapped blocks (planks, fences, rails) in them. Mineshafts generate at
UNDERGROUND_STRUCTURES, step 3 — before the ore blobs and long before the cane —
and their air is written with `setBlock`, so it never floods and never appears in
any carver.

They cut both ways. The spots they create are invisible to this project (recall),
and worse, a mineshaft inside a searched chunk flips cane tries and desynchronises
the chunk's whole RNG stream (precision), because a successful placement consumes
two extra draws.

Implementing them is a large job. Skipping them is not: spacing is 1 and
separation 0, so a chunk starts a mineshaft iff
`setLargeFeatureSeed(seed, cx, cz); nextDouble() < 0.004`, which is 49 cheap draws
for a radius of 3. Measured:

| exclusion radius | chunks skipped | missing air removed |
|---|---|---|
| 2 | 10.4% | 64.2% |
| **3** | **19.0%** | **81.0%** |
| 5 | 38.1% | 91.7% |

The searcher now skips any chunk within 3 of a mineshaft start. It costs 17% of
the spot rate and removes four fifths of the terrain error that was silently
invalidating hits.

## 6s. What is still missing, in order of value

Recall is 50-75% — the simulation finds two thirds of the real spots — and there
are 10,599 cells in the sample where the game has air and this project has solid.
Candidates, best first:

- **Lakes.** Section 5 dismissed them as a source of a vertical water face, which
  is right, but overlooked the plain case: `LakeFeature` runs at step 1, *before*
  the ore blobs, and places air above water sealed inside rock, with no fluid
  ticks, so it never floods. A dirt blob at step 6 replacing stone at the lake rim
  then gives soil, with lake water beside it and lake air above — a spot. Roughly
  one chunk in four attempts a water lake.
- **Mineshafts** are now known to be the dominant missing air (section 6s2) and
  are skipped rather than simulated. Implementing them would recover both the
  skipped 19% of chunks and the spots they create, which are permanently dry and
  therefore the most robust kind.
- **Dungeons** (8 attempts per chunk, same step) are still unsimulated, though
  their cobblestone shell means the air rarely borders soil.
- The 1.2% of ocean columns where the real terrain is higher than the noise
  generator says, still unexplained from section 6d.

## 6t. Seed reversal and the upper 16 bits: verified, and why it does not help

A suggestion worth recording, along with the measurements that settle it: reverse
the carvers for the water ravine and the air cave, then roll the upper 16 bits of
the seed for the biomes and the cane.

### The premise is exactly right

Every worldgen RNG goes through `Random.setSeed`, which masks to 48 bits.
`setDecorationSeed` does XOR in the full 64-bit seed —
`(x * a + z * b) ^ levelSeed` — but feeds the result straight back through
`setSeed`, so the top 16 bits are masked off again. Biomes take another route
entirely: layer salts mix the full seed and the Voronoi uses
`WorldSeed.toHash(worldSeed)`.

`SeedBitsProbe` shows it directly. Holding the low 48 bits and varying the upper
16:

```
upper  decoration seed(0,0)   first cane draw   cave@0,0  biome@0,0
0      1500050556             (12, 2,119)       false     45
1      281476476761212        (12, 2,119)       false     13
2      562951453471868        (12, 2,119)       false      3
```

Identical draws, different biome. So **carvers, terrain noise and all decoration
are properties of the low 48 bits alone**, and the upper 16 are a free 65,536-way
re-roll of the biome map. Chunk (0,0) is the easy case to see it in: both
`setDecorationSeed` and `setLargeFeatureSeed` collapse to the level seed there,
because the chunk coordinates they multiply in are zero.

### Why it still does not beat brute force here

- Biomes feed `getDepthAndScale`, so the noise terrain changes with the roll. The
  only reusable work across variants is the noise samplers, and those are already
  amortised — radius 6 and radius 32 measure the same throughput, so per-seed
  setup is not a cost worth attacking.
- The expensive part cannot be localised to the pair's position. Whether each of
  the ~200 cane tries succeeds shifts the stream for every later one, so the whole
  chunk's geometry is needed, which means the whole 3x3 neighbourhood.
- Any single-chunk-per-seed scheme therefore pays **9 chunk-generations per
  trial**, where the region design amortises to **1.58**. That is a 6x handicap
  against a 4.6x gain from the cane prefilter.

### The idea it did suggest, and its measurement

Ocean biomes that share depth and scale produce *identical* terrain and register
identical carvers — ocean and lukewarm_ocean are both (-1.0, 0.1), deep_ocean and
deep_lukewarm both (-1.8, 0.1). They differ only in the surface configuration, and
that decides the deep floor: `CONFIG_GRASS` gives gravel, which cane cannot stand
on, while `OCEAN_SAND` and `FULL_SAND` give sand, which it can. So the same cave
should be worth more in a lukewarm ocean.

Measured over 320,000 ocean chunks:

| biome | floor | stackable spots/chunk |
|---|---|---|
| lukewarm_ocean | sand | 1.25e-3 |
| cold_ocean | gravel | 9.16e-4 |
| ocean | gravel | 8.81e-4 |
| deep_ocean | gravel | 8.30e-4 |
| warm_ocean | sand | 7.56e-4 |
| deep_lukewarm | sand | 6.39e-4 |
| deep_cold | gravel | 5.09e-4 |

It does not hold up, and the same run says why: **97% of stackable spots are
inside the terrain rather than on the sea floor surface** (267 of 274). Deep spots
stand on ore-blob dirt, which is biome-independent, so the floor material only
decides the other 3%. Lukewarm's 1.4x is barely outside its own error bar and the
other two sand biomes go the other way.

## 6u. Seed-only prefilters, built and measured: 0.70x

Section 6t argued the reversal idea down on cost estimates. Those estimates were
wrong, so both filters were built and benchmarked (`PrefilterBench`,
`GeometryPrefilter`). The answer is worse than the estimate, not better.

```
1013 ocean chunks
  cane RNG pair   : 19.05%   23.2 us/chunk
  carver envelope : 44.92%   68.5 us/chunk
  both            :  9.38%   91.7 us/chunk total

accepts 9.4%, 3x3 neighbourhoods cover 58.8%
projected: 91.7 filter + 64.7 terrain = 156 us against 110 us now  ->  0.70x
```

Three findings, two of them structural:

**The cane filter is unsound as written, and the confirmed find proves it.**
Testing a filter against the one verified result is what caught this; the
aggregate acceptance rate of 19% looked perfectly healthy. Enumerating the shift
pairs that accept seed 1500050556 chunk 5,4:

```
base shift 0, top shift 4  -> ACCEPTED
base shift 0, top shift 8  -> ACCEPTED
```

which matches its trace exactly: the lower column was the chunk's first success
(shift 0), try 8 then placed an unrelated column at 90,16,65, and the upper column
came at invocation 4 with **two** placements already absorbed (shift 4).
`StackPrefilter` only tests *tied* pairs — (0,2), (2,4), (4,6) — so (0,4) is not in
its search space at all. That is a modelling error, not a tuning one.

Making it sound means enumerating (baseShift, topShift) independently, since any
number of unrelated placements can fall between the two columns: about ten
combinations instead of three, so roughly ten times the cost **and** a union over
ten variants, pushing acceptance well above 19%. Both terms move the wrong way
from an already-losing 0.70x.

**A terrain-free carver walk costs more than the real one.** The carvers are about
6 us/chunk in the search, because `hasWater` aborts spheres and
`canReplaceBlock` fails fast. Strip the terrain out and nothing aborts, so the
walk does more work, not less: 68.5 us. The guard that makes the geometry rare is
the same guard that makes the real carve cheap, so there is no cheap preview of it.

**The neighbourhood dilation caps the idea regardless.** A searched chunk needs its
eight neighbours generated. At 9.4% acceptance the union of 3x3 neighbourhoods is
58.8% of chunks, so even a **free** filter would only reach 1.7x.

The envelope test also turned out far looser than the block-level version of the
same question: 44.9% against the 12.4% measured in section 6t by testing actual
carved blocks rather than sphere bounding boxes.

**Conclusion.** The rarity is not in the seed. Two seed-determined layers — the
cane RNG pair and the carver envelope — together admit ~9% of chunks, while 0.09%
actually hold the geometry. The missing factor of a hundred is the noise density
field deciding whether a sphere carves anything, and that is a different generator
which the carver seeds say nothing about.

## 6v. Cross-chunk columns are real, and still worthless near spawn

Seed 4505722117 reported a 5-tall at 20,15,64 and came back 3 tall in game. The
trace showed chunk 1,4 placing only the top two blocks, at y=18 and 19; the three
below came from chunk **1,3** reaching over the border, since a placement lands
within ±4 of an origin drawn inside its own chunk and z=64 is the first block of
chunk 4. `canSurvive` returns true the moment the block below is cane, so 1,4's
placement at y=18 succeeds if and only if 1,3 has already run.

Forced the question with a real server. Move `SpawnX/SpawnZ` far away, delete the
six chunks around the target so they regenerate, then forceload in stages:

| stages | column at 20,15,64 |
|---|---|
| chunk 1,3 alone, then 1,4 | **5 tall, y=15..19** |
| chunk 1,4 alone, then 1,3 | 3 tall |
| both as one forceload box | 5 tall |

So the column is genuine and the simulator's raster order was right. But in an
untouched world it is unreachable: spawn for this seed is x=-4 z=236, chunk
-1,14, and the server pregenerates a radius of 11 chunks around it. The target at
chunk 1,4 is exactly 10 away, so **both chunks are decorated during "Preparing
spawn area"**, before a player exists. The spiral radiates outward from spawn and
reaches 1,4 (distance 10) before 1,3 (distance 11) — it approaches from the south,
which is the losing direction. Four different forceload strategies afterwards all
returned 3, because by then the chunks were already `full`.

This is why the searcher now scores only the run one chunk built by itself rather
than labelling border columns and letting them through. We search at chunk radius
6, so hits sit near 0,0 and usually inside the spawn pregeneration, where the
decoration order is fixed by the spawn spiral and no load pattern can change it.
A cross-chunk column there is not a risky hit — it is an unavailable one.

## 7. Watch out for

- `nextInt(1)` is called twice per try (yspread is 0). It always returns 0 but
  still advances the LCG.
- `ColumnPlacer` writes upward **unconditionally**, overwriting whatever is there.
- The feature index passed to `setFeatureSeed` counts structures placed in the
  same step before the features.
- 1.16.1 predates the 1.16.2 worldgen datapack refactor: the decorator classes
  differ, so 1.16.2 JSON is only a guide, not ground truth.
- `Mth.sin` and `Mth.cos` are float lookup tables, not `Math.sin`/`Math.cos`, and
  the carvers use them in float arithmetic before widening to double. See
  `rng/Mth`.
- `ChunkAccess.getHeight` returns `getFirstAvailable() - 1`, but
  `WorldGenRegion.getHeight` adds one back. During worldgen a feature therefore
  sees the raw heightmap value.
- Heightmaps: `OCEAN_FLOOR_WG` counts only blocks that block motion, so water does
  not count; `MOTION_BLOCKING` does count fluids, which is why it is 63 over open
  ocean. The cane decorator uses MOTION_BLOCKING, the disks use OCEAN_FLOOR_WG.
- `DiskReplaceFeature` tests for water at the position *before* drawing its
  radius, so on land it consumes nothing from the stream.
- `OreFeature.place` computes its endpoints in float and widens afterwards. Exact
  near spawn, wrong by up to a quarter of a block at x of a few million. Assume
  every other transcription has the same class of bug until checked far from the
  origin.
- `OreFeature`'s reachability gate is
  `if (minY > getHeight(OCEAN_FLOOR_WG, x, z)) continue`, and that getHeight is the
  WorldGenRegion one, so it is the firstAvailable value. Getting the gate wrong
  desynchronises every later blob in the chunk, because doPlace draws `size`
  doubles.
- The low 48 bits of the world seed decide carvers, terrain noise and all
  decoration; the upper 16 decide only biomes. See section 6t.
- A `MutableBlockPos` used inside a condition is a side effect as well as an
  argument. See section 6r, which cost eight false hits.
- Never measure carver fidelity against `full` chunks. Use `features`-status
  chunks; `CarverValidator` cannot exceed about 90% for reasons that have nothing
  to do with correctness, while `ProtoValidator` reads 99.95%.
