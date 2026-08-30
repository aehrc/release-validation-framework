# StructBench - what RVF's structural testing costs, and what it could cost

Structural testing is now the floor on end-to-end validation time: with the
DuckDB engine the assertion phase is 26s while structural testing is 110s, so
even an infinitely fast engine cannot beat ~9.5x end to end.

All of this code is IHTSDO's, untouched by this fork - `RF2FileStructureTester`,
`ColumnPatternTester` and friends are byte-identical to `ihtsdo/develop`.

## What it does today

`RF2FileStructureTester.runTestForFile` makes THREE full passes per file:

| pass | line | purpose |
|---|---|---|
| 1 | 73-75 | `readLine()` loop to count lines |
| 2 | 79-82 | `Scanner` with a CRLF delimiter, counting again |
| 3 | 91-114 | re-open and `readLine()` from 1..totalLine, to inspect the LAST line |

`ColumnPatternTester` then makes a fourth, doing `line.split("\t", -1)` and a
regex `matches()` per field.

## Measured, 594MB / 4,174,475 lines / 37,570,266 fields

    pass 1  count lines              2.39s
    pass 2  Scanner CRLF count       5.16s   <- most expensive, and redundant
    pass 3  re-read for last line    1.09s
    pass 4  split + regex columns    6.84s
    ---------------------------------------
    TOTAL as RVF does it            15.48s
    FUSED one pass, char checks      3.53s
    speedup                          4.39x

Two independent wins:

* **Three of the four passes are redundant.** Line count, CRLF-per-line and the
  last line's terminator are all obtainable in the same pass that validates
  columns. That is 8.64s of 15.48s spent re-reading bytes already read.
* **`split` + regex is ~2x slower than scanning chars.** Same four checks,
  6.84s against 3.53s: `split("\t", -1)` allocates an array and a String per
  field - 37.6M allocations for this file alone - and `^\d{8}$` style patterns
  are trivial character loops. `NOT_BLANK = ^(?=\s*\S).*$` is a lookahead per
  field.

`Scanner` deserves its own mention: 5.16s to count lines that pass 1 had already
counted in 2.39s, because `Scanner` tokenises with a regex engine.

## Honest limits of this measurement

The fused loop implements four representative column checks, not RVF's whole
`ColumnType` matrix, and it does not build report rows. The pass-4-vs-fused
comparison is apples to apples (same four checks); the 4.39x total additionally
assumes passes 1-3 disappear entirely, which requires the last-line and CRLF
checks to be folded into the single pass rather than dropped.

Files are validated in parallel via `Executors.newCachedThreadPool()`, so
wall-clock gain across a release is bounded by the largest file rather than the
sum - which is exactly why the 594MB description file is the one measured here.

## Reproducing

    javac -d /tmp/bench bench/StructBench.java
    java -Xmx4g -cp /tmp/bench StructBench <unpacked-release>/Full/Terminology/sct2_Description_Full-en_*.txt
