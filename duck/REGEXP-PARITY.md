# Do the transpiled regexes match the same rows MySQL matches?

Measured 2026-09-09 against the AU edition. **84 calls, 83 identical, 1 that no
row exercised, 0 divergences** - after fixing one real defect the measurement
found, and three defects in the measurement itself.

## Why the assertion-level A/B could not answer this

The nightly gate compares failure COUNTS per assertion. Run the AMT-bearing
corpus against both engines and all 46 regex-bearing assertions agree - and 44
of them agree by **matching nothing on either engine**:

```
REGEXP PARITY   46 assertions carrying a rewritten regex
  both-empty       44
  absent            2
```

Agreement there is a question neither engine was given content to answer. The
AU release simply has no `...`-containing preferred terms and no unspaced
slashes, so no pattern was ever evaluated against a matching row. A gate that
counts this as parity would have shipped the defect below.

So `ci/regexp_oracle.py` + `RegexpOracleProbe` compare the **predicate**: for
every `regexp_matches` the store carries, evaluate it and the MySQL `REGEXP` it
was translated from against the SAME 1,826,331 active terms, and compare the
matched id SETS, not their sizes. Rows are exported from MySQL and read by
DuckDB, so "the engines saw different data" is not available as an explanation.

## The defect it found

`[[:alnum:]]` is **Unicode-aware in MySQL** (ICU) and **ASCII-only in DuckDB**
(RE2). On ` [[:alnum:]]+/[[:alnum:]]+ `:

| engine | rows |
|---|---|
| MySQL | 76,165 |
| DuckDB, `[[:alnum:]]` | 76,164 |
| DuckDB, `[\p{L}\p{N}]` | 76,165 |

The single missing row is `2838949011`, "History of - vertigo/Ménière disease" -
RE2 does not count `è` as alphanumeric. One row today, but the shape is a silent
blind spot: an assertion that checks term formatting stops seeing accented
content, reports nothing, and reads as a pass.

Fixed in the publisher as an AST pass (`_unicode_posix_classes`, carried in
`duck/publisher-fixes.patch`), translating only the classes the corpora use and
**refusing** any other POSIX class rather than passing it through - passing it
through is the failure being fixed. Blast radius was one assertion in the AMT
pack; the bundled international store uses no POSIX classes.

## What was NOT a defect

Three findings that looked like publisher bugs and were bugs in the measurement.
Each produced a confident wrong answer rather than a crash, which is why they
are pinned in `ci/regexp_oracle_selftest.py`:

**Collation is per-operand.** RVF declares `term ... collate utf8_bin`, so
`term REGEXP 'artifact'` does NOT match "Artifact due to freezing" - while the
same pattern against `get_cr_ADRS_PT(id)`, whose return carries the schema
default `utf8mb4_0900_ai_ci`, does. The publisher already gets this right, and
adds `'i'` for exactly those 20 operands and no others. Evaluating every pattern
against the column reported DuckDB matching a superset - 9 extra rows for
`artifact` - which measured the harness.

**MySQL decodes string literals before the regex engine sees them.** `'\\.\\.\\.'`
IS the regex `\.\.\.`. Passing the file's bytes through as a parameter asks for
"backslash then any character", which matches nothing: 15 calls read as
"MySQL 0 vs DuckDB N".

**DuckDB does not.** Backslash is not an escape in a plain single-quoted string,
so MySQL-unescaping the store's literal turned a correct
`\((clinical drug|physical object|product)\)$` into `((clinical drug|physical
object|product))$` - which matches terms ending in the bare word, 11,398 rows
against MySQL's 52,840 - and mangled two S8 patterns into regexes DuckDB refuses
to compile at all. After correct unquoting, **82 of 84 pattern texts are
byte-identical** between source and store; the other two are `\Z` against
`\x1a`, the same character spelled for two engines.

## Running it

Needs a MySQL with a release loaded (`ci/engine_ab_stack.sh` leaves one) and the
test classpath:

    python3 ci/regexp_oracle.py \
      --store /data/work/amt-build/store.json \
      --corpus-scripts /data/work/amt-build/corpus/scripts \
      --assertions /data/work/regexp-assertions.json \
      --pairs-out /data/work/regexp-pairs.json

    java -cp "target/classes:target/test-classes:$(cat /data/work/rvf-cp.txt)" \
      org.ihtsdo.rvf.RegexpOracleProbe \
      /data/work/regexp-pairs.json /data/work/regexp-measured.json \
      "jdbc:mysql://localhost:3307/rvf_au_20260630?user=root&password=rvfpass" \
      description_s /data/work/regexp-terms.tsv

    python3 ci/regexp_oracle.py ... --measured /data/work/regexp-measured.json --gate

`--assertions` is a `uuid -> file` map of the regex-bearing set; regenerate it by
scanning the store for `regexp_matches`. Pairing covers all 46, including the two
whose pattern lives in a stored procedure the publisher unrolls - those are
paired by distinct pattern, since the store holds one call per unrolled site.

Cost: ~11 minutes, dominated by MySQL doing 84 full regex scans of 1.8M rows.
`regexp_time_limit` is raised to 100,000 by the probe; at the default 32 these
patterns error out mid-run.

## Still open

**One pattern no content exercises.** ` [[:alnum:]]+/[[:alnum:]]+ ` has a
partner assertion whose pattern matched nothing on either engine. Reported as
`identical-empty` rather than folded into the pass, because it is not evidence.
Answering it needs either content that exercises it or a synthetic row set.
