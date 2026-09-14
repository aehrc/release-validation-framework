# PR A — snomed-query-service
**Title:** `Answer an out-of-range ECL clause with one TermInSetQuery`

MRCM attribute range checks ask which concepts hold a value *outside* the
permitted set, which in ECL is `attributeName != value`. There is no "not one
of these" query here, so the excluded concepts are turned into the ranges
between them, and those ranges are chained together.

Excluding three concepts produces the four ranges around them:

    {* TO 129264002} OR {129264002 TO 360314001}
      OR {360314001 TO 405813007} OR {405813007 TO * }

Each of those is a `TermRangeQuery`. To match one, the classic query parser
builds a state machine that walks the characters of every term in the field
and decides which fall inside the range. A real attribute range excludes
thousands of concepts, so one check builds thousands of these — and the 134
checks built four million.

Measured on an 853 MB AU edition driven through `release-mrcm-validator`'s 134
attribute ranges, single run, 8-core box:

| | before | after |
|---|---|---|
| `TermRangeQuery` instances constructed | 4,004,000 | 0 |
| peak heap held by their state-machine tables | 10.94 GB | — |
| wall clock, 134 ranges, serial | 724.0s | 170.6s |
| results | — | identical on all 134 |

The heap figure is the cost that matters. Each state machine holds its
transition table as `int[][]` — Lucene's `Automaton` — and they are all live at
once while the chain is being built.

This asks the index which values the field actually holds, subtracts the
excluded ones, and puts the remainder in a single `TermInSetQuery`. A term that
is not in the index cannot match anything, so listing the ones that are selects
the same documents — one query, and no state machines at all.

## Supporting changes

- **`conceptsWithAnyAncestor(ids)`** — one term-set query over the ancestor
  field. Proper ancestors, so the queried ids are not returned; javadoc says so.
  Used by a companion PR in `release-mrcm-validator`.
- **Concept id as `NumericDocValues`.** Queries return hundreds of thousands of
  hits and only want the id back. Reading it from a stored field measured
  24.7µs per hit across 3,885,244 hits — 95.8s single-threaded, 86% of it in the
  200 expressions returning more than a hundred hits.
  **An index built by the previous version still reads correctly**: when the
  field is absent `readConceptIds` falls back to the stored field per hit, at
  the old cost. No reindex required.

## How the clause reaches the parser

`ExpressionConstraintToLuceneConverter` returns query *text*, so it cannot build
a `TermInSetQuery` — that is an object, and there is no way to write one down as
a string.

The excluded ids are already in that text, so the clause is replaced by a token
that names them, `260686004:__notin__129264002_360314001`. When the parser
reaches it, `getFieldQuery` is handed both the field and the token, and builds
the complement from the index there. Nothing is held on the side between
building the text and parsing it, and a query may carry as many of these clauses
as it likes — each is resolved against its own field.

`IntegrationTest` pins that this path is actually taken, for every clause of a
multi-clause query. A correctness result proves nothing if the query quietly
fell back to the range chain. `sqs.notin.rangeform` forces the old rendering, so
both forms can be compared against one index in one process.

## A pre-existing bug this exposed

Locating the clause by regex does not work. The pattern is greedy and anchored
at the end, so on a query with two `!=` clauses it spans from one clause into
the other, harvests the second clause's **field id** as though it were an
excluded concept, and collapses both clauses into one — silently answering a
different question.

Clauses are now located by scanning for the balanced closing bracket and
rewritten right to left. Two `!=` clauses on different fields now give the same
answer as each clause does alone; before this they did not.

## Dependencies

**Depended on by:** [Answer the lateralizable domain with one ancestor query](https://github.com/dionmcm/release-mrcm-validator/pull/1) (`release-mrcm-validator`), which uses `conceptsWithAnyAncestor` and cannot compile until this is in a snapshot.

**Merge order:** first of the set.
