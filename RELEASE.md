# v1.1.0 — search where the player actually spawns

A new world does not drop you at 0,0. It drops you at the spawn point, which over
300 seeds averages **196 blocks** from the origin — so a search centred on 0,0 finds
columns near a place nobody starts.

```
java -jar sugarcane.jar search 1 1000000 6 24 5 --spawn
```

`--spawn` centres each seed's box on that world's **spawn chunk** instead. Off by
default. A find inside the box is then a find you can walk to the moment you load
in.

There is also a `spawn` command for looking one up:

```
$ java -jar sugarcane.jar spawn 1500050556
seed 1500050556  spawn chunk 1,7  (block 24, 120)
```

## What it costs

Measured interleaved, 6000 seeds each way:

| | chunks searched | chunks/s |
|---|---|---|
| centred on 0,0 | 189,870 | ~10,520 |
| `--spawn` | 118,547 | ~6,453 |

**Seeds per second does not change** — the same 18.4 seconds either way. Reproducing
`setInitialSpawn` costs about 14 ms per seed, and that is almost exactly repaid by
there being less ocean to generate near a land spawn. What you pay is coverage: 62%
of the chunks, so roughly **1.6x the time to a find**.

Whether that is worth it depends on what you want. 196 blocks is a short walk, so
origin-centred finds are usually reachable anyway. `--spawn` earns its cost when you
want to hand someone a seed where the cane is right there.

## Correctness

`findBiomeHorizontal` looks like a widening spiral and is not: with `findClosest`
false the outer loop starts at the full radius, so it runs **once**, sweeping a
single 129x129 square of quart cells and reservoir-sampling matches with
`random.nextInt(count + 1)`. The z-outer, x-inner nesting matters, because the draw
consumes RNG and any other order picks a different winner.

Checked against `level.dat` from five worlds a real 1.16.1 server generated — seeds
4531414558, 2585605, 4534752689, 4532846955 and 4505722117 — and it matches the
spawn chunk in **all five**.

## A second confirmed find

**Seed `4534752689`, five tall at `-36 14 63`.** Verified on a real server, and the
only cane in the surrounding 3x3 chunks, so there is nothing to mistake it for. 73
blocks from the origin, in an ordinary ocean, y=14 — dig or swim down.

```
invocation 5: origin -37,63  y=14  try 7  PLACED -36,14,63 height 3  -> y=14,15,16
invocation 6: origin -36,61  y=17  try 1  PLACED -36,17,63 height 2  -> y=17,18
```

Consecutive invocations of the same chunk: one built 3, the next drew y=17 — exactly
its top — and stacked 2 more.

## Expect about one hit in three to be real

This release adds the measurement that explains it. Every placement is gated on
`isEmptyBlock`, so simulated **air where the game has water** invents a legal spot
from nothing, and one wrong cell desynchronises a chunk's whole placement stream.
The accuracy table never measured that particular confusion. Now it does:

```
simulated AIR that is really WATER   : 0.0205% of air cells
simulated WATER that is really AIR   : 0.0078% of water cells
```

0.02% is not harmless. A chunk holds ~2,200 simulated-air cells, so about 0.46 of
them are wrong, and roughly a third of chunks carry at least one. Verifying every
hit found so far gives **2 of 6** — one lost only its upper column, two produced no
cane at all.

So: **a `HIT` line is a candidate, not a result.** Verify before you travel.

```
python tools/verify.py path/to/minecraft_server.1.16.1.jar <seed> <x> <y> <z>
```

Run `validate-proto` against a pre-flood export to see the numbers for yourself.

## Upgrading

Drop in the new `sugarcane.jar`. Nothing changes unless you pass `--spawn`; search
performance is unchanged from v1.0.2, which was already 1.75x v1.0.
