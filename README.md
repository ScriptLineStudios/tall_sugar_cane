# Sugar cane taller than 4

Minecraft caps sugar cane at 3 by growth and 4 by worldgen. This project searches
for **naturally generated columns of 5 or more** in Minecraft 1.16.1, by
reimplementing the relevant slice of worldgen — biomes, noise terrain, the surface
builder, cave and canyon carvers, ore blobs, disks and `patch_sugar_cane` — and
running it a few billion chunks at a time.

## It found one

**Seed `1500050556`, five tall at `91 16 65`, Minecraft 1.16.1.**

Verified on a real server and in-game. It stands on dirt at y=16..20, deep
underwater in a flooded cave in a deep lukewarm ocean about 112 blocks from spawn,
so swim or dig down to it. A three-tall column sits beside it at `90 16 65`.

It formed the only way anything over 4 can:

```
invocation 1: origin 91,64  y=16    try 6  PLACED 91,16,65 height 3   -> y=16,17,18
invocation 4: origin 91,68  y=19    try 15 PLACED 91,19,65 height 2   -> y=19,20
```

Two independent invocations of the same chunk's cane feature. The first built a
3-tall column; a later one drew y=19 — exactly its top — and stacked 2 more on it.

## Quick start

Needs Java 21+. Either grab a release jar or build it:

```
mvn package
java -jar target/sugarcane.jar
```

That prints the commands. The two worth knowing:

```
# search seeds 1.. within 96 blocks of spawn, 24 threads, report height >= 5
java -jar target/sugarcane.jar search 1 1000000 6 24 5

# look at the confirmed find and how it was built
java -jar target/sugarcane.jar inspect 1500050556 91 16 65 6
```

A find prints as:

```
HIT seed 1500050556  x=91 y=16 z=65  height 5  biome 48  chunk 5,4  margin (order-dependent)
```

`interior` hits are self-contained; `margin` hits can depend on the order
neighbouring chunks were decorated in, so they are likelier to fail verification.

### Search arguments

`search <firstSeed> <seeds> <chunkRadius> <threads> <minHeight> [mode]`

- **chunkRadius** bounds how far from spawn a find may be, in chunks. This is
  nearly free: 6 (±96 blocks) runs as fast as 32 (±512). It gets expensive below
  about 2 — see FINDINGS 5c for the measured table.
- **mode** — `diag` counts geometry, `probe:N` measures the hit probability,
  `spots` prints the coordinates of the rare terrain the search hunts for.

Expect roughly **one hit per 4 hours** on 24 cores, of which about half are 6 or
taller and most should survive verification.

## Verifying a hit

The searcher is a reimplementation, so a hit is a candidate until the real game
agrees. `tools/verify.py` builds a throwaway world on the seed, generates the
chunk with enough neighbours for it to be decorated, and reads the region file
back:

```
python tools/verify.py path/to/minecraft_server.1.16.1.jar 1500050556 91 16 65
python tools/verify.py path/to/server.jar 1500050556 91 16 65 --blocks   # when it fails
```

You supply the server jar — it is not redistributable. Any vanilla 1.16.1 server
works, and Java must be on PATH.

The cane survives the flooding that fills its cave when the chunk loads
(`FlowingFluid.canHoldFluid` refuses to spread into sugar cane), which is why a
column generated in a dry pocket is still there underwater.

## How it works, briefly

Growth stops at 3 and `ColumnPlacer` stops at 4, so 5+ needs **two placements on
the same block**. In 1.16.1 that is possible because `COUNT_HEIGHTMAP_DOUBLE` runs
the feature 10 independent times per chunk, each drawing
`y = nextInt(2 × heightmap)` — so placements land anywhere in the column, not on
the surface — and the decorator's stream is lazy, so a later invocation sees what
an earlier one built. `canSurvive` returns true immediately when the block below
is cane, so the second column needs no soil.

What it needs from the terrain is a **water face beside an air pocket, with soil
under it**, and water beside the column at the height where the second placement
starts too. In practice that means an air cave cut against an underwater carver's
water, with dirt from an ore blob beneath — which is why the search only looks at
ocean biomes, and why finds sit deep (mean soil y ≈ 23).

Measured rates: about **1.3e-3** of ocean chunks hold usable geometry, and about
**5e-6** of decoration seeds exploit a given one, so roughly **1 in 200 million
ocean chunks**.

**This does not work in 1.18+.** The placement became
`rarity_filter(6) → in_square → heightmap` with `y_spread: 0`: one placement per
chunk, pinned to the surface, so nothing can stack on anything. Read off the
shipped 1.20.1 data files. See FINDINGS 6t.

## Accuracy

The simulation is checked against chunks a real 1.16.1 server generated, saved at
`features` status — decorated but not yet flooded, which is the state the cane
feature actually saw. Full chunks are useless for this: the underwater carver's
scheduled fluid ticks turn carved air into water on load, which masks exactly the
errors that matter.

Over 4,741 real pre-flood ocean chunks, above the bedrock layer:

| | |
|---|---|
| block categories matching | 98.9% |
| simulated air that is really solid | 0.026% |
| simulated water that is really solid | 0.024% |
| simulated soil that is really not | 0.59% |

Known gaps, all documented in FINDINGS: mineshafts are not simulated (chunks
within 3 of a mineshaft start are skipped rather than searched wrongly), and
lakes, dungeons and structures are missing entirely.

## Layout

```
src/main/java/dev/drakou111/sugarcane/
  Cli.java              every entry point
  RegionSearcher.java   the search
  Inspect.java          dump one position, with the placement trace
  gen/                  worldgen: surface builder, carvers, ore blobs, disks, the cane feature
  rng/                  java.util.Random and Mth, bit-exact
  world/                block palette and the chunk array
  validate/             comparisons against real generated chunks
tools/                  python: verification and pre-flood chunk export
FINDINGS.md             how all of it was worked out, and everything that went wrong
```

`FINDINGS.md` is the real documentation — mechanics read off the decompiled 1.16.1
server, every measurement, and the bugs that produced eight false hits before the
first real one.

## Credits

Biome and terrain generation reuse [KaptainWutax](https://github.com/KaptainWutax)'s
BiomeUtils and TerrainUtils rather than reimplementing the layer stack. Everything
downstream — surface builder, carvers, ore blobs, disks, the cane feature — is
transcribed here from the 1.16.1 server decompiled with official Mojang mappings.
