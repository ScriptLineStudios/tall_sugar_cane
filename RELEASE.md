# v1.0.1 — fewer false hits, working verification, 1.18x faster

A point release. The find, the mechanism and the interface are unchanged — see
[v1.0](../../releases/tag/v1.0) for what this is. Two of the fixes below change
results, so this replaces v1.0 rather than merely speeding it up.

## Fixed: hits that could not exist

v1.0 reported a column whenever the simulated world ended up with one, even when
two neighbouring chunks had cooperated to build it — one chunk laying the base and
the other stacking on top. Such a column only exists if those chunks decorate in
one particular order, and **that order is not a property of the seed**. It depends
on how the world was loaded, and a pregenerated world and a forceload around the
target do not agree.

This is not theoretical. Seed `4505722117` was reported by v1.0 as five tall at
`20 15 64` and comes back **three tall** in game. Chunk 1,4 placed only the top
two blocks; the three below came from chunk 1,3 reaching over the border. Forcing
the two orders on a real server gives five tall one way and three the other — and
the spot sits inside the spawn pregeneration area, where the order is fixed by the
server before any player exists.

The searcher now tracks which chunk placed each cane block and reports only the run
**one chunk built by itself**. Cross-chunk columns print as `cross-chunk` and are
not hits. The confirmed find at `1500050556` is unaffected — it was always
self-contained.

If you were running v1.0, some of your hits were unverifiable. Re-check them with
`inspect`.

## Fixed: `verify.py` reported every world as empty

`out[name()] = payload(tt)` reads correctly and behaves wrongly: Python evaluates
the right-hand side of an assignment before the subscript, so every NBT compound
consumed its payload bytes as the tag name and the parse desynchronised
immediately. `read_chunk` swallowed the resulting errors and returned `None`, so
verification reported no chunk at all, for every input.

The bug arrived when the tools were split out to be standalone for v1.0, so the
verification path in that release never worked. Earlier verifications in this
project ran against the original script and were not affected.

## 1.18x faster

12,633 → 14,934 chunks/s, 24 threads, measured on an idle machine over 40-second
runs. Output is identical throughout — same chunks generated, same chunks searched,
same 666 cane columns.

| | chunks/s |
|---|---|
| v1.0 | 12,633 |
| raw biome ids, flat noise caches | 13,219 |
| biome layer cache 1024 → 4096 | 14,171 |
| hoisted sampling constants and octave samplers | 14,934 |

Biome lookups were 20% of the search. Most of that was not the lookups themselves
but the wrapper: `getBiome` and `getBiomeForNoiseGen` both end in
`Biomes.REGISTRY.get(Integer.valueOf(id))`, and every caller here took `.getId()`
straight back off the result. The layers underneath are public and return the id
directly. The two internal memo caches became flat arrays over the region instead
of `HashMap<Long, …>`.

Enlarging the library's per-layer cache is worth 1.07x at 4096 entries — and
**65536 entries is half the speed of stock**, because forty layers times 24 workers
puts those tables in competition with the noise data for L2.

## New: `diag-all`

Counts the stackable geometry without the ocean restriction, and counts it the way
the game actually asks. The old diagnostic required a connected water face, which is
stricter than the rule `RandomPatchFeature` applies — what is really needed is water
beside two heights, 2 to 4 apart, not necessarily joined.

The answer did not move: 37,000 land chunks, zero stackable spots either way,
against ten times more *legal* spots per chunk than the ocean has. Every shoreline
can start a column; none can supply the second water block.

## Documentation

`FINDINGS.md` gains five sections, all with the measurements behind them:

- **6v** — the cross-chunk load-order experiment on a real server
- **6w** — where the time goes, and why seed reversal cannot help: the rare
  condition is the density field, and the reversible one admits 19% of seeds
- **6x** — the relaxed water rule, tested on land
- **6y** — ravine springs. They generate exactly the isolated water source this
  needs, 50 attempts per chunk, and are dead only because
  `addDefaultExtraVegetation` is called one line before `addDefaultSprings`
- **6z** — what the projected 1.7x actually delivered, and the three places the
  projection was wrong

## Upgrading

Drop in the new `sugarcane.jar`. No interface changes, no new requirements —
still Java 21+.
