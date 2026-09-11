# Two pull requests, ready to submit

Both come out of measuring per-assertion coverage on 2026-09-10: 560 assertions,
two engines, and the question "does each one actually detect anything". Twelve
could not - for any release, ever.

They are packaged for the repositories that OWN the assertions rather than kept
here, because that is the only place the check runs before a pack is built.

## 1. `IHTSDO/snomed-release-validation-assertions` - a guard, and one procedure fix

```
si-assertions/.github/scripts/assertion_lint.py
si-assertions/.github/workflows/assertion-lint.yml
si-assertions/fix-inactivated-component-module-proc.patch
```

### The procedure fix

`file-centric-inactivated-component-module-validation-proc.sql` cursors over
every `%_d` table and builds dynamic SQL selecting `t1.id`. **`identifier_d` has
no `id` column** - RF2 identifies those rows by `alternateidentifier`, and the
schema declares `(identifierschemeid, alternateidentifier, effectivetime,
active, moduleid, referencedcomponentid)`. So the procedure dies with "Unknown
column 't1.id'" and the whole assertion reports incomplete, which reads as a
fault in the assertion rather than a table it was never meant to visit.

The cursor now requires an `id` column, tested against `information_schema`
rather than excluding `identifier_d` by name - so a future table that does not
follow the convention is skipped too, which is exactly how this broke.

**Measured:** with the patch applied, MySQL's incomplete count on the fixture
goes from 1 to 0 and that assertion produces a real result. It is the only thing
keeping the international arm at 238 of 239 rather than 239 of 239.

## The linter - all five shapes

```
si-assertions/.github/scripts/assertion_lint.py
si-assertions/.github/workflows/assertion-lint.yml
```

**All 453 `.sql` files in that repository are clean of the can-fire defects**,
including the 93 not in `manifest.xml` - so the linter half of this PR adds a
check that keeps it true rather than fixing anything.

Verified before proposing it:

```
$ python3 ci/assertion_lint.py snomed-release-validation-assertions/scripts
453 assertion file(s) scanned, 0 that cannot fire
```

## 2. The AMT corpus - the guard, twelve fixes, and three to decide

**Updated 2026-09-11: fifteen, not twelve.** Reading the remaining silent
assertions to author content for them turned up two more shapes, both verified
silent in a real run before being called defects:

| shape | files | fixable mechanically? |
|---|---|---|
| `ccsRefset_<FULL>` where the schema says `ccsrefset` | 1 | **yes - one word** |
|---|---|---|
| `NOT f(id, R) AND f(id, R)` as adjacent conjuncts | 2 | **no - report** |
| `where val.typeid = (null)` | 1 | **no - report** |

The two contradictions are both named "Contains all Active <class>s", and the
intent is evident - a concept that QUALIFIES for the refset and is not in it -
but the qualifying half is simply absent from the SQL. What it was meant to be
cannot be recovered from what is there, so these are reported rather than
repaired. Inventing the missing predicate would be inventing a check.

The NULL comparison is worse than inert. `val.typeid = (null)` is never true, so
the guarded branch never fires; but the statement is `A AND B OR C`, so branch C
runs against EVERY concrete value rather than the type the assertion meant to
single out. The guard does not guard.

One note on the contradiction check, because it matters for trusting the linter:
a first version scanned each statement for the same predicate negated somewhere
and plain somewhere else, and flagged two assertions that demonstrably fire -
the same call legitimately appears across an OR, and in separate EXISTS
subqueries. Two false positives out of four findings is how a linter gets
switched off. It now requires the two calls to be separated by nothing but
`AND`, which cannot span an OR or a subquery boundary, and both real defects
have exactly that shape. Re-verified: **15 flagged, 0 of them fire.**

## The original twelve, all fixable

```
amt/.github/scripts/assertion_lint.py
amt/.github/workflows/assertion-lint.yml
amt/fix-unfireable-assertions.patch      # 12 files, one line each
```

Apply with `git apply --directory=<wherever scripts/ lives>` after checking the
paths in the patch header, or regenerate from the corpus with
`ci/assertion_fix_unfireable.py`, which is idempotent and prints what it
changed.

