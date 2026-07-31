# v1.0 — a sugar cane column taller than worldgen allows

Minecraft caps sugar cane at 3 by growth and 4 by worldgen. This finds taller ones
in 1.16.1, by reimplementing the slice of worldgen that decides where cane goes and
running it billions of chunks at a time.

## The find

**Seed `1500050556`, five tall at `91 16 65`.**

Verified on a real 1.16.1 server and in game. It stands on dirt at y=16..20, deep
underwater in a flooded cave in a deep lukewarm ocean, about 112 blocks from spawn —
swim or dig down. A three-tall column sits beside it at `90 16 65`.

It formed the only way anything over 4 can:

```
invocation 1: origin 91,64  y=16  try 6   PLACED 91,16,65 height 3  -> y=16,17,18
invocation 4: origin 91,68  y=19  try 15  PLACED 91,19,65 height 2  -> y=19,20
```

Two independent invocations of the same chunk's cane feature. The first built a
3-tall column; a later one drew y=19 — exactly its top — and stacked 2 more on it.

## Getting it

Download `sugarcane.jar` below (3.5 MB, all dependencies included) and run it with
**Java 21 or newer**:

```
java -jar sugarcane.jar                                 # lists all 12 commands
java -jar sugarcane.jar inspect 1500050556 91 16 65 6   # the find, with its trace
java -jar sugarcane.jar search 1 1000000 6 24 5         # go looking
```

`search <firstSeed> <seeds> <chunkRadius> <threads> <minHeight>`. The radius bounds
how far from spawn a find may be, in chunks, and is nearly free — 6 runs as fast as
32. On 24 cores it sustains about 15,000 chunks/s.

Or build it yourself with `mvn package`, which needs a JDK and Maven and nothing
else.

## What to expect

Roughly **one hit per 3 hours** on 24 cores, about half of them 6 or taller. That is
a projection from the measured geometry and RNG rates rather than a long-run
observation, so treat it as an order of magnitude.

A hit is a candidate, not a result. The searcher is a reimplementation, so verify
before you go looking:

```
python tools/verify.py path/to/minecraft_server.1.16.1.jar <seed> <x> <y> <z>
```

You supply the server jar; it is not redistributable.

## How it is possible at all

`COUNT_HEIGHTMAP_DOUBLE` runs the cane feature 10 independent times per chunk, and
each draws `y = nextInt(2 × heightmap)` — so placements land anywhere in the column,
not on the surface. The decorator's stream is lazy, so a later invocation sees what
an earlier one built, and `canSurvive` returns true immediately when the block below
is cane.

What the terrain has to supply is water beside the soil **and** water beside the top
of the first column — two heights, 2 to 4 apart. In 1.16 the only generator that
leaves water at two separated heights beside a spot cane can start from is the pair
of underwater carvers, cutting a water face against an air cave. That is why finds
are always in oceans and always deep.

About **1.3e-3** of ocean chunks hold usable geometry, and about **5e-6** of
decoration seeds exploit a given one: roughly **1 in 200 million ocean chunks**.

## Accuracy

Checked against chunks a real 1.16.1 server generated, saved at `features` status —
decorated but not yet flooded, which is the state the cane feature actually saw.
Over 4,741 real pre-flood ocean chunks: 98.9% of block categories match, 0.026% of
simulated air is really solid, 0.024% of simulated water is really solid.

**Known gaps.** Mineshafts are not simulated, so chunks within 3 of a mineshaft start
are skipped rather than searched wrongly. Lakes, dungeons and structures are missing
entirely. Frozen oceans, badlands and swamps are skipped because their surface
builders are not implemented. All of these can only lose finds, not invent them.

## This does not work in 1.18+

The placement became `rarity_filter(6) → in_square → heightmap` with `y_spread: 0`:
one placement per chunk, pinned to the surface. Nothing can stack on anything. Read
off the shipped 1.20.1 data files.

## Read the findings

`FINDINGS.md` is the real documentation — every mechanic read off the decompiled
1.16.1 server, every measurement, and every wrong turn, including the cursor bug
that produced eight false hits before the first real one, and the ravine springs
that would make this easy if they were registered two lines earlier.

## Credits

Biome and terrain generation reuse [KaptainWutax](https://github.com/KaptainWutax)'s
BiomeUtils and TerrainUtils. Everything downstream — surface builder, carvers, ore
blobs, disks, the cane feature — is transcribed from the 1.16.1 server decompiled
with official Mojang mappings.
