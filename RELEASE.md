# v1.0.2 — 1.48x faster, and the bottleneck was a jump table

Performance only. No behaviour changes, no interface changes, still Java 21+, and
every step is bit-exact — the benchmark reports the same chunks generated, chunks
searched and cane columns as v1.0.1 throughout.

**13,279 → 19,608 chunks/s** on 24 threads. Measured interleaved
base/variant/base/variant so machine drift cancels; three pairs, variance under
0.3%.

Compounded with v1.0.1 that is **1.75x since v1.0**.

## The find that made it

Setting out to vectorise the eight gradient dot products per noise sample, the
first step was reading what `MathHelper.grad` actually does. It is not a dot
product. It is a 16-way `tableswitch` where every case is a single add:

```
0: x+y   1: -x+y   2: x-y   3: -x-y     8: y+z    9: -y+z  10: y-z  11: -y-z
4: x+z   5: -x+z   6: x-z   7: -x-z    12: y+x   13: -y+z  14: y-x  15: -y-z
```

That compiles to a jump table indexed by four bits of a permutation value, taken
**eight times per sample**, on an index that is effectively random. It mispredicts
nearly every time, and a mispredict costs an order of magnitude more than the
addition it guards. This was the single largest cost in the search, hiding inside
what the profiler attributed to `Noise.lookup`.

A coefficient table replaces it:

```java
return GX[h] * x + GY[h] * y + GZ[h] * z;
```

**1.42x on its own**, and exact rather than merely equivalent: every case is a sum
of two of the three coordinates with unit signs, multiplying by `1.0` or `-1.0`
reproduces `dload` and `dneg` bit for bit, `a - b` is *defined* as `a + (-b)`, and
the two cases the library writes reversed are commutative in IEEE754. The only
divergence is the unused third coordinate contributing a signed zero, which can
change a result only when the other two terms are both exactly zero at once.

No SIMD was written. It now looks less attractive than it did: what remains per
sample is three dependent permutation gathers and a lerp tree, and gathers are the
thing SIMD does worst.

## Also in this release

**Column hoisting, 1.07x.** `sampleNoiseColumn` asks for 14 cell values at a single
(x, z), and the library redid the whole lattice setup for each. `ColumnPerlin`
computes the y-independent half once per octave per column: the x and z sections,
their fractions and fades, and the two permutation lookups taken before the section
y is added in.

**Tried and rejected**, recorded so nobody repeats it: inverting the loops so each
octave sweeps the column hoists the same work, but moves the per-y accumulator from
a register into an array, and the 224 extra read-modify-writes per column cost more
than the hoisting saves.

## Verification

`TruncatedNoiseTest` compares whole generated columns against TerrainUtils and still
reports **764 columns exact**. All 37 tests pass. The confirmed find at seed
`1500050556` still reports as a five-tall hit.

## Upgrading

Drop in the new `sugarcane.jar`. Nothing else changes.

If you are coming from **v1.0** rather than v1.0.1, note that v1.0.1 carried two
correctness fixes as well: hits are now only reported when a single chunk built the
whole column (v1.0 could report cross-chunk columns that do not reproduce in game),
and `verify.py` was fixed — an NBT parsing bug made it report every world as empty.
See the v1.0.1 notes.

## A note on measurement

The column hoisting above was measured three times as a 10% *regression*, deleted,
and only recovered because the reverted baseline then measured 11,367 chunks/s where
it had measured 14,934 twenty minutes earlier. Minecraft had been launched
mid-session.

Runs minutes apart on a desktop are not comparable. Every figure in this release
comes from alternating base and variant back to back. Absolute numbers under load
are meaningless; ratios survive.