### The four shapes, and why each replacement is what the assertion meant

| shape | files | fix |
|---|---|---|
| `not exists(select get_cr_ADRS_PT(X) = BINARY 'name')` | 7 | `coalesce(get_cr_ADRS_PT(X), '') <> BINARY 'name'` |
| `not exists(select count(1) from T)` | 3 | `not exists(select 1 from T)` |
| `not exists(select isChildOf_cr(a, b))` | 1 | `isChildOf_cr(a, b) = 0` |
| `not exists(select TRUNCATE(get_cr_PercentDefined(X),1))` | 1 | `TRUNCATE(get_cr_PercentDefined(X),1) <> 99.8` |

All four turn on one SQL fact: **a `SELECT` that reads no rows still returns a
row.** Wrapping a comparison or a function call in `EXISTS` asks whether it HAS
a result, and it always does, so `NOT EXISTS` is a constant false.

* The `COUNT` three are the sharpest: `COUNT` with no `GROUP BY` returns a row
  even over an *empty* table, and all three exist to detect an empty file. Their
  own docstrings say `call TableIsPopulated('rf2_cr_ccsRefset_full')`, so the
  intent is not in doubt.
* `COALESCE` in the first fix is not decoration. The port returns NULL when the
  concept has no ADRS preferred term, and `NULL <> 'name'` is NULL, so without
  it the assertion would still miss the most likely way for it to be wrong: the
  term absent altogether.
* `99.8` is from the assertion's own detail text - `'02 DNF 99.8% Medicinal
  Product Pack are 900000000000073002|Defined|'`. The filename truncates to
  `- 02 DNF 99.sql`, and reading the threshold off the filename would have been
  wrong by 0.8.

### Measured effect, not asserted

Republished the pack from the patched corpus and re-ran the two-engine
comparison against the regression fixture:

```
                        before        after
amtv4 assertions firing  151/200      161/200   (75% -> 80%)
formerly-dead firing        0/12        10/12
MySQL vs DuckDB          254/255      254/255 identical, 0 unexplained, PASS
```

**The two that still do not fire are the evidence the fix is right rather than
merely loud.** They are the `csRefset` and `iRefset` populated-file checks, and
that fixture now carries 9 and 3 rows in those files respectively - so they pass
because the condition is genuinely satisfied. The `ccsRefset` one fires, because
the release ships no ccsRefset file at all. The checks now discriminate, which
is the whole point; before, all three said "pass" regardless.

Parity is unchanged, so none of the twelve fixes introduces an engine
difference.

## Running the linter anywhere

```
assertion_lint.py <dir-or-file> [...]        # exit 1 if any cannot fire
assertion_lint.py --format=github <dir>      # ::error annotations
```

Dependency-free, no database, no schema, no compiled pack - it reads the SQL as
committed. In this repository the same property is pinned over the bundled store
by `AssertionCanFireTest`, which also tests the detector against three healthy
patterns it must leave alone, because a guard that only ever passes proves
nothing.


## A fifth shape, and the sharpest one: table names compared case-sensitively

`ccsRefset_<FULL>` against a schema that declares `ccsrefset_f`. MySQL compares
table names case-sensitively wherever `lower_case_table_names = 0`, the default
on Linux, so the statement dies on a table that does not exist, reports
failureCount -1, and reads as a release that failed to ship a file. A baseline
entry in this project said exactly that for weeks, blaming the release.

It survives because it is invisible almost everywhere else: DuckDB resolves
identifiers case-insensitively, and so does MySQL on macOS and Windows. Only a
Linux MySQL sees it, and only for the one name in 1,107 assertion files that is
spelled differently.

**Measured:** one word, `ccsRefset_<FULL>` to `ccsrefset_<FULL>`, and the AMT
fixture arm goes from 266 of 267 to **267 of 267 identical, 0 divergent**, with
MySQL's incomplete count 1 to 0.

The linter carries the RF2 table names built in, so this check works in a corpus
repository that does not contain the schema; `--ddl` overrides for a schema that
has moved on.
