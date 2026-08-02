# v1.2.0 — share your finds, and know how far away they are

Two people running this search separately find two disjoint sets of seeds and
neither knows about the other's. This release adds a shared spreadsheet, so a hit
on your machine shows up on everyone's. It also stops a `HIT` line from being a
bare coordinate: every result now says where that world's spawn is and how far the
cane is from it.

## Reporting finds to a shared sheet

On startup the jar now asks once:

```
Do you want to report your finds to the spreadsheet, which you can open by doing java -jar sugarcane.jar -s? (y/n):
```

Answer `y` and it asks for a username, saved to `config.properties` next to the jar
so it only asks the once. Anything other than yes is a no, and nothing leaves your
machine. Reporting is **off unless you opt in**, every run.

A find then POSTs the seed, coordinates, biome, chunk, height, spawn position and
distance to a Google Apps Script endpoint that appends a row to the sheet. Only
columns of 5 or more are sent.

Open the sheet at any time:

```
java -jar sugarcane.jar -s
```

Thanks to **chunkberries** for the reporting client, the prompt and the `-s` flag
(PRs #1 and #2).

## Results say where spawn is

```
HIT seed 1500050556  x=91 y=16 z=65  height 5  biome 48  chunk 5,4  spawn ~24,120 (~87 blocks away)
```

`cross-chunk` lines carry it too. The point is triage: a hit 87 blocks from spawn is
a swim, a hit 4,000 blocks away is an evening, and until now you had to run the
`spawn` command by hand to tell the difference.

The position is the centre of that world's **spawn chunk**, not the spawn block —
vanilla's `PlayerRespawnLogic` picks the exact block from real terrain, which this
does not generate. So both numbers are good to about ±8 blocks, hence the `~`.

This is free. Reproducing `setInitialSpawn` costs ~14 ms against the ~35 ms a whole
seed takes, which is why `--spawn` is expensive — but a result is rare enough that
computing it only when there is a line to print costs the search nothing measurable.
`--spawn` has already paid for it and the value is reused.

## `--update=<minutes>`

The progress line was hardcoded to once a minute, which is too chatty for a run you
leave overnight and too quiet for a five-minute check.

```
java -jar sugarcane.jar search 1 1000000 6 24 5 --update=15
```

Fractions work (`--update=0.25`). It is a flag, not a position, so it can go
anywhere and does not disturb the mode slot. Default is still 1 minute.

The line itself also now reports progress through the seed range, not just totals:

```
[15.0 min] seeds done ~238/1199, searched 7826 chunks (2593/s), cane 4, stacked 0, tallest 3, currentSeed 239
```

`currentSeed` is what to resume from if you stop a run.

## Known issues in the reporting path

All four are in the new spreadsheet code, and none affect the search itself:

- **"Successfully reported find to spreadsheet" is not proof.** The client checks
  only `statusCode() == 200`, and Apps Script answers a POST with a 302 that Java's
  `HttpClient` follows as a GET to a different host. That second request returns 200
  whether the row was appended, the script threw, or the deployment redirected you
  to a login page. If rows are not appearing, print `response.uri()` and the body —
  a URI on `accounts.google.com` means the deployment's access setting is not
  *Anyone*.
- **Cross-chunk finds are recorded as clean hits.** The `isCrossChunk` argument is
  passed `false` from both call sites.
- **Cross-chunk finds are usually not recorded at all**, because that branch reports
  only when the solo run is 5 or more, which cannot happen while `minHeight` is 5.
- **A find can produce duplicate rows.** One column is printed once per placement
  that lands in it, so the confirmed find on seed 1500050556 reports twice.

## Upgrading

Drop in the new `sugarcane.jar`. Search behaviour, output format aside, is unchanged
from v1.1.0 — same seeds, same hits, same speed.

One thing to watch if you script this: the opt-in prompt reads stdin on every
command, so a run with no terminal attached exits with `NoSuchElementException`.
Pipe it an answer until that is fixed:

```
echo n | java -jar sugarcane.jar search 1 1000000 6 24 5
```
